# Veto notice — `<version>`

Filed in the cut tracker at the moment the veto is used. Published within 24
hours. Without this form, the veto lapses and the cut proceeds
(`docs/GOVERNANCE.md` §5).

**Version:** `<version>` · **Build/commit:** `<sha>` · **Filed:** `<time>`

## Ground (one of the four)

- [ ] **1. A gate is red or unexplained.** Which gate, and what it reported.
- [ ] **2. A claim is not measured on this build.** Quote the sentence and say
      what is missing.
- [ ] **3. An unresolved defect on the RC, with a reproduction.** Issue, and the
      steps.
- [ ] **4. The owner used the RC and it is not ready.** What he did, and what
      happened. No gate needs to be named; this ground stands on use.

## The reason, in one paragraph

Published where the release would have been announced.

## What happens next

- No new date: the release ships when its gates are green (D42, D47).
- The fix ships as its own cut: `<issue>` — or the same cut, with the ground
  cleared and the checklist re-run.
- The schedule moved. No gate was lowered. Nothing that a gate refused was
  shipped by veto.

## The record

This is a critique of the release, not of a person. Named in the tracker so the
next cut can read it.
