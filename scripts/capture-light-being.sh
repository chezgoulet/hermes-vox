#!/usr/bin/env bash
# #275 — capture the being on the light ground, state by state.
#
# A real device capture, not a mock: this drives the installed DEBUG build through the
# debug-only harness extras `category` and `being` (MainActivity.handleDebugHarness), so
# each PNG is the GPU being rendered by the real renderer on the phone's own screen.
# The harness also writes the state/family into the status pill, so every shot is
# self-labelling. No gateway, key, mic or model is needed.
#
# Usage:
#   scripts/capture-light-being.sh [outdir] [package]
#     outdir   default artifacts/275-light-mode
#     package  default com.hermesvox.shot (a side-by-side debug install; see the header
#              of the PR: a release-signed nightly cannot be replaced by a debug build
#              with `adb install -r`, so the shot build uses its own application id and
#              leaves the owner's installed app and data untouched)
#
# Requires: adb, one device, the shot build installed and debuggable.
set -euo pipefail
export PATH="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools:$PATH"

OUT="${1:-artifacts/275-light-mode}"
PKG="${2:-com.hermesvox.shot}"
FAMILIES=(lumen abyss nebula ink)
STATES=(listening thinking speaking)
SETTLE=4

mkdir -p "$OUT"
command -v adb >/dev/null || { echo "capture: adb not found"; exit 2; }
[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] || { echo "capture: no booted device"; exit 2; }
adb shell run-as "$PKG" true >/dev/null 2>&1 || { echo "capture: $PKG is not installed/debuggable"; exit 2; }

seed() {
  local cat="$1"
  cat > /tmp/hv-shot-prefs.xml <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
  <string name="theme">light</string>
  <boolean name="onboarding_skipped" value="true" />
  <boolean name="coach_marks_seen" value="true" />
  <string name="visual_category">$cat</string>
  <boolean name="visual_cycle_all" value="false" />
</map>
EOF
  adb push /tmp/hv-shot-prefs.xml /data/local/tmp/hv-shot-prefs.xml >/dev/null
  adb shell run-as "$PKG" mkdir -p shared_prefs
  adb shell run-as "$PKG" cp /data/local/tmp/hv-shot-prefs.xml shared_prefs/hv.xml
}

launch() {
  local cat="$1" being="$2"
  seed "$cat"
  adb shell cmd uimode night no >/dev/null 2>&1 || true
  adb shell am force-stop "$PKG"
  adb shell am start -n "$PKG/com.hermesvox.MainActivity" \
    --es category "$cat" --es being "$being" >/dev/null
  sleep "$SETTLE"
}

shot() {
  adb exec-out screencap -p > "$OUT/$1.png"
  echo "  -> $OUT/$1.png"
}

echo "capture: $(adb shell getprop ro.product.model | tr -d '\r') / $PKG"
for fam in "${FAMILIES[@]}"; do
  for st in "${STATES[@]}"; do
    launch "$fam" "$st"
    shot "$fam-$st"
  done
done
# The stalled state: the being holds the waiting (hourglass) shape, which is the state
# a long tool call actually shows. One family is enough to prove the ground, but the
# shot is labelled with the same family its siblings use.
launch abyss stalled
shot abyss-stalled
echo "capture: done — $(ls "$OUT"/*.png | wc -l) PNGs in $OUT"
