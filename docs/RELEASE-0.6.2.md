# Hermes Vox 0.6.2 — ER hotfix pass + the honest self-review

The fix pass for everything the 0.6.1 self-review flagged, plus the ER
controls surfaced to Settings. Still alpha; still honest.

## The fixes

- **HOLD-drop** (the bug I'd bet on): after "take your time" holds the mind,
  the reply that lands is now settled like an ordinary turn — it SPEAKS on
  the non-streaming TTS leg (the streaming/warm leg was never affected). The
  hold-release carries a gen-keyed witness consumed exactly once; a genuine
  later cancel still drops (no ghost re-speak regression).
- **Main-thread Gemma ANR**: narration renders on a background thread
  (`VoiceOrchestrator.expressAsync`); the glue lands back on main. With the
  Gemma model loaded + ER on, tool-call narration no longer blocks the UI.
- **Echo pollution of the barge capture**: `erBargeSeg` no longer accumulates
  frames while the soul's own filler plays (speaker leak is the one input we
  know is not the user), plus a post-filler skip window.
- **VOX.md sync retry**: a failed pull now offers "Try again" — the corrective
  note rides the same server-side chain, so the entity remembers the format
  it just got wrong.
- **View the mirror**: Settings → "View the mirrored VOX.md" — what the
  on-device soul actually reads, no adb needed.

## The ER controls (Settings → Entity & Connection, ER-only)

- **Soul presence** toggle — mutes the narration/fillers; the classifier and
  the drift-sync keep running (the mind's sync stays honest).
- **Semantic barge-in** toggle — off = every interruption cancels the
  entity's work (pre-ER behavior); on (default) = "take your time" keeps it.
  The toggle's toast explains the trade.
- **Filler density** slider — 0 (silent) to 4 (chatty); default 2, the Miles
  cap.
- **Echo guard** slider — how long barge capture stays quiet after the soul
  speaks; default 700ms, 0 = off.

## Onboarding notes

The first-ER-enable bootstrap dialog is unchanged (pull VOX.md on enable);
the semantic-barge toggle's toast now explains what it trades, in one line.
ER stays alpha-labeled everywhere.

## Verification

Full Thelio gate on a clean clone: go vet → go test → gomobile bind →
assembleRelease + testReleaseUnitTest (31 suites / 244 tests / 0 failures),
APK versionCode 106 / 0.6.2, signer CN=Hermes Vox, plus the AAB build step
per docs/RELEASE-PROCESS.md.
