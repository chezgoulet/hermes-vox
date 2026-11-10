# #538 — findings

Branch `investigate/538-voice`, cut from `testing` at `f199b75b`. Line references marked
`@f199b75b` are to the code as it was reported against (0.9.1 behaviour, plus #549). Unmarked
references are to this branch. Every claim was checked against the code. **None of it was
observed on the reporter's device**, because no spoken turn could be produced on the test host.
Probabilities are my own estimates.

## Summary

The three symptom families are probably **one mechanism**, and it has two links. Both shipped in
0.9.1: #499 (the bounded TTS lifecycle and terminal steady-off) and #517 (streamed chunking).

1. **Re-warm cascade (A).** The first time a streamed chunk fails, the host re-warms Supertonic
   by building a *new* `SherpaTts` (`TtsEngineHost.kt:237-260`; `install` shuts down the old
   one at `:243`). Every `SherpaTts` owns its own `StreamFence` (`SherpaTts.kt:152`), and a new
   fence starts closed (`StreamFence.kt:14-15`, `started=false, stopped=true`). The open reply
   only ever called `startStreaming()` on the *old* engine (`VoiceController.kt:3156` →
   `streamBegin`). The controller's `tts` is a live getter on the host's engine
   (`VoiceController.kt:104`). So once the re-warmed engine is ready, every later chunk of the
   same reply hits `if (!streamFence.allowed) return false` (`SherpaTts.kt:471@f199b75b`).
   `StreamSynthResult.failed(false, false, false)` is `true`, and the worker calls
   `noteFailure("stream-synth")` (`VoiceController.kt:3254-3262@f199b75b`). That is the second
   failure, and it degrades to the system voice (`TtsEngineHost.kt:394-407`). **One genuine
   fault spends the re-warm and the degradation inside a single reply.** If any further failure
   happens on the system voice, `steadyOff` (`:334`) is terminal until a new host, which `/new`
   provides (`MainActivity.kt:3318` → `endCall()` at `:3432`).
2. **DROP wedge (C, and B).** If the gateway's `done` arrives while the engine is being swapped,
   `settleReply` sees `streamed=true`, `streamingEngine=false` and `genSpokeAudio=true`. That can
   happen mid-re-warm, because `supportsStreaming = isWarm` (`SherpaTts.kt:155`) is false until
   the model loads; it also happens after the degradation, because the system voice never
   streams. `ReplySettleRule` returned **DROP** (`ReplySettleRule.kt:71-73@f199b75b`). DROP
   never calls `streamFinish`, so `sFinal` is never set. The stream worker then waits on its
   queue indefinitely: the inner wait at `VoiceController.kt:3204` re-checks only
   `sFinal`/`sClosed`. While the worker waits:
   - it never posts `onState("idle")` / `releaseTurnGate(…, "stream-done")` (`:3333`);
   - a streamed reply never posts `"speaking"` (the only `onState("speaking")` is the one-shot
     path, `:3773`), so the screen stays on `"thinking"` → **"The entity is working…"**
     (`MainActivity.kt:2471`);
   - the gateway's turn is already `done`, so **btop on the gateway shows nothing**;
   - the barge drain counts `sRunning` as alive (`VoiceController.kt:1427`), so
     `TurnGateCeiling.IDLE_MS` never fires and the gate stays shut until `HARD_MS` = 600 s
     (`TurnGateCeiling.kt:34`). The capture loop only drains for barge-in and never endpoints a
     new utterance. This is **"STT compromised"**;
   - the text is still delivered (`listener?.onReply(finalText)`, `VoiceController.kt:3032`),
     which matches **"text-based answers"**.

Both links are proven by pure-JVM tests (see *Patch*). What I **cannot** establish is the
*first* fault that starts the cascade (see A, "first trigger"), or why "Silence notifications
during a call" correlates with it.

---

## A. Voice demotion → system voice → "spoken replies off" until `/new`

**Mechanism.** The re-warm cascade above. The ladder `rewarm → degrade → steadyOff` is in
`TtsEngineHost.recover` (`:375-410`). The terminal flag is set at `:335`. The line the reporter
saw ("spoken replies are off") comes from `onTtsState` → `onSpokenReplies(issue, true)`
(`VoiceController.kt` `onTtsState`, rendered at `MainActivity.kt:2784`).

**Confidence**
- The cascade exists in the 0.9.1 code and turns one streamed fault into a degradation within
  the same reply: **0.95**. It is reproduced by `StreamChunkResultTest.before_538_one_bad_phrase_spent_the_whole_voice_budget`
  against the real `TtsEngineHost`.
- It is the mechanism operating on the reporter's device: **0.6**. It needs a reply that is
  still streaming when the re-warm finishes. Supertonic's load-plus-warm time is a few seconds
  and voice replies stream for many seconds, so that is likely but unmeasured.
- The final step (system voice → off) has a *specific* cause I have not identified: **0.3** for
  my leading candidate, the system engine's watchdog (`WarmTts.kt:160`,
  `text.length * 90 ms + 8 s`). It can expire on a long, number-heavy reply, because the system
  voice speaks the *whole* reply as one utterance (`ReplySettle.SPEAK`) and spoken length can
  far exceed character count. Any single `FAILED` on the system voice after the degradation
  reaches `steadyOff`.

**First trigger (unknown; candidates).** Under the old reading, any of these made
`streamChunk` return `false` on a live reply:
1. A track that could not be built (`SherpaTts.kt:494-496@f199b75b`). This is **#549's
   defect on the streaming path**. #549 fixed only `play()`, the one-shot path.
2. A genuinely silent synth (`audioIsSilent`, `:479-482@f199b75b`), for example a 30-character
   chunk (#517) whose letters Supertonic's indexer cannot voice.
3. A synth or write exception (`:527-531@f199b75b`).
4. `tts == null` on an engine already shut down by a swap (`:475@f199b75b`). This one is only
   reachable once a swap is under way.

I cannot rank 1–3 without the log. The patch makes (1) and (4) cost nothing, and (2) and (3)
spend only what they should.

**Killed**
- *"#549 is the cure."* Falsified. The streaming worker never touches `play()`. Supertonic
  replies go through `streamChunk` (`VoiceController.kt:3221`, `supportsStreaming=true`), and
  its failures went to `noteFailure`, which applied **no** `NOT_THE_ENGINE` filter
  (`TtsEngineHost.kt:366-372@f199b75b`). The demotion is fully reachable without `play()`.
  `StreamChunkResultTest` drives it through `noteFailure` alone.
- *"A barge or hush during a reply is read as a failure."* No. Those set `sClosed` or
  `genCancelled` first, and `StreamSynthResult.failed` excludes them. This held in 0.9.1 too.
- *"`CallQuiet` touches audio focus, the ringer, or the TTS stream."* No. It calls only
  `NotificationManager.setInterruptionFilter` (`CallQuiet.kt:77`), and only to
  `INTERRUPTION_FILTER_PRIORITY` (`:45`). Nothing in the app reads the interruption filter
  back. No code path links the setting to AudioTrack creation or to the TTS failure ladder.
- *"Model load fails under memory pressure."* Not excluded, but it is not needed to explain the
  ladder. A failed re-warm load produces `event=tts-degrade … reason=rewarm-failed`, which is
  distinguishable in a log. A related note: `SherpaTts.shutdown()` only nulls the reference
  (`SherpaTts.kt:610`). The old `OfflineTts` native memory is not released explicitly, so a
  re-warm briefly holds two Supertonic models. I did not change this (see `DECISIONS.md`).

**The DND correlation.** It is unexplained by app code: **0.15** that DND is causal through a
platform path. The plausible platform path is this: since Android 15, an app's
`setInterruptionFilter` activates an app-owned mode, and if that mode's policy blocks *media*,
`USAGE_MEDIA` playback (`SherpaTts.kt:200`; the system voice's default too) is muted by the
platform. That would make the voice *inaudible*. It would not by itself produce the app's
`FAILED` outcomes, so it cannot drive the ladder. I could not verify Android 17 / GrapheneOS
behaviour here. The remaining **0.85** covers confounding: longer calls and more replies
simply give the cascade more chances.

**What a log shows (current build)**
- For the cascade: `event=tts-fail engine=supertonic code=stream-synth` →
  `event=tts-recover engine=supertonic reason=stream-synth attempt=1` →
  `SherpaTts loaded: supertonic …` → a second `event=tts-fail engine=supertonic code=stream-synth`
  within the **same** `gen`, with **no** `reason=silent-stream` / `reason=stream code=` /
  `buildStreamTrack:` line before it → `event=tts-degrade from=supertonic to=system reason=stream-synth`.
  The tell is a second `stream-synth` that has no SherpaTts-side cause logged.
- Against it: every `stream-synth` is preceded by its own `reason=silent-stream` or `streamChunk:`
  error line, or the degradation reads `reason=rewarm-failed`.
- The off step: `event=tts-fail engine=system code=…` (`timeout`, `error-N`, `speak=-1`,
  `silent`) followed by `event=tts-unavailable engine=system … (call continues text-only)`.

**#549.** Adjacent, not on the path. It is the same defect class (a track failure read as engine
death) but on the one-shot path. The streaming path carried the cascade. Its
`NOT_THE_ENGINE` policy is now applied to the streaming path as well.

---

## B. STT compromised during the same episodes

**Mechanism.** The DROP wedge. With the gate shut, the realtime capture loop stays in its
single-capture barge drain (`VoiceController.kt:1424-1440`). `lastAliveAt` keeps moving because
`sRunning` is true, so the loop never returns to endpointing. The user's speech is never
transcribed as a turn until `HARD_MS`, a barge, a hush, or hang-up.

**Confidence.** That this mechanism exists: **0.9** (traced, and the settle rule is pinned by
`ReplySettleRuleTest.an_engine_swapped_under_a_heard_stream_still_retires`). That it is what the
reporter saw as "STT compromised": **0.55**.

**Killed or weakened**
- *An AudioRecord leak across turns.* The natural loop exit stops and releases the recorder
  (`VoiceController.kt` after `:1587`, `record?.stop(); record?.release()`). Weakened, not
  exhaustively excluded.
- *Shared audio focus with the TTS leg.* The TTS tracks request no focus. The only request is
  the call-level `AUDIOFOCUS_GAIN` in `MainActivity.kt:1901`. Not examined further.
- *The foreground service dying.* That would end the call, not leave it on "working". It does
  not fit the report.

**A log shows:** `event=gate-timeout await=turn-gate … streamed=… sRunning=true turnInFlight=false`
(`VoiceController.kt:1565`), and no `event=tts-retire gen=N` for the wedged gen. On screen: no
"Listening…" while the user speaks.

**#549.** Irrelevant.

---

## C. "The Entity Is Working" with no execution on the gateway

**Liveness, as the code defines it.** The turn's stall clock (`lastEventAt`) moves only when a
poll returns **events, `done`, or an error** (`VoiceController.kt:2683-2689`). SSE `:` keepalive
comments are discarded by the parser (`core/Stream.kt:1169`). They reset only the *socket* read
timeout (`core/Stream.kt:178`, 60 s). So liveness means "the gateway sent an event", not "bytes
arrived", and hypothesis (iii) is **killed**: keepalives cannot hold the stall guards off.

| Candidate | Verdict | Evidence | Log signature |
|---|---|---|---|
| (i) request never left the phone | killed as *indefinite* | `"thinking"` is set before submit (`:2511`). A blank stream id gives `ErHandoff.REASON_NO_STREAM`, then poll errors cap at `POLL_ERROR_MAX` → `stream-poll-abort` → idle | `event=stream-poll-abort` |
| (ii) accepted, run never started | bounded at 60 s → wait card, 120 s budget | `TurnWait.SILENT_STREAM_MS`, `TurnBudget` | `event=wait-silent`, `event=stream-silent` |
| (iii) keepalives count as alive | **killed** | see above | — |
| (iv) budget paused by approval / wait card | bounded unless an approval is stuck; the screen would show the card or "Waiting for your approval", not "working" | `:2621`, `MainActivity.kt` `base = if (approvalWaiting)…` | `event=approval-*` |
| (v) poll loop exits, UI not cleared | **this is the mechanism** — the DROP wedge | above | see Witness |

**Mechanism.** The DROP wedge: the gateway finished (`turn done: gen=N … len=…`), the poll loop
exited on `done`, the reply text was rendered, and the stream worker never retired. The screen
holds `"thinking"` for up to 600 s. `/new` ends the call, which closes the stream.

**Confidence.** That the mechanism exists: **0.9**. That it is the reporter's "working loop":
**0.55**. Note that btop on the gateway is weak evidence either way. A run that is waiting on a
remote model provider uses almost no gateway CPU, so (ii) and (iv) would look the same in btop
for their bounded 60–130 s. What separates (v) is the duration: minutes, not about two.

**#549.** Irrelevant to the wedge itself. The wedge needs an engine swap mid-reply, which a #549
track failure on the *one-shot* path cannot cause during a stream.

---

## Patch (three commits on this branch)

1. `fix(tts): a re-warmed engine is adopted by the open stream, not blamed`.
   `streamChunk` returns a `ChunkResult` (`StreamSynthResult.kt`). `NOT_OPEN` (a fence that was
   never opened, told apart by the new `StreamFence.opened`) is adopted: the worker opens the
   new engine's fence and retries the chunk once (`VoiceController.kt:3269`). `CUT` and
   `NO_ENGINE` blame nobody. A streamed track failure becomes `track-unavailable`, and
   `noteFailure` now honours `NOT_THE_ENGINE`. Tests: `StreamChunkResultTest` (7). It replays the
   0.9.1 cascade against the real host, then shows the patched reading keeps Supertonic.
2. `fix(turn): a heard stream retires even when its engine was swapped`.
   `ReplySettleRule` returns `RETIRE_STREAM` for `streamed && genSpokeAudio` regardless of the
   engine (`ReplySettleRule.kt`). The worker drains on whatever engine is live and releases the
   gate. Tests: `ReplySettleRuleTest.an_engine_swapped_under_a_heard_stream_still_retires`, plus
   the exhaustive table updated (retire 16→24, drop 36→28).
3. These documents.

What the patch does **not** do: it does not change the ladder's budget, the system voice's
watchdog, or `CallQuiet`. See `DECISIONS.md`.

---

## Witness — what the patched build emits that 0.9.1 cannot

| Line / state | Emitted when | Meaning |
|---|---|---|
| `W event=tts-stream-adopt engine=supertonic gen=<n> chunkIdx=<k>` | a chunk reaches a re-warmed engine mid-reply | **The cascade was about to fire and did not.** If the reporter's log has this line, link 1 was operating on their device. |
| `W event=settle-retire gen=<n> reason=engine-swapped engine=<token> ready=<bool>` | `done` arrives during or after an engine swap with audio already heard | **The DROP wedge was about to fire.** It should be followed within seconds by `event=tts-retire gen=<n>` and the screen leaving "The entity is working…". |
| `W event=tts-fail engine=supertonic reason=track-unavailable source=stream sr=<hz> chunk=<n>` then `W event=tts-fail engine=supertonic code=track-unavailable` and `D event=tts-recover-skip … reason=track-unavailable` | a streamed AudioTrack could not be built | First-trigger candidate 1, now free of charge. |
| `D event=tts-drop reason=cut\|no_engine source=stream gen=<n> chunkIdx=<k>` | a chunk wrote nothing for a reason that is not the engine's | Used to be a `stream-synth` failure. |
| On screen | — | After a voice swap, the status leaves "The entity is working…" when the reply's text lands, and "Listening…" returns. The voice line may still read "…using the system voice" if a *real* second fault occurred. |

A build without the patch cannot emit `tts-stream-adopt`, `settle-retire`, or
`source=stream` track lines. In 0.9.1 the same moments appear as an extra `code=stream-synth`
and an `event=settle-drop gen=<n> reason=already-spoken`. **That `settle-drop … already-spoken`
line, for a gen with no hush or barge before it, is the 0.9.1 signature of the wedge.**
