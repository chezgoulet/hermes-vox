# Hermes Vox — Sprint 4 Plan Brief: Stability + Voice-Quality (fix the deferred items first)

**Repo:** chezgoulet/hermes-vox · **Work clone:** /home/c/hermes-vox (Thelio) · **Branch:** feature/040-gates
**Plan + build model:** `deepseek/deepseek-v4-flash-vision-exp` (DeepSeek API) · **Owner/approver:** Torc
**Review gate:** Torc adversarial read + compile + emulator (`emulator-5554` via adb/uiautomator/screencap) BEFORE shipping, + on-device for audio paths.

## Sprint theme
**"Stabilize + make it feel alive — close the deferred items before the 0.4.0 gates."** Per Christopher (2026-08-28): **punt Enhanced Realtime (Gemma) — it has never been run once and is a first-ever experiment** — until the deferred items are fixed. This sprint takes the deferred voice-quality + hardening + polish items (streaming/partial STT, latency instrumentation, speech enhancement, crash-hardening #7/#8, voice-loop polish) as the core, plus the first-run blocker **#61**. Google Play is explicitly out (external passport/identity blocker).

## Context / anchor points (verified, not assumed)
- **Phone-call flow is green** (0.3.24.3, on-device): silero VAD + Piper + whisper-base load + run; multi-turn + barge-in work.
- **Model download path fixed + observable** (0.3.24.3): `dir.parentFile?.mkdirs()` before the ingest rename; `ModelDownloader` logs `model <id>: downloading/result/threw`.
- **Gemma hash corrected** (`main @ 18f4755`); Enhanced Realtime is **PUNTED** out of this sprint.
- **Shrink/minify is currently OFF** (reverted 0.3.24.1); decision below.

## In-scope workstreams

### WORKSTREAM 1 — #61 mic-permission prompt (first-run blocker)  ⭐ HIGH
**Bug:** `MainActivity.kt:135-136` (start-call path) checks `RECORD_AUDIO`; if missing it `setStatus("Mic permission needed to start the call", true); return` — **no `requestPermissions()`**. A sibling path (`MainActivity.kt:333-341`) already does the correct `ActivityCompat.requestPermissions(...)`.
- **Fix:** in the start-call path, when `RECORD_AUDIO` is not granted, call `ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE)` (mirror lines 333-341), optionally preceded by `shouldShowRequestPermissionRationale`; add `onRequestPermissionsResult(...)` so the call proceeds on grant; on hard denial keep the pill + existing behaviour. Consider `POST_NOTIFICATIONS` for the fg service.
- **Verify:** fresh install → tap call → system mic dialog → grant → call starts.

### WORKSTREAM 2 — Streaming / partial STT (#38)  ⭐ the big latency + "alive" win
**Now:** the app waits for silence (utterance complete) before sending the full transcript → the agent can't start until the user stops. **Change:** feed the agent incremental/partial hypotheses as the user speaks (offline whisper partials, or the speech-front-end emitting partial segments), so it starts forming the reply before the utterance completes.
- **Scope:** the STT path emits partials; the turn logic handles "agent starts before utterance complete" (don't wind the loop early, don't double-turn on the final, keep barge-in sane); the `/v1/responses` stream reacts to partials.
- **Verify:** measure a perceptible drop in time-to-first-sound / first-word-of-reply; no double-turn, no mid-utterance cut; emulator + on-device.

### WORKSTREAM 3 — Latency instrumentation (#40)
**Now:** no measurement — latency is eyeballed. **Change:** log/collect P50/P95 per stage (time-to-first-byte, time-to-first-audio, full-reply) so we can quantify + regression-test the loop. Not user-facing; the proof harness for "is it fast + did we regress."
- **Verify:** a short on-device run emits the per-stage latency numbers; a known delay shows up in the numbers.

### WORKSTREAM 4 — Speech enhancement (loud-environment STT)
**Now:** the mic uses `VOICE_COMMUNICATION` (AEC/NS), but genuinely loud settings (street) degrade STT. **Change:** add/push a dedicated NS/DSP layer (e.g. sherpa-onnx NS, or leverage the `VOICE_COMMUNICATION` NS harder) before STT, so the transcript holds in noisy environments.
- **Verify:** on-device STT accuracy in a loud environment materially improves over current; no added perceptible latency or voice-quality regression on TTS.

### WORKSTREAM 5 — Crash-hardening #7/#8 (native crash safety)
- **#7** — `AudioTrack` release under a blocked write → SIGSEGV (a backgrounding/audio-buffer-contention edge).
- **#8** — native use-after-free on background (teardown racing the native pipeline).
- **Scope:** guard the release/teardown paths (catch/order the AudioTrack release; neutralize the use-after-free race); verify backgrounding mid-call + a rapid start/stop does not crash.
- **Verify:** emulator + on-device background-foreground cycles mid-call; no SIGSEGV in logcat.

### WORKSTREAM 6 — Voice-loop polish (small)
- **#60 double-release edge** — already safe (`turnReleased` flag); tidy the underlying double-path so it is single-path by construction.
- **`TurnGateReleaseTest`** — the exactly-once regression assertion (needs Robolectric in the JVM test setup).
- **DevTools/UX extras** — only as they fall out; not scope creep.

### DECISION — R8/minify + shrink
Reverted because the `-keep class org.apache.commons.compress.** { *; }` rule didn't preserve the runtime `BZip2CompressorInputStream`/`TarArchiveInputStream` path. **Re-enable properly** (fix keep-rules: `-keepattributes Exceptions,InnerClasses,Signature`, keep the compressor factory service-loaders, verify against a REAL model download) for ~2 MB, **or drop it explicitly** (keep `minifyEnabled false`, APK ~106 MB native-heavy). **Recommendation:** one focused keep-rule fix + a real model-download gate; if it doesn't hold, drop it as a decision, not a silent revert.

## Punted / deferred OUT of this sprint (explicitly)
- **Enhanced Realtime (Gemma)** — first-ever experiment, never run once; punted until the deferred items are fixed. The Gemma hash is corrected; when we do pick it up it is a spike-first.
- **Google Play publish** — external blocker (Play identity/passport verification); the `hermes-vox` keystore + signed APK + sideload/Obtainium are ready.
- **Particles + settings + mic settings** (the remaining 0.4.0 gates) — next sprint, after the deferred items.

## Definition of done (this sprint)
- **#61** closed: mic-permission prompt + call start on grant (emulator + on-device).
- **Streaming STT** works (partials → agent reacts before utterance completes; no double-turn/no cut).
- **Latency instrumentation** emits per-stage P50/P95.
- **Speech enhancement** improves loud-environment STT on-device (no TTS/voice regression).
- **Crash-hardening #7/#8** — no SIGSEGV on background-foreground mid-call.
- **Voice-loop polish** — #60 single-path by construction; `TurnGateReleaseTest` green (Robolectric).
- **Shrink decision made** (re-enable-with-fixed-keep-rules OR drop explicitly).
- **No-regress:** warm voice, offline STT, streaming, realtime loop, model downloader all keep working (phone-call smoke on-device).

## Iron rules (build agent)
- **Never commit secrets** (keystore gitignored; password from env store / `keystore.properties`).
- **Release convention:** tag === versionName === APK, no leading `v`; proper semver.
- **Verify against a real on-device log before shipping** — instrument the path so it is diagnosable; do NOT iterate builds guessing at a cause.
- One coherent commit per fix; each compiles + its tests pass. Byte-precise edits.
- Do not regress the model downloader (keep `dir.parentFile?.mkdirs()` + the logging).
