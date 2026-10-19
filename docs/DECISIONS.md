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

## D4 — Presence model: Qwen 3.5 0.8B by default

`Qwen3.5-0.8B_int8.litertlm` (920 MB) becomes the default presence model on the
runtime we already use. Gemma 4 E2B (2.6 GB) stays selectable, and an existing
install is never migrated or deleted automatically. One runtime, not two: we
accept the disk cost over adding llama.cpp. Gemma 3 270M and SmolLM2 360M remain
candidates for a lighter option.

**Committed to:** a presence-model picker; no automatic download; no purge path
for installed models; measure load time and resident memory on the Pixel before
calling it done.

## D5 — Streaming STT is decided by A/B

Arms: sherpa streaming Zipformer 20M (43.6 MB, Apache-2.0), Zipformer 06-26
(72.7 MB, Apache-2.0), Nemotron 0.6B (631 MB, NVIDIA licence), and Vosk (40 MB
model, Maven AAR) as the non-sherpa control. Moonshine v2 is a separate decision
because it is a second runtime, not a config swap.

**Metrics:** partial latency (median/p90/p95), finalisation latency, WER on a
frozen corpus plus partial stability, RTF and RSS, battery per audio-hour, cold
start. Measured on the Pixel 7, in quiet, TV and kitchen noise.

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
