import { LayerNode } from "@opencode-ai/core/effect/layer-node"
import path from "path"
import { Effect, Layer, Option, Record, Result, Schema, Context } from "effect"
import { NonNegativeInt } from "@opencode-ai/core/schema"
import { Global } from "@opencode-ai/core/global"
import { FSUtil } from "@opencode-ai/core/fs-util"

export const OAUTH_DUMMY_KEY = "opencode-oauth-dummy-key"

const file = path.join(Global.Path.data, "auth.json")

const fail = (message: string) => (cause: unknown) => new AuthError({ message, cause })

export class Oauth extends Schema.Class<Oauth>("OAuth")({
  type: Schema.Literal("oauth"),
  refresh: Schema.String,
  access: Schema.String,
  expires: NonNegativeInt,
  accountId: Schema.optional(Schema.String),
  enterpriseUrl: Schema.optional(Schema.String),
}) {}

export class Api extends Schema.Class<Api>("ApiAuth")({
  type: Schema.Literal("api"),
  key: Schema.String,
  metadata: Schema.optional(Schema.Record(Schema.String, Schema.String)),
}) {}

export class WellKnown extends Schema.Class<WellKnown>("WellKnownAuth")({
  type: Schema.Literal("wellknown"),
  key: Schema.String,
  token: Schema.String,
}) {}

export const Info = Schema.Union([Oauth, Api, WellKnown]).annotate({ discriminator: "type", identifier: "Auth" })
export type Info = Schema.Schema.Type<typeof Info>

export class Account extends Schema.Class<Account>("AuthAccount")({
  id: Schema.String,
  providerID: Schema.String,
  label: Schema.String,
  type: Schema.Literals(["oauth", "api", "wellknown"]),
  active: Schema.Boolean,
}) {}

class StoredAccount extends Schema.Class<StoredAccount>("StoredAuthAccount")({
  id: Schema.String,
  label: Schema.String,
  auth: Info,
}) {}

class StoredProvider extends Schema.Class<StoredProvider>("StoredAuthProvider")({
  type: Schema.Literal("accounts"),
  active: Schema.String,
  accounts: Schema.Array(StoredAccount),
}) {}

export class AuthError extends Schema.TaggedErrorClass<AuthError>()("AuthError", {
  message: Schema.String,
  cause: Schema.optional(Schema.Defect()),
}) {}

export interface Interface {
  readonly get: (providerID: string) => Effect.Effect<Info | undefined, AuthError>
  readonly all: () => Effect.Effect<Record<string, Info>, AuthError>
  readonly accounts: (providerID?: string) => Effect.Effect<Account[], AuthError>
  readonly add: (providerID: string, info: Info, label?: string) => Effect.Effect<Account, AuthError>
  readonly select: (providerID: string, accountID: string) => Effect.Effect<void, AuthError>
  readonly set: (key: string, info: Info) => Effect.Effect<void, AuthError>
  readonly remove: (key: string) => Effect.Effect<void, AuthError>
  readonly removeAccount: (providerID: string, accountID: string) => Effect.Effect<void, AuthError>
}

export class Service extends Context.Service<Service, Interface>()("@opencode/Auth") {}

const layer = Layer.effect(
  Service,
  Effect.gen(function* () {
    const fsys = yield* FSUtil.Service
    const decode = Schema.decodeUnknownOption(Info)
    const parse = Schema.decodeUnknownSync(Info)
    const decodeProvider = Schema.decodeUnknownOption(StoredProvider)

    const normalize = (key: string) => key.replace(/\/+$/, "")
    const defaultLabel = (info: Info, index: number) => {
      if (info.type === "oauth" && info.accountId) return info.accountId
      if (info.type === "api") return `API key ${index + 1}`
      if (info.type === "oauth") return `OAuth account ${index + 1}`
      return `Account ${index + 1}`
    }

    const stored = Effect.fn("Auth.stored")(function* () {
      const value = process.env.OPENCODE_AUTH_CONTENT
        ? (() => {
            try {
              return JSON.parse(process.env.OPENCODE_AUTH_CONTENT)
            } catch {
              return {}
            }
          })()
        : yield* fsys.readJson(file).pipe(Effect.orElseSucceed(() => ({})))
      const data = value as Record<string, unknown>
      return Record.filterMap(data, (value, providerID) => {
        const current = decodeProvider(value)
        if (Option.isSome(current)) return Result.succeed(current.value)
        const legacy = decode(value)
        if (Option.isNone(legacy)) return Result.failVoid
        return Result.succeed(
          new StoredProvider({
            type: "accounts",
            active: "default",
            accounts: [new StoredAccount({ id: "default", label: defaultLabel(legacy.value, 0), auth: legacy.value })],
          }),
        )
      })
    })

    const write = (data: Record<string, StoredProvider>) =>
      fsys.writeJson(file, data, 0o600).pipe(Effect.mapError(fail("Failed to write auth data")))

    const all = Effect.fn("Auth.all")(function* () {
      return Record.filterMap(yield* stored(), (provider) => {
        const account = provider.accounts.find((item) => item.id === provider.active)
        return account ? Result.succeed(account.auth) : Result.failVoid
      })
    })

    const get = Effect.fn("Auth.get")(function* (providerID: string) {
      return (yield* all())[providerID]
    })

    const accounts = Effect.fn("Auth.accounts")(function* (providerID?: string) {
      return Object.entries(yield* stored()).flatMap(([key, provider]) => {
        if (providerID && normalize(providerID) !== key) return []
        return provider.accounts.map(
          (account) =>
            new Account({
              id: account.id,
              providerID: key,
              label: account.label,
              type: account.auth.type,
              active: account.id === provider.active,
            }),
        )
      })
    })

    const add = Effect.fn("Auth.add")(function* (key: string, info: Info, label?: string) {
      const norm = normalize(key)
      const data = yield* stored()
      const current = data[norm]
      const value = parse(info)
      const account = new StoredAccount({
        id: crypto.randomUUID(),
        label: label?.trim() || defaultLabel(value, current?.accounts.length ?? 0),
        auth: value,
      })
      data[norm] = new StoredProvider({
        type: "accounts",
        active: account.id,
        accounts: [...(current?.accounts ?? []), account],
      })
      if (norm !== key) delete data[key]
      delete data[norm + "/"]
      yield* write(data)
      return new Account({
        id: account.id,
        providerID: norm,
        label: account.label,
        type: value.type,
        active: true,
      })
    })

    const select = Effect.fn("Auth.select")(function* (providerID: string, accountID: string) {
      const norm = normalize(providerID)
      const data = yield* stored()
      const provider = data[norm]
      if (!provider?.accounts.some((account) => account.id === accountID)) {
        return yield* new AuthError({ message: `Credential not found: ${norm}/${accountID}` })
      }
      data[norm] = new StoredProvider({ ...provider, active: accountID })
      yield* write(data)
    })

    const set = Effect.fn("Auth.set")(function* (key: string, info: Info) {
      const norm = normalize(key)
      const data = yield* stored()
      const provider = data[norm]
      if (!provider) {
        yield* add(norm, info)
        return
      }
      const accounts = provider.accounts.map((account) =>
        account.id === provider.active ? new StoredAccount({ ...account, auth: parse(info) }) : account,
      )
      data[norm] = new StoredProvider({ ...provider, accounts })
      if (norm !== key) delete data[key]
      delete data[norm + "/"]
      yield* write(data)
    })

    const remove = Effect.fn("Auth.remove")(function* (key: string) {
      const norm = normalize(key)
      const data = yield* stored()
      delete data[key]
      delete data[norm]
      yield* write(data)
    })

    const removeAccount = Effect.fn("Auth.removeAccount")(function* (providerID: string, accountID: string) {
      const norm = normalize(providerID)
      const data = yield* stored()
      const provider = data[norm]
      if (!provider) return
      const accounts = provider.accounts.filter((account) => account.id !== accountID)
      if (accounts.length === 0) delete data[norm]
      else {
        data[norm] = new StoredProvider({
          ...provider,
          active: provider.active === accountID ? accounts[0].id : provider.active,
          accounts,
        })
      }
      yield* write(data)
    })

    return Service.of({ get, all, accounts, add, select, set, remove, removeAccount })
  }),
)

export const node = LayerNode.make({ service: Service, layer: layer, deps: [FSUtil.node] })

export * as Auth from "."
