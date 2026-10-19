#!/usr/bin/env bash
# Hermes Vox — full build/verify gate (handoff §10).
# One command: Go gates -> gomobile bind -> Gradle assembleDebug + unit tests.
# The app no longer links the bind (#126: the voice core is Kotlin); the Go
# steps stay until the Go code is removed.
#
# Portable: runs from any checkout. Toolchain locations come from the
# environment (JAVA_HOME, ANDROID_HOME, ANDROID_NDK_HOME); the defaults below
# are the maintainer's build host and only apply when a variable is unset.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-$HOME/jdk-17}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/${ANDROID_NDK_VERSION:-25.2.9519653}}"

# A gate that silently substitutes a different toolchain is not a gate: the
# release build is pinned to JDK 17 and the NDK the app was measured on, so a
# missing default must stop the run and name what is installed.
gate_die() { echo "gate: $*" >&2; exit 2; }
[ -x "$JAVA_HOME/bin/java" ] || gate_die "JAVA_HOME=$JAVA_HOME has no bin/java. Set JAVA_HOME to a JDK 17."
java_major="$("$JAVA_HOME/bin/java" -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -n1)"
[ "$java_major" = "17" ] || gate_die "JAVA_HOME=$JAVA_HOME is Java ${java_major:-unknown}; this gate is pinned to 17."
[ -d "$ANDROID_HOME" ] || gate_die "ANDROID_HOME=$ANDROID_HOME does not exist."
[ -d "$ANDROID_NDK_HOME" ] || gate_die "ANDROID_NDK_HOME=$ANDROID_NDK_HOME does not exist. Installed: $(ls "$ANDROID_HOME/ndk" 2>/dev/null | tr '\n' ' '). Set ANDROID_NDK_VERSION or ANDROID_NDK_HOME."
echo "gate: JAVA_HOME=$JAVA_HOME"
echo "gate: ANDROID_NDK_HOME=$ANDROID_NDK_HOME"

export GOBIN="${GOBIN:-$HOME/.local/bin}"
export GOMODCACHE="${GOMODCACHE:-$ROOT/.tools/cache/go-mod}"
export GOCACHE="${GOCACHE:-$ROOT/.tools/cache/go-build}"
export GOTOOLCHAIN="${GOTOOLCHAIN:-go1.26.4}"
export PATH="$JAVA_HOME/bin:$GOBIN:$PATH"

# Pinned to the gomobile the project is proven on (the Ebitengine fork).
GOMOBILE_VERSION=v0.0.0-20260820040257-d11f821a26a6

echo "== [0/4] fetch pinned runtime deps =="
bash scripts/fetch-runtime.sh

echo "== [1/4] go vet =="
go vet ./voice/... ./mobile/... ./tools/streamdump/...

echo "== [2/4] go test -race (offline) =="
# tools/streamdump replays testdata/stream/*.sse against the committed goldens
# (the Go half of the stream parity harness; StreamParityTest is the Kotlin half).
go test -race ./voice/... ./mobile/... ./tools/streamdump/...

echo "== [3/4] gomobile bind -> mobile.aar =="
command -v gomobile >/dev/null || go install "github.com/ebitengine/gomobile/cmd/gomobile@$GOMOBILE_VERSION"
command -v gobind >/dev/null || go install "github.com/ebitengine/gomobile/cmd/gobind@$GOMOBILE_VERSION"
gomobile init >/dev/null 2>&1 || true
# Reproducible bind: -trimpath + a canonical build path. See docs/REPRODUCIBLE.md.
bash scripts/bind-aar.sh "$ROOT/mobile.aar"

echo "== [4/4] gradle assembleDebug + unit tests =="
(cd android && ./gradlew --no-daemon -q clean assembleDebug testDebugUnitTest)
ls -la android/app/build/outputs/apk/debug/*.apk

echo "GATE-GREEN"
