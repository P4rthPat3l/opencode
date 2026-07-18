package ai.opencode.p4rth.jetbrains.ui

import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserActivityTest {
  private val gson = Gson()

  @Test
  fun `idle browser may be disposed once the idle timeout elapsed`() {
    assertTrue(BrowserActivity.mayDispose(idleElapsed = true, state = BrowserActivityState()))
  }

  @Test
  fun `never dispose before the idle timeout elapsed`() {
    assertFalse(BrowserActivity.mayDispose(idleElapsed = false, state = BrowserActivityState()))
  }

  @Test
  fun `any active flag blocks disposal`() {
    val states = listOf(
      BrowserActivityState(streaming = true),
      BrowserActivityState(toolRunning = true),
      BrowserActivityState(permissionPending = true),
      BrowserActivityState(authenticationActive = true),
      BrowserActivityState(fileOperationActive = true),
      BrowserActivityState(voiceActive = true),
    )
    for (state in states) {
      assertFalse(BrowserActivity.mayDispose(idleElapsed = true, state = state), "active=$state must block disposal")
    }
  }

  @Test
  fun `partial activity payload deserializes missing flags to idle`() {
    val state = gson.fromJson("{\"streaming\":true}", BrowserActivityState::class.java)
    assertTrue(state.streaming)
    assertFalse(state.voiceActive)
    assertTrue(state.active)
  }
}
