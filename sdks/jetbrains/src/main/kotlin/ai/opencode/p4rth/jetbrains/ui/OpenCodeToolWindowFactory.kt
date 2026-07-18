package ai.opencode.p4rth.jetbrains.ui

import ai.opencode.p4rth.jetbrains.project.OpenCodeProjectService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

// DumbAware: the chat UI is a web app backed by the local sidecar and needs no IDE indexes,
// so the tool window stays available while the IDE is indexing.
class OpenCodeToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = OpenCodePanel(project)
    project.service<OpenCodeProjectService>().attach(panel)
    val content = ContentFactory.getInstance().createContent(panel.component, "", false)
    content.setDisposer(panel)
    toolWindow.contentManager.addContent(content)
  }
}
