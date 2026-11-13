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
the release document. A release never promotes and rebuilds the same set: unless the promotion changes
no behaviour, it is unproven by construction.

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

Each release **lands one gate** from `docs/PLAN-MVP.md` and says what question it removes. A release is
finished when it closes its gate, not when it adds a feature. **No release graduates two sets, and no
release graduates a set as its theme** — a set graduates when the gate that needed it lands.

**What changed on 2026-11-13, and why.** Two independent second opinions read this series against the
measured state and both said the same thing: it spends four releases graduating features while the
call is far from its own cadence target, and the only component that makes the wait feel alive
(Presence) is scheduled last. So: the instrument moves into 0.9.2, cadence and interruption get
releases of their own before anything graduates, Presence moves *ahead* of turn-taking (nothing can
be turn-taking before something is speaking), continuous capture moves behind both, and phone tools
ships one tier with consent instead of a set-wide pass. Digital assistant leaves the series.

### 0.9.2 — The floor, instrumented (Gates 0 and 1)

**No set graduates.** This release makes "stable" and "fast" into numbers, and makes the repo honest
enough to cite.

**In — Gate 0, the repo tells the truth:** archive the historical documents (`BUILD-*`,
`CLAUDE-BRIEF-*`, `PLAN-0*`, `HANDOFF-*`, `SPEC-*`, the retired series plans) with one line each in
`docs/ARCHIVE.md`; resolve the two `DECISIONS.md` and the two matrix files; DOC-TRUTH checks current
documents only. Wake word **and Digital assistant** leave the panel here rather than later: the first
records enrolment and listens to nothing (`#45`), the second is launcher integration rather than a
voice capability and cannot be verified without the owner's handset. And `#579`'s per-profile key is
fixed: a 401 is named as a refused key, and the probe uses the profile's own stored key.

**In — Gate 1, the instrument:** the per-turn timeline (`event=tl` with speech-end, sent, first
token, first sound, last sound, segment count, gap p95), the comparator rows it feeds
(`app-first-sound` and `app-gap` gated; `brain-first-token` and `turn-total` reported), **success
sampling** (`#596`: every fifth call and the first after each install, plus a one-tap "send this call
— it went well"), and a **recorded-utterance replay corpus** in the functional suite so a good
conversation can be reproduced on the bench. Existing instruments keep their first rows: `#208` the
single-device matrix, `#209`, F4, F5, F8 with the rewritten ten utterances.

**Also here, because they are floor work and already on the default path:** `#404`'s device pass and
`#281`'s acceptance; the retest batch `#538`, `#496`, `#373`, `#374`; the string-extraction lane
starts and runs standing.

**Moved out:** `#569` (the recogniser's real-time factor) to 0.9.3, where it is an input to cadence;
`#562` (what renders Presence's lines) to 0.9.6, where it *is* the design review's evidence.

**Bar:** one `DECISIONS.md`, `docs/` at ≤60 current files plus an archive, gate green on the cleaned
tree; `app-first-sound` recorded with a verdict on two cuts; ten sampled real calls from three
installs; a replay case that reproduces a stored conversation; every `APP` row quoting its build.

**Closes off:** "we cannot tell stable from working, or fast from slow" — and "our documents describe
a codebase that no longer exists".

### 0.9.3 — The call at cadence (Gate 2)

**No set graduates.** The app's own share of the wait, made small and kept small.

**In:** chunk shaping — first piece 20–30 characters, then growth to three times the previous actual
length, one clip synthesised ahead, a player decoupled from synthesis; per-engine speech settings;
the **Supertonic 3** bundle (`#589`) verified on the bench phone before the catalogue entry changes;
`PROVISIONAL` endpointing that **acts**, with the amend path that extends and re-sends if speech
resumes; semantic endpointing off by default and its dead `DEFAULT_ON`/`KEY` constants deleted rather
than left to mislead; `#569`'s arms re-run as the input to all of it.

**This is a D45 major:** committing on a provisional endpoint changes when every user's turn is sent.
A design note before the candidate, a call open until the cut, dissent recorded (D47: no waiting
window).

**Bar:** `app-first-sound` p50 and p95 before and after, same corpus, same device; no double-spoken
reply after an amend; engine invocations per reply down and quoted; twelve paths unchanged; the
`brain-first-token` row reported and never red.

**Closes off:** "the wait is whatever the server gives us" — the app owns the first sound.

### 0.9.4 — Interruption that keeps the work (Gate 3)

**No set graduates.** The release that turns "interruptible" into "responsive".

**In:** the order, not the sensitivity — cut the audio locally, **classify** with `Overlap`, then
decide; **steer the live run** instead of posting `/v1/runs/{id}/stop`, because a stopped run cannot
be steered and therefore has no step to resume; a surface for `run.steered`; stop only on an explicit
abort or hush, or when a steer is unacknowledged within one reply; transcript-similarity rejection of
self-echo; pause-not-stop playback so a false trip resumes mid-word; the adaptive bleed floor and a
pre-roll long enough to keep the first syllable.

**This is a D45 major:** it changes what a barge does for every user.

**Bar:** on the replay corpus — a backchannel does not cancel the reply; an interruption steers the
run and the agent acknowledges it; a deliberate abort still stops; no double speech after a resume;
`app-gap` unchanged.

**Closes off:** research §4's three questions — resume, address, do not repeat — of which we can
currently answer only the third, and only by luck.

### 0.9.5 — One real action, with consent (Gate 4)

**Sets out:** Phone tools, **tier 1 only** — one action family, the timer, as the single demonstrated
action. The remaining tiers stay gated; this is not a set-wide pass.

**In:** `#578` first (a setup sheet may not read *Connected* while the registered entry points
elsewhere), then the tier-1 pass; `#376` approvals on the phone — a request that arrives, is answered
by touch and by voice, and times out to *no* — with the **locked-device refusal verified for gateway
approvals**, not just phone ones; the **clarify surface** (`clarify.request` currently arrives
mid-run and is silently ignored while the mic is open); persisting the last N turns read-only, marked
unchecked, so an answer that never arrived does not look like silence.

**Bar:** a timer set by voice end to end with its approval line quoted; a refusal honoured; a
clarify answered; a locked-device refusal; all inside one recorded session on the default path.

**Closes off:** "the agent can act" as a claim — it becomes one demonstrated action with consent.

### 0.9.6 — The being, by design (Gate 5)

**Sets out:** Presence.

**First, the design review** — `#562` folded into it rather than run beside it. Five questions in
writing before code: which lines exist and how many; what triggers each (an event, never a timer);
whose voice; what happens when the catalogue has no line; how the beat appears in `app-first-sound`
rather than as a vibe.

**In:** the **catalogue** as MVP-Presence — roughly two hundred authored stems per profile, rendered
once on the host from `VOX.md`, selected by rules and the gateway's own operations events — with the
2.6 GB renderer kept as an opt-in experiment rather than the default; backchannels; `#217`'s bar
re-pointed at the catalogue; the memory and string claims corrected wherever they describe the model.

**Why here, and not last:** the beat is the only component that changes how the wait *feels*, and
D18 forbids the current implementation from competing at the turn boundary anyway. It cannot be
scheduled after the things that depend on something speaking.

**Bar:** a measured beat inside the app-owned budget; `#217`'s bar passing with the catalogue;
nothing repeats; nothing lands after the first text.

**Closes off:** "does the being hold a conversation worth having" — and the 2.6 GB question.

### 0.9.7 — Hearing and turn-taking (order inverted)

**Sets out:** Continuous listening, then Turn-taking.

**In:** continuous capture graduates on the **capture** property — one recorder per call, no seam,
the first word after a reply not lost — and not on the endpointing claim, which its own corpus shows
is worth nothing (`BALANCED` is six false endpoints at the same median as the fixed pause). `#228`,
`#405`'s re-measured budgets, `#326`; the D38 amendment stands (continuous listening graduates alone,
not tied to Presence); **D45 major** as now written. Then turn-taking against a bar written for it:
acknowledgements per long utterance, false-yield rate, resume-without-repeat — all from events that
already exist. Then the **thirty-minute soak**, which is MVP's fourth promise.

**Bar:** `event=capture-continuous on=true` on every call; the three turn-taking rows with sample
sizes; a thirty-minute call with no lost turn, no repeated speech, no stuck gate; the budget numbers
re-measured off charge.

**Closes off:** the last two alpha sets, and "does it survive an hour".

### 0.9.8 — First run, the freeze, and the stability pass

**No set graduates; no new features.** The panel is empty; this release is where the app stops moving
long enough to be called stable.

**In:** `#230` first run (gateway, microphone, models, a first call, honest when a step cannot be
completed); the copy pass, which by now is the last strings only; `#388` the speech-rate slider;
`#514` per-profile mTLS. Then **fix-only**: three consecutive cuts with every MVP promise green and
every `APP` latency row inside its bar, and no open sev/high.

**"Stable" is defined here, numerically:** all twelve regression paths green on the candidate; the
app-owned latency rows inside bar on three consecutive cuts; a thirty-minute soak with no lost turn;
no crash in any recorded session; and every open issue either closed with its evidence or
explicitly *pending* with a card (D47).

**Why here:** the freeze starts at this tag, so this is the last release where changing the app's
shape or its voice is normal work.

### Deferred by MVP, stated once

**Digital assistant** — launcher integration, not a voice capability, unverifiable without the owner's
handset. Leaves the series; it is a settings row and a manifest entry whenever it is wanted.
**Phone tools above tier 1**, **any new alpha set**, **push notifications with no call open** — the
only feature that would justify a dashboard credential — and **a second platform**. Each returns only
if MVP's promises are already met.

## 0.10 — Localisation, on a frozen base

**The constraint that makes this release worth having:** 0.10 adds **no behaviour to the default
path**. It is the last release of the series and the first that is allowed to be boring.

**In:** language packs beyond French; plural and gender handling where a language needs it; a
right-to-left pass; the voice-language mismatch warning PierreCsn asked for (a warning, never a
block); speech recognition following the UI locale by default with an explicit override; per-locale
coverage in the gate; `#229` semantic endpointing, which can only graduate with a non-English answer;
and whatever the first translators find wrong with the contract, which is the point of shipping it to
them.

**Not in it:** any new screen, any rewritten sentence, any new setting, any change to interruption,
endpointing or the default voice. If it needs one, it waits for 0.11.

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
