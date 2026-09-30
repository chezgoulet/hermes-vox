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

## Hermes hears what you said (vc135)

- **The whole sentence, every time.** The early turn start used to send the text of only the
  last 6 seconds, and fired on a half-second thinking pause. Now a partial transcript can only
  end your turn on a real pause after a finished sentence, and what Hermes receives is always a
  transcription of everything you said — including turns longer than Whisper's 30-second window.
- **No more invented words.** Every transcript is checked before it becomes a turn: noise tags,
  impossibly fast speech, a decoder stuck repeating itself, and Whisper's "Thank you." on
  near-silence are refused (a real "thank you" still gets through). Refusals are logged without
  your words unless "Log spoken transcript" is on.
- **See what it heard.** A dim line under the being shows the words that were sent, then fades
  — mishearings are obvious and you can correct them by talking over the reply. TalkBack reads
  it; it is never saved to disk.
- **Interrupting no longer loses your words** (#12, "the vanish"). When you talk over a reply,
  what you say — from its first syllable — becomes your next turn.
- **Parakeet (optional).** NVIDIA Parakeet-TDT 0.6B joins the speech models: on our test corpus
  it is more accurate (3.4% vs 5.6% word error) and 2.5x faster than the default Whisper, and
  heard nothing in silence. It is a 482 MB download, so Whisper base stays the default for now.
- Whisper's end padding is now explicit (it always was 1000 frames; the bench shows the smaller
  values the upstream docs suggest make it loop or drop final words).
- New: `tools/sttbench`, a 25-clip WER / speed / hallucination bench with the baseline numbers.

## Answer straight away; ready means warm (vc136)

- **No more clipped first words.** After every reply the mic used to go deaf for 450 ms, so
  answering immediately lost your first syllables. The mic now stays open; the reply's own
  echo is recognised afterwards (short, and ended inside that window) instead of by not
  listening.
- **"Preparing your voice" now means ready.** The soul used to report ready before priming,
  so the pill cleared while it was still warming up and the first turn waited behind it. Now
  the soul is primed, and the voice and speech recognizer each finish a warm-up pass, before
  the pill clears.
- The Speaker row appears only for the engine it controls (Supertonic).

## Downloads that finish (vc137)

- **Downloads keep going** when you leave the Models screen or lock the phone. They run in
  the background with a progress notification (and a Pause button).
- **Resume, not restart.** A stopped or failed download continues from the last byte it
  had (HTTP range requests). Losing the network no longer fails it: it waits, then resumes
  by itself. Cancel is now **Pause**, and the card offers Resume. The sha256 check still
  guards every install, so a bad resume can never install a corrupt model.
- Downloads run one at a time, and there's a free-space check before starting.

## Getting more from the hardware (vc138)

- **Speech recognition runs on int8 weights.** The app loaded Whisper's full-precision files
  and ignored the int8 ones shipped beside them. Measured on the STT bench: whisper-base int8
  has 4.5% word error vs 5.6% (noisy audio 16% vs 32%), uses a quarter of the memory, and is
  faster; ARM phone cores have dedicated int8 instructions. The unused full-precision files
  are removed after the switch (~290 MB freed for base, ~480 MB for small).
- **The voice gets two CPU threads on 4- and 6-core phones** (it got one): first sentence
  249 ms → 171 ms measured; more threads measured no faster.
- **The being draws on the display's own frame clock** (vsync-aligned ~30 fps) instead of a
  30 ms timer that landed on uneven frames. Smoother motion at the same GPU cost.
- Enhanced Realtime without the presence model (or with one that cannot start) no longer
  hangs the call for 90 s on "Preparing your voice" and then claims the models failed; the
  call opens and the warning says what is missing.

## The being, rebuilt (vc139)

- **Drawn on the GPU.** The being is now 6,000 points of light rendered with OpenGL ES 3
  on its own thread, with real bloom and a filmic tone curve. It used to be 320 soft dots
  on the CPU. Every shape is exact, so each one reads as what it is.
- **Every shape redesigned.** The iris has fibres and a breathing pupil. The galaxy has
  spiral arms and the jellyfish a pulsing bell with tentacles. The globe turns under a
  scan band, the constellation's links carry travelling pulses, and the terminal types
  itself out. The flame rises and throws embers, and the black hole has a lensed accretion
  disk. The eye darts and blinks, the radar sweeps and its blips answer, the octopus swims,
  and the sphere is lit.
- **Eight new shapes:** DNA helix, knot, aurora, harmonograph, tesseract, mandala,
  butterfly, and an hourglass for waiting.
- **Smooth morphs** between any two shapes, and **trails** for the Comet category.
- **Efficient by design:** 60 fps, paused in the background, and it lowers its own
  quality on a GPU that cannot keep up rather than slowing the phone.

## Stays lit, and a new face (vc140)

- **The screen stays on during a call** — on by default now (Settings → Appearance → "Keep
  screen on during calls"). It holds only while a call is open; the power button still turns
  the screen off, and the switch turns the behaviour off entirely.
- **A new icon, rendered from the being itself.** The launcher icon is the app's own shaders
  posed as the voice: the speaking equalizer running through the idle orb. It is generated by
  `tools/icon/render_icon.py`, which runs the being's GPU pipeline headless. It ships as an
  adaptive icon with a monochrome layer for Android 13+ themed icons, plus legacy square and
  round icons.

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
