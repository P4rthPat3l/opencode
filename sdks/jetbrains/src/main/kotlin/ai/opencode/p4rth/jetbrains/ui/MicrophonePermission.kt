package ai.opencode.p4rth.jetbrains.ui

import java.net.URI

object MicrophonePermission {
  fun accepts(sidecarUrl: String, requestingUrl: String, requestedPermissions: Int, microphonePermission: Int): Boolean {
    if (requestedPermissions != microphonePermission) return false
    return runCatching {
      val sidecar = URI.create(sidecarUrl)
      val requesting = URI.create(requestingUrl)
      sidecar.scheme == requesting.scheme &&
        sidecar.host.equals(requesting.host, ignoreCase = true) &&
        port(sidecar) == port(requesting)
    }.getOrDefault(false)
  }

  private fun port(uri: URI): Int {
    if (uri.port != -1) return uri.port
    return if (uri.scheme == "https") 443 else 80
  }
}
