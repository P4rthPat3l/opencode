package ai.opencode.p4rth.jetbrains.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "P4rthOpenCodeSettings", storages = [Storage("p4rth-opencode.xml")])
class OpenCodeSettings : PersistentStateComponent<OpenCodeSettings.State> {
  data class State(
    var runtimeMode: String = "managed",
    var externalRuntimePath: String = "",
    var manifestUrl: String = "https://github.com/P4rthPat3l/opencode/releases/latest/download/p4rth-opencode-jetbrains-runtime.json",
    var browserIdleMinutes: Int = 10,
    var sidecarIdleMinutes: Int = 20,
    var diagnosticLogging: Boolean = false,
  )

  private var state = State()

  override fun getState() = state

  override fun loadState(state: State) {
    this.state = state
  }
}
