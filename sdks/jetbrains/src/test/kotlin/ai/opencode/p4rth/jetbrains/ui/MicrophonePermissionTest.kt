package ai.opencode.p4rth.jetbrains.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MicrophonePermissionTest {
  private val microphone = 1

  @Test
  fun `accepts microphone-only requests from the exact sidecar origin`() {
    assertTrue(MicrophonePermission.accepts("http://127.0.0.1:43123", "http://127.0.0.1:43123", microphone, microphone))
    assertTrue(MicrophonePermission.accepts("http://127.0.0.1:43123", "http://127.0.0.1:43123/session", microphone, microphone))
  }

  @Test
  fun `rejects media requests from other origins`() {
    assertFalse(MicrophonePermission.accepts("http://127.0.0.1:43123", "http://127.0.0.1:43124", microphone, microphone))
    assertFalse(MicrophonePermission.accepts("http://127.0.0.1:43123", "http://localhost:43123", microphone, microphone))
    assertFalse(MicrophonePermission.accepts("http://127.0.0.1:43123", "not a URL", microphone, microphone))
  }

  @Test
  fun `rejects camera and combined media requests`() {
    assertFalse(MicrophonePermission.accepts("http://127.0.0.1:43123", "http://127.0.0.1:43123", 2, microphone))
    assertFalse(MicrophonePermission.accepts("http://127.0.0.1:43123", "http://127.0.0.1:43123", 3, microphone))
  }
}
