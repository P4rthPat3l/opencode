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
  const val preferredRuntimeVersion = "1.18.3"
  const val minimumRuntimeVersion = "1.18.3"
  const val maximumRuntimeVersion = "1.18.3"

  val requiredCapabilities = setOf(
    "jetbrains-context",
    "provider-multi-account",
    "provider-account-switching",
  )
}
