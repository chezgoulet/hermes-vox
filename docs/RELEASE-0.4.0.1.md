# Hermes Vox 0.4.0.1 — release notes

> The barge-telemetry release: measurement-only probes for the missed-barge defect
> (deaf turns over long playback tails), a truthful gate-timeout warn, and the
> phantom turn on hangup fixed. Zero behavior change to barge, endpointing,
> silence, retirement, or focus.

## Barge near-miss telemetry (T1 — the probes)

Field evidence (0.4.0): turns over long playback tails (17s / 44s) logged ZERO
barge-in while the user spoke, with no way to tell *why* — three candidate causes,
indistinguishable from an absence of events. This release adds three dd-only
probes in the VoiceController drain loop (debug channel, file only in debug mode,
no behavior change, no extra allocation — they reuse the per-frame level /
sustainedMs already computed):

- `event=barge-gap gen= lastReadMs= speaking=` — fired when the time between
  `r.read()` returns exceeds 500ms while the gate is armed and the barge hasn't
  fired (CAUSE A: mic drain starved by a synth/write burst, so detection can't run).
- `event=barge-nearmiss gen= peakRms= vad= sustainedMs= floor=` — fired (at most
  one per 2s, while speaking) when the double gate came close but stayed false:
  level above the floor with the sustain still accumulating / VAD disagreeing, or
  level at >=0.7x the active floor with VAD agreeing (CAUSE C + threshold
  calibration data).
- `event=barge-skipcheck gen= why=state` — fired once per turn when the
  `!speaking && !turnInFlight` skip state is held for >3s inside a locked gate
  (CAUSE B: a reply still playing or a wedged turn with the speaking flag already
  false — the drain is deaf and the user's barge is being swallowed).

## Gate-timeout truth (T2)

The 60s warn used to stamp `after=` from the turn **close**, so a legitimate long
turn (43s model stall + 44s audio) warned falsely. The controller now tracks a
`lastActivityAt` touched at every SSE delta/event batch, every dequeued TTS chunk,
and every drain mic read; the `event=gate-timeout` W fires only when the gate is
past its ceiling AND nothing has happened for >30s (`sinceActivity=` added, `after=`
kept for history) — a genuinely stalled gate, not merely a slow one.

## Phantom turn on hangup fixed (T3)

`endCall` on a mid-generation turn could log a second `event=turn` (and bump
`session_turns`) for a turn the user never heard — after the `outcome=stop` turn —
because the stream abort's reply-error settle re-released after `stop()`'s state
reset cleared the duplicate latch. The settle emission is now guarded per settled
gate epoch: a gen that already emitted its summary logs no second `event=turn`.
`session_turns` keeps counting real completed or interrupted turns only.

## Version

- `versionCode 80` · `versionName 0.4.0.1`.

## Device test

Talk over a LONG reply repeatedly until the exported log shows
`barge-nearmiss` / `barge-gap` / `barge-skipcheck` lines that explain a missed
barge — then fix the real cause with data in hand.
