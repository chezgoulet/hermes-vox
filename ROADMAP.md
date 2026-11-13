# Roadmap — the 0.9.x series

**Set 2026-11-11.** This replaces the previous roadmap entirely. That document,
`PLAN-0.9.1.md`, `PLAN-0.9.2.md`, `PLAN-0.9.x-to-1.0.md` and `PLAN-1.0.md` are records of how the
plan got here. This is the plan. `docs/MILESTONES.md` holds what a version number means, what the alpha
gate contains, and what a label means; it does not hold a sequence, because two documents holding one
sequence is how they came to disagree.

**The order of gates, and what MVP is:** `docs/PLAN-MVP.md` (2026-11-13). It sequences the work and
defines MVP; where this roadmap and that plan disagree, the plan wins and this document is amended in
the same pull request.

## The foundation, identified first

The series is built outward from what is already stable, so it is worth saying exactly what that is.

**The stable thing is the single-turn call.** One press, one question, one answer, in one voice. Every
user path exercises it, both of its defects are closed with witnesses in 0.9.1.3, the readiness gate
refuses to open a call until the mind, the ears and the voice report ready, and the twelve regression
paths in `docs/REGRESSION.md` all run through it. Beside it, **profiles and sessions** are stable: they
left the alpha gate on a device pass (D39) and have not regressed since.

**What is not stable:** the hearing timing (a recogniser slower than real time on the field device),
Presence's cost (2,588 MB, 5.6 s to load, lines of eleven to thirty-one characters), and anything that
assumes continuous audio. Everything unfinished is either on top of the call or beside it. Nothing
unfinished *is* the call.

So one ordering rule sits above the rest:

> **With the alpha gate off, every release is strictly better than the one before it and no less
> stable.**

That is testable — the twelve paths plus the instruments below — and it is what front-loading
stability means in practice. A release that improves an alpha set while making the default path worse
has failed, whatever else it did.

## The three moves, and the order they happen in

*Re-ordered 2026-11-13 after two independent reviews. The old order promoted the two cheapest sets
first and stabilised continuous listening before anything that speaks; both reviewers pointed out that
this graduates features while the call is far from its own cadence target, and schedules the only
perceptible component last.*

1. **Measure before changing anything.** `#208` (behaviour matrix), `#209` (performance baseline), F4
   (GPU contention), F5 (the thermal soak) and F8 (the ten-utterance verdict) are what tell *stable*
   from *working*; they move into the cut checklist in the first release, and the first release also
   adds the two rows that measure the **app's** share of the wait rather than the server's.
2. **Make the call good before any set graduates.** Cadence (0.9.3) and interruption that keeps the
   work (0.9.4) are the product; they ship before anything leaves the gate, because a set promoted on
   a call that stalls is a set promoted on nothing.
3. **Promote when the gate that needed the set lands.** Presence before turn-taking, because nothing
   can take a turn before something is speaking; continuous capture on its capture property, not on an
   endpointing claim its own corpus shows is empty. A promotion ritual that works matters more than
   the order the panel empties.

## How a set leaves the gate

A set is **stabilised** by being measured with the instruments and judged against its own bar, and
**promoted** by a device pass: the set is removed from `AlphaGate.FeatureSet`, its settings stop being
dimmed, the app skill and the docs lose their "behind the gate" language, and the pass is recorded in
the release document.

**One set is the exception, and the reason is written down rather than discovered later.** Presence is
rebuilt as a catalogue *in* the release that promotes it, which the rule above would normally forbid —
a promotion that rebuilds is unproven by construction. The escape is that the rebuild is measured
against `#217`'s bar *and* the app-owned beat row before promotion, so the proof and the rebuild are
the same act and the evidence is a bar rather than a pass of the old implementation. The design review
is that release's first act, so the release is written against the decision rather than the other way
round. No other set gets this treatment.

The panel shrinks as the series goes — **six sets today; four after 0.9.2** (Wake word and Digital
assistant leave it: the first listens to nothing, the second is launcher integration rather than a
voice capability, and both are documented as post-0.10 work rather than carried as alpha); **three
after 0.9.5** (Phone tools — only tier 1 is implemented, so there is nothing above it left to gate);
**two after 0.9.6**; **none after 0.9.7**. When it is empty there is no gate, because there is nothing
behind it.

## The rules every release obeys

From `docs/GOVERNANCE.md`, not negotiable per release:

- **No date.** A release ships when its gates are green. The series is an order, not a calendar.
- **One theme.** A release is named for what it does, and work that does not serve the theme waits.
  This is the rule that would have stopped 0.9.1 carrying a media change inside a profile fix.
- **One human run** of the candidate before the tag, by someone other than the cutter wherever a
  volunteer exists, with whose run it was recorded in the release document.
- **Twelve regression paths**, the closed tracker, and the evidence rule: no claim in a release note
  that was not measured on that build, and no number without the file and the count behind it.
- **No release waits on a person** (D47). What cannot be verified before the cut ships marked
  *pending*, with a card queued for the next test walkthrough and the release document saying so.
  The functional suite the steward runs against the phone, and the owner's walkthrough, are the
  verification; a reporter's confirmation is welcome and never a gate.
- **Every release is small enough to finish.** A second theme becomes the next release.

## The series — rebuilt 2026-11-13

**The unit of a release is one sentence a user could say after updating.** Work with no user-facing
claim does not get a version number; it rides inside the release whose claim needs it. And **there are
no time estimates in this document** — ordering is by readiness, dependency and effort class only.
With agentic execution a duration is a guess wearing a number's clothes, and a wrong guess becomes a
promise.

### The five rules, in priority order

1. **Testing is front-loaded.** The instruments that prove a promotion come before the promotions;
   nothing leaves the gate on evidence that does not exist.
2. **Built things first.** What already exists is promoted, measured and corrected before anything is
   built. **Nothing ready is held back** to keep a milestone tidy.
3. **Then least effort to most.**
4. **No durations.** Effort is a class.
5. **Localisation is a standard, not a release.** Adopted now, applied to every build, and the series
   ends by proving readiness so that 0.10 is translation rather than archaeology.

**Effort classes** (relative size, never duration): **XS** one edit, no design · **S** contained
change with a test · **M** a component or behaviour change with a design note · **L** architectural,
cross-file, a decision and a device pass · **XL** a codebase sweep that ratchets down rather than
finishes in one go.

### M0 — the gate everything is judged by

**No user-visible change. Ships nothing if the instruments fail.** The only milestone that must
precede all others, because every later exit criterion is one of these lines.

| Item | Effort | Work |
|---|---|---|
| Extend `event=turn` with `lastSound`, `segs`, `gapP95`, `route`, and `firstToken→firstSound` | S | finish |
| `app-first-sound` and `app-gap` rows in the comparator | S | build |
| **Success sampling** (`#596`): every fifth call and first-after-install, ten kept, one-tap "send this — it went well" | S | build |
| **Recorded stimulus corpus** replayed through the real pipeline — fixed utterances in three environments (parked, driving, reflective room), stratified so every third run covers each. A stimulus set, **not a conversation** | M | build |
| Thirty-minute soak harness | S | build |
| Repo truth: one `DECISIONS.md`, an archive for the historical files, DOC-TRUTH on current files only, a file count the gate enforces | S | build |
| Correct the four false statements the audit found in this document | XS | fix |

**Exit:** a release document can quote an `app-first-sound` distribution with its build and n; ten
sampled real calls from three installs; a recorded conversation replays reproducibly; the soak runs
and reports; `docs/` is ≤60 current files plus an archive.

**What the bench asserts, and what it refuses to.** Three jobs only: regression mitigation (the turn
completed, the transcript matched its sidecar, the tool fired, nothing dropped) · metrics under
conditions (`app-first-sound`, gaps, recogniser time, memory, thermal, per environment) · and **tool
calls**, which become fully deterministic by driving recorded audio into the app against the fake
gateway that already ships — a timer call, an approval and a result asserted exactly, with no model in
the loop. **It never asserts that a conversation was good**: barge correctness, turn-taking feel,
resume-without-repeat and backchannel timing are human review, always.

**Needs the owner:** the same utterances recorded three ways — parked and silent, driving, and a
reflective room — with consent. Parked is the control, driving is signal-to-noise and the VAD floor,
reflective is reverberation and echo.

### M1 — promote what already works

**Each row is a promotion with a pass, not a build, and any row that passes ships immediately** — the
milestone does not need to empty before a release is cut. Order is by effort.

| Item | What already exists | Effort | Work |
|---|---|---|---|
| Approvals by touch and voice, timeout to *no* | two-state consent, 120 s timeout, tap and voice, audited | XS | promote |
| **Digital assistant** graduates | the role, route and settings row are built | XS | promote |
| Supertonic 3 bundle (`#589`) | ruled in D48; absent from the catalogue | XS | build |
| Semantic endpointing default **off**, with the test that pins `true` updated | the code is live; the document was wrong | XS | fix |
| **Wake word deferred indefinitely** — its panel row and enrolment screen removed so nothing advertises a feature that listens to nothing | `InertWakeWordEngine`; nothing listens | XS | remove |
| `#404` runs route and `#281` operations line | both shipped and on the default path | XS | promote |
| Retest batch `#538`, `#496`, `#373`, `#374` | fixed in code with catchers; retests are cards | XS | measure |
| Phone tools: **27 actions across 13 groups** already implemented | all default off, per-group grants | S | promote |
| Turn-taking: acknowledgements, backchannels, resume | `Backchannel`, `Overlap`, `Resume` built and gated | S | promote |
| Continuous capture graduates on the **capture** property | built, gated; the endpointing claim is empty | M | promote |
| Presence as the being | a **13-stem catalogue with its own selector and isolated player already ships**; the 2.6 GB renderer does not earn its cost | M | promote |

**Exit per item:** a pass recorded, its bar quoted, its gate row removed. **A set that fails its pass
stays gated and gets a card — that is a result, not a failure.**

### M2 — small builds that unblock quality

| Item | Effort | Work |
|---|---|---|
| Error separation: refused key vs transport vs gateway, spoken and shown | S | build |
| Locked-device refusal for **gateway** approvals | S | build |
| `run.steered` surface — the event arrives and is dropped | S | build |
| Clarify surface — `clarify.request` is silently ignored mid-run | S | build |
| Persist the last N turns, read-only, marked unchecked | S | build |
| Per-engine speech settings | S | build |
| `#202` triage of the nineteen log-flagged candidates | XS | measure |

### M3 — how a reply is spoken

| Item | Effort | Work |
|---|---|---|
| Chunk shaping + synthesis ahead: first piece 20–30 chars, growth to 3× the previous actual, one clip rendered while the current plays, player decoupled | M | build |
| `#569` recogniser A/B, four arms, gate off | M | measure |

### M4 — interruption that keeps the work

**A D45 major: it changes what a barge does for every user.** A barge currently posts
`/v1/runs/{id}/stop`, and a stopped run cannot be steered, so the work cannot be resumed by
construction.

| Item | Effort | Work |
|---|---|---|
| Steer instead of stop: cut locally, classify, steer the live run, stop only on an explicit abort | L | build |
| Amend path: commit on `PROVISIONAL`, extend and re-send if speech resumes | L | build |
| Transcript-similarity echo rejection, and true pause-not-stop | M | build |

**Exit — the mechanism from the log, the judgement from the owner.** The harness asserts only what is
mechanical: a steer was sent rather than a stop, `run.steered` came back, the sequence carries no
double-spoken reply, and the run is still alive afterwards. **Whether an interruption was understood
correctly is a human sitting** — a cough, an "mm-hm" and a real change of mind — with no bench verdict
attached to any of them.

### M5 — localisation readiness verified

**Not translation — proof that translation can start.**

**The standard, adopted now:** every user-visible string in a resource, no literals in Kotlin,
layouts or toasts · no sentence built by concatenation — format arguments and plurals · numbers,
dates, durations and units through locale-aware APIs, logs pinned to `Locale.ROOT` · no layout that
only fits English · a **pseudo-locale build in the gate**.

**The standing correction policy:** any file touched for any reason is brought up to that standard in
the same change. Not a sweep, not a lane, not a promise for later — a rule about the code you are
already in.

| Item | Effort | Work |
|---|---|---|
| Literal sweep — ~840 Kotlin sentence literals, 234 layout literals, a 27-line `strings.xml` | XL | build |
| Plurals and gender rules | S | build |
| Right-to-left pass | M | build |
| Voice-language mismatch warning (warn, never block) | S | build |
| Speech recognition follows the UI locale, with an override | S | build |
| Per-locale gate coverage, and a pseudo-locale run of the twelve paths | M | build |

**Exit:** zero literals outside resources as measured by the gate; twelve paths green under a
pseudo-locale; RTL unclipped; one language's strings ready to hand over.

## 0.10 — translation

Packs beyond French, plural and gender, RTL, the contract's first translator feedback, and `#229`
(which needs a non-English answer to graduate). **No behaviour change on the default path.** If
something needs one, it waits for 0.11.

## The owner's reserved right

**Any feature may be altered, re-scoped or deferred indefinitely by the owner, at any point, until he
deems it complete.** A deferral is a scheduling decision, not a failure, and it is not recorded as
one. Wake word is deferred indefinitely on those terms: it stays out of the sequence and off the
panel until there is time to make a detector that works, and its return is the owner's call alone.

## Not in this roadmap

Push notifications with no call open (the only feature that would justify a dashboard credential) ·
a second platform · any new alpha set · word-level highlighting · any dashboard transport work. Each
returns only if everything above is done and the owner asks for it.

## The freeze, stated precisely

From the `v0.9.8` tag, until a decision lifts it:

- **Frozen:** screen layout and navigation; the settings structure; the wording and placement of every
  user-visible string.
- **Not frozen:** colours and themes that add no strings; bug fixes that change no sentence;
  translations; anything behind a string resource; and **behaviour tuning that adds no screen** — which
  is why Presence's conversation work can run beside the freeze rather than in front of it if it is
  still not ready at 0.9.6.
- **The rule:** a change that adds or rewrites a user-visible sentence is a resource change or it is
  refused. One late sentence is one sentence every translator revisits, and it is how a localisation
  effort stalls.

## After 0.10 — unnumbered on purpose

`PLAN-1.0.md`'s rule stands: **no issue carries `milestone/1.0`**, and the community decides the
destination (`#268`). The candidate work sits in `milestone/future` and is not scheduled here: the phone
as a surface (`#445`) with generated UI (`#444`), screen access (`#446`), local vision (`#448`), the MCP
scene vocabulary (`#233`), the household and speaker ID (`#234`, `#46`), routing and push (`#232`),
Android TV (`#442`), the Jean-Luc theme (`#443`), comprehensive phone control (`#429`), the wake-word
detector (`#45`), and `#421` — the mind directing the phone with a veto — the largest single design item
in the repository, which must not be smuggled into a release that is trying to freeze.

## Standing instruments — not release content

`#208` (single-device), `#209`, F4, F5 and F8 run on every release and belong in the cut checklist. Both
open issues carry `milestone/0.9.2` until they are checklist lines and then close: a milestone label on
an instrument hides that it is not running, which is what happened to the last three, while an issue
with no milestone breaks the rule that every open issue is in a release or in `future`.

`#279` is **not** a standing instrument. It needs a stranger, calendar and mail tools, and an approval —
it is acceptance at 0.9.7, run once, with the result recorded.

## The standing extraction lane

String extraction is a **lane, not a theme**: it runs beside every release from 0.9.2 until the freeze,
and it is exempt from the one-theme rule precisely because a rule that forces it into one release would
make the freeze a big-bang. Each release extracts its own strings as it goes; 0.9.8 carries only the
last of them, and 0.10 carries the coverage checks and the first language packs.

## How a contributor plugs in

- **Every open issue is in a release or in `future`, and `future` says why.** An issue with neither is a
  bug in the tracker, not a backlog.
- **A release's issues are its scope.** Work outside the current theme belongs on the next one; say so
  on the issue rather than opening a pull request, and the answer is often "next".
- **Lanes:** hearing (recogniser, capture), conversation (Presence, turn-taking, the aside), surfaces
  (approvals, operations, first run), translation. A lane has no owner; it has a reviewer, and that is
  whoever last landed a change in it.
- **Definition of done, for any issue:** the change, the catcher that would have caught its defect, and
  the evidence line that shows it worked on a device if it touches one.
- **`good first issue` means a real one.** `#388` is the current example: a preference, a slider and a
  settings row, bounded and reviewable.

## What this roadmap does not carry

The previous roadmap's shipped history and its long ER research sections are records, not plans. They
are kept in `docs/PLAN-0.8-accelerator.md`, `docs/DESIGN-enhanced-realtime-voice.md` and
`docs/DECISIONS.md`, and this document does not repeat them.
