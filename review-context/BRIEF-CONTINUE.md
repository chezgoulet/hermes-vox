# Continuation brief — organise the tracker and the sprints, and fix what is certain

You are continuing the session in which you adversarially reviewed this repository at `9fd95bd8`.
Your report is `REVIEW-0.9.x.md` in this worktree. The owner has read it, and has read your findings
in plain language. He has now given three decisions and one instruction, and this brief is that
instruction.

## What the owner decided today

1. **The fourteen-day and seven-day waiting windows are dead.** He does not want releases waiting on
   people in an agentic setup. Instead there is a **test walkthrough** — a House app that presents him
   with the cards for a given application and records his verdicts — and a **functional suite** that
   the steward runs against the phone. Where a thing cannot be verified before a cut, the release ships
   with the verification marked *pending* and a card queued for the next walkthrough, and says so in
   the release document. Do not encode a number of days anywhere.
2. **The tracker is the owner's least favourite artefact, and he is right about it.** Eleven dead
   issues, seven mislabelled ones, and a backlog of "watch" notes.
3. **The project must be left ready to sprint**, not ready to plan.

## What to do, in this order

### 1. Correct the record (do this first; it is small and it unblocks the rest)

- `scripts/field-log-turns.py`: its docstring defines a reply's length as `fullReply` = milliseconds of
  audio. It is elapsed time from turn launch to the gateway's `done`. Rename the concept ("fast
  gateway", not "short reply"), exclude `turn-dropped` from the `event=turn` match, add the
  characters-per-second proxy you calibrated as the audio estimate, and keep the twelve-turn result
  intact — the numerator was right.
- `docs/RELEASE-0.9.1.3.md` — the sentence at lines 16–18 ("40 replies … 22 of them ran to 126.8–127.1
  s … of the 83 … 9 ran that long"). Replace with what the log supports: twelve turns, 14–50
  characters, 126.8–128.8 s, and a note that the earlier figures did not reproduce.
- `StreamDrain.kt` — the class comment's "28 of the 29" and "every reply of 4 s or more drained in
  under 6 s". Keep the mechanism, correct the counts, and say the long-reply claim is unmeasurable
  from this log.
- `docs/REVIEW-0.9.1.md` — correct the same two errors there (the "12 of 78 short replies" framing and
  the "five long replies ran past two minutes" inference). Leave the rest of the review standing.

### 2. Tracker surgery

Every action below needs an evidence-bearing comment on the issue (`gh issue comment`), quoting the
`file:line` or the log fact that justifies it. Never close silently.

- **Close (11), with reasons:** `#375`, `#428`, `#429`, `#238`, `#237`, `#292`, `#293`, `#373` (on the
  owner's retest — comment that it was fixed by `462c5940` in v0.9.1 and ask for the retest),
  `#374`, `#496`, `#538` (after posting the correction it owes: the RTF attribution and the count).
  Where a close would lose real work, move that work into a named issue first (F-Droid submission out
  of `#428`; Tier-3 screen access out of `#429` into `#446`).
- **Merge:** `#179` into `#569` (same measurement, same A/B — close one, cross-link both), and `#282`
  into `#376` (one device proof, not two).
- **Rewrite** the bodies that describe surfaces which no longer exist or diagnose them wrongly:
  `#404` (the route is the default since v0.9.1; what remains is a device pass), `#281` (built; the
  residual defect is the missing arguments on `tool.started` over the runs route), `#229` (the
  endpointer is default-off until it has a non-English answer), `#421` (route 2 shipped as Phone
  tools; what remains is context-up and the veto), `#374` (a substitution, not an endpoint fault),
  `#268` (rewrite against the current series, not 0.9.2-as-it-was).
- **Every remaining open issue gets** a `## Recommended fix` section: the smallest change that
  resolves it, the file(s) it touches, and how it will be proved (log line, test, or card).
- **Labels:** rename nothing the owner depends on, but add `awaiting-device` and move the seven
  owner-held items onto it. Add `needs-device` only if you find issues that need one and lack it.
  Delete the labels the review calls dead.
- **Milestones:** retag every open issue to the series in `ROADMAP.md`, *as amended by your review's
  rescopes*. Where a rescope changes what the owner wrote (0.9.4 to continuous capture only; 0.9.6
  after `#562`), tag to the rescoped release **and** record the change in `SPRINT-PLAN.md` under
  "changes to the roadmap, for sign-off" rather than silently rewriting `ROADMAP.md`.

### 3. Write the sprint plan

`SPRINT-PLAN.md` at the repo root. For each sprint: the goal in one sentence, the issues in it, the
**bar it will be judged by** (written before the work, not after), what "done" means as evidence, the
exit criteria, and what it unblocks. Sprint 0 is the record and the tracker. Then the floor and its
comparator. Then the device passes on what already ships. Then the three timeboxed benches, each with
the decision it feeds. Then string extraction as a standing lane. Then the releases.

No calendar dates. A sprint ends when its exit criteria are met.

### 4. Land the fixes that are certain and bounded

Code work, each as its own pull request **into `testing`** (branch from `origin/testing`, never from
this detached head; never push to `main`; never force-push):

- `scripts/field-log-turns.py` (from item 1).
- Regression path 2's missing witness: add an `event=lat label=cold` row at first frame, or remove the
  clause from `docs/REGRESSION.md` — your call, but the path must be walkable.
- Path 5: log the expected chunk count beside `chunks=` so "the full chunk count" can be checked.
- `#281`'s residual: carry the tool arguments through `tool.started` on the runs route.
- Whatever else in `REVIEW-0.9.x.md` §6 items 1–2 you can finish inside two hours of work.

Run `bash scripts/gate.sh` before calling any PR ready (`JAVA_HOME=/home/c/jdk-17`,
`ANDROID_HOME=/home/c/Android/Sdk`). **A gate you did not run is not green.** Put the gate's last line
in the PR body.

Do **not** start the bigger code work — no continuous-listening rescope, no model benches, no string
extraction. Those are the sprints, and the owner starts them with you next session.

### 5. Leave a front door

Update `docs/INDEX.md` (and `README.md` if it is wrong) so that a new contributor or a new session
lands on: the roadmap, the sprint plan, the review, and how to pick up work. If a document is now
historical, say so at its head. `ROADMAP.md` must remain the plan of record; `SPRINT-PLAN.md` is how
the next work is sequenced.

## Constraints

- **No invented numbers, no invented results.** If the gate fails, the PR says so.
- **Branch discipline:** `testing` is the integration branch; `main` is releases. Read
  `docs/GIT-FLOW.md` before your first commit.
- **Do not delete** `docs/REVIEW-0.9.1.md`, `docs/GOVERNANCE.md`, `docs/COMMUNITY.md`,
  `ROADMAP.md` or `docs/REVIEW-0.9.x.md`; they are the record.
- Commit the plan documents on a branch named `docs/roadmap-0.9.x` created from the current head, so
  the uncommitted files have a home.
- At the end, write `TRACKER-STATE.md`: what was closed and why, what was rewritten, what was merged,
  what was retagged, which PRs are open and what each proves, and what remains for the owner to decide.

## Output

Finish with a short report in the log: the count of issues closed / rewritten / merged / retagged, the
PRs opened with their state, the gate result per PR, and the three things you would start on Monday.
State every judgement call you made and the alternative you rejected.
