package ai.opencode.p4rth.jetbrains.runtime

import ai.opencode.p4rth.jetbrains.ProductIdentity
import com.intellij.openapi.application.PathManager
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * Plugin-owned runtime management paths under the JetBrains system directory.
 *
 * These are integration/runtime files only: downloaded artifacts, extracted runtimes,
 * the active-runtime pointer, manifest cache, temp files, and plugin diagnostics.
 *
 * The shared p4rth-opencode fork profile (config/data/cache/state/log — auth, sessions,
 * MCP tokens, global config) is NOT owned here. It lives in the user's normal fork
 * profile so the terminal CLI and the plugin share one profile by default. The plugin
 * must not point the runtime at these JetBrains paths for its profile.
 */
data class RuntimePaths(
  val root: Path,
  val runtimes: Path,
  val activeFile: Path,
  val manifests: Path,
  val downloads: Path,
  val tmp: Path,
  val diagnostics: Path,
) {
  companion object {
    fun jetBrainsSystem(): RuntimePaths {
      val root = Path.of(PathManager.getSystemPath(), ProductIdentity.productId)
      return RuntimePaths(
        root = root,
        runtimes = root.resolve("runtimes"),
        activeFile = root.resolve("active-runtime.txt"),
        manifests = root.resolve("manifests"),
        downloads = root.resolve("downloads"),
        tmp = root.resolve("tmp"),
        diagnostics = root.resolve("diagnostics"),
      ).also { paths ->
        listOf(paths.root, paths.runtimes, paths.manifests, paths.downloads, paths.tmp, paths.diagnostics)
          .forEach { it.createDirectories() }
      }
    }
  }
}
