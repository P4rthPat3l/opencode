package ai.opencode.p4rth.jetbrains.runtime

import kotlin.test.Test
import kotlin.test.assertFalse

class SecretRedactorTest {
  @Test
  fun redactsKnownSecretShapes() {
    val text = SecretRedactor.redact("Authorization: Basic abc api_key=sk-verysecretvalue token: abc123")
    assertFalse(text.contains("sk-verysecretvalue"))
    assertFalse(text.contains("Basic abc"))
  }
}
