package ai.opencode.p4rth.jetbrains.project

import com.google.gson.JsonParser
import java.nio.file.Path

/**
 * Parses `/global/event` SSE payloads for OpenCode-controlled file writes and filters them to a
 * single project.
 *
 * Reused server event: `file.edited` (schema `{ file: string }`, packages/schema/src/filesystem.ts)
 * is published by the write, edit, and apply_patch tools only after a write completes, then
 * forwarded to `/global/event` by the EventV2 → GlobalBus bridge with the instance `directory`.
 * The streamed shape is:
 *   { directory, project, workspace, payload: { id, type: "file.edited", properties: { file } } }
 * where `file` is an absolute path. No file contents or selection text are ever included.
 */
object FileRefreshEvents {
  data class FileEdited(val directory: String?, val file: String)

  fun parse(dataJson: String): FileEdited? {
    val root = runCatching { JsonParser.parseString(dataJson).asJsonObject }.getOrNull() ?: return null
    val payload = root["payload"]?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    if (payload["type"]?.asString != "file.edited") return null
    val file = payload["properties"]?.takeIf { it.isJsonObject }?.asJsonObject?.get("file")?.asString ?: return null
    if (file.isBlank()) return null
    return FileEdited(root["directory"]?.asString, file)
  }

  /**
   * Returns the normalized absolute path to refresh when the edit belongs to [projectBase],
   * otherwise null. Filtering by the canonical project base guarantees a write in project A
   * never refreshes project B when one sidecar serves multiple projects.
   */
  fun affectedPath(event: FileEdited, projectBase: Path): Path? {
    val base = projectBase.toAbsolutePath().normalize()
    val target = runCatching { Path.of(event.file).toAbsolutePath().normalize() }.getOrNull() ?: return null
    return if (target.startsWith(base)) target else null
  }
}
