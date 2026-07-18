package ai.opencode.p4rth.jetbrains.ui

/**
 * Lightweight mirror of the web UI activity flags (see IdeActivityState in packages/app).
 * The host keeps only these booleans and never inspects session content. Missing fields in
 * an incoming payload deserialize to false (idle), which is safe because disposal is only
 * ever blocked by a flag that is explicitly true.
 */
data class BrowserActivityState(
  val streaming: Boolean = false,
  val toolRunning: Boolean = false,
  val permissionPending: Boolean = false,
  val authenticationActive: Boolean = false,
  val fileOperationActive: Boolean = false,
  val voiceActive: Boolean = false,
) {
  val active: Boolean
    get() = streaming || toolRunning || permissionPending || authenticationActive || fileOperationActive || voiceActive
}

object BrowserActivity {
  /**
   * The embedded browser may be disposed only when it is hidden, the idle timeout has
   * elapsed, and no activity is in progress. Callers pass the last known activity; an
   * unknown/failed activity read must keep the previous state so an active operation is
   * never interrupted.
   */
  fun mayDispose(idleElapsed: Boolean, state: BrowserActivityState): Boolean = idleElapsed && !state.active
}
