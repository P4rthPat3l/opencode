package ai.opencode.p4rth.jetbrains.actions

import ai.opencode.p4rth.jetbrains.project.OpenCodeProjectService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware

// DumbAware: only toggles the Tool Window; no indexes required.
class ToggleOpenCodeToolWindowAction : AnAction(), DumbAware {
  override fun actionPerformed(event: AnActionEvent) {
    event.project?.service<OpenCodeProjectService>()?.toggleToolWindow()
  }
}
