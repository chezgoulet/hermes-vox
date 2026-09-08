# Hermes Vox 0.6.3 — the field-log fix

A same-day showstopper fix from Christopher's field log, plus the Gemma
render rails.

## Fixed

- **Gemma double-load (the showstopper).** The field log caught two
  "GemmaExpress loaded" lines 105ms apart: onResume → handleModeUi checked
  availability before the first load's background thread finished, so the
  .litertlm initialized twice (double RAM + a window where two Engines could
  serve generations). One in-flight load now; a caller arriving mid-load
  chains to the next resume; `loaded` is @Volatile.
- **Gemma render rails** (ErGemmaGuard): a runaway 2B render is capped at
  600 chars (never dropped — the head is kept); a main-thread reentry is
  refused outright (the ANR can't return); repeat renders inside 1.2s are
  refused (no stuck-record fillers).

## Verification

Thelio gate on a clean clone: 31 suites / 244 tests / 0 failures,
assembleRelease green. versionCode 107 / 0.6.3.
