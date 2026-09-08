# Hermes Vox 0.6.4 — the cadence fix + the render crash guard

Both items from the 0.6.3 field session: the robotic double-ack and the
crash on reply render.

## The cadence fix (the "let me think hold on hm" problem)

Root cause: a DOUBLE-ACK. The app speaks its own acknowledgment ("Let me
think —") the instant the mind starts working, and the model then ALSO said
it was thinking — "let me think hold on hm" was two voices stepping on each
other. Two fixes, together:

- The **presence-cadence contract** in the voice prefix: the model is told
  the app acknowledges on its behalf, so it must not re-state
  thinking/working, and it greets back naturally before anything else.
- The **app's own ack is now one short beat** ("Mm?") instead of a sentence.

## The crash fix

The crash on reply render: a zero/negative sample count in an audio segment
(an engine race under teardown) divided by zero inside SpeechCursor. Two
guards: `recordAudioSegment` refuses insane registrations, and
`SpeechCursor.charsSpoken` treats a zero-sample segment as already-spoken
instead of dividing. Crash path closed.

## Verification

Thelio gate on a clean clone: 32 suites / 250 tests / 0 failures,
assembleRelease + bundleRelease green. versionCode 108 / 0.6.4.
