package ai.opencode.p4rth.jetbrains.runtime

data class RuntimePlatform(val key: String, val executableName: String)

object PlatformDetector {
  fun current(osName: String = System.getProperty("os.name"), archName: String = System.getProperty("os.arch")): RuntimePlatform? {
    val os = when {
      osName.startsWith("Mac", ignoreCase = true) -> "darwin"
      osName.startsWith("Windows", ignoreCase = true) -> "windows"
      osName.startsWith("Linux", ignoreCase = true) -> "linux"
      else -> return null
    }
    val arch = when (archName.lowercase()) {
      "aarch64", "arm64" -> "arm64"
      "x86_64", "amd64" -> "x64"
      else -> return null
    }
    return RuntimePlatform("$os-$arch", if (os == "windows") "p4rth-opencode.exe" else "p4rth-opencode")
  }
}
