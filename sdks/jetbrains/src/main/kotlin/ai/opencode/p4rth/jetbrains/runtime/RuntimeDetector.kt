package ai.opencode.p4rth.jetbrains.runtime

import java.io.File
import java.nio.file.Path
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

/**
 * Discovers an already-installed p4rth-opencode fork binary on the host so the plugin can reuse it
 * instead of downloading a managed runtime.
 *
 * Only the branded `p4rth-opencode` executable name is matched. Official OpenCode is named
 * `opencode` and is intentionally never probed, so detection can never launch official OpenCode or
 * touch its storage. A discovered binary is still validated through the product handshake before it
 * is used (defense in depth). When nothing compatible is found, the caller falls back to the
 * managed download.
 *
 * Search order, all platforms: every directory on `PATH`, then a small set of common per-user and
 * system install locations. GUI-launched IDEs often start with a stripped `PATH`, so the extra
 * locations matter (a shell `which p4rth-opencode` is not enough).
 */
object RuntimeDetector {
  fun detect(
    windows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
    env: Map<String, String> = System.getenv(),
    userHome: Path = Path.of(System.getProperty("user.home")),
  ): List<Path> {
    val names = executableNames(windows)
    val found = LinkedHashSet<Path>()
    for (dir in searchDirectories(windows, env, userHome)) {
      for (name in names) {
        val candidate = dir.resolve(name)
        if (isUsable(candidate, windows)) found.add(candidate.toAbsolutePath().normalize())
      }
    }
    return found.toList()
  }

  private fun executableNames(windows: Boolean): List<String> =
    if (windows) listOf("p4rth-opencode.exe", "p4rth-opencode.cmd", "p4rth-opencode.bat", "p4rth-opencode")
    else listOf("p4rth-opencode")

  private fun searchDirectories(windows: Boolean, env: Map<String, String>, userHome: Path): List<Path> {
    val dirs = LinkedHashSet<Path>()
    env["PATH"]?.split(File.pathSeparatorChar)?.forEach { entry ->
      val trimmed = entry.trim()
      if (trimmed.isNotEmpty()) runCatching { dirs.add(Path.of(trimmed)) }
    }
    dirs.addAll(extraDirectories(windows, env, userHome))
    return dirs.toList()
  }

  private fun extraDirectories(windows: Boolean, env: Map<String, String>, userHome: Path): List<Path> {
    val dirs = mutableListOf<Path>()
    if (windows) {
      env["LOCALAPPDATA"]?.let { runCatching { dirs.add(Path.of(it, "Programs")) } }
      env["APPDATA"]?.let { runCatching { dirs.add(Path.of(it, "npm")) } }
    } else {
      dirs.add(Path.of("/usr/local/bin"))
      dirs.add(Path.of("/opt/homebrew/bin"))
      dirs.add(Path.of("/usr/bin"))
    }
    dirs.add(userHome.resolve(".local").resolve("bin"))
    dirs.add(userHome.resolve("bin"))
    dirs.add(userHome.resolve(".bun").resolve("bin"))
    return dirs
  }

  private fun isUsable(path: Path, windows: Boolean): Boolean {
    if (!path.isRegularFile()) return false
    return windows || path.isExecutable()
  }
}
