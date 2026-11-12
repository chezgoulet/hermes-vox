# Tracker state — after the 0.9.x pass (2026-11-11/12)

What the tracker pass did, and why. Every action below left an evidence-bearing comment on its issue:
the `file:line` or log fact that justified it. Nothing was closed silently. The plan this serves is
`ROADMAP.md`, sequenced by `SPRINT-PLAN.md`. The evidence behind it is `docs/REVIEW-0.9.x.md`.

**Counts.**

| | Count |
|---|---|
| Open before | 47 |
| Closed | 11 |
| Merged (closed as duplicate) | 2 |
| Opened | 2: #575, #579 |
| Created by the steward during the pass and folded into | 1: #578 |
| **Open now** | **37** |
| Bodies rewritten (a "Current state" section above the original) | 6: #404, #281, #229, #421, #268, and #374 before it closed |
| Given a `## Recommended fix` | every open issue |
| Retagged to a different release | 20 |

Three walkthrough failures were folded into existing issues (#376, #578), plus one new issue (#579).

## 1. Closed, and why

| # | Closed as | The evidence in its closing comment |
|---|---|---|
| #375 | completed | 0.9.1 shipped (v0.9.1 → v0.9.1.4). Every child it indexed is closed. #376 stays open on its own; the untouched cards are queued below. |
| #428 | completed | 0.9.1 shipped. Each of the twelve gaps is mapped to where it went. G1 (F-Droid) moved to **#575** first. |
| #429 | not planned (stale) | The body's "one `phone` tool with six actions" vs 13 groups in `PhoneGrants.kt` and `docs/MCP-SURFACE.md:6`. Tier 3 moved to **#446** (comment posted there; `docs/ACCESSIBILITY-TIER.md`). |
| #238 | completed (acted on) | `docs/NOTES-voicemanager.md` (#239). Its contracts went to #229 and #240. Entry kept in `docs/WATCH.md`. |
| #237, #292, #293 | not planned | Watch items, not work. Each moved, with its revisit trigger, to `docs/WATCH.md`. |
| #373 | completed | Fixed in v0.9.1 (`462c5940`, `4eaa2023`): the system engine's single poll replaced by a listener and watchdog; the readiness gate refuses a call without a voice (`CallReadiness.kt:24-28`). The retest is a walkthrough card. |
| #374 | completed (evidence) | Body corrected first. "Endpoint/partials problem" was refuted by its own reproduction (a substitution with a full endpoint). The work is #569. |
| #496 | completed | Plausibly fixed by `462c5940` (in v0.9.1, not the reporter's 0.9.0.3): the system-engine speaking latch that held the turn gate. The reporter was asked to reopen with a log if it recurs. |
| #538 | completed | Fixed in v0.9.1.3 (`199fcbae`). **The correction it was owed was posted first.** The counts were 12 stalled replies, not "22 of 40". The RTF claim came from the owner's log (#569), not the reporter's, which has no `stt-rtf` line. |

## 2. Merged

| From | Into | Why |
|---|---|---|
| #179 | **#569** | The same measurement (`event=stt-rtf`, `VoiceController.kt:1280`) and the same A/B. #179's remaining asks (stutter, presence first-token, the constant tail, its pass criteria and fallback) are carried into #569. |
| #282 | **#376** | One device proof, not two. The client is built and emulator-proven (#364), and the runs route that carries approvals is the default. |

## 3. Rewritten

Each rewrite kept the original body below a "Current state (rewritten 2026-11-11)" section.

| # | What changed |
|---|---|
| #404 | Retitled *"Runs route: the device pass (the route is the default since v0.9.1)"*. The flip shipped (`TurnRoute.kt:36`, `c5fd226a`); the stop exists (`GatewayClient.kt:117`). What remains is the device pass. |
| #281 | **Built, and keeps its subject on both routes.** I **disagree with the brief here**, with evidence. The runs route sends a `preview`, not arguments (`docs/RUNS-ROUTE.md:217-218`). The app passes it through (`core/Stream.kt:917-918`), and `Operations.previewTarget` names the subject from it (`Operations.kt:203-219`), pinned by `OperationsTest` ("a runs preview names the subject the arguments would have"). There is no app change to make. Full arguments would need the gateway to send them. The review's claim is retracted in its errata. |
| #229 | Default-off until it has a non-English answer. Its English word list (`SemanticEndpoint.kt:59-74`) and ≤100 ms gain (`:35-36`) are stated. Tagged 0.10. |
| #421 | Route 2 shipped as Phone tools (D34–D38). D38 closed the local-grammar path. What remains is context-up and the veto. |
| #268 | Rewritten against the current series. It is a standing question. |
| #374 | The diagnosis was corrected before the issue closed. |
| #208 | Retitled to a single-device matrix (D29's ruling). |

## 4. Retagged

The labels are the milestone mechanism. Final placement:

| Label | Issues |
|---|---|
| `milestone/0.9.2` | #202, #208, #209, #281, #404, #559, #562, #569, #579 |
| `milestone/0.9.3` | #578 |
| `milestone/0.9.4` | #228, #326, #405 |
| `milestone/0.9.6` | #217 |
| `milestone/0.9.7` | #279, #376 |
| `milestone/0.9.8` | #230, #388, #514 |
| `milestone/0.10` | #229, #528 |
| `milestone/future` | #45, #46, #232, #233, #234, #242, #268, #421, #442–#448, #575 |

**Moved between releases (20):**

- #202 and #559: 0.9.1 → 0.9.2.
- #208, #209, #281, #404, #562, #569: 0.10 → 0.9.2.
- #228 and #405: 0.10 → 0.9.4. #326: unlabelled → 0.9.4.
- #217: 0.10 → 0.9.6.
- #279 and #376: 0.10 → 0.9.7.
- #230 and #514: 0.10 → 0.9.8. #388: future → 0.9.8.
- #421: 0.10 → future. #242 and #268: unlabelled → future.

Where this differs from what the owner wrote in ROADMAP/PLACEMENT, it is listed in `SPRINT-PLAN.md`,
"Changes to the roadmap, for sign-off". `ROADMAP.md` itself was not rewritten.

**Other labels.**

- `awaiting-device`: #217, #228, #229, #230, #281, #376, #404 (the seven owner-held items, moved off
  `awaiting-reporter`), plus #578 and #579.
- `needs-device`: #202, #208, #209, #279, #326, #405, #562, #569.
- `awaiting-reporter` was removed from #242, where it did not apply. It is now on no open issue.
- **Created:**
  - `awaiting-device` and `needs-device`;
  - `milestone/0.9.3` to `milestone/0.9.8`;
  - `milestone/0.9.2`, given a new description (*the floor, measured*).
- **Deleted:** `milestone/0.4.0`, `milestone/0.6.0`, `gate/0.4.0`, `review/opus5`, `review/deepseek`.
  They were PLACEMENT's dead list, and no open issue depended on them. The provenance of #45 and #46 is
  in their bodies.
- **Kept, against PLACEMENT:** `surfaced`. It encodes D25 ("upstream-owned; do not rebuild"), and
  closing #282 left it with no open issue rather than making it dead.

## 5. The owner's walkthrough of 2026-11-12 (build 0.9.1.4, his handset): 16 pass, 4 fail, 1 pending

| Card | Verdict | Where it went |
|---|---|---|
| Switch profile mid-use | fail | **#579** (new, 0.9.2). "One unreachable" means the host *listed* a profile whose own `/v1/models` did not answer from the phone (`ProfilesAsk.kt:497-498, 523`). The issue asks first for the `event=profiles-ask … status= seen=` line, and does not assume the app. App half: log the per-profile probe, which is not logged today. |
| Refresh the profile list | fail | **#579**, the same cause. |
| Phone tools: set a timer by voice | fail | **#578** (opened by the steward, 0.9.3). Comment added: the immediate failure is **a House configuration defect**. The gateway's `vox` entry targets the bench Pixel 7, and nothing served 8765. One `vox` name per gateway profile lets the last handset to complete setup own it. The phone serves MCP only during a call (`PhoneMcp.kt:494`). |
| Phone tools: one refusal | pending | Blocked by #578; recorded there. |
| Approvals | fail | Folded into **#376** as a **testability gap, not a defect**. The recommended fix is the designed test: `scripts/approval-harness.py` (already a gateway that always asks) on a connection of its own, with three cards. |

## 6. Invariants, and the queries that check them

Each query returned an empty list at the end of the pass:

```
gh issue list --state open --limit 200 --json number,body --jq '[.[]|select(.body|contains("## Recommended fix")|not)|.number]'
gh issue list --state open --limit 200 --json number,labels --jq '[.[]|select(([.labels[].name]|map(select(startswith("milestone/")))|length)!=1)|.number]'
gh issue list --state open --limit 200 --label awaiting-reporter --json number --jq '[.[].number]'
```

## 7. Pull requests

None merged; merging is the owner's.

| PR | Branch → base | What it proves | Gate |
|---|---|---|---|
| **#574** | `fix/field-log-record` → `testing` | The field-log counter counts what it says. `fullReply` is gateway time, and audio is estimated from characters. It reproduces 12 stalls (14–50 chars, 126.8–128.8 s). It corrects `RELEASE-0.9.1.3.md:16-18` and `StreamDrain.kt`'s comment. `test_field_log_turns.py` is in the gate. | `GATE-GREEN` |
| **#576** | `fix/regression-cold-frame` → `testing` | Regression path 2 has its witness: `event=lat label=cold metric=first-frame ms= createMs=` (`ColdFrame`, `ColdFrameTest` 3/3), printed by `regression-smoke.sh start`. | `GATE-GREEN` |
| **#577** | `fix/regression-retire-count` → `testing` | Regression path 5 has its witness: `event=tts-retire … queued= wrote= full=` (`RetireWitness`, `RetireWitnessTest` 4/4), counted by `regression-smoke.sh finish`. | `GATE-GREEN` |
| docs | `docs/roadmap-0.9.x` → `testing` | The plan of record and its governance as written. D47. The 0.9.1 recount correction. `docs/WATCH.md`. The 0.9.x review with errata. `SPRINT-PLAN.md`, this file, and the INDEX front door. Merged with `testing` at 0.9.1.4. | see the PR body |

**Not done, and why.**

- **#281's "carry the arguments" change.** There is nothing to carry (§3).
- **The v0.9.1.3 GitHub release page** still carries the old counts. Editing a published release is
  the owner's call.
- `review-context/` and the untracked `scripts/field-log-turns.py` in the planning worktree were not
  committed. The script's corrected version is in #574.

## 8. Walkthrough cards to queue (D47: pending, never blocking)

- **T23:** system engine, three consecutive spoken turns. **T23-negative:** Piper with no model; the
  call refuses and names the voice leg. (#373)
- **#496:** after a reply on the system engine, the next utterance reaches the agent without redialling.
- **Path 2:** force-stop, launch; `event=lat label=cold` beside `am start -W`. (#576)
- **Path 5:** one long reply; `event=tts-retire … full=true`. (#577)
- **Paths 8 and 9**, after #579's host fix.
- **Phone tools:** in a call, a timer by voice, then one refusal, after #578's re-announce.
- **Approvals:** three harness cards (touch, voice, timeout), then one real-gateway card. (#376)
- **#404:** the runs-route checks. **#281:** "could you tell what it was doing" on a multi-tool run.
- **From #375:** the ten untouched 0.9.1 cards (T4, T12, T14b, T15, T17, T25, T30, T33, T37, T42),
  re-read against 0.9.1.4 before they are run.

## 9. What still needs the owner

1. **Sign-off on the 14 roadmap changes** in `SPRINT-PLAN.md`. The tags already follow them.
2. **Decoupling continuous listening from Presence** amends D38.
3. **#514:** answer @PierreCsn's question (recommended: yes, per profile, per verified origin).
4. **PR #537:** review the extraction PR waiting since 2026-11-08. It starts lane L1.
5. **#575:** place the F-Droid submission (it needs a gitlab.com account).
6. **#578:** re-announce Phone tools from the handset during a call, or edit the House `vox` entry. Then
   decide between a per-device server name and re-announce-on-change.
7. **#579:** export the log from a refresh, so the `profiles-ask` line can be read.
8. **The v0.9.1.3 release page:** correct the old counts, or leave them with a pointer to #574.
9. **Merging** #574, #576, #577 and the docs PR.
