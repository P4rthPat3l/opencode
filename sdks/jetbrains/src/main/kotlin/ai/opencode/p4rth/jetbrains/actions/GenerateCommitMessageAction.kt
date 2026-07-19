package ai.opencode.p4rth.jetbrains.actions

import ai.opencode.p4rth.jetbrains.runtime.SidecarHttpClient
import ai.opencode.p4rth.jetbrains.settings.OpenCodeSettings
import ai.opencode.p4rth.jetbrains.ui.OpenCodeIcons
import ai.opencode.p4rth.jetbrains.vcs.CommitMessageGenerator
import ai.opencode.p4rth.jetbrains.vcs.StagedDiffCollector
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.ui.Refreshable
import com.intellij.vcs.commit.CommitWorkflowUi

/**
 * Generates a commit message from the full staged/included diff via the managed OpenCode sidecar.
 * Placed on the commit message toolbar (Vcs.MessageActionGroup).
 *
 * The Commit message toolbar is icon-only, so an explicit OpenCode mark is required or the
 * control appears as a blank button.
 */
class GenerateCommitMessageAction : AnAction(
  "Generate Commit Message with OpenCode",
  "Generate a commit message from the full staged diff using OpenCode",
  OpenCodeIcons.Action,
), DumbAware {
  override fun update(event: AnActionEvent) {
    val presentation = event.presentation
    presentation.icon = OpenCodeIcons.Action
    // Keep the button visible in the Commit UI even before a message field is focused so the
    // OpenCode mark is always discoverable; only disable when there is no project.
    presentation.isVisible = event.project != null
    presentation.isEnabled = event.project != null && resolveCommitMessageTarget(event) != null
  }

  override fun actionPerformed(event: AnActionEvent) {
    val project = event.project ?: return
    val target = resolveCommitMessageTarget(event) ?: return
    val workflowUi = event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
    val existing = readExisting(target, workflowUi)

    // Capture change lists on the EDT — AnActionEvent data context is not reliable later.
    val snapshot = try {
      StagedDiffCollector.snapshot(project, event)
    } catch (error: Throwable) {
      notifyError(project, error.message ?: error.toString())
      return
    }

    workflowUi?.commitMessageUi?.startLoading()

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Generate commit message with OpenCode", true) {
      override fun run(indicator: ProgressIndicator) {
        try {
          indicator.isIndeterminate = true
          indicator.text = "Collecting staged changes…"
          val diff = StagedDiffCollector.materialize(snapshot)
          val generated = CommitMessageGenerator.generate(project, diff, indicator)
          val settings = ApplicationManager.getApplication().getService(OpenCodeSettings::class.java).state
          val next = SidecarHttpClient.applyReplaceMode(settings.commitMessageReplaceMode, existing, generated)
          ApplicationManager.getApplication().invokeLater {
            workflowUi?.commitMessageUi?.stopLoading()
            writeMessage(target, workflowUi, next)
          }
        } catch (error: com.intellij.openapi.progress.ProcessCanceledException) {
          ApplicationManager.getApplication().invokeLater {
            workflowUi?.commitMessageUi?.stopLoading()
          }
          throw error
        } catch (error: Throwable) {
          ApplicationManager.getApplication().invokeLater {
            workflowUi?.commitMessageUi?.stopLoading()
            notifyError(project, error.message ?: error.toString())
          }
        }
      }

      override fun onCancel() {
        workflowUi?.commitMessageUi?.stopLoading()
      }
    })
  }

  private fun notifyError(project: com.intellij.openapi.project.Project, message: String) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("P4rth OpenCode")
      .createNotification("OpenCode commit message", message, NotificationType.ERROR)
      .notify(project)
  }

  private fun resolveCommitMessageTarget(event: AnActionEvent): CommitMessageI? {
    event.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)?.let { return it }
    event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)?.let { return CommitMessageUiAdapter(it) }
    val refreshable = Refreshable.PANEL_KEY.getData(event.dataContext)
    if (refreshable is CommitMessageI) return refreshable
    return null
  }

  private fun readExisting(target: CommitMessageI, workflowUi: CommitWorkflowUi?): String {
    if (workflowUi != null) return workflowUi.commitMessageUi.text
    if (target is CommitMessageUiAdapter) return target.workflowUi.commitMessageUi.text
    return ""
  }

  private fun writeMessage(target: CommitMessageI, workflowUi: CommitWorkflowUi?, text: String) {
    if (workflowUi != null) {
      workflowUi.commitMessageUi.text = text
      return
    }
    target.setCommitMessage(text)
  }

  private class CommitMessageUiAdapter(val workflowUi: CommitWorkflowUi) : CommitMessageI {
    override fun setCommitMessage(message: String) {
      workflowUi.commitMessageUi.text = message
    }
  }
}
