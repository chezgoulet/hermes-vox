# Milestones — what a version means, and what the alpha gate holds

**Rewritten 2026-11-11.** This document used to hold a sequence. The sequence is
[`../ROADMAP.md`](../ROADMAP.md) now, and only there: two documents holding one plan is how they came to
disagree, and the last disagreement was found by a reviewer rather than by us.

What stays here is the meaning of a number, the alpha gate and its contents, and the labels.

## How versions mean things

`0.9.x` is **beta**. `1.0` is **general availability**, and it arrives when the owner says so — not on a
schedule.

```
(GA major).(features, upgrades, major releases).(point releases, refinements, finesses).(fixes)
```

A feature release raises the minor component (`0.9.2`), a fix to an already-published version appends a
component (`0.9.1.3`), and `versionCode` rises by exactly one per cut. The tree currently reads
`0.9.1.4` (`versionCode` 151), which is the version just shipped; it changes with the next cut, not
before. The 0.9.1 line shipped as point releases — `0.9.1.1` and `0.9.1.2` (the profile list read as
agents write it), `0.9.1.3` (the cleanup cut), `0.9.1.4` (the call back on the speaker) — each with its
`docs/RELEASE-<version>.md`.

There is **no release calendar** (D42). A release ships when its gates are green: the build gate, the
twelve regression paths, the human run, the evidence rule, and a closed tracker
(`docs/GOVERNANCE.md`, `docs/REGRESSION.md`).

**Polish is continuous and belongs to no milestone.** A rough edge found in use — a wording, a colour,
a timing — is fixed when it is found and ships in the next point release. A defect is fixed when it is
known, not scheduled. What a milestone records is the work that needed deciding, not the work that
simply needed doing (D31).

## The alpha gate

There is **one gate for everything unfinished**: **Enable Alpha Features**. It is **off on a fresh
install**, and it sits immediately above the alpha block at the end of Settings. Its panel lists six sets — **Presence**, **Continuous listening**, **Turn-taking**, **Phone tools**, **Wake word** and
**Digital assistant** — each selected on its own, so a tester can take one without taking all.

When the master is off, the settings those sets cover are **dimmed and unreachable**: not hidden, and
not silently running. Continuous listening requires Presence, because it is how the being hears; Phone
tools is independent of it (D38). Quiet calls (#325) is not a set; it is its own switch, off by default.

**Sessions** and **Switching profiles** left the gate on 2026-11-06 (D39), so `/sessions` and the
profile switcher are on for every install.

`Presence` remains the feature's public name in copy and release notes. "Alpha Features" is the gate,
not the name. "Enhanced Realtime" is retired as vocabulary.

The panel **shrinks as each set graduates**: six today, three after 0.9.3 (Phone tools and Digital
assistant promoted, Wake word removed), two after 0.9.4, one after 0.9.5, none after 0.9.6. When it is
empty the gate goes, because there is nothing left behind it. A promotion changes no behaviour of the
set it promotes — otherwise the promotion is unproven by construction.

### What each set is, and what it still needs

The panel must match reality. A set that turns on nothing is worse than an absent one, and a set that
is finished belongs in the app rather than behind a switch.

- **Presence** — the being speaks its own short lines while the agent works; it may read what the
  phone is doing and may not act on it. *Needs:* a model decision (`#562` — 2,588 MB and 5.6 s to
  produce eleven to thirty-one character lines) and a conversation that behaves (`#217`, `#559`).
- **Continuous listening** — the mic stays open and the recogniser is read continuously. *Needs:* a
  recogniser that keeps up (`#569`), the lane itself (`#228`, `#229`), and re-measured budgets
  (`#405`).
- **Turn-taking** — acknowledgement while you speak, yielding when you interrupt, resuming after.
  *Needs:* continuous listening, and `#242` (classify the overlap, do not measure its level).
- **Phone tools** — the mind acting on the phone through one MCP tool. *Needs:* one device pass that
  sets a timer by voice and shows one refusal. It is the closest set to finished.
- **Wake word** — enrolment only: the app records your takes and **no detector is installed**, so
  nothing listens (`docs/WAKE-WORD.md`). *Needs:* a detector, which needs training the House does not
  have (`#45`). **It comes out of the panel at 0.9.3**, because a set that turns on nothing is worse
  than an absent one.
- **Digital assistant** — asks Android for the role so the assistant gesture opens Vox. *Needs:* one
  phone where the gesture works; the row grants nothing by itself.

## Labels, and what they mean

- **`milestone/0.9.x`** — in that release's scope, assigned from `ROADMAP.md` and never by drift.
- **`milestone/future`** — recorded, not scheduled, and the issue says why it is not scheduled.
- **`good first issue`** — bounded, reviewable, and nobody is already on it.
- **`awaiting-reporter`** — a reporter's confirmation is wanted. It never holds a release (D47): the
  release ships with the verification marked pending and a walkthrough card queued.
- **`awaiting-device`** — built; the owner's device verdict is pending, as a walkthrough card (D47).
- **`sev/critical|high|medium|low`** — consequence if it ships, not effort.
- **`needs-device`** — cannot be closed without a phone, so it cannot close in a pull request.

**No issue carries `milestone/1.0`** (D22, `PLAN-1.0.md`): 1.0 is a state the product reaches, and the
candidate work is unnumbered in `milestone/future`, decided by the community (`#268`).
