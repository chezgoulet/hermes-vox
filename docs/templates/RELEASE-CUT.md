# Cut checklist — `<version>` (`versionCode` `<n>`)

Copy this into the cut's tracker issue. Every line is answered, including the
ones that are "no". The tag is cut when the last line is ticked, and not before.

## 0. Scope — when the cut begins (`<date>`)

- [ ] Scope published: what is in, and what is explicitly **not** in.
- [ ] **What does this touch that a phone can see?** For every change to audio,
      the service lifecycle, the network or the UI: the regression path that
      covers it, or a written "none, because …".
- [ ] No transport, media or lifecycle change inside a release whose subject is
      something else — or the release note says so in its first paragraph.

## 1. Build gate

- [ ] `bash scripts/gate.sh` prints `GATE-GREEN` on `testing`.
- [ ] Every test that failed during the cut was investigated, not re-run. (A
      flaky assertion is a defect; see `#565`.)
- [ ] `bash scripts/check-version-code.sh --release` prints `a release may be cut`.

## 2. Device

- [ ] RC cut, `versionCode` bumped, installed **over** the previous release.
- [ ] `bash scripts/regression-smoke.sh start` / `finish`: which paths it proved,
      which it returned `UNKNOWN` for.
- [ ] All twelve paths in `docs/REGRESSION.md` walked; the evidence line for each
      recorded in the release document.
- [ ] **#209 — the baseline row and the diff.** The row taken **gate off, on a fresh
      install** (whisper-base, 900 ms partials, 800 ms VAD silence), labelled with the
      build: `scripts/baseline-report.py --json --label "<build>, gate off, fresh
      install" <log>`. Then `scripts/baseline-diff.py <previous release>.json
      <this build>.json` — exit code `<0|1|3>`, verdict line `<quoted>`.
      **A 1 or a 3 is a red line, not a tick**: 1 is a crossed tolerance, 3 is a row the
      previous release measured and this one does not report.
- [ ] **F5 — the thirty-minute soak.** One call left open for thirty minutes: peak RSS and
      the lowest `MemAvailable` (`event=res-summary`), the thermal trace start → end, and
      any dropped or failed turn. The release document gets the slope, not a single point.
- [ ] **F8 — the ten-utterance verdict.** `scripts/verdict-f8.sh <build-label>`; the rows
      stay in `docs/verdicts/<build-label>.md`. The script records the numbers; whether the
      words were right is the human run's column.
- [ ] **#208 — the behaviour matrix** (2a, below), one row per cut.
- [ ] **A human run**: named `<name>` — the owner, or the volunteer who has taken
      the role — on a real device, in ordinary use, with the twelve paths among
      what they did. What they did and saw: `<one sentence>`.
      *(D41. There is no duration — the run is the gate. If the reviewer is also the
      cutter, say so here: a real run, not an independent one.)*

### 2a. Behaviour matrix (#208)

One device, named once — D29 ruled one, because a matrix nobody can fill is not an
instrument. A second device adds a column; it does not add a second table.

`<device> · Android <version> · build <version> (versionCode <n>)`

Every cell is the log line that proves it, or `not run — <why>`.

**Connect** — mode × endpoint state:

| Mode | HTTPS gateway | cleartext refused | wrong key | unreachable |
|---|---|---|---|---|
| Realtime | | | | |
| Enhanced Realtime | | | | |

**Behaviour on the working endpoint:**

| Behaviour | The line that proves it | Result |
|---|---|---|
| barge-in | `outcome=barge-in` | |
| greeting | | |
| Presence cadence | `event=er-soul` | |
| cues, across the ringer matrix | | |
| headset route | | |

## 3. Evidence

- [ ] Every number in the release note was measured on **this build**.
- [ ] Every quoted log number names the file, the counting rule, and the script
      that reproduces it.
- [ ] No claim in the note that nobody can check.
- [ ] Every defect fixed here landed with its catcher, in the same pull request.

## 4. Record

- [ ] Every issue this release fixes is closed by it, with the evidence line — or
      it stays open and the note says so.
- [ ] This checklist has no unticked line except those explicitly waived, with a
      reason and a linked issue.
- [ ] `docs/RELEASE-<version>.md` exists in the tree (the release page reads it).
- [ ] The previous APK stays published.

## 5. Cut

- [ ] Release built from the SHA that will be tagged; verdict
      `reproducible — identical payloads` read from `compare.txt`.
- [ ] Artifact verified: hash, badging, signer `8be7feed…`.
- [ ] Tag and release published; site dispatch sent; installed on a phone and one
      live call made.

## Waivers

| Line | Reason | Issue |
|---|---|---|
| | | |
