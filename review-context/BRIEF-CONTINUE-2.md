# Continuation two — finish the tracker and the sprint plan

You hit the account's 5-hour limit part-way through the previous brief. Read this as a resumption, not
a restart: several things are already done, and the list below is what remains.

## What already landed (do not redo it)

- Branch `docs/roadmap-0.9.x` holds three commits: the roadmap, governance and 0.9.1 review as written;
  D47 removing the waiting windows plus the 0.9.1 recount correction; and the watch list moved out of
  the tracker into a document.
- Twelve issues were closed. The tracker went from 47 open to 35.
- Labels `awaiting-device` and `needs-device` now exist.
- Two pull requests are open and both report `GATE-GREEN` from `scripts/gate.sh`:
  **#574** `fix/field-log-record` and **#576** `fix/regression-cold-frame`.
- `REVIEW-0.9.x.md` and `review-context/` are untracked in the worktree.

## What remains

### 1. Rewrite every remaining open issue body

Every one of the 35 open issues needs a `## Recommended fix` section: the smallest change that
resolves it, the file(s) it touches, and how it will be proved (a log line, a test, or a walkthrough
card). None of them have it yet — `#569` is the check.

Then the bodies the review called wrong or stale: `#404` (the route is the default since v0.9.1 — the
flip is done, what remains is a device pass), `#281` (built; the residual defect is the missing tool
arguments on `tool.started` over the runs route), `#229` (default-off until it has a non-English
answer), `#421` (route 2 shipped as Phone tools; what remains is context-up and the veto), `#268`
(rewrite against the current series).

### 2. Retag every open issue to the series

None of the retagging has happened: `#569`, `#562`, `#528`, `#405`, `#404`, `#376`, `#281`, `#279`,
`#217`, `#209`, `#208`, `#230`, `#229`, `#228`, `#559` are still on `milestone/0.10` or `0.9.1`. Use the
series in `ROADMAP.md` as amended by your review's rescopes, and record any rescope in
`SPRINT-PLAN.md` under "changes to the roadmap, for sign-off". Move the seven owner-held items from
`awaiting-reporter` to `awaiting-device`.

### 3. Two documents

- **`SPRINT-PLAN.md`** — the sequence from your review §6, sprint by sprint: goal in one sentence, the
  issues in it, the **bar it will be judged by written before the work**, what "done" means as
  evidence, the exit criteria, and what it unblocks. Sprint 0 is the record and the tracker. No dates.
- **`TRACKER-STATE.md`** — what was closed and why, what was rewritten, merged and retagged, which PRs
  are open and what each proves, and what still needs the owner's decision.

### 4. Front door

Update `docs/INDEX.md` so a new session lands on: roadmap, sprint plan, review, and how to pick up
work. Mark any document that is now historical at its head.

### 5. Commit the review

Commit `REVIEW-0.9.x.md` on `docs/roadmap-0.9.x` — it is the record this work rests on. Do **not**
commit `review-context/` (it is working material and carries a copy of the briefs).

## Constraints, unchanged

Read-only on product code except where a change is already in an open PR. No force-push, nothing to
`main`, no merging your own PRs. No invented numbers. If the gate fails, say so.

## Finish with

The count of issues rewritten and retagged, the two documents written, and the three things to start
on Monday.

### 6. Four failures from the owner's first walkthrough (2026-11-12, build 0.9.1.4)

The owner ran the House walkthrough on his own handset against 0.9.1.4: **16 pass, 4 fail, 1 pending**.
The session record is on the steward's machine (`apps/walkthrough/sessions/vox-20261112-004206.md`);
his words are quoted here because they are the evidence.

1. **Switch profile mid-use → fail.** *"I think this is a problem with our configuration, but I was not
   able to refresh the profiles, at the top it read out one unreachable. Therefore, I could not switch
   between the profiles on my gateway."*
2. **Refresh the profile list → fail.** Same cause; no note of its own.
3. **Phone tools: set a timer by voice → fail.** *"I refreshed the connection and skills under the phone
   tool settings. Then I started to call, but the agent said it was unable to connect to my phone via
   MCP."*
4. **Approvals → fail.** *"was unable to conjure an approval card despite asking it to do something that
   it should have needed for one. I'm not a full confidence for this one, and I wonder if we need to
   devise a specific test for this."*
5. **Phone tools: one refusal → pending**, blocked by 3.

**Host-side diagnosis already done — use it, do not re-derive it.**

- The agent reaches the phone through `mcp.servers.vox.url = http://100.82.156.1:8765/mcp` in the
  House gateway's `config.yaml`. `100.82.156.1` is the **Pixel 7** — the bench handset on sasquatch,
  not the phone the owner holds. Probed at the time of his run: **connection refused** (curl exit 7),
  and the other Android peers refused on 8765 too, so no handset was serving the MCP port at all.
  The port is `PhoneMcpServer.kt:679` (`PORT = 8765`), so the URL is right and the target and the
  liveness are wrong. **This is a House configuration defect, not an app defect** — say so in the issue
  rather than filing it against the phone, and make the recommended fix about the target following the
  device plus the server being up when the agent calls.
- The profile failures need one log line before anyone guesses: `event=profiles-ask … status= seen=`.
  The owner can export the log (that card passed). If the ask returned 200 with `seen>0`, it is the
  app's rendering or the saved URL; if it returned 404/401 or never fired, it is the host or the
  address. Write the issue so that line is the first thing asked for, and do not assume the app.
- The approvals failure is not evidence of a defect: there is no deterministic arm, so "no card
  appeared" cannot distinguish "the gateway never asked" from "the app did not surface it". File it as
  a **testability** gap whose recommended fix is the designed test — a mock gateway that always sends
  an approval request, on a profile of its own — not as a bug.

Give these issues the `walkthrough` provenance in their bodies (build, handset, date, his words), tag
them into the series, and if an existing issue already covers one, fold the evidence into it instead of
opening a second.
