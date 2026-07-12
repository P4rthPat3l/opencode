import { describe, expect } from "bun:test"
import { LayerNode } from "@opencode-ai/core/effect/layer-node"
import { Effect } from "effect"
import { Auth } from "../../src/auth"
import { testEffect } from "../lib/effect"

const it = testEffect(LayerNode.compile(Auth.node))

describe("Auth", () => {
  it.instance("set normalizes trailing slashes in keys", () =>
    Effect.gen(function* () {
      const auth = yield* Auth.Service
      yield* auth.set("https://example.com/", {
        type: "wellknown",
        key: "TOKEN",
        token: "abc",
      })
      const data = yield* auth.all()
      expect(data["https://example.com"]).toBeDefined()
      expect(data["https://example.com/"]).toBeUndefined()
    }),
  )

  it.instance("set cleans up pre-existing trailing-slash entry", () =>
    Effect.gen(function* () {
      const auth = yield* Auth.Service
      yield* auth.set("https://example.com/", {
        type: "wellknown",
        key: "TOKEN",
        token: "old",
      })
      yield* auth.set("https://example.com", {
        type: "wellknown",
        key: "TOKEN",
        token: "new",
      })
      const data = yield* auth.all()
      const keys = Object.keys(data).filter((key) => key.includes("example.com"))
      expect(keys).toEqual(["https://example.com"])
      const entry = data["https://example.com"]!
      expect(entry.type).toBe("wellknown")
      if (entry.type === "wellknown") expect(entry.token).toBe("new")
    }),
  )

  it.instance("remove deletes both trailing-slash and normalized keys", () =>
    Effect.gen(function* () {
      const auth = yield* Auth.Service
      yield* auth.set("https://example.com", {
        type: "wellknown",
        key: "TOKEN",
        token: "abc",
      })
      yield* auth.remove("https://example.com/")
      const data = yield* auth.all()
      expect(data["https://example.com"]).toBeUndefined()
      expect(data["https://example.com/"]).toBeUndefined()
    }),
  )

  it.instance("set and remove are no-ops on keys without trailing slashes", () =>
    Effect.gen(function* () {
      const auth = yield* Auth.Service
      yield* auth.set("anthropic", {
        type: "api",
        key: "sk-test",
      })
      const data = yield* auth.all()
      expect(data["anthropic"]).toBeDefined()
      yield* auth.remove("anthropic")
      const after = yield* auth.all()
      expect(after["anthropic"]).toBeUndefined()
    }),
  )

  it.instance("stores multiple accounts and selects the active account", () =>
    Effect.gen(function* () {
      const auth = yield* Auth.Service
      const first = yield* auth.add("multi-account-test", { type: "api", key: "sk-first" }, "Work")
      const second = yield* auth.add("multi-account-test", { type: "api", key: "sk-second" }, "Personal")

      expect((yield* auth.get("multi-account-test"))?.type).toBe("api")
      expect((yield* auth.accounts("multi-account-test")).map((account) => [account.label, account.active])).toEqual([
        ["Work", false],
        ["Personal", true],
      ])

      yield* auth.select("multi-account-test", first.id)
      const active = yield* auth.get("multi-account-test")
      expect(active?.type).toBe("api")
      if (active?.type === "api") expect(active.key).toBe("sk-first")
      expect((yield* auth.accounts("multi-account-test")).find((account) => account.id === second.id)?.active).toBe(
        false,
      )
      yield* auth.remove("multi-account-test")
    }),
  )

  it.instance("removing the active account selects a remaining account", () =>
    Effect.gen(function* () {
      const auth = yield* Auth.Service
      const first = yield* auth.add("remove-account-test", { type: "api", key: "sk-first" })
      const second = yield* auth.add("remove-account-test", { type: "api", key: "sk-second" })

      yield* auth.removeAccount("remove-account-test", second.id)
      expect(yield* auth.accounts("remove-account-test")).toEqual([
        expect.objectContaining({ id: first.id, active: true }),
      ])
      const active = yield* auth.get("remove-account-test")
      if (active?.type === "api") expect(active.key).toBe("sk-first")
      yield* auth.remove("remove-account-test")
    }),
  )

  it.instance("set refreshes the active account without adding another", () =>
    Effect.gen(function* () {
      const auth = yield* Auth.Service
      yield* auth.add("refresh-account-test", { type: "oauth", refresh: "old", access: "old", expires: 1 }, "Work")
      yield* auth.set("refresh-account-test", { type: "oauth", refresh: "new", access: "new", expires: 2 })

      expect(yield* auth.accounts("refresh-account-test")).toHaveLength(1)
      const active = yield* auth.get("refresh-account-test")
      if (active?.type === "oauth") expect(active.access).toBe("new")
      yield* auth.remove("refresh-account-test")
    }),
  )
})
