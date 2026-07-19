package ai.opencode.p4rth.jetbrains.runtime

import com.intellij.openapi.progress.ProgressIndicator
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isExecutable
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText

class RuntimeInstaller(private val paths: RuntimePaths) {
  private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
  private val maxArtifactBytes = 350L * 1024L * 1024L

  /** Branded fork binaries already installed on the host. Validated by the sidecar handshake before use. */
  fun detectedRuntimes(): List<Path> = RuntimeDetector.detect()

  /** Reuse a previously downloaded managed runtime, otherwise download it. */
  fun resolveManagedRuntime(settingsManifestUrl: String, indicator: ProgressIndicator): Path {
    val platform = PlatformDetector.current() ?: error("Unsupported operating system or CPU architecture")
    val active = paths.activeFile.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    if (active != null && active.exists()) return active

    indicator.text = "Downloading OpenCode runtime manifest…"
    val manifest = RuntimeManifestParser.parse(downloadText(settingsManifestUrl, indicator))
    val artifact = RuntimeManifestParser.artifactFor(manifest, platform)
    val installRoot = paths.runtimes.resolve(manifest.runtimeVersion).resolve(platform.key)
    val executable = installRoot.resolve(platform.executableName)
    if (executable.exists()) {
      paths.activeFile.writeText(executable.toString())
      return executable
    }

    indicator.text = "Downloading OpenCode runtime…"
    val archive = paths.downloads.resolve("runtime-${manifest.runtimeVersion}-${platform.key}.zip.tmp")
    downloadFile(artifact, archive, indicator)
    verifySha256(archive, artifact.sha256)

    indicator.text = "Installing OpenCode runtime…"
    val tempRoot = paths.runtimes.resolve(".install-${System.nanoTime()}")
    tempRoot.createDirectories()
    safeExtractZip(archive, tempRoot)
    archive.deleteIfExists()
    installRoot.parent.createDirectories()
    Files.move(tempRoot, installRoot, StandardCopyOption.ATOMIC_MOVE)
    makeExecutable(executable)
    installRoot.resolve(if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "opencode-speech.exe" else "opencode-speech")
      .takeIf { it.exists() }
      ?.let(::makeExecutable)
    paths.activeFile.writeText(executable.toString())
    return executable
  }

  private fun downloadText(url: String, indicator: ProgressIndicator): String {
    require(URI(url).scheme == "https") { "Runtime manifest URL must use HTTPS" }
    val response = client.send(HttpRequest.newBuilder(URI(url)).GET().build(), HttpResponse.BodyHandlers.ofString())
    indicator.checkCanceled()
    require(response.statusCode() in 200..299) { "Failed to download runtime manifest: HTTP ${response.statusCode()}" }
    return response.body()
  }

  private fun downloadFile(artifact: RuntimeArtifact, target: Path, indicator: ProgressIndicator) {
    val expectedSize = artifact.size
    if (expectedSize != null) require(expectedSize <= maxArtifactBytes) { "Runtime artifact is too large" }
    val response = client.send(HttpRequest.newBuilder(URI(artifact.url)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
    require(response.statusCode() in 200..299) { "Failed to download runtime: HTTP ${response.statusCode()}" }
    target.outputStream().use { out ->
      response.body().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
          indicator.checkCanceled()
          val read = input.read(buffer)
          if (read < 0) break
          total += read
          require(total <= maxArtifactBytes) { "Runtime artifact exceeded maximum size" }
          out.write(buffer, 0, read)
        }
      }
    }
  }

  private fun verifySha256(file: Path, expected: String) {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    require(actual.equals(expected, ignoreCase = true)) { "Runtime checksum verification failed" }
  }

  private fun safeExtractZip(archive: Path, destination: Path) {
    ZipInputStream(archive.inputStream()).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        require(!entry.name.contains("\\")) { "Runtime archive contains an invalid path" }
        val target = destination.resolve(entry.name).normalize()
        require(target.startsWith(destination)) { "Runtime archive contains path traversal" }
        if (entry.isDirectory) {
          target.createDirectories()
          continue
        }
        target.parent.createDirectories()
        target.outputStream().use { out -> zip.copyTo(out) }
      }
    }
  }

  private fun makeExecutable(path: Path) {
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true) || path.isExecutable()) return
    path.setPosixFilePermissions(setOf(
      java.nio.file.attribute.PosixFilePermission.OWNER_READ,
      java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
      java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
      java.nio.file.attribute.PosixFilePermission.GROUP_READ,
      java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE,
    ))
  }
}
