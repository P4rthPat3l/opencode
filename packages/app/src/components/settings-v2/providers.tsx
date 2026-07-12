import { ButtonV2 } from "@opencode-ai/ui/v2/button-v2"
import { Tag } from "@opencode-ai/ui/v2/badge-v2"
import type { AuthAccount } from "@opencode-ai/sdk/v2/client"
import { useDialog } from "@opencode-ai/ui/context/dialog"
import { ProviderIcon } from "@opencode-ai/ui/provider-icon"
import { showToast } from "@/utils/toast"
import { popularProviders, useProviders } from "@/hooks/use-providers"
import { createMemo, createResource, type Component, For, Show } from "solid-js"
import { createStore } from "solid-js/store"
import { useLanguage } from "@/context/language"
import { useServerSDK } from "@/context/server-sdk"
import { useServerSync } from "@/context/server-sync"
import { DialogConnectProvider, useProviderConnectController } from "../dialog-connect-provider"
import { DialogCustomProvider } from "../dialog-custom-provider"
import { SettingsListV2 } from "./parts/list"
import "./settings-v2.css"

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

const PROVIDER_ICON_SIZE = 16

export const SettingsProvidersV2: Component<{ onBack?: () => void }> = (props) => {
  const dialog = useDialog()
  const language = useLanguage()
  const serverSdk = useServerSDK()
  const serverSync = useServerSync()
  const providers = useProviders()
  const [accountsResult, { refetch: refreshAccounts }] = createResource(async () => {
    return serverSdk()
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
    await serverSdk()
      .client.auth.select({ providerID: account.providerID, accountID: account.id }, { throwOnError: true })
      .then(async () => {
        await serverSdk().client.global.dispose()
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
    await serverSdk()
      .client.auth.removeAccount({ providerID: account.providerID, accountID: account.id }, { throwOnError: true })
      .then(async () => {
        await serverSdk().client.global.dispose()
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
    <>
      <div class="settings-v2-tab-header">
        <h2 class="settings-v2-tab-title">{language.t("settings.providers.title")}</h2>
      </div>

      <div class="settings-v2-tab-body settings-v2-providers">
        <div class="settings-v2-section" data-component="connected-providers-section">
          <h3 class="settings-v2-section-title">{language.t("settings.providers.section.connected")}</h3>
          <Show when={accountsResult.loading}>
            <p class="settings-v2-provider-account-status">{language.t("settings.providers.accounts.loading")}</p>
          </Show>
          <Show when={accountsResult()?.error}>
            <p class="settings-v2-provider-account-status settings-v2-provider-account-status--error">
              {language.t("settings.providers.accounts.loadError")}
            </p>
          </Show>
          <SettingsListV2>
            <Show
              when={connected().length > 0}
              fallback={
                <div class="settings-v2-provider-empty">{language.t("settings.providers.connected.empty")}</div>
              }
            >
              <For each={connected()}>
                {(item) => {
                  const providerAccounts = () => accounts(item.id)
                  return (
                    <div class="settings-v2-provider-row settings-v2-provider-row--accounts group">
                      <div class="settings-v2-provider-header">
                        <div class="settings-v2-provider-lead">
                          <ProviderIcon
                            id={item.id}
                            width={PROVIDER_ICON_SIZE}
                            height={PROVIDER_ICON_SIZE}
                            class="settings-v2-provider-icon shrink-0"
                          />
                          <div class="settings-v2-provider-main">
                            <span class="settings-v2-provider-name truncate">{item.name}</span>
                            <Tag>{type(item)}</Tag>
                          </div>
                        </div>
                        <Show
                          when={canManage(item)}
                          fallback={
                            <span class="settings-v2-provider-env-hint">
                              {language.t("settings.providers.connected.environmentDescription")}
                            </span>
                          }
                        >
                          <ButtonV2 size="normal" variant="ghost-muted" icon="plus" onClick={() => connect(item.id)}>
                            {language.t("settings.providers.account.add")}
                          </ButtonV2>
                        </Show>
                      </div>
                      <Show when={providerAccounts().length > 0}>
                        <div class="settings-v2-provider-accounts">
                          <For each={providerAccounts()}>
                            {(account) => (
                              <div class="settings-v2-provider-account">
                                <div class="settings-v2-provider-account-copy">
                                  <span class="settings-v2-provider-account-name">{account.label}</span>
                                  <span class="settings-v2-provider-account-type">
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
                                <div class="settings-v2-provider-account-actions">
                                  <Show when={!account.active}>
                                    <ButtonV2
                                      size="small"
                                      variant="ghost-muted"
                                      disabled={!!mutating[account.id]}
                                      onClick={() => void selectAccount(account)}
                                    >
                                      {mutating[account.id] === "select"
                                        ? language.t("settings.providers.account.switching")
                                        : language.t("settings.providers.account.use")}
                                    </ButtonV2>
                                  </Show>
                                  <ButtonV2
                                    size="small"
                                    variant="ghost-muted"
                                    disabled={!!mutating[account.id]}
                                    onClick={() => void removeAccount(account)}
                                  >
                                    {mutating[account.id] === "remove"
                                      ? language.t("settings.providers.account.removing")
                                      : language.t("settings.providers.account.remove")}
                                  </ButtonV2>
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
          </SettingsListV2>
        </div>

        <div class="settings-v2-section">
          <h3 class="settings-v2-section-title">{language.t("settings.providers.section.popular")}</h3>
          <SettingsListV2>
            <For each={popular()}>
              {(item) => (
                <div class="settings-v2-provider-row">
                  <div class="settings-v2-provider-lead">
                    <ProviderIcon
                      id={item.id}
                      width={PROVIDER_ICON_SIZE}
                      height={PROVIDER_ICON_SIZE}
                      class="settings-v2-provider-icon shrink-0"
                    />
                    <div class="settings-v2-provider-copy">
                      <div class="settings-v2-provider-main">
                        <span class="settings-v2-provider-name">{item.name}</span>
                        <Show when={item.id === "opencode" || item.id === "opencode-go"}>
                          <Tag>{language.t("dialog.provider.tag.recommended")}</Tag>
                        </Show>
                      </div>
                      <Show when={note(item.id)}>
                        {(key) => <p class="settings-v2-provider-description">{language.t(key())}</p>}
                      </Show>
                    </div>
                  </div>
                  <ButtonV2 size="normal" variant="neutral" icon="plus" onClick={() => connect(item.id)}>
                    {language.t("common.connect")}
                  </ButtonV2>
                </div>
              )}
            </For>

            <div class="settings-v2-provider-row" data-component="custom-provider-section">
              <div class="settings-v2-provider-lead">
                <ProviderIcon
                  id="synthetic"
                  width={PROVIDER_ICON_SIZE}
                  height={PROVIDER_ICON_SIZE}
                  class="settings-v2-provider-icon shrink-0"
                />
                <div class="settings-v2-provider-copy">
                  <div class="settings-v2-provider-main">
                    <span class="settings-v2-provider-name">{language.t("provider.custom.title")}</span>
                    <Tag>{language.t("settings.providers.tag.custom")}</Tag>
                  </div>
                  <p class="settings-v2-provider-description">{language.t("settings.providers.custom.description")}</p>
                </div>
              </div>
              <ButtonV2
                size="normal"
                variant="neutral"
                icon="plus"
                onClick={() => {
                  dialog.show(() => <DialogCustomProvider onBack={dialog.close} />)
                }}
              >
                {language.t("common.connect")}
              </ButtonV2>
            </div>
          </SettingsListV2>

          <button type="button" class="settings-v2-providers-view-all" onClick={() => connect()}>
            {language.t("dialog.provider.viewAll")}
          </button>
        </div>
      </div>
    </>
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
