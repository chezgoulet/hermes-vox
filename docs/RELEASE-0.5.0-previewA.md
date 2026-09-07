# Hermes Vox 0.5.0-previewA — speech-locked transcript

## The fix
The visible transcript ran 3-4 sentences ahead of the voice: `onDelta` painted every
SSE text delta the moment it landed, while Piper renders speech over 8-15s and the
streamed path queues phrases at sentence boundaries + synthesis time. The text was
driven by *delta arrival*, not by *what had actually been spoken*.

Now the transcript is **speech-locked**. A pure `SpeechCursor` + `PlaybackClock`
map the AudioTrack's playback head to a character position, so text is revealed at
the pace audio is actually played — and the not-yet-spoken tail is **dimmed** (0.35)
so you see what's coming but the "spoken now" boundary moves with the voice.

## What it does
- **Reveal with the voice** — a cursor advances through the reply as the playback
  head moves, not as the model emits tokens.
- **Dimmed unspoken tail** — words the entity hasn't said yet are shown faint, so
  the reveal is unmistakable and it never looks like finished text racing ahead.
- **Freeze on cancel** — barge, hush, or hangup freezes the cursor *before* the
  track is torn down, so a cut-off reply shows only what was actually said; it
  never auto-completes and never rewinds (clamped monotonic).
- **Undeceitful default** — if no audio reaches the engine (text-only settle,
  system-TTS), the cursor stays at `-1` and the display is exactly today's plain
  behavior; the feature is strictly additive.

## The design "get"
The streamed reply path never emits `onState("speaking")` — that's the one-shot
path only. The reveal loop therefore arms on `"thinking"` and lets the cursor
decide engagement (returns `-1` → plain) until a phrase actually leaves the voice.
Arming on "speaking" would have armed where the bug isn't. The playback clock
trusts the head the moment it ever advances, falls back to wall-clock estimate only
if it's never moved after 700ms of audio, and deliberately does **not** fall back
on a held-then-frozen head (a real underrun — freezing reveal with the voice is
correct there).

## Device test
Ask for a multi-sentence answer; watch the text advance *with* the voice and dim
the unspoken tail. Barge mid-reply; confirm the reveal freezes (doesn't run ahead)
and the spoken-portion stays what was actually voiced.

## Verified
- Independent gate on the merged tip: exit 0, 16 test classes, all 14
  `SpeechCursorTest` cases, clean purge+rerun.
- KEEP-list zero-touch: silenceAll, StreamFence, pause-first teardown,
  StreamRetirementState, focus, BargeGate/EscapeRule, ReplySettleRule,
  session_turns, CrawlView role logic, avatar state setters.
- Not yet device-verified: playback-head reliability behavior and frame cost are
  proven against the code + pure tests, stated honestly in the build report.
