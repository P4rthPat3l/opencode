export * as DatabaseMigration from "./migration"

import { sql } from "drizzle-orm"
import { Effect, Semaphore } from "effect"
import type { EffectDrizzleSqlite } from "@opencode-ai/effect-drizzle-sqlite"
import { migrations } from "./migration.gen"
import schema from "./schema.gen"
import { Flock } from "../util/flock"

type Database = EffectDrizzleSqlite.EffectSQLiteDatabase
type Transaction = Parameters<Parameters<Database["transaction"]>[0]>[0]
const lock = Semaphore.makeUnsafe(1)

// Bounded wait for the cross-process migration lock. A crashed holder is auto-recovered
// via the file-lock's stale detection (default 60s) before this timeout is reached.
const MIGRATION_LOCK_TIMEOUT_MS = 120_000

export type Migration = {
  id: string
  up: (tx: Transaction) => Effect.Effect<void, unknown>
}

export type ApplyOptions = {
  /**
   * Absolute database path used to derive the cross-process lock key. When set to a
   * real file path, migrations run under an exclusive file lock in the shared fork
   * state directory so a terminal process and the JetBrains-managed runtime cannot
   * apply schema migrations against the same profile concurrently. Omitted or
   * `:memory:` databases skip the file lock (they are never shared across processes).
   */
  readonly lockKey?: string
}

function migrate(db: Database) {
  // The check-and-apply must run after the lock is held so a process that lost the
  // race re-reads the current schema/migration state instead of acting on a stale read.
  return Effect.gen(function* () {
    const tables = yield* db.all<{ name: string }>(
      sql`SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'`,
    )
    if (tables.some((table) => table.name === "session")) return yield* applyOnly(db, migrations)
    if (tables.length > 0) return yield* Effect.die("Database is not empty and has no session table")
    yield* db.transaction((tx) =>
      Effect.gen(function* () {
        yield* schema.up(tx)
        yield* tx.run(
          sql`CREATE TABLE ${sql.identifier("migration")} (id TEXT PRIMARY KEY, time_completed INTEGER NOT NULL)`,
        )
        yield* Effect.forEach(migrations, (migration) =>
          tx.run(
            sql`INSERT INTO ${sql.identifier("migration")} (id, time_completed) VALUES (${migration.id}, ${Date.now()})`,
          ),
        )
      }),
    )
  })
}

export function apply(db: Database, options?: ApplyOptions) {
  const key = options?.lockKey
  const shared = !!key && key !== ":memory:"
  return lock.withPermit(
    shared
      ? Effect.scoped(
          Effect.gen(function* () {
            // Acquire the exclusive cross-process lock before checking or applying
            // migrations; it is released when this scope closes on success or failure.
            yield* Flock.effect(`database-migration:${key}`, { timeoutMs: MIGRATION_LOCK_TIMEOUT_MS })
            yield* migrate(db)
          }),
        )
      : migrate(db),
  )
}

export function applyOnly(db: Database, input: Migration[]) {
  return Effect.gen(function* () {
    yield* db.run(
      sql`CREATE TABLE IF NOT EXISTS ${sql.identifier("migration")} (id TEXT PRIMARY KEY, time_completed INTEGER NOT NULL)`,
    )
    let completed = new Set(
      (yield* db.all<{ id: string }>(sql`SELECT id FROM ${sql.identifier("migration")}`)).map((row) => row.id),
    )
    if (completed.size === 0) {
      // Existing installs used Drizzle's migration journal. Seed the new
      // journal once so TypeScript migrations don't replay old SQL.
      if (
        yield* db.get(sql`SELECT name FROM sqlite_master WHERE type = 'table' AND name = ${"__drizzle_migrations"}`)
      ) {
        yield* db.run(sql`
          INSERT OR IGNORE INTO ${sql.identifier("migration")} (id, time_completed)
          SELECT name, ${Date.now()}
          FROM ${sql.identifier("__drizzle_migrations")}
          WHERE name IS NOT NULL
        `)
        completed = new Set(
          (yield* db.all<{ id: string }>(sql`SELECT id FROM ${sql.identifier("migration")}`)).map((row) => row.id),
        )
      }
    }

    for (const migration of input) {
      if (completed.has(migration.id)) continue
      yield* db.transaction((tx) =>
        Effect.gen(function* () {
          yield* migration.up(tx)
          yield* tx.run(
            sql`INSERT INTO ${sql.identifier("migration")} (id, time_completed) VALUES (${migration.id}, ${Date.now()})`,
          )
        }),
      )
    }
  })
}
