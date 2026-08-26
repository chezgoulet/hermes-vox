#!/usr/bin/env bash
# Hermes Vox — release build helper (run on the Thelio, from the repo root).
# Builds android/app release APK into android/app/build/outputs/apk/release/.
set -euo pipefail

export ANDROID_HOME=/home/c/Android/Sdk
export ANDROID_SDK_ROOT=/home/c/Android/Sdk
export JAVA_HOME=/home/c/jdk-17.0.12+7
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
export GOTOOLCHAIN=go1.26.4
export GOMODCACHE=/home/c/hermes-vox/.tools/cache/go-mod
export GOCACHE=/home/c/hermes-vox/.tools/cache/go-build

GRADLE=/home/c/.gradle/wrapper/dists/gradle-8.12.1-bin/eumc4uhoysa37zql93vfjkxy0/gradle-8.12.1/bin/gradle
APP=/home/c/hermes-vox/android

cd "$APP"
echo "=== building release APK from $APP ==="
"$GRADLE" --no-daemon assembleRelease "$@"
