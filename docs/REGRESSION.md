# Regression safety

Set 2026-11-11, after two defects shipped in the same release: the short-reply drain (#538,
introduced by #517's buffer change) and the re-warm cascade (#549/#553). Neither was caught by a
gate that was green, because neither had a test or a card that could see it. This document is the
standing answer to that.

`docs/TEST-PLAN-0.9.md` is the historical device pass; the acceptance cards (#375) are the full
manual matrix. This is the smaller, mandatory set that runs on **every cut**.

## The rule that matters most

**Every fixed defect lands with the smallest catcher that would have caught it, in the same PR.**
A unit test where the defect is a rule; a witness line or a card where it needs a device. A fix
without a catcher is a promise, not a guard — and promises are what shipped 0.9.1.

Worked examples:

- #517 changed the playback buffer to 4 s and nothing asserted what that did to a short reply →
  `StreamDrainTest` plus the short-reply card now exist.
- #533 moved the profile ask onto a streamed route → a parser test and the live ask witness.
- The mood markers reaching the voice → a sanitiser test per bracket form.

## The four layers

1. **Pure logic, in CI** — parsers, state machines, budget rules, the readiness gate. No device.
   `scripts/gate.sh` runs these on every push.
2. **Constants and witnesses, in CI** — the invariants a later change is most likely to break,
   asserted directly: a streamed track's start threshold is a quarter-second or less; a drain is
   bounded by the audio owed; a mood marker never reaches `tts-speak`; the dated log line exists.
   Cheap, and they name the defect in the failure message.
3. **Device smoke, per cut** — `scripts/regression-smoke.sh` for the automatable half, then the
   manual set below. Ten minutes with a phone.
4. **The human run — adopted 2026-11-11 (D41).** A release is not a build that passed; it is a
   build that was used. Before a tag, one person installs the candidate **over the previous
   release**, uses it in ordinary course — a real session, with these twelve paths among what they
   do — and says what they saw. The release document records whose run it was and what they did.
   **There is no soak duration: the gate is the run, not the clock.** It defaults to the owner;
   a volunteer who reviews a release takes it permanently, and from then on the author's own run
   does not count.

## The per-cut procedure

1. Cut an RC from `testing` (`rc-<version>-<n>`) with the version already bumped, and install it
   **over the previous release**, never instead of it.
2. Run `bash scripts/regression-smoke.sh` with the phone attached; it prints what it proved and
   what it could not.
3. Walk the manual set; record the evidence (a log line, a screenshot) in the release document.
4. Any red is fixed, or deferred in writing with a reason and a linked issue. **No tag with an
   unexplained red.**
5. The human run (layer 4): someone installs the candidate and uses it, and the document records
   who and what they saw. Then tag, then announce.

## The regression set — twelve paths, every cut

Each names the thing that proves it. "Device" means a human, because the bench cannot speak.

1. **Install over the previous release** — data, profiles, keys and settings intact. Proves:
   `versionName` shows the new build and the switcher still lists what it did.
2. **Cold start** — time to first frame. Proves: `am start -W` TotalTime and the app's own
   `event=lat label=cold metric=first-frame ms=…` row (`ColdFrame`), both printed by
   `regression-smoke.sh start`. Judged against the #209 baseline's gate-off row once it exists;
   until then the number is recorded in the release document and the judgement is marked
   pending (D47).
3. **A call reaches green** — gateway, hearing and voice all ready; the readiness overlay clears.
   Proves: the gate's own states and `event=tts-ready`.
4. **A short reply speaks and ends promptly** — the #538 class. Proves:
   `event=tts-drain result=played` and a turn under ~15 s; never `result=timeout`.
5. **A long reply streams and completes** — proves: `event=tts-retire … full=true`: every chunk
   the reply was cut into (`queued=`) was handed to the engine (`chunks=`) and wrote audio
   (`wrote=`). `full=false` on a reply that was not cut is a lost phrase (`RetireWitness`).
6. **Barge-in** — speaking over the reply cuts it and takes the turn. Proves: `outcome=barge-in`.
7. **Presence on** — a line is produced, spoken once, and never repeats. Proves:
   `event=er-soul`, `event=er-glue-reject reason=repeat` when it should.
8. **Switch profile mid-use** — the new session carries nothing from the old. Proves: the header
   names the new connection and the first turn is answered by it.
9. **Refresh profiles from the host** — the list resolves. Proves:
   `event=profiles-ask … status=200 seen>0`.
10. **Settings during a live call** — opens Settings, does **not** enter PiP, and the call survives.
    Proves: no `event=pip enter` line.
11. **Hang up and redial** — the second call starts clean; no stale stream, no stale gate. Proves:
    `event=gate-release` for the first, a fresh `startStream` for the second.
12. **Log export** — the block is produced and every line carries a date. Proves: the export opens
    the share sheet and the first line matches `MM-dd HH:mm:ss.SSS`.

## Release notes are evidence, not intention

No claim ships in a release note that was not measured on that build, or confirmed by the person
who reported the issue. The 0.9.1 note described a profile fix that was real and a release that was
not ready; the sentence that cost the most was the one nobody could check.

## Rollback

The previous APK stays published and is never deleted. A bad release is answered by reverting the
offending commit, bumping `versionCode`, and shipping — never by re-tagging an older build, which
Android will refuse to install over a higher one. Keep the last known-good APK and the commit it
was built from in `docs/MILESTONES.md`.

## What we still cannot catch

- **Audio paths without a device.** No injected-audio harness exists; the short-reply class is
  caught by a card and by the ring buffer test, not by CI.
- **A second handset** (#208) — everything so far is one Pixel.
- **Long-call drift** — thermal and memory over an hour are measured per release, not per change.
