# Hermes Vox — Logging Blind-Spot Audit & Patch Spec

**Scope:** `chezgoulet/hermes-vox` @ main. Kotlin app under `android/app/src/main/java/com/hermesvox/` (22 files) + Go `voice/`, `mobile/`, `cmd/` packages. Read-only audit; no files modified.

**Logging infra today** (verified): `VoxLog` (logcat tag `HermesVox` + `filesDir/logs/hermes-vox.log`, D/W/E all appended to file, crash handler kills process) · `CrashLog` (`vox_crash.log`, 9 KB cap, chains previous handler) · `LatencyStats` (4 ring buffers, push sites at VoiceController.kt **277** pushStt, **461** pushFirstByte, **479** pushFullReply, **530** pushFirstAudio; single emit gate `log("turn")` at **813**, `turns % 8` → P50/P95 lines only every 8th turn; **`reset()` has zero callers** → rings are lifetime aggregates).

**Kotlin log-site census (VoxLog/LatencyStats/Log):** VoiceController 19 · SherpaTts 12 · OfflineStt 6 · MainActivity 5 · LatencyStats 4 · RealtimeActivity 3 · ModelDownloader 3 · GemmaExpress 3 · WarmTts 2. **Zero call sites:** AvatarView, CrawlView, ModelsActivity, ModelCatalog, OnboardingActivity, SecureStore, SettingsActivity, VoiceLoopState, VoiceService, VoxExpress, VoxLog(infra). **Go side: zero logging anywhere** (pure error returns; all errors cross gomobile and are swallowed in Kotlin).

**Audit-wide headline:** the file log is ~85% *warm-up + per-engine success* lines. The *diagnostic* events that past bugs (#60 gate double-release, self-trigger, stuck `sRunning`, stream worker deadlocks, turn-gate hangs) live in are **not logged**: barge-in fires, gate release attempts, interrupt reasons, stream error text, poll-level failures, and 26 silent `catch (_: …) {}` sites in VoiceController alone swallow the pipeline's failure voice. UI-only signaling (`listener.onStatus/onError/onLog`) never reaches the file in the MainActivity flow (dev console is off by default), so a shared debug log cannot reconstruct a single bad turn.

---

## 1. Coverage matrix (functional stage → status)

| # | Stage | Status | Evidence / notes |
|---|-------|--------|------------------|
| S1 | Audio capture lifecycle (init, startRecording, loop exit reasons, release) | ⚠ PARTIAL | Config logged once (VC:192), NS attach (183), offline-listen fatal (308). **Silent:** `AudioRecord` not-initialized → fallback (174), `minBuf<=0` (167), `r.startRecording()` throw → `break` (201), loop-exit reason (silence/maxMs/noise-reject/PTT-commit), record stop/release errors (273/291/302). |
| S2 | VAD/Silero decisions | ⚠ PARTIAL | Loaded/failed logged (OfflineStt 144/146). **Silent:** per-utterance speech-start (VAD fire → `inSpeech` flip), the **VAD-absent deaf loop** (VC:223 `spoke = (vad?.isAvailable==true) && feed(...)` → without silero, capture loop can never open a segment; logged nowhere at loop start), VAD threshold never firing vs. mic dead. |
| S3 | Barge-in triggers & suppressions | 🔴 BLIND | `startBargeInWatch` (725) arm failures silent (`minBuf<=0` return; `r.state!=INITIALIZED` return; catch at 749). RMS trip → `bargeIn()` (746) → **no VoxLog**; only UI `onLog("// (interrupted)")` (762), which MainActivity does not forward to the file. Grace-window suppression (700 ms arm delay, `if (speaking)` guard) never logged. AEC attach (733) silent. Self-trigger / early-release regressions (the #60 class) are undebuggable from the log. |
| S4 | Generation watch (D2) | 🔴 BLIND | Arm (774) / arm-failure (785/800) / fire / stop all silent. `genCancelled` outcome logged only indirectly at VC:490 (`turn interrupted during generation`) *after* the loop breaks. |
| S5 | Turn state machine (VoiceLoopState) | ⚠ PARTIAL | Class pure/no logs (correct). Controller: `arm()` (427) silent, epoch/gen transitions silent, UI `onState` sequence (idle/listening/thinking/speaking) never written to file. `releaseTurnGate` (809): stale-`gen` early-return (810) and `voiceState.release()==false` (811) both **silent** — a double-release race produces zero evidence. |
| S6 | Gate acquire / release / timeout | 🔴 BLIND (timeout) | `latch.await(TURN_GATE_TIMEOUT_MS=60s)` (289) swallows everything; a **gate that never releases logs nothing** — the stuck-loop "one-turn-then-stops" symptom is invisible. `sDone.await(120s)` (550) same. The canonical per-turn release is not logged (LatencyStats line only every 8th turn). |
| S7 | Streaming STT partials / early-start | ⚠ PARTIAL | Partial transcribed then `mayStart()` **denied** (hypothesis unstable / not silent) → silent (only UI `// (partial)` when accepted, VC:262). Transcribe failure in partial worker → silent `return@partial` (258). Per-loop partial cadence/backlog (900 ms, `partialRunning` skip) silent. Early-start decision content (`early=true`) logged at 279 (text gated on `logTranscripts`, correct). |
| S8 | Network connect / reconnect / auth | ⚠ PARTIAL | `conn-test` (876/902) only on manual test button. **Silent:** `startStream` failure path (VC catch 492–498: `onError` → UI only, **no VoxLog.e**), `reconnect()` result (MainActivity 620–633 UI only), `connectFromPrefs` blank-url early return (351), model-catalog/health fetch failures (MainActivity UI toasts only). Auth 401/403 text reaches UI but not file. |
| S9 | SSE stream events & stalls | ⚠ PARTIAL | Batch log at VC:464 (`poll ev=… done=… err=… textLen=…`) + turn-done at 470. **Problems:** (a) `PollStreamJSON` always returns a snapshot → 464 fires **every ~100 ms idle poll** (up to ~600 lines/turn of `ev=0` during agent "thinking") — file spam; (b) no stall detection (5 s/15 s/30 s no-event markers); (c) event-type timeline (response.created / tool calls) forwarded only to UI `onLog`, not file; (d) `tries<600` (447) timeout → generic `Exception("timeout")` → UI text only. |
| S10 | Cancellation / interrupt paths | 🔴 BLIND | `bargeIn()` (752), `hush()` (833), `stopStreaming()` (653), `stopTts()`, stream-worker kill, re-arm of a stuck worker (`if (sRunning && sClosed) sRunning=false`, 562) — none logged. Cancel cause/release-reason never recorded. |
| S11 | TTS lifecycle (load/warm/synth/playback/flush) | ⚠ PARTIAL | SherpaTts well covered: init (61/64), generate (77/126), played (113), chunk (182), errors (81/115/129/143/159/185). **Silent:** **SystemTts has zero logs** (init-result code, speak, error callback code at WarmTts.kt:44–52) — silent-system-TTS is undiagnosable; `streamChunk` returns false on null samples (SherpaTts 165–169) with no log — worker skips chunk silently (dropped sentence); `finishStreaming` head-vs-written stall loop (189–199) silent for up to 120 s; queue backlog (unbounded `sQueue`) never measured; `shutdown()`/`stop()` silent (fine). |
| S12 | AudioTrack underruns / errors | ⚠ PARTIAL | Build/play/write errors logged (play 115, buildStreamTrack 159). **Silent:** playback-head stall in `play()` (SherpaTts 108–111) and `finishStreaming` (193–196) — an underrun/blocked track spins to 120 s with no W line; `getUnderrunCount()` never polled; no write-vs-head progress log. |
| S13 | Model download states | ✅ COVERED (minor gaps) | Start/result/throw logged (ModelDownloader 41/43/48 incl. cancel, HTTP code, sha mismatch). **Minor:** no elapsed/bytes line on success; progress deliberately UI-only (fine). ModelsActivity completion (`finishInstall`) not logged (covered by downloader result line). |
| S14 | Settings persistence & mode switches | 🔴 BLIND | **SettingsActivity has zero VoxLog call sites.** Every pipeline-affecting pref (duplex/barge-in, voice mode, STT backend/model, tts engine, voice register, speak_responses, log_transcripts, model_source, entity url/model) writes silently. Mode toggles in MainActivity (`toggleRealtimeMode` 651, `applyVoiceMode` 674, `setVoiceChannelOpen`) silent. "Barge-in stopped working because duplex got flipped" — impossible to see. |
| S15 | Battery / foreground-service lifecycle | 🔴 BLIND | VoiceService has zero logs (onCreate/StartCommand/Destroy). FGS `start()` in try/catch (MainActivity 198) — a `ForegroundServiceStartNotAllowedException` (Android 12+) or mic-type FGS denial is **swallowed → app believes call is live, process dies in background**. WakeLock acquire/release (294–301/285–288) silent; `endCall`/`onStop` (210/764) silent (controller-stop reason not recorded). |
| S16 | Permission grants/denials | 🔴 BLIND | `onRequestPermissionsResult` (MainActivity 222–240) → status text only, no file log. RECORD_AUDIO/POST_NOTIFICATIONS denial chain (talk→startCall fallthrough, rationale paths) invisible in log. |
| S17 | Crash paths beyond existing handler | ⚠ PARTIAL | Uncaught-Java handled (VoxLog + CrashLog both registered in MainActivity.onCreate 70–71; CrashLog chains → VoxLog kills). **Gaps:** native sherpa-onnx SIGSEGV (they've shipped around one, #7) leaves **no app-side trace** and no last-known-good boundary; no per-risky-call heartbeat; ANRs unhandled; 26 silent-catch sites mean many "degraded but alive" paths leave no breadcrumb. |
| S18 | Express/glue (Gemma presence) | ⚠ PARTIAL | GemmaExpress load/gen errors logged (46/49/66). **Silent:** `speakGlue` guards (speaking/shouldSpeak/stopTts precedence, VoxExpress/VoiceController 683–694), fallback-to-RoutedExpress decisions, orchestrator owner switches. |
| S19 | Go voice/ + mobile/ | 🔴 BLIND | **Zero logging in all of `voice/*.go`, `mobile/*.go`, `cmd/`.** All failures return errors that Kotlin swallows (S9/S10). `streamInto` (stream.go:98–233) — the SSE consumer — has no visibility into connect, HTTP status, event counts, scanner errors, or cancel path from the app side except what Kotlin re-derives. (In-process Go logging would be gomobile-invisible; fix at the Kotlin call boundary + error echo.) |

**Score:** 1 fully covered (S13) · 0 fully blind infra beyond handlers (S17 partial) · **9 partial** · **9 fully blind** (S3, S4, S6-timeout, S10, S14, S15, S16, S19 + gate-race in S5).

---

## 2. Prioritized logging-patch findings

Each finding: silent event → why it matters → where → suggested line → priority. All lines metadata-only (privacy-safe per §4). Line refs = current file lines.

### P0 — must-have for the next bug hunt

**F1. Per-turn outcome line at gate release (with reason).**
- (a) Every turn's terminal event (completed/barge-in/hush/error/timeout/double-release/stale-gen) is silent except a UI status and an every-8th-turn latency line.
- (b) The #60 bug class (early/duplicate gate release) and the stuck-loop class both leave zero file evidence; you cannot tell *how* a turn ended from the log.
- (c) `VoiceController.releaseTurnGate(gen)` — add a `reason: String` param; log on **every** path: stale-gen early return (line 810), `!voiceState.release()` (811, W — duplicate release!), and the countDown (813).
- (d) `VoxLog.d("gate release gen=$gen reason=$reason epoch=${voiceState.epoch}")`; duplicate → `VoxLog.w("gate DUPLICATE release gen=$gen (ignored)")`; stale → `VoxLog.w("gate stale release gen=$gen current=$turnGen (ignored)")`. Callers: settleReply (stream-done/speak-done/text-only), bargeIn→`"barge-in"`, hush→`"hush"`, runStreamedTurn error→`"stream-error"`, timeout→`"turn-timeout"`, empty reply→`"empty-reply"`, suppress→`"suppressed-inflight"`.
- (e) **P0.**

**F2. Turn-gate timeout and 120 s `sDone` await must log.**
- (a) A 60 s gate hang or 120 s stream-worker hang is silent.
- (b) These are the literal deadlock symptoms; a W at expiry pinpoints which await wedged.
- (c) VC 289 (latch.await) and VC 550 (`sDone.await(120,…)`).
- (d) `VoxLog.w("gate TIMEOUT gen=$myGen after ${TURN_GATE_TIMEOUT_MS}ms still locked (speaking=$speaking streamed=$streamed sRunning=$sRunning bargeInArmed=$bargeInArmed)")` when `await` returns false, and mirror for `sDone`.
- (e) **P0.**

**F3. Stream/SSE errors must reach the file log — stop swallowing poll/start failures.**
- (a) `startStream` exceptions (VC catch 492–498), `waitStream` errors (457), `pollStreamJSON` errors (459) are all caught with empty bodies; a mid-turn connection reset surfaces only as a generic UI "timeout" after 600 tries.
- (b) Network/auth/SSE failures are the #1 support question; the Go error strings are descriptive but never recorded.
- (c) VC `runStreamedTurn` — log `startStream` errors in the catch; log poll errors when `!genCancelled && !done` (the retired-stream noise case is the post-cancel path, which is distinguishable).
- (d) `VoxLog.w("stream poll error gen=$gen: ${e.message} (cancelled=$genCancelled done=$done tries=$tries)")`; in the outer catch replace the UI-only path with `VoxLog.e("stream turn failed gen=$gen: ${e.message}")` *before* `listener?.onError`.
- (e) **P0.**

**F4. Barge-in / interrupt events logged with cause.**
- (a) RMS trip in the playback watch (746) and gen watch (796) → `bargeIn()` → no VoxLog; suppressions (700 ms arm delay, AEC session, `!speaking`) silent; arm failures (record init, catch 749/800) silent.
- (b) Barge-in misfires/self-trigger are the flagship field bugs; need trigger source + timing to diagnose.
- (c) `VoiceController.bargeIn()` (752) and both watcher loops (746/796) + `startBargeInWatch` failure returns (728/735/749) + `armGenerationWatch` (785/800).
- (d) Watcher fire: `VoxLog.d("barge-in TRIGGER source=${if (speaking) "playback-rms" else "genwatch-rms"} rms=${"%.3f".format(r)} speaking=$speaking gen=$turnGen")`; arm: `VoxLog.d("barge-in armed (playback session=$ecSession)")`; arm failure: `VoxLog.w("barge-in arm FAILED: $reason")`. Log one RMS value per trigger only — never a stream.
- (e) **P0.**

**F5. FGS/WakeLock lifecycle + start-failure logging.**
- (a) `VoiceService.start()` wrapped in empty catch (MainActivity 198); FGS denial on Android 12+/14+ (mic-type checks) means "call live" UI with no keepalive — process death in background is silent; `onStop` controller-stop reason silent.
- (b) Foreground-service failure is invisible and kills the core promise (persistent call).
- (c) `VoiceService.onCreate/onStartCommand/onDestroy`; MainActivity `startCall` (198), `endCall` (210), `onStop` (768–774), `acquireVoiceWake`/`stopVoiceWake`.
- (d) Service: `VoxLog.d("fg-service start id=$startId sticky")` / `VoxLog.d("fg-service destroy")`; caller: `VoxLog.d("fg-service start attempted (callLive=$callLive)")` with the catch → `VoxLog.e("fg-service start FAILED: ${e.message}")`; `VoxLog.d("wake acquired/released")`; onStop: `VoxLog.d("activity stop: controller stopped (no live call)")`.
- (e) **P0.**

**F6. Poll-spam fix + SSE stall markers (volume + visibility).**
- (a) VC:464 logs every poll; `PollStreamJSON` always returns a snapshot, so idle polls log ~10×/s during agent thinking (≤600 lines/turn in the exported file).
- (b) File grows unbounded per session and drowns the events that matter; conversely no marker when the stream is genuinely stalled.
- (c) VC runStreamedTurn loop: emit 464 only when `evts.length>0 || done || err!=""`; track `lastEventAt`; add stall W at 5 s and 15 s of zero events (`stream STALL ${ms}ms no events gen=$gen`).
- (d) `VoxLog.d("poll gen=$gen ev=${evts?.length() ?: 0} done=$done textLen=${…} err=${…}")` (keep) — but only on a *non-empty* batch; plus stall line above.
- (e) **P0** (volume is itself a debugging blocker; stalls are the blind spot).

### P1 — high-value, should land with the P0 set

**F7. VAD/speech-transition + VAD-absent-deaf-loop logging.**
- (a) No log when the VAD opens a segment (speech-start), closes on silence, or when **VAD is absent** at loop start (VC:223 means no silero ⇒ capture loop never detects speech ⇒ 15 s silent loops forever — a real trap, currently visible only via init line 109's `vad=null`).
- (c) VC `listenOffline` at loop start (add `vad=` to the 192 line or a sibling) and at the `inSpeech` flip (line 236 area).
- (d) Start: `VoxLog.d("mic: vad=${if (vad?.isAvailable==true) "silero" else "NONE"}" + " bargeAec=$bargeAec")` — **W level when VAD absent** (capture will be deaf); transition: `VoxLog.d("vad: speech-start @${segMs}ms")` once per segment (rate is 1/utterance — safe).
- (e) **P1.**

**F8. Gate/warm/state one-liners at controller transitions.**
- (a) `onState` sequence never hits the file; `arm()` silent; `ensureWarm` retries silent (only MainActivity logs every 10th retry, 179).
- (c) VC dispatch points (`main.post { listener?.onState(...) }` inside listenOffline/runStreamedTurn/settleReply/speak), `runStreamedTurn` arm site (427), `ensureWarm` (354–362).
- (d) Central: `VoxLog.d("state $s gen=$turnGen")` at one funnel, or per site: `VoxLog.d("turn arm gen=$gen epoch=$epoch")`, `VoxLog.d("warm: ttsReady=$ttsReady sttReady=$sttReady vad=${…}")`.
- (e) **P1.**

**F9. SystemTts gets a voice.**
- (a) Zero logs anywhere in SystemTts; silent engine (no TTS engine on device, wrong init code) with system fallback = silent replies and no evidence; `scheduleDone` heuristic can strand the gate (60 s silent hang → F2 catches the hang but not the cause).
- (c) `WarmTts.kt` SystemTts: init callback (44–52), `speak` (54–59), `scheduleDone` (60–63), `stop`.
- (d) `VoxLog.d("systemTts init code=$code")` / `VoxLog.w("systemTts init FAILED code=$code")`; `VoxLog.d("systemTts speak chars=${text.length}")`; when `tts?.isSpeaking == true` at the done-check → `VoxLog.w("systemTts done-check still speaking (chars=$chars) — gate deferred")`.
- (e) **P1.**

**F10. SherpaTts silent-failure and stall surfaces.**
- (a) `streamChunk` returns false silently on null samples (165–169 — dropped sentence in the streaming worker); `finishStreaming` (189–199) and `play` head-wait (108–111) can spin 120 s silently on a stalled/underrunning track; `startStreaming` worker re-arm of stuck `sRunning` (VC 562) silent.
- (c) SherpaTts.kt streamChunk/finishStreaming/play + VC 562.
- (d) `VoxLog.w("piper streamChunk: no samples (${text.length} ch dropped)")`; stall: `VoxLog.w("piper track stall: head=${t.getPlaybackHeadPosition()} written=$streamWritten waited=${waited}ms — releasing")` when head doesn't advance for >2 s; `VoxLog.d("piper stream re-arm: recycled stuck worker")`.
- (e) **P1.**

**F11. Silent-catch triage on the four diagnostic-worthy sites.**
- (a) 26 empty catches in VC; most are legit teardown races, but four hide primary failures: record start (201), record init state (174), partial-worker transcribe skip (258), barge-watch record init (735).
- (c) As cited.
- (d) Convert to one-line W on first occurrence per session (or always — these are rare): `VoxLog.w("capture start FAILED: ${e.message}")`, `VoxLog.w("AudioRecord uninitialized — falling back")`, `VoxLog.w("partial STT skipped: transcribe returned null/blank")`, `VoxLog.w("barge record uninitialized — barge-in unavailable this turn")`.
- (e) **P1.**

**F12. Permission results + onboarding/reconnect results logged.**
- (a) Denial chains (talk → startCall fallthrough, rationale vs hard-deny) are UI-only; `/reconnect`, model catalog fetch failures, onboarding verify failures UI-only.
- (c) MainActivity `onRequestPermissionsResult` (222–240), `reconnect` (620–633), `showModelChooser` fetch (521–525), OnboardingActivity `connectAndVerify` (49–63).
- (d) `VoxLog.d("perm result req=$requestCode ${permissions.zip(grantResults).joinToString { "${it.first}=${if (it.second==0) "granted" else "denied"}" }}")`; `VoxLog.d("reconnect: ok=$ok")`; `VoxLog.w("model catalog fetch failed: ${e.message}")`; `VoxLog.d("onboarding verify ok=$ok")`.
- (e) **P1.**

### P2 — hardening / polish

**F13. Settings & mode-switch audit trail (single debounced line).**
- SettingsActivity has **zero** VoxLog sites; every pipeline pref writes silently. Add one funnel: `pick()` (391–402), toggle listeners (165–176), `bindMicToggle`, sliders on `onStopTrackingTouch` only (never per-tick), `restoreDefaults` (417), entity save (111–116), plus MainActivity `toggleRealtimeMode`/`applyVoiceMode`.
- Line: `VoxLog.d("setting $key=$value")` (never log the API key value; log `key=set` length only), mode: `VoxLog.d("voice-mode ${old}->$next")`.
- **P2.**

**F14. LatencyStats: emit every N with a rolling window + per-turn one-liner.**
- Rings accumulate for the process lifetime (`reset()` uncalled) yet `log()` claims P50/P95 — they're lifetime aggregates skewed by config changes; emit only on `turns % 8`. Fix: after each emit, clear the four lists (8-turn rolling window), log every 8th as today but windowed; OR add `LatencyStats.turnSummary(gen, outcome, t0)` emitting one D line per turn: `turn gen=$gen outcome=$reason ttfFirstByte=… firstAudio=… fullReply=… stt=…` — privacy-safe, greppable, reconstructs the timeline that F1/F6 already hint at. Keep P50/P95 every 8th turn (rolling) for regression tests.
- **P2.**

**F15. Event-type timeline for tool turns.**
- `emitEvents` (508–538) forwards created/added/completed to UI only. Add one D per non-delta event type: `VoxLog.d("sse ${e.optString("type")} …")` (omit `arguments`/`output` bodies; length only: `argsLen=…`).
- **P2.**

**F16. VoxLog hygiene: rotation + level discipline at the file write.**
- File is append-only for process lifetime (no rotation/truncation); D lines (incl. poll spam until F6) bloat exported logs; `VoxLog.e(tag,msg)` overload writes the *tag* into the level column. Add a size cap (~1 MB → rotate to `.1`), and write D to file only when a `debug_log` pref is on OR keep D in logcat-only (logcat is already the debug channel; file becomes the support-export channel: D=state transitions + W/E). Timestamp has no date — add `yyyy-MM-dd`.
- **P2.**

**F17. Native-crash breadcrumbs around sherpa-onnx call boundaries.**
- A JNI SIGSEGV (they've engineered around one, #7) produces no app-side trace; the last line before the crash is the only clue. Add ack logs after the risky sync calls: `piper chunk … written` (after `t.write` returns, SherpaTts 183), `piper played …` already exists post-play (113); add post-generate ack where missing and a `whisper decode ok` after `r.getResult` (OfflineStt 78) — then a native crash always lands between two file lines.
- **P2.**

**F18. Model download success telemetry.**
- Add elapsed/bytes to the result line: `VoxLog.d("model ${spec.id}: OK ${bytes} bytes in ${ms}ms sha256 ok")`.
- **P2.**

---

## 3. Recommended logging conventions (patch-ready)

1. **One log-call site per event, in the layer that owns the event.** VoiceController is the funnel for turn lifecycle; engines log engine-local facts; Activities log UI/lifecycle facts. Do not log the same event in both caller and callee.
2. **Structured `key=value` format, greppable by `event=` tag.** Every new line starts `VoxLog.d("eventName key=val key=val")` — e.g. `event=barge-in source=playback-rms rms=0.21 gen=7`. Existing `mic:`, `pipeline:`, `piper`, `realtime:` prefixes are prose-ish; keep prefixes as the event token: `event=barge-in`, `event=gate-release`, `event=stream-poll`, `event=vad-speech`, `event=perm-result`. Logcat `grep "event=gate-release"` then works.
3. **Level discipline:** D = per-turn/state metadata & engine success; W = suppression/guard trips that *could* indicate bugs (duplicate release, arm failure, stall, VAD-absent, dropped chunk); E = genuine failures (init, stream turn failed, FGS failure). Never log per-frame/per-100 ms at any level (see F6).
4. **LatencyStats:** (a) rolling window per emit (clear after the every-8th-turn P50/P95 emit) so stats describe the *current* regime; (b) add the per-turn one-line summary (F14) — the single most useful line for debugging a voice loop; (c) wire `LatencyStats.reset()` to session/new-conversation so a benchmark run starts clean.
5. **Privacy invariant (unchanged, must hold):** never log transcript text, tool arguments, tool outputs, or API keys — metadata and lengths only. Existing `logTranscripts()` gate (VC:911) stays the *only* channel for content; new lines must not require gating because they never contain content (`textLen=`, `argsLen=`, `<hidden>` pattern already used at 279). The event timeline (F15) logs types + lengths, never bodies.
6. **Rationale for Go-side silence:** in-process `log`/`slog` in `voice/*.go` would not reliably reach logcat under gomobile; fix the boundary in Kotlin (F3) and consider returning a `lastErr`/event counters through `PollStreamJSON` if deeper SSE visibility is ever needed.

## 4. Debug-mode vs production volume split (public app)

- **Production default (file log = support-export artifact):** D = lifecycle + state transitions only (F1, F2, F8, F13-condensed); W/E always (F3, F4, F5, F7-warn, F9–F12). No per-poll, no per-chunk (keep piper chunk at logcat-only D or gate), no content. Estimated steady-state < 30 lines/turn.
- **`dev_console` (existing pref) or new `debug_log` pref = debug mode:** adds per-event SSE detail (F15), per-chunk TTS lines, poll batches (F6 condensed), partial-STT decisions (S7), RMS-sample summaries, and enables D→file. Debug mode may also opt into `log_transcripts` content gating — unchanged from today.
- **Recommended implementation:** add a `level` parameter or a `VoxLog.fileLevel` (default W→file, D→logcat only; `debug_log` flips fileLevel=D). Logcat always carries everything for adb debugging; the exported file stays clean for support. This gives three tiers: logcat full · file W+E production · file D+W+E debug.
- **Volume guards:** file rotation ~1 MB (F16); poll-spam removal (F6) is mandatory before D→file is safe even in debug.

---

## Appendix — file/function map for the patch

| Finding | File | Function(s) |
|---|---|---|
| F1 | VoiceController.kt | `releaseTurnGate` (809) + all 9 call sites |
| F2 | VoiceController.kt | capture loop `latch.await` (289); `settleReply` `sDone.await` (550) |
| F3 | VoiceController.kt | `runStreamedTurn` outer catch (492), poll catches (457/459) |
| F4 | VoiceController.kt | `startBargeInWatch` (725), barge watch loop (737–748), `bargeIn` (752), `armGenerationWatch` (774–801) |
| F5 | MainActivity.kt + VoiceService.kt | `startCall` (198), `endCall` (210), `onStop` (764), wake fns (285/294); service lifecycle |
| F6 | VoiceController.kt | `runStreamedTurn` poll loop (447–489) |
| F7 | VoiceController.kt | `listenOffline` start (192) + `inSpeech` flip (236) |
| F8 | VoiceController.kt | `arm` (427), state dispatch points, `ensureWarm` (354) |
| F9 | WarmTts.kt (SystemTts) | `init` (44–52), `speak` (54–59), `scheduleDone` (60–63) |
| F10 | SherpaTts.kt + VoiceController.kt | `streamChunk` (164–186), `finishStreaming` (189–199), `play` (108–111); VC 562 |
| F11 | VoiceController.kt | 201/174/258/735 |
| F12 | MainActivity.kt, OnboardingActivity.kt | `onRequestPermissionsResult` (222), `reconnect` (620), `showModelChooser` (518); onboarding (49) |
| F13 | SettingsActivity.kt + MainActivity.kt | `pick` (391), toggles (165), sliders (317–356), `restoreDefaults` (417), entity save (111); `toggleRealtimeMode` (651) |
| F14 | LatencyStats.kt + VoiceController.kt | `log` (21), `reset` callers; new `turnSummary` |
| F15 | VoiceController.kt | `emitEvents` (508–538) |
| F16 | VoxLog.kt | `append`/`init` |
| F17 | SherpaTts.kt, OfflineStt.kt | post-write ack (183), post-decode ack (78) |
| F18 | ModelDownloader.kt | `download` (41–49) |
