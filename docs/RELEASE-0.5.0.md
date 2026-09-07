# Hermes Vox 0.5.0 — the presence release

`0.5.0` bundles the two preview feaatures into one stable release. Everything is
built on top of the 0.4.x polish series (no baked keys, VPN explicit, walkie
stripped, audio focus + route rebuild, honest metrics, bounded log, no ghost
re-speak). The 0.4.x field-caught bugs are closed and independently verified.

## Speech-locked transcript (was 0.5.0-previewA)
The visible transcript ran 3-4 sentences ahead of the voice: `onDelta` painted every
SSE text delta the moment it landed, while Piper renders speech over 8-15s and the
streamed path queues phrases at sentence boundaries + synthesis time. The text was
driven by *delta arrival*, not by *what had actually been spoken*.

Now the transcript is **speech-locked**. A pure `SpeechCursor` + `PlaybackClock`
map the AudioTrack's playback head to a character position, so text is revealed at
the pace audio is actually played — and the not-yet-spoken tail is **dimmed** (0.35)
so you see what's coming but the "spoken now" boundary moves with the voice.
- **Reveal with the voice**, **dimmed unspoken tail**, **freeze on cancel**
  (barge/hush/hangup freezes the cursor before the track is torn down; never
  auto-completes, never rewinds).
- **Undeceitful default** — if no audio reaches the engine (text-only settle,
  system-TTS) the cursor stays `-1` and the display is exactly the plain behavior.
- Design get: the streamed reply path never emits `onState("speaking")` (that's the
  one-shot path only), so the loop arms on `"thinking"` and the cursor decides.

## State-driven presence motion (was 0.5.0-previewB)
The being LOOPED; it did not report. Motion was chosen at the call site — `onState`
pushed a shape string plus a made-up level, `onLog` pushed a tool motif, and
everything else fell to the same dispersed idle aura. So a 15-second provider hang
and a finished turn rendered *identically*.

Now the motion is a function of the work. A pure `MotionState` table decides **what
the being is doing**; `renderParams` turns that into drive values; the existing
per-frame generative render reads them. No second animation loop.
- **listening → breathing**, **thinking → gathering**, **speaking → voice-coupled
  pulse** (amplitude locked to real AudioTrack RMS, ~20ms window at the playback
  head), **provider stall → waiting constellation** (the presence-with-latency win),
  **barge/hush → recoil** (one-shot ~350ms), **tool call → the tool's motif**,
  **tool result → satisfied shimmer**, **natural retire → settle**, **sustained
  rest → drift**.
- Two precedence rules carry it: STALL outranks ambient signals; edge events
  outrank the stall. The stall watch is deliberately not armed while audio is
  audible (a long reply streaming in and then speaking is the being working).

## Verified (this build)
- Independent gate (fresh clone, real SDK, purge + `--rerun-tasks`) on the merged
  tip: exit 0, 17 test classes, all 20 `MotionStateTest` + 14 `SpeechCursorTest`
  cases executed.
- Signed: versionCode 86 / versionName 0.5.0 inside the artifact.
- KEEP-list zero-touch across both features: `silenceAll`, `StreamFence`,
  pause-first teardown, `StreamRetirementState`, focus, `BargeGate`/`EscapeRule`,
  `ReplySettleRule`, `session_turns`, `CrawlView` role logic, transcript composition.

## Device test
1. Ask for a multi-sentence answer — text advances *with* the voice, dims the
   unspoken tail; barge mid-reply and confirm it freezes instead of running ahead.
2. Ask for something with tool calls — watch the being gather, take the tool motif,
   shimmer on each result. Let a slow provider hang (waiting constellation that
   widens), then barge mid-reply (one flinch back to listening).

## Notes
The Enhanced Realtime *architecture* (VOX.md Contract+Soul bridge, mind/soul split,
Miles's rules, semantic barge gate) is designed and specified in
`docs/DESIGN-enhanced-realtime-voice.md` — this release is the presence layer that
ER will use. Not yet device-verified in this build: playback-head reliability
behavior (A) and the stall/amp precedence on a real device (B) are proven against
the code + pure tests only, stated honestly.
