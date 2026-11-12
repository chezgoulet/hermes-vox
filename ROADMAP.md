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
- **No release waits on a person** (D47). What cannot be verified before the cut ships marked
  *pending*, with a card queued for the next test walkthrough and the release document saying so.
  The functional suite the steward runs against the phone, and the owner's walkthrough, are the
  verification; a reporter's confirmation is welcome and never a gate.
- **Every release is small enough to finish.** A second theme becomes the next release.

## The series

Each release either **graduates a set** or **closes a gap in the floor**, and says what it closes off.
A release is finished when it removes a question, not when it adds a feature.

### 0.9.2 — The floor, measured

**No set graduates.** This release exists to make "stable" a number.

**In:** the instruments into the cut checklist with their first rows — `#208` (a **single-device**
matrix, as D29 ruled), `#209`, F4, F5, F8; `#569` the recogniser's real-time factor on the field
device; `#179`'s last two numbers (RTF and stutter under a live turn); the retest batch `#538`, `#496`,
`#373`, `#374`, each closed with its evidence line or marked pending with a walkthrough card (D47);
`#404`'s device pass and `#281`'s acceptance, both on the default path every user already runs;
`#559`'s host bench — does Supertonic stay, or is "experimental" its answer; `#562`'s bench — a smaller
presence model, or most lines produced without one — because it decides the headroom every later
release assumes; and the localisation contract, already agreed, so **string extraction starts here and
runs as a standing lane** (below).

**Closes off:** "we cannot tell stable from working", and "we do not know whether this phone can carry
what we have built".

**Why first:** almost nothing here changes behaviour, so the release's risk is near zero while its
value is the rest of the series. `#569`'s cause may turn out to be `#562` — contention between a
2.6 GB resident model and the recogniser — and the bench answers that in days rather than weeks.

### 0.9.3 — The small sets graduate

**Sets out:** Phone tools, Digital assistant. **Also:** Wake word leaves the panel.

**In:** the device passes that promote Phone tools and Digital assistant; the promotion itself — gate
rows removed, settings un-dimmed, skill and docs updated; and Wake word removed until a detector
exists, because today it records enrolment and listens to nothing (`#45`).

The Phone-tools pass covers **one action per tier** — a Tier-0 timer, the D36 message/call handoff, and
the D37 uninstall card — because thirteen capability groups are not proved by one of them. It **waits
on `#578`**: the setup sheet can read *Connected* while the agent's registered entry points at another
handset, and a set cannot be promoted on a connection nobody has proved.

**Closes off:** "alpha" meaning "we have not decided". After this the panel lists **three** sets — the
three that are genuinely unfinished — and a newcomer can read the project's state off one screen.

**Why here:** both sets are independent of Presence and of the hearing leg, so nothing blocks them, and
promoting them exercises the promotion ritual before the sets that need it most.

### 0.9.4 — Hearing graduates

**Sets out:** Continuous listening — **continuous capture only**.

**In:** `#228` continuous capture, `#405` the memory, thermal and battery budgets re-measured with the
lane live, `#326` audio environment modes (same thresholds, same release), and F3b's adaptive barge
floor. Then the promotion, with its pass: a long call, partials arriving, and the budget numbers
recorded.

**What is not in it, and why.** **`#229` semantic endpointing stays default-off** and moves to 0.10: its
candidate list is English and its own bench shows at most 100 ms of gain over the fixed pause
(`SemanticEndpoint.kt:35-36, 59-74`), so promoting it now would schedule a localisation regression.
**`#242` overlap classification stays future**, as the owner ruled on 2026-11-06 — its acceptance needs
two devices and speaker enrolment (`#46`), neither of which exists.

**Closes off:** "can the mic stay open and the recogniser keep up, on a phone, for a long call". The cut
after this holds to the re-measured budgets, not to `BUDGETS-0.9.1.md`.

**The coupling is amended, signed off 2026-11-12.** The gate refuses continuous listening unless
Presence is selected (D38). Continuous listening graduates **alone**: the dependency is a gate-time
convenience, not a runtime fact — the lane, the partials and the endpointing work with Presence off,
and Presence hears per-turn audio (`VoiceController.kt:2460`) either way. D38 is amended accordingly;
leaving it in place would let the least stable work in the project hold the substrate hostage.

**This is a D45 major, and under D47 that costs a note and a call, not a waiting period.** The promotion
turns continuous capture **on** for every user — a change to a default people rely on — so a design note
is published before the candidate is cut, a consensus call stays open until the cut, and the dissent is
recorded.

### 0.9.5 — Turn-taking graduates

**Sets out:** Turn-taking.

**In:** the acknowledgement while you speak, the yielding when you interrupt, the resume after — judged
against **a bar written for turn-taking itself**, before the work starts: acknowledgement rate per long
utterance, false-yield rate, and resume-without-repeat rate, each from log events that already exist
(`resume-mark`, `resume-offer`, `backchannel`). `#217`'s bar measures Presence's working lines rather
than these, which is why `#217` moved to 0.9.6. Then the promotion, with its pass.

**Closes off:** "does the being keep up mid-conversation". Panel after this: **one set**.

**Why here:** the cheapest conversation set once the substrate exists, and the one whose quality a user
hears immediately.

### 0.9.6 — The being graduates

**Sets out:** Presence. **Panel after this: empty.**

**In:** the model decision from `#562` built in; the aside's minimal directive and repairing guard tuned
on a long run; backchannels; and `#217`'s bar — two thirds of offered slots speak, nothing repeats,
nothing lands after the first text, p95 under three seconds — as its acceptance. Carried here without
an issue number, each needing one opened: **pre-rendered personality stems** (so the opening beat is
free rather than a live render), **F6a** narration into the presence loop, and **truncation after a
barge** in session history.

**Scoped after `#562` decides.** If the answer is a catalogue rather than a model, the set is rebuilt in
the release that promotes it, which this roadmap's own rule forbids — so the decision lands first and the
release is written against it.

**Closes off:** "does the being hold a conversation worth having". After this release there is no alpha
gate.

**Why here:** last of the sets, because it is the largest and the most uncertain — and because it is the
one set whose work adds no screens, so if it slips it can slip past the freeze without touching it.

### 0.9.7 — The loop closes

**No set graduates.** This is the release that makes the product usable by someone who was not taught.

**In:** `#376` approvals on the phone — one device proof of a request that arrives, is answered by touch
and by voice, and times out to *no* — and `#279`'s stranger's real task, run here as **acceptance**
rather than as a standing instrument.

The runs route (`#404`) and the operations line (`#281`) left this release for 0.9.2: both are on the
default path today, so proving them is floor work rather than a milestone.

**Closes off:** the last new interaction surface built from scratch, and the two things a person needs
before letting an agent act: seeing what it does, and being asked first.

**Why here:** it needs the conversation settled (an approval arrives mid-turn) and it creates screens, so
it must follow the sets and precede the freeze.

### 0.9.8 — First run, and the last words

**In:** `#230` first run — gateway, microphone, models, Presence, a first call, honest when a step cannot
be completed; the **copy pass**, which by now is the last strings only, because the extraction lane has
run since 0.9.2; `#388` the speech-rate slider; `#514` per-profile mTLS.

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
