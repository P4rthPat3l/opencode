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
    /** Empty uses the server default / small model. Format: providerID/modelID */
    var commitMessageModel: String = "",
    var commitMessagePrompt: String = DEFAULT_COMMIT_MESSAGE_PROMPT,
    /** alwaysReplace | replaceIfEmpty | append */
    var commitMessageReplaceMode: String = "alwaysReplace",
  )

  private var state = State()

  override fun getState() = state

  override fun loadState(state: State) {
    this.state = state
    if (this.state.commitMessagePrompt.isBlank() || this.state.commitMessagePrompt in LEGACY_COMMIT_MESSAGE_PROMPTS) {
      this.state.commitMessagePrompt = DEFAULT_COMMIT_MESSAGE_PROMPT
    }
    if (this.state.commitMessageReplaceMode.isBlank()) {
      this.state.commitMessageReplaceMode = "alwaysReplace"
    }
  }

  companion object {
    /** Older built-in defaults that produced weak / first-person messages. */
    private val LEGACY_COMMIT_MESSAGE_PROMPTS = setOf(
      """You generate git commit messages from staged diffs.

Rules:
- Output ONLY the commit message (subject line, optional blank line, optional body).
- No markdown fences, no quotes, no explanation, no preamble.
- Prefer Conventional Commits (type(scope): summary) when it fits.
- Subject line ideally <= 72 characters; use the body for detail when needed.
- Match the language and tone of the diff (and any recent commit style hints if present).
- Describe the change itself, not the process of generating a message.
- Never invent files or changes that are not in the diff.

Staged changes:
{{diff}}""",
      """You write git commit messages from staged diffs.

Always output BOTH a subject and a body. Never output only a one-line title.

Format (exact):
1. Subject line — Conventional Commits: type(scope): summary
   - type: feat, fix, refactor, docs, test, chore, perf, style, build, or ci
   - scope: short area when clear from the diff (e.g. jetbrains, tui, core); omit parentheses if unclear
   - summary: imperative mood, ~50–72 characters, no trailing period
2. One blank line
3. Body — 2–6 short paragraphs or bullet lines that explain:
   - what changed
   - why it changed (motivation / problem)
   - notable details (APIs, UX, behavior, risks) when the diff supports them

Rules:
- Output ONLY the commit message text. No markdown fences, no quotes, no preamble, no "Commit message:" label.
- Every non-trivial staged change must be reflected; do not invent files or behavior absent from the diff.
- Prefer specific scopes and concrete wording over vague phrases like "update code" or "fix stuff".
- Match the language of the diff/comments when obvious; otherwise English.
- If many files changed, group related points in the body instead of listing every path.

Example shape:
feat(jetbrains): add AI commit message generation

Wire a Commit toolbar action that builds a full staged diff and asks
OpenCode for a conventional subject plus body. Settings control model,
prompt template, and replace mode. Tools stay denied on the ephemeral
session so generation cannot block on permission prompts.

Staged changes:
{{diff}}""",
    )

    const val DEFAULT_COMMIT_MESSAGE_PROMPT =
      """Write a git commit message for the staged diff below.

Output format (required — nothing else):
```
type(scope): imperative summary

Body paragraph(s) or bullets.
```

Subject:
- Conventional Commits: type(scope): summary
- type one of: feat, fix, refactor, docs, test, chore, perf, style, build, ci
- scope when clear (e.g. jetbrains, core); omit (scope) if unclear
- imperative mood: "add", "fix", "wire" — NOT "added", "fixed", "I added", "we fixed"
- ~50–72 characters, no trailing period

Body (required, after one blank line):
- 2–6 short lines or bullets
- Explain what changed and why, from the diff only
- Imperative or neutral third-person phrasing is fine
- NEVER first person: ban "I", "I'm", "I've", "we", "we're", "we've" in the whole message
- Do not write a PR narrative ("I added X. I also updated Y.")

Style:
- Message only — no markdown fences, no quotes, no "Commit message:" prefix
- Concrete over vague; group related changes
- Do not invent files or behavior not in the diff

Good example:
feat(jetbrains): add OpenCode commit message generation

Add a Commit toolbar action that collects the full staged diff and
requests a conventional subject plus body from OpenCode. Persist model
and prompt settings, deny tools on the ephemeral session, and surface
errors in the IDE.

Bad example (do not write like this):
feat(jetbrains): add OpenCode commit message generation

I added a Commit tool window action that gathers the full staged diff...
I also updated settings and documentation...

Staged changes:
{{diff}}"""
  }
}
