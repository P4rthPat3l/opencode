# JetBrains Plugin Phase 1

## Architecture

The plugin lives in `sdks/jetbrains` as a standalone Gradle Kotlin DSL project. It embeds the existing OpenCode web UI in a JetBrains Tool Window through `JBCefBrowser`; it does not reimplement the chat UI in Swing.

Runtime ownership is application-wide:

```text
JetBrains IDE application
  └─ OpenCodeApplicationService
       ├─ managed runtime installer
       ├─ one owned sidecar process
       └─ per-project OpenCodeProjectService instances
```

Project services collect editor context on demand and pass it to the web composer through the typed web bridge. They do not retain `Editor` or `Document` objects.

## Fork identity

Stable internal identity:

```text
productID = p4rth-opencode
runtimeChannel = jetbrains-stable
protocolVersion = 1
jetbrainsBridgeVersion = 1
storageSchemaVersion = 1
```

The server exposes `GET /global/product` and returns product identity, versions, storage schema, bridge version, and required capabilities. The JetBrains plugin rejects runtimes that do not match `p4rth-opencode`, even if health checks pass.

## Storage isolation

Managed JetBrains processes set only fork-specific overrides:

```text
P4RTH_OPENCODE_CONFIG_DIR
P4RTH_OPENCODE_DATA_DIR
P4RTH_OPENCODE_CACHE_DIR
P4RTH_OPENCODE_STATE_DIR
P4RTH_OPENCODE_LOG_DIR
```

They do not replace global XDG variables, so tools launched by OpenCode keep the user's normal environment. Official OpenCode storage remains untouched.

## Credential import

When the fork auth store is empty, the runtime can read the official `auth.json` once, parse only known compatible auth schemas, and copy credentials into the fork store. It writes an import record under fork state and never modifies official credentials. Single imported provider credentials are named `Default`.

Set `P4RTH_OPENCODE_DISABLE_OFFICIAL_AUTH_IMPORT=1` to disable this import.

## Runtime distribution

The plugin does not bundle platform binaries. It downloads a trusted manifest from the configured managed-runtime manifest URL. The manifest must be HTTPS and must contain exact platform artifacts with SHA-256 hashes.

Phase 1 expects runtime artifacts as ZIP files containing `p4rth-opencode` or `p4rth-opencode.exe`. The installer verifies the hash before extraction, rejects path traversal, installs atomically, and marks the installed executable active only after verification.

Default manifest URL:

```text
https://github.com/P4rthPat3l/opencode/releases/latest/download/p4rth-opencode-jetbrains-runtime.json
```

## Sidecar lifecycle

The plugin starts at most one managed sidecar per IDE application. It runs:

```bash
p4rth-opencode web --hostname 127.0.0.1 --port <random> --no-open --no-mdns
```

The sidecar launches with the JetBrains project root as its working directory when the project has one. The embedded browser opens that directory's session route, which registers the directory in shared project state so both the legacy sidebar and the new layout can create and navigate sessions. It receives a random per-launch `OPENCODE_SERVER_PASSWORD`, uses loopback only, disables auto-update, and validates `/global/product` before the browser is loaded. The embedded JCEF browser answers Basic Auth challenges for that sidecar host on every request so HTML, assets, API calls, and SSE all authenticate consistently. The plugin tracks the exact `Process` object and only terminates owned managed processes.

## Bridge protocol

The web app exposes:

```ts
window.__P4RTH_OPENCODE_IDE_HOST__?.addContext(context)
```

The JetBrains host exposes:

```ts
window.__P4RTH_OPENCODE_IDE__ = {
  version: 1,
  getContext(options),
  openFile(request),
  notifyReady()
}
```

Before each normal prompt, the web composer calls `getContext()` for the current active editor and open editor tabs. The plugin returns project-relative paths, active/modified flags, and the active caret position. This editor metadata is sent to the model as synthetic prompt context without attaching every open file's contents. Explicit selection actions remain the only automatic source of selected text.

Voice dictation uses the web app's existing `getUserMedia({ audio: true })` flow. JCEF media requests are denied by default, so the plugin handles them explicitly: only microphone-only requests from the exact loopback sidecar origin are eligible, and the user must approve a native JetBrains permission dialog. Approval is remembered for that browser instance; camera, screen capture, and unrelated origins are always denied.

Normal browser and desktop usage works when these globals are absent.

## IDE context payload

The plugin sends project-relative paths, one-based line and column numbers, unsaved selected text for explicit selection actions, and open-file metadata without file contents. Selected text is capped at 80 KB and marked as truncated.

## Keyboard shortcuts

| Action | Default shortcut | Mac |
| --- | --- | --- |
| Toggle OpenCode chat panel | `Alt+-` | `⌥-` |
| Add Selection to OpenCode | `Ctrl+Shift+O` | `⌃⇧O` (default keymap) |

Toggle behavior: if the panel is hidden or visible but not focused, the shortcut shows and focuses it; if it is already visible and focused, the shortcut hides it. Users can rebind these under **Settings → Keymap** (search for “OpenCode”).

## AI commit messages

The Commit tool window message toolbar includes **Generate Commit Message with OpenCode**.

Behavior:

1. Collects the **full** staged / included change set (no truncation): prefer Commit UI included changes, else `git diff --cached`, else the default changelist.
2. Ensures the managed sidecar is running (does not require the chat Tool Window to be open).
3. Creates an ephemeral session with tools denied, prompts with an explicit model, and writes the result into the commit message field.
4. Deletes the ephemeral session afterward.

### Model selection

Under **Settings → Tools → P4rth OpenCode**, **AI model** is a dropdown of the same models as OpenCode chat (loaded from the sidecar). Use **Refresh list** after signing in.

| Choice | What happens |
| --- | --- |
| **Use my usual OpenCode model** | Same default as the OpenCode app |
| A specific model (e.g. DeepSeek Free — OpenCode Zen) | Always use that model for commit messages |

Progress text shows which model is used. If no provider is set up, generation asks you to open OpenCode chat and sign in.

### Other settings

Under **Settings → Tools → P4rth OpenCode**:

| Setting | Purpose |
| --- | --- |
| AI model | Which model writes commit messages (same list as chat) |
| Writing style | Optional instructions; keep `{{diff}}` for the staged changes |
| If the commit box already has text | Replace, only when empty, or append |
| Advanced | Custom OpenCode path, update source, troubleshooting logs |

The default writing style asks for a Conventional Commits subject + body. Large diffs can take a while; cancel from the progress dialog.

## Error recovery

Tool Window startup shows inline status and a retry button on failure. JCEF-unavailable fallback shows the local server URL.

## Security boundaries

- Never use `opencode` from `PATH` by default.
- Never attach to arbitrary localhost servers.
- Never download official OpenCode in the managed flow.
- Validate product and capabilities before loading the web UI.
- Use fork-specific storage directories.
- Redact common token shapes from sidecar diagnostics.
- Resolve file navigation against the active project root and reject traversal.

## Development setup

Install JDK 21 and Gradle 8.10+ or add a Gradle wrapper, then run from `sdks/jetbrains`:

```bash
gradle test
gradle buildPlugin
gradle verifyPlugin
gradle runIde
```

Use the Advanced settings page to point at a locally built `p4rth-opencode` executable until release artifacts are published.

## Release and signing

For the manual release process, see [`docs/jetbrains-release.md`](./jetbrains-release.md).

1. Build fork runtime ZIPs per platform.
2. Sign and notarize/code-sign runtime artifacts where applicable.
3. Generate `p4rth-opencode-jetbrains-runtime.json` with exact SHA-256 hashes.
4. Upload the manifest and artifacts to this fork's GitHub Releases.
5. Run `gradle buildPlugin verifyPlugin`.
6. Sign and publish the JetBrains plugin with Marketplace credentials.

## Performance measurement plan

Measure separately: plugin installed but inactive, Tool Window open, Tool Window hidden after browser disposal, sidecar stopped, multiple projects, and 20 repeated open/hide/reopen cycles. Record JVM heap, native memory, JCEF renderer memory, sidecar RSS, process count, idle CPU, and time to usable UI.

## Known limitations in this slice

- Remote Development, inline completion, native diff UI, and official compatibility mode are intentionally not implemented.
- Runtime artifact signing beyond SHA-256 is documented but not implemented.
- ZIP is the first supported runtime archive format.
- Idle browser disposal and 20-minute sidecar shutdown are structured in settings but still need timer integration.
- Cross-platform manual testing has not been run in this environment.
