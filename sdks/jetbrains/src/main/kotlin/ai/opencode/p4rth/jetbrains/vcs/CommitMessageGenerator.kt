package ai.opencode.p4rth.jetbrains.vcs

import ai.opencode.p4rth.jetbrains.runtime.OpenCodeApplicationService
import ai.opencode.p4rth.jetbrains.runtime.SidecarHttpClient
import ai.opencode.p4rth.jetbrains.settings.OpenCodeSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project

object CommitMessageGenerator {
  private val log = Logger.getInstance(CommitMessageGenerator::class.java)

  // No hidden "title"/"summary" agent: those force one-liners or first-person ("I added…").
  // Tools are denied on the ephemeral session instead.
  private const val SYSTEM =
    """You are a git commit-message writer, not a chat assistant and not a PR author.

Hard rules:
- Output ONLY a git commit message: subject, blank line, then body.
- Imperative mood only (Add, Fix, Wire) — never first person (never "I added", "I fixed", "We updated").
- Never use past tense in the subject; never write a PR-style summary.
- No markdown fences, labels, or commentary outside the commit message."""

  fun generate(project: Project, diff: String, indicator: ProgressIndicator): String {
    require(diff.isNotBlank()) { "Diff is empty" }
    val settings = ApplicationManager.getApplication().getService(OpenCodeSettings::class.java).state
    val template = settings.commitMessagePrompt.ifBlank { OpenCodeSettings.DEFAULT_COMMIT_MESSAGE_PROMPT }
    val userText = if (template.contains("{{diff}}")) {
      template.replace("{{diff}}", diff)
    } else {
      template.trimEnd() + "\n\nStaged changes:\n" + diff
    }
    val configured = SidecarHttpClient.parseModelRef(settings.commitMessageModel)
    val directory = project.basePath
    val app = ApplicationManager.getApplication().getService(OpenCodeApplicationService::class.java)

    indicator.text = "Starting OpenCode…"
    return app.withSidecar(project, indicator) { sidecar ->
      indicator.checkCanceled()
      val client = SidecarHttpClient(sidecar)
      val model = configured ?: client.resolveDefaultModel(directory)
      if (model == null) {
        error(
          "No OpenCode model is configured. Open the OpenCode chat panel and sign in / pick a model, " +
            "or set Commit message model in Settings → Tools → P4rth OpenCode (provider/model).",
        )
      }
      indicator.text = "Generating commit message with ${model.asSetting()}…"
      log.info("Commit message generation using model ${model.asSetting()} (configured=${configured != null})")
      val sessionID = client.createSession(directory, "jetbrains-commit-message")
      try {
        indicator.checkCanceled()
        client.prompt(
          sessionID = sessionID,
          directory = directory,
          text = userText,
          agent = null,
          system = SYSTEM,
          model = model,
        )
      } finally {
        client.deleteSession(sessionID, directory)
      }
    }
  }
}
