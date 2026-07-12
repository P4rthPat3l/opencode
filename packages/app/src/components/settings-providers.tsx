import { Button } from "@opencode-ai/ui/button"
import { useDialog } from "@opencode-ai/ui/context/dialog"
import { ProviderIcon } from "@opencode-ai/ui/provider-icon"
import { Tag } from "@opencode-ai/ui/tag"
import type { AuthAccount } from "@opencode-ai/sdk/v2/client"
import { showToast } from "@/utils/toast"
import { popularProviders, useProviders } from "@/hooks/use-providers"
import { createMemo, createResource, type Component, For, Show } from "solid-js"
import { createStore } from "solid-js/store"
import { useLanguage } from "@/context/language"
import { useServerSDK } from "@/context/server-sdk"
import { useServerSync } from "@/context/server-sync"
import { DialogConnectProvider, useProviderConnectController } from "./dialog-connect-provider"
import { DialogCustomProvider } from "./dialog-custom-provider"
import { SettingsList } from "./settings-list"
import { SettingsServerPicker, SettingsServerScope } from "./settings-server-picker"

type ProviderSource = "env" | "api" | "config" | "custom"
type ProviderItem = ReturnType<ReturnType<typeof useProviders>["connected"]>[number]

const PROVIDER_NOTES = [
  { match: (id: string) => id === "opencode", key: "dialog.provider.opencode.note" },
  { match: (id: string) => id === "opencode-go", key: "dialog.provider.opencodeGo.tagline" },
  { match: (id: string) => id === "anthropic", key: "dialog.provider.anthropic.note" },
  { match: (id: string) => id.startsWith("github-copilot"), key: "dialog.provider.copilot.note" },
  { match: (id: string) => id === "openai", key: "dialog.provider.openai.note" },
  { match: (id: string) => id === "google", key: "dialog.provider.google.note" },
  { match: (id: string) => id === "openrouter", key: "dialog.provider.openrouter.note" },
  { match: (id: string) => id === "vercel", key: "dialog.provider.vercel.note" },
] as const

export const SettingsProviders: Component<{ onBack?: () => void }> = (props) => {
  return (
    <SettingsServerScope>
      <SettingsProvidersContent onBack={props.onBack} />
    </SettingsServerScope>
  )
}

const SettingsProvidersContent: Component<{ onBack?: () => void }> = (props) => {
  const dialog = useDialog()
  const language = useLanguage()
  const serverSDK = useServerSDK()
  const serverSync = useServerSync()
  const providers = useProviders()
  const [accountsResult, { refetch: refreshAccounts }] = createResource(async () => {
    return serverSDK()
      .client.auth.list({ throwOnError: true })
      .then((response) => ({ accounts: response.data ?? [], error: undefined }))
      .catch((error: unknown) => ({
        accounts: [] as AuthAccount[],
        error: requestError(error),
      }))
  })
  const [mutating, setMutating] = createStore<Record<string, "select" | "remove" | undefined>>({})
  const providerConnect = useProviderConnectController({ onBack: props.onBack, onConnected: refreshAccounts })

  const connect = (provider?: string) => {
    providerConnect.select(provider)
    void dialog.show(() => <DialogConnectProvider controller={providerConnect} />)
  }

  const connected = createMemo(() => {
    return providers
      .connected()
      .filter((p) => p.id !== "opencode" || Object.values(p.models).find((m) => m.cost?.input))
  })

  const popular = createMemo(() => {
    const connectedIDs = new Set(connected().map((p) => p.id))
    const items = providers
      .popular()
      .filter((p) => !connectedIDs.has(p.id))
      .slice()
    items.sort((a, b) => popularProviders.indexOf(a.id) - popularProviders.indexOf(b.id))
    return items
  })

  const source = (item: ProviderItem): ProviderSource | undefined => {
    if (!("source" in item)) return
    const value = item.source
    if (value === "env" || value === "api" || value === "config" || value === "custom") return value
    return
  }

  const type = (item: ProviderItem) => {
    const current = source(item)
    if (current === "env") return language.t("settings.providers.tag.environment")
    if (current === "api") return language.t("provider.connect.method.apiKey")
    if (current === "config") {
      if (isConfigCustom(item.id)) return language.t("settings.providers.tag.custom")
      return language.t("settings.providers.tag.config")
    }
    if (current === "custom") return language.t("settings.providers.tag.custom")
    return language.t("settings.providers.tag.other")
  }

  const accounts = (providerID: string) =>
    (accountsResult()?.accounts ?? []).filter((account) => account.providerID === providerID)

  const canManage = (item: ProviderItem) => source(item) !== "env" || accounts(item.id).length > 0

  const note = (id: string) => PROVIDER_NOTES.find((item) => item.match(id))?.key

  const isConfigCustom = (providerID: string) => {
    const provider = serverSync().data.config.provider?.[providerID]
    if (!provider) return false
    if (provider.npm !== "@ai-sdk/openai-compatible") return false
    if (!provider.models || Object.keys(provider.models).length === 0) return false
    return true
  }

  const selectAccount = async (account: AuthAccount) => {
    setMutating(account.id, "select")
    await serverSDK()
      .client.auth.select({ providerID: account.providerID, accountID: account.id }, { throwOnError: true })
      .then(async () => {
        await serverSDK().client.global.dispose()
        await refreshAccounts()
        showToast({
          variant: "success",
          icon: "circle-check",
          title: language.t("settings.providers.account.select.success", { account: account.label }),
        })
      })
      .catch((error: unknown) => {
        showToast({ title: language.t("settings.providers.account.select.error"), description: requestError(error) })
      })
    setMutating(account.id, undefined)
  }

  const removeAccount = async (account: AuthAccount) => {
    setMutating(account.id, "remove")
    await serverSDK()
      .client.auth.removeAccount({ providerID: account.providerID, accountID: account.id }, { throwOnError: true })
      .then(async () => {
        await serverSDK().client.global.dispose()
        await refreshAccounts()
        showToast({
          variant: "success",
          icon: "circle-check",
          title: language.t("settings.providers.account.remove.success", { account: account.label }),
        })
      })
      .catch((error: unknown) => {
        showToast({ title: language.t("settings.providers.account.remove.error"), description: requestError(error) })
      })
    setMutating(account.id, undefined)
  }

  return (
    <div class="flex flex-col h-full overflow-y-auto no-scrollbar px-4 pb-10 sm:px-10 sm:pb-10">
      <div class="sticky top-0 z-10 bg-[linear-gradient(to_bottom,var(--surface-stronger-non-alpha)_calc(100%_-_24px),transparent)]">
        <div class="flex items-center justify-between gap-4 pt-6 pb-8 max-w-[720px]">
          <h2 class="text-16-medium text-text-strong">{language.t("settings.providers.title")}</h2>
          <SettingsServerPicker />
        </div>
      </div>

      <div class="flex flex-col gap-8 max-w-[720px]">
        <div class="flex flex-col gap-1" data-component="connected-providers-section">
          <h3 class="text-14-medium text-text-strong pb-2">{language.t("settings.providers.section.connected")}</h3>
          <Show when={accountsResult.loading}>
            <p class="text-14-regular text-text-weak pb-2">{language.t("settings.providers.accounts.loading")}</p>
          </Show>
          <Show when={accountsResult()?.error}>
            <p class="text-14-regular text-text-critical-base pb-2">
              {language.t("settings.providers.accounts.loadError")}
            </p>
          </Show>
          <SettingsList>
            <Show
              when={connected().length > 0}
              fallback={
                <div class="py-4 text-14-regular text-text-weak">
                  {language.t("settings.providers.connected.empty")}
                </div>
              }
            >
              <For each={connected()}>
                {(item) => {
                  const providerAccounts = () => accounts(item.id)
                  return (
                    <div class="group flex flex-col gap-2 py-3 border-b border-border-weak-base last:border-none">
                      <div class="flex flex-wrap items-center justify-between gap-4 min-h-8">
                        <div class="flex items-center gap-3 min-w-0">
                          <ProviderIcon id={item.id} class="size-5 shrink-0 icon-strong-base" />
                          <span class="text-14-medium text-text-strong truncate">{item.name}</span>
                          <Tag>{type(item)}</Tag>
                        </div>
                        <Show
                          when={canManage(item)}
                          fallback={
                            <span class="text-14-regular text-text-base opacity-0 group-hover:opacity-100 transition-opacity duration-200 pr-3 cursor-default">
                              {language.t("settings.providers.connected.environmentDescription")}
                            </span>
                          }
                        >
                          <Button
                            size="large"
                            variant="ghost"
                            icon="plus-small"
                            onClick={() => connect(item.id)}
                          >
                            {language.t("settings.providers.account.add")}
                          </Button>
                        </Show>
                      </div>
                      <Show when={providerAccounts().length > 0}>
                        <div class="flex flex-col pl-8">
                          <For each={providerAccounts()}>
                            {(account) => (
                              <div class="flex flex-wrap items-center justify-between gap-3 py-1.5">
                                <div class="flex items-center gap-2 min-w-0">
                                  <span class="text-14-regular text-text-strong truncate">{account.label}</span>
                                  <span class="text-12-regular text-text-weak">
                                    {account.type === "api"
                                      ? language.t("provider.connect.method.apiKey")
                                      : account.type === "oauth"
                                        ? language.t("settings.providers.account.oauth")
                                        : language.t("settings.providers.account.managed")}
                                  </span>
                                  <Show when={account.active}>
                                    <Tag>{language.t("settings.providers.account.active")}</Tag>
                                  </Show>
                                </div>
                                <div class="flex items-center gap-1">
                                  <Show when={!account.active}>
                                    <Button
                                      size="small"
                                      variant="ghost"
                                      disabled={!!mutating[account.id]}
                                      onClick={() => void selectAccount(account)}
                                    >
                                      {mutating[account.id] === "select"
                                        ? language.t("settings.providers.account.switching")
                                        : language.t("settings.providers.account.use")}
                                    </Button>
                                  </Show>
                                  <Button
                                    size="small"
                                    variant="ghost"
                                    disabled={!!mutating[account.id]}
                                    onClick={() => void removeAccount(account)}
                                  >
                                    {mutating[account.id] === "remove"
                                      ? language.t("settings.providers.account.removing")
                                      : language.t("settings.providers.account.remove")}
                                  </Button>
                                </div>
                              </div>
                            )}
                          </For>
                        </div>
                      </Show>
                    </div>
                  )
                }}
              </For>
            </Show>
          </SettingsList>
        </div>

        <div class="flex flex-col gap-1">
          <h3 class="text-14-medium text-text-strong pb-2">{language.t("settings.providers.section.popular")}</h3>
          <SettingsList>
            <For each={popular()}>
              {(item) => (
                <div class="flex flex-wrap items-center justify-between gap-4 min-h-16 py-3 border-b border-border-weak-base last:border-none">
                  <div class="flex flex-col min-w-0">
                    <div class="flex items-center gap-x-3">
                      <ProviderIcon id={item.id} class="size-5 shrink-0 icon-strong-base" />
                      <span class="text-14-medium text-text-strong">{item.name}</span>
                      <Show when={item.id === "opencode"}>
                        <Tag>{language.t("dialog.provider.tag.recommended")}</Tag>
                      </Show>
                      <Show when={item.id === "opencode-go"}>
                        <Tag>{language.t("dialog.provider.tag.recommended")}</Tag>
                      </Show>
                    </div>
                    <Show when={note(item.id)}>
                      {(key) => <span class="text-12-regular text-text-weak pl-8">{language.t(key())}</span>}
                    </Show>
                  </div>
                  <Button size="large" variant="secondary" icon="plus-small" onClick={() => connect(item.id)}>
                    {language.t("common.connect")}
                  </Button>
                </div>
              )}
            </For>

            <div
              class="flex items-center justify-between gap-4 min-h-16 border-b border-border-weak-base last:border-none flex-wrap py-3"
              data-component="custom-provider-section"
            >
              <div class="flex flex-col min-w-0">
                <div class="flex flex-wrap items-center gap-x-3 gap-y-1">
                  <ProviderIcon id="synthetic" class="size-5 shrink-0 icon-strong-base" />
                  <span class="text-14-medium text-text-strong">{language.t("provider.custom.title")}</span>
                  <Tag>{language.t("settings.providers.tag.custom")}</Tag>
                </div>
                <span class="text-12-regular text-text-weak pl-8">
                  {language.t("settings.providers.custom.description")}
                </span>
              </div>
              <Button
                size="large"
                variant="secondary"
                icon="plus-small"
                onClick={() => {
                  dialog.show(() => <DialogCustomProvider onBack={dialog.close} />)
                }}
              >
                {language.t("common.connect")}
              </Button>
            </div>
          </SettingsList>

          <Button
            variant="ghost"
            class="px-0 py-0 mt-5 text-14-medium text-text-interactive-base text-left justify-start hover:bg-transparent active:bg-transparent"
            onClick={() => connect()}
          >
            {language.t("dialog.provider.viewAll")}
          </Button>
        </div>
      </div>
    </div>
  )
}

function requestError(error: unknown) {
  if (error && typeof error === "object" && "data" in error) {
    const data = (error as { data?: { message?: unknown } }).data
    if (typeof data?.message === "string") return data.message
  }
  if (error instanceof Error) return error.message
  return String(error)
}
