package ai.opencode.p4rth.jetbrains.actions

import ai.opencode.p4rth.jetbrains.project.OpenCodeProjectService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware

// DumbAware: reads the active file path via VFS only (no indexes), so it stays usable while
// the IDE is indexing.
class AddCurrentFileToOpenCodeAction : AnAction(), DumbAware {
  override fun actionPerformed(event: AnActionEvent) {
    event.project?.service<OpenCodeProjectService>()?.addCurrentFile()
  }
}
