import { describe, expect, test } from "bun:test"
import fs from "fs/promises"
import os from "os"
import path from "path"
import { Global, resolvePaths } from "@opencode-ai/core/global"

describe("global paths", () => {
  test("tmp path is under the system temp directory", () => {
    expect(Global.Path.tmp).toBe(path.join(os.tmpdir(), "p4rth-opencode"))
    expect(Global.make().tmp).toBe(Global.Path.tmp)
  })

  test("tmp path is created on module load", async () => {
    expect((await fs.stat(Global.Path.tmp)).isDirectory()).toBe(true)
  })

  test("fork-specific storage overrides do not replace global XDG variables", () => {
    const paths = resolvePaths({
      XDG_DATA_HOME: "/xdg/data",
      XDG_CACHE_HOME: "/xdg/cache",
      XDG_CONFIG_HOME: "/xdg/config",
      XDG_STATE_HOME: "/xdg/state",
      P4RTH_OPENCODE_DATA_DIR: "/fork/data",
      P4RTH_OPENCODE_CACHE_DIR: "/fork/cache",
      P4RTH_OPENCODE_CONFIG_DIR: "/fork/config",
      P4RTH_OPENCODE_STATE_DIR: "/fork/state",
      P4RTH_OPENCODE_LOG_DIR: "/fork/log",
    })

    expect(paths.data).toBe("/fork/data")
    expect(paths.cache).toBe("/fork/cache")
    expect(paths.config).toBe("/fork/config")
    expect(paths.state).toBe("/fork/state")
    expect(paths.log).toBe("/fork/log")
    expect(paths.repos).toBe(path.join("/fork/data", "repos"))
    expect(paths.bin).toBe(path.join("/fork/cache", "bin"))
  })
})
