# PLAN — Barge-in single-capture fix + VAD-extended endpointing + model-switch review

## Evidence (2026-09-05 field log, Pixel 9, 14 turns)
- Every playback watch arms `aec=false`: `startBargeInWatch` (VoiceController.kt:771-784) attaches
  `AcousticEchoCanceler.create(ecSession)` where `ecSession = (tts as? SherpaTts)?.playbackSession` —
  a PLAYBACK audio session. Effects attach to CAPTURE sessions → create() returns null → no AEC.
- playback-rms fires at 0.153–0.172 vs threshold 0.15 (speaker echo leaking), incl. a fire while
  `speaking=false` (VC:794 race — turn 11). Long replies cut by the app's own voice; queued piper
  chunks keep playing after gate release (duplicate-release lines).
- `if (uptimeMillis - segStart > maxMs) break` (VC:268, maxMs=15000) chopped the user's 13s
  utterances mid-sentence (turn 8: "…The app is." then a 1216ms "That" fragment).

## B1 — Single-capture barge-in (VoiceController.kt + SherpaTts.kt)
One AudioRecord for the whole loop. The main recorder `r` (VOICE_COMMUNICATION, platform AEC/NS,
hardware NoiseSuppressor attached VC:207-213) is currently STOPPED during turns (VC:318 r.stop())
and each watch opens a second recorder. Replace with a drain loop:

1. In `listenOffline` (VC:317-322), replace the blocking `latch.await(...)` with:
   while the gate is closed (poll `latch.await(40ms)`), keep `r` recording: read frames,
   compute rms + feed VAD, and route frames to a barge check when `speaking` (playback mode)
   or `turnInFlight && !speaking` (generation mode). Frames are DISCARDED (never appended to
   `seg`). On barge decision: `main.post { bargeIn() }` (which releases the latch → drain exits).
   Keep the TURN_GATE_TIMEOUT_MS ceiling and the existing no-gate-release log.
2. Pure decision object (new file `BargeGate.kt`, unit-testable, VoiceLoopState pattern):
   `decide(rms: Float, vadSpeech: Boolean?, sustainedMs: Long, rmsMin: Float, vadAvailable: Boolean): Boolean`
   — require sustained rms > rmsMin for >= 200ms AND vadSpeech==true when VAD available;
   no-VAD fallback: rms > rmsMin*1.4 sustained >= 350ms. Defaults: `barge_rms_min` 0.15f,
   grace `barge_grace_ms` 500 (skip checks until 500ms after `speaking` became true; generation
   mode exempt — nothing plays yet).
3. VAD sharing: the barge feed uses the same `SileroVadGate.vad` — MUST call its reset() (verify
   API; if absent, add one) at gate-close so barge-window state cannot corrupt next-turn
   segmentation. Acceptance: early/segmentation behavior unchanged on quiet turns.
4. Delete: `startBargeInWatch`, `armGenerationWatch`, `bargeRecord`, `bargeAec`, `genRecord`,
   the bogus `AcousticEchoCanceler.create(playbackSession)` call, and both second-recorder
   `event=barge-in-armed` lines. New single line per turn at gate close:
   `event=barge-watch mode=single-capture vad=<on|off> rmsMin=<val> gen=<n>`.
   Keep `event=barge-in source=...` firing lines: `event=barge-in source=single-capture mode=<playback|generation> rms=… vad=… gen=… speaking=…`.
5. `bargeIn()` guard: return early (log `event=barge-in ignored reason=state`) when
   `!turnInFlight && !speaking` (fixes the speaking=false race).
6. SherpaTts: route ALL playback AudioTracks (stream + one-shot paths — find both) through
   AudioAttributes(USAGE_VOICE_COMMUNICATION, CONTENT_TYPE_SPEECH), pref kill-switch
   `tts_voice_usage` (default true; false = current attributes). This gives the platform AEC a
   proper echo reference for the VOICE_COMMUNICATION capture. NOTE the old handoff lesson warned
   a MODE_IN_COMMUNICATION *global toggle* broke playback — usage attributes are NOT that
   toggle; the device test (Christopher) decides; kill-switch bounds the risk.

## B2 — VAD-extended endpointing (VoiceController.kt:268 + pure rule)
New pure object `EndpointRule.shouldStop(elapsedMs, silentMs, maxMs, hardMs): Boolean`:
stop when `elapsed >= maxMs && silentMs > 100` OR `elapsed >= hardMs`. Pref `vad_max_hard_ms`
default 60000. While extending, log once: `event=endpoint-extended ms=$elapsedMs`. Existing
normal silence-break (silentMs > silenceMs, default 800) unchanged; partial early-start worker
keeps running inside extended segments (snapshot already bounded to 6s tail).
Unit tests: rows (14000,0,15000,60000)=false; (15100,120,15000,60000)=true; (61000,0)=true;
(8000,900,15000,60000) handled by existing silence rule (EndpointRule not consulted < maxMs).

## B3 — Model-switch path: review + fix the chat connector gap
Verified path (responses/stream = the voice loop): MainActivity.kt:569-571 writes prefs
model+provider → connectFromPrefs (352-360) rebuilds HermesSession(u,k,model)+setProvider →
voice/responses.go buildBody (54-66) sends {"model", "provider"} → gateway honors
provider-qualified requests unconditionally (direct provider path; model alone would be ignored
without direct_model_requests — we send both, so we ride the honored path). Gateway
/api/model/options confirmed today: provider slug `deepseek`, models include `deepseek-v4-flash`.
GAP FOUND: voice/hermes.go (chat completions, the send-text path) has SetModel but NO provider
field in the body — a cleared-provider "model-only" request would be silently ignored by the
gateway. FIX: mirror responses.go — add provider to HermesClient, include in body when set,
wire from Kotlin session (same plumbing as setProvider on responses). Add Go unit test asserting
body includes provider when set, omits when empty (both connectors).
Acceptance (Christopher, next call, 0.3.32.0): /models → deepseek-v4-flash → label updates →
next turn `event=turn firstByte` small AND total turn latency ~2s class (vs mimo's 5s+); no 400.

## B4 — Version bump 0.3.32.0 (versionCode 73) + docs/RELEASE-0.3.32.md
Release notes + device-test checklist: (1) model switch to deepseek-v4-flash via /models,
(2) long reply + speak-over test: expect NO self-cut at rms~0.16 and `source=single-capture`
barge lines only on real speech, (3) 15-25s continuous utterance: expect
`event=endpoint-extended`, complete transcript, (4) remote STT toggle + fallback (still
untested), (5) log export.

## Commit order, gates, risks
B1 → B2 → B3 → B4. Gate per commit: `cd android && <ext gradle 8.12.1/JDK17> :app:testDebugUnitTest`
exit 0; never weaken existing tests. Risks: platform AEC still insufficient on Tensor G4
(mitigations: grace window + double-gate + `tts_voice_usage`/`barge_rms_min` prefs); VAD-state
bleed (reset() at gate close + quiet-turn regression check); drain-loop vs stop()/release races
(bounded reads, existing exception guards, `bargeInEnabled=false` path must still drain r to
avoid buffer flood).
