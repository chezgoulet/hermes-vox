# Hermes Vox 0.8.0 — "the accelerator release" (staged notes — NOT RELEASED)

> These notes were filed as `docs/RELEASE-0.7.3.md` while `0.7.3` was still the planned home
> of the accelerator work. That version number went instead to the **first-run fix** release
> (`docs/RELEASE-0.7.3.md`, version code 115), which contains none of the content below. The
> accelerator work lives on `testing` and ships as **0.8.0** when the `0.8` series closes
> (`ROADMAP.md`); its headline gate — G1, the GPU actually initialising on a Tensor G4 — is
> still **UNPROVEN** (`PLAN-0.8-accelerator.md`). Do not publish these notes until it passes.

Version code **TBD** · `versionName` 0.8.0 · cut from `main` when the series closes.

## Enhanced Realtime, complete (vc133)

- **The warm soul.** The on-device voice keeps one rolling conversation, so its persona is
  prefilled once instead of on every render.
- **The beat.** On every turn the soul speaks first: a short answer when the turn is small talk,
  or a few generated words as it starts to think while Hermes works on the real answer.
- **No more double answers.** The soul decides before Hermes is asked, and Hermes is told what the
  voice said; when the voice already answered, Hermes can stay silent (`<<SKIP>>`, never heard).
- **A clean handoff.** Hermes' answer no longer loses its first words when it arrives during the
  soul's beat.
- **Honest ER state.** Enhanced Realtime without the presence model — or with one that cannot
  start — says so, with one tap to the download.
- Fixes: turn decisions are never dropped behind tool narration; tool narration names the tool
  and never speaks a control token; without the model, a soul directive can no longer be spoken.

## A better voice, and a soul that hears (vc134)

- **Supertonic** is the new recommended on-device voice: ten voices at 44.1 kHz, about ten
  times faster than real time. It was chosen by a measured bake-off against Pocket TTS and
  Kokoro (`docs/VOICE-BAKEOFF.md`). Piper remains as the lighter option. Settings adds a
  Speaker picker, and choosing an engine, speaker or delivery plays a short sample.
- **The soul hears your tone** (Enhanced Realtime). Gemma 4 E2B listens to the last few
  seconds of what you said, not just the transcript. Your tone gently steers how the voice
  speaks and what Hermes is told about the moment. It runs on-device only (switch in
  Settings → Enhanced Realtime) and falls back to words alone where the audio path is
  unavailable.

## Public-release hygiene

- No personal cleartext exceptions: every gateway needs TLS except tailnet MagicDNS names.
- No personal defaults, hosts, paths or family details anywhere in the code.

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
