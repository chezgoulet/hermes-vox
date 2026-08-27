# Hermes Vox — Sprint 2 Plan Brief: Realtime Loop — re-arm + survive re-init + session isolation

**Repo:** chezgoulet/hermes-vox · **Work clone:** /home/c/hermes-vox (Thelio) · **Branch:** feature/sprint2-realtime-loop
**Model (plan + build):** `deepseek/deepseek-v4-flash-vision-exp` (DeepSeek API, vision) · **Owner/approver:** Torc
**Review gate:** Torc adversarial read + compile + emulator test (`emulator-5554`).

## Sprint theme
**"The mic must stay loud, and the agent must answer what you actually said."** On-device finding (0.3.22 log, 2026-08-27): the realtime loop works for a few turns then **loses speech** (neither listens nor speaks), AND the agent's replies are **off-topic** (a ~1,400-char monologue about *hardware research* in response to a simple "Hello"). Two root causes to fix — the **loop re-arm / state reliability** and the **session / conversation isolation**.

## On-device evidence (the anomalies)
1. **Pipeline re-initializes between turns** — models reload (`SileroVadGate`/`SherpaTts`/`OfflineWhisperStt` "loaded") at 15:07:07 and 15:08:35 — a continuous realtime loop should load once; this is the foreground/background (`onStop`→stop(), `onResume`→re-init) teardown + rebuild the handoff flags as the ~26s dead window.
2. **A rejected noise turn ends it** — at 15:08:45 speech → "(dog barking)" → correctly not sent to the agent → `speech=false` at 15:08:53 and the loop **never re-arms**.
3. **The agent answered the WRONG conversation** — you said "Hello, robot friend. How are you?" and it replied with a ~1,400-char monologue about **hardware research**; the "user" follow-ups were you responding to that off-topic content. So the agent's `/v1/responses` turn is carrying **context from a different conversation/session**, not the one you just started.

## In-scope workstreams

### WORKSTREAM A — Re-arm / state-machine reliability (the "stops after a turn" class)
- **#9** — unbounded `latch.await()` + a completion callback that can never fire → the loop hangs after a turn and never returns to listening.
- **#5** — toggling speak off mid-turn leaves the streaming worker unterminatable (every subsequent turn blocks in `sDone.await(120s)`).
- **#4** — the streaming worker's completion latch can be counted down by the PREVIOUS turn's worker → half-duplex violation / self-hearing / re-arm corruption.
- **#32** — `streamed` never reset to false; `streamFeed` appends on `supportsStreaming` when speech is off (queue grows unbounded).
- **Rejection re-arm** — after a SHORT/noise utterance rejected (no agent turn), the loop must STILL re-arm to listening. (Tied to #9/#11/#20 semantics: the post-turn re-listen on EVERY settle path, including "rejected / no-reply / blank-text / noise".)
- **Plan approach (already drafted by the Plan agent):** extract a pure-JVM `VoiceLoopState.kt` (epoch-scoped turn gate + streaming-worker state), wire `VoiceController` onto it, and funnel EVERY settle/error/reject/barge/speak-off path through one idempotent re-arm. See the existing Sprint-2 plan.

### WORKSTREAM B — Survive re-init / backgrounding
- **#10** — after `onStop`→`onResume` the controller is reused but `sttReady`/`ttsReady` are never reset; `isWarm()` stays true but `transcribe` returns null → listens but never hears. Fix: reset flags in `stop()`, add idempotent `ensurePipeline()` re-init in `start()`.
- **#58** — an ACTIVE realtime call should continue as a **foreground service until hang-up** (survive backgrounding; no ~26s dead window).
- **Ground rule:** the loop must reliably re-arm to listening after `onStop`/`onResume` and after ANY turn outcome.

### WORKSTREAM C — Session / conversation isolation (the "wrong context" cause)  ⭐ add this
- **Symptom:** the agent replied to "Hello, robot friend. How are you?" with a ~1,400-char monologue about **hardware research** — content from a session/conversation unrelated to the current input (user confirmed these are genuine replies, not captured voice). So the agent's `/v1/responses` turn carries **another conversation's context**.
- **Root-cause hypothesis (verify then fix):** the Vox app chains turns via **`previous_response_id`** (Go `HermesResponsesClient` / `mobile` session / `conversation.go`) and is reusing a **stale or other-conversation id** — e.g. a prior session that was about hardware research, or a **shared agent conversation** (the same Hermes agent fronts Telegram/desktop/relay/Vox). The agent therefore continues the wrong thread instead of listening to the current turn.
- **Investigate + fix direction:**
  - Find where `previous_response_id` is stored/reset across turns (Go `voice`/`mobile` + the app's session state + the Settings "New conversation — RESET" path). Confirm a new voice session starts a **fresh** agent conversation (no stale id).
  - Verify what context the gateway hands a `/v1/responses` turn — is it an agent-global/cross-client conversation that would bleed another client's content? If so, the Vox call needs its **own conversation namespace** / an explicit reset.
  - Ensure a new call/voice session **resets `previous_response_id`** (and the New-conversation RESET actually clears it).
  - The agent must respond to the **current** user turn, not a prior/side conversation. (Scope: the Go session/context wiring + the app's reset path — do NOT change the Hermes gateway itself.)

## Deferred (not this sprint's core — do not expand scope)
- #7 (AudioTrack release under blocked write → SIGSEGV) + #8 (native use-after-free on background) — crash-hardening, separate sub-track.
- #20 / #43 (barge-in double-capture) — separate (but note: single-capture discipline matters for Workstream A's re-arm).
- #6 (failed-chunk falls back to speak) — only if it lands cleanly without scope creep.

## Definition of done
- The realtime loop returns to listening after EVERY turn: normal reply, rejected/short/noise utterance, empty/blank transcript, speak-off; and survives a background→foreground cycle (no permanent "lost speech").
- **The agent answers what was actually said** — a fresh voice session starts a fresh conversation; no stale/other-conversation `previous_response_id`; the reply is grounded in the current turn (e.g. a greeting gets a greeting, not a hardware-research monologue).
- 20+ consecutive realtime turns do not lose speech / do not drift off-topic.
- No regression: model downloader (Sprint 1), offline STT, warm Piper TTS, streaming.
- Deterministic device-free tests: the re-arm state machine (#4/#5/#9/#32 class), the re-arm-after-rejection path, and the session-reset path.

## Iron rules (build agent)
- Touch ONLY the realtime/voice-loop path (Workstream A/B: `VoiceController.kt`, `WarmTts.kt`, `SherpaTts.kt`, `MainActivity.kt` onStop/onResume, loop/stream state) and the session/context wiring (Workstream C: Go `voice`/`mobile` `HermesResponsesClient`/`session.go`/`conversation.go` + the app's reset path). Do NOT touch `ModelCatalog`/`ModelDownloader` (Sprint 1 done) or the Hermes gateway.
- Never commit secrets; keep the release convention (tag === versionName === APK, no leading `v`).
- Do NOT regress the model downloader, offline STT, streaming, or the warm voice.
- One coherent commit per fix; each compiles + its tests pass. Byte-precise edits for the loop state machine.
- Run the JVM/unit tests + emulator smoke (build + boot, no crash).

## Verification (review phase — Torc runs)
- Compile (`testDebugUnitTest` + `assembleDebug`) green.
- Emulator smoke: install + boot no crash; drive a few realtime turns via adb.
- JVM tests: `VoiceLoopStateTest` (re-arm state machine + rejection path) + a session-reset test.
- **The off-topic check:** on-device, start a fresh voice session and confirm the agent answers the current turn (a greeting → a greeting), not a prior/other conversation.
