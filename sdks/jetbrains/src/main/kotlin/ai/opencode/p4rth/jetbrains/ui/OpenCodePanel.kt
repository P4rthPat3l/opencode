package ai.opencode.p4rth.jetbrains.ui

import ai.opencode.p4rth.jetbrains.ProductIdentity
import ai.opencode.p4rth.jetbrains.project.IdeContext
import ai.opencode.p4rth.jetbrains.project.OpenCodeProjectService
import ai.opencode.p4rth.jetbrains.runtime.OpenCodeApplicationService
import ai.opencode.p4rth.jetbrains.settings.OpenCodeSettings
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.Alarm
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefAuthCallback
import org.cef.callback.CefMediaAccessCallback
import org.cef.callback.CefMediaAccessCallback.MediaPermissionFlags
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefPermissionHandler
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import org.cef.network.CefRequest.TransitionType
import java.awt.BorderLayout
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

class OpenCodePanel(private val project: Project) : Disposable {
  val component = JPanel(BorderLayout())
  private val gson = Gson()
  private val service = ApplicationManager.getApplication().getService(OpenCodeApplicationService::class.java)
  private val disposeAlarm = Alarm(this)
  private var browser: JBCefBrowser? = null
  private var query: JBCefJSQuery? = null
  private var ready = false
  private val pending = mutableListOf<IdeContext>()
  @Volatile private var activity = BrowserActivityState()

  init {
    showStatus("Preparing OpenCode…")
    prepare()
  }

  fun addContext(context: IdeContext) {
    // Always buffer until the web host bridge has called notifyReady. Delivering earlier is a
    // silent no-op when window.__P4RTH_OPENCODE_IDE_HOST__ is not mounted yet.
    if (!ready || browser == null) {
      pending += context
      prepare()
      return
    }
    deliverContext(context)
  }

  /**
   * Push context into the embedded page. Retries briefly if the web bridge is mid-remount
   * (SPA route change), so a selection is not lost during a short gap after notifyReady.
   */
  private fun deliverContext(context: IdeContext) {
    val payload = gson.toJson(context)
    val web = ProductIdentity.webObject
    browser?.cefBrowser?.executeJavaScript(
      """
      (function(ctx) {
        function attempt(n) {
          var host = window.$web;
          if (host && typeof host.addContext === 'function') {
            host.addContext(ctx);
            return;
          }
          if (n < 40) setTimeout(function() { attempt(n + 1); }, 50);
        }
        attempt(0);
      })($payload);
      """.trimIndent(),
      browser?.cefBrowser?.url,
      0,
    )
  }

  /** Tool Window shown: keep the browser alive and recreate it if it was disposed while hidden. */
  fun onShown() {
    disposeAlarm.cancelAllRequests()
    if (browser == null) prepare()
  }

  /**
   * Reconnect after the application-level sidecar was restarted (e.g. via the settings
   * "Restart OpenCode Server" action). No-op if this panel has no live browser: it is already
   * idle/hidden and will start fresh against the new sidecar the next time it is shown.
   */
  fun reload() {
    if (browser == null) return
    disposeBrowser()
    showStatus("Restarting OpenCode…")
    prepare()
  }

  /** Tool Window hidden: dispose the browser after the idle period to free JCEF memory. */
  fun onHidden() {
    disposeAlarm.cancelAllRequests()
    disposeAlarm.addRequest(::maybeDisposeIdleBrowser, browserIdleMillis())
  }

  /**
   * Idle timeout elapsed while hidden. Dispose only when no OpenCode activity is in progress
   * (streaming, tool, permission, auth, file op, or voice). While active, keep the browser and
   * re-check shortly so long operations are never interrupted mid-flight.
   */
  private fun maybeDisposeIdleBrowser() {
    if (browser == null) return
    if (BrowserActivity.mayDispose(idleElapsed = true, state = activity)) {
      disposeBrowser()
    } else {
      disposeAlarm.cancelAllRequests()
      disposeAlarm.addRequest(::maybeDisposeIdleBrowser, ACTIVITY_RECHECK_MS)
    }
  }

  private fun browserIdleMillis(): Long {
    val minutes = runCatching {
      ApplicationManager.getApplication().getService(OpenCodeSettings::class.java).state.browserIdleMinutes
    }.getOrDefault(10).coerceAtLeast(1)
    return minutes.toLong() * 60_000L
  }

  private fun prepare() {
    service.prepare(project).whenComplete { sidecar, error ->
      SwingUtilities.invokeLater {
        if (error != null) {
          showError(error.message ?: "OpenCode failed to start")
          return@invokeLater
        }
        project.service<OpenCodeProjectService>().onSidecarReady(sidecar)
        if (!JBCefApp.isSupported()) {
          showFallback(sidecar.url)
          return@invokeLater
        }
        loadBrowser(sidecar.url, sidecar.username, sidecar.password, project.basePath)
      }
    }
  }

  private fun loadBrowser(url: String, username: String, password: String, directory: String?) {
    if (browser != null) return
    val browser = JBCefBrowser()
    this.browser = browser
    service.registerConsumer()
    val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
    this.query = query
    val sidecarUri = URI.create(url)
    // Inject Basic Auth on every request to the loopback sidecar so the server never returns 401.
    // Relying on CefRequestHandler.getAuthCredentials is not enough: CEF only reliably invokes it
    // for proxy auth, so a server auth challenge on the top-level load shows the native login dialog.
    val authHeader = "Basic " + Base64.getEncoder()
      .encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))
    val sidecarResourceHandler = object : CefResourceRequestHandlerAdapter() {
      override fun onBeforeResourceLoad(cefBrowser: CefBrowser?, frame: CefFrame?, request: CefRequest): Boolean {
        request.setHeaderByName("Authorization", authHeader, true)
        return false
      }
    }
    query.addHandler { message ->
      if (message == "ready") {
        ready = true
        val items = pending.toList()
        pending.clear()
        // deliverContext (not addContext) so we do not re-queue if ready flips mid-flush.
        items.forEach(::deliverContext)
        return@addHandler JBCefJSQuery.Response("")
      }
      runCatching { handleBridgeMessage(message).orEmpty() }.fold(
        onSuccess = { JBCefJSQuery.Response(it) },
        onFailure = { JBCefJSQuery.Response(null, 1, it.message ?: "IDE bridge request failed") },
      )
    }
    browser.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
      override fun getResourceRequestHandler(
        cefBrowser: CefBrowser?,
        frame: CefFrame?,
        request: CefRequest,
        isNavigation: Boolean,
        isDownload: Boolean,
        requestInitiator: String?,
        disableDefaultHandling: BoolRef?,
      ): CefResourceRequestHandler? {
        return if (request.url.startsWith(url)) sidecarResourceHandler else null
      }

      override fun getAuthCredentials(
        cefBrowser: CefBrowser,
        originUrl: String,
        isProxy: Boolean,
        host: String,
        port: Int,
        realm: String,
        scheme: String,
        callback: CefAuthCallback,
      ): Boolean {
        if (isProxy || host != sidecarUri.host || port != sidecarUri.port) return false
        callback.Continue(username, password)
        return true
      }
    }, browser.cefBrowser)
    val microphoneAllowed = AtomicBoolean(false)
    val microphonePromptOpen = AtomicBoolean(false)
    browser.jbCefClient.addPermissionHandler(object : CefPermissionHandler {
      override fun onRequestMediaAccessPermission(
        cefBrowser: CefBrowser,
        frame: CefFrame,
        requestingUrl: String,
        requestedPermissions: Int,
        callback: CefMediaAccessCallback,
      ): Boolean {
        val microphone = MediaPermissionFlags.DEVICE_AUDIO_CAPTURE
        if (!MicrophonePermission.accepts(url, requestingUrl, requestedPermissions, microphone)) {
          callback.Cancel()
          return true
        }
        if (microphoneAllowed.get()) {
          callback.Continue(microphone)
          return true
        }
        if (!microphonePromptOpen.compareAndSet(false, true)) {
          callback.Cancel()
          return true
        }
        ApplicationManager.getApplication().invokeLater {
          if (this@OpenCodePanel.browser !== browser || project.isDisposed) {
            microphonePromptOpen.set(false)
            callback.Cancel()
            return@invokeLater
          }
          val allowed = Messages.showYesNoDialog(
            project,
            "Allow OpenCode to use your microphone for voice dictation in this panel?\n\n" +
              "Microphone access is used only for local voice dictation.",
            "OpenCode Microphone Access",
            "Allow",
            "Don't Allow",
            Messages.getQuestionIcon(),
          ) == Messages.YES
          microphonePromptOpen.set(false)
          if (this@OpenCodePanel.browser !== browser || project.isDisposed) {
            callback.Cancel()
            return@invokeLater
          }
          microphoneAllowed.set(allowed)
          if (allowed) callback.Continue(microphone) else callback.Cancel()
        }
        return true
      }
    }, browser.cefBrowser)
    browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
      override fun onLoadStart(cefBrowser: CefBrowser, frame: CefFrame, transitionType: TransitionType) {
        // Full navigations tear down the web host bridge. Until it remounts and notifyReady
        // runs again, queue context instead of executeJavaScript against a missing object.
        if (frame.isMain) ready = false
      }

      override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
        if (frame.isMain) injectBridge(browser, query, url)
      }
    }, browser.cefBrowser)
    browser.loadURL(directory?.let { directoryUrl(url, it) } ?: url)
    component.removeAll()
    component.add(browser.component, BorderLayout.CENTER)
    component.revalidate()
    component.repaint()
  }

  private fun showStatus(text: String) {
    component.removeAll()
    component.add(JLabel(text), BorderLayout.CENTER)
  }

  private fun injectBridge(browser: JBCefBrowser, query: JBCefJSQuery, url: String) {
    // injectBridge runs on onLoadEnd, which is typically *after* deferred SPA modules have
    // already mounted IdeHostPromptBridge and called notifyReady once. If that first call
    // happened before this object existed, ready stayed false and Add Selection queued forever.
    // After installing the host object, re-signal ready when the web bridge is already present.
    browser.cefBrowser.executeJavaScript(
      """
      window.${ProductIdentity.bridgeObject} = {
        version: ${ProductIdentity.jetbrainsBridgeVersion},
        notifyReady: function() { ${query.inject("'ready'")} },
        openFile: function(request) { ${query.inject("JSON.stringify({type:'openFile', request: request})")} },
        reportActivity: function(state) { ${query.inject("JSON.stringify({type:'activity', state: state})")} },
        getContext: function(options) {
          return new Promise(function(resolve, reject) {
            ${query.inject(
              "JSON.stringify({type:'getContext', options: options || {}})",
              "function(response) { resolve(JSON.parse(response)); }",
              "function(code, message) { reject(new Error(message || ('IDE context failed: ' + code))); }",
            )}
          });
        }
      };
      if (window.${ProductIdentity.webObject}) {
        window.${ProductIdentity.bridgeObject}.notifyReady();
      }
      """.trimIndent(),
      url,
      0,
    )
  }

  private fun directoryUrl(url: String, directory: String): String {
    val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(directory.toByteArray(StandardCharsets.UTF_8))
    return "$url/$encoded/session"
  }

  private fun showError(text: String) {
    val retry = JButton("Retry")
    retry.addActionListener { prepare() }
    component.removeAll()
    component.add(JLabel(text), BorderLayout.CENTER)
    component.add(retry, BorderLayout.SOUTH)
    component.revalidate()
    component.repaint()
  }

  private fun showFallback(url: String) {
    component.removeAll()
    component.add(JLabel("JCEF is unavailable. OpenCode is running at $url"), BorderLayout.CENTER)
    component.revalidate()
    component.repaint()
  }

  private fun handleBridgeMessage(message: String): String? {
    val json = runCatching { JsonParser.parseString(message).asJsonObject }.getOrNull() ?: return null
    return when (json["type"]?.asString) {
      "openFile" -> {
        handleOpenFile(json)
        null
      }
      "activity" -> {
        handleActivity(json)
        null
      }
      "getContext" -> getContext(json)
      else -> null
    }
  }

  private fun getContext(json: com.google.gson.JsonObject): String {
    val options = json["options"]?.asJsonObject
    val read = {
      project.service<OpenCodeProjectService>().context(
        includeSelection = options?.get("includeSelection")?.asBoolean ?: false,
        includeOpenFiles = options?.get("includeOpenFiles")?.asBoolean ?: false,
        includeCaret = options?.get("includeCaret")?.asBoolean ?: false,
      )
    }
    if (ApplicationManager.getApplication().isDispatchThread) return gson.toJson(read())
    lateinit var context: IdeContext
    ApplicationManager.getApplication().invokeAndWait { context = read() }
    return gson.toJson(context)
  }

  private fun handleActivity(json: com.google.gson.JsonObject) {
    // Conservative: a malformed payload leaves the previous activity untouched so an active
    // operation is never mistaken for idle.
    val state = json["state"] ?: return
    runCatching { gson.fromJson(state, BrowserActivityState::class.java) }.getOrNull()?.let { activity = it }
  }

  private fun handleOpenFile(json: com.google.gson.JsonObject) {
    val request = json["request"]?.asJsonObject ?: return
    val requestedPath = request["path"]?.asString ?: return
    val base = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() } ?: return
    val target = base.resolve(requestedPath).normalize()
    if (!target.startsWith(base)) return
    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target) ?: return
    val line = (request["line"]?.asInt ?: 1).coerceAtLeast(1) - 1
    val column = (request["column"]?.asInt ?: 1).coerceAtLeast(1) - 1
    OpenFileDescriptor(project, file, line, column).navigate(true)
  }

  private fun disposeBrowser() {
    val current = browser ?: return
    // Best-effort: ask the page to stop microphone tracks and release bridge state before the
    // browser is torn down. Disposing the JBCefBrowser also destroys the page, which releases
    // any getUserMedia tracks; this call makes the intent explicit for graceful voice teardown.
    runCatching {
      current.cefBrowser.executeJavaScript(
        "window.${ProductIdentity.webObject}?.releaseForHostDispose?.()",
        current.cefBrowser.url,
        0,
      )
    }
    browser = null
    query = null
    ready = false
    activity = BrowserActivityState()
    current.dispose()
    service.unregisterConsumer()
    showStatus("OpenCode is paused. Reopen the tool window to resume.")
    component.revalidate()
    component.repaint()
  }

  override fun dispose() {
    disposeAlarm.cancelAllRequests()
    disposeBrowser()
  }

  private companion object {
    // While an operation is active at the idle deadline, re-check this often instead of disposing.
    const val ACTIVITY_RECHECK_MS = 30_000L
  }
}
