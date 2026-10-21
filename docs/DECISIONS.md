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

## D14 — After 1.0: F-Droid at the 1.0 release, multi-profile in 1.1, wake word in 1.2

Set 2026-10-20.

**F-Droid is submitted with the 1.0 release.** Not before. The recipe and the metadata are
prepared against the tag, and the submission rides the same cut — the app is already
reproducible, which is the part their `Binaries:` flow turns on.

**Multi-profile (#174) is 1.1.** The client-side registry and switcher; discovery from the
gateway stays a separate contract, because `GET /v1/models` returns one entity name.

**Wake word (D6) is 1.2.** Which makes 1.2 the point at which the training question is
answered rather than deferred: the House has no ML-training GPU, so step one ("train Hey
Hermes in our own pipeline") needs rented compute or a decision to drop it.

**A consequence recorded rather than discovered later.** D3 lists *"F-Droid accepted"* as a
1.0 gate. Submitting at the 1.0 release puts acceptance **after** 1.0, because the review
is external and slow. Either the gate is amended to *"submission made, and a rebuild of the
released tag proven to match our APK"*, or the submission moves earlier — 0.9.2. This is a
decision for the 1.0 cut and should be settled before it, not during it.

## D15 — The 1.0 gate for F-Droid is submission, not acceptance

Set 2026-10-20. **Amends D3's gate "F-Droid accepted".**

D14 submits with the 1.0 release, and F-Droid's review is external and slow — so acceptance
would land *after* 1.0. A gate that cannot be closed on the day it is judged is not a gate;
it is a wish with a reviewer attached.

**The 1.0 gate becomes:** the submission is made against the released tag, the recipe builds
sherpa-onnx and onnxruntime from source rather than consuming prebuilt binaries,
`Binaries:` and `AllowedAPKSigningKeys` name our signature, and **a rebuild of the released
tag reproduces our APK** — which is the part of their flow that actually turns on our work.

**Acceptance remains tracked, and its failure is still a defect.** When it lands, the rail
goes live and the app is installable from F-Droid. If F-Droid skips a version because their
rebuild did not match ours, that is not their problem: it falsifies our reproducibility
claim and is treated as a severe bug.

## D16 — There is one product, and presence is a layer on it

Set 2026-10-20. **Retires the two-mode framing of D13.**

"Enhanced Realtime" is not a mode. It is **Presence** — the layer made of the on-device
being, its asides, its cadence, its visual and its voice. Realtime is what the app does;
Presence is what it does *with the layer on*, and **that is the default state**.

**One boolean, not a mode.** `presence_enabled`, default **on**. `voice_mode` is migrated
rather than dropped: a stored `realtime` becomes off, a stored `enhanced` becomes on. An
existing install never has its choice silently reversed, and an install that has never
touched the setting arrives with presence on, which is the point.

**Presence runs only when it can.** The layer needs its model downloaded and that model
able to start. When either is false the app is simply normal operation: no mode label, no
warning pill announcing a degraded mode, no half-state in the UI. The presence row in
Settings says what is missing and why — that is the only place the absence is reported,
because it is the only place a user can act on it.

**Two visible facts replace three states.** Presence is on or off (the user's choice), and
the model is ready or not ready (a fact). The old mode selector asked the user to reason
about both at once, which is why it read as a switch that did nothing.

**Naming inside the layer.** The being's spoken interruptions are currently called
"presence lines", which now reads as "presence's presence". In docs they are **asides**:
Presence is the layer, an aside is a thing it says.

## D17 — Latency is judged against conversational cadence, and the budget is constant

Set 2026-10-20.

Speed is not a goal. Being **within cadence** is the goal, and no faster: a voice that answers
in 300 ms buys nothing over one that answers in 900 ms, and paying accuracy for it would be a
bad trade. This decides where measurement effort goes and, more usefully, where it does not.

**The budget, as the target to hold 0.9.2 to** (to be confirmed against real calls, not
asserted): speech-end to first audio **p50 ≤ 1.5 s, p95 ≤ 2.5 s**. The working assumption is
that a gap under about a second reads as immediate and one over about two and a half seconds
reads as broken — people repeat themselves or stop trusting the turn. If the field disagrees,
the numbers move; the principle does not.

**The property that matters more than the number: the wait must not grow with how long you
spoke.** Today it does. The capture loop transcribes after the turn ends, so with the
streaming arm at RTF ≈ 0.70 the decode tail is roughly 0.7 × the utterance: about 2.1 s after
a 3-second question, 7 s after a ten-second one, 21 s after half a minute. That is not a tuning
problem, it is the wrong shape — and it is why the arm is opt-in.

The fix is not a faster model. It is **overlap**: decode while the user is still talking, so
the remainder after they stop is one chunk and a flush. A 60-second utterance should then cost
the same tail as a 3-second one, differing by a few hundred milliseconds rather than by a
factor of twenty.

**Therefore 0.9.2's gate is a constant tail, not a lower RTF.** Any change that makes the
product faster without making the tail constant has not addressed this.

## D18 — Cadence, quantified: 700 ms and 1.4 s

Set 2026-10-20. **Amends D17's numbers; the principle is unchanged.**

D17 set p50 ≤ 1.5 s and p95 ≤ 2.5 s on the assumption that under a second reads as immediate
and over two and a half reads as broken. The research checkpoint
(`docs/RESEARCH-2026-10-voice-agents.md` §2) shows that assumption was calibrated to *not
broken* rather than to *natural*: across 831 measured turn transitions with a voice agent, the
agent's mean floor-transfer offset was **1366 ms**, and the authors' judgement is that this sits
in the range of *meaningfully marked* gaps in human conversation — the thing that strips
naturalness. Human turn gaps are commonly 200–300 ms; the ceiling for unmarked silence is about
one second.

**The target is now: speech-end to first audio, p50 ≤ 700 ms, p95 ≤ 1.4 s.** 1.4 s is a bar to
beat, not to meet. The constant-tail property from D17 is unchanged and remains the more
important half: the wait must not grow with how long the user spoke.

**What this forces on the design.** At 700 ms you cannot wait out a silence threshold, then
decode, then think, then synthesise. The endpointer must commit early and be wrong sometimes —
which is what the provisional-endpoint frame is for — TTS must begin before the reply is
complete, and nothing expensive (presence asides included) may compete for the CPU at the
turn boundary.

**If p95 cannot be met on-device, record the gap and the cause.** Widening the target silently
is how a budget stops meaning anything.

## D19 — The guard's premise is an assumption, and it will be replaced by a measurement

Set 2026-10-21.

`ErSoulTurn.opener` says it in one line: *"The Contract is enforced here in code, not trusted to
a 2B: no digits (a number is a fact), no markup, bounded length."* That is a defensible stance
and it is **not a finding**. Nothing in this repo measures whether the presence model invents
numbers or facts at this task. What the field has actually shown is narrower: lines **overshoot
in shape** — ten of the twenty-five refusals in the measured corpus are `too-many-words` — and
the model needed a written prohibition against service-desk phrasing when greeting.

So the bounds stand on two different things, and only one of them is evidence:

- **measured**: length, repetition and envelope are where real lines are lost;
- **assumed**: numbers and world facts would be invented if allowed.

**The assumption gets tested, not trusted, in either direction.** Opening the guard because the
assumption is unproven would repeat the same error inverted. The rule for this project is the
one that rejected Qwen: a bound that matters is set from a distribution, not from a hunch.

**The measurement:** sample the same directive across a set of contexts, many times, and classify
every render — a number not present in the input, a world fact not present in the input, a claim
of an action that did not happen, a repeat, and the length distribution. Rate per class. The
bounds are then set from those rates, and the classes with a rate of zero stop being policed by
a lexical ban.

**One relaxation is admissible without waiting, because it is a narrowing rather than a
loosening:** a number that appears in the client's own directive is *grounded* — the client
vouched for it — and may be spoken. Everything else stays refused. That turns the rule from
"no numbers" into "**no new numbers**", which is the claim the Contract actually makes, and it
generalises the single vouched value in #218.

**Until the measurement exists, no bound is removed on the grounds that it is probably fine.**

## D20 — A minimal directive, and a guard that only blocks what has been seen

Set 2026-10-21. Amends the philosophy behind D19's measurement; the measurement itself still
stands.

The presence guard is the wrong shape. It tries to enforce the whole Contract in code, which
means **every unmeasured worry becomes silence** — and the measured refusals show where that
lands: ten of twenty-five are `too-many-words`, i.e. the code is policing the model's
*formatting*, not its honesty.

So the split changes. **Content is what the code polices. Format is what the code repairs.**

- An eleven-word line is not a lie: cut it at a clause boundary and speak it.
- Markdown, a fence, a title line, a trailing courtesy: strip them. #220 already proved the
  same lesson for VOX.md — repair the envelope, refuse only the content.
- A number the client itself supplied is grounded and may be spoken. A number nobody supplied
  stays refused **until a documented case says otherwise**.
- Claiming a result that does not exist ("I've booked it") stays refused, always. That is the
  one content rule with an asymmetric cost.

**The directive gets minimal, and behavioural.** Five things, and no more:

1. who you are, in your own voice (from VOX.md);
2. what this moment is — a greeting, the caller just spoke, or the mind is working and here is
   what it is doing;
3. never state a result you do not have — nothing is done until the mind says it is done;
4. one line, in your own voice, no longer than a breath;
5. silence is a valid answer, and often the right one.

Shape belongs to rule 4 and is enforced by repair, not by refusal. The old lexical contract
does not survive as a list of rules the model must satisfy; it survives only as the small set
of things that have actually been seen to go wrong.

**Every bound in code must cite a documented failure.** A bound with no citation is a
hypothesis: it is labelled as one in the code, with a note saying what would retire it. The
guard's growth becomes archival — it accumulates only what reality demanded, and the field can
retire what reality stopped demanding.

**The field is the corpus.** Users are the only ones who see the failures this model produces
in a real room. So: an opt-in ring buffer of the last N raw renders alongside the directive
that produced them, exported by an explicit "copy presence log" action — on the device, never
uploaded — and an issue template that asks for the line, the directive, and which of the five
rules it broke. Selection bias is fine for discovering a *class* of failure and useless for a
rate; rates come from the bench in #256.
