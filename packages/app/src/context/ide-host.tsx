import { createEffect, onCleanup } from "solid-js"
import { useParams } from "@solidjs/router"
import { usePrompt } from "./prompt"
import { useSync } from "./sync"
import {
  ideContextToContextItems,
  isIdeActive,
  mergeIdeActivity,
  type IdeActivityState,
  type IdeContext,
  type IdeFile,
  type IdeSelection,
} from "./ide-host-context"

export const IDE_HOST_BRIDGE_VERSION = 1

export type IdeHostBridge = {
  version: number
  getContext?(options?: {
    includeSelection?: boolean
    includeOpenFiles?: boolean
    includeCaret?: boolean
  }): Promise<IdeContext>
  openFile?(request: { path: string; line?: number; column?: number }): Promise<void>
  reportActivity?(state: IdeActivityState): void
  notifyReady?(): void
}

export type IdeWebBridge = {
  version: number
  addContext(context: IdeContext): Promise<{ accepted: number }>
  /** Optional: host calls this right before disposing the embedded browser so the web can
   * stop microphone tracks and release resources gracefully. Page teardown also releases them. */
  releaseForHostDispose?(): void
}

declare global {
  interface Window {
    __P4RTH_OPENCODE_IDE__?: IdeHostBridge
    __P4RTH_OPENCODE_IDE_HOST__?: IdeWebBridge
  }
}

export function requestIdeEditorContext() {
  return window.__P4RTH_OPENCODE_IDE__?.getContext?.({
    includeSelection: false,
    includeOpenFiles: true,
    includeCaret: true,
  })
}

export function IdeHostPromptBridge() {
  const prompt = usePrompt()

  createEffect(() => {
    const bridge: IdeWebBridge = {
      version: IDE_HOST_BRIDGE_VERSION,
      async addContext(context) {
        const items = ideContextToContextItems(context)
        for (const item of items) prompt.context.add(item)
        return { accepted: items.length }
      },
    }
    window.__P4RTH_OPENCODE_IDE_HOST__ = bridge
    window.__P4RTH_OPENCODE_IDE__?.notifyReady?.()

    onCleanup(() => {
      if (window.__P4RTH_OPENCODE_IDE_HOST__ === bridge) delete window.__P4RTH_OPENCODE_IDE_HOST__
    })
  })

  IdeHostActivityReporter()

  return null
}

/**
 * Pushes coarse activity flags to the JetBrains host whenever they change, so the host can
 * refuse to dispose the embedded browser mid-operation. In a plain browser (no host bridge)
 * every push is a no-op.
 *
 * Signals wired from server-sync: an active session that is working feeds `streaming` and
 * `toolRunning`; any pending permission feeds `permissionPending`. Auth, file-operation, and
 * voice flags are part of the contract and enforced by the host, but are conservatively left
 * idle here until their web state is surfaced.
 */
function IdeHostActivityReporter() {
  const params = useParams<{ id?: string }>()
  const sync = useSync()

  const activity = (): IdeActivityState => {
    const id = params.id
    const data = sync().data
    const working = id ? data.session_working(id) : false
    const permissions = data.permission ?? {}
    const permissionPending = id
      ? (permissions[id]?.length ?? 0) > 0
      : Object.values(permissions).some((list) => (list?.length ?? 0) > 0)
    return mergeIdeActivity({ streaming: working, toolRunning: working, permissionPending })
  }

  let last = ""
  createEffect(() => {
    const state = activity()
    const serialized = JSON.stringify(state)
    if (serialized === last) return
    last = serialized
    window.__P4RTH_OPENCODE_IDE__?.reportActivity?.(state)
  })

  onCleanup(() => {
    // On teardown report idle so a host that was blocking disposal can proceed.
    if (isIdeActive(JSON.parse(last || "{}"))) window.__P4RTH_OPENCODE_IDE__?.reportActivity?.(mergeIdeActivity({}))
  })
}

export type { IdeActivityState, IdeContext, IdeFile, IdeSelection }
