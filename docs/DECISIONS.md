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

## D21 — Three releases, then a declaration: 0.9.1 mechanics, 0.9.2 Presence, 1.0 everything else

Set 2026-10-21. **Supersedes the release shape in D14** (multi-profile in 1.1, wake word in 1.2) and
the 0.9.1 / 0.9.2 / 0.9.3 split in `PLAN-0.9.x-to-1.0.md`. D13 is already retired by D16 and is not
reinstated here.

The series was shaped as three small releases and a declaration, each a slice of the same work.
That optimised for shipping often and produced a plan whose shape had to be explained before it
could be followed. The replacement is three releases, each with a claim.

**0.9.1 — mechanics.** The app is fast, correct and navigable with Presence **off**, and every
primitive Presence needs exists, is measured, and carries a bar: capture, recognition, endpointing,
the reply lifecycle under interruption, profiles, the commands surface, first run, the look, and the
instruments. Presence is not required to have a good call; that is the test of the primitives.

**0.9.2 — Presence.** Presence is the product. The two-mode distinction goes, and D16 finishes here:
`presence_enabled`, default on, no selector, no mode label. Asides, backchannels, resume after an
interruption, overlap classification, the render budget, and a tuning bar with a per-session
instrument.

**1.0 — the general release.** Everything else plotted, including what was numbered 2.0: continuity,
the household, the store rail, and the standing gates held at once. It has no date. It is cut when
the features are complete and stable in the community's hands, and the community's answers decide
what "complete" includes.

**Future — unnumbered and community-led.** Entity calls, remote control of the app's interface, the
wake word and the watch items are recorded, not scheduled. They move out of that list when the
community asks for them (#268), not when the plan runs out of numbers.

**The rule that places an issue**, so the next one does not need a conversation:

- a defect that breaks a call or a screen, or a capability the app needs whether or not Presence is
  on → **0.9.1**;
- a behaviour that exists only because Presence is on → **0.9.2**;
- a capability that needs a second surface or a second person → **1.0**;
- something that needs the community to want it first → **future**.

**Milestones are labels in this repo.** There are no GitHub milestones; `milestone/*` labels carry
the release, and the roadmap and plan documents carry the reasoning. Retired labels are deleted
rather than left to mean something else: `milestone/0.9`, `milestone/0.9.3`, `milestone/1.1`,
`milestone/1.2`, `milestone/2.0`, `milestone/settings-ux`.

**Two consequences worth recording.** Multi-profile moves from 1.1 into 0.9.1, because a profile
switcher is plumbing and the app is not excellent without it. Wake word moves from 1.2 to unnumbered,
because it needs training compute and a demonstrated want, in that order.


## D22 — 1.0 moves to the end and carries nothing; the candidate set is unnumbered

Set 2026-10-21. **Amends the placement half of D21.**

D21 gave 1.0 a payload: continuity, the household, the store. That made 1.0 a bucket — the next
number that anything unscheduled could be poured into — and a bucket with a version number reads as a
promise. It also tied F-Droid to it, which is wrong on its own: a store listing is distribution, not
evidence that the feature set is finished, and it can ship with whichever release has a ready recipe.

**1.0 is the horizon, and it is last.** It carries no issues. It is cut when the feature set is
complete and stable in the community's hands, and the only artifact that can tell us that is use —
#268 — rather than a plan running out of items.

**Everything that was tagged 1.0 moves:**

- **#165, F-Droid path B → 0.9.1**, as a rail that runs alongside the release and does not gate its
  cut. If the recipe is not ready, the release still ships.
- **#231 (continuity), #234 (household), #46 (speaker ID) → unnumbered**, with the rest of the
  candidate set, in `PLAN-FUTURE.md`.

**The list is open in both directions.** New features and workflows proposed by people using the app
join the unnumbered list. The task before 1.0 is to work out which of those make the app notably
better, which can wait, and which were a bad idea — and that judgement is the community's evidence,
not our enthusiasm.

**Placement rule, extended.** Numbered work is only what we can justify before 1.0. Everything else
waits for a demonstrated want.

## D23 — Continuity of the call is cut

Set 2026-10-21. **Removes #231 from the unnumbered list.**

The idea was never specified beyond a sentence: start on the phone, finish at a desk. And that
sentence is already true — the agent keeps the session on the server, so another Hermes front end
resumes the conversation with the same history and memory. What remained was a *call* handoff between
surfaces, which had no design: no chosen surface, no handoff protocol, and no answer to two surfaces
racing one session.

Cutting it is the honest outcome. A future list that keeps an underspecified idea keeps a promise
nobody can evaluate.

**Reopen only on a concrete proposal:** a named surface, what the handoff carries (voice, being,
in-flight turn), and who is the writer. Until then, conversation-level resumption — which already
works — is the answer, and it needs no work here.

## D24 — 0.9.1's goal is a closed loop, and the loop is the gate

Set 2026-10-21. Records the direction agreed in the founder interrogation. Supersedes the framing of
0.9.1 as a list of fixes.

Vox is a **voice-first tool set** for fully engaging a Hermes agent. The phone is the shell — ears,
voice, the being, the controls — and the agent is the brain and the hands, on hardware its owner
controls. Vox's own features stay on the device; the agent may use whatever model its owner chose.
Within that slice the aim is the best version of it, and the product refuses to become a command
centre, a dashboard aggregator, or a second desktop client.

**The loop.** Command out, visible work, clean feedback, discovery — closed. 0.9.1's question is no
longer "is the app good with Presence off?" That is a property the release must have, not its goal.

**The gate** is one stranger completing a real multi-step task by voice with no tutorial: the
calendar run (#279) — read the day, decline a meeting, draft, edit by voice, send, clear the time,
check trail conditions. It is a gate, not a demo.

**What the loop needs**, in issues: a state audit (#280), operations visibility (#281), approvals
surfaced on the phone (#282), feedback that closes the command (#283), discovery designed as one
system (#284), and the app skill bundled and enabled so the agent can teach (#285). Approvals are in
0.9.1 because the loop is not closed without them.

**What the release is judged on:** no regressions, and no confusing interface — even if every issue
closes. Speed, reliability and a designed feel are entry requirements; the ergonomics and logical
flow of every screen and setting are part of the work, not polish after it.

**Presence** remains 0.9.2 and waits until 0.9.1 is genuinely good. Its benchmark is Sesame across the
board: turn-taking, emotional attunement, naturalness and latency. 0.9.0.x may carry features when
they are good; the roadmap shows one only after it ships.

**1.0** is stable in strangers' hands and feature-complete enough for general release. No date.

**The community** gives testers, contributors and direction. No promises to any of the three.

## D25 — Three kinds of work: owned, upstream, surfaced

Set 2026-10-21. Names the distinction that kept getting missed.

- **App-owned.** The capability lives in Vox: voice, Presence, the being, the call UI. It is built,
  tested and maintained here.
- **Upstream-owned.** The capability lives in Hermes: memory, tools, the session chain, skills,
  approvals. Vox must not build a parallel version. Conversation continuity is the example — it
  already works because the agent keeps the session.
- **Surfaced.** Upstream-owned, but invisible or clumsy in a voice-first interface. The app's work is
  the affordance — what the user sees and says — plus a small upstream hook so the app can see or
  trigger it. **Undo is the clearest case: Hermes can already undo the last action. Vox does not need
  an undo store; it needs to show what was done and make "undo that" one word.**

**The rule:** before planning a feature, ask which kind it is. Building a surfaced capability from
scratch duplicates the agent, drifts from it, and breaks when the upstream changes. The hook is part
of the work and is small by definition — if it is large, the capability is upstream-owned and belongs
there.

**Why the word matters:** it tells the roadmap where the work is. A surfaced feature is an interface
problem with an upstream dependency, not a subsystem. Approvals and the app skill are the other two
current examples; both are labelled `surfaced`.

## D26 — #283 is closed; the loop's feedback half is operations and approvals

Set 2026-10-21. Corrects the list in D24.

#283 asked the app to show "what changed in the world" and to offer an undo. The owner's judgement: it
does not make sense, and it is closed as nonsense.

- **Undo is upstream (D25).** Hermes can already undo the last action. There is no app subsystem to
  build and no affordance to add beyond what a conversation already does.
- **"What changed in the world" is not the app's to assert.** The app can show which tool ran and what
  the agent reported — that is #281 — and it can show a failure. It cannot verify an external effect.
- **The feedback half of the loop survives as #281 (operations) and #282 (approvals).** Nothing
  replaces #283.

Counted: 0.9.1 is **24 issues**.

## D27 — Presence releases one minute after hangup

Set 2026-10-24, on the memory measurements in docs/BASELINE.md ("Memory", §5).

Presence holds ~2.2 GB of `MemAvailable` while resident, and 0.9.1 returned none of it between
calls: the engine lived until the process died. Unloading on every hangup is measured and wrong —
it charges 5.6–7.7 s to every redial. The delayed arm returns ~1.75 GB when the phone is left
alone and cancels on a new call, so the only cost is paid by a user who dials again after the
window, which is the case a delay exists to exclude.

**The delay is one minute.** A longer window returns the memory later for no benefit; a shorter
one starts charging the reload to a redial. A user either redials promptly or is not coming back.

The KV cache is not part of this decision. Measured against a ~2 GB engine it is 265–351 MB in
total; 4096 is inside the device's noise floor; 2048 halves the soul's usable memory for ~300 MB;
and a cache configuration LiteRT-LM has not seen costs a one-off ~31 s first load, because its
cache is keyed by it. The shipped cache stays 8192.

## D28 — The milestones, re-aligned

Set 2026-11-03. **Supersedes D24's sentence "Presence remains 0.9.2 and waits until 0.9.1 is
genuinely good"**, and the "Not in 0.9.1" list in `docs/PLAN-0.9.1.md` as it stood.

The pre-release architecture review found that the documents described a release that no longer
existed: D24, the plan and the roadmap all placed Presence in 0.9.2, while 0.9.1 was already
shipping the layer — the handoff receipt, the per-profile soul, the cost controls — and the
owner had said publicly that the layer ships in 0.9.1 under the name Presence. A reviewer
reading the documents faithfully reported the fiction back.

**The distinction the documents failed to draw, and the owner's correction to it:** 0.9.1 ships
the **layer and its behaviours**, all behind the Alpha gate; 0.9.2 is where **the gate opens** for
Presence — on by default. (The one feature item this decision also deferred, **per-profile
settings**, has since landed in 0.9.1: a profile carries its own voice, model and look — see
`docs/PRESENCE.md` and `ProfileScope`.)

- `0.9.x` is beta. `0.9.1` stays open and absorbs work until the owner signs it off. `0.9.2` and
  `0.9.3` are the next beta milestones. `1.0` is GA, when the owner says so, with no feature
  bucket attached to it. Version scheme: `(GA major).(features).(point).(fixes)`.
- **One gate for everything unfinished: Enable Alpha Features.** Off on a fresh install. Its
  panel lists **five sets**, each selected on its own: **Presence**, **Continuous listening**,
  **Turn-taking**, **Phone tools** and **Switching profiles**. The settings it covers are dimmed
  and unreachable when it is off.
- **The hardware check advises, never enables.** A cheap RAM/free-memory gate at first run, then
  a real load attempt on the first call that can demote the choice. If the phone cannot carry
  Presence it stays off, with one line saying why and where to turn it on.
- **First run (#230) and the retirement of the mode vocabulary (#222) move into 0.9.1**, because
  a default now has to be decided at first run.
- **Profiles** ship in 0.9.1 and switching must be flawless; **per-profile settings** (a
  profile's own voice, model and look) also landed in 0.9.1 rather than waiting for 0.9.2.
- **The Presence behaviours** — asides and backchannels, resume, overlap classification, the
  tuning bar, the Presence transcript, the invention measurement, and a fresh pass on the
  directive and rails — are **0.9.1 work, behind the Alpha gate**, not deferred.
- "Enhanced Realtime" is retired as vocabulary. **Presence remains the public name**; Alpha
  Features is the gate, not the name.

`docs/MILESTONES.md` is the authoritative map. Where this decision and any older document
disagree, this decision and that document win.

## D29 — The open questions, settled before the rest of 0.9.1 is built

Set 2026-11-03, in a structured interrogation after the milestone re-alignment (D28). Each of
these was open in `docs/0.9.1-SCOPE.md` §13 or blocked a lane; none is left to be discovered in
code review.

**#325 — silence notifications while the app is open.** Build it, as a setting, **off by default**.
Not on by default: the app does not decide on the user's behalf that a call outranks a message.
It is its own switch in Hearing, with **no master gate in front of it**: #325 is not an Alpha set
(the implementation landed after D29 and this is the shape it shipped in, `CallQuiet`).

**#252 — command parity or the phone-call identity.** **Full command parity in-app.** The commands
screen stays the inventory it is; hanging up and calling back is a convenience, not the model.
This settles the question the plan said must not be deferred.

**Continuous capture and semantic endpointing (#228, #229).** Their preferences default **on**, and
both sit behind the Alpha gate's **Continuous listening** set — so "on at the cut" means on for
anyone who enables Alpha and selects that set. The release is gated on the features being tested
in that state.

**The hardware check.** Bar: **8 GB total RAM**, with a free-memory floor at first run. It runs at
**first run for everyone**, and its advice is shown in the Alpha panel when the user opens it — it
never enables anything.

**The route flip (PLAN §10).** **The cut waits** if parity and the device pass are not clean.
Approvals on the real turn path are a gate, not a target.

**#191 — the presence crash.** **Close it**, with the non-reproduction recorded: presence loaded in
35.6 s on `0.9.1-nightly-f8dca26` and survived a 30-minute soak with the full stack resident.

**#208 — the second handset.** **Carry it.** The behaviour matrix stays single-device and says so.

**What signing 0.9.1 means.** The acceptance run passes on a device, by voice, with no regressions,
**and every milestone issue is closed.**

**The presence directive and rails.** **Drop the no-new-numbers rule now** and keep only the
false-action-claim guard. The invention measurement (#256) still runs, as **observation**, so we
know what the unrestricted model does — but it no longer gates the rule.

**The budgets.** Continuous capture ships on, so **battery, thermal and RSS are re-measured with it
on, and the cut holds to the numbers.**

**#165 — the store rail.** **It is 0.9.1 work and the release waits for it**, with the bar at the
app being **listed in F-Droid**: the recipe reproducible, a merge request against a released tag,
and the listing granted. This puts the critical path through their review, which we do not
control — recorded as the deliberate cost of the decision.

**#202 and #208's closure.** Each closes **with its limitation recorded** — #202's cause unproven
because it needs an ear, #208 single-device — and both statements go in the release notes.

**#249 — the render budget.** **Measure and report; it does not gate.**

**#251 — ambient cues.** **In 0.9.1**, built as it is, and it gets an acceptance card.

**The Alpha gate, as a surface.** A master **Alpha features** toggle at the very top of Settings,
off by default. Enabling it presents a **slide-out listing the individual feature sets marked
alpha, with Presence at the top**, so a tester can take one without taking all. It shipped with
**five sets** — Presence, Continuous listening, Turn-taking, Phone tools, Switching profiles —
not the four described while the panel was being designed; the fifth arrived when profile
switching was gated (#402/#436). With the master off, everything alpha is dimmed and unreachable.
It is a deliberate back door for testers, not a buried switch.

**The release notes.** Presence is **announced** — named, described as a layer you turn on through
Alpha, and honest about being unfinished. It is not buried, and it is not oversold.
## D30 — The soul is a companion, not a gatekeeper

Set 2026-11-04, on the evidence of the Presence review (`docs/reviews/PRESENCE-REVIEW-2026-11-04.md`) and
the field report it explains: *"faster, but sometimes no response is returned"*.

**The decision.** The local Presence model no longer answers turns. Every voice turn goes to the mind
immediately. The soul's job on a turn is the **beat** — two to six words, rendered and spoken to fill
the silence while the mind works — and its full answers are kept for the greeting, where no mind turn
competes. The `<<SKIP>>` offer is retired: the mind is never told the voice already answered.

**Why.** The review traced the reporter's silence to four paths, all Presence-only, and the largest
was this one: a soul render without an escalate token counted as *answered*, the mind was invited to
reply `<<SKIP>>`, and an instruction-literal model obliged — so the caller heard a promise and then
nothing. A second path could mark a turn answered before its line was ever played, which produced
total silence with the receipt reading `ok=true`.

**What it costs.** Smalltalk now gets a beat and then the mind's reply instead of one soul line. That
retires the D16-era claim that the soul "answers the short turns", and it is the trade the owner
accepted: a slightly chattier smalltalk in exchange for never swallowing a question.

**What it buys.** 700–900 ms off every substantive turn (the deleted gate), and a turn model that
cannot fail closed. The literature is unambiguous that a hesitation of that length already sits past
the point where a listener reads meaning into a pause.

**What replaces the safety the skip offer pretended to provide.** Delivery is recorded from
*playback*, not from render; `er-handoff` keeps a per-turn receipt (`ok`, `reason`, `soul`,
`askChars`); and the `er-mind-skip` / `er-swallow` events are gone with the mechanism they logged.
A claim that a turn was answered is now a claim about what the caller heard.

**What is not decided here.** Whether the posture is user-selectable — a setting for people who want
the voice to answer more of the small talk — is a separate question, tracked with the other Presence
controls. If it returns, it returns as *policy over what the client does*, never as an instruction
telling a 2B model to stay quiet.

## D31 — The mind is talked to through the API, not through the user's words

Set 2026-11-04, with D30, on the evidence of the Presence review
(`docs/reviews/PRESENCE-REVIEW-2026-11-04.md`, F4 and §5.3).

**The decision.** A voice turn sends the mind its framing in the request's **ephemeral
`instructions`** — the voice register, the soul-sync, and what the phone is doing — and a per-request
**reasoning effort** (`model_options.reasoning`, defaulting to *low*). The caller's utterance is sent
byte-exact as `input`. The anti-tool clause is deleted.

**Why.** Three faults in one string. The voice prefix was *inside the user's message*, so it entered
the agent's stored session history, was replayed through `previous_response_id`, was visible to
memory and session search, and grew the context by about a hundred tokens a turn — the agent
remembered a protocol instead of a conversation. It told the mind **not to use its tools** ("Answer
from what you know; do NOT do exhaustive tool work…"), which is exactly the clause that turns "what's
a used 4090 going for" into a stale guess or a refusal, and made Presence measurably worse than
Realtime — same agent, same gateway — on the questions people actually ask a voice assistant. And
parts of it were simply false by 0.6.7 (the app no longer says "let me think" before it answers).

**What it rests on.** The gateway already supports both fields on `/v1/responses` and `/v1/runs`:
`instructions` maps to `ephemeral_system_prompt`, which is appended to the system prompt at API time
and never written to the transcript (`api_server_response_admissions.py`), and `model_options` is
decoded per request (`_runtime_options_from_model_options`, `_request_reasoning_config`). No gateway
change, no new endpoint, no model change.

**The two risks, stated.** *Inheritance:* a chained request that omits `instructions` inherits the
previous turn's value, so **every** request sends the field — empty on typed turns, which clears it.
*Providers that ignore reasoning* simply behave as today; the setting is an optimisation, never a
correctness requirement.

**What it commits us to.** Protocol text never rides the user's message again. When the phone needs
to tell the mind something (the register, the soul's lines, the phone's state, what the phone just
did), it goes in `instructions`. And the mind keeps every capability it has: a voice turn is not a
lesser turn.

## D32 — The phone takes four actions by itself, and the mind is told what they were

Set 2026-11-04, on #421 (Presence's tool use, steps 1 and 2).

**Superseded 2026-11-05 by D38.** The local grammar lane below was removed: the phone no longer
decides what an utterance means, and every voice turn goes to the mind. The entry is kept as it was
written, including the reason the fast path seemed right at the time.

**The decision.** Presence gets a **local-action lane** for the handful of things that must be
instant — **timer, alarm, do-not-disturb, volume** — matched by a deterministic grammar, executed on
the phone with **no network round trip**, and recorded in a small registry that the voice turn's
`instructions` report to the mind (`presence: … / local_actions: … / device: …`). Everything the
grammar does not recognise falls through to the mind unchanged.

**Why a grammar and not a model.** The value of the path is that it is already done when a streamed
turn would still be connecting. A 2B model's judgement would put a render (1–3 s) in front of the
fastest thing in the app, and the tool-use lane it would need is the one we are deliberately not
building yet. This is a command syntax, not a language judgement — the inversion of the reason the
174-substring classifier was deleted: that was a *classification* problem, and this is not.

**The bounds, which are the decision.**
- **Tier 0 only**: reversible, private, nothing outward-facing. No message, no call, no purchase, no
  app launch. A later lane can add approval-gated actions; this one never needs one.
- **User-initiated only**: nothing fires on a timer or on the agent's initiative.
- **The confirmation must be true.** A failure says so; a missing permission is named as a missing
  permission. The false-action-claim guard's premise applies to the phone's own claims first.
- **English only, for now.** Any letter outside A–Z falls through, so no other language is half-parsed.
- **The permission answer is part of the decision.** Timers and alarms use the `OnAlarmListener` form
  of the exact alarm API, which needs **no** special access; the trade is that they ring while the
  app's process is alive (it is, during a call). Do Not Disturb needs the user's policy access, which
  the app does not ask for on the user's behalf — so a caller without it is told the truth instead.
  An alarm that lives in the system Clock app would need an outward-facing intent and a confirmation
  the phone cannot verify, and is not this lane.

**What it commits us to.** The presence context in `instructions` is the contract between the two
halves: anything the phone does to itself is reported there, so the mind never contradicts the phone
or asks for what it already knows. MCP tools and the veto tiers (#421's later steps) stay out of this
lane; they are the mind's tools, not the phone's.

## D33 — Refinement belongs to the milestone that commits the feature

Set 2026-11-04, after the owner corrected a deferral: Presence's tool use had been written up as
0.9.2 work, and it is not.

**The decision.** When a milestone commits to a feature, the **deepest refinement of that feature
belongs to that milestone**. A feature is not finished when its first working version lands.
Deciding what it actually *is* — its design, its edges, the shape a careful review would give it —
is the work the milestone promised. Deferring that to the next number is how a feature ships as a
sketch and stays one.

**What this does not mean.** Polish of things already shipped still gets no number: the rough edge
found in use is fixed when it is found, and it does not wait for a milestone to be planned. The
distinction is between *deciding a feature* and *improving a shipped one*.

**Consequence for 0.9.1.** Presence's tool use — the mind's ongoing context of what the phone is
doing, the phone's own capabilities executed instantly and reported as context, the mind's
direction of those capabilities with results, and its veto — is 0.9.1 scope, with a build order:
context block, local intent grammar, MCP server, tiers and veto. It sits in `MILESTONES.md` and
`0.9.1-SCOPE.md` alongside the other Presence work rather than after it.

**Why it matters beyond this release.** A milestone whose features are only "built" is a version
number, not a commitment. The rule exists so that the depth of thinking we do is the depth we
promised at the moment we promised it.

## D34 — The mind acts on the phone through one MCP tool, and every action returns a result

Set 2026-11-04, on #421's third comment and steps 3–4.

**Amended 2026-11-05 by D38.** The user-initiated lane D34 kept separate is gone, so its "why the
two initiators stay separate" argument no longer describes the code. The MCP tool, its result and
its grants stand; it is now the only way the phone acts on the owner's words. The rest of this entry
is kept as written.

**The decision.** The phone runs a small **MCP server on the tailnet** exposing **one** tool,
`phone`, with an action enum (`timer`, `alarm`, `dnd`, `volume`, `note`, `undo`, `status`). Every call
returns what actually happened; Tier 2 actions are refused and routed to the gateway's existing
approval surface. The user-initiated lane (D32) is untouched: it still executes on the phone with no
round trip.

**Why MCP and not a directive in the reply.** A directive is fire-and-forget: the mind says "timer,
five minutes" and never learns whether it started. #421 rejected that shape. MCP gives the mind a
schema, a result and an error surface, and Hermes already speaks it — nothing is patched, no protocol
is invented.

**Why one tool and not eight.** The schema rides on every turn the gateway offers it on. One tool
with a required `action` costs a fraction of eight and is easier for a model to call correctly.

**Why the two initiators stay separate.** A timer the caller asked for must be running before a
streamed turn could have opened its connection; routing it through MCP would add a network hop to the
fastest thing in the app. A timer the mind asked for must return a result, because the mind is the one
acting. Same engine, same registry, different wire — and the registry is the single source of truth,
so the context block and the `status` action cannot disagree.

**The tiers, as implemented.**
- **Tier 0** (timer, alarm, dnd, volume, status, undo): executes immediately, reversible, private.
- **Tier 1** (note): executes immediately, is reported to the mind on the next turn through the
  presence context, and is undoable.
- **Tier 2** (share, message, call, email, delete, purchase, launch, send): **never executed from the
  phone's MCP surface.** An MCP call carries no run id, so the phone cannot mint an approval card, and
  a second approval surface inside the MCP server is what #421 rules out. The result says
  `approval-required`; the consequential path is the gateway's own approval surface, and
  `trust: untrusted` puts *every* phone call behind it for an owner who wants that.

**Undo is part of the result, not a second protocol.** Every reversible action names its own undo in
its result (`{"action":"undo","id":"timer-3"}`) and the undo lives for the action's lifetime: a timer
until it rings, dnd/volume until superseded or ten minutes, a note for a day.

**The invariant.** `ok:true` is only ever rendered after the platform performed the action, and a
refusal carries a reason and no success fact. A claim in the mind's reply is therefore traceable to a
result it was given, which is the false-action-claim guard applied to the phone's own claims first.

**How it is set up (#421 step 5).** The owner is not handed a YAML block. The app composes one
ordinary turn — server name, tailnet URL, bearer token, tool name, what the phone can and cannot do —
and sends it through the existing session, so the agent edits its own configuration and the change is
visible in the conversation and the sheet. The turn is idempotent by content: an existing `vox` entry
is updated in place, never duplicated. **Connected is not the agent's word**: the phone says it only
after its own server has seen an authenticated MCP `initialize`, and the proof is tied to the token
in force, so rotation clears it. The config block survives as a disclosed manual fallback for an
agent that cannot edit its own config.

**What it costs, stated.** The token and the tailnet are the boundary, and both are the owner's to
keep (`docs/MCP-PHONE.md`). The server is up only while a call is, so the gateway's first call after
a call begins may pay a connect, and with the call down the tool is simply absent. Tool schemas ride
on every turn the server is offered on; `status` exists so the mind asks instead of remembering.

**What it does not decide.** Whether the soul ever gets tools (it does not — its job is the beat,
D30), and whether a user wants a setting to change the tier table. Both stay open.

## D35 — The phone's capabilities are granted group by group, and the schema follows the grant

Set 2026-11-04, on #429 step 1.

**The decision.** The agent's reach into the phone is governed by **twelve capability groups**, each
with exactly one of three states: **Off**, **Ask**, **Allow**. The groups are defined once
(`PhoneGrants`), and the tool schema, the Phone tools screen and the audit all read that one object.
A capability the owner has not granted is not a temptation the model has to resist — it is **absent**
from the advertised `enum`.

**Every group defaults to Off.** A fresh install grants nothing. There is no allow-all switch; the
one bulk shortcut the design permits is *Allow all instant actions*, and it touches Tier 0 groups
alone.

**The three states mean one thing each, for every group.** *Off* refuses. *Ask* raises a card naming
the action and its arguments, answered by tap or by voice, and nothing happens until the owner does.
*Allow* runs the action without asking. Every group offers all three, and a stored state is honoured
as stored — there is no second opinion on read or write. **Allow on a consequential group is a real
permission**: the row says what it means (a Messages row at Allow says Vox may compose a text and
open the messaging app without asking), and the default is Off everywhere. A warning is the
protection; a hidden veto is not.

**The tier describes, it does not prohibit.** Tier 0 is reversible and private and its actions carry
an undo; Tier 1 changes something the owner can see and is reported in Recent actions; Tier 2 is
consequential or private to read and has no undo. The tier decides what a card says and whether an
undo is offered — it does not decide which states are legal.

**Off means invisible; a direct call still gets an answer.** A call that names an Off group's action
returns `ok:false, reason:"not_granted"` — a described refusal, not a transport error — so the model
can say what happened instead of inventing a failure. Ask advertises the action and answers the first
call with `approval-required`, the card, its id and expiry; a second call with that id and identical
arguments executes. The same two-step handshake (`PhoneApproval`) answers every action, so an
advertisement at Ask is a capability the phone can keep. An action that cannot raise a card would be
a defect in the action; the built ones all can, and unbuilt Tier 2 names are never advertised.

**The schema follows the grants, at call time.** `PhoneGrantStore` is read on every `tools/call` and
every `tools/list`; nothing is cached for the life of the session. Revocation therefore takes effect
on the very next call — including mid-call — which is the only revocation that means anything. The
test `PhoneGovernanceTest.revocation_takes_effect_on_the_very_next_call` is the proof, and the device
pass turns a group off while a call is live.

**Every call is visible, refusals included.** *Recent actions* records time, action, summarised
arguments, outcome and an undo where the action carries one. Arguments are summarised, not copied: a
note's text is stored as its length, so the list never carries a message body or a secret. The list is
the record the owner checks the agent's claims against, so a success and a refusal are the same kind
of entry.

**Undo is per action.** The result of a reversible action names its undo id; the agent can call
`undo`, and so can the owner by tapping the row. An undo that did not survive its call says `expired`
rather than pretending.

**The two things we will not build.**
- **Bulk deletion, or "clean out my phone".** Irreversible, ambiguous, and a misheard phrase away
  from damage. Triage is read-only; deletion, when it lands, is one named file on a card.
- **Silent reading of messages or notifications.** Those are approval-tier capabilities with their
  own warnings, and they are absent from the schema until granted. There is no background read and no
  "just this once" that skips the card.

**What it costs, stated.** Nine of the twelve groups have no action in this lane; their grants are
stored and shown, and their schema entries appear only when their capability lands (a planned action
name is never advertised; a direct call is refused as not granted). The screen says which groups have
nothing yet. The grant is not a permission: Android's own runtime and special-access grants still
apply underneath, and the phone still refuses with `reason:"permission"` when one is missing.

**What it does not decide.** Whether an approved Ask can be remembered for a session, whether a group
may be limited to a chosen set of apps (Notifications hints at it), and screen automation — which is
its own decision, with its own indicator and hard off switch.

**Amended 2026-11-05.** D36 split `messages-calls` into `messages` and `calls`, and gave `apps` its
instant `launch`/`open` half. The registry therefore holds **thirteen** groups, not twelve; the
group-by-group rule above is unchanged.

**Amended 2026-11-05 (uniform grants).** The original decision made Allow a Tier 0 word, let Tier 1
at Ask run without asking, and withheld Tier 0 at Ask because it had no card — one word with three
behaviours. The three states now mean the same thing for every group, every action at Ask goes
through the one card handshake, and the tier is kept only as the description of consequence and undo.

## D36 — Messages and calls are handoffs, never sends or placed calls

Set 2026-11-05, on #429's handoff lane. (Numbered D36 because D35 above already records the
group-grant decision; the two are separate and neither replaces the other.)

**The decision.** Three capabilities need **no permission and no approval card**, because each one
hands off to an app the owner already trusts. The old `messages-calls` group splits by consent, not
by convenience:

- **`messages`** (Tier 0, Off by default, Allow offered) — `message` takes `to` and `text`, composes
  an `ACTION_SENDTO` intent with an `smsto:` URI and `sms_body`, and opens the owner's messaging
  app. The result is `ok`, `to`, `chars`, `handed_off:true`. **Vox never sends.**
- **`calls`** (Tier 0, Off by default, Allow offered) — `dial` takes `to` and opens
  `ACTION_DIAL` with a `tel:` URI. The result is `ok`, `to`. **Vox never dials; the owner presses
  call.**
- **`apps`** keeps its safe half instant: `launch` resolves a package or launcher label among
  **launchable** activities only, and `open` resolves a URI with an optional package. Both report
  `package` and `component`, and both enter PiP first while a Vox call is live so the being stays
  visible and stoppable. If PiP is unavailable the launch happens anyway.

**Messages: `ACTION_SENDTO` only; no `SEND_SMS`, ever.** The owner's own messaging app is the
sender. Vox does not become the default SMS app, does not ask for the role, and does not read or
send messages itself. Two-factor codes therefore reach the agent the way the owner already handles
them: copy the code, and let the existing `clipboard` group read it. The rejected alternative —
`SEND_SMS` plus the default-SMS role — would have made Vox an SMS app to gain a permission the
handoff does not need, and would have put a silent send behind a tool call.

**Calls: `ACTION_DIAL` only; no `CALL_PHONE`, ever.** The dialer opens with the number filled in and
the owner makes the call. `CALL_PHONE` was rejected because it places the call itself, needs a
runtime permission, and creates an emergency-number question (what happens when the agent dials 112)
that does not exist here: **we never dial**, so no emergency special case is needed and none is
written. `ACTION_DIAL` also leaves the owner's own dialer, call log and SIM choice intact.

**Dialling ends Vox's own call — deliberately, at the handoff.** If a Vox call is live, the being
says one line, *"dialling — ending our call"*, the call ends through the same teardown the on-screen
hang-up uses, and then the dialer opens. It does **not** wait for the telephony call to connect: by
then audio focus has moved and the teardown would run under a grab. **Ending the call does not end
the conversation** — the thread lives on the gateway, and the next call resumes it. That survival is
what makes ending the call acceptable instead of a lost turn.

**Launching enters PiP first when a call is live.** `launch` and `open` call the existing
[CallWindow] funnel before starting the intent, so the being floats beside the app and Stop still
works. A launch is never failed because the window could not shrink: no PiP feature, a refused
window or an OEM that says no all fall through to starting the app immediately.

**`uninstall` is not in this lane.** It needs the owner's approval card, and it stays
`approval-required` until that card exists on this wire. It is named in the apps group's `planned`
list and is never advertised.

**Refusals are part of the decision.** A group that is Off is `not_granted`; bad arguments are
`invalid-arguments`; and a handoff with nothing to receive it is `no_app` — no `ACTION_SENDTO` app,
no dialer, no launchable activity, no app for the URI. A **contact name** without the Contacts group
is `not_granted` **naming Contacts**, because resolving a name is reading the address book. Contacts
lookup itself is declared, not built, so a granted name still refuses `unavailable` and asks for a
number rather than opening `smsto: Mom`.

**What it costs, stated.** The agent cannot send a text or place a call, even when the owner would
have approved it; it can only put the draft or the number in front of them. A contact name needs a
second group grant. Apps can be opened but not removed. Every handoff is logged and audited like any
other call, and a refusal carries no success fact.

**What it does not decide.** Whether the gateway's approval surface ever gets a card for `uninstall`
or for an SMS send, and whether a future lane reads contacts. Both are separate decisions with their
own consent.

[CallWindow]: ../android/app/src/main/java/com/hermesvox/CallWindow.kt

## D37 — Uninstalling an app is the one phone action with its own approval card

Set 2026-11-05, on the phone-approval lane. D36 settled messages and calls as handoffs and left
`uninstall` `approval-required` until a card existed on this wire. This is that card.

**The decision.** `apps.uninstall` is built, and it is the **only** phone action that asks the owner
directly. Messages and calls stay handoffs; contacts and notifications are reads, so a group grant
is enough. Removal is irreversible and has no app to hand it to, so it gets a card on the phone.

**The protocol is two steps on the same tool.** An MCP call carries no run id, so it cannot park a
host run the way the gateway's `approval.request` does. `uninstall` without `approval` reads the
app's label, raises the card, and returns `ok:false, reason:"approval-required"` with `approval_id`,
`expires_at` and `summary`. The agent then calls **the same action with the same canonical
arguments** plus `approval:"<id>"`. It executes only if the id is unexpired, unburnt, and the action
and its canonical arguments match the card. The whole conversation stays one tool, one result
shape, and one audit.

**Canonicalisation, and why it cannot be confused.** The binding text is the action plus every
argument except `approval`, sorted by key, with string values trimmed and internal whitespace
collapsed; its SHA-256 is stored beside it and both must match. Key order and whitespace therefore
cannot change the identity, and a different package cannot slip through. The package is validated as
a dotted package name before a card is raised: a path or a URI is not a package.

**The card is the existing approval card, not a second one.** One line, in the owner's language:
*“Your agent wants to remove Firefox. Approve?”* — the platform's label, never the tool name. It is
answered by tapping **APPROVE** / **DENY** or by voice through `core/Approval.kt`'s allowlists: the
same yes/no vocabulary, a hedged answer is no answer and re-asks once, and silence at two minutes
is a no. Dismissing it is a deny. It is spoken when a call is live. Because a full-screen dialog
cannot start from the background, an unfocused Vox raises a heads-up notification with the same two
actions, and tapping the body opens Vox. If neither surface can be shown the request fails
`unavailable` — the owner could not be asked, which is not the same as a denial.

**One card at a time, three waiting, two minutes.** A second request queues; the fourth is
`approval_busy`. Expiry burns the id. Refusals are `approval_denied`, `approval_expired`,
`approval_mismatch`, `approval_unknown` and `approval_busy`, and each burns the id: an id is used
once.

**Android's own confirmation still runs, and that is not double gating.** Our card asks *“did you
mean this app?”* — it carries the label, the voice answer and the two-minute window Android's screen
does not know about. The platform then asks *“may this app be removed?”*, the system-level consent
that no app can give on the owner's behalf. The tool reports `platform_confirmation:true`, never
“removed”, because it cannot see the owner's answer on Android's own screen.

**Audit.** Three rows: the request, the decision (`approved` / `denied` / `expired`, and whether by
tap or voice), and the execution. Each names the package and the app's label; the canonical text and
hash are never stored.

**What it costs, stated.** An app can now be removed through the agent, but only after the owner
answers **two** consent screens: Vox's and Android's. If Vox cannot show a card or a notification,
the action is simply unavailable rather than silently denied later.

**What it does not decide.** Whether the gateway's approval surface also gets an `uninstall` card
(the phone's card is enough and does not need a run id), and whether a later lane lets the owner
remember a yes for a session. All are separate decisions.

## D38 — The phone parses nothing; the mind decides, and Presence may look but never act

Set 2026-11-05, on the Presence-scope correction. **Supersedes D32 and the "two initiators stay
separate" argument in D34.**

**The decision.** Every voice turn goes to the mind. The phone keeps no local grammar that matches
the caller's words, and no turn ends on the phone before the mind is asked. When the mind wants the
phone it calls the existing MCP `phone` tool, with the grant and the card that path already
enforces. `LocalActionGrammar`, the interception in `runStreamedTurn`, and the local turn-gate
plumbing that existed only to release a locally-taken turn are deleted. `LocalActions` and its
registry stay: they are the phone's engine and the memory the sheet and a follow-up read.
`LocalActions.run` stays as the owner-initiated entry point to that engine; no voice turn calls it.

**Why.** A second initiation path is a second actor. The owner was talking to one of them and not
the other: "set a timer" matched a phone regex and never reached the mind, whether Presence was on
or off. The mind could not answer a timer request it never saw, and the owner had no way to tell
which decision-maker had heard them. One actor is the point of having one agent.

**What it costs, stated.** A timer now costs a gateway round trip, and it needs the **Phone tools**
set with the timer's capability group granted. With the tool absent or the group Off, the mind
cannot act on the phone; it says what it cannot do instead of the phone inventing a timer. That is
the honest price of one actor, and it is accepted.

**Presence's scope.** Presence is the being: it may read what the phone is doing — the registry,
what is running, the time left, the device facts — because that is what lets it say "your timer has
about four minutes left". It may not set, cancel or change anything. `PhoneReadout` exposes the
registry and nothing else, so a mutating capability is absent from its type, not merely discouraged.

**The gate is two independent sets.** Presence is the being; **Phone tools** is the mind acting on
the phone. Either can be taken without the other, and no dependency is added between them.
**Continuous listening** belongs with Presence because it is how the being hears: it cannot be
selected without Presence, `AlphaGate.allows()` refuses a stored preference from an earlier build,
and turning Presence off leaves it inert with the picker saying why.

**What it does not decide.** Whether a future foreground model ever gets a safe, separate voice
action lane (it would be a new decision and would need its own always-on guarantee), and whether the
gateway's own approval surface changes. Both stay open.

## D39 — Sessions and Profiles leave Alpha after a device pass, not on a build

Set 2026-11-05, alongside D38.

**The decision.** **Sessions** and **Switching profiles** stay behind the Alpha gate for now. They
leave Alpha once a device pass shows conversation switching and profile switching work as intended.
That is the promotion condition: it is a check on the phone, not a version number and not a claim
from the code that the paths exist.

**Why.** Both are host questions answered with the host's own key, and both were lived with only
lightly. D33 puts the depth of a committed feature in the milestone that commits it; this decision
records the door out, so the sets are not promoted by drift or by a release note.
