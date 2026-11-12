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
- [ ] **A human run**: named `<name>` — the owner, or the volunteer who has taken
      the role — on a real device, in ordinary use, with the twelve paths among
      what they did. What they did and saw: `<one sentence>`.
      *(D41. There is no duration — the run is the gate. If the reviewer is also the
      cutter, say so here: a real run, not an independent one.)*

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
