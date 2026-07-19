package ai.opencode.p4rth.jetbrains.vcs

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ContentRevision
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Collects the full diff of changes that will be committed.
 * Never truncates content — the full staged/included patch is returned.
 *
 * Capture [Snapshot] on the EDT (AnActionEvent data is only reliable there),
 * then [materialize] on a background thread under a read action.
 */
object StagedDiffCollector {
  data class Snapshot(
    val changes: List<Change>,
    val unversioned: List<FilePath>,
    val projectRoot: String?,
  )

  /** Call on the EDT while the action data context is still valid. */
  fun snapshot(project: Project, event: AnActionEvent? = null): Snapshot {
    val workflow = event?.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
    if (workflow != null) {
      val changes = workflow.getIncludedChanges()
      val unversioned = workflow.getIncludedUnversionedFiles()
      if (changes.isNotEmpty() || unversioned.isNotEmpty()) {
        return Snapshot(changes.toList(), unversioned.toList(), project.basePath)
      }
    }

    val selected = event?.getData(VcsDataKeys.SELECTED_CHANGES)?.toList().orEmpty()
    if (selected.isNotEmpty()) {
      return Snapshot(selected, emptyList(), project.basePath)
    }

    val defaultChanges = ChangeListManager.getInstance(project).defaultChangeList.changes.toList()
    return Snapshot(defaultChanges, emptyList(), project.basePath)
  }

  fun materialize(snapshot: Snapshot): String {
    if (snapshot.changes.isNotEmpty() || snapshot.unversioned.isNotEmpty()) {
      return ReadAction.compute<String, RuntimeException> {
        formatIncluded(snapshot.changes, snapshot.unversioned)
      }
    }

    val root = snapshot.projectRoot
    if (!root.isNullOrBlank()) {
      val cached = gitDiffCached(File(root))
      if (!cached.isNullOrBlank()) return cached
    }

    error("No staged or selected changes to generate a commit message from")
  }

  fun collect(project: Project, event: AnActionEvent? = null): String =
    materialize(snapshot(project, event))

  private fun formatIncluded(changes: Collection<Change>, unversioned: Collection<FilePath>): String {
    val sections = ArrayList<String>(changes.size + unversioned.size)
    for (change in changes) {
      sections += formatChange(change)
    }
    for (path in unversioned) {
      sections += formatUnversioned(path)
    }
    require(sections.isNotEmpty()) { "No staged or selected changes to generate a commit message from" }
    return sections.joinToString("\n\n")
  }

  private fun formatChange(change: Change): String {
    val before = change.beforeRevision
    val after = change.afterRevision
    val path = after?.file?.path ?: before?.file?.path ?: "unknown"
    val status = when (change.type) {
      Change.Type.NEW -> "added"
      Change.Type.DELETED -> "deleted"
      Change.Type.MOVED -> "renamed/moved"
      Change.Type.MODIFICATION -> "modified"
    }
    val beforeText = readRevision(before)
    val afterText = readRevision(after)
    return buildString {
      append("diff --git a/").append(path).append(" b/").append(path).append('\n')
      append("status: ").append(status).append('\n')
      if (before != null && after != null && before.file.path != after.file.path) {
        append("rename from ").append(before.file.path).append('\n')
        append("rename to ").append(after.file.path).append('\n')
      }
      append("--- ").append(if (before == null) "/dev/null" else "a/${before.file.path}").append('\n')
      append("+++ ").append(if (after == null) "/dev/null" else "b/${after.file.path}").append('\n')
      append(unifiedBody(beforeText, afterText))
    }
  }

  private fun formatUnversioned(path: FilePath): String {
    val content = runCatching {
      val file = path.virtualFile
      if (file != null && !file.isDirectory) String(file.contentsToByteArray(), StandardCharsets.UTF_8)
      else path.ioFile.takeIf { it.isFile }?.readText(StandardCharsets.UTF_8)
    }.getOrNull()
    val rel = path.path
    return buildString {
      append("diff --git a/").append(rel).append(" b/").append(rel).append('\n')
      append("status: untracked\n")
      append("--- /dev/null\n")
      append("+++ b/").append(rel).append('\n')
      if (content == null) {
        append("@@ binary or unreadable file @@\n")
      } else {
        append(unifiedBody(null, content))
      }
    }
  }

  private fun readRevision(revision: ContentRevision?): String? {
    if (revision == null) return null
    return try {
      revision.content
    } catch (_: VcsException) {
      null
    }
  }

  /** Full line-oriented dump (no truncation). Adequate context for the model. */
  private fun unifiedBody(before: String?, after: String?): String {
    if (before == null && after == null) return "@@ binary or empty @@\n"
    if (before == null) {
      val lines = after.orEmpty().split('\n')
      return buildString {
        append("@@\n")
        for (line in lines) append('+').append(line).append('\n')
      }
    }
    if (after == null) {
      val lines = before.split('\n')
      return buildString {
        append("@@\n")
        for (line in lines) append('-').append(line).append('\n')
      }
    }
    return buildString {
      append("@@ full before @@\n")
      for (line in before.split('\n')) append('-').append(line).append('\n')
      append("@@ full after @@\n")
      for (line in after.split('\n')) append('+').append(line).append('\n')
    }
  }

  private fun gitDiffCached(root: File): String? {
    if (!File(root, ".git").exists() && !isInsideGitWorkTree(root)) return null
    val process = ProcessBuilder("git", "-C", root.absolutePath, "diff", "--cached", "--no-color", "--find-renames")
      .redirectErrorStream(true)
      .start()
    val finished = process.waitFor(120, TimeUnit.SECONDS)
    if (!finished) {
      process.destroyForcibly()
      return null
    }
    val output = process.inputStream.readBytes().toString(StandardCharsets.UTF_8)
    if (process.exitValue() != 0) return null
    return output.takeIf { it.isNotBlank() }
  }

  private fun isInsideGitWorkTree(root: File): Boolean {
    val process = ProcessBuilder("git", "-C", root.absolutePath, "rev-parse", "--is-inside-work-tree")
      .redirectErrorStream(true)
      .start()
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      return false
    }
    return process.exitValue() == 0 && process.inputStream.readBytes().toString(StandardCharsets.UTF_8).trim() == "true"
  }
}
