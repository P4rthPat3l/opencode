package ai.opencode.p4rth.jetbrains.runtime

import ai.opencode.p4rth.jetbrains.ProductIdentity
import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

data class Sidecar(val process: Process, val url: String, val username: String, val password: String, val product: ProductInfo)

class SidecarProcess(private val paths: RuntimePaths) {
  private val log = Logger.getInstance(SidecarProcess::class.java)
  private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
  private val gson = Gson()

  fun start(executable: Path, workingDirectory: Path?): Sidecar {
    val port = randomLoopbackPort()
    val password = UUID.randomUUID().toString()
    val env = launchEnv(password)
    val process = ProcessBuilder(
      executable.toString(),
      "web",
      "--hostname", "127.0.0.1",
      "--port", port.toString(),
      "--no-open",
      "--no-mdns",
    )
      .redirectErrorStream(false)
      .apply { workingDirectory?.takeIf { Files.isDirectory(it) }?.let { directory(it.toFile()) } }
      .apply { environment().putAll(env) }
      .start()
    try {
      drain(process.inputReader(), false)
      drain(process.errorReader(), true)
      val url = "http://127.0.0.1:$port"
      waitForHealth(process, url, password)
      val product = fetchProduct(url, password)
      val validation = ProductValidator.validate(product)
      require(validation.accepted) { validation.reason ?: "Runtime is incompatible" }
      return Sidecar(process, url, "opencode", password, product)
    } catch (error: Throwable) {
      // Never leak a started process when startup or validation fails: a rejected candidate must
      // die before the caller tries the next runtime.
      runCatching { process.destroyForcibly() }
      throw error
    }
  }

  fun stop(sidecar: Sidecar) {
    sidecar.process.destroy()
    if (!sidecar.process.waitFor(6, TimeUnit.SECONDS)) sidecar.process.destroyForcibly()
  }

  /**
   * Per-launch, process-local environment only.
   *
   * Deliberately does NOT set P4RTH_OPENCODE_*_DIR. Those would split the user's fork
   * profile between the terminal CLI and the IDE. The runtime resolves the shared fork
   * profile from its own branded defaults so both surfaces share one profile by default.
   * A separate profile is only used when the user explicitly configures one (not here).
   */
  internal fun launchEnv(password: String): Map<String, String> {
    return mapOf(
      "OPENCODE_SERVER_USERNAME" to "opencode",
      "OPENCODE_SERVER_PASSWORD" to password,
      "OPENCODE_DISABLE_AUTOUPDATE" to "1",
      "OPENCODE_CLIENT" to "jetbrains",
    )
  }

  private fun waitForHealth(process: Process, url: String, password: String) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
    while (System.nanoTime() < deadline) {
      require(process.isAlive) { "OpenCode sidecar exited during startup" }
      if (runCatching { request(url, "/global/health", password).statusCode() in 200..299 }.getOrDefault(false)) return
      Thread.sleep(150)
    }
    error("OpenCode sidecar did not become healthy within 60 seconds")
  }

  private fun fetchProduct(url: String, password: String): ProductInfo {
    val response = request(url, "/global/product", password)
    require(response.statusCode() in 200..299) { "OpenCode product handshake failed: HTTP ${response.statusCode()}" }
    return gson.fromJson(response.body(), ProductInfo::class.java)
  }

  private fun request(url: String, path: String, password: String): HttpResponse<String> {
    val auth = Base64.getEncoder().encodeToString("opencode:$password".toByteArray(StandardCharsets.UTF_8))
    return client.send(
      HttpRequest.newBuilder(URI.create(url + path))
        .timeout(Duration.ofSeconds(2))
        .header("Authorization", "Basic $auth")
        .GET()
        .build(),
      HttpResponse.BodyHandlers.ofString(),
    )
  }

  private fun drain(reader: BufferedReader, stderr: Boolean) {
    CompletableFuture.runAsync {
      reader.useLines { lines ->
        lines.forEach { line ->
          val safe = SecretRedactor.redact(line)
          if (stderr) log.warn(safe) else log.debug(safe)
        }
      }
    }
  }

  private fun randomLoopbackPort(): Int = ServerSocket(0).use { it.localPort }
}
