import { Auth } from "@/auth"

import { Effect } from "effect"
import { HttpApiBuilder } from "effect/unstable/httpapi"
import { RootHttpApi } from "../api"
import { LogInput } from "../groups/control"
import { ProviderV2 } from "@opencode-ai/core/provider"

export const controlHandlers = HttpApiBuilder.group(RootHttpApi, "control", (handlers) =>
  Effect.gen(function* () {
    const auth = yield* Auth.Service

    const authList = Effect.fn("ControlHttpApi.authList")(function* () {
      return yield* auth.accounts().pipe(Effect.orDie)
    })

    const authAdd = Effect.fn("ControlHttpApi.authAdd")(function* (ctx: {
      params: { providerID: ProviderV2.ID }
      payload: { auth: Auth.Info; label?: string }
    }) {
      return yield* auth.add(ctx.params.providerID, ctx.payload.auth, ctx.payload.label).pipe(Effect.orDie)
    })

    const authSelect = Effect.fn("ControlHttpApi.authSelect")(function* (ctx: {
      params: { providerID: ProviderV2.ID; accountID: string }
    }) {
      yield* auth.select(ctx.params.providerID, ctx.params.accountID).pipe(Effect.orDie)
      return true
    })

    const authRemoveAccount = Effect.fn("ControlHttpApi.authRemoveAccount")(function* (ctx: {
      params: { providerID: ProviderV2.ID; accountID: string }
    }) {
      yield* auth.removeAccount(ctx.params.providerID, ctx.params.accountID).pipe(Effect.orDie)
      return true
    })

    const authSet = Effect.fn("ControlHttpApi.authSet")(function* (ctx: {
      params: { providerID: ProviderV2.ID }
      payload: Auth.Info
    }) {
      yield* auth.set(ctx.params.providerID, ctx.payload).pipe(Effect.orDie)
      return true
    })

    const authRemove = Effect.fn("ControlHttpApi.authRemove")(function* (ctx: {
      params: { providerID: ProviderV2.ID }
    }) {
      yield* auth.remove(ctx.params.providerID).pipe(Effect.orDie)
      return true
    })

    const log = Effect.fn("ControlHttpApi.log")(function* (ctx: { payload: typeof LogInput.Type }) {
      const write =
        ctx.payload.level === "debug"
          ? Effect.logDebug
          : ctx.payload.level === "info"
            ? Effect.logInfo
            : ctx.payload.level === "warn"
              ? Effect.logWarning
              : Effect.logError
      yield* write(ctx.payload.message).pipe(Effect.annotateLogs(ctx.payload.extra ?? {}))
      return true
    })

    return handlers
      .handle("authList", authList)
      .handle("authAdd", authAdd)
      .handle("authSelect", authSelect)
      .handle("authRemoveAccount", authRemoveAccount)
      .handle("authSet", authSet)
      .handle("authRemove", authRemove)
      .handle("log", log)
  }),
)
