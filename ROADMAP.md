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

## The series

**A release exists to give a user something they can feel.** So the unit of a release is not a gate and
not a set — it is **one sentence a user could say after updating**. Work with no user-facing claim
does not get a version number: it rides inside the release whose claim needs it. That is why the
instrument has no release of its own; the release that claims *faster* is the one that has to measure
it.

**What changed on 2026-11-13, twice.** First re-cut put the gates on the calendar and produced nine
releases, two of which (repo truth, the instrument) gave a user nothing. Second cut, below, folds
those in, splits the one risky item that could block a whole release, and states the claim each
release keeps.

| | The claim | Work | Est. |
|---|---|---|---|
| 0.9.2 | *"It sounds better and starts sooner."* | cadence + Supertonic 3 | 5 |
| 0.9.3 | *"It answers faster, without saying anything twice."* | provisional endpoint + amend | 3.5 |
| 0.9.4 | *"I can interrupt it, and the work survives."* | interruption path | 8.5 |
| 0.9.5 | *"It can do one thing for me — after asking."* | one action + consent | 8.5 |
| 0.9.6 | *"It says something while it works."* | the being | 7.5 |
| 0.9.7 | *"It keeps up with me."* | hearing + turn-taking | 7 |
| 0.9.8 | *"It's ready for someone else."* | first run, freeze, stability | 8 |
| 0.10 | *"It speaks French."* | localisation | 9 |

Roughly 57–62 sessions, unchanged. Eight human sittings instead of seven or nine, each judging one
claim.

### 0.9.2 — "It sounds better and starts sooner"

**In:** chunk shaping with a decoupled player (first piece 20–30 characters, then growth to three
times the previous actual length, one clip ahead) — this is the prosody fix, and it is why a reply
stops resetting its intonation every two seconds; per-engine speech settings; the **Supertonic 3**
bundle (`#589`) verified on the bench phone first, which is the "stops swallowing words" half;
semantic endpointing off by default with its dead `DEFAULT_ON`/`KEY` constants deleted;
`#569`'s arms as the input.

**Rides along, no claim:** the **per-turn timeline** (`event=tl`: speech-end, sent, first token, first
sound, last sound, segments, gap p95) — without it the claim cannot be checked, and it is an hour's
work; the two comparator rows it feeds (`app-first-sound` and `app-gap` gated, `brain-first-token`
reported); the **repo tidying** (archive the historical documents, one `DECISIONS.md`, DOC-TRUTH on
current files only); **Wake word removed** from the panel (`#45` — it records enrolment and listens to
nothing); `#202`'s agent-only triage of the nineteen log-flagged candidates.

**Bar:** `app-first-sound` before and after on the same corpus and device, with a verdict; engine
invocations per reply down and quoted; no reply re-read after an amend; twelve paths unchanged;
Supertonic's word loss on the bench corpus at the v3 rate, quoted.

**Human run asks:** does it sound smoother, and does it start sooner?

### 0.9.3 — "It answers faster, without saying anything twice"

**In:** `PROVISIONAL` endpointing that **acts**, with the amend path that extends and re-sends if
speech resumes inside the window. Nothing else. This is one item on purpose: it is a **D45 major**
(it changes when every user's turn is sent) and its failure mode is a double-spoken reply, so it gets
a release where it cannot block anything, and a bar narrow enough to fail honestly.

**Bar:** time-to-commit down on the corpus; zero double-spoken replies across the replay run; the
amend path proven by a recorded interruption mid-utterance; `app-gap` unchanged.

**Human run asks:** does it feel quicker to answer — and did it ever say the same thing twice?

### 0.9.4 — "I can interrupt it, and the work survives"

**In:** the order — cut audio locally, classify with `Overlap`, then decide; **steer the live run**
instead of posting `/v1/runs/{id}/stop` (a stopped run cannot be steered, so there is no step to
resume); a surface for `run.steered`; stop only on an explicit abort or hush, or when a steer is
unacknowledged within one reply; transcript-similarity rejection of self-echo; pause-not-stop playback
so a false trip resumes mid-word; the adaptive bleed floor and a pre-roll long enough to keep the
first syllable. **D45 major** — it changes what a barge does for everyone.

**Also here, because this release is what needs them:** the **recorded-utterance replay corpus** and
**success sampling** (`#596`) — barge behaviour cannot be judged by ear alone, and the corpus is what
makes it a measurement.

**Bar:** on the corpus — a backchannel does not cancel the reply; an interruption steers and is
acknowledged; a deliberate abort stops; no double speech after a resume; `app-gap` unchanged.

**Human run asks:** interrupt it mid-answer, three ways — a cough, an "mm-hm", and a real change of
mind — and say which of the three behaved correctly.

### 0.9.5 — "It can do one thing for me — after asking"

**In:** `#578` first (a setup sheet may not read *Connected* while the registered entry points
elsewhere); Phone tools **tier 1 only** — the timer, as the single demonstrated action, with the
remaining tiers not built rather than gated; `#376` approvals answered by touch and by voice, timing
out to *no*, with the **locked-device refusal verified for gateway approvals**; the **clarify
surface** (`clarify.request` currently arrives mid-run and is ignored); the last N turns persisted
read-only, marked unchecked.

**Also here, one sitting:** the device passes that are floor work but not instrument work — `#404`'s
runs route, `#281`'s operations line, and the retest batch `#538`, `#496`, `#373`, `#374`.

**Bar:** a timer set by voice end to end with its approval line quoted; a refusal honoured; a clarify
answered; a locked-device refusal; all inside one recorded session on the default path.

**Human run asks:** set a timer by voice, refuse one, and lock the phone and try again.

### 0.9.6 — "It says something while it works"

**Sets out:** Presence.

**First, the design review** — `#562` folded into it rather than run beside it. Five answers in
writing before code: which lines, how many; what triggers each (an event, never a timer); whose voice;
what happens when the catalogue has no line; how the beat appears in `app-first-sound`.

**In:** the **catalogue** as MVP-Presence — roughly two hundred authored stems per profile, rendered
once on the host from `VOX.md`, selected by rules and the gateway's own operations events — with the
2.6 GB renderer kept as an opt-in experiment rather than the default; backchannels; `#217`'s bar
re-pointed at the catalogue; the memory and string claims corrected wherever they describe the model.

**Bar:** a measured beat inside the app-owned budget; `#217`'s bar passing with the catalogue;
nothing repeats; nothing lands after the first text.

**Human run asks:** does the being feel present while it works — and does it ever get in the way?

### 0.9.7 — "It keeps up with me"

**Sets out:** Continuous listening and Turn-taking, together, on **one claim** — the pair serves a
single sentence a user can say, and the pass covers both. *(The earlier rule said a release never
graduates two sets; it is amended here to one **claim** per release, because these two are substrate
and behaviour of the same thing: acknowledgements cannot exist without a continuous lane.)*

**In:** continuous capture graduates on the **capture** property — one recorder per call, no seam, the
first word after a reply not lost — and not on the endpointing claim, which its own corpus shows is
worth nothing; `#228`, `#405`'s re-measured budgets, `#326`; the D38 amendment stands (continuous
listening graduates alone, not tied to Presence). Then turn-taking against a bar written for it:
acknowledgements per long utterance, false-yield rate, resume-without-repeat. Then the
**thirty-minute soak**.

**Bar:** `event=capture-continuous on=true` on every call; the three turn-taking rows with sample
sizes; thirty minutes with no lost turn, no repeated speech, no stuck gate; budgets re-measured off
charge.

**Human run asks:** talk at it for half an hour, including interruptions and long pauses, and say
where it lost the thread.

### 0.9.8 — "It's ready for someone else"

**No new features; no set graduates.** The panel is empty.

**In:** `#230` first run (gateway, microphone, models, a first call, honest when a step cannot be
completed); the copy pass, which by now is the last strings only; `#388` the speech-rate slider;
`#514` per-profile mTLS. Then **fix-only** to a numeric definition of stable:

- twelve regression paths green on the candidate;
- every `APP` latency row inside its bar on **three consecutive cuts**;
- a thirty-minute soak with no lost turn;
- no crash in any recorded session;
- every open issue closed with its evidence, or explicitly *pending* with a card (D47).

**Human run asks:** hand it to the stranger, and say whether they got through the first call unaided.

### Deferred, stated once

**Digital assistant** — launcher integration, not a voice capability, unverifiable without the owner's
handset; it leaves the series and the panel, and returns as a settings row whenever it is wanted.
**Phone tools above tier 1**, **any new alpha set**, **push notifications with no call open** (the only
feature that would justify a dashboard credential) and **a second platform** — each returns only if
every claim above already holds.

## 0.10 — "It speaks French"

**The constraint that makes this release worth having:** it adds **no behaviour to the default path**.
It is the last release of the series and the first allowed to be boring.

**In:** language packs beyond French; plural and gender handling where a language needs it; a
right-to-left pass; the voice-language mismatch warning PierreCsn asked for (a warning, never a
block); speech recognition following the UI locale by default with an explicit override; per-locale
coverage in the gate; `#229` semantic endpointing, which can only graduate with a non-English answer;
and whatever the first translators find wrong with the contract, which is the point of shipping it to
them.

**Not in it:** any new screen, any rewritten sentence, any new setting, any change to interruption,
endpointing or the default voice. If it needs one, it waits for 0.11.

**Human run asks:** use it in French for a day, and say where it broke character.

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
