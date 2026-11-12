# Sprint plan — how the 0.9.x series is worked

`ROADMAP.md` is the plan of record: what each release is for, and in what order. This document is how
the next work is sequenced to get there. `ROADMAP.md` and this document now agree: the fourteen changes below were signed off on
2026-11-12 and are written into the roadmap, so the sequence is the plan rather than a proposal. The changes come from the 0.9.x review
(`docs/REVIEW-0.9.x.md`) and the first walkthrough (2026-11-12).

**No dates.** A sprint ends when its exit criteria are met, not when time runs out (D42).

**Verification** is the owner's test walkthrough (the House app, card by card) and the functional
suite the steward runs against the phone. What cannot be verified before a sprint's exit ships marked
*pending*, with a card queued (D47).

**Each sprint states its bar before the work starts.** A bar written after the work is a description,
not a bar.

Timeboxes for research are **effort budgets in steward sessions** (one session is one sitting of
focused work), not calendar time. Each budget names the decision it feeds and the default that
applies if the budget runs out.

---

## Sprint 0 — the record and the tracker

**Goal.** Every number in the tree reproduces, and every open issue says where it goes and how it will
be proved.

**Issues.** The record: PR #574. The tracker: every open issue (`TRACKER-STATE.md` lists what changed).
The walkthrough failures: #578, #579, #376.

**Bar, written before the work.**

1. The quoted 0.9.1 field-log counts are re-derived by `scripts/field-log-turns.py`, and its
   definitions are pinned by `scripts/test_field_log_turns.py` in the gate.
2. Every open issue carries exactly one `milestone/*` label and a `## Recommended fix` section.
3. No owner-held issue carries `awaiting-reporter`.
4. No document encodes a waiting window in days (D47).

**Done, as evidence.**

- `GATE-GREEN` on #574.
- The three queries in `TRACKER-STATE.md` §6 return empty lists.
- `grep` finds no day-window in `ROADMAP.md`, `docs/GOVERNANCE.md` or `docs/DECISIONS.md` D41–D47.

**Exit criteria.** The owner has reviewed and merged #574 and the `docs/roadmap-0.9.x` pull request, and
has decided the items in "Changes to the roadmap, for sign-off".

**Unblocks.** G5 (the evidence rule) can block a tag without blocking on wrong arithmetic. Every later
sprint starts from a tracker that says what is in it.

**State:** done except the merges and the sign-off.

---

## Sprint 1 — the floor, and something to compare it with

**Goal.** "No less stable than the last release, with the alpha gate off" becomes a command that
exits non-zero, not a sentence.

**Issues.**

- #209: the gate-off, fresh-install row, and a comparator.
- #208: a single-device matrix template.
- #579: profile refresh on the owner's handset. These are regression paths 8 and 9, which already
  failed on the floor.
- PRs #576 (path 2's cold row) and #577 (path 5's chunk count), walked on a device.

**Bar, written before the work.**

1. `scripts/baseline-diff.py` takes two `baseline-report.py --json` outputs and exits non-zero when any
   stated tolerance is crossed. A self-test feeds it a synthetic regression and requires a failure.
2. The gate-off row is taken on a fresh install (the defaults are `whisper-base`, 900 ms partials, and
   800 ms VAD silence) and fills every row of #209 or says "not in log".
3. Paths 2 and 5 are walked:
   - `event=lat label=cold metric=first-frame` beside `am start -W`;
   - `event=tts-retire … full=true` on a long reply.
4. #579 has its `event=profiles-ask` line, and paths 8 and 9 pass, or the unreachable profile is named
   with its cause.
5. `docs/templates/RELEASE-CUT.md` gains the instrument lines: #209 run plus diff, the #208 template,
   F5, and F8.

**Done, as evidence.**

- The two baseline rows (0.9.1.4 and the current `testing`) and the diff's output, in the sprint's
  record.
- Walkthrough verdicts for paths 2, 5, 8 and 9.

**Exit criteria.** The comparator is in the cut checklist and has run once against real rows. Paths 2,
5, 8 and 9 are each pass, or pending with a card and a stated reason.

**Unblocks.** Every graduation after this can be judged against the floor rather than remembered.
#209 and #208 close when they are checklist lines.

**State.** The comparator is in the gate and has run on two real rows — 0.9.1.4 and `testing`
`983f68b` on the bench handset — passing on the eight rows the build owns and refusing a verdict
on the conversational ones. Both rows turned out to be taken with the alpha master **on** (Phone
Tools only) rather than gate-off; the record says so, and the gate-off row is still owed. The record, including what the rows exposed and
what is still pending, is `docs/SPRINT-2026-11-12-the-floor.md`. Paths 4, 5, 8 and 9 are cards on
the owner's handset: each needs a spoken turn or his own device.

---

## Sprint 2 — device passes on what already ships

**Goal.** Prove on a phone the things every install already runs.

**Issues.**

- #404: the runs route, the default since v0.9.1.
- #281: the operations line; built, and keeps its subject on both routes.
- #202: first triage the 19 log-flagged candidates (no device needed), then a card if any survive.
- The retest cards for #373 and #496, both closed as fixed in v0.9.1 with their retests pending.

**Bar, written before the work.**

1. Each check in `docs/RUNS-ROUTE.md` has its quoted line: `route=runs`, a barge that logs
   `event=turn-abandon-done route=runs stopped=true`, a reattach after a dropped connection, and the
   404 fallback.
2. One multi-tool run records the operations line at every step.
3. #202's triage table classifies all 19 candidates.
4. Card T23 passes three consecutive spoken turns on the system engine, and T23-negative refuses the
   call with a named leg.

**Done, as evidence.** The lines quoted in each issue, and the walkthrough verdicts.

**Exit criteria.** #404 and #281 close on their lines. #202 closes with its triage or turns into a
specific defect.

**Unblocks.** 0.9.2 can claim its floor. 0.9.7 shrinks to approvals and the stranger's task.

---

## Sprint 3 — three benches, each with a decision

**Goal.** Answer the three questions that decide the shape of every later release, inside a fixed
effort.

| Bench | Budget | Decision it feeds | Default if the budget runs out |
|---|---|---|---|
| **#569**: the recogniser's real-time factor, four arms (Presence off; Presence loaded after the turn; cadence 250 / 500 ms; **gate off, default config**), plus #179's merged asks | two sessions | the cadence continuous capture graduates with, and whether Nemotron is offered beside it | graduate at 250 ms, Nemotron opt-in only |
| **#562**: what renders Presence's lines (keep E2B / Gemma 3 270M or 1B as `.litertlm` / an authored catalogue for most lines) | five sessions; if no `.litertlm` candidate converts within the first two, compare E2B with the catalogue only | 0.9.6's scope, #230's Presence step, the "2.6 GB" strings that must change before the freeze, and #448's premise | keep E2B, with a catalogue for the working lines |
| **#559**: Supertonic's dropped words, on the host (no phone) | one session | Supertonic stays experimental, gets a chunk-boundary fix, or leaves | stays experimental, labelled as such |

**Bar, written before the work.**

1. Each bench's table comes from a committed script, not from prose.
2. Each decision is recorded as a D-number.
3. #569 also lands the locale-safe log format (`Locale.ROOT` at `VoiceController.kt:1281`), so
   `rtf=` parses the same on every phone.

**Done, as evidence.** Three tables and three D-numbers.

**Exit criteria.** All three decided, by evidence or by the stated default.

**Unblocks.** 0.9.4 (#569), 0.9.6 and #230 (#562), and the voice recommendation (#559).

---

## Lane L1 — string extraction, standing, from now until the freeze

**Goal.** By the v0.9.8 tag, no user-visible literal lives outside a string resource. Localisation
(0.10) is then resource work, as the #528 contract says.

**Issues.** #528 (the contract, agreed 2026-11-07). PR #537 (models and mTLS strings, waiting on
review). PR #518 (the French pack, deferred behind the extraction).

**Bar, written before the work.**

1. A gate check counts literals outside resources (`android:text="…"` in layouts; `.text = "…"`,
   `setText("…")`, `setTitle("…")`, `setMessage("…")` and `Toast` text in Kotlin) against a committed
   baseline.
2. It fails if the count rises; each extraction PR lowers it.
3. The review counted at least 234 layout literals and 109 Kotlin literals against 23 resources. The
   check's first run replaces that estimate with its own number.

**Done, as evidence.** The count falling in the gate output, PR by PR. Settings goes first.

**Exit criteria.** The count reaches zero before the v0.9.8 tag.

**Unblocks.** 0.10. It also unblocks #230's strings, which are written once, into resources.

---

## Lane L2 — volunteer-ready, independent of every theme

#388 (speech-rate slider) and #514 (per-profile mTLS) land when they are ready, in any release before
the freeze. Both add a settings row. #514 needs the owner's answer to its design question first; the
recommended answer is in the issue.

---

## The releases

Each release's bar is the sprint output it depends on, plus the standing cut checklist:

- the twelve paths;
- the comparator run against the previous release, gate off;
- the human run;
- the evidence rule;
- a closed tracker.

**0.9.2 — the floor, measured.** Sprints 1–3.
- Issues: #209, #208, #569, #562, #559, #202, #404, #281, #579.
- **Bar:** the comparator row exists; every path is walked or pending with a card; the three benches
  are decided.

**0.9.3 — Phone tools and Digital assistant graduate; Wake word leaves the panel.**
- **First, #578:** the agent dials the phone in the call, and the setup card says which address it
  announced.
- **Bar:** a timer set by voice during a call; one refusal; one D36 handoff (a message the user sends
  themselves); and the D37 uninstall card. Thirteen groups promote, so the pass covers one action per
  tier, not one timer.
- The assistant gesture opens Vox on the owner's handset (a card).
- Wake word's set and row are removed (#45 step 1), along with the false blurb (`AlphaGate.kt:69`).
- Every Phone-tools group defaults Off (`PhoneGrants.kt:18`), so this promotion changes no default and
  is not a major (D45).

**0.9.4 — continuous capture graduates (rescoped).**
- Issues: #228, #405, #326, and F3b's adaptive barge floor (open an issue at sprint start).
- The decoupling (`AlphaGate.kt:101-104` and the blurb at `:51`) is a decision for the owner: it amends
  D38.
- Semantic endpointing stays default-off (#229, now 0.10).
- Promotion turns continuous capture on for every user, so it is a **major under D45**: a design note
  and a call, open until the cut.
- **Bar:**
  - `event=stt-rtf … rtf<1` at Sprint 3's cadence, on a long call with speech in the room;
  - #405's budgets re-measured off charge;
  - the comparator shows no gate-off regression;
  - `event=capture-continuous on=true` on every call.

**0.9.5 — turn-taking graduates.**
- **First, write its bar.** #217 measures Presence, not turn-taking. The bar has three rows, each from
  events that already exist: acknowledgements per long utterance (`event=backchannel fired=…`); false
  yields; and resumes that do not repeat what was heard (`event=resume-mark` / `event=resume-offer`).
  Open an issue for it at sprint start.
- Move the cut-transcript report (`VoiceController.kt:3525`, gated on PRESENCE) onto TURN_TAKING. It
  is the same mechanism as resume, and it would otherwise stay behind Presence for a release.

**0.9.6 — Presence graduates, after #562.**
- Issue: #217 (`presence-session-report.py --bar` passing on a long Conversational run, with the model
  #562 chose).
- Open issues for the pre-rendered stems and F6a when the sprint starts. "Truncation after a barge" is
  already Resume; see 0.9.5.
- **Bar:** `--bar` passes all rows with their sample sizes; the strings in `AlphaGate.kt:40-44`,
  `SettingsHints.kt:71` and `MemoryBudget.kt:65` describe the chosen model.

**0.9.7 — the loop closes.**
- #376: the designed test first, `scripts/approval-harness.py` on a connection of its own with three
  cards, then a real-gateway card.
- #279: a stranger's real task, once, as acceptance.
- **Bar:** the three approval lines per card. #279's run is recorded step by step, and every break is
  filed.
- If the real-gateway card cannot run, the release ships with harness proof and says so (D47).

**0.9.8 — first run and the last words.**
- Issues: #230 (strings written into resources from the start), #388 and #514 if they have not landed,
  and the residue of L1.
- The UI and copy freeze starts at this tag.

**0.10 — localisation.**
- Issue: #528.
- Also #229, semantic endpointing, which can only graduate with a non-English answer.

---

## Adopted 2026-11-12

Signed off by the owner on 2026-11-12 and **written into `ROADMAP.md`**, so these are decisions rather
than proposals. They came from the 0.9.x review (`docs/REVIEW-0.9.x.md`), the first walkthrough
(2026-11-12) and the evidence in each item.

1. **0.9.4 graduates continuous capture only.** Semantic endpointing (#229) stays default-off and moves
   to 0.10: its word list is English, and its own bench shows at most 100 ms of gain
   (`SemanticEndpoint.kt:35-36, 59-74`).
2. **#242 stays future, as the owner ruled on 2026-11-06.** PLACEMENT had moved it to 0.9.4. Its
   acceptance needs two devices and speaker enrolment (#46), which do not exist.
3. **Continuous listening is decoupled from Presence**, which amends **D38** (`DECISIONS.md:1190-1192`),
   not D29. The coupling exists only in the gate (`AlphaGate.kt:101-104`); Presence
   hears per-turn audio (`VoiceController.kt:2460`).
4. **0.9.6 is scoped after #562 decides.** Option 3 (a catalogue) would rebuild the set in the release
   that promotes it, which ROADMAP's own rule forbids.
5. **#217 moves from 0.9.5 to 0.9.6.** 0.9.5 gets a turn-taking bar of its own.
6. **The #404 device pass and #281's acceptance move from 0.9.7 to 0.9.2.** Both are on the default
   path now.
7. **#279 is acceptance at 0.9.7, not a standing instrument.** It needs a stranger and an approval.
8. **#559 moves from 0.9.6 to 0.9.2** as a host bench. It is a voice-leg defect, not Presence.
9. **String extraction is a standing lane (L1), exempt from the one-theme rule.** #528's contract is
   already agreed, so 0.9.2's "contract agreed" item is done.
10. **The instruments (#208, #209) carry `milestone/0.9.2`** until they are checklist lines, then
    close. ROADMAP says "no milestone", but its own "every open issue is in a release or in future"
    rule needs one.
11. **#208 is a single-device matrix** (D29's ruling of 2026-11-03).
12. **#326 moves from 0.9.8 to 0.9.4**, next to F3b. Both move the barge and VAD thresholds.
13. **0.9.3's Phone-tools pass covers one action per tier**, not a timer and a refusal, and waits for
    #578.
14. **A promotion that turns a default on for every user is a D45 major** (0.9.4 certainly; 0.9.5 and
    0.9.6 if their defaults are on). Under D47 that is a note and a call open until the cut, with no
    fixed window.
