#!/usr/bin/env bash
# Hermes Vox — release build helper (from any checkout).
# Builds the signed release APK into android/app/build/outputs/apk/release/.
# Needs keystore/keystore.properties (see docs/PLAY-APP-SIGNING.md) and the same
# inputs as scripts/gate.sh (run the gate first: it binds mobile.aar and fetches
# the verified sherpa-onnx runtime). Toolchain paths come from the environment.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="${JAVA_HOME:?set JAVA_HOME to a JDK 17}"
export ANDROID_HOME="${ANDROID_HOME:?set ANDROID_HOME to your Android SDK}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

cd "$ROOT/android"
echo "=== building release APK from $PWD ==="
./gradlew --no-daemon assembleRelease "$@"
