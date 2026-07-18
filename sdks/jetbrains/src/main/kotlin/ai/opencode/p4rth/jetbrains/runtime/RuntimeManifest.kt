package ai.opencode.p4rth.jetbrains.runtime

import ai.opencode.p4rth.jetbrains.ProductIdentity
import com.google.gson.Gson
import java.net.URI

data class RuntimeArtifact(
  val url: String,
  val sha256: String,
  val size: Long? = null,
)

data class RuntimeManifest(
  val pluginVersion: String,
  val runtimeVersion: String,
  val product: String,
  val protocolVersion: Int,
  val jetbrainsBridgeVersion: Int,
  val storageSchemaVersion: Int,
  val artifacts: Map<String, RuntimeArtifact>,
)

object RuntimeManifestParser {
  private val gson = Gson()

  fun parse(json: String) = gson.fromJson(json, RuntimeManifest::class.java)

  fun artifactFor(manifest: RuntimeManifest, platform: RuntimePlatform): RuntimeArtifact {
    require(manifest.product == ProductIdentity.productId) { "Manifest product mismatch: ${manifest.product}" }
    require(manifest.protocolVersion == ProductIdentity.protocolVersion) { "Manifest protocol mismatch: ${manifest.protocolVersion}" }
    require(manifest.jetbrainsBridgeVersion == ProductIdentity.jetbrainsBridgeVersion) { "Manifest bridge mismatch: ${manifest.jetbrainsBridgeVersion}" }
    require(manifest.storageSchemaVersion == ProductIdentity.storageSchemaVersion) { "Manifest storage mismatch: ${manifest.storageSchemaVersion}" }
    val artifact = manifest.artifacts[platform.key] ?: error("No runtime artifact for ${platform.key}")
    val uri = URI(artifact.url)
    require(uri.scheme == "https") { "Runtime artifact URL must use HTTPS" }
    require(artifact.sha256.matches(Regex("^[a-fA-F0-9]{64}$"))) { "Runtime artifact sha256 is invalid" }
    return artifact
  }
}
