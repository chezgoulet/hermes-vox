# Hermes Vox 0.5.0.3 — static in-place transcript + screen-alive toggle

Post-facelift pass from the 2026-09-07 13:00 field log (0.5.0.2). The animation was
the verdict ("holy smokes they look good") — this release is the only two changes
Christopher asked for, plus the investigated flag. Spec: `PLAN-0503-static-transcript.md`.

## K1 — the Star-Wars drift is dead; the text renders IN
`CrawlView.kt` dropped the `drift` field, its `rate*dt` increment, and the frame-delta
clock that fed it. The block now rests at `blockTop = baseBottom - tl.height` — a
**static, top-faded transcript** anchored above the bottom pad. The reply appears in
place and the reveal boundary moves through it; nothing scrolls away mid-sentence.

Kept byte-for-byte (the parts the field log proved):
- the spoken/unspoken split at the speech cursor (`cut`, dim tail `UNSPOKEN_ALPHA`),
- the top-fade band (0.62·height) so text never reaches the being,
- the reveal-freeze path (`chars= of=` logging lives in VoiceController, untouched),
- hide-when-done guard (now a no-op at rest — kept per KEEP-list),
- `setRole` / `setText(t, spokenChars)` signatures.

## K2 — screen-alive toggle (Settings → Appearance & Presence)
New pref `keep_screen_on`, **default OFF**, exposed as "Keep screen on during calls".
It holds `FLAG_KEEP_SCREEN_ON` on the MainActivity window — a plain window flag:
**no new permission, not a WAKE_LOCK**.

Lifetime is welded to the call so it cannot leak:
- armed in `acquireVoiceWake()` at call start, beside the wake acquisition (pref-gated);
- cleared unconditionally in `stopVoiceWake()` — the one release every teardown path
  runs (endCall / newSession / resetActiveConversation / onStop-no-call);
- `applyKeepScreenOn()` re-applies on resume (after `resumeLiveCallIfAny`), so flipping
  the toggle mid-call lands the moment the call surface is back; the `liveController`
  gate keeps a stale `callLive` from re-arming with no live line.

Restore-defaults (Appearance) covers it back to OFF.

## K3 — the slow-upstream flag: mechanism found, minimal fix applied
**Flag:** "the app pushes the last message text to the screen when the upstream is slow."

**Mechanism (read, not guessed):** `replyBuf` in MainActivity was never scoped to the
turn. `onReply` leaves it holding the finished reply; the next turn's `onState`
("thinking") reset `toolCount` and the transcript cursor (`armTranscript` in
VoiceController) but NOT `replyBuf`. During the new turn's pre-voice window —
`speechCursor() == -1` until the first phrase reaches the engine (`audioStartedAt`) —
the reveal loop's fallback paints `reply.setText(replyBuf)`, i.e. the PREVIOUS
message. A slow upstream stretches that window to the whole stall, so the last
message sits on screen (in 0.5.0.2: re-pushed after the drift had consumed it; with
K1's static block it would also flash stale-then-snap as the voice lock engages).
`onReply` itself never races the reveal (it paints only when `!revealActive`, and a
frozen reveal is immune: `frozenChars` is checked before the -1 fallback).

**Fix (one line, turn-scoping):** `if (state == "thinking") replyBuf = ""` — the
mirror of `armTranscript()`. The arguably-correct behavior is preserved: during a
stall you still see THIS turn's composed text plainly ("what it'll say"); only the
cross-turn stale text is gone. No reveal/freeze/settle logic touched.

## KEEP-list verified zero-touch
AvatarView (the whole 0.5.0.2 PorterDuff/13-archetype swarm), MotionState, silenceAll,
StreamFence, StreamRetirementState, focus (C3 H1), BargeGate/EscapeRule,
ReplySettleRule, crash guard, transcript reveal chars logic (SpeechCursor/
transcriptText/freezeCursor), session_turns. Changed files: `CrawlView.kt`,
`MainActivity.kt`, `SettingsActivity.kt`, `activity_settings.xml`, `app/build.gradle`.

## Gate
- `:app:testDebugUnitTest` — exit 0 (17 pure-rule suites green).
- Release variant compiles + R8-minifies clean (`compileReleaseKotlin`,
  `minifyReleaseWithR8` exit 0); `packageRelease` needs the gitignored signing
  keystore, absent on this machine by design (secrets never in the repo).
- versionCode 89 / versionName 0.5.0.3.
