# Hermes Vox — Sprint 2 Plan Brief: Realtime Loop — re-arm + survive re-init

**Repo:** chezgoulet/hermes-vox · **Work clone:** /home/c/hermes-vox (Thelio) · **Branch:** feature/sprint2-realtime-loop
**Model (plan + build):** `deepseek/deepseek-v4-flash-vision-exp` (DeepSeek API, vision) · **Owner/approver:** Torc
**Review gate:** Torc adversarial read + compile + emulator test (`emulator-5554`).

## Sprint theme
**"The mic must stay loud."** On-device finding (0.3.22 log, 2026-08-27): the realtime loop works for a few turns (streams, replies, speaks) then **loses speech** — it neither listens nor speaks. This is the Realtime gate (#53). Goal: after EVERY turn — including a rejected short/noise utterance and after a foreground/background re-init — the loop reliably returns to listening (and can still speak).

## On-device evidence (the two anomalies)
1. **Pipeline re-initializes between turns.** Models reload (`SileroVadGate`/`SherpaTts`/`OfflineWhisperStt` "loaded") at 15:07:07 and 15:08:35 — a continuous realtime loop should load once. This is the foreground/background (`onStop`→stop(), `onResume`→re-init) teardown + rebuild the handoff flags as the ~26s dead window; each rebuild re-arms a fresh, fragile loop.
2. **A rejected noise turn ends it.** At 15:08:45 speech is detected → transcribed "(dog barking)" → correctly NOT sent to the agent (no `startStream`) → then `speech=false` at 15:08:53 and the loop **never re-arms**. After a short/noise turn (or a re-init) the mic loop stops listening.

## In-scope issues (from the tracker)
### Re-arm / state-machine reliability (the "stops after a turn" class)
- **#9** — unbounded `latch.await()` + a completion callback that can never fire → the loop hangs after a turn and never returns to listening.
- **#5** — toggling speak off mid-turn leaves the streaming worker unterminatable (every subsequent turn blocks in `sDone.await(120s)`).
- **#4** — the streaming worker's completion latch can be counted down by the PREVIOUS turn's worker → half-duplex violation / self-hearing / re-arm corruption.
- **#32** — `streamed` never reset to false; `streamFeed` appends on `supportsStreaming` when speech is off (queue grows unbounded) — stale streaming state across turns.
- **Rejection re-arm** — after a SHORT/noise utterance that is rejected (no agent turn), the loop must STILL re-arm to listening. (Tied to #9/#11/#20 semantics: the post-turn re-listen must run on EVERY settle path, including "rejected / no-reply / blank-text / noise".)

### Survive re-init / backgrounding
- **#10** — after `onStop`→`onResume` the controller is reused but `sttReady`/`ttsReady` are never reset; the app "looks warm" but `transcribe` returns null → listens but never hears.
- **#58** — an ACTIVE realtime call should continue as a **foreground service until hang-up** (survive backgrounding; no ~26s dead window). Christopher's decision: in-scope for the Realtime gate.
- **Ground rule:** the loop must reliably re-arm to listening after `onStop`/`onResume` and after ANY turn outcome.

## Deferred (not this sprint's core — do not expand scope)
- #7 (AudioTrack release under blocked write → SIGSEGV) + #8 (native use-after-free on background) — crash-hardening, separate sub-track.
- #20 / #43 (barge-in double-capture) — separate.
- #6 (failed-chunk falls back to speak) — only if it lands cleanly without scope creep.

## Definition of done
- The realtime loop returns to listening after EVERY turn: normal reply, rejected/short/noise utterance, empty/blank transcript, and speak-off.
- Realtime survives a background→foreground cycle (no permanent "lost speech"; the loop re-arms; models stay usable).
- 20+ consecutive realtime turns do not lose speech / do not hang.
- No regression: model downloader (Sprint 1), offline STT, warm Piper TTS, streaming.
- Deterministic, device-free tests for the re-arm state machine (#9/#5/#4/#32 class) and the re-arm-after-rejection path.

## Iron rules (build agent)
- Touch ONLY the realtime/voice-loop path: `VoiceController.kt`, `WarmTts.kt`, `SherpaTts.kt`, `MainActivity.kt` (onStop/onResume + re-arm), and loop/stream state. Do NOT touch `ModelCatalog`/`ModelDownloader` (Sprint 1 done) or the Go voice-backend layer unless it's the loop contract.
- Never commit secrets; keep the release convention (tag === versionName === APK, no leading `v`).
- Do NOT regress the model downloader, offline STT, streaming, or the warm voice.
- One coherent commit per fix; each compiles + its tests pass. Byte-precise edits for the loop state machine.
- Run the JVM/unit tests + emulator smoke (build + boot, no crash).

## Verification (review phase — Torc runs)
- Compile (`testDebugUnitTest` + `assembleDebug`) green.
- Emulator smoke: install + boot no crash; drive a few realtime turns via adb.
- Deterministic JVM test: drive the streaming-worker / re-arm state machine through `streamBegin → feed → finish` and the rejection/re-arm path; assert `sDone` reaches zero, `sRunning` returns to false, and the loop re-arms.
