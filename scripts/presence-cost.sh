#!/usr/bin/env bash
# presence-cost.sh — what the on-device presence layer costs while the app is at rest.
#
# The question this measures: the presence model (Gemma 4 E2B, ~2.59 GB on disk) is loaded
# on every launch and never released while the app stays open. What does that cost, and what
# does each way of not paying it cost instead?
#
# It measures, on a connected phone and without building anything:
#
#   idle [SECONDS] [INTERVAL]  sample PSS / RSS / MemAvailable while the app sits idle with
#                              whatever presence state it is in — this is the "no idle release"
#                              evidence: a loaded engine stays resident for as long as you watch
#   snapshot                   one full `dumpsys meminfo` section table + the App Summary
#   coldstart N                N cold starts: `am force-stop`, settle, `am start -W`, then the
#                              app's OWN load line (`event=presence-load phase=end`) and the PSS
#                              once the load has finished
#   presence on|off            drive the one Settings switch (bounds read from a uiautomator
#                              dump, so it is not tied to this device's coordinates), then wait
#                              for the release the off switch arms
#   logs [PATTERN]             the presence lines from logcat (loads, unloads, arms, backend,
#                              hears, kv)
#   pref KEY [VALUE]           read or write one `hv` pref. Writing needs a debuggable build
#                              (`adb shell run-as`); it is how the flag-guarded arms in this
#                              script's companion doc are selected on a bench build.
#   arm NAME [N]               run one flag-guarded arm end to end: cold start + load + PSS.
#                              Known arms: default (today's behaviour), no-hearing, deferred,
#                              idle-release. Each is a pref the app reads at load.
#
# Every result is appended to presence-cost-results/*.jsonl AND printed as a row, so a figure
# in a document can be traced back to a command.
#
# Usage:
#   scripts/presence-cost.sh idle 180
#   scripts/presence-cost.sh coldstart 10
#   scripts/presence-cost.sh presence off
#   scripts/presence-cost.sh arm no-hearing 3
#
# The flag-guarded arms need a BENCH BUILD, because they are prefs and a release-signed APK
# refuses `run-as`. The bench build is a second, debug-signed install that sits NEXT TO the
# owner's app (its own package id, its own data dir, its own copy of the 2.5 GB model):
#
#   (cd android && ./gradlew assembleDebug -Phermesvox.benchSuffix=presencecost)
#   adb install -r android/app/build/outputs/apk/debug/hermes-vox-0.9.1.apk
#   # give it the model it looks for (files/models/gemma-e2b/gemma-4-E2B-it.litertlm):
#   adb push gemma-4-E2B-it.litertlm /data/local/tmp/
#   adb shell "run-as com.hermesvox.presencecost mkdir -p files/models/gemma-e2b"
#   adb shell "run-as com.hermesvox.presencecost sh -c \
#     'cat /data/local/tmp/gemma-4-E2B-it.litertlm > files/models/gemma-e2b/gemma-4-E2B-it.litertlm'"
#   adb shell "run-as com.hermesvox.presencecost sha256sum files/models/gemma-e2b/gemma-4-E2B-it.litertlm"
#   # and measure the arm on THAT package
#   PKG=com.hermesvox.presencecost scripts/presence-cost.sh arm no-hearing 10
#
# `arm` seeds a placeholder endpoint if the install has none, because a blank `url` routes the
# app to OnboardingActivity, where presence never loads and the arm would measure nothing.
# Its absolute numbers are a DEBUG build's and are not comparable with the release figures;
# the two arms measured against each other on the same bench build are.
#
# Env: ADB (default $ANDROID_HOME/platform-tools/adb), SERIAL, PKG (com.hermesvox),
#      BENCH_URL (seeded endpoint, default the documented gateway http://127.0.0.1:8642).
set -euo pipefail

ADB="${ADB:-${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb}"
SERIAL="${SERIAL:-}"
PKG="${PKG:-com.hermesvox}"
# The launch component. Empty means "ask the phone": a bench build installed with
# -Phermesvox.benchSuffix=… has package id com.hermesvox.<suffix> while its Activity class is
# still com.hermesvox.MainActivity, so `-n $PKG/.MainActivity` expands to a class that does not
# exist and `am start -W` prints no TotalTime. Resolve it instead of assuming it.
ACTIVITY="${ACTIVITY:-}"
TAG="${TAG:-HermesVox}"
OUT="${OUT:-$(cd "$(dirname "$0")/.." && pwd)/presence-cost-results}"

if [ -n "$SERIAL" ]; then ADB="$ADB -s $SERIAL"; fi

mkdir -p "$OUT"

say() { echo "[$(date +%H:%M:%S)] $*"; }
die() { echo "presence-cost: $*" >&2; exit 2; }

sh_cmd() { $ADB shell "$@" 2>/dev/null | tr -d '\r'; }

# --- the two memory numbers -------------------------------------------------------------
# PSS is the footprint the system charges to the process; MemAvailable is what the rest of
# the phone could still get. A release has to show up in both.
mem_pss()      { sh_cmd dumpsys meminfo "$PKG" | awk '/TOTAL PSS/{print $3; exit}'; }
mem_rss()      { sh_cmd dumpsys meminfo "$PKG" | awk '/TOTAL PSS/{print $6; exit}'; }
mem_avail()    { sh_cmd cat /proc/meminfo | awk '/MemAvailable/{print $2}'; }
# One named row from the meminfo section table (Native Heap, GL mtrack, EGL mtrack, ...).
mem_row()      { sh_cmd dumpsys meminfo "$PKG" | awk -v r="$1" '$1" "$2==r {print $3; exit}'; }

app_pid()      { sh_cmd pidof "$PKG" || true; }

# The launcher component, asked of the phone (see ACTIVITY above). Cached per run.
_comp=""
launch_component() {
  if [ -n "$_comp" ]; then echo "$_comp"; return 0; fi
  if [ -n "$ACTIVITY" ]; then
    case "$ACTIVITY" in .*) _comp="$PKG/$ACTIVITY" ;; *) _comp="$PKG/$ACTIVITY" ;; esac
  else
    _comp="$(sh_cmd cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$PKG" | tail -1)"
  fi
  [ -n "$_comp" ] || die "no launcher activity for $PKG (installed?)"
  echo "$_comp"
}

# --- logcat: the app's own account of a load ---------------------------------------------
# `event=presence-load phase=end ok=… ms=…` is emitted by MainActivity at the end of a load,
# and `GemmaExpress loaded: … backend=… hears=… kv=… (primed)` names the arm behind it.
load_lines()   { $ADB logcat -d -s "$TAG" 2>/dev/null | grep -E "event=presence-(load|unload)|GemmaExpress loaded|GemmaExpress warm-up" || true; }

wait_load_end() {
  # $1 = seconds to wait. Echoes the `ms=` of the load that ends in the window (or "none").
  local deadline=$(( $(date +%s) + ${1:-60} ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    local line
    line="$(load_lines | grep "phase=end" | tail -1 || true)"
    if [ -n "$line" ]; then
      echo "$line" | sed -n 's/.*ms=\([0-9]*\).*/\1/p'
      return 0
    fi
    sleep 1
  done
  echo "none"
}

# The arm the engine was actually built with, taken from the load's own line rather than from
# what the settings screen was asked to show.
load_arm() {
  local l
  l="$(load_lines | grep "GemmaExpress loaded" | tail -1 || true)"
  if [ -z "$l" ]; then echo "none"; return 0; fi
  echo "$l" | sed -n 's/.*backend=\([a-z]*\) hears=\([a-z]*\) kv=\([0-9]*\).*/backend=\1 hears=\2 kv=\3/p'
}

emit() { echo "$1" >> "$OUT/$2"; echo "$1"; }

# --- the UI switch, addressed by resource id so it is not device-specific ----------------
# SettingsActivity is not exported: it is reached the way a person reaches it, by the gear on
# the main screen. Bounds come from a uiautomator dump each time, never from a hardcoded tap.
ui_dump() {
  sh_cmd uiautomator dump /sdcard/presence-cost-ui.xml >/dev/null
  sh_cmd cat /sdcard/presence-cost-ui.xml
}

ui_bounds() {
  # $1 = resource-id suffix or exact text. Prints "x y" of the centre of the node.
  ui_dump | python3 -c '
import re,sys
want=sys.argv[1]
xml=sys.stdin.read()
for m in re.finditer(r"<node [^>]*>", xml):
    n=m.group(0)
    rid=re.search(r"resource-id=\"([^\"]*)\"",n)
    txt=re.search(r"text=\"([^\"]*)\"",n)
    b=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n)
    if not b: continue
    hit=(rid and rid.group(1).endswith(want)) or (txt and txt.group(1)==want)
    if hit:
        x1,y1,x2,y2=map(int,b.groups())
        print((x1+x2)//2,(y1+y2)//2)
        break
' "$1"
}

tap() { read -r x y <<<"$1"; sh_cmd input tap "$x" "$y"; }

presence_switch_on() {
  ui_dump | python3 -c '
import re,sys
xml=sys.stdin.read()
m=re.search(r"<node [^>]*resource-id=\"[^\"]*sw_presence_enabled\"[^>]*>",xml)
print("true" if m and "checked=\"true\"" in m.group(0) else "false")
'
}

open_presence_settings() {
  # Main screen -> Settings (the gear) -> the Presence group. Each step is conditional so a
  # half-navigated app converges instead of tapping blind.
  for _ in 1 2 3 4; do
    local b
    b="$(ui_bounds sw_presence_enabled || true)"
    [ -n "$b" ] && return 0
    b="$(ui_bounds row_grp_presence || true)"
    if [ -n "$b" ]; then tap "$b"; sleep 2; continue; fi
    b="$(ui_bounds "⚙" || true)"
    if [ -n "$b" ]; then tap "$b"; sleep 2; continue; fi
    # Something else is in front (a sheet, a dialog): back out one step and retry.
    sh_cmd input keyevent KEYCODE_BACK >/dev/null || true
    sleep 2
  done
  return 1
}

# --- subcommands -------------------------------------------------------------------------
cmd_snapshot() {
  sh_cmd dumpsys meminfo "$PKG" | sed -n '1,40p'
  say "pss=$(mem_pss) rss=$(mem_rss) availMb=$(mem_avail) pid=$(app_pid)"
}

cmd_idle() {
  local secs="${1:-120}" every="${2:-10}" end label
  end=$(( $(date +%s) + secs ))
  label="${3:-idle}"
  say "idle sampling for ${secs}s every ${every}s -> $OUT/idle-$label.jsonl"
  : > "$OUT/idle-$label.jsonl"
  while [ "$(date +%s)" -lt "$end" ]; do
    local pss rss avail t
    pss=$(mem_pss); rss=$(mem_rss); avail=$(mem_avail); t=$(date +%s)
    emit "{\"t\":$t,\"pss_kb\":$pss,\"rss_kb\":$rss,\"avail_mb\":$((avail/1024))}" "idle-$label.jsonl" >/dev/null
    printf '%s pss=%s kB availMb=%s\n' "$(date +%H:%M:%S)" "$pss" "$((avail/1024))"
    sleep "$every"
  done
  local first last
  first=$(head -1 "$OUT/idle-$label.jsonl" | sed -n 's/.*"pss_kb":\([0-9]*\).*/\1/p')
  last=$(tail -1 "$OUT/idle-$label.jsonl" | sed -n 's/.*"pss_kb":\([0-9]*\).*/\1/p')
  say "idle-$label: first=${first} kB last=${last} kB delta=$(( last - first )) kB over ${secs}s"
}

cmd_coldstart() {
  local n="${1:-5}" label="${2:-coldstart}" row
  say "$n cold starts -> $OUT/$label.jsonl"
  : > "$OUT/$label.jsonl"
  for i in $(seq 1 "$n"); do
    $ADB logcat -c 2>/dev/null || true
    sh_cmd am force-stop "$PKG" >/dev/null
    sleep 3
    local avail0; avail0="$(mem_avail)"
    # `cmd package resolve-activity` is the phone's answer, not ours: see ACTIVITY above.
    local comp; comp="$(launch_component)"
    # `-W` prints TotalTime/WaitTime: the system's own first-frame clock, the same one
    # docs/BUDGETS-0.9.1.md pins the 250 ms startup budget to.
    local out total wait this
    out="$(sh_cmd am start -W -n "$comp" || true)"
    total=$(echo "$out" | sed -n 's/^TotalTime: //p')
    wait=$(echo "$out" | sed -n 's/^WaitTime: //p')
    this=$(echo "$out" | sed -n 's/^ThisTime: //p')
    # A start that produced no TotalTime is not a 0 ms start: it did not launch. Say so here
    # instead of writing nulls into the JSONL (an empty arm-*.jsonl is what silence looks like).
    [ -n "$total" ] || die "am start -W printed no TotalTime for $comp: $(echo "$out" | tr '\n' ' ')"
    sleep 2
    local pss_start; pss_start="$(mem_pss)"
    local loadms arm avail1 pss_end
    # LOAD_WAIT_SECONDS: with Presence switched off there is no load to wait for, and waiting
    # the full 90 s per run would measure the script rather than the app.
    loadms="$(wait_load_end "${LOAD_WAIT_SECONDS:-90}")"
    arm="$(load_arm)"
    sleep 5
    pss_end="$(mem_pss)"; avail1="$(mem_avail)"
    row="{\"run\":$i,\"total_ms\":${total:-null},\"this_ms\":${this:-null},\"wait_ms\":${wait:-null},\"pss_at_start_kb\":${pss_start:-null},\"load_ms\":$([ "$loadms" = none ] && echo null || echo "$loadms"),\"arm\":\"$arm\",\"pss_loaded_kb\":${pss_end:-null},\"avail_before_mb\":$((avail0/1024)),\"avail_after_mb\":$((avail1/1024))}"
    echo "$row" >> "$OUT/$label.jsonl"
    printf '%s run=%s total=%sms load=%sms pss_start=%s kB pss_loaded=%s kB avail=%s->%s MB arm=%s\n' \
      "$(date +%H:%M:%S)" "$i" "${total:-?}" "$loadms" "$pss_start" "$pss_end" \
      "$((avail0/1024))" "$((avail1/1024))" "${arm:-none}"
    load_lines >> "$OUT/$label.log"
  done
  say "done: $OUT/$label.jsonl"
}

cmd_presence() {
  local want="${1:?on|off}"
  open_presence_settings || die "could not reach the Presence switch (is the app foregrounded?)"
  local state; state="$(presence_switch_on)"
  say "presence switch is $state; want $want"
  if [ "$state" != "$([ "$want" = on ] && echo true || echo false)" ]; then
    tap "$(ui_bounds sw_presence_enabled)"
    sleep 2
    say "now $(presence_switch_on)"
  fi
  sh_cmd input keyevent KEYCODE_BACK >/dev/null || true
  sleep 2
  # The off switch arms the shipped one-minute idle release (MemoryBudget.DELAY_DEFAULT).
  case "$want" in
    off)
      say "waiting for the armed release (up to 90s)"
      local deadline=$(( $(date +%s) + 90 ))
      while [ "$(date +%s)" -lt "$deadline" ]; do
        if load_lines | grep -q "presence-unload reason="; then break; fi
        sleep 3
      done
      ;;
  esac
  load_lines | tail -6
  say "pss=$(mem_pss) kB availMb=$(mem_avail)"
}

cmd_logs() {
  local pat="${1:-event=presence}"
  $ADB logcat -d -s "$TAG" 2>/dev/null | grep -E "$pat" || true
}

# --- prefs (bench builds) ----------------------------------------------------------------
# The three flag-guarded options are prefs read at engine load, so an arm is "set the pref,
# cold start, read the load line". Writing needs a debuggable build: a release-signed APK
# refuses `run-as`, which is why the flags ship off and the measurement of them needs a bench
# build. See docs/PRESENCE-COST.md.
cmd_pref() {
  local key="${1:?pref key}" val="${2:-}"
  # Relative on purpose: the runas_app SELinux domain is denied absolute paths into app data,
  # but its cwd IS the app's data dir, so `shared_prefs/hv.xml` is reachable and
  # `/data/user/0/<pkg>/shared_prefs/hv.xml` is not.
  local xml="shared_prefs/hv.xml"
  local tmp="$OUT/.hv.xml"
  if ! sh_cmd run-as "$PKG" true >/dev/null 2>&1; then
    die "$PKG is not debuggable (release-signed): prefs are read-only here. Use the bench build: android/gradlew assembleDebug -Phermesvox.benchSuffix=presencecost"
  fi
  if [ -z "$val" ]; then
    if sh_cmd run-as "$PKG" test -f "$xml"; then sh_cmd run-as "$PKG" cat "$xml"; else echo "(no $xml)"; fi
    return 0
  fi
  sh_cmd am force-stop "$PKG" >/dev/null
  rm -f "$tmp"
  if sh_cmd run-as "$PKG" test -f "$xml"; then sh_cmd run-as "$PKG" cat "$xml" > "$tmp"; fi
  python3 - "$key" "$val" "$tmp" <<'PY'
import re,sys
key,val,path=sys.argv[1],sys.argv[2],sys.argv[3]
try: xml=open(path).read()
except FileNotFoundError:
    xml="<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n"
# Android's SharedPreferences is an XmlPullParser over <map> whose CHILD ELEMENT NAME IS THE
# TYPE and whose attribute `name` is the key: <boolean name="presence_hears" value="false" />.
# Writing <presence_hears value="false" /> instead is silently ignored — the app then writes its
# own (nearly empty) map back over the file, so the pref looks written and never applies.
low=val.strip().lower()
if low in ("true","false"):
    entry=f'<boolean name="{key}" value="{low}" />'
elif re.fullmatch(r"-?\d+", val.strip()):
    entry=f'<int name="{key}" value="{val.strip()}" />'
else:
    entry=f'<string name="{key}">{val}</string>'
pat=re.compile(rf'<(boolean|int|long|float|string|set) name="{re.escape(key)}"[^>]*(/>|>.*?</\1>)', re.S)
if pat.search(xml):
    xml=pat.sub(lambda _m: entry, xml, count=1)
else:
    xml=xml.replace('</map>', '    '+entry+'\n</map>')
open(path,'w').write(xml)
PY
  sh_cmd run-as "$PKG" mkdir -p shared_prefs
  $ADB shell "run-as $PKG sh -c 'cat > $xml'" < "$tmp"
  rm -f "$tmp"
  say "pref $key=$val"
}

cmd_arm() {
  local arm="${1:?arm: default|no-hearing|deferred|idle-release}" n="${2:-3}"
  # The keys here are MemoryBudget's, not nicknames: a pref the app does not read is a
  # measurement of today's behaviour wearing another arm's name, and a silent
  # presence-cost-results/arm-*.jsonl is what that looks like in the record. So each arm is
  # written, then read back before anything is measured.
  local hears lazy idle
  case "$arm" in
    default)      hears=true;  lazy=false; idle=0 ;;
    no-hearing)   hears=false; lazy=false; idle=0 ;;
    deferred)     hears=true;  lazy=true;  idle=0 ;;
    idle-release) hears=true;  lazy=false; idle=2 ;;
    *) die "unknown arm: $arm" ;;
  esac
  cmd_pref presence_hears "$hears"
  cmd_pref presence_lazy_load "$lazy"
  cmd_pref presence_idle_open_min "$idle"
  local xml
  xml="$(sh_cmd run-as "$PKG" cat shared_prefs/hv.xml 2>/dev/null || true)"
  # Read the value back in the shape Android actually parses: element = type, `name` = key.
  # A check that greps for the writer's own shape only proves the writer agrees with itself.
  check_pref() { # KEY VALUE
    grep -qE "<(boolean|int|long|float) name=\"$1\" value=\"$2\"" <<<"$xml" \
      || die "arm $arm: pref $1 did not take (wanted $2). Prefs are read-only on a release build."
  }
  check_pref presence_hears "$hears"
  check_pref presence_lazy_load "$lazy"
  check_pref presence_idle_open_min "$idle"
  say "arm=$arm prefs confirmed in shared_prefs/hv.xml"
  # A fresh bench install has no stored endpoint, and MainActivity routes a blank `url` to
  # OnboardingActivity — where the presence layer never loads, so the arm would measure
  # nothing. Seed the documented gateway address once, on a debuggable (bench) install only.
  # This can only ever touch a package `run-as` could already reach: the read above would
  # have died first on a release-signed build.
  if ! grep -q 'name="url"' <<<"$xml"; then
    cmd_pref url "${BENCH_URL:-http://127.0.0.1:8642}"
    say "arm=$arm: no endpoint was stored, seeded BENCH_URL so the app does not route to onboarding"
  fi
  cmd_coldstart "$n" "arm-$arm"
}

case "${1:-}" in
  idle)      shift; cmd_idle "$@" ;;
  snapshot)  shift; cmd_snapshot "$@" ;;
  coldstart) shift; cmd_coldstart "$@" ;;
  presence)  shift; cmd_presence "$@" ;;
  logs)      shift; cmd_logs "$@" ;;
  pref)      shift; cmd_pref "$@" ;;
  arm)       shift; cmd_arm "$@" ;;
  *)
    sed -n '2,45p' "$0" | sed 's/^# \{0,1\}//'
    exit 2 ;;
esac
