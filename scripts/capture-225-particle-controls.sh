#!/usr/bin/env bash
# #225 — the Being section's five look controls, at their defaults and at each extreme,
# in light and dark.
#
# Real device captures, not mocks: each PNG is the Settings screen's LIVE PREVIEW — the
# same AvatarView the call screen draws, from the same applyLookPrefs() reader — with the
# pref seeded to the value the shot names. The shot is taken with the fine-look panel
# open and scrolled to the control being changed, so the control's current value, its
# range and its slider are in the frame under the being it changes.
#
# Usage:
#   scripts/capture-225-particle-controls.sh [outdir] [package]
#     outdir   default artifacts/225-particle-controls
#     package  default com.hermesvox.shot (a side-by-side debug install; see the header of
#              the #275 capture script for why a release-signed nightly cannot be replaced
#              by a debug build with `adb install -r`)
#
# Requires: adb, python3 with Pillow (the frame check), one unlocked device, the shot build
# installed and debuggable. The debug harness (MainActivity.handleDebugHarness +
# SettingsActivity.applyDebugHarness) opens the section and the panel, holds the preview on
# one state and scrolls — so no tap is needed and every run is identical. Nothing about a
# release build changes: both harnesses return immediately unless the APK is debuggable.
#
# Shot list, per ground: `<ground>-top` (the section at the shipped look, preview in frame)
# then, for each of the five controls, `<ground>-<control>-default|min|max` scrolled so the
# control's row sits at the bottom of the screen — its value line, its range, its slider
# and the being above it. 32 PNGs in all.
set -euo pipefail
export PATH="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools:$PATH"

OUT="${1:-artifacts/225-particle-controls}"
PKG="${2:-com.hermesvox.shot}"
SETTLE="${SETTLE:-6}"

# The five controls #225 names, and the bounds VisualStyle gives them.
CONTROLS=(density speed trail hue energy)
declare -A MIN=( [density]=0.3  [speed]=0.5  [trail]=0.0  [hue]=-180 [energy]=0.5 )
declare -A MAX=( [density]=1.0  [speed]=1.8  [trail]=0.9  [hue]=180  [energy]=1.6 )
declare -A ROW=( [density]=row_visual_density [speed]=row_visual_speed [trail]=row_visual_trail
                 [hue]=row_visual_hue [energy]=row_visual_energy )

mkdir -p "$OUT"
command -v adb >/dev/null || { echo "capture: adb not found"; exit 2; }
[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] || { echo "capture: no booted device"; exit 2; }

DEVICE_MODEL="$(adb shell getprop ro.product.model | tr -d '\r')"
DEVICE_BUILD="$(adb shell getprop ro.build.fingerprint | tr -d '\r')"

# ensure_app: the shot build is installed and has a data directory.
#
# A shared emulator can be restored to a snapshot between shots — the package disappears
# mid-run. When that happens, reinstall from $APK (the shot build) instead of aborting the
# whole pass; on a phone this is a no-op after the first check.
ensure_app() {
  adb shell run-as "$PKG" true >/dev/null 2>&1 && return 0
  if [ -n "${APK:-}" ] && [ -f "$APK" ]; then
    echo "  (reinstalling $PKG from $APK)"
    adb install -r -g "$APK" >/dev/null 2>&1 \
      || { adb uninstall "$PKG" >/dev/null 2>&1 || true; adb install -g "$APK" >/dev/null 2>&1 || true; }
    adb shell am start -n "$PKG/com.hermesvox.MainActivity" >/dev/null 2>&1 || true
    sleep 4
  fi
  adb shell run-as "$PKG" true >/dev/null 2>&1
}

# A freshly installed app has no data directory yet, and run-as needs one: open it once.
ensure_app || { echo "capture: $PKG is not installed/debuggable (set APK=<shot.apk> to install it)"; exit 2; }

# seed GROUND [KEY VALUE]: write the app's prefs for one shot. Every run starts from the
# shipped defaults, so an extreme is the ONLY thing that differs between two shots.
seed() {
  local ground="$1" key="${2:-}" value="${3:-}"
  ensure_app || { echo "capture: $PKG is not installed and no \$APK to restore it" >&2; exit 2; }
  local d=1.0 s=1.0 t=0.0 h=0.0 e=1.0
  case "$key" in
    density) d="$value" ;;
    speed)   s="$value" ;;
    trail)   t="$value" ;;
    hue)     h="$value" ;;
    energy)  e="$value" ;;
  esac
  cat > /tmp/hv-225-prefs.xml <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
  <string name="theme">$ground</string>
  <boolean name="onboarding_skipped" value="true" />
  <boolean name="coach_marks_seen" value="true" />
  <boolean name="presence_enabled" value="false" />
  <string name="visual_category">lumen</string>
  <boolean name="visual_cycle_all" value="false" />
  <float name="visual_density" value="$d" />
  <float name="visual_speed" value="$s" />
  <float name="visual_trail" value="$t" />
  <float name="visual_hue" value="$h" />
  <float name="visual_energy" value="$e" />
  <float name="visual_glow" value="1.0" />
</map>
EOF
  adb push /tmp/hv-225-prefs.xml /data/local/tmp/hv-225-prefs.xml >/dev/null
  adb shell run-as "$PKG" mkdir -p shared_prefs
  adb shell run-as "$PKG" cp /data/local/tmp/hv-225-prefs.xml shared_prefs/hv.xml
}

# shoot FILE WANT_BEING GROUND: screencap, retried until the frame is real.
#
# Three checks decide whether a frame is worth keeping: the app's own page colour must be in
# the margin (a launcher wallpaper or a stale frame fails), the frame must not be mostly
# empty (a black 1080 × 2400 PNG compresses to ~15 kB; a page of text is ~180 kB), and — for
# a shot of the section's top — the preview band must contain the being's ink, because
# software GL sometimes presents the rows before the 60 fps being. On the phone all three
# pass on the first frame. `adb exec-out` hands back a stale buffer on a software-GL
# emulator, so the frame is taken through the shell service and pulled.
shoot() {
  local file="$1" want_being="$2" ground="$3" i size ok
  for i in 1 2 3 4 5 6; do
    adb shell screencap -p /sdcard/hv225.png >/dev/null 2>&1
    adb pull /sdcard/hv225.png "$file" >/dev/null 2>&1
    size=$(stat -c%s "$file" 2>/dev/null || echo 0)
    read -r ok ink < <(python3 - "$file" "$ground" <<'PY' 2>/dev/null || echo "0 0"
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB")
w, h = im.size
margin = im.crop((0, int(h * 0.35), max(1, w // 12), int(h * 0.50)))   # page, left margin
mr = sum(p[0] for p in margin.getdata()) / (margin.width * margin.height)
mg = sum(p[1] for p in margin.getdata()) / (margin.width * margin.height)
mb = sum(p[2] for p in margin.getdata()) / (margin.width * margin.height)
paper = mr > 200 and mg > 200 and mb > 200      # @color/hv_bg  #F3F5F9
dark = mr < 60 and mg < 60 and mb < 60          # the night ground
band = im.crop((0, int(h * 0.10), w, int(h * 0.30)))                   # the preview's own band
ink = sum(1 for r, g, b in band.getdata() if abs(r - 243) + abs(g - 245) + abs(b - 249) > 60)
ok = (paper if sys.argv[2] == "light" else dark)
print(1 if ok else 0, ink)
PY
)
    if [ "$size" -gt 100000 ] && [ "${ok:-0}" = 1 ] && { [ "$want_being" = 0 ] || [ "${ink:-0}" -gt 15000 ]; }; then
      return 0
    fi
    sleep 3
  done
  echo "  ! $file is not a real frame after $i tries (size=$size paper=$ok ink=${ink:-0})" >&2
}

# wait_for_settings: the app's own Settings screen is the resumed one. After a snapshot
# restore the app takes a few seconds to come up, and a shot taken too early is the launcher.
wait_for_settings() {
  local i
  for i in $(seq 1 20); do
    adb shell dumpsys activity activities 2>/dev/null \
      | grep -q "topResumedActivity.*$PKG/com.hermesvox.SettingsActivity" && return 0
    sleep 2
  done
  return 1
}

# capture GROUND NAME [ROW]: open Being with the fine-look panel, hold the preview on
# "working" (the sparsest, most legible state for density/trail/hue), scroll to ROW, shoot.
capture() {
  local ground="$1" name="$2" row="${3:-}"
  adb shell cmd uimode night "$([ "$ground" = dark ] && echo yes || echo no)" >/dev/null 2>&1 || true
  adb shell am force-stop "$PKG"
  # MainActivity is the exported entry; its debug harness opens Settings and finishes
  # itself, so exactly one being exists in the process that is being photographed.
  local args=(--es settings visuals --es being_advanced true --es preview_state thinking)
  [ -n "$row" ] && args+=(--es scroll_to "$row")
  adb shell am start -n "$PKG/com.hermesvox.MainActivity" "${args[@]}" >/dev/null
  wait_for_settings || echo "  ! Settings did not come up for $name" >&2
  sleep "$SETTLE"
  # The top shot is the one that must show the being; a scrolled shot's band is rows.
  if [ -n "$row" ]; then shoot "$OUT/$name.png" 0 "$ground"; else shoot "$OUT/$name.png" 1 "$ground"; fi
  echo "  -> $OUT/$name.png"
}

echo "capture: $DEVICE_MODEL ($PKG) -> $OUT"
for ground in light dark; do
  # The section itself at the shipped look: preview + the panel's first rows.
  seed "$ground"
  capture "$ground" "$ground-top"
  for c in "${CONTROLS[@]}"; do
    for level in default min max; do
      case "$level" in
        default) seed "$ground" ;;
        min)     seed "$ground" "$c" "${MIN[$c]}" ;;
        max)     seed "$ground" "$c" "${MAX[$c]}" ;;
      esac
      capture "$ground" "$ground-$c-$level" "${ROW[$c]}"
    done
  done
done

# Leave the phone on the shipped look, not on the last extreme.
seed light
echo "capture: done — $(ls "$OUT"/*.png | wc -l) PNGs in $OUT ($DEVICE_BUILD)"
