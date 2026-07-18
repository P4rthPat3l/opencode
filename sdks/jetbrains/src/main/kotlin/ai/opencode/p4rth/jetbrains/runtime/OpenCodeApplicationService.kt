package ai.opencode.p4rth.jetbrains.runtime

import ai.opencode.p4rth.jetbrains.settings.OpenCodeSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Application-level owner of the single plugin-managed OpenCode sidecar.
 *
 * One sidecar is shared by every open project in this IDE process. It is started lazily
 * on first Tool Window activation, kept alive while any browser consumer is attached, and
 * stopped after an idle period once the last consumer detaches. Only the exact process
 * started here is ever stopped — never by name, never official OpenCode.
 */
class OpenCodeApplicationService : Disposable {
  private val log = Logger.getInstance(OpenCodeApplicationService::class.java)
  private val paths = RuntimePaths.jetBrainsSystem()
  private val installer = RuntimeInstaller(paths)
  private val process = SidecarProcess(paths)
  private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
    Thread(runnable, "p4rth-opencode-idle").apply { isDaemon = true }
  }

  @Volatile private var sidecar: Sidecar? = null
  private var starting: CompletableFuture<Sidecar>? = null
  private var consumers = 0
  private var idleStop: ScheduledFuture<*>? = null

  fun prepare(project: Project): CompletableFuture<Sidecar> {
    sidecar?.takeIf { it.process.isAlive }?.let { return CompletableFuture.completedFuture(it) }
    synchronized(this) {
      cancelIdleStop()
      starting?.let { return it }
      val future = CompletableFuture<Sidecar>()
      starting = future
      ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Preparing OpenCode", true) {
        override fun run(indicator: ProgressIndicator) {
          try {
            val settings = ApplicationManager.getApplication().getService(OpenCodeSettings::class.java).state
            indicator.text = "Preparing OpenCode…"
            val executable = installer.resolveRuntime(settings.manifestUrl, settings.externalRuntimePath, indicator)
            indicator.text = "Starting OpenCode…"
            val current = process.start(executable, project.basePath?.let { Path.of(it) })
            sidecar = current
            future.complete(current)
          } catch (error: Throwable) {
            future.completeExceptionally(error)
          } finally {
            synchronized(this@OpenCodeApplicationService) { starting = null }
          }
        }
      })
      return future
    }
  }

  /** A live browser is attached. Keeps the sidecar running. */
  @Synchronized
  fun registerConsumer() {
    consumers++
    cancelIdleStop()
  }

  /** A browser was disposed. Starts the idle timer when none remain. */
  @Synchronized
  fun unregisterConsumer() {
    if (consumers > 0) consumers--
    if (consumers == 0) scheduleIdleStop()
  }

  private fun scheduleIdleStop() {
    cancelIdleStop()
    val minutes = runCatching {
      ApplicationManager.getApplication().getService(OpenCodeSettings::class.java).state.sidecarIdleMinutes
    }.getOrDefault(20).coerceAtLeast(1)
    idleStop = scheduler.schedule({ stopIfIdle() }, minutes.toLong(), TimeUnit.MINUTES)
  }

  private fun cancelIdleStop() {
    idleStop?.cancel(false)
    idleStop = null
  }

  private fun stopIfIdle() {
    synchronized(this) {
      idleStop = null
      if (consumers > 0) return
    }
    stopIfOwned()
  }

  fun stopIfOwned() {
    val current = synchronized(this) { sidecar.also { sidecar = null } } ?: return
    runCatching { process.stop(current) }.onFailure { log.warn("Failed to stop OpenCode sidecar", it) }
  }

  override fun dispose() {
    synchronized(this) { cancelIdleStop() }
    scheduler.shutdownNow()
    stopIfOwned()
  }
}
