# P4rth OpenCode Release Checklist

Preferred path: **GitHub Actions multi-platform workflow** (builds `p4rth-opencode` + `opencode-speech` for every JetBrains target). Manual/local packaging is still supported for a single host.

## 0. Multi-platform release via GitHub Actions (recommended)

Workflow: [`.github/workflows/jetbrains-runtime-release.yml`](../.github/workflows/jetbrains-runtime-release.yml)

### What it does

1. Builds `opencode-speech` natively on 5 runners:
   - `linux-x64` → `ubuntu-24.04`
   - `linux-arm64` → `ubuntu-24.04-arm`
   - `darwin-arm64` → `macos-15`
   - `darwin-x64` → `macos-15-intel`
   - `windows-x64` → `windows-2025`
   - `windows-arm64` is **not** built yet (whisper.cpp ggml does not support MSVC on ARM)
2. Cross-compiles the OpenCode CLI for those 6 platforms with speech copied into each `bin/`
3. Runs `packageRuntimeRelease` → `p4rth-opencode-*.zip` + `p4rth-opencode-jetbrains-runtime.json`
4. Creates/updates GitHub Release `v<version>` with all assets

### How to run

1. Push the `jetbrains` branch (or merge the workflow to your default branch).
2. Open **Actions → JetBrains runtime release → Run workflow**.
3. Set **version** to match `pluginVersion` in `sdks/jetbrains/gradle.properties` (e.g. `2.0.1`).
4. Leave **create_release** checked.
5. Wait for green jobs, then verify:

```text
https://github.com/P4rthPat3l/opencode/releases/latest/download/p4rth-opencode-jetbrains-runtime.json
```

Each platform ZIP **must** list both `p4rth-opencode` and `opencode-speech` (or `.exe` variants). Packaging fails without speech.

### After publish — force clients to re-download

The plugin re-reads the manifest on prepare. Incomplete installs (binary without speech) are re-downloaded. To force a clean install on your machine:

```bash
rm -rf ~/.cache/JetBrains/*/p4rth-opencode
# Windows: %LOCALAPPDATA%\JetBrains\<IDE>\p4rth-opencode
# macOS: ~/Library/Caches/JetBrains/*/p4rth-opencode
```

Then reopen the OpenCode tool window.

### Optional signing secrets

| Secret | Purpose |
| --- | --- |
| `APPLE_CERTIFICATE` | base64 Developer ID `.p12` (macOS speech codesign) |
| `APPLE_CERTIFICATE_PASSWORD` | password for that `.p12` |

Without them, macOS/Windows binaries still ship; users may see first-run OS Gatekeeper/SmartScreen prompts. Linux needs no paid signing.

### Runner notes

- Public repos get free minutes on standard GitHub-hosted runners (including `ubuntu-24.04-arm`, `macos-15-intel`, `windows-11-arm`).
- No Blacksmith or self-hosted runners required.
- If a label is unavailable on your plan, edit the matrix host in the workflow (e.g. `macos-13` for Intel, drop `windows-11-arm` temporarily).

## 1. One-time accounts and tools

- Make `P4rthPat3l/opencode` public so standard GitHub Actions remain free.
- Install Bun, JDK 21, Rust, `gh`, and the native build tools required by `packages/speech` (only needed for local packaging).
- Run `gh auth login` and select the `P4rthPat3l/opencode` repository.
- Create a JetBrains Marketplace vendor profile, accept the Developer Agreement, and create a permanent Marketplace token.
- Follow the [JetBrains plugin signing guide](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html) to create a private key and certificate chain.

Use one version for the runtime and plugin, for example `2.0.1`. Update `pluginVersion` in `sdks/jetbrains/gradle.properties` before building.

Before committing the first release, remove generated IntelliJ caches from Git tracking if they were staged previously:

```bash
git rm -r --cached sdks/jetbrains/.intellijPlatform
```

The directory is ignored and Gradle will recreate it locally.

## 2. Quick local package for the current machine

Helper (speech + CLI + ZIP + partial manifest):

```bash
chmod +x sdks/jetbrains/script/package-local-runtime.sh
./sdks/jetbrains/script/package-local-runtime.sh 2.0.1
```

Manual equivalent (example: Linux x64):

```bash
cd packages/speech
cargo build --release --locked

cd ../opencode
OPENCODE_VERSION=2.0.1 bun run build --single --skip-install
# --single copies packages/speech/target/release/opencode-speech into dist when present

cd ../../sdks/jetbrains
./gradlew packageRuntimeRelease \
  -PreleaseVersion=2.0.1 \
  -PreleasePlatform=linux-x64
```

A single-platform ZIP is enough for **your** IDE. Marketplace / multi-OS users need the full Actions workflow.

The release files are written to:

```text
packages/opencode/dist/opencode-linux-x64.tar.gz
sdks/jetbrains/build/release/0.1.0/
```

Upload both kinds of assets if you want users to install your fork directly and also let the plugin auto-download the runtime:

- standalone CLI archive from `packages/opencode/dist/`
- JetBrains runtime ZIP(s) plus `p4rth-opencode-jetbrains-runtime.json` from `sdks/jetbrains/build/release/<version>/`

Inspect them before upload:

```bash
unzip -l build/release/0.1.0/p4rth-opencode-linux-x64.zip
./gradlew test buildPlugin
```

The ZIP must contain `p4rth-opencode` at its root. If speech was built, it should also contain `opencode-speech`.

Create the GitHub Release:

```bash
gh release create v0.1.0 \
  ../../packages/opencode/dist/opencode-linux-x64.tar.gz \
  build/release/0.1.0/* \
  --repo P4rthPat3l/opencode \
  --title "P4rth OpenCode 0.1.0" \
  --notes "First P4rth OpenCode release"
```

Verify these URLs return HTTP 200:

```text
https://github.com/P4rthPat3l/opencode/releases/latest/download/p4rth-opencode-jetbrains-runtime.json
https://github.com/P4rthPat3l/opencode/releases/download/v0.1.0/p4rth-opencode-linux-x64.zip
```

Anyone on that platform can now install it without building the repository:

```bash
mkdir -p "$HOME/.local/share/p4rth-opencode"
unzip p4rth-opencode-linux-x64.zip -d "$HOME/.local/share/p4rth-opencode"
chmod +x "$HOME/.local/share/p4rth-opencode/p4rth-opencode"
chmod +x "$HOME/.local/share/p4rth-opencode/opencode-speech"
export PATH="$HOME/.local/share/p4rth-opencode:$PATH"
p4rth-opencode --version
```

Add that PATH export to your shell profile. Keep `opencode-speech` beside `p4rth-opencode` so local voice transcription works. The JetBrains plugin installs the same ZIP automatically through the manifest.

## 3. Complete runtime release for Marketplace

Do not publish the Marketplace plugin until the manifest contains every platform you intend to support:

```text
linux-x64
linux-arm64
darwin-x64
darwin-arm64
windows-x64
windows-arm64
```

Build `packages/speech` and the runtime on each target OS/architecture. Collect the resulting `packages/opencode/dist/opencode-<platform>` directories into one checkout, then package all targets:

```bash
cd sdks/jetbrains
./gradlew packageRuntimeRelease -PreleaseVersion=0.1.0
```

The task fails if any required runtime is missing. You can also package platforms one by one; existing ZIPs in the same version directory are preserved and the manifest is regenerated with every platform currently present:

```bash
./gradlew packageRuntimeRelease -PreleaseVersion=0.1.0 -PreleasePlatform=linux-x64
./gradlew packageRuntimeRelease -PreleaseVersion=0.1.0 -PreleasePlatform=linux-arm64
./gradlew packageRuntimeRelease -PreleaseVersion=0.1.0 -PreleasePlatform=darwin-x64
./gradlew packageRuntimeRelease -PreleaseVersion=0.1.0 -PreleasePlatform=darwin-arm64
./gradlew packageRuntimeRelease -PreleaseVersion=0.1.0 -PreleasePlatform=windows-x64
./gradlew packageRuntimeRelease -PreleaseVersion=0.1.0 -PreleasePlatform=windows-arm64
```

On Windows, use `gradlew.bat` instead of `./gradlew`. The supported `releasePlatform` values match the names above.

To package standalone user-download archives for every available `packages/opencode/dist/opencode-*` target, run once after the builds are present:

```bash
cd packages/opencode
bun run package:release
```

That creates:

```text
opencode-linux-*.tar.gz
opencode-darwin-*.zip
opencode-windows-*.zip
```

Upload the generated ZIPs and manifest to the same GitHub Release. macOS and Windows public releases should be code-signed before packaging; otherwise users may see operating-system security warnings.

- **macOS:** build and sign both `opencode` and `opencode-speech` on the matching Intel or Apple Silicon machine, then notarize the public archive.
- **Windows:** build and Authenticode-sign `opencode.exe` and `opencode-speech.exe` before packaging.
- **Linux:** no paid platform signing is required.

## 4. Build and verify the JetBrains plugin

```bash
cd sdks/jetbrains
JAVA_HOME=/path/to/jdk-21 ./gradlew clean test buildPlugin verifyPlugin
```

The unsigned plugin ZIP is created under:

```text
sdks/jetbrains/build/distributions/
```

To sign it, provide the signing values without committing them:

```bash
export JETBRAINS_CERTIFICATE_CHAIN="$(cat chain.crt)"
export JETBRAINS_PRIVATE_KEY="$(cat private.pem)"
export JETBRAINS_PRIVATE_KEY_PASSWORD="your-password"
./gradlew signPlugin verifyPluginSignature
```

## 5. First JetBrains Marketplace upload

1. Open [JetBrains Marketplace](https://plugins.jetbrains.com/).
2. Choose **Upload plugin**.
3. Select your vendor profile.
4. Upload the signed ZIP from `build/distributions/`.
5. Choose an open-source license/EULA and link the source to `https://github.com/P4rthPat3l/opencode`.
6. Declare trader or non-trader status.
7. Explain that the plugin downloads a SHA-256-verified runtime from your GitHub Releases and runs it only on loopback.
8. Submit it for review.

The GitHub runtime release must remain available while the plugin is under review so JetBrains can test installation.

## 5a. Exact asset checklist for GitHub Release

For a full release, upload all standalone CLI archives that exist in `packages/opencode/dist/`:

```text
opencode-linux-x64.tar.gz
opencode-linux-arm64.tar.gz
opencode-darwin-x64.zip
opencode-darwin-arm64.zip
opencode-windows-x64.zip
opencode-windows-arm64.zip
```

Also upload all JetBrains runtime assets from `sdks/jetbrains/build/release/<version>/`:

```text
p4rth-opencode-linux-x64.zip
p4rth-opencode-linux-arm64.zip
p4rth-opencode-darwin-x64.zip
p4rth-opencode-darwin-arm64.zip
p4rth-opencode-windows-x64.zip
p4rth-opencode-windows-arm64.zip
p4rth-opencode-jetbrains-runtime.json
```

`packageRuntimeRelease` **requires** `opencode-speech` / `opencode-speech.exe` next to the main binary. Incomplete ZIPs are rejected at package time so voice does not silently break.

## 6. Later Marketplace updates

After the first plugin listing exists, create a Marketplace token under **My Tokens**, then publish updates directly:

```bash
export JETBRAINS_PUBLISH_TOKEN="your-marketplace-token"
export JETBRAINS_CERTIFICATE_CHAIN="$(cat chain.crt)"
export JETBRAINS_PRIVATE_KEY="$(cat private.pem)"
export JETBRAINS_PRIVATE_KEY_PASSWORD="your-password"
./gradlew publishPlugin
```

Never commit Marketplace tokens, private keys, certificate passwords, Apple certificates, or Windows signing credentials.
