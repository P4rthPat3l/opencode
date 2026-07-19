import type { FileContextItem } from "./prompt-state"

/**
 * Lightweight activity flags reported from the web UI to a host IDE (JetBrains) so the
 * host can avoid disposing the embedded browser while real work is in progress. The host
 * keeps only these booleans; it never inspects session content.
 */
export type IdeActivityState = {
  streaming: boolean
  toolRunning: boolean
  permissionPending: boolean
  authenticationActive: boolean
  fileOperationActive: boolean
  voiceActive: boolean
}

export const IDE_ACTIVITY_IDLE: IdeActivityState = {
  streaming: false,
  toolRunning: false,
  permissionPending: false,
  authenticationActive: false,
  fileOperationActive: false,
  voiceActive: false,
}

/** Complete an activity snapshot from partial signals gathered across web contexts. */
export function mergeIdeActivity(input: Partial<IdeActivityState>): IdeActivityState {
  return { ...IDE_ACTIVITY_IDLE, ...input }
}

/** True when any tracked activity is in progress. The host must not dispose while active. */
export function isIdeActive(state: IdeActivityState): boolean {
  return (
    state.streaming ||
    state.toolRunning ||
    state.permissionPending ||
    state.authenticationActive ||
    state.fileOperationActive ||
    state.voiceActive
  )
}

export type IdeSelection = {
  startLine: number
  startColumn: number
  endLine: number
  endColumn: number
  text?: string
  truncated?: boolean
}

export type IdeFile = {
  relativePath: string
  languageId?: string
  isModified?: boolean
  isActive?: boolean
  caret?: {
    line: number
    column: number
  }
  selection?: IdeSelection
}

export type IdeContext = {
  projectRoot?: string
  activeFile?: IdeFile
  openFiles?: IdeFile[]
}

export function formatIdeEditorContext(context: IdeContext | undefined) {
  if (!context) return
  const active = context.activeFile ?? context.openFiles?.find((file) => file.isActive)
  const files = [active, ...(context.openFiles ?? [])].filter((file): file is IdeFile => !!file)
  const unique = files.filter(
    (file, index) => files.findIndex((item) => item.relativePath === file.relativePath) === index,
  )
  if (unique.length === 0) return

  const activePath = active?.relativePath
  const caret = active?.caret
  const activeLine = active
    ? `Active file: ${active.relativePath}${caret ? ` (line ${caret.line}, column ${caret.column})` : ""}${active.isModified ? " (modified)" : ""}`
    : "Active file: none"
  return [
    "IDE editor context:",
    context.projectRoot ? `Project root: ${context.projectRoot}` : undefined,
    activeLine,
    `Open tabs (${unique.length}):`,
    ...unique.map(
      (file) =>
        `- ${file.relativePath}${file.relativePath === activePath || file.isActive ? " (active)" : ""}${file.isModified ? " (modified)" : ""}`,
    ),
  ]
    .filter((line): line is string => !!line)
    .join("\n")
}

export function ideContextToContextItems(context: IdeContext): FileContextItem[] {
  const active = context.activeFile ? [context.activeFile] : []
  const open = context.openFiles ?? []
  return [...active, ...open].flatMap((file) => {
    if (!file.relativePath) return []
    return [
      {
        type: "file" as const,
        path: file.relativePath,
        selection: file.selection
          ? {
              startLine: file.selection.startLine,
              startChar: file.selection.startColumn,
              endLine: file.selection.endLine,
              endChar: file.selection.endColumn,
            }
          : undefined,
        preview: file.selection?.text,
        comment: commentForFile(file),
      },
    ]
  })
}

function commentForFile(file: IdeFile) {
  const parts = [
    file.languageId,
    file.isModified ? "modified in IDE" : undefined,
    file.selection ? (file.selection.truncated ? "selection truncated" : "selection from IDE") : undefined,
  ].filter((part): part is string => !!part)
  // Always return a non-empty comment for selections so v2 prompt UI (which only
  // renders context items that have comments) shows the chip.
  return parts.join(" · ") || undefined
}
