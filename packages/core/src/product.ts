export const id = "p4rth-opencode"
export const name = "P4rth OpenCode"
export const runtimeChannel = "jetbrains-stable"
export const protocolVersion = 1
export const jetbrainsBridgeVersion = 1
export const storageSchemaVersion = 1
export const envPrefix = "P4RTH_OPENCODE"

export const capabilities = [
  "jetbrains-context",
  "voice-input",
  "provider-multi-account",
  "provider-account-switching",
] as const

export * as Product from "./product"
