# Hermes Vox — Roadmap

Hermes Vox is an open-source **voice client for the Hermes agent**: a particle-being
you talk to on your phone, with on-device speech processing and a hands-free,
barge-in conversation line.

**Status keys:** `[x]` shipped · `[ ]` in progress / planned · `[?]` open question · `[-]` deferred.

---

## Shipped (`[x]`)

**0.3 → 0.5.6.x — the MVP + hardening line.**

- **M1 — Core completeness.** A working end-to-end voice loop: STT/VAD/TTS on-device,
  streaming to the Hermes gateway, barge-in, and the particle-being rendered on OLED black.
- **M2 — Modern sci-fi UI overhaul.** A designed interface on true OLED black, the
  particle-being as the central "presence."
- **M3 — SSE + streaming.** The entity's stream consumed as a live SSE push, and the reply
  rendered in real time.
- **M4 — Voice pipeline (sherpa-onnx).** The sherpa-onnx runtime bundled (pinned k2-fsa AAR);
  Silero VAD, Whisper STT, Piper TTS.
- **M5 — In-app model downloader.** Blessed models download inside the app; only the Gemma
  expression model needs a separate license acknowledgement.
- **M6 — Realtime + Enhanced Realtime (alpha).** Two hands-free modes; ER adds the on-device
  Gemma expression layer.
- **0.5.x hardening.** Crash-guard, wall-clock streaming deadline, barge-in leak fixes,
  `/compress` chain unify, Kotlin 2.2.21, seven new visual archetypes, settings-UX pass.
- **0.6.x — the being.** Identity/glyph work, the archetype vocabulary, interface aliveness.
- **0.7.x — the aura arc.** `0.7.2` shipped the halo bake — the emitter's near-black rim baked
  to true transparent at ~0.66 radius; the hardware-layer theory was falsified in field and the
  layer removed. Field-verified on both Natural and Adaptive colour profiles.

---

## In flight — `0.8`, the accelerator series

Ships as **one 0.8.0** at the end. Every increment lands on `testing` as a nightly first;
`main` stays on the last release until the series closes.

**The hallmark: acceleration, provable and perceptible.**

- `[x]` **0.7.3 — Gemma on the GPU.** `Backend.CPU()` was an untouched default; the manifest had
  zero `<uses-native-library>` grants. GPU-first with CPU fallback, plus the `VoxThreads` policy.
- `[x]` **M1 — ER instrumentation.** The counters, `gemma-render` percentiles, `should_emit`.
  Before this, "no perceptible difference" was only an impression.
- `[x]` **M2 — the presence ladder un-collapsed.** `SILENCE_FIRST_MS == LAG_AFTER_MS == 4000`
  made the middle rung unreachable. One nonverbal cue at 900 ms; the dead sentence inventory
  and the literal-`false` `warm` parameter deleted.
- `[x]` **M2.1 / M2.2 — the double-load fixes.** `dialGateway()` was constructing a throwaway
  full pipeline; `EngineConfig.cacheDir` took cold load 24.3 s → 1.29 s; one-render-in-flight.
- `[x]` **M3a — the express knobs.** `maxNumTokens=8192`, `maxOutputToken=256`, speculative
  decoding on the `ExperimentalFlags` singleton (correctly, for the pinned 0.16.1), character
  guard 600 → 1000, mode-aware init gate.
- `[x]` **M3 — the lists stop routing.** `ErIntent` is a safety classifier and nothing else.
  The pattern lists are gone from the decision path entirely.
- `[x]` **M3 — the soul decides.** One render per turn carries the caller's line; its *output*
  is the decision — a line to speak, or `<<ESCALATE>>`. The mind runs in parallel on every turn.
- `[x]` **M3c — the prompt remade for a decider,** not a renderer. The prelude was describing
  a renderer while the router was asking for a decision; the persona was fighting the directive.
- `[x]` **M3c — the soul opens the call.** In ER the entity greets you first, in its own voice,
  rendered against its own VOX.md — so turn one is the soul's by construction.
- `[x]` **M3c — the router's numbers.** `soul(answer=N escalate=N nothing=N)` in the per-call
  line, so the router's invisible failure mode is visible.
- `[ ]` **Community PRs #128 / #129** — merged, gated, field-tested alongside our own changes.
- `[ ]` **Field session on the router** — the open question the numbers exist to answer.
- `[ ]` **G2 — render latency** — warm render ~2.2 s against a ≲500 ms target. Reframed rather
  than abandoned: the soul must beat the *mind*, and on the turns that matter it already does by
  an order of magnitude. The structural fix is the beat (below), not a faster render.

**Close-out for the series:** the ER state doc and the field checklist are in
`.hermes/reviews/vox-er-*.md`; the plan and its statuses live in `docs/PLAN-0.8-accelerator.md`.

---

## The ER direction — the beat (decision, 2026-09-10)

**The finding that set the direction.** ER was functional but inaudible on a healthy gateway:
the mind's first token lands in ~1.8 s, the soul's render takes ~2.5 s, and the delivery guard
correctly drops the soul line once the reply has begun flowing. So on a *good* connection ER
looked inert — for a brand-new reason.

**What the field does about it** (primary sources, in `.hermes/reviews/vox-er-turn-taking-field-research-2026-09-10.md`):

- **Sesame** — one model over interleaved text and audio, and in their own words CSM "can only
  model the text and speech content in a conversation—not the structure of the conversation
  itself… turn taking, pauses, pacing." The best voice in the business explicitly excludes
  turn-taking from what its model does.
- **OpenAI** — turn detection is a *classifier*: "scores the input audio based on the probability
  that the user is done speaking", plus an `eagerness` preset. Not a silence threshold.
- **ElevenLabs** — the most deployed conversational stack is **cascaded**, not end-to-end, with a
  dedicated turn-taking model and inline expressive tags (`[laughs]`, `[sighs]`).
- **Full-Duplex-Bench** — measures *stop latency* and *response latency*; re-entry delay should
  depend on intent; **a single static policy is the wrong shape.**

**Nobody settles who owns a turn by routing it to one of two generators. The floor is taken in
beats.**

`[ ]` **The beat (A′).** The soul takes the *opening beat of every turn*, instantly; the mind
preempts exactly as it does now; escalation decides only whether the soul *continues*. This kills
the race without discarding anyone's work, and it makes ER audible on every turn rather than only
the turns the router hands over.

`[ ]` **Pre-rendered personality stems.** The beat must be instant, so it cannot be a live render.
The VOX.md pipeline emits the entity's own **stem vocabulary** — its actual backchannel register,
authored by the soul, not a generic clip library — synthesised once at init, in the same voice as
everything else. This is a change to the VOX.md pipeline's contract, and it is the piece that makes
the beat free.

---

## The remaining ER findings (`[ ]`)

Ranked by what will actually bite. Detail in `.hermes/reviews/vox-er-remaining-plans-2026-09-10.md`.

- `[ ]` **F3a — the vanish bug.** Mic light on while interrupted words disappear. Log before
  hypothesis: does it happen early in a fresh connection, and do the words arrive later or vanish?
- `[ ]` **F3b — real-world turn-taking.** The in-cabin endpointer A/B (open since 0.6.8) and the
  **adaptive barge floor** — near-misses cluster at 0.10–0.13 on a 0.10 floor in a noisy room;
  sample ambient at call start rather than using an absolute threshold.
- `[ ]` **F5 — memory and thermal.** Release the express engine on call teardown (the init gate
  makes this safe), then a thirty-minute soak watching RSS.
- `[ ]` **F6a — narration into the presence loop.** The directive already takes the tool context;
  route the tool event through the loop so the line is topical and density-governed.
- `[ ]` **F4 — GPU contention.** Per-frame timing idle versus during a render. Christopher's
  inclination, to be confirmed by measurement: sacrifice frame rate, not the soul.
- `[ ]` **F8 — a verdict mechanism.** A fixed ten-utterance script run per candidate build, so
  "this build is better" stops being an impression. Adopt the benchmark's scenario taxonomy:
  user interruption, backchannel, side conversation, ambient speech.
- `[ ]` **Truncation after a barge** — check whether session history keeps the whole reply or only
  what was heard. The reference implementation truncates, so the model doesn't believe it said
  things the caller never heard.
- `[ ]` **Semantic endpointing** — probability plus timeout, with an `eagerness` preset instead of
  hand-tuned constants. **Blocked** until the listening half is streaming (see 0.9).

---

## Next — `0.9`, voice and the arrangement

Charter set by Christopher: **voice choice and different soul LLMs.** Research and candidate
rationale in `.hermes/reviews/vox-er-on-device-stack-review-2026-09-10.md`.

**The finding that shapes the series: a batch model choice has foreclosed a logic upgrade.**
Whisper here is an offline recogniser — it transcribes a complete utterance after the fact, so the
endpointer never sees words, only silence. Semantic turn detection and mid-speech backchannelling
are *unreachable* in the current arrangement, not under-tuned.

- `[ ]` **Kokoro for TTS.** 82M, StyleTTS2-derived, Apache-2.0, CPU, **already supported by
  sherpa-onnx** — a model change inside an existing dependency. Roughly double Piper's cost,
  first audio ~90 ms, materially better naturalness. The cheapest quality win in the stack.
  Limits: fixed voices, no cloning.
- `[ ]` **Streaming STT** — Moonshine (edge-first, streaming v2 encoder) or SenseVoiceSmall
  (non-autoregressive, ~70 ms per 10 s, and in the same forward pass returns **emotion and
  audio-event detection**: laughter, coughing, applause, background music). If its English holds
  up against `whisper-base` in an on-device A/B, the affect and event tags give the presence layer
  *measured* inputs and retire the last of the keyword machinery. This swap is what unblocks
  semantic endpointing and mid-speech beats.
- `[ ]` **A presence-sized soul model** — LFM2-0.7–1.2B class rather than the current 2.6 GB /
  ~2.2 s-per-render Gemma 4 E2B, which is roughly 20–30× the size its job implies. Chosen
  **together with the voice**, because the entity has one throat: if the presence voice and the
  reply voice come from different models, the entity splits in the ear.
- `[ ]` **Then** the semantic endpointer and intent-dependent re-entry delay — both of which need
  the partials to exist before they can be built at all.

**The arrangement items that go with it.** The listening half becomes continuous (VAD + streaming
ASR for the whole call) rather than per-turn; presence triggers on **speech onset** and on partials
rather than on turn boundaries — that is backchannelling, and it is the most human behaviour on
this list. The continuous lane needs an explicit CPU/GPU and battery budget, because it collides
directly with the GPU-contention and thermal findings above.

Every swap is a claim until it is A/B'd on the device with everything else running. The visual arc
taught us that a change which should obviously help can be inert.

---

## Open questions (`[?]`)

- **Desktop / cross-platform.** The portable `voice/` Go core + `mobile/session.go` are the
  reusable surface. A desktop frontend would be a NEW renderer (Compose Desktop, web/Electron, or
  a native window) — the current Ebitengine `game/` shell is boilerplate coupled to the OLD
  VoiceBackend architecture and is NOT the desktop path.
- **In-app model downloads vs bundling** for the 0.9 model set — the APK-size tradeoff returns
  when Kokoro and a streaming ASR join Whisper and Piper in the catalog.

---

## Deferred (`[-]`)

- **Bundling the voice models** — kept in-app downloadable (Play-friendly; Gemma stays a separate
  license download). A future "offline out of the box" could bundle the required set, but it's a
  deliberate APK-size tradeoff.
- **Walkie Talkie / PTT** — removed in C2 (0.4.0). Every mode is hands-free; there is no
  push-to-talk button. (Stripped deliberately — see docs/PLAN-c2-walkie-strip.md.)
- **Cloud voice processing + full-duplex realtime** — the app is local / self-hosted (you vs. your
  own Hermes gateway); cloud is not a mode today. Note that the *arrangement* work in 0.9 moves us
  toward full-duplex behaviour without a cloud dependency.

---

## The being

The identity the whole thing is built around: the particle-being — a luminous swarm that
*is* the agent, present and alive. The shapes it can be are one of its defining features
(the 20-archetype vocabulary from the visual passes). Design north star: "the AI agent exists
within the forms and likes to play with them."
