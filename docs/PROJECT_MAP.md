# OpenCode — Project Map

Purpose of this file: a navigational map of the OpenCode monorepo so an agent (or a person) can go straight to the right file instead of re-exploring the repo. OpenCode is an AI coding-agent product: a CLI + terminal UI + desktop/web app, backed by a local HTTP server that runs "sessions" (agent conversations) against multiple LLM providers, executes tools (file edits, shell, etc.), and persists everything to SQLite.

Read this file first. Only fall back to searching the repo when something here is missing or looks stale.

For coding conventions and architectural rules, see root `AGENTS.md` (style guide, dependency-direction rules, V2 session core rules) and `CONTEXT.md` (domain glossary for the session/context runtime — read before touching session/context code).

---

## 1. How the repo is organized

Bun workspace monorepo (`bun.lock`, `bunfig.toml`), orchestrated with Turborepo (`turbo.json`), deployed with SST (`sst.config.ts`, `infra/`). Default branch is `dev` (not `main`).

Top-level layout:
- `packages/` — all product code, ~34 packages (see below)
- `infra/` — SST infrastructure-as-code modules (one file per deployed service)
- `.github/workflows/` — CI/CD (26 workflows: deploy, test, publish, containers, docs, nix, etc.)
- `.opencode/` — this repo's **own** OpenCode agent config (self-hosted dogfooding: custom commands, agents, tools, skills — see §7)
- `nix/`, `flake.nix` — reproducible dev shell + Nix package builds for CLI and desktop
- `install` — curl-based install script for the CLI binary
- `script/` — root-level release/CI scripts (generate, publish, beta, changelog, translate)
- `AGENTS.md` — coding style guide + architectural rules (read this)
- `CONTEXT.md` — domain glossary for session/context runtime (read before session/context work)
- `CONTRIBUTING.md`, `SECURITY.md`, `STATS.md` — contributor docs

Root scripts worth knowing (`package.json`):
- `bun run dev` → runs `packages/opencode` directly (the CLI, unbuilt)
- `bun run dev:desktop` / `dev:web` / `dev:console` / `dev:stats` / `dev:storybook` → run individual apps
- `bun run typecheck` → `bun turbo typecheck` (never run root `tsc` — always run `bun typecheck` inside a package dir)
- Tests **cannot** run from repo root — run from package dirs (e.g. `packages/opencode`)

---

## 2. Architecture: the dependency layers

Enforced by AGENTS.md and by import-boundary tests. This is the single most important thing to understand before editing:

```
Schema  (pure domain types/codecs — zero deps besides effect)
  ↑
Protocol, LLM   (HttpApi contract; multi-provider LLM abstraction — depend on Schema only)
  ↑
Core            (session runtime, tools, config, db, plugins — depends on Schema + LLM + Plugin)
  ↑
Server          (implements Protocol's HttpApi using Core services)

Client (@opencode-ai/client) depends only on Schema + Protocol — NEVER Core or Server.
sdk-next is the one exception: it composes Client + Core + Server for in-process ("embedded") hosting.
```

Practical rules:
- After changing the public Protocol or Server `HttpApi`, run `bun run generate` from `packages/client`. Never hand-edit `src/generated` or `src/generated-effect`.
- `packages/sdk` (legacy) spawns a real `opencode serve` subprocess and talks HTTP. `packages/sdk-next` runs the server in-process (no network) and is the intended long-term replacement.

---

## 3. Core runtime layer (the backend engine)

### `packages/schema` — `@opencode-ai/schema`
Foundation type system: every domain entity (Session, Message, Provider, Model, Agent, Permission, Credential, Location, Event, etc.) as Effect `Schema` definitions. Zero workspace dependencies (only `effect`). Everything else depends on this.
- `src/index.ts` — export barrel
- `src/session.ts`, `src/session-input.ts`, `src/session-message.ts` — Session domain + durable input admission
- `src/provider.ts`, `src/model.ts` — Provider.ID/Info, Model.ID/Capabilities/Cost
- `src/agent.ts`, `src/permission.ts`, `src/event.ts`, `src/location.ts`, `src/schema.ts` (AbsolutePath/RelativePath/Identifier brands)
- `src/v1/` — legacy V1 schemas kept for migration

### `packages/protocol` — `@opencode-ai/protocol`
Declarative HTTP API **contract** (Effect `HttpApi`) — defines every endpoint group and its schemas, but no implementation (Server injects handlers).
- `src/api.ts` — `makeApi()` / `makeDefaultApi()`, composes all groups + middleware injection points (Location, SessionLocation)
- `src/groups/*.ts` — one file per domain: `session.ts`, `provider.ts`, `model.ts`, `command.ts`, `agent.ts`, `event.ts`, `fs.ts`, `permission.ts`, `pty.ts`
- `src/middleware/authorization.ts` — auth context key
- `src/errors.ts` — shared error types (`SessionNotFoundError`, `ConflictError`, etc.)

### `packages/llm` — `@opencode-ai/llm`
Multi-provider LLM abstraction: routes requests to the right provider/protocol adapter, handles tool calling, streaming, and cost tracking.
- `src/llm.ts` — top-level `generate`/`stream`/request builder API
- `src/provider.ts` — provider registry (`Definition`, `ModelFactory`)
- `src/route/client.ts` — `LLMClient.Service` interface; `src/route/auth.ts` — auth abstraction; `src/route/executor.ts` — provider execution
- `src/protocols/*.ts` — wire-protocol adapters: `openai-chat.ts`, `anthropic-messages.ts`, `bedrock-converse.ts`, `gemini.ts`, etc.
- `src/tool.ts`, `src/tool-runtime.ts` — tool schema + dispatch
- `src/schema/messages.ts`, `src/schema/options.ts` — Message/ContentPart, GenerationOptions/ToolChoice

### `packages/core` — `@opencode-ai/core`
The runtime orchestration engine — the biggest and most important package. Owns session execution, tool registry, config, plugins, database.
- **Session (V2 session core — see CONTEXT.md before editing):**
  - `src/session/runner/index.ts` — `SessionRunner.Service`, drives durable session drains
  - `src/session/execution.ts` — `SessionExecution.Service`, process-local coordinator (resume/wake/interrupt)
  - `src/session/sql.ts` — `session_input`/`session_message`/`session` tables
  - `src/session/message.ts` — `SessionMessage.Service`
- **Tools:** `src/tool/registry.ts` — `ToolRegistry.Service` (materialize, settle, register)
- **System context:** `src/system-context/index.ts` — `SystemContext.Source`/`SystemContext.make()` (see CONTEXT.md — this is the "system prompt assembly" mechanism)
- **Config:** `src/config.ts` — `Config.Service`; `src/config/provider.ts` and siblings for provider/model/command/agent/plugin config (self-export pattern: `export * as ConfigAgent from "./agent"`)
- **Database:** `src/database/database.ts` — `Database.Service` (Drizzle + SQLite); `src/database/schema.sql.ts` — table definitions
- **Effect plumbing:** `src/effect/layer-node.ts`, `src/effect/app-node.ts` — composable service-layer abstractions used throughout Core
- **State managers:** `src/provider.ts` (`ProviderV2.Service`), `src/model.ts` (`ModelV2.Service`), `src/agent.ts` (`AgentV2.Service`), `src/plugin.ts` (`PluginV2.Service`), `src/credential.ts`, `src/location.ts` (`Location.Service` — directory/workspace/project resolution), `src/event.ts` (`EventV2.Service`, durable event log), `src/permission/` (rule evaluation)
- Depends on: `effect-drizzle-sqlite`, `effect-sqlite-node` (DB layers), `llm`, `plugin`, `schema`

### `packages/server` — `@opencode-ai/server`
HTTP bridge: implements Protocol's endpoint groups by wiring them to Core services.
- `src/api.ts` — `Api` instance from `makeDefaultApi()`
- `src/routes.ts` — `createRoutes()` (with password), `createEmbeddedRoutes()` (no password, used by sdk-next), service-layer composition
- `src/handlers/*.ts` — one per domain: `session.ts`, `provider.ts`, `model.ts`, `command.ts`, `agent.ts`, `fs.ts`, `pty.ts`
- `src/middleware/authorization.ts`, `src/middleware/session-location.ts` — concrete middleware implementations for Protocol's injection points
- `src/auth.ts`, `src/location.ts`, `src/cors.ts`

**Data flow through this layer:** Protocol defines the contract → Server implements it against Core → Core's `SessionRunner`/`SessionExecution` orchestrate a session → calls `LLMClient.stream` (from `llm`) → tool calls route through `ToolRegistry` → state persisted via `Database` (Drizzle/SQLite) → `PermissionV2` evaluates rules per workspace/session.

---

## 4. Client / SDK layer

### `packages/client` — `@opencode-ai/client`
Generated Promise + Effect network clients derived from Server's `HttpApi`. Depends only on Schema + Protocol (never Core/Server) — this boundary is enforced by `test/import-boundaries.test.ts`.
- `src/contract.ts` — maps server HttpApi groups to client-facing names; codegen input
- `src/index.ts` — Promise client (zero-Effect, `fetch`-based)
- `src/effect.ts` — Effect client (decoded canonical types) + re-exports Schema types (Session, Location, Prompt, etc.)
- `src/generated/`, `src/generated-effect/` — **generated, do not hand-edit**; regenerate via `packages/client/script/build.ts` (`bun run generate`)

### `packages/sdk` (`packages/sdk/js`) — `@opencode-ai/sdk` (legacy)
Spawns a real `opencode serve` subprocess and wraps the generated HTTP client. Being phased out in favor of `sdk-next`.
- `src/index.ts` — `createOpencode(options)` factory
- `src/server.ts` — subprocess spawning (cross-spawn), port binding
- `src/client.ts`, `src/error-interceptor.ts` — client wrapper + error mapping
- `src/v2/` — Hey-API-generated OpenAPI client (regenerate via `packages/sdk/js/script/build.ts`)

### `packages/sdk-next` — `@opencode-ai/sdk-next`
Effect-native **in-process** host — composes Client + Core + Server with no network I/O (executes Server's HTTP router in memory). The intended replacement for `sdk` once legacy consumers migrate; will eventually take over the `@opencode-ai/sdk` name.
- `src/opencode.ts` — `OpenCode.create()` Effect factory, `OpenCode.Service`, `OpenCode.layer`
- `src/tool.ts` — re-exports Core's Tool API (`Tool.make`, `Tool.Definition`, `Tool.AnyTool`)
- `src/index.ts` — entry point

### `packages/session-ui` — `@opencode-ai/session-ui`
Solid.js components for rendering a session: turns, diffs, tool output, markdown streaming. Ships **two parallel component sets** — see "The v1/v2 dual-UI pattern" in §6 before touching anything here; it applies to this package too.
- `src/context/data.tsx` / `src/context/index.ts` — session data context consumed by both component sets
- **v1** (`src/components/`, plain names): `session-turn.tsx` (one agent turn), `session-review.tsx` (diff/file-change review), `markdown.tsx` / `markdown-cache.tsx` (streaming markdown + Shiki), `message-part.tsx` (dispatches per content-part type — text/tool/file/etc.), `file.tsx` / `file-media.tsx` / `file-search.tsx` / `file-ssr.tsx`, `line-comment.tsx` / `line-comment-annotations.tsx`, `basic-tool.tsx` / `tool-error-card.tsx` / `tool-status-title.tsx` / `tool-count-label.tsx` / `tool-count-summary.tsx`, `dock-prompt.tsx`, `message-nav.tsx`, `session-retry.tsx`
- **v2** (`src/v2/components/`, `-v2` suffix): mirrors most of the above — `basic-tool-v2.tsx`, `tool-error-card-v2.tsx`, `session-review-v2.tsx` (+ `session-review-empty-changes-v2.tsx`, `session-review-empty-no-git-v2.tsx`, `session-review-file-preview-v2.tsx`), `session-file-panel-v2.tsx`, `session-progress-indicator-v2.tsx`, `attachment-card-v2.tsx`, `comment-card-v2.tsx`, `line-comment-annotations-v2.tsx`
- **`src/v2/components/prompt-input/`** — the actual **v2 composer toolbar** (attach/agent/model/variant/submit buttons all render here). `index.tsx` is the component (`PromptInputV2`); it exposes an open extension slot **`props.controls: JSX.Element`** so consumers can inject extra buttons (e.g. voice dictation, auto-accept) without editing this file. `interaction.ts` exports `createPromptInputV2Controller`/`PromptInputV2Interaction`, consumed by `packages/app/src/components/prompt-input-v2.tsx`. Also `machine.ts` (state machine), `store.ts`, `types.ts`, `attachments.ts`.
- Depends on `sdk` (v2 types) and `ui`; no Core/Server dependency

### `packages/ui` — `@opencode-ai/ui`
Published, low-level Solid.js component + theming library (no app logic). Also ships **two parallel primitive sets**:
- **v1** (`src/components/`, plain names, imported as `@opencode-ai/ui/button` etc.): `button.tsx`, `icon.tsx` (large icon set — has things like `shield`), `icon-button.tsx`, `dialog.tsx`, `select.tsx`, `tooltip.tsx`, `card.tsx`, `accordion.tsx`, and more, one file per primitive.
- **v2** (`src/v2/components/`, `-v2` suffix, imported as `@opencode-ai/ui/v2/button-v2` etc.): `button-v2.tsx`, `icon-button-v2.tsx`, `dialog-v2.tsx`, `select-v2.tsx`, `tooltip-v2.tsx`, `menu-v2.tsx`, `keybind-v2.tsx`, `switch-v2.tsx`, `toast-v2.tsx`, `tabs-v2.tsx`, `badge-v2.tsx`, `checkbox-v2.tsx`, `radio-v2.tsx`, `text-input-v2.tsx`, `textarea-v2.tsx`, `inline-input-v2.tsx`, `field-v2.tsx`, `loader-v2.tsx`, `progress-circle-v2.tsx`, `segmented-control-v2.tsx`, `split-button-v2.tsx`, `divider-v2.tsx`, `avatar-v2.tsx`, `project-avatar-v2.tsx`, `line-comment-v2.tsx`, `diff-changes-v2.tsx`, `text-shimmer-v2.tsx`, `wordmark-v2.tsx`, `tab-state-indicator.tsx`.
  - **`icon.tsx` (v2) only defines ~21 icon names** (`edit, folder, branch, help, status, menu, plus, stop, microphone, collapse, check, monitor, workspace, close, expand, filetree, split, unified, review, reset, archive`). Passing any other name does **not** error — it silently falls back to rendering `"plus"` (see the `iconName()` function in that file). Always check this file before picking an icon name for new v2 UI; don't assume v1 icon names (like `shield`) exist in v2.
- `src/theme/` — theme engine shared by both: `color.ts` (hex↔oklch↔rgb), `resolve.ts`, `default-themes.ts` (~30 v1 presets: dracula, nord, solarized...). v2-specific tokens live in `src/theme/v2/mapping.ts` and `src/theme/themes/oc-2.json`; v2 CSS custom properties follow a `--v2-<category>-<token>` naming convention (e.g. `--v2-state-fg-success`, `--v2-icon-icon-muted`) defined in `src/v2/styles/theme.css` — grep that file before inventing a class name like `text-v2-...`.
- `src/context/`, `src/hooks/`, `src/i18n/`

### `packages/httpapi-codegen` — `@opencode-ai/httpapi-codegen`
Build-time generator that compiles an Effect `HttpApi` into Promise/Effect client code. Single-file implementation in `src/index.ts` (`compile()`, `emitEffect()`, `emitPromise()`, `write()`, `generate()`). Used by `packages/client`'s build script.

---

## 5. CLI / TUI / Plugin layer (what the user runs)

### `packages/opencode` — `opencode` (the main published binary)
The orchestrator: all CLI commands, agent execution, and the piece that ties everything together.
- `src/index.ts` — yargs entry point, registers all commands
- `src/cli/cmd/run.ts` + `src/cli/cmd/run/` — the core interactive agent-session command (largest/most complex: `runtime.ts` lifecycle, `stream.ts` event transport, `footer.ts` UI)
- `src/cli/cmd/tui.ts` — spawns `packages/tui`
- `src/cli/cmd/serve.ts`, `src/cli/cmd/models.ts`, `src/cli/cmd/debug/index.ts`
- `bin/opencode` — platform-aware executable shim
- `src/agent/agent.ts` — agent type/lifecycle; `src/acp/` — protocol domain model; `src/lsp/` — language server integration; `src/storage/` — DB schema/migrations at this layer

### `packages/cli` — `@opencode-ai/cli`
Effect-based daemon manager (separate, thinner CLI — binary name `lildax`) for background-server lifecycle.
- `src/index.ts` — entry; `src/commands/commands.ts` — command tree (api/debug/service/serve/migrate)
- `src/services/daemon.ts` — daemon connection/lifecycle
- `src/framework/runtime.ts` — command routing framework
- `bin/lildax.cjs`

### `packages/tui` — `@opencode-ai/tui`
The terminal UI itself: Solid.js + OpenTUI + Effect.
- `src/index.tsx` — exports `run(input: TuiInput)`
- `src/app.tsx` — root component, all context providers
- `src/context/sdk.tsx`, `src/context/route.tsx`, `src/context/sync.tsx` — SDK bridge, navigation, live sync
- `src/plugin/runtime.tsx` — plugin host + slot rendering
- `src/keymap.tsx`, `src/config/index.tsx` — keybindings/theme config
- `src/routes/session.tsx` — main session view; `src/component/command-palette.tsx`, `src/component/dialog-model.tsx`
- `src/feature-plugins/builtins.ts` — built-in feature plugins (sidebar, diff viewer, MCP UI)

### `packages/plugin` — `@opencode-ai/plugin`
Plugin/extensibility API — the surface third parties (and this repo's own `.opencode/`) write against. Two generations:
- **V1 (legacy, Promise-based):** `src/index.ts` (`Plugin`, `Hooks`, `PluginInput`), `src/tool.ts` (`ToolDefinition`)
- **V2 (Effect-native, current):** `src/v2/effect/index.ts`, `plugin.ts` (`define()`), `context.ts` (`PluginContext`), plus `command.ts`, `skill.ts`, `agent.ts`, `catalog.ts`, `event.ts`

### `packages/codemode` — `@opencode-ai/codemode`
Sandboxed JS interpreter for "code mode" tool execution — lets the agent write a snippet of JS that calls schema-described tools under strict resource limits (time, tool-call count, output size).
- `src/codemode.ts` — `execute()`, `ExecutionLimits`
- `src/interpreter/` — confined JS interpreter (AST-based, not `eval`)
- `src/tool-runtime.ts`, `src/tool-schema.ts`, `src/openapi/` — tool↔OpenAPI conversion
- `src/stdlib/` — sandboxed built-ins (console, JSON, string, collections)

**Entry-point cheat sheet:**
| Command | Entry | Leads to |
|---|---|---|
| `opencode run` | `packages/opencode/src/index.ts` → RunCommand | `packages/opencode/src/cli/cmd/run.ts` |
| `opencode tui` (default) | `packages/opencode/src/cli/cmd/tui.ts` | `packages/tui/src/index.tsx` |
| `lildax serve` | `packages/cli/bin/lildax.cjs` | `packages/cli/src/commands/handlers/serve.ts` |

---

## 6. Applications (user-facing frontends & internal services)

### `packages/app` — `@opencode-ai/app` (deep dive — highest-churn package; most feature requests land here)
Shared Solid.js web UI — the actual product screen, embedded by `desktop` and used standalone on web. Solid.js + solid-router + solid-query + Kobalte + Vite + Tailwind.
- `src/app.tsx` — root with providers (Router, Query, Sentry, Theme, File, I18n)
- `src/entry.tsx` — browser entry
- Depends on: `core`, `schema`, `sdk`, `ui`, `session-ui`

#### The v1/v2 dual-UI pattern (read this before touching any UI file)

The app ships **two parallel UI implementations side by side**, selected at runtime by one flag: `settings.general.newLayoutDesigns` (`src/context/settings.tsx`). "v1"/"legacy" is the older design; "v2"/"new" is the redesign. This is not mid-migration cleanup debt — both are actively maintained, and a composer/settings/layout feature request typically needs a matching change in **both**.

- Naming is **inconsistent** — don't assume one suffix pattern:
  - Most common: `foo.tsx` (v1) next to `foo-v2.tsx` (v2), e.g. `prompt-input.tsx` / `prompt-input-v2.tsx`, `dialog-select-model-unpaid.tsx` / `dialog-select-model-unpaid-v2.tsx`, `dialog-edit-project.tsx` / `dialog-edit-project-v2.tsx`, `dialog-select-directory.tsx` / `dialog-select-directory-v2.tsx`, `file-tree.tsx` / `file-tree-v2.tsx`.
  - Sometimes `-new` instead of `-v2`: `pages/layout.tsx` (v1 shell) vs `pages/layout-new.tsx` (v2 shell).
  - Sometimes a `v2/` subdirectory holding otherwise-shared code: `pages/session/v2/` (v2 review panel + file browser), `components/settings-v2/` (whole v2 settings dialog).
  - Sometimes there's no separate v1 file at all because the v1 behavior is inline in a shared file, with only v2 split out — e.g. the auto-accept toggle: the v1 button lives directly inside `prompt-input.tsx`, while the v2 button is a separate `PromptInputV2AutoAcceptControl` function inside `prompt-input-v2.tsx`.
  - `titlebar.tsx` doesn't split into two files at all — it branches internally on a `useV2Titlebar()` memo.
- `@opencode-ai/ui` and `@opencode-ai/session-ui` mirror the same split (see their entries in §4): plain-named components are v1, `-v2`-suffixed ones (often under a `v2/` subfolder) are v2, with different import paths (`@opencode-ai/ui/button` vs `@opencode-ai/ui/v2/button-v2`).
- **v2's icon set is small (~21 icons) and fails silently** — an unknown icon name quietly renders as `"plus"` instead of erroring. See the `packages/ui` entry in §4 for the exact list. This bit us once already; check the icon file before adding a v2 icon reference.
- Shared business logic (contexts, controller factories like `usePromptInputV2Controller`, permission/session state) is **not** duplicated — only the presentational shell differs between v1 and v2. Before adding new state for a toggle/control, check whether it already exists in a `context/*.tsx` file or a controller factory.
- `settings.general.newLayoutDesigns` resolution is intentionally elaborate — one-time forced-migration-on-first-launch logic (removed as of the "default new profiles to the legacy layout" change), a hard "sunset" retirement date (`oldInterfaceSunset`), and per-install eligibility gating — all in `src/context/settings.tsx`. If a UI toggle/button "disappeared," check there first for a `<Show when={settings...}>` gate before assuming the code was deleted; it usually wasn't.

#### `src/context/` — all app-wide state (Solid context providers), one file per concern

| File | Purpose |
|---|---|
| `settings.tsx` | Persisted user settings (`settings.v3`): general/appearance/keybinds/permissions/notifications/sounds — **and** the v1/v2 layout-selection state machine (`newLayoutDesigns`, sunset retirement, migration, `visibility.*` gates) |
| `permission.tsx` | Per-session/per-directory auto-accept permission state (`isAutoAccepting`, `toggleAutoAccept`, `toggleAutoAcceptDirectory`) and pending permission-request routing |
| `permission-auto-respond.ts` | Pure matching logic (rules → auto-respond decision) used by `permission.tsx` |
| `sdk.tsx` | Active SDK client instance for the current server/session |
| `server.tsx`, `server-sdk.tsx`, `server-sync.tsx`, `server-session.tsx` | Server connection lifecycle, per-server SDK instances, sync bootstrapping, session-to-server mapping (multi-server support) |
| `sync.tsx` | Live reactive data store synced from the server — sessions, messages, diffs, working/busy state; most components read from here |
| `directory-sync.ts` | Directory-level sync helpers |
| `global.tsx`, `global-sync/` | Cross-server/global state and sync |
| `local.tsx`, `local-agent.ts` | Local-only (non-persisted) UI state; local agent selection |
| `prompt.tsx`, `prompt-state.ts` | Prompt draft state per session/tab, context items (@ mentions, attachments) |
| `tabs.tsx`, `tab-memory.ts`, `closed-tabs.ts` | Session/file tab management, recently-closed tab memory |
| `layout.tsx`, `layout-helpers.ts`, `layout-scroll.ts`, `layout-tabs.ts` | Panel layout (sidebar/file-tree/terminal visibility), scroll-position persistence, tab-in-layout logic |
| `comments.tsx` | Inline review comments on diffs |
| `command.tsx` | Command palette + keybind registry — `command.register(scope, () => [...])`, `command.keybind(id)` / `command.keybindParts(id)`, `command.trigger(id)`. **This is how every keybind, slash command, and command-palette entry is wired**; session-scoped commands are registered in `pages/session/use-session-commands.tsx` |
| `models.tsx`, `model-variant.ts` | Provider/model catalog and variant (thinking-level) selection |
| `mcp.ts` | MCP server config/state |
| `notification.tsx` | Desktop/browser notification dispatch |
| `language.tsx` | i18n — `language.t(key)`, locale switching (translations in `src/i18n/*.ts`, one file per locale, `en.ts` is source of truth) |
| `platform.tsx` | Platform detection (desktop/web/WSL) + platform-specific APIs (file pickers, clipboard, app version) |
| `file.tsx` (+ `src/context/file/`) | File content loading/caching, active file tab resolution |
| `highlights.tsx` | Syntax highlighting (Shiki) state |
| `terminal.tsx`, `terminal-title.ts` | PTY/terminal session state |

#### `src/components/` — shared components (mixed v1/v2, see naming pattern above)

- **Composer:** `prompt-input.tsx` (v1) / `prompt-input-v2.tsx` (v2) — the chat input, including the auto-accept and voice-dictation toolbar buttons. Shared submodules in `src/components/prompt-input/`: `submit.ts` (prompt submission logic), `attachments.ts`, `history.ts` / `history-store.ts` (prompt history), `editor-dom.ts` (contenteditable parsing), `build-request-parts.ts`, `context-items.tsx`, `slash-popover.tsx`, `image-attachments.tsx`, `drag-overlay.tsx`, `voice.ts` (dictation), `contracts.ts` (shared prop types), `placeholder.ts`.
- **Settings:** v1 = `settings-dialog.tsx` + one file per tab (`settings-general.tsx`, `settings-keybinds.tsx`, `settings-models.tsx`, `settings-providers.tsx`, `settings-servers.tsx`, `settings-server-picker.tsx`, `settings-list.tsx`). v2 = `src/components/settings-v2/` (`index.tsx`, `dialog-settings-v2.tsx`, `general.tsx`, `models.tsx`, `providers.tsx`, `servers.tsx`, `dialog-server-v2.tsx`) — plus **`interface-transition.tsx`**, which holds `LayoutTransitionToggle` and `LayoutRetirementNotice`, the presentational pieces used by **both** v1's `settings-general.tsx` (inlined) and v2's `general.tsx` (imported directly) for the old/new-UI switch.
- **Model/project/directory dialogs:** `dialog-select-model.tsx`, `dialog-select-model-unpaid(.tsx|-v2.tsx)`, `dialog-edit-project(.tsx|-v2.tsx)`, `dialog-select-directory(.tsx|-v2.tsx)`, `dialog-select-server.tsx`, `dialog-connect-provider.tsx`, `dialog-custom-provider.tsx`, `dialog-manage-models.tsx`, `dialog-select-mcp.tsx`, `dialog-select-file.tsx`, `dialog-fork.tsx`, `dialog-release-notes.tsx`, `dialog-usage-exceeded.tsx`, `dialog-command-palette-v2.tsx` (v2 only).
- **Window chrome:** `titlebar.tsx` (internal v1/v2 branch, no separate file), `titlebar-tab-strip.tsx`, `titlebar-tab-nav.tsx`, `titlebar-tab-popover.tsx`, `windows-app-menu.tsx`.
- **File tree:** `file-tree.tsx` (v1) / `file-tree-v2.tsx` (v2).
- **`src/components/session/`:** `session-header.tsx`, `session-sortable-tab(.tsx|-v2.tsx)`, `session-sortable-terminal-tab(.tsx|-v2.tsx)`, `session-context-tab.tsx`, `session-context-breakdown.ts` / `session-context-metrics.ts` (context-window usage accounting), `open-in-app(.tsx|-v2.tsx)`.
- **`src/components/server/`:** `server-row.tsx`, `server-row-menu.tsx` (multi-server picker rows).
- Misc: `terminal.tsx`, `status-popover.tsx` / `status-popover-body.tsx` (connection status), `session-context-usage.tsx`, `directory-picker.tsx`, `help-button.tsx`, `link.tsx`, `model-tooltip.tsx`, `debug-bar.tsx`.

#### `src/pages/` — routed pages

- `home.tsx` (session list/launcher), `new-session.tsx` (new-session composer entry).
- `session.tsx` — the main session screen; there is **no** `session-v2.tsx` — v2 differences are handled by internal `newLayoutDesigns` branches plus the `pages/session/v2/` and `pages/session/composer/` subfolders below.
- `layout.tsx` (v1 shell: sidebar + tabs + content) / `layout-new.tsx` (v2 shell — note the `-new` suffix, not `-v2`).
- `directory-layout.tsx` (per-directory/workspace shell), `error.tsx` / `error-description.ts` (error boundary page).
- `home-session-archive.ts` / `home-session-open.ts` — pure helpers backing the home page's session list.
- **`src/pages/layout/`** (sidebar internals): `sidebar-shell.tsx`, `sidebar-project.tsx`, `sidebar-workspace.tsx`, `sidebar-items.tsx`, `session-tab-avatar.tsx`, `inline-editor.tsx` (rename-in-place), `deep-links.ts`, `project-avatar-state.ts`.
- **`src/pages/session/`** (top-level files, cross-cutting session-screen logic): `use-session-commands.tsx` — registers all session-scoped commands/keybinds, **including `permissions.autoaccept`**; `use-composer-commands.tsx` (composer-scoped commands, v2); `file-tabs.tsx`; `review-tab.tsx` (v1 diff review tab); `session-side-panel.tsx`; `terminal-panel(.tsx|-v2.tsx)`; `session-panel-layout.ts` / `session-panel-width.ts` (resizable panel math); `session-layout.ts`; `session-lineage.ts` (fork/revert lineage); `session-ownership.ts`; `session-model-helpers.ts`; `handoff.ts`; `helpers.ts`; `message-gesture.ts`; `file-tab-scroll.ts`.
- **`src/pages/session/composer/`** (chrome around the prompt input — "docks" that appear above it): `session-composer-region.tsx` (wraps `PromptInputV2Composer` + docks), `session-composer-region-controller.ts`, `session-composer-controls.ts`, `session-composer-state.ts`, `session-permission-dock.tsx`, `session-question-dock.tsx`, `session-followup-dock.tsx`, `session-todo-dock.tsx`, `session-revert-dock.tsx`, `session-request-tree.ts`, `prompt-model-selection.ts`.
- **`src/pages/session/timeline/`** (the virtualized message list): `message-timeline.tsx` (main render), `rows.ts` / `row-reconciliation.ts` / `virtual-items.ts` (virtualization), `projection.ts` / `model.ts` (message→row projection), `measure.ts`, `summary-diffs.ts`, `observe-element-offset.ts`.
- **`src/pages/session/v2/`** (v2-only review/file-browser): `review-panel-v2.tsx`, `review-panel-v2-state.ts`, `review-diff-kinds.ts`, `session-file-browser-tab.tsx`, `session-file-list-v2.tsx`.

#### Other `packages/app` directories

- **`src/wsl/`** — Windows Subsystem for Linux remote-server support: `context.tsx` (detection/lifecycle; calls `window.api.isOldLayoutEligible()` from desktop's preload — the **only** call site that sets the layout-transition eligibility flag), `settings.tsx` / `settings-model.ts` (WSL server list UI+state), `dialog-add-server.tsx`, `add-server-probes.ts`, `types.ts`.
- **`src/utils/`** — stateless helpers: server (`server.ts`, `server-health.ts`, `server-errors.ts`, `server-scope.ts`), `persist.ts` (the `persisted()` helper every context store above is built on), `prompt.ts`, `diffs.ts`, notifications (`notification-click.ts`, `sound.ts`, `toast.tsx`), terminal (`terminal-writer.ts`, `terminal-websocket-url.ts`), misc (`worktree.ts`, `session-route.ts`, `session-title.ts`, `refcount.ts`, `scoped-cache.ts`, `uuid.ts`, `id.ts`, `base64.ts`, `same.ts`, `time.ts`, `solid-dnd.tsx`, `agent.ts`, `aim.ts`, `comment-note.ts`, `file-manager.ts`, `path-key.ts`).
- **`src/hooks/`** — `provider-catalog.ts` / `use-providers.ts` (provider/model list fetching + caching).
- **`src/i18n/`** — one file per locale; `language.t("some.key")` looks strings up here (`en.ts` is source of truth, mirrored into the rest).
- **`src/constants/file-picker.ts`**, **`src/addons/serialize.ts`** — small standalone utilities.
- **`e2e/`** — Playwright end-to-end tests (100+ specs); **`test-browser/`** — browser-environment unit tests.

### `packages/desktop` — `@opencode-ai/desktop`
Electron desktop shell wrapping `packages/app`.
- `src/main/index.ts` — Electron main entry, spawns local server sidecar
- `src/main/server.ts` — sidecar server process management
- `src/main/wsl/` — WSL distro detection + server management
- `src/main/updater.ts` — electron-updater; `src/main/ipc.ts` — IPC bridge; `src/main/menu.ts`
- `src/preload/index.ts` — preload script exposing IPC to renderer
- `electron-builder.config.ts` — packaging/signing config

### `packages/web` — `@opencode-ai/web`
Astro + Starlight marketing/docs site (separate from `packages/docs`), deployed to Cloudflare Pages.
- `src/content.config.ts`, `src/pages/[...slug].md.ts`, `astro.config.mjs`

### `packages/console/*` — internal SaaS console (accounts, billing, API keys, LLM proxy)
- `console/app` (Solid Start): `src/routes/workspace/[id]/` (billing/keys/usage/providers), `src/routes/zen/v1/` (OpenAI-compatible LLM proxy API), `src/context/auth.ts`. Uses Stripe, OpenAuth, Upstash Redis.
- `console/core`: Drizzle schema + domain logic — `src/schema/*.sql.ts` (account, workspace, billing, key, provider, model), `src/actor.ts`, `src/billing.ts`, `src/provider.ts`
- `console/function`: Lambda handlers — `src/auth.ts`, `src/stat.ts`, `src/log-processor.ts`
- `console/mail`, `console/resource`, `console/support` — supporting pieces (email templates, SST resource bindings, support tooling)

### `packages/stats/*` — public analytics dashboard (model/provider benchmark comparison)
- `stats/app` (Solid Start): `src/routes/[lab]/[model].tsx`, `src/routes/compare/` — model comparison UI (D3/TopoJSON for geo charts)
- `stats/core`: `src/domain/*.ts` (stat, model, provider, inference, geo), `src/athena.ts` (AWS Athena historical queries)
- `stats/server`: `src/server.ts`, `src/router.ts`, `src/ingest.ts` (AWS Firehose ingestion from `packages/server` usage events)

### `packages/storybook` — `@opencode-ai/storybook`
Storybook instance documenting `packages/ui` and `packages/session-ui` components only. `.storybook/main.ts`, `.storybook/preview.ts`.

---

## 7. Infra, support libraries, and tooling

| Package/path | Purpose |
|---|---|
| `packages/effect-drizzle-sqlite` | Effect wrapper around Drizzle ORM for SQLite (select/insert/update/delete/count/migrate) — used by Core's database layer |
| `packages/effect-sqlite-node` | Minimal Effect SQLite bindings for Node.js — paired with the above |
| `packages/http-recorder` | Published npm package: record/replay Effect HTTP client traffic (VCR-style cassettes) for deterministic tests |
| `packages/enterprise` | "Teams" SolidStart SSR app (sharing, storage) deployed to Cloudflare Workers + R2, defined in `infra/enterprise.ts` |
| `packages/function` | Cloudflare Worker: GitHub App/Discord/Feishu webhook API (`src/api.ts`), defined in `infra/app.ts` |
| `packages/identity` | Brand assets only (logos/icons), no code |
| `packages/slack` | Slack bot using Slack Bolt (`src/index.ts`) |
| `packages/speech` | Rust/whisper voice sidecar (`opencode-speech` binary), optional, installed alongside the CLI |
| `packages/containers` | Dockerfiles for CI base images (base, bun-node, rust, tauri-linux, publish), built by `.github/workflows/containers.yml` |
| `packages/script` + root `/script` | Shared scripting utils + release automation: `script/generate.ts` (codegen, auto-committed on `dev`), `script/publish.ts`, `script/beta.ts`, `script/translate-app.ts` |
| `packages/docs` | Mintlify SDK documentation site (`docs.json`, `essentials/`, `ai-tools/`) — distinct from `packages/web` |
| `sst.config.ts` | Root SST app config: providers (AWS/Stripe/Cloudflare/PlanetScale/Honeycomb), stages (production/dev/personal) |
| `infra/*.ts` | One file per deployed service: `app.ts` (function worker), `stage.ts` (domain routing), `enterprise.ts`, `lake.ts`, `stats.ts`, `console.ts`, `monitoring.ts`, `secret.ts` |
| `turbo.json` | Turborepo task graph (typecheck/build/test) |
| `flake.nix` / `nix/*.nix` | Nix dev shell + package derivations for CLI and desktop binaries |
| `install` | Curl-installer script for the CLI binary (platform/arch detection, musl detection) |
| `.github/workflows/` | CI: `deploy.yml`, `publish.yml` (npm/VSCode/AUR/GitHub releases), `containers.yml`, `generate.yml`, `test.yml`, `docs-update.yml`, `nix-hashes.yml`, etc. (26 total) |

### `.opencode/` — this repo's own agent configuration (self-hosted / dogfooding)
This is OpenCode configuring itself as a project — worth knowing about separately since it's not "product code" but governs how AI agents (including you) behave in this repo:
- `.opencode/opencode.jsonc` — main project config (disabled tools, related-repo references)
- `.opencode/command/*.md` — custom slash commands: `learn.md` (extract learnings into AGENTS.md), `commit.md`, `ai-deps.md`, `spellcheck.md`, `changelog.md`, `issues.md`, `rmslop.md`
- `.opencode/agent/*.md` — custom agents: `triage.md` (issue triage by team), `duplicate-pr.md`
- `.opencode/tool/github-pr-search.ts` — custom tool
- `.opencode/skills/effect/SKILL.md` — Effect/FP reference skill
- `.opencode/glossary/` — multilingual glossaries; `.opencode/themes/`, `.opencode/plugins/` — TUI theme/plugin customizations for this repo

---

## 8. Quick index — "where do I look for X?"

- **Agent conversation / session lifecycle logic** → `packages/core/src/session/` (read `CONTEXT.md` first)
- **System prompt / context assembly** → `packages/core/src/system-context/`
- **Tool implementations & tool-call handling** → `packages/core/src/tool/`, `packages/opencode/src/cli/cmd/run/`, `packages/codemode`
- **LLM provider/model routing** → `packages/llm/src/route/`, `packages/llm/src/protocols/`
- **HTTP API shape (adding/changing an endpoint)** → `packages/protocol/src/groups/` (contract) + `packages/server/src/handlers/` (implementation), then regenerate `packages/client`
- **CLI commands** → `packages/opencode/src/cli/cmd/`
- **Terminal UI** → `packages/tui/src/`
- **Plugin API surface** → `packages/plugin/src/v2/effect/` (current), `packages/plugin/src/index.ts` (legacy)
- **Desktop/web app screens** → `packages/app/src/pages/`, `packages/app/src/components/` (see the full §6 deep dive — mixed v1/v2, check both when adding a UI feature)
- **Which UI (v1/legacy or v2/new) is currently shown, and the default** → `packages/app/src/context/settings.tsx` (`newLayoutDesigns` memo, `oldInterfaceSunset` retirement date)
- **Auto-accept permissions (state, keybind, composer button)** → state: `packages/app/src/context/permission.tsx`; keybind/command registration (`permissions.autoaccept`, `mod+shift+a`): `packages/app/src/pages/session/use-session-commands.tsx`; composer button: `packages/app/src/components/prompt-input.tsx` (v1) and `prompt-input-v2.tsx` (v2, via `PromptInputV2AutoAcceptControl`)
- **Prompt composer toolbar (attach/agent/model/variant/submit buttons)** → v1: `packages/app/src/components/prompt-input.tsx`; v2 controller: `packages/app/src/components/prompt-input-v2.tsx`; v2 rendered toolbar (shared, has a `controls` extension slot): `packages/session-ui/src/v2/components/prompt-input/index.tsx`
- **Settings dialog tabs / old-new UI toggle location** → v1: `packages/app/src/components/settings-general.tsx`; v2: `packages/app/src/components/settings-v2/general.tsx`; shared toggle UI: `packages/app/src/components/settings-v2/interface-transition.tsx`
- **Command palette, keybinds, slash commands** → `packages/app/src/context/command.tsx` (registry) + `packages/app/src/pages/session/use-session-commands.tsx` (session-scoped registrations)
- **v2 icon names (small fixed set, silently falls back to "plus")** → `packages/ui/src/v2/components/icon.tsx`
- **v2 CSS design tokens (`--v2-*` custom properties)** → `packages/ui/src/v2/styles/theme.css`, `packages/ui/src/theme/v2/mapping.ts`
- **Theming** → `packages/ui/src/theme/`
- **Database schema/migrations** → `packages/core/src/database/`
- **Billing/accounts/API keys (internal SaaS)** → `packages/console/core/src/schema/`, `packages/console/app/src/routes/workspace/`
- **Public model/provider benchmarks site** → `packages/stats/`
- **Release/CI/build tooling** → `script/`, `.github/workflows/`, `packages/containers/`
- **This repo's own agent config** → `.opencode/`
