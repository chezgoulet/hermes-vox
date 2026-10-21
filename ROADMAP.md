# Hermes Vox — Roadmap

Hermes Vox is an open-source **voice client for the Hermes agent**: a GPU-drawn being
you talk to on your phone, with on-device speech processing and a hands-free,
barge-in conversation line. It is a work in progress: installable and useful today,
with the items below still open.

**Status keys:** `[x]` shipped · `[ ]` in progress / planned · `[?]` open question · `[-]` deferred.

## Direction (set 2026-10-19; release shape amended 2026-10-21)

- **Android only.** Desktop and iOS are out of scope; the Go core that a desktop
  client would have reused was removed in #126.
- **No Google Play.** Distribution is GitHub Releases, Obtainium and F-Droid.
  F-Droid is the store, and we always ship our own signature — so reproducible
  builds are a release requirement, not polish.
- **Three releases, then a declaration (D21).** `0.9.1` makes the mechanics excellent, judged with
  Presence off as readily as with it; `0.9.2` makes Presence the product — one mode, with the
  two-mode distinction retired; `1.0` carries everything else plotted, including the work formerly
  numbered 2.0. No dates: 1.0 is cut when the features are complete and stable in the community's
  hands, not when a calendar says so.
- **Presence is the product, not a mode (D16).** Realtime is what the app does; Presence is the
  layer that makes it a being. It is on by default, it is named Presence, and by the end of `0.9.2`
  no selector asks a user to choose between two names for the same call.
- **After 1.0, the backlog is community-led and unnumbered.** Entity calls, remote control of the
  interface, the wake word and the watch items are recorded, not scheduled. What moves out of that
  list moves because the community asked for it (#268).
- The full records are in [`docs/DECISIONS.md`](docs/DECISIONS.md).

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
- **0.8.0 — the accelerator release** (released 2026-09-30, `docs/RELEASE-0.8.0.md`). Gemma on
  the GPU; Enhanced Realtime complete (the warm soul, the beat, `<<SKIP>>`); Supertonic TTS by
  bake-off; the soul hears the caller's tone; whole-utterance STT with a transcript validator,
  the "heard" line and optional Parakeet; the mic stays open after a reply; background,
  resumable model downloads; int8 Whisper; the being rebuilt on OpenGL ES 3 with 27 shapes;
  keep-screen-on during calls; an icon rendered from the being's own shaders. The series log
  is below.

- **0.8.1 — the headset release** (released 2026-10-19, `docs/RELEASE-0.8.1.md`). An attached
  Bluetooth, wired or USB headset becomes the call's route (#149); the README, roadmap, device
  guide and policies that had only reached `testing` land on `main`; and the nightlies are
  tagged at the commit they were built from and carry a version code that installs over the
  release.

---

## `0.8` — the accelerator series (released as 0.8.0; field verification open)

Shipped as **one 0.8.0** on 2026-09-30. Every increment landed on `testing` as a nightly first.
The one fix release cut inside the series was **0.7.3** (vc115), the first-run block from #130
(`docs/RELEASE-0.7.3.md`). The `[ ]` items below are the field work still open after the release.

**The hallmark: acceleration, provable and perceptible.**

- `[x]` **Gemma on the GPU** (was labelled `0.7.3`; that number became the first-run fix).
  `Backend.CPU()` was an untouched default; the manifest had
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
- `[x]` **The beat, built (vc133).** The soul keeps ONE warm LiteRT-LM conversation (persona
  prefilled once, rotated by `SoulBudget`), so a render only prefills its directive. On the
  mind's turns the same render returns a generated two-to-six-word opener (`Outcome.Beat`), the
  soul decides *before* the mind's submit (`SoulGate`, bounded + adaptive), and the mind is told
  what the voice did — including that it already answered, when it may reply `<<SKIP>>`
  (`MindSkip`) and stay silent. The beat→reply audio handoff is explicit. See
  `docs/DESIGN-enhanced-realtime-voice.md` §DECISION (the warm soul).
- `[x]` **The voice + the soul's ears (vc134).** Supertonic by measured bake-off
  (`docs/VOICE-BAKEOFF.md`); Gemma 4 E2B audio input so the soul hears the caller's tone
  (`VoiceMood` → delivery + the vibe Hermes is told).
- `[ ]` **Field session on the beat** — the `express-probe … warm=` line and
  `soul(beat= … mind-skip=)` are the numbers that prove it on a real device.
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

`[x]` **The beat (A′)** — built in 0.8.0 (vc133) as a *generated* beat from the warm soul. The soul takes the *opening beat of every turn*, instantly; the mind
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

- `[x]` **F3a — the vanish bug.** Mic light on while interrupted words disappear. Fixed in 0.8.0
  (`BargeCarry`, #12): the interrupting words are kept from their onset and become the next
  turn. Field witness: `event=barge-carry`.
- `[ ]` **F3b — real-world turn-taking.** The in-cabin endpointer A/B (open since 0.6.8) and the
  **adaptive barge floor** — near-misses cluster at 0.10–0.13 on a 0.10 floor in a noisy room;
  sample ambient at call start rather than using an absolute threshold.
- `[ ]` **F5 — memory and thermal.** Release the express engine on call teardown (the init gate
  makes this safe), then a thirty-minute soak watching RSS.
- `[ ]` **F6a — narration into the presence loop.** The directive already takes the tool context;
  route the tool event through the loop so the line is topical and density-governed.
- `[ ]` **F4 — GPU contention.** Per-frame timing idle versus during a render. The maintainer's
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

## `0.9.1` — mechanics

**The claim:** the app is fast, correct and navigable with Presence **off** — and every primitive
Presence needs exists, is measured, and carries a bar. A good call does not depend on the layer.
When the layer is on, these same primitives are what make it good.

Plan: [`docs/PLAN-0.9.1.md`](docs/PLAN-0.9.1.md). Milestone label: `milestone/0.9.1`.

**Already in hand, not to be rebuilt.** The streaming arm is in the app (#207, closed 2026-10-20):
`StreamingStt.kt` and `OnlineNemotronStt`, a catalogue entry at the 160 ms chunk, and a Settings
chooser that falls back to the Apache-2.0 Zipformer while the streaming model is absent,
downloading, or will not load. The A/B that chose it — Nemotron 0.6B at 11.9 % WER on accented
speech against 38.8 %, at 3.1× the CPU and memory — is `docs/STT-AB.md`, D5. The live gateway suite
(#167) runs in CI and fails the build if any of its six cases skip.

**The work, in dependency order:**

- `[ ]` **Co-residency (#179) — the gate.** One live call with VAD + the streaming arm + TTS +
  presence resident: RTF, peak RSS, worst `MemAvailable`, presence first token, stutter, thermal.
  Then the default arm is decided and written into `DECISIONS.md` with the numbers.
- `[ ]` **The listening half, continuous (#228, #235, #229).** The mic stays open for the call, the
  partial worker stops re-decoding from scratch, and endpointing becomes a probability with a
  timeout and an `eagerness` preset rather than a silence constant. This is the substrate 0.9.2's
  behaviours need; building it is 0.9.1's work because a call is better with it either way.
- `[ ]` **The reply lifecycle under interruption (#240, #202).** One end event per request, audio
  already in flight attributable to its reply, and the truncation channel either producing a named
  cause or closed with the evidence that narrowed it.
- `[ ]` **Profiles (#174).** A registry of saved connections and a switcher — endpoint, key, agent
  name, session scope. Client-side only: `GET /v1/models` returns one entity name, so discovery is a
  gateway contract. Switching ends the live call; one line at a time is an invariant.
- `[ ]` **The commands surface (#226, #227, #252).** A themed sub-UI instead of the stock list, a
  six-verb animation grammar instead of per-command art, and the open question in #252 settled: does
  the app keep full command parity in-app, or lean into the phone-call identity and make hanging up
  and calling back the normal way to start a new session?
- `[ ]` **The instruments (#209, #208, F4, F5, F8).** The performance baseline and behaviour matrix
  recorded per release; GPU contention and the thirty-minute thermal soak measured; a fixed
  ten-utterance verdict script so "this build is better" stops being an impression.

**The bar for the cut:** a stranger installs, connects a gateway, places a call, switches profile
and reads the commands screen without help; speech-end to first audio holds D17's budget on the release APK;
the streaming arm is co-resident or the default is the fallback, with the numbers that decided it;
no truncation report without a named cause; matrix and baseline recorded on both devices.

---

## `0.9.2` — Presence

**The claim:** Presence is the product, not a mode. One selector retired, one name, on by default —
and the conversation behaves: it acknowledges while you speak, it yields when you interrupt, it
resumes where it was, and it does not say a thing nobody asked for.

Plan: [`docs/PLAN-0.9.2.md`](docs/PLAN-0.9.2.md). Milestone label: `milestone/0.9.2`.

- `[ ]` **The selector goes (#222 — D16 completes here).** `presence_enabled`, default on;
  `voice_mode` migrated rather than dropped, so no existing install has its choice reversed. No mode
  label, no degraded-mode pill; the Settings row reports the absence, and only when it is absent.
- `[ ]` **The settings IA and the transcript (#224, #223).** Being, Hearing, Voice and Presence as
  four sections, split by what the user is changing; a Presence transcript that is not empty.
- `[ ]` **First run and the Being's controls (#230, #225).** The onboarding path written against the
  names the app keeps, and fine-grained particle controls with a live preview, on the Being screen
  #224 creates.
- `[ ]` **The aside: a minimal directive and a guard that repairs (#257, #256).** Content is what
  the code polices; format is what it repairs (D20). Every remaining bound cites a documented
  failure, and the measured ones retire.
- `[ ]` **The render budget (#249).** ~2.5 s against a 900 ms gate is a lost race, not an ordering
  bug. Move the gate, shrink the render, or let the beat carry the turn — decided by field numbers.
- `[ ]` **The conversation behaviours (#245, #243, #242).** Backchannels while listening; resume the
  step after an interruption instead of repeating it; classify an overlap — interruption,
  backchannel, third party, background — rather than measuring its level.
- `[ ]` **The instrument and the bar (#217).** A per-session summary from the log: slots offered,
  lines spoken, skips by reason, repeats, render p50/p95, anything that landed after the reply. The
  bar: at Conversational, over a long run, two thirds of offered slots speak, nothing repeats,
  nothing lands after the first text, p95 under three seconds.
- `[ ]` **What the conversation still owes.** F3b's adaptive barge floor and real-room turn-taking;
  F6a's narration into the loop; truncation after a barge in session history.

---

## `1.0` — the general release

1.0 is no longer a declaration release with no features. It carries everything still plotted,
including what was numbered 2.0, and it is cut when the feature set is complete and stable in the
community's hands (D21). There is no date: there is a bar and a conversation (#268).

Plan: [`docs/PLAN-1.0.md`](docs/PLAN-1.0.md). Milestone label: `milestone/1.0`.

- `[ ]` **Continuity (#231).** One conversation moved between surfaces with the same session,
  memory and voice — done twice, on real hardware, without repeating a turn.
- `[ ]` **The household (#234, #46).** Per-person identity and relationships. Speaker ID stops
  being `sev/low` and becomes a security property, because any detected voice currently triggers
  the agent.
- `[ ]` **The store (#165).** F-Droid path B: sherpa-onnx and onnxruntime built from source,
  `Binaries:` + `AllowedAPKSigningKeys`, submission against the released tag, and their rebuild
  reproducing our APK (D15).
- `[ ]` **The gates, re-run on the candidate.** Reproducible build, live suite, behaviour matrix,
  performance baseline, no open severe bug without a written reason.

---

## Future — community-led and unnumbered

Recorded, not scheduled. What moves out of here moves because the community asked for it — that ask
is #268.

- **Entity calls (#232)** — per-event-class routing and a bring-your-own push endpoint, with a
  per-class ceiling and a visible way to stop it.
- **Remote control of the interface (#233)** — an MCP surface and a closed scene vocabulary, so the
  agent can draw meaning rather than free-draw: per-tool grants, a visible control state, an audit
  log, a dead-man switch.
- **Wake word (#45)** — D6 step one needs training compute the House does not have.
- **Watch items (#237, #238)** — prior art worth reading, not dependencies.

---

## Distribution

- `[x]` **GitHub releases + Obtainium.** Signed APKs on every release; nightly pre-releases from
  `testing`. Obtainium tracks the repo directly.
- `[x]` **Project site** — [hermesvox.org](https://hermesvox.org).
- `[ ]` **F-Droid** — the store rail. sherpa-onnx and onnxruntime built from
  source in the recipe (path B in `docs/DECISIONS.md`), with `Binaries:` +
  `AllowedAPKSigningKeys` so users get our signature. Google Play is dropped.

---

## Open questions (`[?]`)

- **Desktop / cross-platform — answered, out of scope (2026-10-19).** The Go core
  was removed in #126, and a desktop renderer would be a new product rather than a
  port. Android only.
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
