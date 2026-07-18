import { describe, expect, test } from "bun:test"
import { $ } from "bun"
import { fileURLToPath } from "url"
import path from "path"
import { migrations } from "@opencode-ai/core/database/migration.gen"
import { tmpdir } from "./fixture/tmpdir"

const script = fileURLToPath(new URL("./fixture/migrate-once.ts", import.meta.url))

function result(output: string) {
  const line = output
    .split("\n")
    .reverse()
    .find((entry) => entry.startsWith("RESULT:"))
  if (!line) throw new Error(`no RESULT line in output:\n${output}`)
  return JSON.parse(line.slice("RESULT:".length)) as { ok: boolean; count?: number; error?: string }
}

describe("DatabaseMigration cross-process lock", () => {
  test(
    "two processes migrating one shared profile apply migrations exactly once",
    async () => {
      await using shared = await tmpdir()
      const dbPath = path.join(shared.path, "shared.db")
      const env = {
        ...process.env,
        // Both processes share one Flock root (state dir) and one database file, so the
        // exclusive migration lock must serialize them across process boundaries.
        XDG_STATE_HOME: path.join(shared.path, "state"),
        XDG_DATA_HOME: path.join(shared.path, "data"),
        XDG_CACHE_HOME: path.join(shared.path, "cache"),
        XDG_CONFIG_HOME: path.join(shared.path, "config"),
        MIGRATE_DB_PATH: dbPath,
      }

      const [a, b] = await Promise.all([
        $`bun ${script}`.env(env).quiet().nothrow(),
        $`bun ${script}`.env(env).quiet().nothrow(),
      ])

      const ra = result(a.stdout.toString() + a.stderr.toString())
      const rb = result(b.stdout.toString() + b.stderr.toString())

      // Neither process may crash on a double-apply (duplicate migration id / non-empty db).
      expect(a.exitCode, `first process failed: ${ra.error ?? ""}`).toBe(0)
      expect(b.exitCode, `second process failed: ${rb.error ?? ""}`).toBe(0)
      expect(ra.ok).toBe(true)
      expect(rb.ok).toBe(true)
      // Each migration recorded exactly once (PRIMARY KEY id) — no duplicates, no partial state.
      expect(ra.count).toBe(migrations.length)
      expect(rb.count).toBe(migrations.length)
    },
    60_000,
  )
})
