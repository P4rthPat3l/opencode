package ai.opencode.p4rth.jetbrains.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createDirectories
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeDetectorTest {
  @Test
  fun findsBrandedBinaryOnPath() {
    val dir = Files.createTempDirectory("p4rth-detect")
    val binary = executable(dir.resolve("p4rth-opencode"))
    val found = RuntimeDetector.detect(windows = false, env = mapOf("PATH" to dir.toString()), userHome = dir)
    assertTrue(found.contains(binary.toAbsolutePath().normalize()))
  }

  @Test
  fun ignoresOfficialOpencodeBinary() {
    val dir = Files.createTempDirectory("p4rth-detect")
    executable(dir.resolve("opencode"))
    val found = RuntimeDetector.detect(windows = false, env = mapOf("PATH" to dir.toString()), userHome = dir)
    assertEquals(emptyList(), found)
  }

  @Test
  fun findsBrandedBinaryInPerUserBinDirectory() {
    val home = Files.createTempDirectory("p4rth-home")
    val binDir = home.resolve(".local").resolve("bin").also { it.createDirectories() }
    val binary = executable(binDir.resolve("p4rth-opencode"))
    val found = RuntimeDetector.detect(windows = false, env = emptyMap(), userHome = home)
    assertTrue(found.contains(binary.toAbsolutePath().normalize()))
  }

  @Test
  fun findsWindowsExecutable() {
    val dir = Files.createTempDirectory("p4rth-detect")
    val binary = dir.resolve("p4rth-opencode.exe").also { it.writeText("stub") }
    val found = RuntimeDetector.detect(windows = true, env = mapOf("PATH" to dir.toString()), userHome = dir)
    assertTrue(found.contains(binary.toAbsolutePath().normalize()))
  }

  private fun executable(path: Path): Path {
    path.writeText("stub")
    runCatching {
      path.setPosixFilePermissions(
        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_WRITE),
      )
    }
    return path
  }
}
