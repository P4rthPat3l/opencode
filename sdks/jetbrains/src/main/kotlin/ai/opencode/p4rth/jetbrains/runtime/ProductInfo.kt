package ai.opencode.p4rth.jetbrains.runtime

import ai.opencode.p4rth.jetbrains.ProductIdentity

data class ProductInfo(
  val product: String,
  val runtimeVersion: String,
  val upstreamVersion: String,
  val protocolVersion: Int,
  val webVersion: String,
  val runtimeChannel: String,
  val jetbrainsBridgeVersion: Int,
  val storageSchemaVersion: Int,
  val capabilities: List<String>,
)

data class ProductValidationResult(val accepted: Boolean, val reason: String? = null)

object ProductValidator {
  fun validate(info: ProductInfo): ProductValidationResult {
    if (info.product != ProductIdentity.productId) return ProductValidationResult(false, "Unexpected runtime product: ${info.product}")
    if (info.protocolVersion != ProductIdentity.protocolVersion) return ProductValidationResult(false, "Unsupported protocol version: ${info.protocolVersion}")
    if (info.jetbrainsBridgeVersion != ProductIdentity.jetbrainsBridgeVersion) return ProductValidationResult(false, "Unsupported JetBrains bridge version: ${info.jetbrainsBridgeVersion}")
    if (info.storageSchemaVersion != ProductIdentity.storageSchemaVersion) return ProductValidationResult(false, "Unsupported storage schema version: ${info.storageSchemaVersion}")
    if (info.runtimeChannel != ProductIdentity.runtimeChannel) return ProductValidationResult(false, "Unsupported runtime channel: ${info.runtimeChannel}")
    val missing = ProductIdentity.requiredCapabilities - info.capabilities.toSet()
    if (missing.isNotEmpty()) return ProductValidationResult(false, "Runtime is missing capabilities: ${missing.joinToString()}")
    return ProductValidationResult(true)
  }
}
