import { describe, expect, test } from "bun:test"
import {
  IDE_ACTIVITY_IDLE,
  formatIdeEditorContext,
  ideContextToContextItems,
  isIdeActive,
  mergeIdeActivity,
} from "./ide-host-context"

describe("IDE host bridge", () => {
  test("converts selected IDE text into visible prompt context", () => {
    expect(
      ideContextToContextItems({
        projectRoot: "/project",
        activeFile: {
          relativePath: "src/example.ts",
          languageId: "TypeScript",
          isModified: true,
          selection: {
            startLine: 12,
            startColumn: 1,
            endLine: 18,
            endColumn: 15,
            text: "const value = 1",
            truncated: true,
          },
        },
      }),
    ).toEqual([
      {
        type: "file",
        path: "src/example.ts",
        selection: { startLine: 12, startChar: 1, endLine: 18, endChar: 15 },
        preview: "const value = 1",
        comment: "TypeScript · modified in IDE · selection truncated",
      },
    ])
  })

  test("labels non-truncated selections so v2 prompt chips stay visible", () => {
    expect(
      ideContextToContextItems({
        activeFile: {
          relativePath: "src/plain.txt",
          selection: {
            startLine: 1,
            startColumn: 1,
            endLine: 2,
            endColumn: 1,
            text: "hello",
          },
        },
      }),
    ).toEqual([
      {
        type: "file",
        path: "src/plain.txt",
        selection: { startLine: 1, startChar: 1, endLine: 2, endChar: 1 },
        preview: "hello",
        comment: "selection from IDE",
      },
    ])
  })

  test("adds open file metadata without file contents", () => {
    expect(
      ideContextToContextItems({
        openFiles: [
          { relativePath: "src/a.ts", isModified: false, isActive: true },
          { relativePath: "src/b.ts", isModified: true },
        ],
      }),
    ).toEqual([
      { type: "file", path: "src/a.ts", selection: undefined, preview: undefined, comment: undefined },
      { type: "file", path: "src/b.ts", selection: undefined, preview: undefined, comment: "modified in IDE" },
    ])
  })

  test("formats the active file and deduplicated open tabs without contents", () => {
    expect(
      formatIdeEditorContext({
        projectRoot: "/project",
        activeFile: {
          relativePath: "src/a.ts",
          isActive: true,
          isModified: true,
          caret: { line: 12, column: 4 },
        },
        openFiles: [
          { relativePath: "src/a.ts", isActive: true, isModified: true },
          { relativePath: "src/b.ts", isActive: false, isModified: false },
        ],
      }),
    ).toBe(
      [
        "IDE editor context:",
        "Project root: /project",
        "Active file: src/a.ts (line 12, column 4) (modified)",
        "Open tabs (2):",
        "- src/a.ts (active) (modified)",
        "- src/b.ts",
      ].join("\n"),
    )
  })
})

describe("IDE activity state", () => {
  test("idle state is inactive", () => {
    expect(isIdeActive(IDE_ACTIVITY_IDLE)).toBe(false)
  })

  test("merge fills missing flags with idle defaults", () => {
    expect(mergeIdeActivity({ streaming: true })).toEqual({
      streaming: true,
      toolRunning: false,
      permissionPending: false,
      authenticationActive: false,
      fileOperationActive: false,
      voiceActive: false,
    })
  })

  test("any single flag marks the UI active so the host must not dispose", () => {
    for (const key of Object.keys(IDE_ACTIVITY_IDLE) as (keyof typeof IDE_ACTIVITY_IDLE)[]) {
      expect(isIdeActive(mergeIdeActivity({ [key]: true }))).toBe(true)
    }
  })
})
