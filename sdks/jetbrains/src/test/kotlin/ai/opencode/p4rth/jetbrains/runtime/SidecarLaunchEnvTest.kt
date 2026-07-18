package ai.opencode.p4rth.jetbrains.runtime

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SidecarLaunchEnvTest {
  private fun paths() = RuntimePaths(
    root = Path.of("/tmp/p4rth-opencode"),
    runtimes = Path.of("/tmp/p4rth-opencode/runtimes"),
    activeFile = Path.of("/tmp/p4rth-opencode/active-runtime.txt"),
    manifests = Path.of("/tmp/p4rth-opencode/manifests"),
    downloads = Path.of("/tmp/p4rth-opencode/downloads"),
    tmp = Path.of("/tmp/p4rth-opencode/tmp"),
    diagnostics = Path.of("/tmp/p4rth-opencode/diagnostics"),
  )

  @Test
  fun `launch env never redirects the shared fork profile to JetBrains paths`() {
    val env = SidecarProcess(paths()).launchEnv("secret")
    // The plugin must not split the fork profile between terminal and IDE.
    assertTrue(env.keys.none { it.endsWith("_DIR") }, "launch env must not set any *_DIR override")
    assertFalse(env.containsKey("XDG_DATA_HOME"))
    assertFalse(env.containsKey("XDG_CONFIG_HOME"))
  }

  @Test
  fun `launch env is process-local and disables runtime self-update`() {
    val env = SidecarProcess(paths()).launchEnv("secret")
    assertEquals("secret", env["OPENCODE_SERVER_PASSWORD"])
    assertEquals("1", env["OPENCODE_DISABLE_AUTOUPDATE"])
    assertEquals("jetbrains", env["OPENCODE_CLIENT"])
  }
}
