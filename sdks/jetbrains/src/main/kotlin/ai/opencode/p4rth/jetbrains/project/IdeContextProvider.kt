package ai.opencode.p4rth.jetbrains.project

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore

data class IdeSelection(
  val startLine: Int,
  val startColumn: Int,
  val endLine: Int,
  val endColumn: Int,
  val text: String? = null,
  val truncated: Boolean = false,
)

data class IdeFile(
  val relativePath: String,
  val languageId: String? = null,
  val isModified: Boolean = false,
  val isActive: Boolean = false,
  val caret: Map<String, Int>? = null,
  val selection: IdeSelection? = null,
)

data class IdeContext(
  val projectRoot: String?,
  val activeFile: IdeFile? = null,
  val openFiles: List<IdeFile>? = null,
)

class IdeContextProvider(private val project: Project) {
  private val selectionLimit = 80_000

  fun activeFile(includeSelection: Boolean): IdeContext {
    return IdeContext(project.basePath, active(includeSelection, includeCaret = true), null)
  }

  fun openFiles(): IdeContext {
    return context(includeSelection = false, includeOpenFiles = true, includeCaret = false)
  }

  fun context(includeSelection: Boolean, includeOpenFiles: Boolean, includeCaret: Boolean): IdeContext {
    val manager = FileEditorManager.getInstance(project)
    val active = manager.selectedFiles.firstOrNull()
    val openFiles = if (!includeOpenFiles) null else manager.openFiles.mapNotNull { file ->
      val path = relativePath(file.path) ?: return@mapNotNull null
      IdeFile(
        relativePath = path,
        isModified = FileDocumentManager.getInstance().getDocument(file)?.let {
          FileDocumentManager.getInstance().isDocumentUnsaved(it)
        } ?: false,
        isActive = file == active,
      )
    }
    return IdeContext(
      project.basePath,
      active(includeSelection, includeCaret),
      openFiles,
    )
  }

  private fun active(includeSelection: Boolean, includeCaret: Boolean): IdeFile? {
    val manager = FileEditorManager.getInstance(project)
    val file = manager.selectedFiles.firstOrNull() ?: return null
    val editor = manager.selectedTextEditor?.takeIf { it.virtualFile == file }
    val relativePath = relativePath(file.path) ?: return null
    val document = FileDocumentManager.getInstance().getDocument(file)
    val caret = editor?.caretModel?.primaryCaret?.logicalPosition
    val selection = if (includeSelection && editor?.selectionModel?.hasSelection() == true) {
      val start = editor.offsetToLogicalPosition(editor.selectionModel.selectionStart)
      val end = editor.offsetToLogicalPosition(editor.selectionModel.selectionEnd)
      val text = editor.selectionModel.selectedText.orEmpty()
      IdeSelection(
        startLine = start.line + 1,
        startColumn = start.column + 1,
        endLine = end.line + 1,
        endColumn = end.column + 1,
        text = text.take(selectionLimit),
        truncated = text.length > selectionLimit,
      )
    } else null
    return IdeFile(
      relativePath = relativePath,
      languageId = file.fileType.name,
      isModified = document?.let { FileDocumentManager.getInstance().isDocumentUnsaved(it) } ?: false,
      isActive = true,
      caret = if (includeCaret && caret != null) mapOf("line" to caret.line + 1, "column" to caret.column + 1) else null,
      selection = selection,
    )
  }

  private fun relativePath(path: String): String? {
    val basePath = project.basePath ?: return null
    val fs = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
    val base = fs.findFileByPath(basePath) ?: return null
    val file = fs.findFileByPath(path) ?: return null
    return VfsUtilCore.getRelativePath(file, base, '/')
  }
}
