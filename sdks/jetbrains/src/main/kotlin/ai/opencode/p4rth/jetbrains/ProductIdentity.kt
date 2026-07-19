package ai.opencode.p4rth.jetbrains

object ProductIdentity {
  const val productId = "p4rth-opencode"
  const val displayName = "P4rth OpenCode"
  const val envPrefix = "P4RTH_OPENCODE"
  const val protocolVersion = 1
  const val jetbrainsBridgeVersion = 1
  const val storageSchemaVersion = 1
  const val bridgeObject = "__P4RTH_OPENCODE_IDE__"
  const val webObject = "__P4RTH_OPENCODE_IDE_HOST__"
  const val runtimeChannel = "jetbrains-stable"
  const val preferredRuntimeVersion = "2.0.1"
  const val minimumRuntimeVersion = "2.0.0"
  const val maximumRuntimeVersion = "2.0.1"

  val requiredCapabilities = setOf(
    "jetbrains-context",
    "provider-multi-account",
    "provider-account-switching",
  )
}
