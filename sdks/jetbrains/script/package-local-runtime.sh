#!/usr/bin/env bash
# Build speech + single-platform opencode for this host, package a JetBrains runtime ZIP.
# Usage:
#   ./sdks/jetbrains/script/package-local-runtime.sh [version]
# Example:
#   ./sdks/jetbrains/script/package-local-runtime.sh 2.0.1

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
VERSION="${1:-2.0.1}"

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) PLATFORM=linux-x64; DIST=opencode-linux-x64; SPEECH=opencode-speech; EXE=opencode ;;
  Linux-aarch64|Linux-arm64) PLATFORM=linux-arm64; DIST=opencode-linux-arm64; SPEECH=opencode-speech; EXE=opencode ;;
  Darwin-x86_64) PLATFORM=darwin-x64; DIST=opencode-darwin-x64; SPEECH=opencode-speech; EXE=opencode ;;
  Darwin-arm64) PLATFORM=darwin-arm64; DIST=opencode-darwin-arm64; SPEECH=opencode-speech; EXE=opencode ;;
  MINGW*|MSYS*|CYGWIN*) echo "Use package-local-runtime on a native shell or run packageRuntimeRelease after a Windows build"; exit 1 ;;
  *) echo "Unsupported host: $(uname -s)-$(uname -m)"; exit 1 ;;
esac

echo "==> Building speech sidecar (release)"
(
  cd "$ROOT/packages/speech"
  cargo build --release --locked
)

echo "==> Building opencode for $PLATFORM (with speech)"
(
  cd "$ROOT/packages/opencode"
  OPENCODE_VERSION="$VERSION" bun run build --single --skip-install
)

SPEECH_SRC="$ROOT/packages/speech/target/release/$SPEECH"
SPEECH_DST="$ROOT/packages/opencode/dist/$DIST/bin/$SPEECH"
if [[ ! -x "$SPEECH_SRC" ]]; then
  echo "Missing speech binary at $SPEECH_SRC"
  exit 1
fi
# build.ts --single already copies speech when present; re-copy for safety
cp -f "$SPEECH_SRC" "$SPEECH_DST"
chmod 755 "$SPEECH_DST"
cp -f "$ROOT/packages/speech/THIRD_PARTY_NOTICES.md" \
  "$ROOT/packages/opencode/dist/$DIST/bin/OPENCODE_SPEECH_NOTICES.md"

test -f "$ROOT/packages/opencode/dist/$DIST/bin/$EXE"
test -x "$SPEECH_DST"

echo "==> Packaging JetBrains runtime for $PLATFORM"
(
  cd "$ROOT/sdks/jetbrains"
  ./gradlew --no-daemon packageRuntimeRelease \
    -PreleaseVersion="$VERSION" \
    -PreleasePlatform="$PLATFORM"
)

OUT="$ROOT/sdks/jetbrains/build/release/$VERSION"
echo "==> Done"
echo "    ZIP:      $OUT/p4rth-opencode-$PLATFORM.zip"
echo "    Manifest: $OUT/p4rth-opencode-jetbrains-runtime.json"
unzip -l "$OUT/p4rth-opencode-$PLATFORM.zip"
echo
echo "Upload (single platform) or run the GitHub Actions workflow for all platforms:"
echo "  gh release upload v$VERSION $OUT/* --clobber --repo P4rthPat3l/opencode"
