#!/bin/bash
# Hermes Vox — full build/verify gate (handoff §10).
# One command: Go gates -> gomobile bind -> STAGE AAR -> Gradle assembleDebug.
# The staging step is load-bearing: Gradle consumes app/libs/mobile.aar (a
# COPY); skipping it builds against a stale bind silently ("up-to-date" lies).
# NOTE (bind-strip): the old Ebitengine js-wasm/cmd-app build path is GONE.
# Hermes Vox is 100% Android-native now; the mobile bind only exports the
# HermesSession voice logic.
set -euo pipefail
cd /home/c/hermes-vox

export JAVA_HOME=/home/c/jdk-17.0.12+7
export ANDROID_HOME=/home/c/Android/Sdk
export ANDROID_NDK_HOME=/home/c/Android/Sdk/ndk/25.2.9519653   # literal path — $ANDROID_HOME expansion inside the same export is empty
export GOBIN=/home/c/.local/bin
export GOMODCACHE=/home/c/hermes-vox/.tools/cache/go-mod
export GOCACHE=/home/c/hermes-vox/.tools/cache/go-build
export GOTOOLCHAIN=go1.26.4
export GOMOBIN=$GOBIN
export PATH=$JAVA_HOME/bin:$GOBIN:$PATH

echo "== [0/5] fetch pinned runtime deps =="
bash scripts/fetch-runtime.sh

echo "== [1/5] go vet =="
go vet ./voice/...

echo "== [2/5] go test -race (offline) =="
go test -race ./voice/...

echo "== [3/5] gomobile bind -> mobile.aar =="
gomobile init >/dev/null 2>&1 || true
gomobile bind -target android -androidapi 23 -javapkg com.hermesvox \
  -o mobile.aar github.com/chezgoulet/hermes-vox/mobile

echo "== [4/5] stage AAR into android/app/libs (load-bearing) =="
mkdir -p android/app/libs
cp -f mobile.aar android/app/libs/mobile.aar
ls -la android/app/libs/mobile.aar

echo "== [5/5] gradle assembleDebug =="
GRADLE=/home/c/.gradle/wrapper/dists/gradle-8.12.1-bin/eumc4uhoysa37zql93vfjkxy0/gradle-8.12.1/bin/gradle
(cd android && "$GRADLE" --no-daemon clean assembleDebug -q)
ls -la android/app/build/outputs/apk/debug/app-debug.apk

echo "GATE-GREEN"
