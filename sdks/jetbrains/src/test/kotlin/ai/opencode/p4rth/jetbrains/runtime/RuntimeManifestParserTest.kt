package ai.opencode.p4rth.jetbrains.runtime

import ai.opencode.p4rth.jetbrains.ProductIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RuntimeManifestParserTest {
  @Test
  fun selectsHttpsArtifactForCurrentProduct() {
    val manifest = RuntimeManifestParser.parse(
      """
      {
        "pluginVersion":"1.18.3",
        "runtimeVersion":"1.18.3",
        "product":"${ProductIdentity.productId}",
        "protocolVersion":1,
        "jetbrainsBridgeVersion":1,
        "storageSchemaVersion":1,
        "artifacts":{"linux-x64":{"url":"https://example.com/runtime.zip","sha256":"${"a".repeat(64)}"}}
      }
      """.trimIndent(),
    )

    assertEquals("https://example.com/runtime.zip", RuntimeManifestParser.artifactFor(manifest, RuntimePlatform("linux-x64", "p4rth-opencode")).url)
  }

  @Test
  fun rejectsOfficialProductManifest() {
    val manifest = RuntimeManifest(
      pluginVersion = "1.18.3",
      runtimeVersion = "1.18.3",
      product = "opencode",
      protocolVersion = 1,
      jetbrainsBridgeVersion = 1,
      storageSchemaVersion = 1,
      artifacts = mapOf("linux-x64" to RuntimeArtifact("https://example.com/runtime.zip", "a".repeat(64))),
    )

    assertFailsWith<IllegalArgumentException> {
      RuntimeManifestParser.artifactFor(manifest, RuntimePlatform("linux-x64", "p4rth-opencode"))
    }
  }
}
