# Hermes Vox 0.4.0.4 — release notes

> The ghost-voice release. In 0.4.0.3 a hush could silence a reply and then be
> answered, one second later, by the *entire* reply spoken again — through an
> AudioTrack nothing in the app could stop, still talking after the call ended and
> the foreground service was destroyed. Two independent defects, both proven from
> the field log below. Zero behaviour change to silenceAll, the fence/retirement
> ordering, focus, route, endpointing, the barge decision, or the probes.

## Why — a cancelled turn re-spoke itself

Field log: the 0.4.0.3 build field log (removed in the log sweep). Call 1, gen=1:

```
22:17:24.592 … 22:17:31.406   piper chunk ×5          (~13s of streamed speech)
22:17:31.460  event=tts-stop reason=hush msSinceBarge=0
22:17:31.461  event=gate-release gen=1 reason=hush
22:17:31.462  turn done: gen=1 len=199 err=            <-- done lands 1ms later
22:17:32.862  piper generated 225146 samples (text 199 chars)   <-- ALL of it
22:17:39.744  event=call-end / fg-service destroy
22:17:43.209  piper played 225146 samples (waited 200ms)        <-- still talking
```

**Defect 1 — the settle branched on the wrong witness.** `settleReply`
(`VoiceController.kt`) chose its path with `if (streamed && supportsStreaming)`.
But `streamed` is cleared by `stopStreaming()`, which every cancel path runs
through `silenceAll` — so by the time `done=true` arrived (one millisecond later)
`streamed` was already `false` and the completed turn fell into the text-only
branch, `else if (shouldSpeak()) -> speak(finalText, gen)`. `streamed` never meant
"this turn was cancelled"; it only means "a streaming turn is currently open". A
half-spoken, user-silenced reply therefore looked exactly like a fresh text-only
one, and was synthesized again in full: 225146 samples = 10.2s of audio for 199
chars the user had already heard 13s of and explicitly stopped.

**Defect 2 — the one-shot speak path was uncancellable.** `SherpaTts.play()` built
a **bare, private** `AudioTrack`: not stored in `streamTrack`, not taken under
`trackLock`, not gated by the `StreamFence`. `stop()` fences and tears down the
*stream* track only, so hush, a second hush (22:17:36.265), call-end
(22:17:39.745) and service destruction all hit a track that was not there, while
the real one wrote the whole reply in a single `WRITE_BLOCKING` call and then
waited out the playback head. Nothing in the app could interrupt it — the ghost
outlived every teardown and only stopped when the audio ran out, at 22:17:43.

The two defects compound: #1 starts a speech nobody asked for, #2 makes it
unstoppable. Either alone is a bug; together they are the reported symptom.

Call 2 in the same log (30s stall, `len=0`, `err=hermes stream: ended without
completion`) is the *healthy* shape and is untouched here: the error branch never
speaks. It does post a `releaseTurnGate(gen, "reply-error")` for an already-stopped
turn, which lands 645ms after `reason=stop` — but the duplicate latch and the T3
epoch guard absorb it (one `event=turn` in the log, no second speech). Checked,
not changed.

## The fix

**1. A settle may never (re)speak a reply that was cancelled or already heard.**
New pure rule `ReplySettleRule.decide(...)` -> `RETIRE_STREAM | SPEAK |
SILENT_SETTLE | DROP`, called from `settleReply`. Two per-turn witnesses, both
already maintained, replace the `streamed` guess:

- **gate released** — `gen != turnGen || voiceState.released()`. Every cancel
  (bargeIn / hush / endCall-stop / focus-loss) releases this gen's gate through
  `silenceAll`; a settle for a released gate is a settle for a turn the user
  already ended. It renders the text, starts no speech, and re-releases nothing.
  (`VoiceLoopState.released()` is a new read-only accessor over the existing
  `releasedForEpoch` latch — no new state.)
- **already audible** — the existing per-turn `firstAudioPushed` latch, set at the
  `streamChunk` seam once a real write reached the playback track. A reply that
  was even partly audible is never spoken a second time.

Order matters and is asserted: `streamed && streamingEngine` -> `RETIRE_STREAM`
is decided **first**, because `firstAudioPushed` is true for every healthy
streamed reply and checking the cancel witnesses first would starve the tail and
wedge the gate until the 60s backstop (#71 regression). Text-only turns — never
streamed, never audible, gate live — still `SPEAK` exactly as before.

**2. The one-shot speak path now uses the streaming path's own discipline.**
`SherpaTts.play()` installs its track as `streamTrack` under `trackLock`, opens
the existing `StreamFence` for the utterance, slices the write into ~2s
sub-writes with a fence check before each (the same F2 pattern as `streamChunk`),
and drains the tail through `finishStreaming()`. So `stop()`/hush/call-end pause
it on the caller's thread in milliseconds (pause-first, F1) and hand
stop/flush/release to the single teardown thread — the same `msSinceBarge` budget
streaming already meets, with the #7 never-release-while-writing invariant intact.
No parallel mechanism was introduced.

**3. `StreamFence.stopEpoch` — a cancel token for the synthesis window.**
`allowed` is level-triggered, so it cannot answer "did a stop land while I was
busy?". That matters because one-shot synthesis takes ~1.4s (the log's 199 chars)
and `play()` must *open* the fence to write — it would have re-opened the very
fence the hush closed. `speak()`/`speakBlocking()` capture `stopEpoch` before
synthesis; `play()` re-checks it **inside `trackLock`**, the same monitor
`stopStreaming()` takes, so the check-and-open is atomic against a racing stop. A
changed token drops the utterance unplayed (`event=tts-speak-drop`).

## Honest risk

- **The `streamTrack` slot is now shared with one-shot playback.** If a one-shot
  `speak()` ever overlaps a live streaming reply, the second track displaces the
  first in the slot and the displaced track is leaked (never released) rather than
  torn down. The controller keeps them apart today — `speakGlue` returns early
  while `speaking`, `speak()` calls `stopTts()` first — and the previous behaviour
  in that same race was *worse* (two tracks audible, the one-shot permanently
  unstoppable). It is still a leak, not a crash: the #7 rule (never flush/release
  while `writing`) is untouched, and the teardown's bounded poll leaks rather than
  races. If overlap is ever observed, the fix is a second track slot, not a second
  fence.
- **A dropped utterance still reports done.** `speak()`'s `onDone` fires after a
  drop, so the controller still posts `releaseTurnGate(gen, "speak-done")` for a
  gen whose gate is already gone; the duplicate guard logs it as
  `result=duplicate`. Harmless, and after fix #1 the cancelled path no longer
  reaches `speak()` at all — but the log line can still appear via glue.
- **`DROP` is silent by design.** A cancelled turn's reply text is still rendered
  (`onReply`), but no state transition and no gate release happen from the settle.
  If a future cancel path forgets to release the gate, `DROP` will not cover for
  it and the turn will sit until `TURN_GATE_TIMEOUT_MS` (60s). That is deliberate:
  the alternative is what shipped in 0.4.0.3 — the settle guessing.
- **Sliced writes change nothing about latency but do change the log.** `piper
  played N samples` no longer carries `(waited Xms)`; a cut utterance logs
  `event=tts-speak-cut at=… of=…` instead of nothing.
- **Not verified on device.** Everything above is proven from the field log and
  unit tests; the device test below is what confirms it.

## Tests

`:app:testDebugUnitTest` — **103 tests, 0 failures**. New/changed:

- `ReplySettleRuleTest` (9): `hush_then_done_must_not_respeak` (the exact field
  shape — streamed cleared, gate released, audio already emitted -> `DROP`),
  `hush_before_any_audio_still_must_not_speak` (a cut mid-first-chunk leaves
  `firstAudioPushed` false, so the gate is the only witness),
  `already_spoken_never_respeaks_even_with_a_live_gate`,
  `clean_text_only_turn_must_speak` (streaming and non-streaming engines),
  `done_while_streaming_must_retire` (#71, including with audio already pushed and
  with speech toggled off), `speech_off_settles_quietly_and_still_releases`,
  `cancelled_beats_speech_off`, and an exhaustive 64-row sweep pinning the outcome
  census (16 retire / 36 drop / 9 silent / 3 speak).
- `StreamFenceTest` (5 -> 7): `stop_epoch_is_the_one_shot_cancel_token` and
  `stop_epoch_advances_on_every_stop`. The five original fence rows are unchanged.
- `TurnGateReleaseTest` (3 -> 4): `released_reports_whether_this_turns_gate_is_
  already_gone` — false while live, true after release, false again after `arm()`.
- `BargeGateTest`'s 19 rows are untouched, as are the retirement, chunk-boundary,
  latency and STT suites.

## Version

- `versionCode 83` · `versionName 0.4.0.4`.

## Device test

Ask something long, let it start speaking, then hush. The log must show
`event=gate-release … reason=hush` followed by `event=settle-drop gen=… reason=
cancelled chars=…` — and **no** `piper generated` line after it. Then send a
text-only turn with the voice channel open and confirm it still speaks. Finally,
hush a reply during the ~1.4s synthesis window of a non-streamed speak: the log
must show `event=tts-speak-drop reason=stopped-during-synth`, and silence.
