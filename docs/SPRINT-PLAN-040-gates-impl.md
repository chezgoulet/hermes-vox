# Hermes Vox — Sprint 4 Implementation Plan (branch `feature/040-gates`)

Reference brief: `docs/SPRINT-PLAN-040-gates.md`. Scope: Stability + Voice-Quality. **Out:** Enhanced Realtime (Gemma), Google Play.
App root: `android/app/src/main/java/com/hermesvox/`. Build: `android/`. All edits are plan-only.

Verified facts underpinning every edit (not assumed):
- sherpa-onnx **1.13.6** AAR (`android/app/libs/sherpa-onnx-1.13.6.aar`) exposes `OnlineRecognizer` (streaming ASR → partials), `OnlineSpeechDenoiser`/`OfflineSpeechDenoiser` + `OfflineSpeechDenoiserGtcrnModelConfig`/`DpdfNetModelConfig`, `OnlineSpeechDenoiserConfig`, `DenoisedAudio`. So both streaming-partials and a real NS path are already bundled — **no new native dep needed**.
- `minifyEnabled false` at `android/app/build.gradle:41`; the old keep-rule for commons-compress is `proguard-rules.pro:18-20`.
- `MainActivity` has **no** `onRequestPermissionsResult` today; `talk()` already requests via code `100` (lines 333-344) but **does not handle the grant** (just `return`).
- The realtime loop's only STT is full-utterance offline Whisper (`VoiceController.listenOffline`, segment built at 181-201, single blocking `stt.transcribe` at 204). No partial path exists.
- `releaseTurnGate` at `VoiceController.kt:618-623` already `@Volatile turnReleased`-guards (#60), but has **7 release call-sites** (multi-path).

---

## WORKSTREAM 1 — #61 mic-permission prompt (HIGH)

**Goal:** start-call path requests `RECORD_AUDIO` (mirroring the working `talk()` path) and proceeds on grant. Consider `POST_NOTIFICATIONS`.

**File:** `android/app/src/main/java/com/hermesvox/MainActivity.kt`

**Edit 1 — companion request code** (in `companion object { ... }`, after `@Volatile var callStartedAt = 0L`, currently line 257):

Current:
```kotlin
        @Volatile var callStartedAt = 0L
        @Volatile var liveController: VoiceController? = null
```
Replacement:
```kotlin
        @Volatile var callStartedAt = 0L
        @Volatile var liveController: VoiceController? = null
        // #61: dedicated call-start permission request code + pending flag so the
        // warm-retry path never re-requests while the dialog is up / after denial.
        const val REQ_MIC_CALL = 101
        @Volatile var callStartPending = false
```

**Edit 2 — start-call permission gate** (currently lines 135-137). Current:
```kotlin
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setStatus("Mic permission needed to start the call", true); return
        }
```
Replacement:
```kotlin
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (!callStartPending) {
                callStartPending = true
                val rationale = ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)
                setStatus(if (rationale) "Mic needs to be enabled to start the call" else "Mic permission needed to start the call", true)
                val needNotif = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                // Mirror the working talk() path (lines 333-344): RECORD_AUDIO always
                // requested for the mic-type foreground service; POST_NOTIFICATIONS only
                // when missing (a fg-service notification renders for Android 13+).
                ActivityCompat.requestPermissions(this,
                    listOfNotNull(
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS.takeIf { needNotif }
                    ).toTypedArray(), REQ_MIC_CALL)
            }
            return
        }
```
This keeps `POST_NOTIFICATIONS` optional (an FGS runs without it; it only affects notification visibility) and honors the "proceed on grant" goal. Because the warm-retry `startCall()` re-schedules only *after* the permission gate passes, tapping call → grant → `startCall()` recurses cleanly with `callStartPending=false`.

**Edit 3 — add `onRequestPermissionsResult`** — insert as a new method after `endCall()` (after line 183). **This method does not exist yet; net-new.**
```kotlin
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC_CALL) {
            callStartPending = false
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startCall()   // mic granted -> the call proceeds (warm + open the line)
            } else {
                setStatus("Mic permission denied — tap call to retry", true)
            }
        } else if (requestCode == 100) {
            // talk() path: previously it just returned; now proceed on grant so a
            // single tap grants + opens the walkie line.
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                talk()
            } else {
                setStatus("Mic permission denied", true)
            }
        }
    }
```

**Risks:** No new imports needed (`ActivityCompat`, `ContextCompat`, `PackageManager`, `Manifest` already imported). FGS mic-type start on Android 14 (targetSdk 34) requires `RECORD_AUDIO` granted at `startForeground`; requesting before `VoiceService.start(this)` (ensured by this gate) prevents the `SecurityException`. `POST_NOTIFICATIONS` is intentionally conditional so a user who denies notifications can still make calls.

**Verify:** fresh install → tap call → system mic dialog → grant → call starts (emulator `emulator-5554` + on-device). Deny → pill shown, no crash; tap again re-prompts (rationale-aware).

---

## WORKSTREAM 2 — Streaming / partial STT (#38) ⭐ the big change

**Goal:** emit incremental partial hypotheses as the user speaks and start the agent *earlier* than full-silence, without double-turn/mid-utterance cut, keeping barge-in sane.

### Chosen approach (honest, buildable with the existing offline-Whisper + AAR)
A true streaming-ASR swap would need a new streaming zipformer model; instead we implement **"stable-partial early turn-start"** reusing the offline Whisper model:

- A **partial worker** (separate `exec` thread, single-flight) periodically snapshots the *growing* speech buffer (bounded tail) and transcribes it, so the **capture loop is never blocked** (transcribe is CPU-heavy + would otherwise drop mic frames).
- The **turn starts** when the partial is **stable**: the normalized transcript is unchanged from the previous partial AND a `silentMs` pause ≥ `earlySilenceMs` (default 450 ms) has elapsed. This fires *before* the current 800 ms full-silence endpoint → the agent begins ~350 ms earlier (the latency/alive win) with **no double-turn** (exactly one turn is launched) and **no mid-utterance cut** (only a genuine pause + unchanged hypothesis triggers it, not a mid-word pause).
- The turn text is that stable partial (the user is effectively done). If no partial ever stabilizes, the existing full-silence path still emits the full transcription — unchanged.

The `/v1/responses` stream "reacts to partials" via the `runStreamedTurn` launch now firing on the stable partial rather than on the full-silence moment (rest of streaming behaves unchanged).

### New file — `android/app/src/main/java/com/hermesvox/VoiceLoopState.kt` (pure JVM, testable)
Folds the #60 exactly-once gate + the stable-partial rule (also the subject of WS6's `TurnGateReleaseTest`). Full intended content:
```kotlin
package com.hermesvox

/**
 * VoiceLoopState — the pure-JVM, emulator-free core of the realtime loop's turn
 * gate + the streaming-STT stable-partial rule.
 *
 * - arm(): start a NEW turn epoch; retires the previous epoch's gate. Only a
 *   fresh arm() re-arms; prior release() latches are discarded.
 * - release(): countDown the active epoch's latch exactly once. Every racing
 *   release (barge-in + settle + error + speak-off) collapses to one real
 *   release (#60). Later releases for the same epoch are no-ops.
 * - mayStart(): the early-turn-start gate. A turn may START once the normalized
 *   partial hypothesis is unchanged since the previous snapshot AND the speaker
 *   has been silent for >= earlySilenceMs — a genuine pause, not a mid-word cut.
 */
class VoiceLoopState(private val earlySilenceMs: Long = 450L) {

    @Volatile private var epoch = 0L
    @Volatile private var releasedForEpoch = false
    @Volatile private var lastNormalized = ""
    @Volatile private var lastPartialAt = 0L

    @Synchronized fun arm() { epoch++; releasedForEpoch = false }

    @Synchronized fun release(): Boolean {
        if (releasedForEpoch) return false
        releasedForEpoch = true
        return true
    }

    @Synchronized fun mayStart(text: String, silentMs: Long, nowMs: Long): Boolean {
        val n = normalize(text)
        val stable = n.isNotBlank() && silentMs >= earlySilenceMs && n == lastNormalized
        if (n != lastNormalized) { lastNormalized = n; lastPartialAt = nowMs }
        return stable
    }

    @Synchronized fun reset() { releasedForEpoch = false; lastNormalized = "" }

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()
}
```

### Edit — `VoiceController.kt`

**E1. New fields** (add near the other `@Volatile` state, after line 66 `sttReady`):
```kotlin
    @Volatile private var partialRunning = false
    @Volatile private var partialEnabled = false
    private val voiceState = VoiceLoopState(micInt("vad_early_silence_ms", 450))
```
(`VoiceController` is constructed with `context`, so `micInt(...)` referencing `context.getSharedPreferences` is available — see `micInt` at line 688.)

**E2. `isWarm` unaffected.** `buildStt()` unchanged.

**E3. `listenOffline()` — enable partials + early-start.** Current inner block (lines 168-224). Insert partial-armature. Replace the segment-gather block (lines 180-207) so the partial worker is started and the early-start decision is honored and the turn text flows once.

Concretely, replace the capture `while (listening && !commitRequested) { ... }` body end + the text step. Current (lines 180-216):
```kotlin
                    // VAD-driven segmentation: gather one utterance; close it on a pause.
                    while (listening && !commitRequested) {
                        val n = r.read(shortBuf, 0, shortBuf.size)
                        if (n <= 0) continue
                        val frames = FloatArray(n)
                        for (i in 0 until n) frames[i] = shortBuf[i] / 32768f
                        val spoke = (vad?.isAvailable == true) && vad!!.feed(frames)
```
Replacement (adds partial snapshot after frames are appended):
```kotlin
                    // VAD-driven segmentation: gather one utterance; close it on a pause.
                    val partialEnabled = micBool("partial_stt", true)
                    var lastPartialMs = android.os.SystemClock.uptimeMillis()
                    var earlyStartText: String? = null
                    while (listening && !commitRequested && earlyStartText == null) {
                        val n = r.read(shortBuf, 0, shortBuf.size)
                        if (n <= 0) continue
                        val frames = FloatArray(n)
                        for (i in 0 until n) frames[i] = shortBuf[i] / 32768f
                        val spoke = (vad?.isAvailable == true) && vad!!.feed(frames)
```
And at the end of the segmentation block, after line 199/200 (break conditions), add the partial emission. Replace current (lines 199-207 area):
```kotlin
                        if (inSpeech && silentMs > silenceMs) break      // pause -> utterance complete
                        if (android.os.SystemClock.uptimeMillis() - segStart > maxMs) break
                    }
                    try { r.stop() } catch (_: Throwable) {}
                    val text = if (inSpeech && seg.size >= (sr * minSpeechMs / 1000)) {
                        try { stt?.transcribe(seg.toFloatArray(), sr) } catch (e: Throwable) { VoxLog.e("transcribe: ${e.message}"); null }
                    } else null
```
Replacement:
```kotlin
                        if (inSpeech && silentMs > silenceMs) break      // pause -> utterance complete
                        if (android.os.SystemClock.uptimeMillis() - segStart > maxMs) break
                        // #38 partial STT: on a SEPARATE worker (never block capture),
                        // snapshot a bounded tail + transcribe; start the turn EARLY on
                        // a stable partial (unchanged hypothesis + a >=450ms pause).
                        if (partialEnabled && inSpeech && !turnInFlight &&
                            android.os.SystemClock.uptimeMillis() - lastPartialMs >= 900 &&
                            seg.size >= (sr * minSpeechMs / 1000)) {
                            lastPartialMs = android.os.SystemClock.uptimeMillis()
                            if (!partialRunning) {
                                partialRunning = true
                                val snap = seg.subList(maxOf(0, seg.size - sr * 6), seg.size).toFloatArray()
                                val now = android.os.SystemClock.uptimeMillis()
                                exec.execute {
                                    try {
                                        val t = runCatching { stt?.transcribe(snap, sr) }.getOrNull()
                                        if (t.isNullOrBlank()) return@execute
                                        if (voiceState.mayStart(t, silentMs.toLong(), now)) {
                                            main.post {
                                                if (!turnInFlight && !commitRequested) {
                                                    listener?.onLog(if (logTranscripts()) "// partial → ${t.take(120)}" else "// (partial)")
                                                    earlyStartText = t
                                                    commitRequested = true   // drain the loop; the turn runs below
                                                }
                                            }
                                        }
                                    } finally { partialRunning = false }
                                }
                            }
                        }
                    }
                    try { r.stop() } catch (_: Throwable) {}
                    val text = if (inSpeech && seg.size >= (sr * minSpeechMs / 1000)) {
                        try { stt?.transcribe(seg.toFloatArray(), sr) } catch (e: Throwable) { VoxLog.e("transcribe: ${e.message}"); null }
                    } else null
```
Then prefer the stable partial when present (so the agent got exactly its early text — no double transcript). Replace current (lines 207-211):
```kotlin
                    VoxLog.d("realtime: speech=$inSpeech ms=${seg.size * 1000 / sr} text=${if (logTranscripts()) text?.take(120) else "<hidden>"}")
                    if (text.isNullOrBlank()) continue                  // noise / no-speech -> keep listening
                    val t = text.trim()
```
Replacement:
```kotlin
                    val t = (earlyStartText ?: text).trim()
                    VoxLog.d("realtime: speech=$inSpeech ms=${seg.size * 1000 / sr} early=${earlyStartText != null} text=${if (logTranscripts()) t.take(120) else "<hidden>"}")
                    if (t.isBlank()) continue                           // noise / no-speech -> keep listening
```
(Note `text.isNullOrBlank()` → `t.isBlank()` because `t` is now a non-null `String`; drop the prior `val t = text.trim()`.)

**E4. `runStreamedTurn()` — arm the state once per turn; release via the state.** Replace the `turnReleased`/release sites.

Current (lines 330-333):
```kotlin
    private fun runStreamedTurn(text: String, gen: Long) {
        if (turnInFlight) { VoxLog.d("turn suppressed (in flight)"); releaseTurnGate(turnGen); return }
        turnInFlight = true
        turnReleased = false   // #60: re-arm exactly-once for this turn
```
Replacement:
```kotlin
    private fun runStreamedTurn(text: String, gen: Long) {
        if (turnInFlight) { VoxLog.d("turn suppressed (in flight)"); releaseTurnGate(turnGen); return }
        turnInFlight = true
        voiceState.arm()        // #60: re-arm exactly-once for this turn (via VoiceLoopState)
        val t0 = android.os.SystemClock.uptimeMillis()
```

**E5. `releaseTurnGate()` (currently lines 618-623):** Current:
```kotlin
    private fun releaseTurnGate(gen: Long) {
        if (gen != turnGen) return
        if (turnReleased) return   // #60: a turn's gate releases exactly once
        turnReleased = true
        try { turnDone.countDown() } catch (_: Throwable) {}
    }
```
Replacement:
```kotlin
    private fun releaseTurnGate(gen: Long) {
        if (gen != turnGen) return
        if (!voiceState.release()) return   // #60: exactly once per epoch (race-safe)
        try { turnDone.countDown() } catch (_: Throwable) {}
    }
```
This removes the ad-hoc `turnReleased` flag; the `VoiceLoopState.arm()/release()` owns exactly-once. (Keep the `@Volatile private var turnReleased = false` field declaration removed or left unused — remove to avoid dead field; it is only referenced in these two sites.)

**E6. `stop()` — drain partial worker + native handles (aids #8).** Extend the existing `stop()` (lines 238-255). After `stopBargeInWatch()`, add a bounded wait for `loopActive`/`partialRunning`, and reset the state. Replace current (lines 240-246):
```kotlin
        listener?.also { }
        recognizer?.destroy(); recognizer = null
        stopTts()
        stopStreaming()
        stopBargeInWatch()
        try { record?.stop(); record?.release() } catch (_: Exception) {}
        try { bargeRecord?.stop(); bargeRecord?.release() } catch (_: Exception) {}
        record = null; bargeRecord = null
```
Replacement:
```kotlin
        recognizer?.destroy(); recognizer = null
        stopTts()
        stopStreaming()
        stopBargeInWatch()
        // #8: wait (bounded) for the capture loop + partial worker to exit their
        // native calls (vad.feed / stt.transcribe / record.read) before releasing
        // the native handles -> no use-after-free on a background/teardown race.
        val ns = android.os.SystemClock.uptimeMillis()
        var waited = 0L
        while ((loopActive || partialRunning) && waited < 500L) {
            android.os.SystemClock.sleep(20L); waited = android.os.SystemClock.uptimeMillis() - ns
        }
        try { record?.stop(); record?.release() } catch (_: Exception) {}
        try { bargeRecord?.stop(); bargeRecord?.release() } catch (_: Exception) {}
        record = null; bargeRecord = null
        voiceState.reset()
```

**E7. settings hooks:** the early-silence + partial toggles read via `micInt`/`micBool` already (defaults 450 ms / true). Optional UI wiring in `SettingsActivity` is out of scope unless trivial; note it as a follow-up.

**Risks / guardrails:** partials run off the capture thread (no mic drop). Single-flight (`partialRunning`) prevents whisper queueing. `normalize()` strips punctuation/case so identical-acoustic pauses hash the same. Early start only on `silentMs >= 450` + unchanged hypothesis → no mid-word cut; exactly one turn → no double-turn. Turn launches via `main.post`; the `commitRequested` drain reuses the existing teardown; the speak-gate (half-duplex) still blocks barge-in during speech. If `partial_stt` set false → behaviour is byte-identical to today.

**Verify:** measure a perceptible drop in time-to-first-word of the reply (see WS3 numbers); no double-turn, no mid-utterance cut (say a 5-sentence answer with normal pauses — it should start speaking after the 1st-2nd sentence at latest, not after the whole utterance); on-device + emulator.

---

## WORKSTREAM 3 — Latency instrumentation (#40)

**Goal:** emit per-stage P50/P95 for time-to-first-byte, first-audio, full-reply (plus speech→text). Not user-facing.

### New file — `android/app/src/main/java/com/hermesvox/LatencyStats.kt`
```kotlin
package com.hermesvox

/** #40 per-stage latency capture. Not user-facing; logs P50/P95 so the realtime
 *  loop is quantifiable + regression-testable. Bounded ring per metric. */
object LatencyStats {
    private val lock = Object()
    private val firstByte = ArrayList<Long>()
    private val firstAudio = ArrayList<Long>()
    private val fullReply = ArrayList<Long>()
    private val stt = ArrayList<Long>()
    private const val MAX = 128L
    private var turns = 0L

    fun pushFirstByte(ms: Long) { synchronized(lock) { firstByte.add(ms); trim(firstByte) } }
    fun pushFirstAudio(ms: Long) { synchronized(lock) { firstAudio.add(ms); trim(firstAudio) } }
    fun pushFullReply(ms: Long) { synchronized(lock) { fullReply.add(ms); trim(fullReply) } }
    fun pushStt(ms: Long) { synchronized(lock) { stt.add(ms); trim(stt) } }

    private fun trim(l: ArrayList<Long>) { while (l.size > MAX) l.removeAt(0) }

    fun log(label: String) {
        synchronized(lock) {
            turns++
            if (turns % 8L != 0L) return
            VoxLog.d("lat[$label] ttf-first-byte p50=${pct(firstByte,0.50)} p95=${pct(firstByte,0.95)} n=${firstByte.size}")
            VoxLog.d("lat[$label] ttf-first-audio p50=${pct(firstAudio,0.50)} p95=${pct(firstAudio,0.95)} n=${firstAudio.size}")
            VoxLog.d("lat[$label] ttf-full-reply p50=${pct(fullReply,0.50)} p95=${pct(fullReply,0.95)} n=${fullReply.size}")
            VoxLog.d("lat[$label] stt(speech->text) p50=${pct(stt,0.50)} p95=${pct(stt,0.95)} n=${stt.size}")
        }
    }
    fun reset() { synchronized(lock) { firstByte.clear(); firstAudio.clear(); fullReply.clear(); stt.clear(); turns = 0 } }
    private fun pct(l: List<Long>, p: Double): Long { if (l.isEmpty()) return 0L; val s = l.sorted(); return s[(s.size - 1).toDouble().times(p).toInt()] }
}
```

### Edits — `VoiceController.kt`

**E1. timestamps in `listenOffline()`** (STT latency). `segStart` already exists (line 177). After the `stt?.transcribe(...)` line (now inside the WS2-modified block), add:
```kotlin
                    if (text != null) LatencyStats.pushStt(android.os.SystemClock.uptimeMillis() - segStart)
```
(Place immediately after the `val text = ...` expression.)

**E2. timestamps in `runStreamedTurn()`.** Add locals after `val t0 = SystemClock.uptimeMillis()` (E4 of WS2) and wire through:
- First byte: after `val sid = session.startStream(text)` → `val firstByteAt = SystemClock.uptimeMillis()`, and on the first `payload != null` push `LatencyStats.pushFirstByte(firstByteAt - t0)`.
- First audio: in `emitEvents`, `response.output_text.delta` branch — on first delta (only once) push `LatencyStats.pushFirstAudio(now - t0)`.
- Full reply: in `settleReply` (or the `done` branch) push `LatencyStats.pushFullReply(now - t0)`.
- Every turn end: `LatencyStats.log("turn")`.

Concretely, edit the `done` block (currently lines 352-369): add pushFullReply when `finalText` is settled, and add `LatencyStats.log("turn")` after `releaseTurnGate(gen)` in both the error and success `main.post` blocks. Edit `emitEvents` delta branch (currently 409-415) to push first-audio once via a per-turn latch flag.

Add a field `@Volatile private var firstAudioLatch = false;` reset per turn in `runStreamedTurn` entry (`firstAudioLatch = false`).

**Risks:** purely additive logging; no behavior change. `LatencyStats` is safe to call from any thread (synchronized). First-byte is measured from turn launch (transcript ready) — consistent baseline.

**Verify:** a short on-device call emits the four `lat[...]` lines; a forced delay (e.g., temporary `Thread.sleep(1000)` in `startStream`) shows up in `ttf-first-byte`.

---

## WORKSTREAM 4 — Speech enhancement (loud-environment STT)

The AAR exposes a real denoiser. Two-layer answer:

### Option A (model-free, exact edit) — push extra platform NS before STT
`android.media.audiofx.NoiseSuppressor` on the `AudioRecord`'s audio session, alongside the existing `VOICE_COMMUNICATION` source. Gated by a setting so it can be disabled if it double-processes.

**E1. `VoiceController.kt` — field** (near `bargeAec`, line 47):
```kotlin
    private var ns: android.media.audiofx.NoiseSuppressor? = null
```

**E2. `listenOffline()` — attach after record init.** Current (lines 153-155):
```kotlin
            val r = AudioRecord(source, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf)
            if (r.state != AudioRecord.STATE_INITIALIZED) return listen()
            record = r
```
Replacement:
```kotlin
            val r = AudioRecord(source, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf)
            if (r.state != AudioRecord.STATE_INITIALIZED) return listen()
            record = r
            // #40/#81: push an extra platform NS on the record's session for the
            // loud-environment case, on top of VOICE_COMMUNICATION AEC/NS. Optional
            // (ns_extra) — disable if a device double-processes the speech.
            try {
                if (micBool("ns_extra", true) && android.media.audiofx.NoiseSuppressor.isAvailable()) {
                    ns?.release(); ns = null
                    ns = android.media.audiofx.NoiseSuppressor.create(r.audioSessionId)?.also { it.enabled = true }
                    VoxLog.d("mic: extra NoiseSuppressor attached (session ${r.audioSessionId}) enabled=${ns?.enabled}")
                }
            } catch (_: Throwable) {}
```

**E3. `stop()` — release.** After the `record?.release()` in the (WS2-modified) `stop()`, add:
```kotlin
        try { ns?.release() } catch (_: Exception) {}; ns = null
```

**Risks:** `NoiseSuppressor` is on the AudioRecord session; availability varies by device (Android 24+). With `VOICE_COMMUNICATION` the OS may already NS; `ns_extra` default true risks double-processing on some OEM skins — hence the toggle + log. TTS path untouched (playback side), so no voice/output regression.

**Verify (Option A):** loud-environment STT accuracy on-device; log line confirms attach; disable via setting to compare; no TTS regression on a clean room.

### Option B (documented sherpa-onnx NS path) — the real fix for loud-street
The AAR bundles `OnlineSpeechDenoiser`. Documented path (implement only if Option A is insufficient; it needs a denoiser model download):

- Model: sherpa-onnx NS **GTCRN** (`sherpa-onnx-ns-gtcrn` → `model.onnx`, tiny). Add a `ModelSpec("ns-gtcrn", ...)` to `ModelCatalog.blessed` with a verified **upstream URL + sha256** (must be sourced from the k2-fsa release zoo and verified by ingestion, per the "verify against a real download" iron rule — do **not** fabricate the hash).
- Load (mirror `OfflineWhisperStt.init`):
  ```kotlin
  val gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(modelPath)            // "model.onnx"
  val dm = OfflineSpeechDenoiserModelConfig(gtcrn, null, 1, false, "cpu")
  val den = OnlineSpeechDenoiser(null, OnlineSpeechDenoiserConfig(dm))
  // per snapshot: val out = den.run(snapshot, sr); val clean = out.samples
  ```
- Feed: inside `listenOffline()`, right before `stt.transcribe(...)` on the segment snapshot, run `den.run(seg, sr)` and feed `DenoisedAudio.samples` to the whisper transcribe. Reset with `den.reset()` per utterance.
- Gate behind `micBool("ns_mic", false)` (off by default until verified) so it can be validated without forcing the model.

**Recommendation:** ship Option A now (safe, zero-download, immediately helps), and document Option B as the follow-up for genuine loud-street; do **not** block this sprint on a new NS model download + sha verification.

---

## WORKSTREAM 5 — Crash-hardening #7/#8

### #7 — `AudioTrack` release under a blocked write → SIGSEGV — `SherpaTts.kt`
The racing paths: the streaming worker calls `streamChunk`→`t.write(WRITE_BLOCKING)` while the activity/barge-in `stop()`→`SherpaTts.stopStreaming()`→`streamTrack.release()`. Release must never happen mid-write.

**E1. fields** (after line 25 `streamWritten`):
```kotlin
    private val trackLock = Object()
    @Volatile private var writing = false
```

**E2. `streamChunk()` serialized write.** Replace current (lines 143-155):
```kotlin
    fun streamChunk(text: String): Boolean {
        val t = streamTrack ?: return false
        val eng = tts ?: return false
        return try {
            val audio = eng.generate(text, 0, 1.0f)
            val samples = audio.samples ?: return false
            if (streamSR == 0) streamSR = audio.sampleRate
            VoxLog.d("piper chunk ${samples.size} smp @${audio.sampleRate}Hz (${text.length} ch)")
            t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            streamWritten += samples.size
            true
        } catch (e: Throwable) { VoxLog.e("streamChunk: ${e.message}"); false }
    }
```
Replacement:
```kotlin
    fun streamChunk(text: String): Boolean {
        val eng = tts ?: return false
        return try {
            val audio = eng.generate(text, 0, 1.0f)
            val samples = audio.samples ?: return false
            if (streamSR == 0) streamSR = audio.sampleRate
            val t: AudioTrack
            synchronized(trackLock) {
                t = streamTrack ?: return false
                writing = true                 // stopStreaming() waits for this to clear
            }
            VoxLog.d("piper chunk ${samples.size} smp @${audio.sampleRate}Hz (${text.length} ch)")
            try { t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING); streamWritten += samples.size; true }
            finally { synchronized(trackLock) { writing = false; trackLock.notifyAll() } }
        } catch (e: Throwable) { VoxLog.e("streamChunk: ${e.message}"); false }
    }
```
(Date-race note: because `t` is captured under the lock and `stopStreaming` nulls `streamTrack` under the same lock *before* waiting, a `streamTrack !== t` guard is unnecessary — the null-out already fences.)

**E3. `stopStreaming()` waits for the in-flight write, then releases.** Replace current (lines 169-172):
```kotlin
    fun stopStreaming() {
        try { streamTrack?.stop(); streamTrack?.release() } catch (_: Throwable) {}
        streamTrack = null; streamWritten = 0
    }
```
Replacement:
```kotlin
    fun stopStreaming() {
        val t: AudioTrack?
        synchronized(trackLock) {
            t = streamTrack
            streamTrack = null                       // fence: no new write may start
            while (writing) { try { trackLock.wait(50) } catch (_: Throwable) { break } }
        }
        try { t?.pause() } catch (_: Throwable) {}   // stop might block on a full buffer in some builds
        try { t?.stop() } catch (_: Throwable) {}
        try { t?.flush() } catch (_: Throwable) {}
        try { t?.release() } catch (_: Throwable) {} // never concurrent with a WRITE -> no SIGSEGV #7
        streamWritten = 0
    }
```
`finishStreaming()` (lines 158-168) already ends by calling `stopStreaming()`, so it inherits the guard. `play()` (lines 78-108) uses a local track nobody else releases → safe.

### #8 — native use-after-free on background teardown — `VoiceController.stop()` (done in WS2 E6) + make native objects `@Synchronized`
- `VoiceController.stop()` now waits on `loopActive || partialRunning` before `record/vad/stt` release (WS2 E6) → the capture/partial threads leave the native calls first.
- **`OfflineStt.kt`:** `transcribe` is already `@Synchronized`; make `shutdown()` `@Synchronized` so a concurrent `transcribe` (partial worker) can't race the native `rec.release()`. Current:
  ```kotlin
  override fun shutdown() { rec?.release(); rec = null }
  ```
  → `@Synchronized override fun shutdown() { rec?.release(); rec = null }`
- **`SileroVadGate`:** make `feed` and `shutdown` `@Synchronized` (native `vad.acceptWaveform` vs `vad.release`). Current lines 150-156:
  ```kotlin
  fun feed(samples: FloatArray): Boolean {
      val v = vad ?: return false
      return try { v.acceptWaveform(samples); v.isSpeechDetected() } catch (_: Throwable) { false }
  }
  fun shutdown() { vad?.release(); vad = null }
  ```
  → `@Synchronized fun feed(...)` (body unchanged) and `@Synchronized fun shutdown() ...`.
- **`SherpaTts.shutdown()`** (line 175 `tts = null`) — make `@Synchronized` too for symmetry with its `generate` calls.

**Risks:** `trackLock.wait` bounded by the `while (writing)` + outer try; a stuck write (blocked WRITE_BLOCKING) could hold up to the wait, but WRITE_BLOCKING eventually progresses; the pause-before-stop avoids the Android known `.stop()`-on-full-buffer deadlock. The WS2 `loopActive` wait is bounded at 500 ms so `stop()` can't hang.

**Verify:** rapid start/stop + background-foreground cycles mid-call (emulator + on-device); logcat shows no `SIGSEGV`/`Fatal signal`/`use after free`.

---

## WORKSTREAM 6 — Voice-loop polish (#60 + TurnGateReleaseTest)

### #60 single-path by construction
`releaseTurnGate` (WS2 E5) now delegates to `VoiceLoopState.release()`, which is the **single** exactly-once latch owner. All 7 prior call-sites (settle/error/timeout/barge/hush/suppress) funnel through `releaseTurnGate(gen)` already; the ad-hoc `@Volatile turnReleased` flag is removed. No other change — the latch is released exactly once per `arm()`-ed epoch regardless of how many paths race.

### `TurnGateReleaseTest` — `android/app/src/test/java/com/hermesvox/TurnGateReleaseTest.kt` (new)
The gate is pure JVM concurrency (`CountDownLatch`-free here; the state machine is the subject), so **no Robolectric is required** (see note below). Full content:
```kotlin
package com.hermesvox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Proves the realtime turn gate releases EXACTLY once per turn epoch (#60), even
 * when many paths race to release (barge-in + settle + error + speak-off). Pure
 * JVM — no Robolectric needed; VoiceLoopState has no Android deps.
 */
class TurnGateReleaseTest {

    @Test fun releases_exactly_once_under_racing_callers() {
        val state = VoiceLoopState()
        val latch = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        state.arm()
        val results = java.util.concurrent.ConcurrentLinkedQueue<Boolean>()
        for (i in 0 until 256) pool.submit { results.offer(state.release() == true && { latch.countDown(); true }()) }
        pool.shutdown(); pool.awaitTermination(5, TimeUnit.SECONDS)
        assertEquals(0L, latch.count)          // the turn-gate latch fired exactly once
        assertEquals(1, results.count { it })  // exactly one caller observed the "real" release
    }

    @Test fun rearming_for_a_new_turn_allows_another_release() {
        val state = VoiceLoopState(); val latch = CountDownLatch(1)
        state.arm(); assertTrue(state.release()); assertFalse(state.release())  // 2nd same-epoch release is a no-op
        state.arm(); assertTrue(state.release())                                // new epoch re-arms
    }

    @Test fun stable_partial_gates_early_turn_start() {
        val state = VoiceLoopState(earlySilenceMs = 450)
        assertFalse(state.mayStart("hello", silentMs = 200, nowMs = 1_000))     // pause too short -> no early start
        assertFalse(state.mayStart("hello", silentMs = 200, nowMs = 1_400))     // short pause -> no
        assertFalse(state.mayStart("hello world", silentMs = 500, nowMs = 1_900)) // text CHANGED -> no
        assertTrue(state.mayStart("hello world", silentMs = 500, nowMs = 2_400))  // unchanged + >=450ms -> early start
    }
}
```
This asserts: exactly-once release under racing callers; re-arm per epoch; and the stable-partial early-start rule.

### Robolectric note
The brief suggests Robolectric; **it is unnecessary here** because `VoiceLoopState` and the gate are pure JVM (the test above already covers exactly-once). If a *VoiceController-level* test (e.g., `start()`/`bargeIn()` on a fake) were wanted, Robolectric would be required because `VoiceController`'s `init` constructs `SherpaTts`/`OfflineWhisperStt`/`SystemTts` (Android deps) — but with the models absent it degrades to SystemTts and `isWarm=false`, making a meaningful controller test hard. Recommendation: keep the pure-JVM `TurnGateReleaseTest`; skip Robolectric (avoid the dependency + `testOptions.unitTests.includeAndroidResources` + `@Config(sdk=[34])` churn). Add Robolectric only if a controller-level test is later demanded:
```
testImplementation 'org.robolectric:robolectric:4.13'
testOptions { unitTests { includeAndroidResources = true } }
```

**Risks:** none beyond owning exactly-once in a tested class.

**Verify:** `./gradlew :app:testReleaseUnitTest` (or `:app:test`) — `TurnGateReleaseTest` green; existing `ModelCatalogSourceTest`/`VoiceOrchestratorTest` still green; on-device no double-release log / no stuck loop.

---

## DECISION — R8/minify + shrink

**Recommendation: fix the keep-rules correctly and re-enable `minifyEnabled true`, gated on a real model download. If the gate fails, drop explicitly (keep `minifyEnabled false`).**

Why the old rule failed: `-keep class org.apache.commons.compress.** { *; }` stripped the `@ServiceProvider` factory/`ServiceLoader` wiring + removed `InnerClasses`/`Signature`/`Annotations` attributes that the archive-reader class graph needs to rebuild streams at runtime (`CompressorStreamFactory` → `BZip2CompressorInputStream`; `TarArchiveInputStream`). With `shrinkResources false` this is purely a Java/reference-preservation fix.

**Edit — `android/app/proguard-rules.pro`, replace lines 18-20:**
Current:
```
# ---- org.apache.commons.compress: service-loaders + reflection (tar/bzip2) ----
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**
```
Replacement:
```
# ---- org.apache.commons.compress: runtime bzip2/tar path (ModelDownloader.unpkg) ----
# ModelDownloader.kt:122-123 constructs BZip2CompressorInputStream + TarArchiveInputStream
# directly. Keep the stream-graph attributes (InnerClasses/Signature) + the class-names
# + the ServiceLoader/providers used by the CompressorStreamFactory/TarArchiveInputStream
# reflection path, or R8 strips the factory wiring and the model unpack throws at runtime.
-keepattributes Exceptions,InnerClasses,Signature,Deprecated,Annotation,EnclosingMethod
-keep,includedescriptorclasses class org.apache.commons.compress.** { *; }
-keep,includedescriptorclasses class org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream { <init>(...); *; }
-keep,includedescriptorclasses class org.apache.commons.compress.archivers.tar.TarArchiveInputStream { <init>(...); *; }
-keepclassmembers,includedescriptorclasses class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**
```

**Edit — `android/app/build.gradle`, re-enable shrink:** current lines 41-42:
```groovy
            minifyEnabled false
            shrinkResources false
```
Replacement:
```groovy
            minifyEnabled true
            shrinkResources false
```
(`shrinkResources` stays false: the native .so + resource-heavy UI aren't the target; only ~2 MB of pure code is saved. ABI filters stay `arm64-v8a, armeabi-v7a` at line 45.)

**Gate before shipping (iron rule — verify, don't guess):** build `release`, install on device, and **perform a REAL model download** for `whisper-base` (`sherpa-onnx-whisper-base.en.tar.bz2`) → confirm the `untarBz2` bzip2/tar path runs (watch `VoxLog` `model whisper-base: result=OK` + the model dir gets `encoder.onnx`/`decoder.onnx`/`tokens.txt`), and run a phone-call smoke (silero VAD + Piper + whisper-base load). If the download/unpack throws under minify, do **not** silently revert — make the explicit decision to keep `minifyEnabled false` and record it (drop), per the brief.

**Risks:** the keep-rule is the only in-sample change; if R8 still strips a commons-compress internal, the gate catches it exactly where it bites (model unpack). `gomobile` `go.**` + `com.hermesvox.mobile.**` + `com.k2fsa.sherpa.onnx.**` + `com.google.ai.edge.litertlm.**` keep-rules are untouched.

**Verify:** build → APK size drop (~2 MB) + on-device real model download/untar succeeds + phone-call smoke green.

---

## Definition of done mapping
- **#61** → WS1 (grant → call starts).
- **Streaming STT** → WS2 (partials; no double-turn/cut; barge-in sane).
- **Latency instrumentation** → WS3 (P50/P95 per stage).
- **Speech enhancement** → WS4 (Option A edit + Option B documented path).
- **#7/#8** → WS5 (serialized AudioTrack release; teardown drain + `@Synchronized` native).
- **#60 + TurnGateReleaseTest** → WS6 (exactly-once via `VoiceLoopState`, pure-JVM test green).
- **Shrink decision** → fixed keep-rules + real-download gate (or explicit drop).
- **No-regress** → verify model downloader (`dir.parentFile?.mkdirs()` + logging intact), warm voice, offline STT, realtime loop smoke on-device. One coherent commit per workstream; each compiles + tests pass.
