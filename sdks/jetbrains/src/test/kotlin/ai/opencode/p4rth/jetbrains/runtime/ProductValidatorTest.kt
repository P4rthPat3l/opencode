package ai.opencode.p4rth.jetbrains.runtime

import ai.opencode.p4rth.jetbrains.ProductIdentity
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProductValidatorTest {
  @Test
  fun acceptsCompatibleForkRuntime() {
    assertTrue(ProductValidator.validate(valid()).accepted)
  }

  @Test
  fun rejectsOfficialRuntimeProduct() {
    assertFalse(ProductValidator.validate(valid(product = "opencode")).accepted)
  }

  @Test
  fun rejectsMissingRequiredCapabilities() {
    assertFalse(ProductValidator.validate(valid(capabilities = listOf("jetbrains-context"))).accepted)
  }

  private fun valid(
    product: String = ProductIdentity.productId,
    capabilities: List<String> = ProductIdentity.requiredCapabilities.toList(),
  ) = ProductInfo(
    product = product,
    runtimeVersion = "1.18.3",
    upstreamVersion = "1.18.3",
    protocolVersion = ProductIdentity.protocolVersion,
    webVersion = "1.18.3",
    runtimeChannel = ProductIdentity.runtimeChannel,
    jetbrainsBridgeVersion = ProductIdentity.jetbrainsBridgeVersion,
    storageSchemaVersion = ProductIdentity.storageSchemaVersion,
    capabilities = capabilities,
  )
}
