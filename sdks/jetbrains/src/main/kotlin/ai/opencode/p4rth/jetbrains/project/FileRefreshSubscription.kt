package ai.opencode.p4rth.jetbrains.project

import ai.opencode.p4rth.jetbrains.runtime.Sidecar
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

/**
 * Subscribes to `/global/event` for the active sidecar and refreshes only the JetBrains virtual
 * files that OpenCode itself wrote (`file.edited`), scoped to this project. One subscription per
 * project; disposed with the project service.
 */
class FileRefreshSubscription(private val project: Project, private val sidecar: Sidecar) {
  private val log = Logger.getInstance(FileRefreshSubscription::class.java)
  private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
  private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
    Thread(runnable, "p4rth-opencode-file-refresh").apply { isDaemon = true }
  }
  private val pendingPaths = Collections.synchronizedSet(LinkedHashSet<Path>())
  @Volatile private var running = false
  @Volatile private var stream: InputStream? = null
  @Volatile private var flush: ScheduledFuture<*>? = null
  private var worker: Thread? = null

  val url: String get() = sidecar.url

  fun start() {
    if (running) return
    running = true
    worker = Thread({ runLoop() }, "p4rth-opencode-file-events").apply { isDaemon = true }.also { it.start() }
  }

  private fun runLoop() {
    val base = project.basePath ?: return
    val projectBase = Path.of(base)
    val auth = Base64.getEncoder()
      .encodeToString("${sidecar.username}:${sidecar.password}".toByteArray(StandardCharsets.UTF_8))
    while (running) {
      try {
        val response = client.send(
          HttpRequest.newBuilder(URI.create("${sidecar.url}/global/event"))
            .header("Authorization", "Basic $auth")
            .header("Accept", "text/event-stream")
            .GET()
            .build(),
          HttpResponse.BodyHandlers.ofInputStream(),
        )
        if (response.statusCode() !in 200..299) {
          Thread.sleep(2000)
          continue
        }
        val input = response.body()
        stream = input
        input.bufferedReader().use { reader -> readEvents(reader, projectBase) }
      } catch (error: Throwable) {
        if (running) log.debug("OpenCode file-refresh stream ended; will reconnect", error)
      }
      if (running) Thread.sleep(1000)
    }
  }

  private fun readEvents(reader: BufferedReader, projectBase: Path) {
    val data = StringBuilder()
    while (running) {
      val line = reader.readLine() ?: break
      when {
        line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
        line.isEmpty() -> {
          if (data.isNotEmpty()) {
            onData(data.toString(), projectBase)
            data.setLength(0)
          }
        }
      }
    }
  }

  private fun onData(json: String, projectBase: Path) {
    val event = FileRefreshEvents.parse(json) ?: return
    val path = FileRefreshEvents.affectedPath(event, projectBase) ?: return
    pendingPaths.add(path)
    scheduleFlush()
  }

  private fun scheduleFlush() {
    flush?.cancel(false)
    // Debounce closely related writes (e.g. a multi-file patch) into one refresh batch.
    flush = scheduler.schedule({ flushNow() }, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
  }

  private fun flushNow() {
    val batch: List<Path>
    synchronized(pendingPaths) {
      batch = pendingPaths.toList()
      pendingPaths.clear()
    }
    if (batch.isEmpty()) return
    ApplicationManager.getApplication().invokeLater { refreshFiles(batch) }
  }

  private fun refreshFiles(paths: List<Path>) {
    if (project.isDisposed) return
    val fs = LocalFileSystem.getInstance()
    val documents = FileDocumentManager.getInstance()
    val targets = LinkedHashSet<File>()
    for (path in paths) {
      if (path.exists()) {
        targets.add(path.toFile())
        val existing = fs.findFileByNioFile(path)
        if (existing != null && documents.isFileModified(existing)) {
          // Never silently overwrite unsaved editor content. VFS refresh surfaces IntelliJ's
          // native memory-vs-disk conflict for the user to resolve instead of clobbering.
          log.info("OpenCode wrote a file with unsaved IDE changes: $path")
        }
      } else {
        // Deleted or renamed away: refresh the nearest existing parent, never the whole project.
        var parent = path.parent
        while (parent != null && !parent.exists()) parent = parent.parent
        if (parent != null) targets.add(parent.toFile())
      }
    }
    if (targets.isNotEmpty()) fs.refreshIoFiles(targets, true, false, null)
  }

  fun dispose() {
    running = false
    flush?.cancel(false)
    runCatching { stream?.close() }
    scheduler.shutdownNow()
    worker?.interrupt()
  }

  private companion object {
    const val DEBOUNCE_MS = 250L
  }
}
