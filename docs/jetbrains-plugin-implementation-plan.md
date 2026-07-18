# JetBrains Plugin Implementation Plan

This document describes the full end-state plan for first-class JetBrains IDE support in this `p4rth-opencode` fork. It covers the complete implementation path, not only the first vertical slice.

The core product rule is:

```text
Use this fork by default.
Never silently use official OpenCode.
Never share mutable official OpenCode storage.
Share the same p4rth-opencode user profile between the terminal CLI and JetBrains plugin by default.
Reuse the existing OpenCode server, web UI, sessions, providers, tools, permissions, agents, and configuration systems.
```

## 1. Final user experience

Normal path:

```text
Install JetBrains plugin
→ Open OpenCode Tool Window
→ Plugin prepares p4rth-opencode runtime automatically
→ Forked OpenCode web UI appears inside the Tool Window
→ User authenticates only when provider access is actually needed
→ User chats, runs tools, approves permissions, switches provider accounts, and uses voice input where supported
```

The normal user should not need to:

- Install OpenCode manually.
- Know whether official OpenCode is installed.
- Configure an executable.
- Pick a server URL.
- Pick a port.
- Edit OpenCode configuration files.
- Understand that the UI is a web app backed by a local server.
- Resolve runtime compatibility.

Advanced paths exist, but they are never selected silently:

- External compatible fork runtime.
- Official OpenCode compatibility mode.
- Existing server URL.
- Runtime mirror or manually downloaded runtime.

## 2. Product identity

The fork has a stable internal identity independent of the public display name.

```text
productID = p4rth-opencode
displayName = P4rth OpenCode
runtimeChannel = jetbrains-stable
protocolVersion = 1
jetbrainsBridgeVersion = 1
storageSchemaVersion = 1
```

This identity is used for:

- Product handshake.
- Runtime manifest validation.
- Managed executable name.
- Storage directories.
- Lock/PID namespace.
- Update channel.
- JetBrains plugin ID.
- Bridge global names.
- Diagnostics and process labels.

The display name should be distinguishable in user-facing surfaces. Use `P4rth OpenCode` for JetBrains Marketplace, plugin settings, process labels, diagnostics, and fork-branded runtime UI where the user needs to tell it apart from official OpenCode. The permanent product/storage identity remains `p4rth-opencode`.

The plugin must never accept a runtime only because:

- The executable is named `opencode`.
- A semantic version looks compatible.
- A health endpoint returns success.
- A server is already listening on localhost.

The plugin validates `/global/product` before loading the web UI.

## 3. Repository layout

JetBrains plugin source lives under:

```text
sdks/jetbrains
```

Planned package layout:

```text
sdks/jetbrains/
  build.gradle.kts
  settings.gradle.kts
  gradle.properties
  README.md
  src/main/kotlin/ai/opencode/p4rth/jetbrains/
    ProductIdentity.kt
    runtime/
      OpenCodeApplicationService.kt
      RuntimeInstaller.kt
      RuntimeManifest.kt
      RuntimePaths.kt
      SidecarProcess.kt
      ProductInfo.kt
      Platform.kt
      SecretRedactor.kt
      SafeArchiveExtractor.kt
      RuntimeLock.kt
      RuntimeCleanup.kt
      RuntimeUpdateManager.kt
    project/
      OpenCodeProjectService.kt
      IdeContextProvider.kt
      FileNavigation.kt
      TargetedFileRefresh.kt
      ProjectTrust.kt
    ui/
      OpenCodeToolWindowFactory.kt
      OpenCodePanel.kt
      OpenCodeBrowser.kt
      OpenCodeBridge.kt
      RecoveryPanel.kt
      ToolbarActions.kt
    actions/
      AddSelectionToOpenCodeAction.kt
      AddCurrentFileToOpenCodeAction.kt
      AddOpenFilesToOpenCodeAction.kt
      OpenToolWindowAction.kt
    settings/
      OpenCodeSettings.kt
      OpenCodeConfigurable.kt
    diagnostics/
      DiagnosticsBundle.kt
      DiagnosticsRedactor.kt
  src/main/resources/META-INF/plugin.xml
  src/main/resources/META-INF/pluginIcon.svg
  src/test/kotlin/...
```

OpenCode runtime and web changes stay in existing packages:

```text
packages/core        product identity, storage paths, database paths
packages/opencode    CLI flags, auth import, product endpoint
packages/app         typed IDE bridge and visible composer context
packages/client      regenerated clients when public API changes require it
```

## 4. Runtime modes

### 4.1 Managed fork runtime

Default mode. Fully supported.

Behavior:

- Plugin owns runtime download, install, update, rollback, startup, validation, and shutdown.
- Runtime is built from this fork only.
- Runtime uses the shared `p4rth-opencode` fork profile by default.
- Runtime self-update is disabled for plugin-managed processes.
- Official OpenCode is ignored even if installed.
- No user configuration is required.

This is the only happy path for the first stable release.

### 4.2 External fork runtime

Advanced mode.

Behavior:

- User explicitly selects a runtime executable or server URL.
- Plugin validates `/global/product`.
- Plugin does not update it.
- Plugin terminates it only if the plugin started the exact process.
- Full functionality is available only if protocol, bridge, storage schema, and capabilities match.

### 4.3 Official OpenCode compatibility mode

Later phase only.

Behavior:

- Never selected automatically.
- Requires explicit user opt-in.
- Uses a maintained compatibility matrix.
- Disables unsupported fork features.
- Clearly marks reduced functionality.
- Does not run fork storage migrations against official storage.
- May need official matching web assets instead of fork web assets.

## 5. Storage model

Storage has three separate categories.

### 5.1 Shared fork product profile

The terminal `p4rth-opencode` CLI and the JetBrains plugin runtime use the same fork profile by default. This is intentional product behavior, not leakage.

The shared profile contains normal user-facing OpenCode state for this fork:

- Authentication credentials.
- Multi-account provider profiles.
- Active account and active org selection.
- Session database and message history.
- MCP OAuth tokens.
- Global configuration.
- Agents.
- Commands.
- Plugins.
- Permissions configured in `opencode.json` / `opencode.jsonc`.
- Model metadata and speech model cache, unless later explicitly separated.
- User preferences that belong to the fork product rather than to a single UI surface.

Default shared profile paths must be branded to `p4rth-opencode` and must not default to official `opencode` directories. The current code still defaults `Global.Path.*` to `opencode`; Phase 1 is not complete until that default is changed or a better platform-specific branded resolver is added.

Supported override variables for advanced users and tests:

```text
P4RTH_OPENCODE_CONFIG_DIR
P4RTH_OPENCODE_DATA_DIR
P4RTH_OPENCODE_CACHE_DIR
P4RTH_OPENCODE_STATE_DIR
P4RTH_OPENCODE_LOG_DIR
P4RTH_OPENCODE_TMP_NAME
```

The JetBrains plugin must not set these variables to JetBrains system paths merely because it launched the runtime. Doing so would split the user's fork profile between terminal and IDE. The plugin may pass these variables only when the user explicitly configures a separate profile or an integration test needs isolated storage.

Official OpenCode remains isolated:

- No mutable writes to official OpenCode storage.
- No fork migrations against official OpenCode storage.
- Read-only one-time credential import only.
- No automatic attachment to an official server.

### 5.2 JetBrains plugin-owned runtime storage

The plugin owns only its integration/runtime management files under the JetBrains system path:

- Downloaded runtime artifacts.
- Extracted runtime versions.
- Active runtime pointer.
- Runtime manifest cache.
- Download/install temporary files.
- Rollback metadata.
- Plugin diagnostics that are not normal OpenCode logs.

These files are plugin implementation details. They are not the user's fork profile.

### 5.3 Process/interface-local state

The plugin and sidecar keep per-launch or UI-local state separate from the shared fork profile:

- Random server password.
- Bound loopback port.
- Process handle and PID ownership record.
- Tool Window browser lifecycle state.
- JCEF bridge readiness.
- Temporary microphone permission state from JCEF/browser.

This state may live in memory or plugin-owned directories. It must not be written into official OpenCode storage.

The plugin must not globally rewrite `XDG_*` variables for the child process unless a later measured need proves it necessary. Tools and language servers launched by OpenCode should see the user's normal shell environment.

Shared project configuration remains compatible:

```text
opencode.json
opencode.jsonc
.opencode/
AGENTS.md
```

Fork-only project configuration should later use namespaced files:

```text
p4rth-opencode.jsonc
.p4rth-opencode/
```

## 6. Credential import and account migration

The fork must not share official OpenCode's live auth file.

One-time import flow:

1. Check whether fork auth storage is empty.
2. If empty, detect official OpenCode auth storage.
3. Parse only known compatible schemas.
4. Copy compatible credentials into fork auth storage.
5. Convert them to the fork's account-profile model.
6. Name a single imported provider credential `Default`.
7. Record source schema and migration version.
8. Never modify official auth storage.
9. Never delete official credentials.
10. Never repeatedly overwrite fork accounts from official storage.
11. Never log tokens.
12. Redact tokens from diagnostics.

The import should run lazily when credentials are first needed, not during plugin installation.

If consent is required for a provider or enterprise environment, show one minimal confirmation at the moment authentication is needed.

## 7. Server product handshake

The server exposes:

```http
GET /global/product
```

Response shape:

```json
{
  "product": "p4rth-opencode",
  "runtimeVersion": "1.18.3",
  "upstreamVersion": "1.18.3",
  "protocolVersion": 1,
  "webVersion": "1.18.3",
  "runtimeChannel": "jetbrains-stable",
  "jetbrainsBridgeVersion": 1,
  "storageSchemaVersion": 1,
  "capabilities": ["jetbrains-context", "voice-input", "provider-multi-account", "provider-account-switching"]
}
```

The plugin validates:

- Product ID.
- Runtime protocol version.
- JetBrains bridge version.
- Storage schema version.
- Runtime channel.
- Required capabilities.
- Web compatibility.
- Server API compatibility.

Unknown or incompatible runtimes are rejected.

Version fields are intentionally independent:

- `runtimeVersion`: this fork runtime build.
- `upstreamVersion`: upstream OpenCode version or upstream base revision this fork is based on.
- `webVersion`: bundled web UI version.
- `protocolVersion`: server/client API contract expected by host integrations.
- `jetbrainsBridgeVersion`: browser-to-IDE bridge contract.
- `storageSchemaVersion`: fork profile storage/migration contract for host compatibility decisions.

Do not infer one of these values from another. A plugin can be compatible with one runtime version range while requiring a specific bridge or storage version.

## 8. Managed runtime distribution

The plugin package does not bundle binaries for every platform.

Release process publishes platform-specific runtime ZIPs and a trusted manifest from this fork's release pipeline.

Manifest shape:

```json
{
  "pluginVersion": "1.18.3",
  "runtimeVersion": "1.18.3",
  "product": "p4rth-opencode",
  "protocolVersion": 1,
  "jetbrainsBridgeVersion": 1,
  "storageSchemaVersion": 1,
  "artifacts": {
    "darwin-arm64": {
      "url": "https://.../p4rth-opencode-darwin-arm64.zip",
      "sha256": "...",
      "size": 12345678
    }
  }
}
```

Supported first-release platforms:

- macOS ARM64.
- macOS x64.
- Windows x64.
- Linux x64.
- Linux ARM64.

Security requirements:

- Manifest URL must use HTTPS.
- Artifact URL must use HTTPS.
- Artifact platform must match exact OS and CPU architecture.
- Artifact size must be capped.
- SHA-256 must match before extraction.
- No execution before verification.
- Archive extraction must reject traversal.
- Archive extraction must reject unsafe symlinks.
- Partial downloads must be cleaned up.
- Installation must be atomic.
- Executable permission must be set on Unix.
- Current runtime must not be removed before replacement is verified.
- Signed manifests and signed artifacts are Phase 2 hardening.

Managed runtime install path:

```text
<JetBrains system path>/
  p4rth-opencode/
    runtimes/
      <runtime-version>/
        <platform-architecture>/
          p4rth-opencode
    active-runtime.txt
    manifests/
    downloads/
    tmp/
    diagnostics/
```

Do not put the shared fork profile under this tree by default. The managed runtime binary is plugin-owned; the fork profile is product-owned and shared with the terminal CLI.

Runtime retention:

- Keep active runtime.
- Keep previous known-good runtime.
- Keep temporary runtime during installation.
- Do not delete a runtime used by an active process.

## 9. Runtime updates and rollback

Each plugin release declares:

```text
preferredForkRuntimeVersion
minimumForkRuntimeVersion
maximumForkRuntimeVersion
protocolVersion
jetbrainsBridgeVersion
storageSchemaVersion
```

Update sequence:

1. Download replacement manifest.
2. Validate manifest identity and compatibility.
3. Download replacement artifact.
4. Verify integrity.
5. Extract safely into a temp directory.
6. Start the replacement runtime on a temporary loopback port.
7. Check health.
8. Validate product handshake.
9. Validate bridge/API compatibility.
10. Mark replacement as active.
11. Keep previous runtime as known-good.
12. Roll back automatically if startup or validation fails.

The managed runtime must not self-update. Plugin-managed child processes receive runtime-only overrides such as:

```text
OPENCODE_DISABLE_AUTOUPDATE=1
```

No user global configuration or official OpenCode configuration is modified.

## 10. Sidecar lifecycle

Preferred end state: one shared fork server per user profile, with JetBrains connecting to that server when compatible and safe.

The first stable plugin may start one plugin-managed sidecar per JetBrains IDE application, but that is an implementation step, not the desired long-term architecture. The sidecar count should eventually be counted per shared fork profile/runtime server, not per project. Multiple open projects in one IDE should share one managed process. Concurrent terminal + JetBrains usage should converge on one compatible fork server where possible, or fail clearly when a server is incompatible or already owned by another mode.

Startup command:

```bash
p4rth-opencode web \
  --hostname 127.0.0.1 \
  --port <random> \
  --no-open \
  --no-mdns
```

Startup flow:

1. User opens Tool Window or invokes an OpenCode action.
2. Application service checks for a compatible fork server for the shared profile.
3. If a compatible server exists and policy allows attaching, connect to it.
4. If none exists, acquire startup lock.
5. Resolve runtime.
6. Start runtime off the Event Dispatch Thread.
7. Bind loopback only.
8. Generate random per-launch password.
9. Poll `/global/health`.
10. Fetch `/global/product`.
11. Validate identity and capabilities.
12. Expose sidecar info to project UI.

Shutdown flow:

1. Stop only exact process started by plugin.
2. Attempt graceful destroy.
3. Wait bounded timeout.
4. Force destroy if needed.
5. Close stdout/stderr readers.
6. Clear active handle.

Idle shutdown final behavior:

```text
Browser visible: sidecar running
Browser hidden but work active: sidecar running
No browser, task, permission, or auth flow: idle timer starts
Idle for 20 minutes: sidecar stops
Next action: sidecar restarts automatically
```

The plugin never kills processes by name and never kills official OpenCode.

Concurrent usage requirements:

- A terminal `p4rth-opencode` session and a JetBrains Tool Window must not corrupt shared database, auth, MCP auth, config, cache, or lock state.
- Account switching in either surface should be visible to the other after refresh/reload because active account state is shared fork state.
- If both surfaces ask for permissions at the same time, pending approvals remain request/session scoped and do not cross-approve unrelated work.
- Database migrations are guarded by an exclusive cross-process file lock in the shared fork state directory (`database-migration:<db path>`), so terminal and JetBrains processes cannot apply schema migrations against one profile concurrently. Remaining startup/shared-server coordination is still Phase 2.

## 11. Tool Window and JCEF lifecycle

The plugin registers the Tool Window declaratively but creates no browser until the content is activated.

Initial resource rules:

```text
Plugin installed: 0 managed fork processes, 0 browsers
Project opened: 0 managed fork processes, 0 browsers
Tool Window never opened: 0 managed fork processes, 0 browsers
Tool Window activated: prepare runtime, start sidecar, create browser
```

Browser lifecycle:

- Create one `JBCefBrowser` per active project Tool Window.
- Keep browser briefly while hidden for fast reopening.
- Dispose browser after 10 minutes hidden and idle.
- Do not dispose during streaming, tool calls, permission prompts, auth flows, runtime preparation, or file operations.
- Dispose `JBCefJSQuery`, handlers, browser, and project references together.

Fallback when JCEF is unavailable:

- Show native panel.
- Explain JCEF is unavailable.
- Show server URL if available.
- Provide Retry.
- Provide Open in Browser.
- Provide Diagnostics.

Voice input requirements in JCEF:

- JCEF must expose `navigator.mediaDevices.getUserMedia` and `AudioContext` for the web voice UI to work.
- The plugin must handle microphone permission prompts intentionally; silent failure should show the existing disabled/unsupported voice state.
- Local transcription still runs in the OpenCode runtime through `experimental.transcribe`; JCEF only captures browser audio and sends WAV bytes to the server.
- The runtime distribution must include the `opencode-speech` sidecar where voice is advertised as supported.
- If microphone access or the speech sidecar is unavailable, the UI should degrade to text input without blocking normal chat.

## 12. Web host bridge

The web app provides a typed optional bridge. Browser and desktop behavior continue when bridge globals are absent.

Kotlin host global:

```ts
window.__P4RTH_OPENCODE_IDE__ = {
  version: 1,
  getContext(options),
  openFile(request),
  notifyReady()
}
```

Web app global:

```ts
window.__P4RTH_OPENCODE_IDE_HOST__ = {
  version: 1,
  addContext(context)
}
```

Rules:

- No DOM scraping.
- No CSS selector automation.
- No simulated clicks.
- No text scraping.
- Small typed API only.
- Versioned handshake.
- Missing required capabilities fail visibly.

## 13. Composer context model

IDE context is visible and removable. It is not hidden in system prompts by default.

Selected code appears as composer context with:

- Relative file path.
- Language.
- Line range.
- Modified/unsaved indicator.
- Truncation indicator.
- Preview of selected text.
- Remove button.

Open files action adds metadata only:

- Relative path.
- Modified state.
- Active-file indicator.

It does not attach all contents.

Fallback representation when the existing attachment model requires text:

````text
<ide_context>
File: src/example.ts
Language: TypeScript
Selection: lines 12-18
Unsaved: true
Truncated: false

```typescript
selected source code
```
</ide_context>
````

## 14. IDE context collection

Implemented actions:

- Add Selection to OpenCode.
- Add Current File to OpenCode.
- Add Open Files to OpenCode.
- Current caret context on demand.
- File navigation from web UI to JetBrains editor.

Collection rules:

- Collect only when user invokes an action or the web composer explicitly asks.
- Read unsaved selection text from JetBrains `Document`.
- Use project-relative paths where possible.
- Use canonical paths internally.
- Use one-based line and column numbers in payloads.
- Do not stream caret movement.
- Do not serialize the project.
- Do not send all open file contents.
- Do not retain long-lived `Editor` or `Document` references.
- Enforce selection-size limit.
- Mark truncated selections visibly.

## 15. File navigation

When the web UI asks JetBrains to open a file:

1. Resolve path against active project root.
2. Normalize and canonicalize.
3. Reject path traversal.
4. Reject paths outside the project unless a later explicit policy supports it.
5. Use JetBrains public file editor APIs.
6. Move caret to requested one-based line/column.
7. Focus editor.

External `http` and `https` links continue opening normally through browser/platform behavior.

## 16. File refresh

OpenCode file writes should refresh only affected paths.

Implementation plan:

- Reuse existing server events where possible.
- Add a small file-change event if no suitable event exists.
- JetBrains plugin subscribes while Tool Window or sidecar is active.
- Debounce repeated events.
- Refresh exact files or parent directories.
- Never refresh the whole project for every event.
- Never overwrite unsaved JetBrains documents silently.
- Show conflicts when server writes overlap unsaved editor changes.

## 17. Advanced settings

Location:

```text
Settings → Tools → OpenCode → Advanced
```

Settings:

- Managed fork runtime.
- External fork runtime path.
- Existing fork server URL.
- Official compatibility mode, later phase only.
- Runtime manifest URL or mirror.
- Proxy configuration.
- Diagnostic logging.
- Browser idle timeout.
- Sidecar idle timeout.

Successful first-run should never open this settings page.

## 18. Diagnostics and logging

Diagnostics bundle includes:

- Plugin version.
- Runtime version.
- Product handshake result.
- Runtime mode.
- Platform and architecture.
- Sidecar process state.
- Recent redacted stdout/stderr tail.
- Runtime installation status.
- Storage path summary split into shared fork profile paths and JetBrains plugin-owned runtime paths.
- JCEF availability.
- JCEF microphone capability and permission status when voice support is being diagnosed.

Diagnostics must redact:

- Provider tokens.
- API keys.
- Basic auth headers.
- OAuth access/refresh tokens.
- Full prompts.
- Selected source text by default.

## 19. Security model

Required protections:

- Loopback-only server binding.
- Random port.
- Random per-launch password.
- Product identity validation.
- Runtime manifest identity validation.
- Artifact integrity verification.
- Safe archive extraction.
- No execution before verification.
- Official OpenCode mutable storage isolation.
- Shared p4rth-opencode profile for terminal and JetBrains by default.
- Read-only official credential import.
- No token logging.
- Project-root bounded file navigation.
- No process-name killing.
- No arbitrary localhost attachment.
- Explicit process ownership.
- Project trust handling.

## 20. Performance model

Hard requirements:

- Plugin installation starts no external process.
- Opening a project starts no external process.
- Never activating OpenCode creates no browser.
- At most one managed sidecar per IDE application.
- Hidden inactive browsers are disposed.
- Completely idle sidecars stop.
- Caret changes are not continuously monitored.
- Open file contents are not automatically transmitted.
- JetBrains indexing is not duplicated.
- Long operations run off the Event Dispatch Thread.
- Plugin unload and IDE shutdown leave no owned process.

Measurements to record:

1. Baseline: plugin installed but inactive.
2. Tool Window open.
3. Tool Window hidden before and after browser disposal.
4. Sidecar stopped.
5. One, two, and three projects open.
6. 20 repeated lifecycle cycles.
7. Official OpenCode running at the same time.

Metrics:

- JVM heap.
- Native memory.
- JCEF renderer memory.
- Sidecar RSS.
- Child process count.
- Idle CPU.
- Time until UI is usable.

## 21. Automated tests

Kotlin plugin tests:

- Product identity validation.
- Protocol compatibility validation.
- Bridge compatibility validation.
- Storage-schema compatibility validation.
- Official-versus-fork runtime rejection.
- Platform detection.
- Architecture detection.
- Manifest parsing.
- HTTPS enforcement.
- Checksum verification.
- Safe archive extraction.
- Path traversal rejection.
- Symlink extraction rejection.
- Atomic install.
- Runtime selection.
- Runtime rollback.
- Duplicate startup prevention.
- Does not set `P4RTH_OPENCODE_*_DIR` to JetBrains system paths by default.
- Separates plugin-owned runtime paths from shared fork profile paths.
- Port selection.
- Health polling.
- Product handshake.
- Shared compatible server attachment policy.
- Project-relative path handling.
- Outside-project path rejection.
- Context serialization.
- Selection truncation.
- Secret redaction.
- Process state transitions.
- Idle shutdown.
- JCEF unavailable fallback.
- JCEF microphone unsupported/denied states.

TypeScript tests:

- Fork storage path overrides.
- Branded default storage paths for `p4rth-opencode`.
- Terminal and JetBrains default path resolution match when no explicit override is set.
- Product endpoint response.
- HttpApi route coverage.
- Credential import idempotency.
- Official credential non-modification.
- MCP auth file uses shared fork data path.
- Account active state uses shared fork database.
- Bridge context conversion.
- Browser absence behavior.
- Visible composer context injection.
- Voice unavailable behavior in browser/JCEF-like environments.

Integration/manual tests:

- `buildPlugin`.
- `verifyPlugin`.
- `runIde`.
- JCEF smoke test.
- Runtime download test.
- Runtime rollback test.
- Process cleanup test.
- Coexistence test with official OpenCode.
- Concurrent terminal `p4rth-opencode` + JetBrains Tool Window test against the same shared profile.
- First-run with existing terminal fork auth/config/session state.
- Voice dictation in JCEF with microphone allowed, microphone denied, and speech sidecar missing.
- IntelliJ IDEA.
- WebStorm or PyCharm.
- Linux, macOS, Windows.

## 22. Phase plan

### Phase 1 — usable vertical slice

Goal: one developer can run the plugin with a compatible fork runtime and use OpenCode in a Tool Window.

Scope:

- Plugin project foundation.
- Product identity.
- Branded shared fork profile defaults.
- Fork storage overrides for explicit separate profiles and tests.
- Product endpoint.
- Managed runtime installer foundation.
- One plugin-managed sidecar shared by all open projects in the IDE process.
- Cross-process migration lock for the shared fork profile (terminal + JetBrains safe).
- Lazy Tool Window with JCEF.
- Basic recovery panel.
- Add Selection.
- Add Current File.
- Add Open Files.
- Basic file navigation.
- Targeted file refresh from OpenCode-controlled writes (reusing the `file.edited` event).
- Browser activity contract so JCEF is not disposed mid-operation.
- Typed web bridge.
- Basic tests and docs.

Server attachment scope for Phase 1:

- Phase 1 always starts or reuses the sidecar owned by the current JetBrains IDE application.
- Automatic attachment to a terminal-started server is deferred.
- Terminal and JetBrains may run separate processes against the shared profile only after migration locking and concurrency tests pass.
- No automatic shared-server attachment is claimed until protected discovery metadata, process-liveness validation, product/protocol validation, secure credential retrieval, and stale-entry cleanup all exist. None of these are implemented yet, so discovery/attachment stays out of Phase 1.

Exit criteria:

- TypeScript packages pass typecheck.
- Kotlin plugin builds.
- `runIde` loads plugin.
- Opening project starts no process.
- Opening Tool Window starts managed fork runtime.
- Product mismatch is rejected.
- Official OpenCode is untouched.
- Terminal `p4rth-opencode` and JetBrains plugin see the same fork config/auth/account state by default.
- Plugin-owned runtime storage is separate from shared fork profile storage.
- Concurrent terminal + JetBrains migrations against one shared profile apply exactly once (cross-process lock tested).
- Browser activity flags prevent JCEF disposal while streaming, running tools, awaiting permission, authenticating, performing file operations, or recording voice.
- Targeted file refresh updates only affected paths and never the whole project.
- Selection context appears visibly in composer.

### Phase 2 — runtime delivery hardening

Goal: safe, reliable distribution and updates.

Scope:

- Signed manifests.
- Signed runtime artifacts.
- macOS notarization flow.
- Windows code signing flow.
- Enterprise proxy support.
- Runtime mirrors.
- Offline install package.
- Delta updates if worthwhile.
- Runtime cleanup policy.
- Rollback diagnostics.
- CI release workflow for runtime + plugin.
- Cross-process startup/server-attach locking (the database migration lock ships in Phase 1; remaining startup and shared-server coordination is hardened here).

Exit criteria:

- Fresh install downloads verified runtime on all supported platforms.
- Runtime replacement rolls back when validation fails.
- Plugin never downloads official OpenCode.
- Plugin does not leave partial installs after cancellation.
- Concurrent launches do not corrupt shared profile files (database migrations are already serialized by the Phase 1 cross-process lock).

### Phase 3 — deeper IDE integration

Goal: useful JetBrains-specific context without duplicating JetBrains indexing.

Scope:

- Native permission notifications.
- Shared compatible fork server discovery/attachment, if not completed in Phase 1.
- Problems/diagnostics context.
- Git branch and changed files context.
- Run configuration context.
- Terminal context.
- Test failure context.
- PSI symbol context, only on demand.
- Current class/method context.
- JetBrains theme synchronization.
- Reduced Resource mode.
- More precise targeted file refresh.

Exit criteria:

- Context remains user-visible and removable.
- No continuous caret streaming.
- No project-wide scanning loop.
- Measured resource cost stays acceptable.

### Phase 4 — Remote Development design and implementation

Goal: support JetBrains Gateway / Remote Development correctly.

Separate responsibilities:

- Client-side JCEF may run locally.
- JetBrains backend may run remotely.
- Project files are remote.
- Runtime sidecar may need to run remote.
- Server access may require port forwarding.
- File navigation crosses client/backend boundary.

Scope:

- Explicit architecture design before code.
- Remote runtime placement policy.
- Port forwarding strategy.
- Remote storage isolation.
- Remote file navigation.
- Authentication handling.

Exit criteria:

- No assumption that browser, IDE backend, and sidecar are on the same machine.

### Phase 5 — official compatibility mode

Goal: optional reduced-functionality mode for official OpenCode.

Scope:

- Upstream compatibility matrix.
- Official runtime detection only after explicit opt-in.
- Official product validation.
- Disable unsupported fork features.
- Prevent fork migrations against official storage.
- Use official web UI when needed.
- Compatibility-specific tests.

Exit criteria:

- Never selected automatically.
- User sees clear reduced-functionality labeling.
- No fork-only writes to official storage.

## 23. Release workflow

1. Build fork runtime for each supported OS/arch.
2. Name runtime executables/artifacts as `p4rth-opencode` where platform packaging allows it, while keeping any internal compatibility needs explicit.
3. Include fork web UI and optional voice sidecar artifacts as needed.
4. Sign runtime artifacts.
5. Generate manifest with exact runtime/upstream/web/protocol/bridge/storage versions.
6. Upload artifacts and manifest to this fork's GitHub Releases.
7. Build JetBrains plugin.
8. Run plugin verifier against supported IDE versions.
9. Run `runIde` smoke tests.
10. Run runtime download smoke tests.
11. Sign JetBrains plugin.
12. Publish plugin to JetBrains Marketplace as `P4rth OpenCode`.
13. Document untested platforms honestly.

## 24. Repository findings and remaining decisions

These findings are grounded in the current repository state and should guide the next implementation pass.

1. `packages/core/src/product.ts` defines `Product.id = "p4rth-opencode"`, `envPrefix = "P4RTH_OPENCODE"`, and independent protocol/bridge/storage versions. Keep this as the source of truth.
2. `Product.name` and JetBrains `ProductIdentity.displayName` still say `OpenCode`. Decision: change user-facing fork surfaces to `P4rth OpenCode` while keeping product ID stable.
3. `packages/core/src/global.ts` still defaults `const app = "opencode"`. This currently routes default data/config/cache/state paths to official-looking directories. Decision: change default branded path ownership to `p4rth-opencode` before Phase 1 is complete.
4. `Global.resolvePaths` already supports `P4RTH_OPENCODE_CONFIG_DIR`, `DATA_DIR`, `CACHE_DIR`, `STATE_DIR`, `LOG_DIR`, and `TMP_NAME`. These should remain override escape hatches, not the plugin's default launch mechanism.
5. `Global.make` still honors legacy `OPENCODE_CONFIG_DIR` via `Flag.OPENCODE_CONFIG_DIR`. Decision: keep only if deliberate compatibility is needed; otherwise prevent it from accidentally redirecting the fork profile.
6. `packages/core/src/database/database.ts` stores the default SQLite database as `Global.Path.data/opencode.db`. Decision: once `Global.Path.data` is branded, the filename can stay if desired; the directory boundary is the important isolation boundary.
7. SQLite uses WAL, `synchronous = NORMAL`, `busy_timeout = 5000`, and passive checkpointing. This helps concurrent reads/writes but is not a full startup/migration coordination design.
8. `packages/core/src/database/migration.ts` now wraps the check-and-apply in an exclusive cross-process file lock (via `Flock`, keyed by database path, under `Global.Path.state/locks`) in addition to the in-process semaphore. `:memory:` and unkeyed callers skip the file lock. The database layer threads the resolved path as `lockKey`. Covered by `packages/core/test/database-migration-lock.test.ts` (two processes, one profile, exactly-once).
9. `packages/core/src/util/flock.ts` provides file locks under `Global.Path.state/locks`. This is the likely primitive for cross-process auth/MCP/migration coordination.
10. Legacy provider auth is in `Global.Path.data/auth.json`; the current fork import foundation reads official `xdgData/opencode/auth.json` without modifying it. Keep official import read-only and idempotent.
11. New account state is stored in SQLite tables `account` and `account_state`; active account and active org are shared profile state, not JetBrains UI state.
12. Global config loads from `Global.Path.config/config.json`, `opencode.json`, and `opencode.jsonc`, and auto-creates a schema-only config when no explicit config env is set. This should happen in the shared fork profile, not JetBrains system storage.
13. Project config is read from `opencode.json`, `opencode.jsonc`, and `.opencode` directories directly in the project tree. The plugin should not copy or shadow project config.
14. macOS managed preferences can override config. The plugin plan must not assume config is only filesystem-backed JSON.
15. MCP server definitions come from config. MCP OAuth tokens are stored in `Global.Path.data/mcp-auth.json` and protected with `EffectFlock`. This belongs in the shared fork profile.
16. Permission rules come from config and session input; pending requests and `always` approvals are process-local in `packages/opencode/src/permission/index.ts`. Decision: do not treat one process's in-memory approval as global policy.
17. `opencode web` defaults to loopback and port `0`; explicit port `0` tries `4096` first, then any free port. The plugin should pass an explicit random loopback port if it wants to avoid the 4096 preference.
18. `web --no-open` works through yargs' boolean negation for the `open` option. `--no-mdns` works through the shared network option.
19. Server auth uses Basic Auth from `OPENCODE_SERVER_PASSWORD` and default username `opencode`. The plugin can keep a per-launch random password in process-local state.
20. `/global/product` exists, but the handler currently hard-codes several values and sets `upstreamVersion` equal to `runtimeVersion`. Decision: expose a real upstream base version or commit when release metadata exists.
21. `/global/product` schema validates product/protocol/bridge/storage literals from `Product`. Keep plugin validation strict and capability based.
22. The TypeScript CLI still uses script name `opencode`, and the build emits `dist/<target>/bin/opencode`. Decision: fork runtime packaging must produce a `p4rth-opencode` executable or documented wrapper.
23. The npm shim `packages/opencode/bin/opencode` uses official names, `OPENCODE_BIN_PATH`, and a `.opencode` cache. Decision: fork package/shim naming must be corrected before distribution.
24. `InstallationVersion` and `InstallationChannel` are compile-time constants, with no commit field. Decision: add release metadata if plugin/runtime compatibility needs commit-level diagnostics.
25. Speech support currently uses `OPENCODE_STT_MODEL`, `OPENCODE_SPEECH_BIN`, `opencode-speech`, and `xdgCache/opencode/speech`, not `Global.Path.cache`. Decision: fork-brand or intentionally share speech cache; do not advertise voice in JetBrains until the packaged sidecar path is valid.
26. Web voice captures audio through `navigator.mediaDevices.getUserMedia` and `AudioContext`, encodes WAV in the browser, and calls `experimental.transcribe`. JCEF support depends on browser microphone permission and API availability.
27. TUI voice uses the native speech binary directly through `Speech.recordCommand`. JetBrains web voice does not use terminal recording; it uses the server transcription route.
28. `run --attach` and the separate `attach <url>` command already exist. They are useful references for future shared-server attachment behavior.
29. The current TUI command starts a worker/internal transport by default; it only uses external server transport with explicit network options or mDNS. Do not assume the terminal TUI already publishes a reusable server.
30. `packages/cli/src/services/daemon.ts` has a daemon registry under `Global.Path.state`, but it appears to belong to the newer `packages/cli` path rather than the main `packages/opencode` runtime. Reuse only after confirming package ownership.
31. The web bridge currently exposes `window.__P4RTH_OPENCODE_IDE_HOST__.addContext(...)` and optional host methods. File navigation is part of the bridge contract but still needs complete host/UI wiring.
32. IDE context conversion adds visible file context items with path, selection range, preview, and comments for modified/truncated/language state. This matches the product rule that IDE context should be visible and removable.
33. `sdks/jetbrains/runtime/RuntimePaths.kt` now creates only plugin-owned runtime-management dirs (runtimes, active pointer, manifests, downloads, tmp, diagnostics); it no longer creates config/data/cache/state/log. Fixed.
34. `sdks/jetbrains/runtime/SidecarProcess.kt` no longer sets `P4RTH_OPENCODE_*_DIR`; the launched runtime resolves the shared fork profile from its branded defaults. Fixed.
35. Kotlin verification has not run locally because `gradle`, `kotlinc`, and Kotlin LSP are unavailable in this environment. CI or a developer machine with the JetBrains toolchain must verify the plugin.

## 25. Current implementation status

Every item is classified as: **Implemented and tested**, **Implemented but unverified**, **Partially implemented**, **Planned only**, or **Blocked**. Phase 1 is not complete.

### Implemented and tested (TypeScript, verified with `bun test`)

- `p4rth-opencode` product identity and fork-specific storage env overrides.
- Branded default shared fork profile paths (`packages/core/src/global.ts` uses `Product.id`); `packages/core/test/global.test.ts`.
- `Product.name` display is `P4rth OpenCode`.
- `/global/product` endpoint and schema; `packages/opencode/test/server/httpapi-global.test.ts`.
- `web --no-open`.
- One-time read-only official auth import; `packages/opencode/test/auth/auth.test.ts`.
- Typed web bridge + visible composer context conversion; `packages/app/src/context/ide-host.test.ts`.
- Cross-process database migration lock (exclusive `Flock`, keyed by db path, in the shared fork state dir); `packages/core/test/database-migration-lock.test.ts` (two processes / one profile / exactly-once) plus the existing `database-migration.test.ts` suite.
- IDE activity contract (`IdeActivityState`, merge/active helpers) and web-side reporter wiring; `packages/app/src/context/ide-host.test.ts`. `streaming`/`toolRunning`/`permissionPending` are wired from server-sync.

### Implemented and tested (Kotlin — compiled and verified against the real IntelliJ platform)

Toolchain: Gradle 9.0.0 + JBR 21. `./gradlew compileKotlin` succeeds; `./gradlew test` runs 19/19 unit tests green across 7 suites; `./gradlew buildPlugin` produces `p4rth-opencode-jetbrains-1.18.3.zip`; `./gradlew verifyPlugin` reports **Compatible** against IC-242, IC-243, IC-251, IC-252 with no upper-bound cap.

- JetBrains plugin package, product/manifest/platform validation, managed runtime installer, sidecar startup — compile clean; `ProductValidatorTest`, `RuntimeManifestParserTest`, `PlatformDetectorTest`, `SecretRedactorTest`, `SidecarLaunchEnvTest` pass.
- `RuntimePaths.kt` owns only runtime-management dirs; `SidecarProcess.kt` no longer sets `P4RTH_OPENCODE_*_DIR`.
- Sidecar idle shutdown, browser hidden disposal, tool-window visibility handling, and plugin unload / IDE shutdown cleanup (`OpenCodeApplicationService` is `Disposable`).
- Browser activity guard: JCEF is not disposed while any activity flag is true; conservative on malformed payloads; page teardown releases the microphone. `BrowserActivityTest` passes.
- Targeted file refresh reusing `file.edited`: SSE subscription per project, canonical-directory filtering, debounced batch, exact-file/nearest-parent refresh, unsaved-document detection. `FileRefreshEventsTest` passes.
- Deprecated APIs removed for forward compatibility: `Project.getBaseDir()` → `project.basePath`; `JBCefJSQuery.create(JBCefBrowser)` (scheduled for removal) → the `JBCefBrowserBase` overload.
- Build config: Gradle wrapper committed (Gradle 9.0.0); `settings.gradle.kts`/`build.gradle.kts` fixed for IntelliJ Platform Gradle Plugin 2.18.1; `untilBuild` cap removed.

### Partially implemented

- Browser activity: `streaming`, `toolRunning`, and `permissionPending` are wired from web state. `authenticationActive`, `fileOperationActive`, and `voiceActive` are part of the contract and enforced by the host, but are conservatively left idle until their web state is surfaced.
- File refresh handles create/modify (`file.edited`) and refreshes parents for deletes; rename/delete precision is Phase 3.

### Planned only

- Runtime executable/package naming and the npm shim still largely say `opencode` (packaging/release work).
- Speech cache and speech binary env names still use official `opencode` naming; voice not advertised until the packaged sidecar path is valid.
- `/global/product` distinct upstream base version or commit.
- Local fork runtime artifact named `p4rth-opencode` and a published test manifest.
- Gradle wrapper / CI toolchain.
- Shared compatible server discovery/attachment (requires protected discovery metadata, liveness, product/protocol validation, secure credential retrieval, stale-entry cleanup — none exist yet).
- Memory/process usage measurements.

### Blocked (need the JetBrains toolchain or a fork runtime build)

- `buildPlugin`, `verifyPlugin`, `runIde` — no gradle/kotlinc here.
- JCEF smoke test and voice (mic allowed / denied / speech sidecar missing).
- End-to-end targeted file refresh and browser activity behavior in a live IDE.
- Concurrent terminal + JetBrains shared-profile behavior in a live IDE (the migration lock itself is tested headlessly).
- Process cleanup exercised under `runIde` / IDE shutdown.

### Phase 1 is not complete until all of the following hold

- Kotlin compiles.
- Plugin Verifier passes.
- `runIde` loads the plugin.
- Cross-process migration locking is tested (done headlessly; re-confirm in the live concurrent scenario).
- Browser activity prevents mid-operation disposal (verified in a live IDE).
- Targeted file refresh works (verified in a live IDE).
- Process cleanup is exercised.
- Concurrent terminal and JetBrains use is tested.

## 26. Non-goals before stable Phase 1

- Swing chat UI.
- A second chat protocol.
- Kotlin provider/model/session logic.
- DOM automation.
- One sidecar per project.
- Official runtime auto-detection.
- Official compatibility mode.
- Remote Development.
- Inline completion.
- Native diff UI.
- Deep PSI context by default.
