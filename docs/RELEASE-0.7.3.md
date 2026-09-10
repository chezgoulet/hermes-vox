# Hermes Vox 0.7.3 — "the accelerator release"

Version code **115** · `versionName` 0.7.3 · built from `main` after the `testing` nightly.

## What this is

Three changes, all from one finding: the on-device **Gemma 4 E2B** express layer had
been running on the **CPU** since the day it was integrated, and the two speech legs
were pinned to a single thread each. This release moves the LLM to the GPU, gives the
speech models the CPU back, and corrects the model sizes the app had been understating.

## 1. Gemma 4 E2B moves to the GPU

- `GemmaExpress` now builds its LiteRT-LM engine **GPU-first**, falling back to CPU if
  the accelerator does not come up. The load logs which backend actually served the
  model: `GemmaExpress loaded: … backend=gpu|cpu`.
- The manifest gains the two native-library grants the LiteRT-LM Android GPU backend
  requires — `<uses-native-library libvndksupport.so>` and `libOpenCL.so`, both
  `required="false"`. Without them the app cannot open the vendor OpenCL stack and the
  GPU backend cannot initialize at all. This was the real blocker: not the model, not a
  missing download.
- **No new download.** Google's own AI Edge Gallery allowlist declares Gemma-4-E2B with
  the same generic `gemma-4-E2B-it.litertlm` Vox already pins (sha256 unchanged,
  2,588,147,712 bytes) and `"accelerators": "gpu,cpu"`. The accelerator ships inside the
  `litertlm-android` AAR.
- Why it matters: the express layer is the latency-critical one. The documented
  phone-class difference is roughly **1.8 s → 0.3 s time-to-first-token**, with *lower*
  memory (≈1.7 GB → ≈0.7 GB). A soul's beat has to land inside a conversational pause;
  1.8 s cannot, 0.3 s can.
- **NPU is not available on the Pixel 9** and GrapheneOS is not the reason: LiteRT-LM's
  NPU path needs a model compiled for the specific SoC, and the published Tensor builds
  are G5/G6 only — there is no G4 artifact. Google's Tensor SDK is a gated beta that
  lists G5/G6, NNAPI was deprecated with Android 15, and the AOT path is currently
  broken even on supported hardware.

## 2. The speech models get the CPU (multi-threading)

The voice models deliberately **stay on the CPU** — Whisper, Piper and Silero are small
and already inside budget, and putting them on the GPU would only contend with the LLM
and the being's render loop. But all three shipped pinned to `numThreads = 1`, leaving a
9-core phone idle.

- **Whisper (STT)** — Auto = half the cores, floored at 2 (one thread starves the
  encoder) and capped at 4 (beyond that the decode is memory-bound and the extra threads
  only steal CPU from capture).
- **Piper (TTS)** — Auto = 2. Piper already outruns real time; this only shortens the
  synthesis gap between streamed sentences.
- **Silero VAD stays at 1 thread on purpose** — it is evaluated per 30 ms frame on the
  capture path, where frame jitter costs more than any throughput win.
- New Settings row: **STT → "Voice CPU threads"** (Auto / 1 / 2 / 3 / 4). The override
  applies to both legs and resets with the STT restore scope. `numThreads` is baked into
  a recognizer/synth session at construction, so a change lands on the **next
  voice-model load**, not mid-turn — the row says so.
- Both loads log what ran: `OfflineWhisperStt loaded: … threads=N cores=M` and
  `SherpaTts loaded: … threads=N`.
- Policy lives in `VoxThreads` (pure JVM) and is unit-tested off-device.

## 3. The model sizes were understated — corrected

`ModelsActivity` renders `sizeMB` directly, and the whole table was lighter than the real
download. Every value is now the artifact's true `Content-Length` in decimal MB:

- Silero VAD 0.5 → **0.6**
- Piper en-US 78.0 → **82.0**
- Whisper tiny.en 86.0 → **118.1**
- Whisper base.en 162.0 → **208.6**
- Whisper small.en 540.0 → **635.7**
- Gemma 4 E2B (presence) 2050.0 → **2588.1** (was ~540 MB light)

## Documentation

`DEVICE_VERIFY.md` claimed the LiteRT-LM runtime "targets the device NPU". It does not —
corrected to GPU, with the `backend=gpu` log check and the thread-log check added to the
device verification steps.

## Verification

- Gate on the Thelio: `go vet` → `go test ./voice/...` → `gomobile bind` →
  `assembleRelease` + `testReleaseUnitTest` + `bundleRelease`, test roll-up, APK
  badging/signer/md5 on both boxes.
- New unit tests: `VoxThreadsTest` (derived defaults, override passthrough, out-of-range
  fallback, the VAD invariant, label honesty).

## Field notes for the next session

- **The GPU fallback is at init only.** If GPU initialization succeeds and a *generation*
  later fails, the existing `GemmaExpress`/`VoiceOrchestrator` path degrades to the
  routed stand-in line rather than retrying on CPU. Acceptable for this pass; the
  `backend=` log line is what tells us which path a given device is on.
- **The express model shares the GPU with the being's render loop.** Watch avatar frame
  pacing while narration runs — that is the next measurement, not a prediction.
