// Standalone migration runner used by the cross-process migration-lock test.
//
// The parent test spawns two of these against the same shared profile (same XDG_STATE_HOME
// so both resolve the same Flock lock root) and the same database file. Exactly one process
// must apply the fresh schema; the other must wait for the lock, re-read state, and no-op.
//
// Env inputs:
//   MIGRATE_DB_PATH   absolute path to the shared sqlite file
//   MIGRATE_HOLD_MS   optional artificial delay while holding the lock (widens the race)
import { sql } from "drizzle-orm"
import { Effect } from "effect"
import { Database } from "@opencode-ai/core/database/database"

const dbPath = process.env["MIGRATE_DB_PATH"]
if (!dbPath) {
  console.error("MIGRATE_DB_PATH is required")
  process.exit(2)
}

const holdMs = Number(process.env["MIGRATE_HOLD_MS"] ?? "0")

const program = Effect.gen(function* () {
  const { db } = yield* Database.Service
  if (holdMs > 0) yield* Effect.sleep(`${holdMs} millis`)
  const row = yield* db.get<{ count: number }>(sql`SELECT count(*) as count FROM migration`)
  return row?.count ?? 0
}).pipe(Effect.provide(Database.layerFromPath(dbPath)), Effect.scoped)

try {
  const count = await Effect.runPromise(program)
  console.log(`RESULT:${JSON.stringify({ ok: true, count })}`)
  process.exit(0)
} catch (error) {
  console.log(`RESULT:${JSON.stringify({ ok: false, error: error instanceof Error ? error.message : String(error) })}`)
  process.exit(1)
}
