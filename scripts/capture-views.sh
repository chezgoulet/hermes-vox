#!/bin/bash
# Hermes Vox — reusable screenshot-batch for UI passes.
#
# Captures the full 5-view inventory PLUS a pipeline-active Main turn, on the
# running emulator, into a timestamped output dir. Re-run after any UI change to
# re-shoot everything.
#
# Usage:  HERMES_VOX_API_KEY=<key> bash scripts/capture-views.sh [outdir]
#   (default outdir: $PWD/shots/<timestamp>)
# Requires: emulator-5554 online, the app installed, the entity gateway reachable.
set -uo pipefail
export PATH=/home/c/Android/Sdk/platform-tools:$PATH
A="adb -s emulator-5554"
OUT="${1:-$PWD/shots/$(date +%Y%m%d_%H%M%S)}"
mkdir -p "$OUT"
KEY="${HERMES_VOX_API_KEY:-${HV_KEY:-}}"
[ -z "$KEY" ] && { echo "set HERMES_VOX_API_KEY"; exit 1; }

cx(){  # resource-id -> center
  $A shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1 || true
  $A shell cat /sdcard/u.xml > /tmp/u.xml 2>/dev/null || true
  python3 - "$1" <<'PY'
import re,sys
k=sys.argv[1]
try: xml=open('/tmp/u.xml').read()
except Exception: print("0 0"); raise SystemExit
m=re.search(rf'<node[^>]*?resource-id="com\.hermesvox:id/{k}"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',xml)
if m: a,b,c,d=map(int,m.groups()); print(f"{(a+c)//2} {(b+d)//2}")
else: print("0 0")
PY
}
tap(){ C=$(cx "$1"); X=$(echo "$C"|cut -d' ' -f1); Y=$(echo "$C"|cut -d' ' -f2); [ "$X" != "0" ] && $A shell input tap $X $Y; sleep 2; }
shot(){ $A exec-out screencap -p > "$OUT/$1.png"; echo "  -> $OUT/$1.png"; }

seed_prefs(){
  cat > /tmp/hv.xml <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
  <string name="url">http://100.84.47.125:8642</string>
  <string name="model">hermes-agent</string>
  <string name="key">$KEY</string>
</map>
EOF
  $A push /tmp/hv.xml /data/local/tmp/hv.xml >/dev/null 2>&1
  $A shell run-as com.hermesvox mkdir -p shared_prefs
  $A shell run-as com.hermesvox cp /data/local/tmp/hv.xml shared_prefs/hv.xml
}

$A shell cmd statusbar collapse >/dev/null 2>&1; sleep 1

echo "=== 1/6 Onboarding (fresh install) ==="
$A shell am force-stop com.hermesvox; sleep 1
$A shell pm clear com.hermesvox >/dev/null 2>&1
$A shell cmd uimode night yes >/dev/null 2>&1
$A shell am start -n com.hermesvox/.MainActivity >/dev/null; sleep 4
shot 01_onboarding

echo "=== 2/6 Main (connected) ==="
seed_prefs
$A shell am force-stop com.hermesvox; sleep 1
$A shell am start -n com.hermesvox/.MainActivity >/dev/null; sleep 5
shot 02_main

echo "=== 3/6 Settings ==="
tap settings
shot 03_settings

echo "=== 4/6 Models ==="
tap row_models
shot 04_models
$A shell input keyevent 4; sleep 1; $A shell input keyevent 4; sleep 2

echo "=== 5/6 Realtime (hardened) ==="
tap realtime
shot 05_realtime
$A shell input keyevent 4; sleep 1

echo "=== 6/6 Pipeline-active Main (live SSE tool turn) ==="
$A shell am force-stop com.hermesvox; sleep 1
$A shell "am start -n com.hermesvox/.MainActivity --es url http://100.84.47.125:8642 --es key $KEY --es say 'Use your shell tool to run exactly: echo vox-ui-live and reply with just its output.'"
sleep 11
shot 06_pipeline_active
echo "DONE: shots in $OUT"
