# P4rth OpenCode Release Checklist

This is the low-maintenance release path: build locally, create one GitHub Release, then upload the plugin to JetBrains Marketplace manually.

## 1. One-time accounts and tools

- Make `P4rthPat3l/opencode` public so standard GitHub Actions remain free if automation is added later.
- Install Bun, JDK 21, Rust, `gh`, and the native build tools required by `packages/speech`.
- Run `gh auth login` and select the `P4rthPat3l/opencode` repository.
- Create a JetBrains Marketplace vendor profile, accept the Developer Agreement, and create a permanent Marketplace token.
- Follow the [JetBrains plugin signing guide](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html) to create a private key and certificate chain.

Use one version for the runtime and plugin, for example `0.1.0`. Update `pluginVersion` in `sdks/jetbrains/gradle.properties` before building.

Before committing the first release, remove generated IntelliJ caches from Git tracking if they were staged previously:

```bash
git rm -r --cached sdks/jetbrains/.intellijPlatform
```

The directory is ignored and Gradle will recreate it locally.

## 2. Quick GitHub release for the current machine

This example builds Linux x64. Change the platform key when building on another supported machine.

```bash
cd packages/speech
cargo build --release

cd ../opencode
OPENCODE_VERSION=0.1.0 bun run build --single --skip-install

cd ../../sdks/jetbrains
./gradlew packageRuntimeRelease \
  -PreleaseVersion=0.1.0 \
  -PreleasePlatform=linux-x64
```

The release files are written to:

```text
sdks/jetbrains/build/release/0.1.0/
```

Inspect them before upload:

```bash
unzip -l build/release/0.1.0/p4rth-opencode-linux-x64.zip
./gradlew test buildPlugin
```

The ZIP must contain `p4rth-opencode` at its root. If speech was built, it should also contain `opencode-speech`.

Create the GitHub Release:

```bash
gh release create v0.1.0 \
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
