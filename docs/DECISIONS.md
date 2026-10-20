# Decisions — 2026-10-19

A record of the direction set for 0.9 and 1.0. Each entry is a decision, the
reason, and what it commits us to. Superseding an entry means adding a new one
with the date, not editing history.

## D1 — Android only

Desktop and iOS are out of scope. The remaining Go core was removed in #126, so
the reusable surface a desktop client would have needed no longer exists; a
desktop renderer would be a new product, not a port. **Committed to:** no desktop
work, no cross-platform abstractions added "for later".

## D2 — Distribution: GitHub + Obtainium + F-Droid

Google Play is dropped as a rail: AAB builds, Play App Signing, the data-safety
form and the store-compliance steps come out of the process. **F-Droid is the
store.** We always ship our own signature, which makes reproducible builds a
release requirement rather than polish.

## D3 — 0.9 is the last series before 1.0

Every 1.0 feature ships during 0.9.x, behind its own setting where it needs one,
and accumulates real use. 1.0 is a declaration, not a feature drop. **1.0 gates:**
F-Droid accepted; reproducible builds proven and enforced in CI; live-gateway
suite green; behaviour matrix on at least two devices; no open severe bugs;
performance within the measured baseline.

## D4 — Presence model: Gemma 4 E2B stays the default (amended 2026-10-19)

Originally Qwen 3.5 0.8B (920 MB LiteRT) was to become the default on the
existing runtime. It was measured on the Pixel 7 first, as this decision
required, and **rejected**:

- **The GPU engine does not fit on Mali.** `initialize()` never returned: process
  PSS reached 6.86 GB against 4.30 GB available, `MemAvailable` fell to 35 MB,
  and the 180 s watchdog fired — twice, both prompt shapes. The vendor's card
  documents the same on a Pixel 8a (Mali, OpenCL) and verifies Android GPU only
  on Adreno; the fp32 activations it requires are the cause.
- **On CPU it is ~5× slower than the model it would replace**: 1.42–1.69 s to
  first token, ~6 tok/s, 1.6–1.9 s per short line, against Gemma 4 E2B on GPU at
  **289 ms** to first token and **756 ms** per line.
- **Its output is corrupted under our conversation shape.** LiteRT-LM does not
  reset Qwen's GatedDeltaNet recurrent state for a new `Conversation` on an
  existing `Engine` (upstream issue #3165): the third render returned the system
  prompt verbatim. The vendor requires one Engine per conversation, and engine
  init measured 35–94 s — impossible for a sub-second presence layer.
- **It saves disk, not memory**: 2.28 GB resident against Gemma's 2.41 GB,
  despite 965 MB on disk against 2.6 GB.

SmolLM2 360M was measured too and is also slower (1.45 s to first token on GPU,
1.92 s per line) at 1.5 GB resident.

**Decision:** Gemma 4 E2B (LiteRT-LM, GPU) remains the presence default. Qwen is
not shipped. It can be reconsidered only if the runtime resets recurrent state
and engine init becomes cheap, and only on devices whose GPU can hold the fp32
engine. Measurements: `docs/PRESENCE-BENCH.md`.

## D5 — Streaming STT: Nemotron 0.6B, measured (amended 2026-10-19)

Arms: sherpa streaming Zipformer 20M (43.6 MB, Apache-2.0), Zipformer 06-26
(72.7 MB, Apache-2.0), Nemotron 0.6B (631 MB, NVIDIA licence), and Vosk (40 MB
model, Maven AAR) as the non-sherpa control — later dropped without being run (see
the residual note below). Moonshine v2 is a separate decision
because it is a second runtime, not a config swap.

**Metrics:** partial latency (median/p90/p95), finalisation latency, WER on a
frozen corpus plus partial stability, RTF and RSS, battery per audio-hour, cold
start. Measured on the Pixel 7, in quiet, TV and kitchen noise.

**Outcome — Nemotron 0.6B at the 160 ms chunk wins, and the accent result decides it.**
On the same corpus, chunking, feed and endpoint rule: WER **1.4 / 2.6 / 1.7 %**
(quiet / TV / kitchen) against the 06-26 Zipformer's 2.0 / 4.5 / 2.8 %;
**11.9 % on accented speech against 38.8 %**; words visible at a **707 ms**
median against 956 ms. The 20M arm remains unusable (20–24 % WER, majority of
words never surfacing in a partial). Full tables, method and committed raw
results: `docs/STT-AB.md`.

**Cost, stated because it is the constraint:** RTF 0.68–0.76 against 0.19,
**903 MB RSS** against 291 MB, a **464 MB** download against 310 MB. That is
3.1× the CPU and memory for the accuracy, and it leaves little headroom beside
VAD, TTS and the presence model on one phone.

**Decision:** adopt Nemotron on 8 GB-class devices, with the Apache-2.0
Zipformer as the low-end fallback. **Gated on one in-app co-residency run** —
VAD + STT + TTS + presence together in a live call — because a 0.68–0.76 RTF
measured alone is not evidence that it holds when the rest of the pipeline is
running. Until that run passes, the shipped STT path does not change.

**Licence:** NVIDIA Open Model License, revocable, and the upstream export ships
no licence file. The weights are **never bundled** — downloaded at runtime, with
the agreement, the attribution string and the model-card link shown and accepted
on the download screen.

**Residual, recorded rather than assumed:** the **Vosk control arm was never run**,
and is now **dropped rather than outstanding** (maintainer, 2026-10-19). Vosk needs
a second ASR runtime — its own Maven AAR and model format, not a config swap — and
F-Droid path B already builds sherpa-onnx and onnxruntime from source, so a third
native dependency costs twice: in the recipe and in review. Nothing measured suggests
it beats the Zipformer, which is our measured low-end fallback. It comes back only as
a proposal with a number attached, never as an untested default. The A/B therefore
stands as "best of four sherpa arms", not "best of five".

## D6 — Wake word: ship "Hey Hermes", personalise, then arbitrary phrases

1. "Hey Hermes" trained in our pipeline with openWakeWord and shipped.
2. **Personalise to my voice**: record 5–10 takes of the known phrase; train
   openWakeWord's custom verifier as a second-stage filter. This is also the
   shared embedding front-end for speaker-ID in 1.1.
3. **Arbitrary phrases** (e.g. "Hey Torc"): pick by measurement between matching
   the phrase in streaming ASR (opt-in, costs battery) and training an
   openWakeWord model on the user's gateway from their recordings.

Enrolment audio is voice biometric data: device-local, encrypted, deletable, and
sent to a gateway only with explicit consent.

Design, evidence and open questions: `docs/WAKE-WORD.md`.

## D7 — Audio cues are part of the product

Procedurally generated from the being's own motion vocabulary, unique to us.
**On by default on new installs**, with a Settings toggle. Respects
`AudioManager.ringerMode` (normal / vibrate / silent), the interruption filter,
media volume, and system haptics; uses the haptic engine when the phone is on
vibrate-only. Never plays while TTS is speaking.

## D8 — F-Droid: build the native stack from source

Path B. sherpa-onnx from a pinned submodule or `srclibs`, built per-ABI in the
recipe; onnxruntime from Maven Central (`com.microsoft.onnxruntime:onnxruntime-android`),
which F-Droid's policy allows. Precedents: Anti-Vocale, Ramblr, Marmalade.

The JitPack route (a trusted Maven source under the policy) is worth asking the
reviewers about in parallel, since the artifact is byte-identical to ours — but
we do not plan around it.

## D9 — A live gateway is part of the test story

Scripted protocol suite for ordering, keepalive, mid-stream failure, cancel,
compress chaining and auth; plus a nightly run against a real Hermes gateway with
a small local model and a test key. Fixtures prove the parser; only a gateway
proves the wire.

## D10 — The what's-new banner

Shown once on the first launch after an upgrade — never on a fresh install, never
twice for the same version, never on a downgrade. Release notes ship inside the
APK. Breaking changes carry one action the app routes itself (for 0.9: HTTPS →
Entity & Connection).

## D11 — Licence: GPL-3.0

The app is GPL-3.0. sherpa-onnx's TTS support statically links espeak-ng
(GPL-3.0) for Piper phonemes; Apache-2.0 and GPL-3.0 combine only under GPL-3.0,
so the whole app is GPL-3.0 rather than Apache-2.0 with an undeclared GPL
component inside it. This was already true of every release we shipped with the
upstream AAR; compiling sherpa from source made it visible.

**Committed to:** `LICENSE` GPL-3.0, a `NOTICE` naming each component and the
source offer, README/architecture/site saying GPL-3.0, and the F-Droid metadata
field `License: GPL-3.0-only`. Models downloaded at runtime keep their own terms.

## D12 — Notifications: an armed session in 1.x; routing and a BYO endpoint in 2.x

The House operates no notification infrastructure — no push service of our own, no
FCM, no Google Play Services. Nothing wakes a phone that is not running the app.

**1.x — the armed session.** The user can arm the line for a specific wait ("call me
when the build finishes"). While armed, the app holds a foreground service and its
existing gateway connection open, with a visible indicator and a visible battery
cost, and delivers the completion as a local notification or in the entity's voice.
If the app is killed, the arm dies with it. A disconnect reconnects to the same
session; there is no offline mode and no capture while disconnected.

Armed is not listening: the socket and the foreground service are held, the
microphone is not. The two states carry distinct indicators, because a user who
cannot tell them apart will assume the worse one. An arm is bounded — it has a
visible expiry and it ends on the run's terminal state, failure or timeout, never
because the socket merely stayed open.

**2.x — per-notification-type routing, with the BYO endpoint.** Fine-grained control
per notification class — voice call, text only, none, or delegated to another
surface such as Telegram or Discord — ships with the user-supplied push endpoint:
the user provides their own push server, the app speaks UnifiedPush, and we operate
and promise nothing.

Rationale: reachability is the user's decision, not a service we run, and a phone
that is not running the app cannot be woken without either Google or a push server
somewhere. Policy only earns its place once there is more than one transport to
choose between, which is why the routing table lands with the endpoint rather than
with the armed session.

## D13 — Realtime is frozen after the basics; all polish goes to Enhanced Realtime

Set 2026-10-20, after a field session spent in the wrong mode.

Realtime is the default and the floor: a hands-free line that hears you, answers, and
stops when you speak. That has to work, and the barge fixes in flight land there because
they are shared code — the barge gate, the capture drain and the endpoint clock are the
same path in both modes.

**Beyond that, Realtime gets no polish.** Every improvement from here — length and
register, turn-taking, backchannels, presence, delivery — lands in Enhanced Realtime. ER
is where the product is going; Realtime is what it has to keep working.

**Committed to:** no new behaviour in Realtime that is not a fix; no duplicated polish
written twice for both modes; and ER stops being described as the side door once its
basics hold (which is a 1.0 question, not a 0.9 one).

Consequence worth naming: ER carries the on-device Gemma presence model and the GPU
path, so it is the heavier mode and it is not the default. Freezing Realtime means the
default experience stays plain until ER is promoted — the promotion is a decision
someone has to make, not something that happens by drift.
