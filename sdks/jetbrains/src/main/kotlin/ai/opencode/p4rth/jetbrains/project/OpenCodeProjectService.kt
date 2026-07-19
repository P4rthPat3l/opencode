package ai.opencode.p4rth.jetbrains.project

import ai.opencode.p4rth.jetbrains.runtime.Sidecar
import ai.opencode.p4rth.jetbrains.ui.OpenCodePanel
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

class OpenCodeProjectService(private val project: Project) : Disposable {
  private var panel: OpenCodePanel? = null
  private val contextProvider = IdeContextProvider(project)
  private var fileRefresh: FileRefreshSubscription? = null

  fun attach(panel: OpenCodePanel) {
    this.panel = panel
  }

  fun onToolWindowVisibility(visible: Boolean) {
    val panel = this.panel ?: return
    if (visible) panel.onShown() else panel.onHidden()
  }

  /** Start (or restart on a new sidecar) the targeted file-refresh subscription for this project. */
  @Synchronized
  fun onSidecarReady(sidecar: Sidecar) {
    if (fileRefresh?.url == sidecar.url) return
    fileRefresh?.dispose()
    fileRefresh = FileRefreshSubscription(project, sidecar).also { it.start() }
  }

  fun addSelection() = addContext(contextProvider.activeFile(includeSelection = true))

  fun addCurrentFile() = addContext(contextProvider.activeFile(includeSelection = false))

  fun addOpenFiles() = addContext(contextProvider.openFiles())

  fun context(includeSelection: Boolean, includeOpenFiles: Boolean, includeCaret: Boolean) =
    contextProvider.context(includeSelection, includeOpenFiles, includeCaret)

  /** Show and focus the chat panel, or hide it when it is already visible and active. */
  fun toggleToolWindow() {
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("OpenCode") ?: return
    if (toolWindow.isVisible && toolWindow.isActive) {
      toolWindow.hide()
      return
    }
    toolWindow.activate(null)
  }

  private fun addContext(context: IdeContext) {
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("OpenCode") ?: return
    // Show (and focus) so ToolWindowFactory can attach the panel on first use, then deliver.
    toolWindow.show {
      panel?.addContext(context)
    }
  }

  override fun dispose() {
    fileRefresh?.dispose()
    fileRefresh = null
    panel = null
  }
}
