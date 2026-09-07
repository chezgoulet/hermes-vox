# SPEC — 0.5.0-previewB: state-driven presence motion (Christopher-approved vocabulary)

## Why
Christopher (2026-09-07, APPROVED): "way more animations and they should be way more
interesting and they should be way more dynamic depending on the work being done."
The being must *show its state through its motion*, not loop a screensaver.
Vocabulary blessed as written. Motion feel = rich/energetic default, sweepable via
drive-param (not a rewrite). This is the presence layer: a provider stall becomes
visible concentration, not dead air. Pairs with the 0.5.0-A speech-locked release.

## Principle new shapes keyed to real work signals
The AvatarView ALREADY has `SHAPES` (iris/listening/vortex/scan/bracket/
constellation/lumen/waveform/bloom), `setState`, `setStateLevel`, `onTool`,
`pulseTool`, `setWorking` and a seeded-per-call generative system. This adds the
**state-driven motion vocabulary** on top:
  - listening → **breathing** (slow, low-amp, receptive field)
  - thinking → **gathering** (particles draw inward, brighten, orbit)
  - speaking → **voice-coupled pulse** (existing waveform, but amplitude LOCKED to
    the REAL AudioTrack RMS level — dances with the voice, not a placeholder)
  - stream-stall (provider latency) → **waiting constellation** (particles hold a
    drifting pattern — unmistakably "still here, still working")
  - barge-in / hush → **recoil** (fast, respectful — it heard you)
  - tool-call → **tool motif** (existing `onTool` geometry: grid/wisp/sweep)
  - tool-result → **satisfied shimmer** (existing `pulseTool`)
  - natural retire (tts-retire) → **settle back to breathing**
  - low-workload idle → gentle **drift** (never frozen, never busy)

## M1 — MotionState (pure JVM, transition table like BargeGate/ReplySettleRule)
OBJECT `MotionState` in its own file, no Android deps:
  - `enum Motion` = LISTENING, THINKING, SPEAKING, STALL, RECOIL, TOOL, TOOL_RESULT,
    SETTLE, IDLE, DRIFT.
  - `fun transition(current: Motion, signal: Signal): Motion` — a pure table
    (signal = the live state/cursor/stall/tool/amp distilled to a small enum).
    Unit-testable: every signal → expected next motion; no invalid transitions;
    STALL overrides THINKING/SPEAKING (the presence lever precedence); RECOIL is
    one-shot (returns to prior motion after ~350ms, tracked via a staysUntil in the
    caller, not in the pure table).
  - `fun renderParams(m: Motion, workload: Float, amp: Float): Params` — maps a
    motion to the drive params (sphere radius, particle speed, brightness, color
    theme, orbit bias). One source of truth so the animation code never hardcodes a
    shape per state.

## M2 — AvatarView wiring (the scheduler)
- Add `fun applyMotion(m: Motion, amp: Float, workload: Float, tool: String?)` that
  calls the existing setters (setState, setStateLevel, onTool for TOOL, setWorking,
  pulseTool for TOOL_RESULT) — NO new parallel animation loop; drive the existing
  per-frame generative render. `params` from MotionState.renderParams drive the
  active shape/theme.
- STALL: bind to the real stall signal — add `fun setStall(stalled: Boolean, ms: Long)`
  called from MainActivity when `stream-stall`/idleMs>stallThreshold; the being
  shifts to the waiting-constellation while stalled, back to the prior motion on
  resume. Use the EXISTING `stream-stall` event in the log pipeline.
- SPEAKING amp: MainActivity already feeds `amp` from the speak-level; ensure it's
  the real AudioTrack RMS level when available (not a placeholder).
- RECOIL: triggered on barge/hush (existing cancel path); one-shot, returns to prior.

## M3 — MainActivity: feed the signals
In the listener (`onState`, `onDelta`, `onLog`, plus a stall watcher):
  - map state → Motion via MotionState (listening/thinking/speaking).
  - on `stream-stall` event OR `idleMs>=STALL_MS` → avatar.setStall(true); on resume
    → setStall(false). (This is the presence-with-latency win.)
  - on barge/hush/stop → avatar.applyMotion(RECOIL,...) then settle back.
  - keep existing `avatar.onTool`/`pulseTool` (already wired in onLog).
- VersionCode 85 / versionName "0.5.0-previewB" + docs/RELEASE-0.5.0-previewB.md.

## KEEP-LIST (zero-touch)
SpeechCursor/PlaybackClock reveal loop (0.5.0-A), silenceAll, fence, pause-first
teardown, StreamRetirementState, focus, BargeGate/EscapeRule, ReplySettleRule,
session_turns, CrawlView role logic, transcript composition. This is additive to
the *avatar's motion only*. AvatarView existing setters stay call-compatible.

## Blast-radius
Start with the MotionState machine + wiring + 3-4 NEW shapes (not 20); prove
in-device, then expand. Rich/energetic default; drive-param sweep toward minimal if
the first device pass reads as too busy.

## Gate
cd android && JAVA_HOME=/home/c/jdk-17.0.12+7 /home/c/gradle-8.12.1/bin/gradle
:app:testDebugUnitTest -q --no-daemon exit 0. Build requires android/local.properties
(sdk.dir=/home/c/Android/Sdk) + libs from /home/c/hermes-vox/android/app/libs if
missing in the clone.

## Deliverable — one commit, print
DIAGNOSIS (which states/motions already exist vs new; any naming collision)
FIX (MotionState table + AvatarView wiring + MainActivity signal feed)
MOTION_TABLE (the transition table with the STALL precedence + RECOIL one-shot rule)
TEST_TABLE (MotionState rows)
RISK (frame cost of more shapes, stall-detection false positives, amp-lock accuracy)
