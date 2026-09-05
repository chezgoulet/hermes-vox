# PLAN — remote STT backend (VoxStt over an OpenAI-compatible endpoint)

## Goal
Add a **`remote` STT backend** to the existing `VoxStt` seam: any OpenAI-style
`POST {base}/audio/transcriptions` server (lemonade, whisper.cpp server, faster-whisper
server, etc.). Zero new native dependencies; the existing partial/early-start loop
reuses it unchanged because partials already call `stt.transcribe(snapshot, sr)`
(VoiceController.kt:257) on a worker thread.

## Naming (hard rule — Christopher)
Public app: no house-specific names. Backend value = `remote`. No "House GPU".
Defaults are empty; the user supplies the URL (the app already does this for the
entity URL — same pattern, Settings → custom backend).

## Design decisions (verified against code today)
- `VoxStt` interface (OfflineStt.kt:18–25): name / isAvailable / init(onReady) /
  transcribe(FloatArray, sampleRate): String? / shutdown(). BLOCKING, worker-thread.
- `buildStt()` (VoiceController.kt:112): chooses via `ModelCatalog.KEY_STT_BACKEND`
  (`on-device` | `platform`). `null` return → platform SpeechRecognizer fallback.
- ModelCatalog consts at :66–72 (`DEFAULT_STT_MODEL`, `KEY_STT_BACKEND`, ...).
- Partial early-start (#38) at VC:244–262: 900ms cadence, 6s bounded tail,
  `voiceState.mayStart` stable-hypothesis gate. Remote STT at ~165ms per call
  (measured, Whisper-Base GPU) sits far inside this cadence — early-start works.
- sherpa-onnx AAR already ships `OnlineRecognizer` (streaming) classes — a future
  streaming-partials upgrade needs no new dependency; explicitly NOT in this plan.
- Existing `event=` structured logging from PR #62: the new backend logs through
  `VoxLog.dd`/`w` with the same conventions. Privacy: no transcript text in logs
  (lengths only; existing `logTranscripts()` gate unchanged); audio bytes leave the
  device ONLY when the user picks `remote` and saves a URL.

## Commits (each: gradle `:app:testDebugUnitTest` exit 0 before commit; JDK17 +
## external gradle 8.12.1 on Thelio — see docs in repo or use the logpatch harness)

### R1 — RemoteStt.kt (new file) + WAV encoder + unit tests
- `class RemoteStt(context: Context) : VoxStt`
  - prefs: `stt_remote_url` (String, default ""), `stt_remote_model` (String,
    default "whisper"), `stt_remote_key` (via SecureStore, optional Bearer).
  - `init(onReady)`: async `GET {base}/v1/models` (or `/api/v1/models` — try both
    paths, 3s timeout) → isAvailable=HTTP 200 & url non-blank. Never throws.
  - `transcribe(samples, sr)`: encode FloatArray → **mono PCM16 WAV in memory**
    (pure function `wavPcm16(samples, sr): ByteArray` — unit-testable); multipart
    POST `{base}/.../audio/transcriptions` fields `model`, `file=utterance.wav`;
    Accept/Authorization headers as configured; **3s read timeout**; parse
    `{"text"}` → trimmed string or null; any failure → null + `VoxLog.w("event=stt-remote err=${msg.take(120)}")`,
    success → `VoxLog.dd("event=stt-remote ms=$wall textLen=${text.length}")` (length only).
  - `shutdown()`: no-op (stateless HTTP).
- Unit tests (JVM, no network): WAV header correctness (magic, size fields,
  fmt bits, clip behavior at ±1.0 → Int16 range), URL guards (blank →
  isAvailable false; no exception), text-parse edge cases (missing key, blank).

### R2 — Wire buildStt + fallback chain + ModelCatalog const
- ModelCatalog: `const val BACKEND_REMOTE = "remote"`.
- VoiceController.buildStt():
  `BACKEND_REMOTE -> RemoteStt(context).takeIf { urlPref non-blank } ?: on-device-if-installed`
  and when remote was configured but the init probe failed (`sttReady false at call
  time`), fall back at runtime: VC:275 final-transcribe already handles null →
  keep behavior, but log `event=stt-fallback from=remote to=<platform|on-device>`
  once per pipeline start so the degraded mode is visible in the new per-turn logs.
- On-device-first chain preserved: remote unavailable ⇒ whisper (if installed) ⇒
  platform. Sovereignty default never broken.

### R3 — Settings UI (SettingsActivity + layout)
- STT backend chooser gains "remote" entry (list pattern already exists).
- When `remote` selected: reveal `stt_remote_url` text field (placeholder
  `http://192.168.1.9:13305/v1 or https://…`), `stt_remote_model` field (default
  `whisper`), optional key field (password input, stored via SecureStore, NEVER
  written to prefs XML in clear — SecureStore pattern already exists), and a
  "Test connection" button reusing the init probe with a toast result + dd log.
- Privacy note line under the URL field: "Audio is sent to this server for
  transcription when remote STT is selected."

## Out of scope (parked)
Moonshine/`/audio/speech` TTS-over-server; sherpa `OnlineRecognizer` streaming
partials; multi-server failover; TLS pinning. All follow-ups on the same seam.

## Verification
1. `cd android && <GRADLE 8.12.1, JDK17> :app:testDebugUnitTest` exit 0 after each commit.
2. Post-merge device test (Christopher): Settings → STT backend → remote; URL =
   Thelio lemonade (after bind flip), model `Whisper-Base`; take 8+ turns; export
   log; expect `event=stt-remote ms≈<200` and `event=turn … stt=<~200>` lines with
   outcome=ok, fallback chain verified by turning Thelio off mid-session (expect
   one `event=stt-fallback` and the loop continuing on-device).
3. A/B on identical calls (remote vs on-device whisper-base): compare p50
   `stt=` fields from `event=turn` lines — the measurement this whole line of
   work exists to make.
