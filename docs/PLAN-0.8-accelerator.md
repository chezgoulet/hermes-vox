# PLAN — Hermes Vox 0.8: the accelerator series

Series thesis: **0.7.3 made the hardware path *possible*; 0.8 makes it *provable and
perceptible*.** GPU acceleration is the hallmark feature. The Enhanced-Realtime presence
work rides on it: the whole point of moving the express model off the CPU is to buy the
soul a sub-second beat, and a beat nobody can hear is not a feature.

Scope, as set by Christopher (2026-09-10):
1. GPU support — the hallmark of the 0.8 series.
2. Fixes/improvements for every problem identified in the ER code review.
3. Every refinement/improvement identified for Enhanced Realtime.

---

## The gates (hard, in order — nothing downstream is provable until each lands)

**G1 — the GPU actually initializes on a Tensor G4.** Field log: `GemmaExpress loaded: … backend=gpu`.
**STATUS: UNPROVEN.** 0.7.3 is code-complete and gated, but the accelerator path can only be
confirmed on the device. This is the gate on the entire series.
- If `backend=cpu`: read the GPU-init error immediately above it in the same log.
  Ladder: (a) confirm the two `<uses-native-library>` grants survived the merge;
  (b) try the `gemma-4-E2B-it-gpu.litertlm` variant (2.01 GB) — Google's Gallery doesn't use
  it, so this is a diagnostic fallback, not a default; (c) `Backend.CPU(threadCount = N)` as
  an interim mitigation; (d) re-scope 0.8's headline honestly.

**G2 — the soul's time-to-first-token lands inside a conversational beat.** Warm P50/P95 of
`express()` on-device. The instant lane needs ≲500 ms; Google's phone-class number is ~0.3 s
GPU vs ~1.8 s CPU. **Requires M1's instrumentation to measure.**
- If the G4 lands >800 ms: the instant lane changes SHAPE (fewer, shorter, more curated soul
  turns) rather than being abandoned.

**G3 — the ER-delta is measurable and non-zero.** A field session must show a soul-turn count
> 0 and a soul-first-word percentile. Today ER emits no number at all, so every claim about
it — including "no perceptible difference" — has been an impression. **Delivered by M1.**

---

## The inventory (the review, with status — nothing dropped)

Found across the 2026-09-10 review. **Shipped in 0.7.3** are marked ✔.

| # | Finding | Status |
|---|---|---|
| 1 | `SILENCE_FIRST_MS == LAG_AFTER_MS` (both 4000) collapses the presence ladder — the NEUTRAL/WARM inventory is unreachable dead code | **M2** |
| 2 | `SOUL_DIRECT` is a dead lane: classified, counted, opens a quiet window, speaks nothing. Its comment claims a host render that does not exist | **M3** |
| 3 | The intent classifier is a narration selector, not a router — every route still submits to the mind | **M3** |
| 4 | `ErTelemetry.line()` has ZERO call sites — the Phase-8 instrumentation is collected and never emitted | **M1** |
| 5 | `gemma-e2b` is `recommended=false` and 2.6 GB → absent on most installs; the one live Gemma path degrades to the canned `RoutedExpress` line | **M3 (decision)** |
| 6 | Model sizes understated across the whole table (Gemma ~540 MB light) | ✔ 0.7.3 |
| 7 | Whisper and Piper pinned to `numThreads = 1` | ✔ 0.7.3 |
| 8 | `Backend.CPU()` since day one, never revisited | ✔ 0.7.3 |
| 9 | Manifest missing the two `<uses-native-library>` grants (GPU could not work at all) | ✔ 0.7.3 |
| 10 | `DEVICE_VERIFY.md` claimed the runtime "targets the device NPU" | ✔ 0.7.3 |
| 11 | `ROADMAP.md` still says ER is "not yet wired into the immersive view (issue #52)" — `RealtimeActivity` was deleted in 0.5.4, the call mode IS the surface. Anchor rotten | **M6** |
| 12 | "Mic indicator live but interrupt words VANISH" — barge fails intermittently, speech not replayed | **M4 (log first)** |
| 13 | The express fallback is INIT-ONLY: if GPU init succeeds and a generation later fails, we degrade to the canned line, never retry on CPU | **M6** |
| 14 | GPU contention with the being's render loop is unmeasured (one GPU, three tenants incl. future TTS) | **M6 (measure)** |

---

## Milestones

### M1 — Make ER measurable — ✅ LANDED (nightly `nightly-20260910-113058-82ac6dd`)
The instrument every later claim depends on. Safe to ship in a nightly immediately.
- Emit `ErTelemetry.line()` — periodically on the turn-settle path, and always at call end.
- Add the **ER-delta counters**: turns where the soul produced ≥1 utterance during the mind's
  window (that is exactly "soul spoke before the mind's reply", since the window *is* the
  mind's work), plus the gemma-render P50/P95.
- Extend the line: `er: cls(...) barge(...) arb(...) soul-first-word[...] turns=N soul-spoke=N (x%) gemma-render[p50/p95]`.
- **Gate: G3.** Then one field session, both modes, one script.

### M2 — Un-collapse the presence ladder — ✅ LANDED (the first perceptible change; GPU-independent)
- The ladder now has its middle rung back, as a **NONVERBAL cue at 900 ms**
  (`ErFillers.PREAMBLE_MS`), once per window, superseding the old assumption that
  `SILENCE_FIRST_MS` should gate *all* sound. `SILENCE_FIRST_MS` now gates **words**
  only — which is the rule that was always the real one (Piper's worst case is a
  two-character interjection).
- `State.PREAMBLE` deliberately carries `speak = null`: the presence plays a recorded
  clip on its private track, and `spoken` mode stays **silent** here rather than falling
  back to a sentence. A missing clip degrades to silence, never to words.
- The unreachable `NEUTRAL`/`WARM` sentence inventory is **deleted**, and with it the
  vestigial `warm` parameter (all ten call sites passed a literal `false`).
- `ErFillersTest` now carries a **reachability** test for the rung, so the collapse
  cannot silently recur.
- Gate: field verdict on "alive, not chatty" (the filler-cap slider and presence-voice
  picker are the dials).

### M2.1 / M2.2 — field-fix increments (from the 09-10 logs) — ✅ LANDED
Two rounds, every fix traced to a line in a field log rather than to theory.

**M2.1** (vc118)
- `dialGateway()` now builds a **probe-only** `VoiceController`. It used to construct a full
  controller just to ping the gateway (TTS + Whisper + Silero all load in the constructor) and
  then drop the reference — so every app open loaded the whole pipeline **twice**, ~10 s apart,
  discarding the first copy. Field-verified fixed: one load round, and no pipeline work before
  `conn-test`.
- `EngineConfig.cacheDir` is set (LiteRT-LM documents it for second-load time). **Still
  inconclusive** — the first run after installing has a cold cache by definition; it needs a
  second session on the same build.
- `GemmaExpress` renders twice after load and logs `GemmaExpress warm-up N/2 ms=…`: G2 evidence
  from a quiet conversation, and the cold render moves off the user's first turn. Deliberately
  NOT fed into `ErTelemetry.gemmaRender` — warming an engine is not the soul speaking.
- `soulFirstWord` double-count fixed (`firstWordSeen` latch). Field-verified: p95 4050 ms → 1016 ms
  against a p50 of 1010 ms.

**M2.2** (vc119) — the express layer was burning the GPU for nothing
- One tool-heavy turn fired five narration requests; each spawned a thread and a full render
  (uncontended ~2.2–3.2 s, serialized on one Engine, measured at 18221 ms / 24401 ms), and
  **every one was then rejected** by the glue guard because the reply was already live.
- `VoiceOrchestrator.expressAsync` now permits **one render in flight** (latest-wins), and
  `VoiceController.glueBlocked()` lets the caller decline to generate what the guard would
  discard. `streamingReplyLive()` is one shared condition, not a second copy.
- `ErGemmaGuard`'s spacing rail moved **before** the generation, so a too-soon request no longer
  pays for a render it will throw away.

**Known and NOT fixed, with the evidence:** the persona is re-prefilled on every render.
LiteRT-LM's `Conversation` exposes `close` / `sendMessage` / `cancelProcess` but **no reset**, so
one conversation cannot be reused across renders and a fresh one is built each time. The remaining
levers are a shorter fast-lane prompt, or the engine's own cache. Do not plan on conversation reuse.

### M3 — The soul lane, for real — ✅ LANDED (vc126–vc129)
- Gemma renders greeting/identity/emotion/smalltalk against the mirrored VOX.md, and the soul
  speaks it — with the mind NOT engaged for content-free classes.
- Interlock with the streaming worker: one track, one fence (the 0.6.5 lesson is structural —
  a one-shot must never fight a streamed reply's audio state).
- Contract safety: the soul never answers facts or actions; a misclassification must still
  land the mind's answer.
- **Decision needed:** does ER *require* the 2.6 GB express model (onboarding gate) or degrade
  loudly without it?
- Gate: G2 (TTFT) + the Contract invariant held under a misclassification test.

### M4 — Turn-taking — SPLIT: the vanish bug is still open; the semantic endpointer is BLOCKED

**Blocked, and the block is structural:** semantic end-of-turn "from STT partials" cannot be built
on our current recogniser — Whisper here is offline/batch, so the endpointer never sees words, only
silence. This moves to 0.9 with the streaming-STT swap. See
`.hermes/reviews/vox-er-on-device-stack-review-2026-09-10.md`.
- Semantic end-of-turn from the existing classifier + STT partials: commit on a completed
  question, stay conservative mid-thought.
- Genuine interrupts land via the level-only escape the field log already proved necessary
  (`barge_level_only_ms`).
- **The vanish bug (#12) first, from a log, never a hypothesis.** Discriminator already agreed:
  does the failed-interrupt speech arrive later as a normal turn (captured, flag unset) or
  vanish entirely (frames never evaluated)?

### M5 — VOX.md quality — ✅ PROMPT REMADE (vc127); the stems are the next piece
- A/B: mirrored VOX.md vs the generic persona, same prompts.
- Validate the authoring/resync loop end-to-end in the field (its provider-layer 400 is
  resolved; confirm the fix holds).

### M6 — The honesty pass — PARTIAL (ROADMAP refreshed 2026-09-10; contention measurement open)
- ROADMAP refresh (#11) + the 0.8 series added.
- #13 runtime fallback, #14 render-loop contention measurement.
- Per-milestone: `docs/RELEASE-0.8.x` + `DEVICE_VERIFY.md` refresh.

---

## Series close-out (2026-09-10)

The planned order held; what changed is M4, and the reason is worth recording. The plan assumed the
semantic endpointer was a matter of wiring the classifier to STT partials. It is not: **there are no
partials to wire**, because the recogniser is offline. A model choice foreclosed a logic upgrade, and
the fix is a model swap (0.9), not a milestone.

**Landed on `testing`** (each a verified nightly, gate green): 0.7.3 GPU · M1 instrumentation ·
M2 ladder · M2.1/M2.2 double-load · M3a knobs · M3 lists-out · M3 router · M3c prompt + greeting +
router numbers · the two community PRs.

**Carried forward** with a home in the roadmap: the field session on the router, G2's reframing, the
vanish bug, the in-cabin A/B, the adaptive barge floor, engine release + soak, narration into the
loop, GPU contention, truncation-after-barge, and the verdict mechanism.

**The direction that replaced the turn-ownership framing:** the beat (A′) — see
`docs/DESIGN-enhanced-realtime-voice.md` §DECISION. It is the next increment.

**Ship shape:** 0.8 as a milestone series on `testing` (each milestone = a nightly, field-verified
before the next lands), then release from `main` when the series is coherent. Decisions 1–3 below
shape this.

## Decisions LOCKED (Christopher, 2026-09-10)

**1. ER owns smalltalk — the mind may override.** The soul answers greetings, identity,
emotion and smalltalk directly; the Hermes agent keeps the right to override. M3 therefore has
to build the override path, not just the soul path:
- the soul's answer is voiced immediately (sub-second — the entire point of the feature);
- the mind is still engaged in parallel; that engagement IS what gives it the chance to override;
- if the mind's reply lands while the soul is still speaking, the existing `speak()` precedence
  (Hermes preempts Gemma) cuts the soul — keep that as THE override mechanism, do not invent a
  second one;
- the failure this creates is the **double answer** (the soul greets, then the mind greets again).
  Client-side guard: the SOUL_DIRECT voice prefix tells the agent the app has already answered and
  to reply with a bare no-op token when it has nothing to add; the client suppresses that token and
  never voices it. If a stock gateway echoes the token rather than honouring it, the client fails
  safe — suppress, keep the soul's answer, never speak the token.

**2. The express model is a REQUIREMENT of Enhanced Realtime.** ER is *defined* by using a local
model to fill the gaps Sesame-style; Realtime already exists as the standard experience. So the
silent `RoutedExpress` stand-in must stop masquerading as the soul:
- enabling ER without the installed model is a loud, blocking state — not a quiet degrade
  (download prompt / "ER unavailable: the express model is not installed");
- the stand-in survives only as a crash-guard for the orchestration, never as a user-visible voice;
- `ModelCatalog` keeps `recommended = false` (Realtime does not need it) while ER's own gate treats
  it as required.

**3. Series shape: every milestone lands on `testing` as a nightly; ONE 0.8.0 is cut from `main`
at the end.** Intermediate nightlies carry `versionName 0.8.0` with a rising `versionCode`.
