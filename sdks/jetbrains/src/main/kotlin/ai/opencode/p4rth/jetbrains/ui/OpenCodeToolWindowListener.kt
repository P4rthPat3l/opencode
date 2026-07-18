package ai.opencode.p4rth.jetbrains.ui

import ai.opencode.p4rth.jetbrains.project.OpenCodeProjectService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener

/**
 * Forwards OpenCode Tool Window show/hide transitions to the project service so the panel
 * can dispose its JCEF browser after an idle period while hidden and recreate it on reopen.
 */
class OpenCodeToolWindowListener(private val project: Project) : ToolWindowManagerListener {
  private var lastVisible: Boolean? = null

  override fun stateChanged(toolWindowManager: ToolWindowManager) {
    val toolWindow = toolWindowManager.getToolWindow("OpenCode") ?: return
    val visible = toolWindow.isVisible
    if (visible == lastVisible) return
    lastVisible = visible
    project.service<OpenCodeProjectService>().onToolWindowVisibility(visible)
  }
}
