# Barge-in regression analysis — 0.8.1 (Go core) vs 0.9.0 (Kotlin core)

Branch `fix/barge-090`. Inputs: the 0.8.1 tree (`origin/main`, release commit
`9058fa8`), the 0.9.0 tree (`origin/testing`, `a41636b`), and a 0.9.0 Pixel 7 field
logcat (2026-10-20, 00:14–00:23). The field report: *"it truncates what I say and it
keeps talking even when I'm talking."*

## The finding that changes the question

**The Go core never owned the barge path.** `go-core/voice/*.go` is the gateway/stream
layer only (`stream.go`, `responses.go`, `runs.go`, `entity.go`, `gateway.go`). It has no
audio, VAD, RMS, TTS or turn-gate code. In 0.8.1 the microphone, the barge drain,
`BargeGate`, `BargeCarry`, `EndpointRule` and the turn gate were already Kotlin, in
`go-core/android/app/src/main/java/com/hermesvox/`. #126 replaced the Go
`mobile.HermesSession` (gomobile) with `com.hermesvox.core.HermesSession`. It did not
touch the audio path.

So I compared the 0.8.1 Kotlin audio path against the 0.9.0 one, and the Go stream
layer against `core/`:

- `BargeGate.kt`, `BargeCarry.kt`, `EndpointRule.kt`, `EchoTailRule.kt`,
  `EarlyStartRule.kt`, `VoiceLoopState.kt`, `ErBargeGate.kt`, `StreamFence.kt`,
  `StreamPollGate.kt` and `ReplySettleRule.kt` are **byte-identical** between the trees.
- The 0.9.0 `VoiceController` drain is a pure extraction of the 0.8.1 inline drain into
  `BargeAudioPath` (#42). The same operations run in the same order: carry push → both
  leaky accumulators → VAD recency → `decide` → carry fire / peak update. Every barge
  constant is equal.
- The capture loop (`VoiceController.kt:380–560`) is identical in both trees apart from
  the import line.

**I found no change between 0.8.1 and 0.9.0 that explains "barge-in feels worse".** The
defects below are in both versions. What the field log shows is how they behave on
long, slow replies: 40 s of tool-backed generation, then 1.5–2 k characters of speech.
Whether 0.8.1 felt better because its replies were shorter or faster, I **cannot tell**.
I have no 0.8.1 field log of the same conversation.

## What the field log actually shows

| Symptom | Evidence (0.9.0 log) | Cause | Fixed here |
|---|---|---|---|
| Reply keeps talking over the user | gen=2 gate close 00:17:30.483 → `cue=listening` at 00:18:30.548 (= 60.07 s), while TTS chunks keep coming until 00:20:31. gen=3: 00:20:31.770 → segment opened 00:21:31.8. No log line marks either exit. | The drain gives up at a flat `TURN_GATE_TIMEOUT_MS` = 60 s **from gate close**. The reply was still speaking, so for the rest of it there was no barge check. | `TurnGateCeiling` (`aa350bd`) |
| User's words truncated | `endpoint-extended ms=31798` → `ms=1274 speechMs=896 "and not"`; `ms=33015` → `ms=4858 speechMs=4480 "okay i just want to point out in the"`; `ms=118642` → `ms=2106 "And you explain that part."`. Every clip = 250 ms pre-roll + speech + ~128 ms. | `EndpointRule`'s past-`maxMs` rule (close on the first >100 ms pause) runs on a clock that starts at **mic open**. Once the mic has been open 15 s, any utterance closes at its first breath. The 60 s exit above is what left the mic open under the reply. | `EndpointRule.utteranceMs` (`9cac91b`) |
| Talking over the reply doesn't fire | gen=2 near-misses at 0.113/0.160/0.197, `vad=false`; fired at 0.208 `vad=true` | With VAD false only the level escape can fire, and its 0.6 break-even duty sits above the duty a syllabic voice spends over the 0.130 bar. See the replay. | `SUSTAIN_LEAK_MS` 96 → 48 (`2cb114e`) |
| Generation-time barges (`firstText=-` ×3) | gen=3/4/5 fired at rms 0.294/0.109/0.156, all `vad=true`. The next transcripts are "Yeah, you can come back. I think it is working.", "Are you there?" (after a 40 s stall with `stream-stall`), "I'm going to hang up and try again." | **Not a detector fault.** The user really was speaking. Generation-mode barge (speech during generation cancels the turn) is in both versions by design. | No. This is a product decision; see open questions. |

**Corrections to the brief's reading of the log:**

- The near-miss lines don't prove "one above-bar read then a dip". The probe is
  rate-limited to one line per 2 s and prints the **first** read of a run, so
  `sustainedMs=64` is what any run looks like at its first read. That's why
  `event=barge-stat` exists now.
- `barge-carry` does **not** seed from the detection point. `leadMs=1068` of `ms=1132`
  means the carry began ~770 ms before the fire, plus the 300 ms pre-roll. All four
  post-barge transcripts are whole sentences. The fragments come from the endpointer,
  not the carry.
- The carry does have a real hole, which the replay found: a fire more than ~1.7 s
  after the onset fell outside the 2 s history ring (fixed: the ring is now 4 s).

## Behaviour table

Verdicts: **preserved** / **changed deliberately** / **changed by accident** /
**missing**. "Go" refers to the 0.8.1 tree (`go-core/`), "Kt" to the 0.9.0 tree. Paths
are relative to `android/app/src/main/java/com/hermesvox/` unless they start with
`voice/` or `core/`. Line numbers are pre-fix (0.9.0) unless marked *(branch)*.

### Barge path

| Behaviour | Verdict | Go file:line | Kt file:line | Notes |
|---|---|---|---|---|
| When the check runs: playback after the grace window, plus generation mode (`turnInFlight && !speaking`) | preserved | VoiceController.kt:722–723 | VoiceController.kt:732–733 | |
| Playback grace 500 ms (`DEFAULT_GRACE_MS`) | preserved | BargeGate.kt:75 | BargeGate.kt:94 *(branch)* | |
| What it listens to: one `AudioRecord`, `VOICE_COMMUNICATION` (platform AEC/NS) + an extra `NoiseSuppressor` | preserved | VoiceController.kt:382, 392 | VoiceController.kt:382, 392 | Single capture in both. No second recorder. |
| Stale-buffer flush at gate close (64 non-blocking reads, unchecked) | preserved | VoiceController.kt:681 | VoiceController.kt:685 | |
| Floor 0.10, bar ×1.3, VAD sustain 200 ms, escape 200 ms, VAD recency 250 ms, cap 800 ms | preserved | BargeGate.kt:72–113 | BargeGate.kt:91–132 *(branch)* | |
| Sustain leak 96 ms (break-even duty 0.6) | **changed deliberately (this branch)** → 48 ms | BargeGate.kt:104 | BargeGate.kt:125 *(branch)* | Evidence in `BargeReplayTest`. The trade is stated in `BargeGateTest`. |
| Peak-crest escape (0.6.8): peak tracked on non-fire reads only, reset below 0.7×floor | preserved | VoiceController.kt (inline, else-branch of decide) | BargeAudioPath.kt `process` | The #42 extraction kept the order. |
| What happens to the triggering audio: 2 s history ring, onset = VAD ∨ level>floor, 300 ms pre-roll, 400 ms quiet reset, seeds the next segment | preserved in 0.9.0 | BargeCarry.kt:30–35; VoiceController.kt:787 | BargeCarry.kt:30–35; BargeAudioPath.kt `process` | |
| Carry history ring 2 s | **changed deliberately (this branch)** → 4 s | BargeCarry.kt:32 | BargeCarry.kt:32 *(branch)* | A late fire lost the onset (replay: ttf 2,688 ms vs 2,000 ms of carry). |
| How a cancel reaches the stream: `genCancelled` → `silenceAll` → `stopTts`/`stopStreaming`/`cancelStream` → gate release on main | preserved (call chain) | VoiceController.kt:1808 (bargeIn; `genCancelled` at :1824) | VoiceController.kt:1849 | Measured fast in the field: `tts-stop msSinceBarge=4–9`. |
| `cancelStream`: retire the map entry, then abort the request | changed deliberately (#126) | voice/stream.go:560 (ctx cancel) | core/Stream.kt:211 (`HttpURLConnection.disconnect`) | Same effect on the gateway. See the connection-reuse row below. |
| `stop()` sets `genCancelled` (hang-up stops the poll loop) | changed deliberately (a41636b) | VoiceController.kt:877 (absent) | VoiceController.kt:871 | Fixes the 0.9.0 `no such stream` poll storm. 0.8.1 had the same gap. |
| Near-miss probe, rate-limited, first read of a run | preserved | VoiceController.kt (inline) | VoiceController.kt:765 | Can't show duty cycle. `event=barge-stat` added *(branch)*. |

### Capture loop

| Behaviour | Verdict | Go file:line | Kt file:line | Notes |
|---|---|---|---|---|
| Mic open for the whole call; `r` keeps recording through transcribe and the turn gate | preserved | VoiceController.kt:409–560 | same | |
| What closes a segment: 800 ms pause; past `maxMs`=15 s, a >100 ms pause; opt-in hard ceiling | preserved | EndpointRule.kt:40; VoiceController.kt:496 | EndpointRule.kt:40; VoiceController.kt:501 | |
| **Clock for the past-`maxMs` rule = mic open** | **defect in both** → fixed *(branch)*: speech onset | VoiceController.kt:496 (`now - segStart`) | EndpointRule.kt:56, VoiceController.kt *(branch)* | This is the truncation in the field log. |
| Pre-roll 250 ms ring before VAD onset | preserved | VoiceController.kt:441 | same | |
| Across a barge: carry seeds the next segment in speech, no recorder stop, no cooldown; echo guard skipped | preserved | VoiceController.kt:820–846 | VoiceController.kt:806–832 | |
| Echo-tail guard after a reply (`EchoTailRule`) | preserved | VoiceController.kt:844 | VoiceController.kt:830 | |
| Partial-STT early start (900 ms cadence, 6 s tail snapshot) | preserved | VoiceController.kt:~505 | same | |
| `LatencyStats.pushStt` measured from mic open, not speech end | in both, not fixed | VoiceController.kt (segStart) | same | Telemetry only. It inflates `stt=` on a turn whose mic was open long (field: `stt=33043`, `stt=38230`). |

### Turn loop

| Behaviour | Verdict | Go file:line | Kt file:line | Notes |
|---|---|---|---|---|
| Drain ceiling: flat 60 s from gate close | **defect in both** → fixed *(branch)*: 60 s from last sign of life, 10 min hard | VoiceController.kt:153, 687 | TurnGateCeiling.kt *(branch)* | No line logged in either version. `event=gate-ceiling` added. |
| Gate-timeout warn needs 30 s of no activity | preserved | VoiceController.kt:160 | VoiceController.kt:160 | Every drain read counts as activity, so it can't fire during a drain. |
| Stream turn budget 120 s wall-clock (`StreamPollGate`) | preserved | StreamPollGate.kt:37 | StreamPollGate.kt:37 | |
| Poll-error cap 200 | preserved | VoiceController.kt:157 | VoiceController.kt:157 | |
| Idle watchdog 60 s, reset by any byte (keepalives included) | preserved (mechanism changed) | voice/stream.go:95 (`time.AfterFunc` + `ResponseHeaderTimeout` 60 s :115) | core/Stream.kt:106 (socket `readTimeout`, which also bounds the header wait) | Equivalent bound. |
| Push-side wake (`WaitStream` / `notify` capacity 1, drained by poll) | preserved | voice/stream.go:517 | core/Stream.kt:191 | |
| Terminal states: completed; incomplete-with-text = done; incomplete-empty / failed / error = failure; "ended without completion" | preserved | voice/stream.go:265–300, 370–375 | core/Stream.kt:335–360, 276–277 | Pinned by `StreamParityTest` over 10 fixtures (incomplete, error, timeout, barge, …). |
| One terminal state per turn; a failure with assembled text is spoken as a fallback | changed deliberately (#6) | VoiceController.kt (throws text away) | VoiceController.kt:1080, 1378 | |
| Threads: SSE consumer on its own goroutine / daemon thread; poll loop on the executor; drain on the capture thread; release on main | preserved | voice/stream.go:442 | core/Stream.kt:133 | |
| Connection reuse: HTTP/2 + idle pool (`MaxIdleConns` 4, 90 s) | **changed by accident (unmeasured)** | voice/stream.go:111–113 | core/Stream.kt:281 (`HttpURLConnection` reuses only a body read to its end) | A cancelled (barged) stream tears its connection down, so the next turn pays TCP+TLS. Under Go with HTTP/2 a cancel reset only the stream. **I could not tell** whether the gateway negotiates HTTP/2, and the log has no handshake timing. |
| Voice-turn prefix (`UserTurnPrefix`) only in Enhanced mode | preserved | voice/responses.go:70; VoiceController.kt:1104 | core/ResponsesClient.kt:86; VoiceController.kt:1104 | The field session is Realtime (`voiceTurn=false`), so it ran without "2-4 sentences". That explains the 2 k-character replies. Same in 0.8.1. |

### Speech-locked reveal and TTS chunk accounting

| Behaviour | Verdict | Go file:line | Kt file:line | Notes |
|---|---|---|---|---|
| Reveal driven by the playback head over `(chars, samples)` chunk stats | preserved | VoiceController.kt:1562–1600; SpeechCursor.kt | VoiceController.kt:1607–1668 | The Go side had none; this was always Kotlin. |
| Synthesis-gap overrun holds the last honest position | changed deliberately (a41636b) | SpeechCursor.kt (no `isOverrun`) | SpeechCursor.kt:74; VoiceController.kt:1661 | I checked it and think it's correct. At a natural end, `stopReveal` (MainActivity.kt:108) repaints the full reply, so the hold can't strand the tail. Cosmetic residue: if the head runs past the registered total during the last chunk, the final word stays dim until `stopReveal`, at most one tick past the end of speech. |
| No audio yet but chars revealed: hold instead of painting everything | changed deliberately (a41636b) | VoiceController.kt:1593 | VoiceController.kt:1640 | |

## Numbers or rules in Go with no Kotlin counterpart

Checked one by one in `voice/*.go` and `mobile/session.go`:

- `streamIdleTimeout` 60 s → `Streams.idleTimeoutMs` 60 s. Present.
- Dial 10 s / TLS 10 s / response-header 60 s → `CONNECT_TIMEOUT_MS` 10 s plus the read
  timeout, which bounds the header wait. There is no separate TLS bound;
  `HttpURLConnection`'s connect timeout covers the handshake. Present in effect.
- `MaxIdleConns` 4, `IdleConnTimeout` 90 s, `ForceAttemptHTTP2`: **no counterpart**
  (row above).
- 120 s context on blocking `Send` (mobile/session.go:150, 161) → `Entity.exchange`
  timeouts. I didn't trace every non-stream call; the voice path uses the stream.
- `/v1/runs` cancellable path (`runs.go`, `CancelRun`): no counterpart in `core/`. But
  neither tree's app code calls `StartRun`/`CancelRun` (grep across both
  `com/hermesvox/*.kt` finds nothing), so the voice turn never used it. Not a
  regression.

**None of the "lost tuning" is in the Go core.** The barge tuning lives in the Kotlin
audio path, and it is unchanged from 0.8.1.

## What the fix changed, and what proves it

1. `event=barge-stat` (dd): per ~1 s of checked playback reads it logs frames,
   floorPct, barPct, vadPct, peak, mean and both sustains (`352b720`).
2. `BargeReplayTest`: 9 tests driving the production `BargeAudioPath` (`2cb114e`). Inputs:
   - the field near-miss peaks;
   - syllabic speech at 3–6 Hz, plateau and crest shapes, peaks 0.13–0.21, over a 0.09
     echo bed, with VAD held false, 10 phases each;
   - measured-band echo and crest echo up to 0.17, for 120 s each.
   
   It runs the old and the new leak through the same code:
   - Old 96 ms: crest speech at 0.21 fires 0–1/10 at 3/4/6 Hz.
   - New 48 ms: plateau speech fires 10/10 within 1.5 s at all rates and peaks; crest
     speech at 0.21 fires 10/10 within 2 s.
   - Echo never fires under either.
3. The stated trade (`BargeGateTest`): the 0.4.0.3 envelope, 50% duty with crests at
   0.15–0.17, now fires at the default floor. It is blocked at `barge_rms_min=0.14`.
4. `TurnGateCeilingTest` (field timestamps) and `EndpointRuleTest` (the "and not" case)
   pin the other two fixes (`aa350bd`, `9cac91b`).

## What I could not prove

- **Nothing here ran on a microphone.** The replay's speech is a model (an envelope
  shape and a level), not a recording. It encodes the field's amplitudes, not its
  waveforms. Silero's real verdicts under echo aren't modelled. The replay holds VAD
  false, which is the worst case.
- Whether residual echo on other devices or volumes sits above 0.13 with a syllabic
  duty above 0.43. If it does, 0.9.1 can self-cut where 0.9.0 didn't. The
  `event=barge-stat` lines from the next field session will show it: watch `barPct`
  during playback with nobody talking. The mitigation is `barge_rms_min` 0.14.
- The lower leak also makes generation-mode barge somewhat easier to trigger (side
  talk, TV). Not measured.
- Whether 0.8.1 felt better, and why (see top).
- The gateway's HTTP version and whether a fresh handshake after each barge is
  noticeable.

## Open questions for the owner

1. **Generation-mode barge kills the answer.** In the log, "Are you there?" after a
   40 s stall cancelled the stalled turn and started a new one. That's arguably right.
   But side speech during a tool call also cancels the work. One option: a
   generation-mode utterance becomes a queued follow-up instead of a cancel. That's a
   behaviour change, so I didn't make it.
2. **Realtime mode sends no voice-turn prefix.** 2,077-character spoken replies are what
   stretched the turn past the old 60 s ceiling. Whether Realtime should carry the
   "2-4 sentences" instruction is a product call.
3. **F3b (adaptive floor):** not done. The replay didn't need it. The better variant
   probably samples the playback grace window (pure echo) rather than call-start
   ambient, because echo is what the floor has to clear. `event=barge-stat` gives the
   data to decide.
