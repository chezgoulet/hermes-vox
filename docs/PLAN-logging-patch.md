# PLAN — Hermes Vox logging patch (P0 + latency/poll infra)

Input: `docs/LOGGING-BLIND-SPOTS-AUDIT-2026-09-05.md` (18 findings). **Scope = F1–F6 (all P0) + two infra fixes** (LatencyStats per-turn summary / rolling window; VC:464 poll-spam gate). P1/P2 findings (F7–F18) are tracked in a backlog note, not implemented here. No Go changes (S19 boundary fix is the Kotlin error-echo in F3, which IS in scope).

## Verified reference table (audit line → real line, P0-relevant)

| Audit | Real file:line | Delta |
|---|---|---|
| VC `releaseTurnGate` 809; latch/log 813 | VoiceController.kt:809–814 | ✅ match |
| VC poll log 464 | VoiceController.kt:464 | ✅ match |
| VC `startBargeInWatch` 725–750 | VoiceController.kt:725–750 | ✅ |
| VC `bargeIn` 752 / watch loop 737–748 / fire 746 | :752 / :738–748 / :746 | ✅ |
| VC `armGenerationWatch` 774–801 / 785 / 800 | :774–801 / :785 / :800 | ✅ |
| VC `latch.await` 289 / `sDone.await` 550 | :289 / :550 | ✅ |
| VC outer catch 492–498, wait catch 457, poll catch 459 | ✅ | |
| VC `runStreamedTurn` 424, arm 427, throws timeout 491 | ✅ | |
| MA `startCall` 198, `endCall` 210, wake 285–301, `onStop` 764–775 | ✅ | |
| VoiceService `onCreate/onStartCommand/onDestroy` 27/32/37 | ✅ | |
| LatencyStats `log` 21, `reset` 31 zero callers | ✅ (confirmed no callers) | |
| **F1 audit line uses `epoch=${voiceState.epoch}`** | `epoch` is `@Volatile private` in VoiceLoopState.kt:18 — **not readable from VC** | ⚠ **drop `epoch`** from the release line |
| F4 fire rms value | watch computes `spoke` boolean only (:745, :795); RMS double is local | ⚠ capture `level` value at trigger for the log |

## Conventions applied (per audit §3 + AGENTS.md)

- Structured `event=` token first, then `key=value`. New events: `event=gate-release`, `event=turn`, `event=gate-timeout`, `event=stream-poll`, `event=stream-stall`, `event=stream-wait`, `event=stream-poll-error`, `event=stream-turn-failed`, `event=barge-in`, `event=barge-in-armed`, `event=barge-in-arm-failed`, `event=fg-service*`, `event=wake*`, `event=call-*`, `event=activity-stop`.
- **Privacy invariant hard-held:** metadata + lengths only; never transcript text, tool args/outputs, keys. `logTranscripts()` gate (VC:911) untouched and stays the only content channel. All lengths via `textLen=`/`err=…take(70)`.
- **Level discipline:** `W`/`E` → file always. `D` (lifecycle/state/turn metadata) → file always. New **`VoxLog.dd()`** (debug-detail) → logcat always, file only when debug mode on (C1). The F6 poll line is the only `dd` in scope.
- One call-site per event class; engines own engine facts, VC owns turn lifecycle, Activity/Service own UI/service facts.

## Commit plan (each compiles; app shippable after each)

### C1 — VoxLog debug/production file split (infra, req #4)

**Files:** `VoxLog.kt`, `SettingsActivity.kt`, `res/layout/activity_settings.xml`

`VoxLog.kt`:
- Add `@Volatile private var debugFile = false`.
- Add debug-detail channel: `fun dd(msg: String) { Log.d(TAG, msg); if (debugFile) append("D", msg) }`.
- `init(context)` reads the gate: `debugFile = context.getSharedPreferences("hv", Context.MODE_PRIVATE).getBoolean("debug_log", false)`.
- Add `fun setDebugFile(on: Boolean) { debugFile = on }`.

`activity_settings.xml` (About & Diagnostics section, mirroring `row_logtranscripts` at :905–921): new row `row_debuglog` + `SwitchCompat` id `set_debuglog`, label "Verbose file log (debug)".

`SettingsActivity.kt` `bindFlows()` (next to `devc`/`ltr`, :171–176):
```
val dbl = findViewById<SwitchCompat>(R.id.set_debuglog)
dbl.isChecked = prefs.getBoolean("debug_log", false)
dbl.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("debug_log", on).apply(); VoxLog.setDebugFile(on) }
```
`restoreDefaults` GROUP_ABOUT (:446–448): add `.putBoolean("debug_log", false)` and after `e.apply()` call `VoxLog.setDebugFile(false)` in the GROUP_ABOUT rebind branch.

Production default unchanged (pref default false). **Verify:** `cd android && ./gradlew :app:testDebugUnitTest` (compiles + runs the 5 existing pure-JVM tests); manual: toggle row, confirm a `dd` line appears in `filesDir/logs/hermes-vox.log` only when on.

### C2 — F1 + F14 infra: per-turn outcome at gate release; LatencyStats summary every turn + rolling window

**Files:** `LatencyStats.kt`, `VoiceController.kt`, new `src/test/java/com/hermesvox/LatencyStatsTest.kt`

`LatencyStats.kt` rewrite of `log` internals:
- Keep 4 rings + `MAX` trim. Add per-turn last values defaulting to `-1L`, set by each `push*` (e.g. `pushStt(ms) { …; stt.add(ms); trim(stt); turnStt = ms }`).
- Replace `fun log(label)` with:
```
fun log(label: String, outcome: String, gen: Long) {
    summaryLines(label, outcome, gen).forEach { VoxLog.d(it) }
}
internal fun summaryLines(label: String, outcome: String, gen: Long): List<String> = synchronized(lock) {
    turns++
    val out = ArrayList<String>()
    out += "event=turn label=$label gen=$gen outcome=$outcome stt=${fmt(turnStt)} " +
           "firstByte=${fmt(turnFirstByte)} firstAudio=${fmt(turnFirstAudio)} fullReply=${fmt(turnFullReply)}"
    turnStt = -1L; turnFirstByte = -1L; turnFirstAudio = -1L; turnFullReply = -1L
    if (turns % 8L == 0L) {
        out += "event=lat label=$label metric=first-byte p50=${pct(firstByte,0.50)} p95=${pct(firstByte,0.95)} n=${firstByte.size}"
        out += "event=lat label=$label metric=first-audio p50=${pct(firstAudio,0.50)} p95=${pct(firstAudio,0.95)} n=${firstAudio.size}"
        out += "event=lat label=$label metric=full-reply p50=${pct(fullReply,0.50)} p95=${pct(fullReply,0.95)} n=${fullReply.size}"
        out += "event=lat label=$label metric=stt p50=${pct(stt,0.50)} p95=${pct(stt,0.95)} n=${stt.size}"
        firstByte.clear(); firstAudio.clear(); fullReply.clear(); stt.clear()   // rolling window
    }
    out
}
private fun fmt(v: Long) = if (v < 0L) "-" else v.toString()
internal fun windowCounts(): IntArray = synchronized(lock) { intArrayOf(firstByte.size, firstAudio.size, fullReply.size, stt.size) }
```
Per-turn values clear after every emit; P50/P95 only every 8th release and now describe a true 8-turn window (values only meaningful for the release that consumed the last push of a turn — guaranteed by the half-duplex gate, VC:289). `reset()` unchanged.

`VoiceController.kt` — `releaseTurnGate` (:809) becomes `private fun releaseTurnGate(gen: Long, reason: String)`:
```
if (gen != turnGen) { VoxLog.w("event=gate-release result=stale gen=$gen current=$turnGen reason=$reason"); return }
if (!voiceState.release()) { VoxLog.w("event=gate-release result=duplicate gen=$gen reason=$reason"); return }
try { turnDone.countDown() } catch (_: Throwable) {}
VoxLog.d("event=gate-release gen=$gen reason=$reason")          // (audit's epoch= dropped — private)
LatencyStats.log("turn", reason, gen)
```
10 call sites (corrected reasons): :425 `"suppressed-inflight"`, :476 `"reply-error"`, :483 `"empty-reply"`, :497 `"stream-error"` (refined in C4), :550 `"stream-done"`, :554 `"text-only"`, :701 `"no-engine"`, :716 `"speak-done"`, :764 `"barge-in"`, :842 `"hush"`. All use `gen`/`turnGen` as today.

New `LatencyStatsTest.kt` (pure JVM, mirrors `TurnGateReleaseTest` style — calls `summaryLines`/`windowCounts`, never touches `VoxLog`):
- per_turn_summary_every_call: seed `pushStt(40)`+others → 1st `summaryLines("turn","stream-done",1L)` returns exactly 1 line containing `event=turn outcome=stream-done stt=40`; 2nd call shows `stt=-` (cleared).
- rolling_p50_every_8th_then_clear: seed metrics, 7 calls → 1 line each; 8th call → 5 lines (1 turn + 4 `event=lat`); `windowCounts()` all-zero after 8th; a fresh push then grows from 0.
- reset_zeroes_window.

**Verify:** `cd android && ./gradlew :app:testDebugUnitTest`.

### C3 — F2: gate-timeout & 120 s sDone-await W lines

**Files:** `VoiceController.kt`

:289 (capture loop):
```
val gateReleased = try { latch.await(TURN_GATE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Throwable) { false }
if (!gateReleased) VoxLog.w("event=gate-timeout await=turn-gate gen=$myGen after=${TURN_GATE_TIMEOUT_MS}ms still-locked speaking=$speaking streamed=$streamed sRunning=$sRunning bargeInArmed=$bargeInArmed turnInFlight=$turnInFlight")
```
:550 (settleReply):
```
exec.execute {
    val doneOk = try { sDone.await(120, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Throwable) { false }
    if (!doneOk) VoxLog.w("event=gate-timeout await=stream-worker gen=$gen after=120s sRunning=$sRunning streamed=$streamed")
    releaseTurnGate(gen, if (doneOk) "stream-done" else "stream-done-timeout")
}
```
**Verify:** compile + `testDebugUnitTest`; manual hang scenario → exactly one W per wedged await, names the await site.

### C4 — F3 + F6: stream loop — log poll/wait/start failures; gate the :464 spam; stall markers (req #1b)

**Files:** `VoiceController.kt` `runStreamedTurn` (:438–505)

Loop-top locals before `while (!done && tries < 600)`: `val tick = android.os.SystemClock.uptimeMillis()` each iteration; `var lastEventAt = …uptimeMillis()`; `var stall5 = false; var stall15 = false`.

- :457 → `try { session.waitStream(sid, 100) } catch (e: Exception) { if (!genCancelled) VoxLog.w("event=stream-wait err=${e.message.take(120)} gen=$gen tries=$tries") }`
- :459 → `try { payload = session.pollStreamJSON(sid) } catch (e: Exception) { if (!genCancelled && !done) VoxLog.w("event=stream-poll-error err=${e.message.take(120)} gen=$gen tries=$tries") }`
- Replace :464 (spam fix + stall):
```
val evts = obj.optJSONArray("events")
val doneNow = obj.optBoolean("done")
val pollErr = obj.optString("error", "")
val textLen = obj.optString("text", "").length
if ((evts?.length() ?: 0) > 0 || doneNow || pollErr.isNotBlank()) {
    lastEventAt = tick
    stall5 = false; stall15 = false
    VoxLog.dd("event=stream-poll gen=$gen ev=${evts?.length() ?: 0} done=$doneNow textLen=$textLen err=${pollErr.take(70)}")
} else {
    val idle = tick - lastEventAt
    if (idle >= 15000 && !stall15) { stall15 = true; VoxLog.w("event=stream-stall gen=$gen idleMs=$idle no-events=15s") }
    else if (idle >= 5000 && !stall5) { stall5 = true; VoxLog.w("event=stream-stall gen=$gen idleMs=$idle no-events=5s") }
}
```
(keep `if (obj.optBoolean("done"))` logic below on the same `obj`; `textLen`/`doneNow`/`pollErr` are read once.)
- Outer catch :492–498 — add E before the UI-only path:
```
} catch (e: Throwable) {
    stopStreaming()
    VoxLog.e("event=stream-turn-failed gen=$gen err=${e.message}")
    main.post {
        listener?.onError("hermes: ${e.message}")
        listener?.onState("idle")
        releaseTurnGate(gen, if (e.message == "timeout") "turn-timeout" else "stream-error")
    }
}
```
(The generic `Exception("timeout")` from :491 is now distinguishable.)

**Verify:** compile + unit; manual: agent "thinking" gaps produce **no** `event=stream-poll` in the prod file (only logcat) and ≤ a handful per turn in debug mode; a blackholed network shows one `event=stream-stall` at 5 s and 15 s.

### C5 — F4: barge-in/interrupt events with cause

**Files:** `VoiceController.kt`

`startBargeInWatch` (:725):
- :728 `if (minBuf <= 0) { VoxLog.w("event=barge-in-arm-failed reason=min-buffer gen=$turnGen"); return }`
- :735 (uninitialized record) `if (r.state != AudioRecord.STATE_INITIALIZED) { VoxLog.w("event=barge-in-arm-failed reason=record-not-initialized gen=$turnGen"); return }`
- after :736 `r.startRecording()`: `VoxLog.d("event=barge-in-armed phase=playback gen=$turnGen ecSession=$ecSession aec=${bargeAec?.enabled ?: false}")`
- fire :745–746: `val level = Math.sqrt(rms / n) / Short.MAX_VALUE; if (level > 0.15f) { VoxLog.d("event=barge-in source=playback-rms rms=${"%.3f".format(level)} gen=$turnGen speaking=$speaking"); main.post { bargeIn() }; break }`
- :749 catch: `catch (e: Throwable) { VoxLog.w("event=barge-in-arm-failed reason=${e.message} gen=$turnGen") }`

`armGenerationWatch` (:774):
- :777 `if (minBuf <= 0) { VoxLog.w("event=barge-in-arm-failed reason=min-buffer phase=generation gen=$turnGen"); return }`
- :785 `if (r.state != …) { VoxLog.w("event=barge-in-arm-failed reason=record-not-initialized phase=generation gen=$turnGen"); genWatchArmed = false; genRecord = null; return }`
- after :786 `r.startRecording()`: `VoxLog.d("event=barge-in-armed phase=generation gen=$turnGen")`
- fire :795–796: same level-capture pattern → `VoxLog.d("event=barge-in source=genwatch-rms rms=${"%.3f".format(level)} gen=$turnGen turnInFlight=$turnInFlight")`
- :800 catch: `catch (e: Throwable) { genWatchArmed = false; VoxLog.w("event=barge-in-arm-failed reason=${e.message} phase=generation gen=$turnGen") }`

`bargeIn()` (:752) gets **no** new line (watchers own the trigger; F1 logs the `reason=barge-in` release — single call-site). One RMS value per trigger only, never a stream.

**Verify:** compile + unit; manual: talk over a reply → exactly one `event=barge-in` (playback-rms), arm + release lines present; self-trigger regressions now visible in the export.

### C6 — F5: FGS + WakeLock lifecycle logging

**Files:** `VoiceService.kt`, `MainActivity.kt`

`VoiceService.kt`:
- `onCreate`: after `createChannel()` → `VoxLog.d("event=fg-service onCreate")`
- `onStartCommand`:
```
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    VoxLog.d("event=fg-service start id=$startId")
    return try { startForeground(1, buildNotification()); START_STICKY }
    catch (e: Exception) { VoxLog.e("event=fg-service startForeground-failed err=${e.message}"); START_NOT_STICKY }
}
```
- `onDestroy`: `VoxLog.d("event=fg-service destroy")`
- companion `start`: single call-site for the attempt (covers both `startCall`:198 and `talk`:417):
```
fun start(context: Context) {
    VoxLog.d("event=fg-service-start requested sdk=${Build.VERSION.SDK_INT}")
    try {
        val i = Intent(context, VoiceService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
        else context.startService(i)
        VoxLog.d("event=fg-service-start ok")
    } catch (e: Exception) { VoxLog.e("event=fg-service-start-failed err=${e.message}") }
}
```
Then MainActivity:198 `try { VoiceService.start(this) } catch (_: Exception) {}` collapses to `VoiceService.start(this)` (failure now logged, not swallowed); :417 unchanged (already bare).

`MainActivity.kt`:
- `endCall` (:210): first line `VoxLog.d("event=call-end callLive=$callLive")`
- `stopVoiceWake` (:285): when `voiceWake != null` log `VoxLog.d("event=wake released")` before `release()`
- `acquireVoiceWake` (:294): empty catch → `catch (e: Exception) { VoxLog.e("event=wake-acquire-failed err=${e.message}") }`; on success `VoxLog.d("event=wake acquired")`
- `onStop` no-call branch (:768): `VoxLog.d("event=activity-stop reason=no-live-call controller-stopped")`

**Verify:** compile + unit; manual: start/hang-up a call → onCreate/start/destroy + wake lines; startForeground denial path logs `startForeground-failed` and returns `START_NOT_STICKY` (no restart loop).

## Backlog (P1/P2, out of scope — logged for the next patch)

F7 VAD/absent-deaf-loop, F8 state/arm/warm one-liners, F9 SystemTts, F10 SherpaTts stall/streamChunk, F11 silent-catch triage, F12 permission/reconnect, F13 settings/mode audit trail, F14-cleanup (`reset()` wiring to session), F15 SSE event timeline, F16 rotation (~1 MB → `.1`) + date in timestamp, F17 native-crash breadcrumbs, F18 download telemetry. F6's debug-only poll batch detail is `dd`-ready for when F15 lands.

## Risks & mitigations

1. **Log volume on low-end devices.** Per-turn adds ~4–8 file lines/turn (gate-release, turn summary, state at C2–C4; barge-in lines are rare). The 464 poll spam is removed (C4) and its replacement is `dd` (logcat-only in prod). Worst case a chatty tool turn ≈ tens of lines, bounded. Watch `file.length()` growth on a long call; F16 rotation is the follow-up cap.
2. **File rotation / unbounded growth.** Still append-only for the process lifetime (F16 deferred). This patch *reduces* growth; the Settings "Clear logs" path remains the manual reset. Do not ship C4 without the spam gate — the two must land together (they do, one commit).
3. **gomobile error surfacing.** Go error text (e.g. `stream.go:125` embeds the HTTP body on non-200) can be long or contain server text; all new lines truncate `err=` to 70–120 chars and are W/E only. Never log `arguments`/`output`/`transcript` (privacy) — only lengths. If a future need for deeper SSE visibility arises, do it by returning counters via `PollStreamJSON` (audit §3.6), not in-process Go logs.
4. **Concurrency regression surface.** All edits are log-only except: `releaseTurnGate` signature (mechanical, 10 sites), the `latch.await` return value now captured (behavior identical), `onStartCommand` now returns `START_NOT_STICKY` only on a startForeground failure (deliberate), and the LatencyStats clear-after-emit (pure, ring semantics only). The fragile half-duplex ordering (stt pushed at :277 before `runStreamedTurn`) is why per-turn values are cleared *after* the summary, never at arm time.
5. **Privacy.** No new line carries content; `logTranscripts()` gate (VC:911) is the only content channel and is preserved verbatim.

## Rollout order (shippable at every commit)

C1 → C2 → C3 → C4 → C5 → C6, pushed one at a time; each passes `cd android && ./gradlew :app:testDebugUnitTest` and is release-cuttable. C1 first (infra the later `dd` needs); C2 next (the highest-value P0 line); C3/C4 together make a stalled/errored stream fully reconstructable; C5 and C6 are independent lifecycle visibility. Suggested commit subjects: `log: add debug/production file-level split (VoxLog dd, debug_log pref)`, `log: per-turn outcome at gate release with reason (F1, F14)`, `log: gate-timeout + sDone-await markers (F2)`, `log: surface stream errors, gate poll spam, stall markers (F3, F6)`, `log: barge-in trigger/arm events (F4)`, `log: fg-service + wake-lock lifecycle (F5)`.
