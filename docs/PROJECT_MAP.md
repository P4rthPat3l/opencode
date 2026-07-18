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
Solid.js components for rendering a session: turns, diffs, tool output, markdown streaming.
- `src/components/session-turn.tsx` — one agent turn
- `src/components/session-review.tsx` — diff/file-change review
- `src/components/markdown.tsx` — streaming markdown + Shiki highlighting
- `src/components/message-part.tsx`, `src/components/file.tsx`
- `src/context/data.tsx` — session data context
- Depends on `sdk` (v2 types) and `ui`; no Core/Server dependency

### `packages/ui` — `@opencode-ai/ui`
Published, low-level Solid.js component + theming library (no app logic).
- `src/components/*.tsx` — Card, Accordion, Icon, Dialog, Select, etc.
- `src/theme/` — full theme system: `color.ts` (hex↔oklch↔rgb), `resolve.ts`, `default-themes.ts` (~30 presets: dracula, nord, solarized, oc2...)
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

### `packages/app` — `@opencode-ai/app`
Shared Solid.js web UI — the actual product screen, embedded by both `desktop` and (likely) a hosted web target. Solid.js + solid-router + solid-query + Kobalte + Vite + Tailwind.
- `src/app.tsx` — root with providers (Router, Query, Sentry, Theme, File, I18n)
- `src/entry.tsx` — browser entry
- `src/pages/session/` — session editor/composer; `src/pages/layout/` — shell/sidebar
- `src/context/server.tsx` — server connection lifecycle; `src/context/sdk.tsx` — SDK instance; `src/context/settings.tsx`
- `src/components/prompt-input/` — multi-file prompt editor + slash commands
- `src/wsl/` — Windows Subsystem for Linux server detection/lifecycle
- Depends on: `core`, `schema`, `sdk`, `ui`, `session-ui`

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
- **Desktop/web app screens** → `packages/app/src/pages/`, `packages/app/src/components/`
- **Theming** → `packages/ui/src/theme/`
- **Database schema/migrations** → `packages/core/src/database/`
- **Billing/accounts/API keys (internal SaaS)** → `packages/console/core/src/schema/`, `packages/console/app/src/routes/workspace/`
- **Public model/provider benchmarks site** → `packages/stats/`
- **Release/CI/build tooling** → `script/`, `.github/workflows/`, `packages/containers/`
- **This repo's own agent config** → `.opencode/`
