# SPEC — 0.4.0.1: barge near-miss telemetry + log-honesty fixes

## Field evidence (2026-09-06 20:03 log, 0.4.0)
Silence 3-9ms, retirement clean, focus/route symmetric, provenance + session_turns
working. NEW DEFECT: barge-in goes DEAF during long playback tails — turns 1/2/4/6
barge instantly; turns 5 (17s tail) and 7 (44s tail) logged ZERO barge-in while the
user spoke. Failed tails = piper synth + giant streamChunk writes (157,952 smp)
under heavy trackLock/CPU. Three candidate causes, INDISTINGUISHABLE from absence
of events (that's the meta-bug):
  A) mic drain starved by synth/write burst (reads stop -> detection can't run)
  B) skip branch (!spk && !turnInFlight) with speaking state edge cases
  C) user level genuinely < floor (unlikely — same voice fired elsewhere)
Rule: measure before fixing (no defensive change without data).

## T1 — Near-miss telemetry in the drain loop (VoiceController, dd channel)
Track, per turn, while gate armed & !bargeFired:
  - lastReadAtMs: timestamp of each r.read() return; on gap > 500ms log
    dd "event=barge-gap gen= lastReadMs=$gap speaking=$spk" (CAUSE A probe)
  - nearMiss: level>floor sustainedMs accumulating but BargeGate.decide false, OR
    level rising >= floor*0.7 with vad true; emit at most one
    dd "event=barge-nearmiss gen= peakRms= vad= sustainedMs= floor=" per
    2s window while speaking (CAUSE C probe + threshold calibration data)
  - the skip branch: log ONCE per turn when it's active for >3s while
    speaking==true: dd "event=barge-skipcheck gen= why=state" (CAUSE B probe)
All dd-gated (debug channel), zero behavior change, zero hot-path cost beyond
arithmetic already done (level, sustainedMs exist per frame).

## T2 — gate-timeout warn: measure from last activity, log both
The 60s warn stamps after= from turn CLOSE (gateClosedAt), so legitimate long
turns (43s model stall + 44s audio) warn falsely. Add sinceActivity= computed from
a lastActivityAt updated at each delta/chunk/read; keep warn but only when
sinceActivity > 30s (a genuinely stalled gate) — field 'after=' kept for history.

## T3 — phantom turn on hangup (event=turn outcome=reply-error with all '-' fields)
endCall on a mid-generation turn: turn 8 logged a full event=turn (session_turns
8->next) for a turn the user never heard, AFTER the stop-outcome turn. The stream
abort's reply-error settle must not push a second event=turn for a gen that
already settled with outcome=stop. Guard in the settle emission: skip if
turnSettledFor(gen) already logged (the duplicate-release guard concept extended
to the turn-log line). session_turns stays counting real completed or interrupted
turns only.

## T4 — version 0.4.0.1 (80) + docs/RELEASE-0.4.0.1.md
Notes: telemetry release (near-miss/gap/skip probes), gate-timeout truth, phantom
turn fixed; device test = talk over a LONG reply repeatedly until the export shows
barge-nearmiss/gap/skipcheck lines that explain a missed barge.

## Rules
- NO behavior changes to barge threshold, endpointing, silence, retirement, focus.
- Gate: /home/c/gradle-8.12.1/bin/gradle :app:testDebugUnitTest -q exit 0.
- One commit 'telemetry: barge near-miss + mic-gap probes; gate-timeout activity
  measure; no phantom turn on hangup — 0.4.0.1'
- Print D_DONE <hash> + PROBE_SITES <file:line list> + SETTLE_GUARD <how>.
