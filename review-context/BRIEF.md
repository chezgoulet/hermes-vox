# Brief — adversarial review of the 0.9.x roadmap, the issue set, and the code

You are reviewing **Hermes Vox**, an Android voice client for a self-hosted AI agent, for its owner.
You are the challenger, not the summariser. Your job is to find where this plan is wrong before it
costs another four months, and to say what the evidence supports.

## The situation, in one paragraph

The project shipped 0.9.1 too early: two defects reached users, neither caught by a gate that was
green. The owner then wrote down a release process (a human run before a tag, twelve device
regression paths, an evidence rule, a closed tracker), published a governance page, and has just
reset the roadmap from scratch into a seven-release 0.9.x series that is meant to end in a UI/copy
freeze so that localisation (0.10) can proceed. Nothing about that plan has been adversarially
tested. That is this review.

## What you are looking at

- Repository root: this worktree. Detached at **`9fd95bd8`** (`v0.9.1.3`). Nothing here is a branch
  tip that will move under you.
- `git status` shows **uncommitted** files: `ROADMAP.md`, `docs/GOVERNANCE.md`,
  `docs/COMMUNITY.md`, `docs/MILESTONES.md`, `docs/REGRESSION.md`, `docs/RELEASE-PROCESS.md`,
  `docs/REVIEW-0.9.1.md`, `docs/DECISIONS.md` (D41–D46), `docs/INDEX.md`, `docs/templates/`,
  `CONTRIBUTING.md`, `scripts/field-log-turns.py`. **Those are the new plan and the new governance.**
  Read them as the proposal under review, not as settled truth.
- `review-context/ISSUES.md` — the 47 open issues, titles + labels + a 300-character hook. `gh` is
  authenticated here; pull full bodies and comments with `gh issue view <n>` for every issue you
  judge. **Do not judge an issue from the hook alone.**
- `review-context/PLACEMENT.md` — the proposed release assignment per issue.
- Research already in the tree: `docs/RESEARCH-2026-10-voice-agents.md`, `docs/VOICE-BAKEOFF.md`,
  `docs/STT-AB.md`, `docs/PRESENCE.md`, `docs/PRESENCE-TUNING-BAR.md`, `docs/BUDGETS-0.9.1.md`,
  `docs/PRESENCE-COST.md`, `docs/WAKE-WORD-RESEARCH.md`, `docs/INVENTION-MEASUREMENT.md`.
- The app: 246 Kotlin files, ~60k lines, under `android/app/src/main/java/com/hermesvox/`. You cannot
  read it all. Read the subsystems the issues and the roadmap name — `VoiceController`, `SherpaTts`,
  `StreamDrain`, `StreamingStt`/`OfflineStt`, `ContinuousCapture`, `SemanticEndpoint`, `AlphaGate`,
  `PresenceGate`, `Backchannel`, `PhoneMcp`, `CallReadiness`, `StreamRoute`, `ReleaseNotes` — plus
  the specific file each issue cites. Report what you did not read.
- **No device is attached.** You cannot measure anything on a phone. That is expected: a device pass
  is a separate gate. Every claim you make must come from code, docs, committed logs or the issue
  tracker, and you must say when a claim can only be settled by a device.
- Toolchain, if you choose to run anything: `JAVA_HOME=/home/c/jdk-17`,
  `ANDROID_HOME=/home/c/Android/Sdk`. `bash scripts/gate.sh` runs the full gate and takes many
  minutes; a bare `cd android && ./gradlew --no-daemon testDebugUnitTest` records whether the suite
  is green at this sha and is worth one attempt. **Timebox it to 15 minutes**; if the toolchain does
  not resolve, say so and move on. Do not build a release (it needs secrets that are not here).

## Task A — falsify the roadmap

Read `ROADMAP.md` and `docs/GOVERNANCE.md` closely. Then attack.

For **each** of 0.9.2 through 0.10, state:

1. **Probability it completes as scoped** (0.0–1.0) with the reason, and what would raise or lower it.
2. **The dependency that is claimed and is not real** — where the plan says A needs B and the code
   says otherwise.
3. **The dependency that is missing** — where the plan puts two things in different releases and the
   code couples them.
4. **Whether the release can be finished at all** — a release that contains a research question with
   no timebox, or a done-condition nobody wrote, is not a release.
5. **What would falsify your own estimate**, in one line. Be specific enough that someone could go
   and check.

Specifically test these claims, which the plan depends on and which nobody has checked:

- That the single-turn call is the stable floor, and that **with the alpha gate off** every release
  is no less stable than the one before it. Is that measurable with what exists in `scripts/`? Where
  would it break?
- That **continuous listening graduating at 0.9.4** is possible while the gate text says it requires
  Presence (`AlphaGate.kt`). Is the coupling a gate-time convenience or a runtime fact? Find the
  code that decides it.
- That **Presence's work adds no screens**, which is what lets it slip past the freeze. Grep for its
  surfaces. Is the claim true, and if not, which screens would have to be pulled forward?
- That the **twelve regression paths** in `docs/REGRESSION.md` are each witnessable: for every path,
  confirm the `event=` string it names is emitted by the code, and cite the file:line. A path whose
  witness does not exist cannot be walked.
- That promotion is a device pass and nothing else: check whether removing a set from
  `AlphaGate.FeatureSet` is actually sufficient, or whether other code reads the set list.

## Task B — check every open issue against the code

There are 47. For each one, produce a verdict:

- **REAL** — the defect or gap exists as described, with `file:line` and, where the issue quotes a log
  line or a measurement, whether the code can produce that line.
- **ALREADY FIXED** — the behaviour was changed after the issue was filed. Cite the commit or the
  current code and say what makes it fixed.
- **MISDESCRIBED** — the issue is real but its stated cause, mechanism or scope is wrong. Say what is
  true instead.
- **STALE** — it describes a surface that no longer exists (`#429`'s "one tool with six actions"
  against `docs/MCP-SURFACE.md`'s thirteen groups is the example the owner suspects).
- **UNVERIFIABLE WITHOUT A DEVICE** — the code cannot settle it; say what would.
- **BLOCKED ON A PERSON** — waiting on a reporter, with the age of the wait.

Two rules. **Do not take an issue's own diagnosis as fact** — several were written by agents or by the
owner in the middle of a failure, and the 0.9.1 post-mortem found at least one log reading that did
not reproduce. And **grep the whole tree before calling something missing**: schema, event emitters
and string keys live in surprising files.

Then answer the question the owner actually cares about: **is the proposed release placement right?**
For each issue you disagree with, say where it belongs instead and why. If an issue should be closed
rather than scheduled, say that.

## Task C — the research against the code

`docs/RESEARCH-2026-10-voice-agents.md` and the bake-off docs record what excellent voice agents do
(turn detection as a classifier with an eagerness preset, interruption semantics, backchannels,
latency budgets, cascaded vs end-to-end). Take the concrete practices, and for each one say what this
app does today — `file:line`, not adjectives — and what the distance costs a user.

Do not re-research the field. Use the documents in the tree, and where a document contradicts the
code, the code wins and you say so.

The question to answer: **if this app met its own plan, would it be a competent voice agent, or a
well-built app that is not one?** Answer with the specific gaps that decide it.

## Task D — what to do from here

A single ordered list of the work that matters, with:

- effort as a range in working days, marked as an estimate;
- confidence in the estimate, and why;
- the dependency that makes it come in that order;
- for anything that is research rather than engineering: a **timebox and a decision it feeds**, because
  an unbounded investigation in a release is how a milestone becomes a wish.

Include a short section: **the three most expensive mistakes we are about to make**, each with the
evidence it rests on.

## One claim to test, because it is the reason this review exists

`docs/RELEASE-0.9.1.3.md` says of the field log: *"40 replies carried under four seconds of audio and
22 of them ran to 126.8–127.1 s"*. `StreamDrain.kt`'s own comment says *"28 of the 29"*.
`scripts/field-log-turns.py` was written to recount it and reports **12 of 78** from the only surviving
log, which is copied here as `review-context/hermes-vox-merged.log` (the 0.9.1 block starts at line
48957; the script documents the rule and why that boundary is inferable). **Verify or refute the
recount by running it**, and say whether the release note is wrong, the script is wrong, or the two are
counting different things. Also report what the log says about the neighbouring claim in
`StreamDrain.kt` that every reply of four seconds or more drained in under six. This matters more than it looks: the note was written to end exactly this
class of failure, and the project's new evidence rule (`docs/GOVERNANCE.md` §4, G5) exists because of
it.

## Output

Write **one** file at the worktree root: **`REVIEW-0.9.x.md`**. Write it in sections as you finish
them, in this order — do not hold it to the end, and do not stop after task A:

1. **Verdict** — at most 300 words. What is right, what is wrong, what would you do first.
2. **The issues, verified** — the 47-row table (§B), plus every placement you would change.
3. **The roadmap, falsified** — §A, one entry per release, with the probability.
4. **Claims we should stop repeating** — statements in the docs this tree's code does not support.
   Quote the sentence, give the file:line that contradicts it.
5. **The research gap** — §C.
6. **What to do from here** — §D, and the three most expensive mistakes.
7. **What I did not check, and the hypotheses I killed.**

## Rules

- **Read-only on the source.** Do not edit, format or "fix" any file other than `REVIEW-0.9.x.md`.
  Do not commit. Do not push. No pull requests.
- **Every claim carries evidence**: `file:line`, a quoted document line, or an issue number with its
  comment. A claim you could not check is marked `UNVERIFIED` and stays marked.
- **No invented measurements.** There is no device. The only field log available is
  `review-context/hermes-vox-merged.log`, and the only benchmark you can run is the unit suite. If a
  number is needed and neither can produce it, say what would measure it.
- **No praise, no restating, no summary of the documents.** The owner wrote them; he does not need
  them read back. Assume the plan is wrong in at least three places and find them.
- **Adversarial means specific, not hostile.** "This is over-ambitious" is worthless. "0.9.4 cannot
  graduate before Presence because `AlphaGate.kt:102` refuses the set without it, so the stated order
  inverts" is the shape wanted.
- **State every judgement call** you made and the alternative you rejected, at the end. The useful
  part of a review is often the default it chose silently.
- End with a plain answer to one question: **if the owner does exactly this plan, where does it break
  first, and how much of the series survives that?**
