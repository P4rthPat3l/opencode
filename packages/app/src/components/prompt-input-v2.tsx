import { ImagePreview } from "@opencode-ai/ui/image-preview"
import { useDialog } from "@opencode-ai/ui/context/dialog"
import { ProviderIcon } from "@opencode-ai/ui/provider-icon"
import { ButtonV2 } from "@opencode-ai/ui/v2/button-v2"
import { Icon } from "@opencode-ai/ui/v2/icon"
import { KeybindV2 } from "@opencode-ai/ui/v2/keybind-v2"
import { LoaderV2 } from "@opencode-ai/ui/v2/loader-v2"
import { TooltipV2 } from "@opencode-ai/ui/v2/tooltip-v2"
import type { Prompt, ReferenceInfo } from "@opencode-ai/sdk/v2/client"
import { createEffect, createMemo, createSignal, For, Match, on, onCleanup, onMount, Show, Switch } from "solid-js"
import { createStore } from "solid-js/store"
import { ModelSelectorPopoverV2 } from "@/components/dialog-select-model"
import { DialogSelectModelUnpaidV2 } from "@/components/dialog-select-model-unpaid-v2"
import type { PromptInputProps } from "@/components/prompt-input/contracts"
import { normalizePromptHistoryEntry, promptLength, type PromptHistoryComment } from "@/components/prompt-input/history"
import { createPersistedPromptInputHistory } from "@/components/prompt-input/history-store"
import { promptDesignPlaceholder, promptPlaceholder } from "@/components/prompt-input/placeholder"
import { createPromptSubmit } from "@/components/prompt-input/submit"
import { blobToBase64, startVoiceRecorder, type VoiceRecorder } from "@/components/prompt-input/voice"
import { selectionFromLines, type SelectedLineRange, useFile } from "@/context/file"
import { useComments } from "@/context/comments"
import { useCommand } from "@/context/command"
import { useLanguage } from "@/context/language"
import { useLayout } from "@/context/layout"
import { usePermission } from "@/context/permission"
import { type ImageAttachmentPart, usePrompt } from "@/context/prompt"
import { usePlatform } from "@/context/platform"
import { useSDK } from "@/context/sdk"
import { useSync } from "@/context/sync"
import { createSessionTabs } from "@/pages/session/helpers"
import { showToast } from "@/utils/toast"
import { PromptInputV2, type PromptInputV2Suggestion } from "@opencode-ai/session-ui/v2/prompt-input"
import {
  createPromptInputV2Controller,
  createPromptInputV2State,
  type PromptInputV2Interaction,
} from "@opencode-ai/session-ui/v2/prompt-input/interaction"

export type PromptInputV2ComposerProps = {
  class?: string
  controller: PromptInputV2ComposerController
  edit?: PromptInputProps["edit"]
  onEditLoaded?: PromptInputProps["onEditLoaded"]
}

export type PromptInputV2ControllerProps = Omit<PromptInputProps, "class" | "edit" | "onEditLoaded" | "submission">
export type PromptInputV2ComposerController = PromptInputV2Interaction & {
  readonly model: PromptInputProps["controls"]["model"]
  readonly autoAccept: { active: () => boolean; toggle: () => void }
}

export function PromptInputV2Composer(props: PromptInputV2ComposerProps) {
  const dialog = useDialog()
  const command = useCommand()
  const language = useLanguage()

  useCommands(props)
  useEditHandler(props)

  return (
    <div class="flex flex-col gap-3">
      <PromptInputV2
        controller={props.controller}
        class={props.class}
        controls={
          <>
            <PromptInputV2VoiceControl controller={props.controller} />
            <PromptInputV2AutoAcceptControl controller={props.controller} />
          </>
        }
        modelControl={
          <PromptInputV2ModelControl
            loading={props.controller.model.loading}
            paid={props.controller.model.paid}
            title={language.t("command.model.choose")}
            keybind={command.keybindParts("model.choose")}
            model={props.controller.model.selection}
            providerID={props.controller.model.selection.current()?.provider?.id}
            modelName={props.controller.model.selection.current()?.name ?? language.t("dialog.model.select.title")}
            onClose={props.controller.restoreFocus}
            onUnpaidClick={() =>
              dialog.show(() => <DialogSelectModelUnpaidV2 model={props.controller.model.selection} />)
            }
          />
        }
      />
    </div>
  )
}

const useEditHandler = (props: PromptInputV2ComposerProps) => {
  const prompt = usePrompt()

  createEffect(
    on(
      () => props.edit?.id,
      (id) => {
        const edit = props.edit
        if (!id || !edit) return
        prompt.context.items().forEach((item) => prompt.context.remove(item.key))
        edit.context.forEach((item) =>
          prompt.context.add({
            type: item.type,
            path: item.path,
            selection: item.selection,
            comment: item.comment,
            commentID: item.commentID,
            commentOrigin: item.commentOrigin,
            preview: item.preview,
          }),
        )
        props.controller.dispatch({ type: "mode.normal" })
        props.controller.resetHistory()
        prompt.set(edit.prompt, promptLength(edit.prompt))
        props.controller.restoreFocus()
        props.onEditLoaded?.()
      },
      { defer: true },
    ),
  )
}

const useCommands = (props: PromptInputV2ComposerProps) => {
  const command = useCommand()
  const language = useLanguage()

  command.register("prompt-input", () => [
    {
      id: "file.attach",
      title: language.t("prompt.action.attachFile"),
      category: language.t("command.category.file"),
      keybind: "mod+u",
      disabled: props.controller.state.mode !== "normal",
      onSelect: () => props.controller.attach(),
    },
    {
      id: "prompt.mode.shell",
      title: language.t("command.prompt.mode.shell"),
      category: language.t("command.category.session"),
      keybind: "mod+shift+x",
      disabled: props.controller.state.mode === "shell",
      onSelect: () => props.controller.dispatch({ type: "mode.shell" }),
    },
    {
      id: "prompt.mode.normal",
      title: language.t("command.prompt.mode.normal"),
      category: language.t("command.category.session"),
      keybind: "mod+shift+e",
      disabled: props.controller.state.mode === "normal",
      onSelect: () => props.controller.dispatch({ type: "mode.normal" }),
    },
  ])
}

function PromptInputV2VoiceControl(props: { controller: PromptInputV2ComposerController }) {
  const sdk = useSDK()
  const [voice, setVoice] = createStore({
    starting: false,
    recording: false,
    transcribing: false,
    held: false,
    stopRequested: false,
    level: 0,
  })
  const [elapsed, setElapsed] = createSignal(0)
  let timer: ReturnType<typeof setInterval> | undefined
  let recorder: VoiceRecorder | undefined
  let disposed = false
  const active = () => voice.starting || voice.recording || voice.transcribing
  const disabled = createMemo(
    () =>
      props.controller.state.mode !== "normal" ||
      !!props.controller.view.submit.working?.() ||
      voice.starting ||
      voice.transcribing ||
      typeof navigator.mediaDevices?.getUserMedia !== "function",
  )
  const label = createMemo(() => {
    if (voice.recording) return "Stop recording and transcribe locally"
    if (voice.starting) return "Opening microphone…"
    if (voice.transcribing) return "Transcribing locally…"
    return "Start local dictation"
  })
  const tip = createMemo(() => {
    if (voice.recording) return "Release Ctrl+Space or click Stop to transcribe"
    if (voice.starting) return "Opening microphone…"
    if (voice.transcribing) return "Preparing local transcription. First run may download the speech model…"
    if (props.controller.state.mode === "shell") return "Dictation is available for prompts, not shell commands"
    if (props.controller.view.submit.working?.()) return "Dictation is available when opencode is idle"
    if (typeof navigator.mediaDevices?.getUserMedia !== "function")
      return "Voice dictation is not supported in this browser"
    return "Hold Ctrl+Space to dictate locally, or click to record"
  })
  const elapsedLabel = () => {
    const total = Math.floor(elapsed() / 1000)
    return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, "0")}`
  }
  const stopTimer = () => {
    if (timer !== undefined) clearInterval(timer)
    timer = undefined
  }

  const start = async (held = false) => {
    if (disabled() || voice.recording) return
    setVoice({ starting: true, held, stopRequested: false, level: 0 })
    try {
      const next = await startVoiceRecorder({ onLevel: (level) => setVoice("level", level) })
      if (disposed) {
        await next.cancel()
        return
      }
      recorder = next
      setVoice({ starting: false, recording: true })
      setElapsed(0)
      const startedAt = Date.now()
      stopTimer()
      timer = setInterval(() => setElapsed(Date.now() - startedAt), 200)
      if (voice.stopRequested) void stop()
    } catch (error) {
      setVoice({ starting: false, held: false, stopRequested: false, level: 0 })
      showToast({ variant: "error", title: voiceStartErrorMessage(error) })
    }
  }

  const stop = async () => {
    if (voice.starting) {
      setVoice({ held: false, stopRequested: true })
      return
    }
    const current = recorder
    if (!current || !voice.recording) return
    recorder = undefined
    stopTimer()
    setVoice({ recording: false, held: false, transcribing: true, stopRequested: false, level: 0 })
    try {
      const result = await sdk().client.experimental.transcribe({
        experimentalTranscribePayload: { audio: await blobToBase64(await current.stop()) },
      })
      if (result.error) throw new Error(voiceTranscribeErrorMessage(result.error))
      const text = result.data?.text.trim() ?? ""
      if (text) props.controller.addPart({ type: "text", content: text, start: 0, end: 0 })
      if (!text) showToast({ title: "No speech detected" })
    } catch (error) {
      showToast({ variant: "error", title: error instanceof Error ? error.message : "Voice transcription failed" })
    } finally {
      setVoice("transcribing", false)
      props.controller.restoreFocus()
    }
  }

  const toggle = () => {
    if (voice.recording) {
      void stop()
      return
    }
    void start()
  }

  onMount(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (!event.ctrlKey || event.code !== "Space" || event.repeat) return
      const active = document.activeElement
      if (!(active instanceof HTMLElement) || !active.closest('[data-component="prompt-input-v2"]')) return
      event.preventDefault()
      void start(true)
    }
    const onKeyUp = (event: KeyboardEvent) => {
      if (!voice.held || event.code !== "Space") return
      event.preventDefault()
      void stop()
    }
    document.addEventListener("keydown", onKeyDown, true)
    document.addEventListener("keyup", onKeyUp)
    onCleanup(() => {
      disposed = true
      stopTimer()
      document.removeEventListener("keydown", onKeyDown, true)
      document.removeEventListener("keyup", onKeyUp)
      void recorder?.cancel()
    })
  })

  const bars = [0.45, 0.8, 1, 0.7, 0.4]
  const barHeight = (weight: number) => `${Math.max(20, Math.min(100, voice.level * 600 * weight))}%`

  return (
    <Show
      when={active()}
      fallback={
        <TooltipV2 placement="top" value={tip()}>
          <ButtonV2
            data-action="prompt-voice"
            variant="ghost-muted"
            size="normal"
            class="!px-2"
            style={{ height: "28px" }}
            disabled={disabled()}
            aria-label={label()}
            onClick={toggle}
          >
            <Icon name="microphone" />
          </ButtonV2>
        </TooltipV2>
      }
    >
      <Switch>
        <Match when={voice.recording}>
          <button
            type="button"
            data-action="prompt-voice-stop"
            onClick={() => void stop()}
            aria-label="Stop recording and transcribe"
            class="flex h-7 items-center gap-2 rounded-full border border-red-500/25 bg-red-500/10 pl-2 pr-1.5 text-red-500 transition-colors hover:bg-red-500/[0.16]"
          >
            <span class="relative flex size-2">
              <span class="absolute inline-flex size-full animate-ping rounded-full bg-red-500 opacity-60" />
              <span class="relative inline-flex size-2 rounded-full bg-red-500" />
            </span>
            <span class="flex h-3.5 items-end gap-[2px]" aria-hidden="true">
              <For each={bars}>
                {(weight) => (
                  <span
                    class="w-[2px] rounded-full bg-current"
                    style={{ height: barHeight(weight), transition: "height 90ms linear" }}
                  />
                )}
              </For>
            </span>
            <span class="text-xs font-medium tabular-nums">{elapsedLabel()}</span>
            <Icon name="stop" />
          </button>
        </Match>
        <Match when={voice.starting || voice.transcribing}>
          <div class="flex h-7 items-center gap-1.5 rounded-full px-2 text-v2-text-text-muted">
            <LoaderV2 class="size-4" />
            <span class="text-xs font-medium">{voice.starting ? "Opening mic…" : "Transcribing…"}</span>
          </div>
        </Match>
      </Switch>
    </Show>
  )
}

function PromptInputV2AutoAcceptControl(props: { controller: PromptInputV2ComposerController }) {
  const command = useCommand()
  const language = useLanguage()
  const active = () => props.controller.autoAccept.active()
  const label = createMemo(() =>
    language.t(active() ? "command.permissions.autoaccept.disable" : "command.permissions.autoaccept.enable"),
  )

  return (
    <TooltipV2
      placement="top"
      value={
        <>
          {label()}
          <KeybindV2 keys={command.keybindParts("permissions.autoaccept")} variant="neutral" />
        </>
      }
    >
      <ButtonV2
        data-action="prompt-permissions"
        variant="ghost-muted"
        size="normal"
        class="size-7 shrink-0 p-0"
        classList={{ "text-v2-state-fg-success": active() }}
        onClick={props.controller.autoAccept.toggle}
        aria-label={label()}
        aria-pressed={active()}
      >
        <Icon name="check" size="small" />
      </ButtonV2>
    </TooltipV2>
  )
}

function voiceStartErrorMessage(error: unknown) {
  if (error instanceof DOMException && error.name === "NotAllowedError") {
    return "Microphone permission is blocked. Allow mic access to dictate locally."
  }
  if (error instanceof DOMException && error.name === "NotFoundError") return "No microphone was found"
  return error instanceof Error ? error.message : "Microphone access failed"
}

function voiceTranscribeErrorMessage(error: unknown): string {
  if (error instanceof Error) return error.message
  if (error && typeof error === "object" && "message" in error && typeof error.message === "string") {
    return error.message
  }
  if (error && typeof error === "object" && "data" in error) return voiceTranscribeErrorMessage(error.data)
  return "Voice transcription failed"
}

export function usePromptInputV2Controller(props: PromptInputV2ControllerProps): PromptInputV2ComposerController {
  const sdk = useSDK()
  const sync = useSync()
  const files = useFile()
  const layout = useLayout()
  const comments = useComments()
  const dialog = useDialog()
  const command = useCommand()
  const permission = usePermission()
  const language = useLanguage()
  const platform = usePlatform()
  const prompt = props.state ?? usePrompt()
  let editor: HTMLDivElement | undefined

  const interaction = createPromptInputV2State()
  const mode = () => interaction[0].mode
  const history = props.history ?? createPersistedPromptInputHistory()
  const tabs = () => props.controls.session.tabs
  const activeFileTab = createSessionTabs({
    tabs,
    pathFromTab: files.pathFromTab,
    normalizeTab: (tab) => (tab.startsWith("file://") ? files.tab(tab) : tab),
  }).activeFileTab
  const recent = createMemo(() => {
    const all = tabs().all()
    const active = activeFileTab()
    const order = active ? [active, ...all.filter((tab) => tab !== active)] : all
    return order.reduce<string[]>((result, tab) => {
      const path = files.pathFromTab(tab)
      if (!path || result.includes(path)) return result
      return [...result, path]
    }, [])
  })
  const info = createMemo(() => (props.controls.session.id ? sync().session.get(props.controls.session.id) : undefined))
  const working = createMemo(() => sync().data.session_working(props.controls.session.id ?? ""))
  const attachments = createMemo(() =>
    prompt.current().filter((part): part is ImageAttachmentPart => part.type === "image"),
  )
  const commentCount = createMemo(() => {
    if (mode() === "shell") return 0
    return prompt.context.items().filter((item) => !!item.comment?.trim()).length
  })
  const blank = createMemo(() => {
    const text = prompt
      .current()
      .map((part) => ("content" in part ? part.content : ""))
      .join("")
    return text.trim().length === 0 && attachments().length === 0 && commentCount() === 0
  })
  const stopping = createMemo(() => working() && blank())
  const placeholder = createMemo(() =>
    promptPlaceholder({
      mode: mode(),
      commentCount: commentCount(),
      example: mode() === "shell" ? "git status" : "",
      suggest: false,
      t: (key, params) => language.t(key as Parameters<typeof language.t>[0], params as never),
    }),
  )
  const designPlaceholder = () => promptDesignPlaceholder(mode(), placeholder())

  const historyComments = () => {
    const byID = new Map(comments.all().map((item) => [`${item.file}\n${item.id}`, item] as const))
    return prompt.context.items().flatMap((item) => {
      const comment = item.comment?.trim()
      if (!comment) return []
      const selection = item.commentID ? byID.get(`${item.path}\n${item.commentID}`)?.selection : undefined
      const nextSelection =
        selection ??
        (item.selection
          ? ({ start: item.selection.startLine, end: item.selection.endLine } satisfies SelectedLineRange)
          : undefined)
      if (!nextSelection) return []
      return [
        {
          id: item.commentID ?? item.key,
          path: item.path,
          selection: { ...nextSelection },
          comment,
          time: item.commentID ? (byID.get(`${item.path}\n${item.commentID}`)?.time ?? Date.now()) : Date.now(),
          origin: item.commentOrigin,
          preview: item.preview,
        } satisfies PromptHistoryComment,
      ]
    })
  }
  const restoreHistoryComments = (items: PromptHistoryComment[]) => {
    comments.replace(
      items.map((item) => ({
        id: item.id,
        file: item.path,
        selection: { ...item.selection },
        comment: item.comment,
        time: item.time,
      })),
    )
    prompt.context.replaceComments(
      items.map((item) => ({
        type: "file",
        path: item.path,
        selection: selectionFromLines(item.selection),
        comment: item.comment,
        commentID: item.id,
        commentOrigin: item.origin,
        preview: item.preview,
      })),
    )
  }

  const accepting = createMemo(() => {
    const id = props.controls.session.id
    if (!id) return permission.isAutoAcceptingDirectory(sdk().directory)
    return permission.isAutoAccepting(id, sdk().directory)
  })
  const toggleAccept = () => {
    const id = props.controls.session.id
    if (!id) {
      permission.toggleAutoAcceptDirectory(sdk().directory)
      return
    }
    permission.toggleAutoAccept(id, sdk().directory)
  }
  const submission = createPromptSubmit({
    prompt,
    info,
    imageAttachments: attachments,
    commentCount,
    autoAccept: accepting,
    mode,
    working,
    editor: () => editor,
    queueScroll: () => requestAnimationFrame(() => editor?.scrollIntoView({ block: "nearest" })),
    promptLength,
    addToHistory: (value, mode) => controller.addHistory(value, mode),
    resetHistoryNavigation: () => controller.resetHistory(),
    setMode: (next) => controller.dispatch({ type: next === "shell" ? "mode.shell" : "mode.normal" }),
    setPopover: (popover) => {
      if (!popover) controller.dispatch({ type: "popover.close" })
    },
    newSessionWorktree: () => props.newSessionWorktree,
    onNewSessionWorktreeReset: props.onNewSessionWorktreeReset,
    shouldQueue: props.shouldQueue,
    onQueue: props.onQueue,
    onAbort: props.onAbort,
    onSubmit: props.onSubmit,
    model: props.controls.model.selection,
  })

  const referenceDescription = (reference: ReferenceInfo) =>
    reference.source.type === "git" ? reference.source.repository : reference.source.path
  const references = createMemo(() =>
    sync()
      .data.reference.filter((reference) => !reference.hidden)
      .map((reference) => ({
        id: `reference:${reference.name}`,
        kind: "reference" as const,
        label: `@${reference.name}`,
        path: reference.path,
        description: reference.description ?? referenceDescription(reference),
        mention: {
          type: "file" as const,
          path: reference.path,
          content: `@${reference.name}`,
          start: 0,
          end: 0,
          mime: "application/x-directory",
          filename: reference.name,
        },
      })),
  )
  const resources = createMemo(() =>
    Object.values(sync().data.mcp_resource).map((resource) => ({
      id: `resource:${resource.client}:${resource.uri}`,
      kind: "resource" as const,
      label: `@${resource.name}`,
      path: resource.uri,
      description: resource.description,
      mention: {
        type: "file" as const,
        path: resource.uri,
        content: `@${resource.name}`,
        start: 0,
        end: 0,
        mime: resource.mimeType ?? "text/plain",
        filename: resource.name,
        url: resource.uri,
        source: {
          type: "resource" as const,
          text: { value: `@${resource.name}`, start: 0, end: resource.name.length + 1 },
          clientName: resource.client,
          uri: resource.uri,
        },
      },
      resource,
    })),
  )
  const context = createMemo<PromptInputV2Suggestion[]>(() => [
    ...references(),
    ...props.controls.agents.available
      .filter((agent) => !agent.hidden && agent.mode !== "primary")
      .map((agent) => ({
        id: `agent:${agent.name}`,
        kind: "agent" as const,
        label: `@${agent.name}`,
        mention: { type: "agent" as const, name: agent.name, content: `@${agent.name}`, start: 0, end: 0 },
      })),
    ...resources(),
    ...recent().map((path) => ({
      id: `file:${path}`,
      kind: "file" as const,
      label: path,
      path,
      recent: true,
      mention: { type: "file" as const, path, content: `@${path}`, start: 0, end: 0 },
    })),
  ])
  const slashCommands = createMemo(() => [
    ...sync().data.command.map((item) => ({
      id: `custom.${item.name}`,
      trigger: item.name,
      title: item.name,
      description: item.description,
      type: "custom" as const,
    })),
    ...command.options
      .filter((item) => !item.disabled && !item.id.startsWith("suggested.") && item.slash)
      .map((item) => ({
        id: item.id,
        trigger: item.slash!,
        title: item.title,
        description: item.description,
        type: "builtin" as const,
      })),
  ])
  const commands = createMemo<PromptInputV2Suggestion[]>(() =>
    slashCommands().map((item) => ({
      id: item.id,
      kind: "command",
      label: `/${item.trigger}`,
      trigger: item.trigger,
      title: item.title,
      description: item.description,
      keybind: command.keybindParts(item.id),
    })),
  )
  const variants = createMemo(() => ["default", ...props.controls.model.selection.variant.list()])
  const controller = createPromptInputV2Controller({
    store: () => prompt.capture().store,
    state: interaction,
    identity: () => prompt.capture(),
    history: {
      entries: (mode) =>
        history.entries(mode).map((value) => {
          const entry = normalizePromptHistoryEntry(value)
          return { prompt: entry.prompt, metadata: entry.comments }
        }),
      add: (value, mode) => history.add(value, mode, mode === "shell" ? [] : historyComments()),
      capture: historyComments,
      restore: (metadata) => restoreHistoryComments(metadata as PromptHistoryComment[]),
    },
    commands,
    context,
    searchContextFiles: async (query) =>
      (await files.searchFilesAndDirectories(query)).map((path) => ({
        id: `file:${path}`,
        kind: "file",
        label: path,
        path,
        mention: { type: "file", path, content: `@${path}`, start: 0, end: 0 },
      })),
    onContextRemove(item) {
      if (item?.commentID) comments.remove(item.path, item.commentID)
    },
    openAttachment: (attachment) =>
      dialog.show(() => <ImagePreview src={attachment.dataUrl} alt={attachment.filename} />),
    openContext(key) {
      const item = controller.contextItem(key)
      if (item) openComment(item, props, sync, layout, files, comments)
    },
    onEditor(element) {
      editor = element as HTMLDivElement
      props.ref?.(editor)
    },
    onSuggestionSelect(item) {
      if (item.kind !== "command") return
      const selected = slashCommands().find((entry) => entry.id === item.id)
      if (!selected || selected.type === "custom") return
      return () => command.trigger(selected.id, "slash")
    },
    attachments: {
      picker: platform.openAttachmentPickerDialog,
      directory: () => sdk().directory,
      isDialogActive: () => !!dialog.active,
      warn: () =>
        showToast({
          title: language.t("prompt.toast.pasteUnsupported.title"),
          description: language.t("prompt.toast.pasteUnsupported.description"),
        }),
      onError: (error) =>
        showToast({
          variant: "error",
          title: language.t("common.requestFailed"),
          description: error instanceof Error ? error.message : String(error),
        }),
      readClipboardImage: platform.readClipboardImage,
      getPathForFile: platform.getPathForFile,
    },
    view: {
      placeholder: designPlaceholder,
      agent:
        props.controls.agents.visible && props.controls.agents.options.length > 0
          ? {
              options: () => props.controls.agents.options.map((name) => ({ id: name, label: name })),
              current: () => props.controls.agents.current,
              onSelect: props.controls.agents.select,
            }
          : undefined,
      variant: {
        options: () => variants().map((value) => ({ id: value, label: value })),
        current: () => props.controls.model.selection.variant.current() ?? "default",
        onSelect: (value) => props.controls.model.selection.variant.set(value === "default" ? undefined : value),
      },
      submit: {
        stopping,
        working,
        onSubmit: () => void submission.handleSubmit(new Event("submit")),
        onStop: () => void submission.abort(),
      },
    },
  })
  Object.defineProperty(controller, "model", { get: () => props.controls.model })
  Object.defineProperty(controller, "autoAccept", { get: () => ({ active: accepting, toggle: toggleAccept }) })
  return controller as PromptInputV2ComposerController
}

function PromptInputV2ModelControl(props: {
  loading: boolean
  paid: boolean
  title: string
  keybind: string[]
  model: PromptInputV2ComposerController["model"]["selection"]
  providerID?: string
  modelName: string
  onClose: () => void
  onUnpaidClick: () => void
}) {
  const shouldAnimate = createMemo<boolean>((previous) => previous ?? props.loading)
  const content = () => (
    <>
      <Show when={props.providerID}>
        {(providerID) => (
          <ProviderIcon
            id={providerID()}
            class="size-4 shrink-0 opacity-40 group-hover:opacity-100 transition-opacity duration-150"
            style={{ "will-change": "opacity", transform: "translateZ(0)" }}
          />
        )}
      </Show>
      <span class="truncate leading-4">{props.modelName}</span>
      <span class="-ml-0.5 -mr-1 flex shrink-0">
        <Icon name="chevron-down" />
      </span>
    </>
  )
  return (
    <Show when={!props.loading}>
      <TooltipV2
        placement="top"
        gutter={4}
        value={
          <>
            {props.title}
            <KeybindV2 keys={props.keybind} variant="neutral" />
          </>
        }
      >
        <Show
          when={props.paid}
          fallback={
            <ButtonV2
              data-action="prompt-model"
              variant="ghost-muted"
              size="normal"
              class="min-w-0 max-w-[220px] justify-start ![font-weight:440] group"
              classList={{ "animate-in fade-in": shouldAnimate() }}
              style={{ height: "28px" }}
              onClick={props.onUnpaidClick}
            >
              {content()}
            </ButtonV2>
          }
        >
          <ModelSelectorPopoverV2
            model={props.model}
            triggerAs={ButtonV2}
            triggerProps={{
              variant: "ghost-muted",
              size: "normal",
              style: { height: "28px" },
              class: "min-w-0 max-w-[220px] justify-start ![font-weight:440] group",
              classList: { "animate-in fade-in": shouldAnimate() },
              "data-action": "prompt-model",
            }}
            onClose={props.onClose}
          >
            {content()}
          </ModelSelectorPopoverV2>
        </Show>
      </TooltipV2>
    </Show>
  )
}

function openComment(
  item: { path: string; commentID?: string; commentOrigin?: "review" | "file" },
  props: PromptInputV2ControllerProps,
  sync: ReturnType<typeof useSync>,
  layout: ReturnType<typeof useLayout>,
  files: ReturnType<typeof useFile>,
  comments: ReturnType<typeof useComments>,
) {
  if (!item.commentID) return
  const focus = { file: item.path, id: item.commentID }
  comments.setActive(focus)
  const queueFocus = (attempts = 6) => {
    requestAnimationFrame(() => {
      comments.setFocus({ ...focus })
      if (attempts <= 0) return
      requestAnimationFrame(() => {
        const current = comments.focus()
        if (current?.file === focus.file && current.id === focus.id) queueFocus(attempts - 1)
      })
    })
  }
  const diffs = props.controls.session.id ? sync().data.session_diff[props.controls.session.id] : undefined
  const review =
    item.commentOrigin === "review" || (item.commentOrigin !== "file" && diffs?.some((diff) => diff.file === item.path))
  if (!props.controls.session.reviewPanel.opened()) props.controls.session.reviewPanel.open()
  if (review) {
    layout.fileTree.setTab("changes")
    props.controls.session.tabs.setActive("review")
    queueFocus()
    return
  }
  layout.fileTree.setTab("all")
  const tab = files.tab(item.path)
  void props.controls.session.tabs.open(tab)
  props.controls.session.tabs.setActive(tab)
  void Promise.resolve(files.load(item.path)).finally(() => queueFocus())
}
