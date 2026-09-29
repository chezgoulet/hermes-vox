#!/usr/bin/env bash
# Hermes Vox — full build/verify gate (handoff §10).
# One command: Go gates -> gomobile bind -> STAGE AAR -> Gradle assembleDebug +
# unit tests. The staging step is load-bearing: Gradle consumes
# app/libs/mobile.aar (a COPY); skipping it builds against a stale bind silently
# ("up-to-date" lies).
#
# Portable: runs from any checkout. Toolchain locations come from the
# environment (JAVA_HOME, ANDROID_HOME, ANDROID_NDK_HOME); the defaults below
# are the maintainer's build host and only apply when a variable is unset.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-$HOME/jdk-17.0.12+7}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/25.2.9519653}"
export GOBIN="${GOBIN:-$HOME/.local/bin}"
export GOMODCACHE="${GOMODCACHE:-$ROOT/.tools/cache/go-mod}"
export GOCACHE="${GOCACHE:-$ROOT/.tools/cache/go-build}"
export GOTOOLCHAIN="${GOTOOLCHAIN:-go1.26.4}"
export PATH="$JAVA_HOME/bin:$GOBIN:$PATH"

# Pinned to the gomobile the project is proven on (the Ebitengine fork).
GOMOBILE_VERSION=v0.0.0-20260820040257-d11f821a26a6

echo "== [0/5] fetch pinned runtime deps =="
bash scripts/fetch-runtime.sh

echo "== [1/5] go vet =="
go vet ./voice/... ./mobile/...

echo "== [2/5] go test -race (offline) =="
go test -race ./voice/... ./mobile/...

echo "== [3/5] gomobile bind -> mobile.aar =="
command -v gomobile >/dev/null || go install "github.com/ebitengine/gomobile/cmd/gomobile@$GOMOBILE_VERSION"
gomobile init >/dev/null 2>&1 || true
gomobile bind -target android -androidapi 23 -javapkg com.hermesvox \
  -o mobile.aar github.com/chezgoulet/hermes-vox/mobile

echo "== [4/5] stage AAR into android/app/libs (load-bearing) =="
mkdir -p android/app/libs
cp -f mobile.aar android/app/libs/mobile.aar
ls -la android/app/libs/mobile.aar

echo "== [5/5] gradle assembleDebug + unit tests =="
(cd android && ./gradlew --no-daemon -q clean assembleDebug testDebugUnitTest)
ls -la android/app/build/outputs/apk/debug/*.apk

echo "GATE-GREEN"
