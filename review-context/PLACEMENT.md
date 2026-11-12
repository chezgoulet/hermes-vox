# Proposed placement

## All 47 open issues, placed

| Release | Issues | Count |
|---|---|---|
| closes at 0.9.1 sign-off | `#375` `#428` `#202` `#538` | 4 |
| 0.9.2 — the floor, measured | `#569` `#179` `#496` `#373` `#374` `#562` `#528` | 7 |
| 0.9.3 — the small sets graduate | *(device passes only; no open issue)* | 0 |
| 0.9.4 — hearing graduates | `#228` `#229` `#405` `#242` | 4 |
| 0.9.5 — turn-taking graduates | `#217` | 1 |
| 0.9.6 — the being graduates | `#559` | 1 |
| 0.9.7 — the loop closes | `#404` `#282` `#376` `#281` | 4 |
| 0.9.8 — first run, last words | `#230` `#388` `#326` `#514` | 4 |
| standing instruments (no milestone) | `#208` `#209` `#279` | 3 |
| future (unnumbered, with a reason) | `#45` `#46` `#232` `#233` `#234` `#237` `#238` `#292` `#293` `#421` `#429` `#442` `#443` `#444` `#445` `#446` `#447` `#448` | 18 |
| the standing question | `#268` | 1 |
| | | **47** |

### Notes on the moves that matter

- **`#569` leaves `milestone/0.10` for 0.9.2.** It decides every release after it and it is the first
  thing that has to be true.
- **`#562` joins 0.9.2** because its bench decides the headroom every later release assumes — and
  because `#569`'s cause may be `#562` in disguise. Bench before debugging.
- **`#528` joins 0.9.2** for the contract only; extraction rides every release, and the coverage checks
  land at 0.10.
- **`#208`, `#209`, `#279` lose their milestone entirely.** They are instruments, not features; a
  milestone label hid that they were not running. They move into `docs/templates/RELEASE-CUT.md`.
- **`#242` moves from unlabelled into 0.9.4.** Turn-taking cannot be promoted without it.
- **`#421` leaves 0.10 for future.** It is the largest design item in the repo and it would break the
  freeze.
- **0.9.3 carries no issue** because the two promotions are device passes, and a promotion never
  rebuilds the set it promotes.

## What to close now

| Issue | Action | Why |
|---|---|---|
| `#374` | close as evidence | It is a transcript of a mishearing; the finding is kept in the issue, the work is `#569` |
| `#375` | close at sign-off | The acceptance tracker for 0.9.1; the cards are the record once the owner signs |
| `#428` | rewrite, then close | Ten of the twelve gaps closed; what survives is the F-Droid submission, which belongs on its own line (D14/D15 put it at 1.0) and is not a release gap |
| `#202` | close with its limitation | D29 says so in terms: mechanical half fixed, cause unproven. Needs one ear on a truncated call first |
| `#538` | close on the reporter's confirmation | Already answered with the release and the fix; closes with the evidence line when they confirm |
| `#429` | verify, then close or merge | It describes "one tool with six actions"; `docs/MCP-SURFACE.md` documents thirteen groups. If the body is stale, close it and keep the capability work in `#421` |

## Carried work that has no issue number

The old roadmap held four items that exist in no issue and would have been lost by the reset. Each
needs an issue opened when its release comes:

- **F3b — real-world turn-taking.** The adaptive barge floor (near-misses cluster at 0.10–0.13 on a
  0.10 floor in a noisy room) and the in-cabin endpointer A/B. → 0.9.3.
- **Pre-rendered personality stems.** The opening beat must be instant, so it cannot be a live
  render; the stems are synthesised once at init in the entity's own voice. → 0.9.5.
- **F6a — narration into the presence loop.** Route the tool event through the loop so the line is
  topical. → 0.9.5.
- **Truncation after a barge in session history.** The model must not believe it said things the
  caller never heard. → 0.9.5.

`F4` (GPU contention) and `F5` (the thirty-minute thermal soak) join the standing instruments.

## Label hygiene

**Delete** — they name releases that no longer exist or reviews that already happened:
`milestone/0.4.0`, `milestone/0.6.0`, `gate/0.4.0`, `review/opus5`, `review/deepseek`, `surfaced`.

**Add** — three that the new plan actually needs: `needs-device` (cannot close without a phone, so it
cannot close in a pull request), `i18n` (touches a user-visible string or a translation), and
`freeze-exempt` (a fix inside the freeze window that changes no sentence).

## The migration

Run `relabel-0.9.x.sh` in this directory. It is deliberately a script and not a list of commands to
paste: 47 issues is too many to do by hand without a mistake, and it prints each action as it makes
it. It does not close anything — closing is a judgement and belongs in a browser, with the evidence
line written by a person.
