# SPEC — Immediate silence on stop + voice-usage default OFF (0.3.32.2)

## Field evidence (2026-09-06 night log)
- Toggle `tts_voice_usage` OFF: loud volume restored AND barge-in still fired cleanly
  (rms 0.102/0.116, vad=true) — hardware AEC on the VOICE_COMMUNICATION capture is
  sufficient; voice-usage routing buys nothing on Pixel 9 and costs loudness.
- Barge decision->audio death lag: turn 2 ~2.5s, turn 3 ~4s (chunk at 12:09:16.176,
  1.3s AFTER stopStreaming at 14.855). endCall->silence: 8s (turn 4).

## Root cause (verified in SherpaTts.kt)
`stopStreaming()` nulls `streamTrack` as its "fence" — but `streamChunk()` treats a
null track as "first chunk" and BUILDS A NEW AudioTrack, so the still-running synth
worker (VoiceController stream worker loop) resurrects playback after every stop.
Additionally VoiceController's worker keeps iterating its queued-sentence loop after
stop, and the `finishStreaming()` playback-head wait delays gate release.

## Commits (gate per commit: :app:testDebugUnitTest exit 0; keys byte-match; never weaken tests)

### D1 — SherpaTts stopped-fence + VC one silence path
1. SherpaTts: add `@Volatile private var streamStopped = true`; `startStreaming()`
   sets it false; `stopStreaming()` sets it true BEFORE nulling the track;
   `streamChunk()` returns false immediately when `streamStopped` (no rebuild).
   `finishStreaming(timeout)` additionally aborts (returns fast) when streamStopped.
2. VoiceController: the stream worker loop must CHECK `genCancelled` (or equivalent
   sClosed) BEFORE each `streamChunk` call and break out (log
   `event=tts-stop gen=$gen worker-break chunkIdx=$i`); the in-flight blocking write
   returns on stop() by construction once D1.1's fence holds.
3. One `silenceAll(reason: String)` path used by bargeIn(), hush(), endCall/stop():
   stopTts() -> stopStreaming() -> cancelStream -> releaseTurnGate(gen, reason),
   called SYNCHRONOUSLY on the barge decision (bargeIn already runs on main; verify
   the gate release lands <150ms after the event=barge-in line — the 878ms lag was
   main-thread queueing behind TTS callbacks). Log `event=tts-stop reason=$reason
   msSinceBarge=$delta`.
4. Unit test (pure, no Android): SherpaTts fence logic is hard to JVM-test; extract
   the decision as `StreamFence` (tiny pure object: allowed to play iff started &&
   !stopped) + tests, and use it inside SherpaTts.

### D2 — default flip: tts_voice_usage true -> false
- SherpaTts.voiceUsage(): getBoolean("tts_voice_usage", FALSE); Settings bind def false;
  restoreDefaults(GROUP_MIC) putBoolean(..., false).
- Comment WHY: field A/B 2026-09-06 — OFF = loud volume AND working barge (single
  capture + double gate); ON = incall-quiet on Pixel. The toggle stays for leaky devices.

### D3 — version 0.3.32.2 (75) + docs/RELEASE-0.3.32.2.md
Notes: immediate silence on barge/hush/hangup (was up to 4s of audio after the cut);
echo-routing default OFF after field A/B (toggle remains); device checklist:
1) barge a long reply -> gate release + last-audio within ~150ms, `event=tts-stop`;
2) hang up mid-sentence -> silence same instant; 3) volume loud with default settings
(no manual toggle); 4) export log.

## Verification (Torc after loop)
Diff review of the fence ordering (stop must set streamStopped BEFORE touching the
track; the worker-break check BEFORE streamChunk; silenceAll call sites), test gate
independent re-run, build+release as usual.
