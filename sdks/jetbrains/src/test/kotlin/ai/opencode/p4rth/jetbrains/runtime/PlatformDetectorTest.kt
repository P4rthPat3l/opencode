package ai.opencode.p4rth.jetbrains.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlatformDetectorTest {
  @Test
  fun detectsSupportedPlatforms() {
    assertEquals("darwin-arm64", PlatformDetector.current("Mac OS X", "aarch64")?.key)
    assertEquals("linux-x64", PlatformDetector.current("Linux", "amd64")?.key)
    assertEquals("windows-x64", PlatformDetector.current("Windows 11", "x86_64")?.key)
  }

  @Test
  fun rejectsUnsupportedArchitecture() {
    assertNull(PlatformDetector.current("Linux", "riscv64"))
  }
}
