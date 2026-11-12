# Roadmap — the 0.9.x series

**Set 2026-11-11.** This replaces the previous roadmap entirely. That document,
`PLAN-0.9.1.md`, `PLAN-0.9.2.md`, `PLAN-0.9.x-to-1.0.md` and `PLAN-1.0.md` are records of how the
plan got here. This is the plan. `docs/MILESTONES.md` holds what a version number means, what the alpha
gate contains, and what a label means; it does not hold a sequence, because two documents holding one
sequence is how they came to disagree.

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

## The three stability moves, in order

1. **Measure before changing anything.** `#208` (behaviour matrix), `#209` (performance baseline),
   `#279` (a stranger's real task), F4 (GPU contention), F5 (the thermal soak) and F8 (the ten-utterance
   verdict) are what tell *stable* from *working*. Three of them are parked in a feature milestone and
   not running; they move into the cut checklist in the first release, so every release after it can be
   compared rather than remembered.
2. **Stabilise the substrate before the behaviour.** Continuous listening is what every conversation
   feature stands on. It is built behind switches and has never been measured on a long call.
3. **Promote in order of readiness, not ambition.** The two cheapest sets leave the gate first, because
   a promotion ritual that works is worth more to a community than a set that ships half-alive — and
   proving the ritual before the risky sets need it is itself a stability move.

## How a set leaves the gate

A set is **stabilised** by being measured with the instruments and judged against its own bar, and
**promoted** by a device pass: the set is removed from `AlphaGate.FeatureSet`, its settings stop being
dimmed, the app skill and the docs lose their "behind the gate" language, and the pass is recorded in
the release document. A release never promotes and rebuilds the same set: unless the promotion changes
no behaviour, it is unproven by construction.

The panel shrinks as the series goes — **six sets today, three after 0.9.3, two after 0.9.4, one after
0.9.5, none after 0.9.6** — and when it is empty there is no gate, because there is nothing behind it.

## The rules every release obeys

From `docs/GOVERNANCE.md`, not negotiable per release:

- **No date.** A release ships when its gates are green. The series is an order, not a calendar.
- **One theme.** A release is named for what it does, and work that does not serve the theme waits.
  This is the rule that would have stopped 0.9.1 carrying a media change inside a profile fix.
- **One human run** of the candidate before the tag, by someone other than the cutter wherever a
  volunteer exists, with whose run it was recorded in the release document.
- **Twelve regression paths**, the closed tracker, and the evidence rule: no claim in a release note
  that was not measured on that build, and no number without the file and the count behind it.
- **No release waits more than seven days on a person.** A report or a retest with no reply in a week
  closes with its limitation recorded and a link to the issue. A release whose scope depends on
  strangers is a release that cannot start.
- **Every release is small enough to finish.** A second theme becomes the next release.

## The series

Each release either **graduates a set** or **closes a gap in the floor**, and says what it closes off.
A release is finished when it removes a question, not when it adds a feature.

### 0.9.2 — The floor, measured

**No set graduates.** This release exists to make "stable" a number.

**In:** the instruments into the cut checklist with their first rows — `#208`, `#209`, `#279`, F4, F5,
F8; `#569` the recogniser's real-time factor on the field device; `#179`'s last two numbers (RTF and
stutter under a live turn); the retest batch `#538`, `#496`, `#373`, `#374`, each closed with its
evidence line or with its limitation under the seven-day rule; `#562`'s bench — a smaller presence
model, or most lines produced without one — because it decides the headroom every later release
assumes; and `#528`'s contract agreed, so string extraction can start in every release after this.

**Closes off:** "we cannot tell stable from working", and "we do not know whether this phone can carry
what we have built".

**Why first:** almost nothing here changes behaviour, so the release's risk is near zero while its
value is the rest of the series. `#569`'s cause may turn out to be `#562` — contention between a
2.6 GB resident model and the recogniser — and the bench answers that in days rather than weeks.

### 0.9.3 — The small sets graduate

**Sets out:** Phone tools, Digital assistant. **Also:** Wake word leaves the panel.

**In:** the device passes that promote Phone tools (a timer set by voice through the agent, and one
refusal) and Digital assistant (the assistant gesture opening Vox on a phone we have); the promotion
itself — gate rows removed, settings un-dimmed, skill and docs updated; and Wake word removed until a
detector exists, because today it records enrolment and listens to nothing (`#45`).

**Closes off:** "alpha" meaning "we have not decided". After this the panel lists **three** sets — the
three that are genuinely unfinished — and a newcomer can read the project's state off one screen.

**Why here:** both sets are independent of Presence and of the hearing leg, so nothing blocks them, and
promoting them exercises the promotion ritual before the sets that need it most. Days of work for the
project's credibility.

### 0.9.4 — Hearing graduates

**Sets out:** Continuous listening.

**In:** `#228` continuous capture, `#229` semantic endpointing with the eagerness preset, `#405` the
memory, thermal and battery budgets re-measured with the lane live, `#242` overlap classification, and
F3b's adaptive barge floor. Then the promotion, with its pass: a long call, partials arriving, a turn
ending on a judgement rather than a silence, and the budget numbers recorded.

**Closes off:** "can the mic stay open and the recogniser keep up, on a phone, for a long call". The cut
after this holds to the re-measured budgets, not to `BUDGETS-0.9.1.md`.

**Why here:** it is the substrate. Every conversation feature below assumes it, so it must be measured
and shipped before turn-taking or the being can be judged at all.

**One decision it needs first.** The gate says continuous listening requires Presence, because
continuous listening is how the being hears (D29). If that coupling stays, this set cannot leave the
gate until Presence does — the least stable work in the project holding the substrate hostage. Two ways
through:

- **Decouple (recommended, for stability).** Continuous listening graduates alone. The dependency is a
  gate-time convenience, not a runtime fact: the lane, the partials and the endpointing work with
  Presence off, and it is those that matter here.
- **Graduate as a pair.** Presence and continuous listening leave together at 0.9.5, honouring D29
  without amending it. The cost is that the substrate waits on the most uncertain piece of work, and
  the series gains a release.

### 0.9.5 — Turn-taking graduates

**Sets out:** Turn-taking.

**In:** the acknowledgement while you speak, the yielding when you interrupt, the resume after — each
judged against `#217`'s bar on a long run: two thirds of offered slots speak, nothing repeats, nothing
lands after the first text, p95 under three seconds. Then the promotion, with its pass.

**Closes off:** "does the being keep up mid-conversation". Panel after this: **one set**.

**Why here:** the cheapest conversation set once the substrate exists, and the one whose quality a user
hears immediately.

### 0.9.6 — The being graduates

**Sets out:** Presence. **Panel after this: empty.**

**In:** the model decision from `#562` built in; the aside's minimal directive and repairing guard
tuned on a long run; backchannels; and `#217`'s bar as its acceptance. Carried here without an issue
number, each needing one opened: **pre-rendered personality stems** (so the opening beat is free rather
than a live render), **F6a** narration into the presence loop, and **truncation after a barge** in
session history.

**Closes off:** "does the being hold a conversation worth having". After this release there is no alpha
gate.

**Why here:** last of the sets, because it is the largest and the most uncertain — and because it is the
one set whose work adds no screens, so if it slips it can slip past the freeze without touching it (see
below).

### 0.9.7 — The loop closes

**No set graduates.** This is the release that makes the product usable by someone who was not taught.

**In:** `#404` the turn route on `/v1/runs`, `#282` approvals on the phone, `#376` the evidence that an
approval reached a phone and was answered, `#281` operations — which tool is running, in plain
language, and what it is acting on — and `#279`'s stranger's real task, run here as acceptance rather
than as an instrument.

**Closes off:** the last **new interaction surface** built from scratch, and the last of the two things
a person needs before letting an agent act: seeing what it does, and being asked first.

**Why here:** it needs the conversation settled (an approval arrives mid-turn) and it creates screens,
so it must follow the sets and precede the freeze.

### 0.9.8 — First run, and the last words

**In:** `#230` first run — gateway, microphone, models, Presence, a first call, honest when a step
cannot be completed; the **copy pass**, which by now is the last strings only, because every release
since 0.9.2 extracted its own as it went; `#388` the speech-rate slider; `#326` audio environment modes
decided in or out; `#514` per-profile mTLS.

**Closes off:** UI and copy construction. Onboarding is the last surface that did not exist; the copy
pass is the last time a sentence may be rewritten freely.

**Why here:** the freeze starts at this tag, so this is the last release where changing the app's shape
or its voice is normal work.

## 0.10 — Localisation

**In:** language packs beyond French; plural and gender handling where a language needs it; a
right-to-left pass; the voice-language mismatch warning PierreCsn asked for (a warning, never a block);
speech recognition following the UI locale by default with an explicit override; per-locale coverage in
the gate; and whatever the first translators find wrong with the contract, which is the point of
shipping it to them.

**Not in it:** any new screen, any rewritten sentence, any new setting. If it needs one, it waits.

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

`#208`, `#209`, `#279`, F4, F5 and F8 run on every release and belong in the cut checklist, not in a
milestone. A milestone label on an instrument hides the fact that it is not running, which is exactly
what happened to the last three.

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
