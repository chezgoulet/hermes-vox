package com.hermesvox

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.hermesvox.mobile.HermesSession
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

/**
 * VoiceController is the phone-side "front of house" for a warm voice turn:
 * listen (on-device STT, guarded) -> the entity via a REAL STREAMED turn
 * (HermesSession.startStream/pollStreamJSON/cancelStream — the SSE events as
 * they arrive) -> speak (a VoxTts, warm when configured+present, else system),
 * with an AudioRecord-RMS barge-in that cancels the streamed turn and re-listens.
 *
 * It drives the UI through a [Listener] so the app renders the entity's work
 * live: state changes, incremental text, tool-progress lines, final reply.
 */
class VoiceController(private val context: Context, private val session: HermesSession) {

    /** UI render callbacks — all dispatched on the main thread. */
    interface Listener {
        fun onState(state: String)      // idle | listening | thinking | speaking
        fun onDelta(text: String)       // incremental assistant text (live)
        fun onLog(line: String)         // stream-console line (events, tool progress)
        fun onReply(finalText: String)  // assembled final reply (for TTS + settle)
        fun onError(msg: String)
    }

    private var recognizer: SpeechRecognizer? = null
    private var tts: VoxTts? = null
    private var stt: VoxStt? = null
    private var vad: SileroVadGate? = null
    private var record: AudioRecord? = null
    private var bargeRecord: AudioRecord? = null
    private var bargeAec: android.media.audiofx.AcousticEchoCanceler? = null
    private var ns: android.media.audiofx.NoiseSuppressor? = null
    private val exec: ExecutorService = Executors.newCachedThreadPool()
    @Volatile private var commitRequested = false
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var turnDone = java.util.concurrent.CountDownLatch(1)
    @Volatile private var turnGen = 0L
    @Volatile private var speaking = false
    @Volatile private var bargeInArmed = false
    @Volatile private var currentStream: String? = null
    @Volatile private var turnInFlight = false
    @Volatile private var loopActive = false
    // D2 (interrupt-during-generation): a generation-phase mic watch that catches
    // the user's voice while the agent is still producing (thinking / tool-calls /
    // first-token latency), not only during playback. It is a SEPARATE recorder so
    // it runs while the main capture loop is paused on the turn gate (the main
    // record is stopped for the turn). It self-stops the instant `speaking` flips
    // (the AEC'd playback barge-in takes over) so it never hears its own reply.
    @Volatile private var genWatchArmed = false
    @Volatile private var genRecord: AudioRecord? = null
    // Set when a barge-in lands before startStream returned (currentStream was
    // still null), so the stream loop breaks instead of settling a ghost reply.
    @Volatile private var genCancelled = false
    // MODE-GATED RE-LISTEN: true (Realtime/Enhanced) keeps the loop going after a
    // turn; false (Walkie PTT) does exactly one turn then stops until the next PTT.
    @Volatile var continuous = false
    // SPEAK GATE (WS4a): voice a reply only when a call / voice channel is open.
    // Set by the host: MainActivity -> == callLive; RealtimeActivity -> true.
    // Default false = a reply with no open voice channel is TEXT-ONLY (fixes the
    // slash-command-speaks-with-no-call bug).
    @Volatile private var voiceChannelOpen = false
    private var warmTries = 0
    @Volatile private var listening = false
    private var listener: Listener? = null
    private var bargeInEnabled = true
    @Volatile private var ttsReady = false
    @Volatile private var sttReady = false
    @Volatile private var partialRunning = false
    @Volatile private var partialEnabled = false
    @Volatile private var firstAudioLatch = false   // #40: one first-audio push per turn
    private val voiceState = VoiceLoopState(micInt("vad_early_silence_ms", 450).toLong())
    // Guards idempotent pipeline re-init on start() so overlapping starts don't double-init.
    @Volatile private var initializing = false
    // Bounded wait on the turn-gate so a stuck reply can't wedge the listen loop forever.
    private val TURN_GATE_TIMEOUT_MS = 60000L

    init {
        // Blessed default: auto-use the warm on-device Piper voice once it's
        // installed; fall back to System only if Piper isn't present. (The old
        // default of "system" left the app speaking via a silent system TTS.)
        val voice = prefString("tts", if (ModelCatalog.isInstalled(context, "piper-lessac")) "piper" else "system")
        tts = buildTts(context, voice)
        VoxLog.d("pipeline: tts=${tts?.name} (voice=$voice) ttsReady=$ttsReady")
        // Load the TTS engine up front so a reply is always voiced (text OR mic).
        tts?.init { ttsReady = it }
        // STT backend/model from Settings: on-device Whisper (selected model),
        // house GPU (lemonade), or platform (SpeechRecognizer fallback).
        stt = buildStt()
        stt?.init { sttReady = it }
        if (ModelCatalog.isInstalled(context, "silero-vad")) {
            vad = SileroVadGate(context, micFloat("vad_threshold", 0.5f)); vad?.init {}
        }
        VoxLog.d("pipeline: stt=${stt?.name} sttReady=$sttReady vad=${vad?.isAvailable}")
    }

    private fun buildStt(): VoxStt? {
        val backend = prefString(ModelCatalog.KEY_STT_BACKEND, ModelCatalog.BACKEND_ONDEVICE)
        return when (backend) {
            ModelCatalog.BACKEND_PLATFORM -> null   // use the on-device Whisper model, else platform STT fallback
            else -> {
                val model = prefString(ModelCatalog.KEY_STT_MODEL, ModelCatalog.DEFAULT_STT_MODEL)
                if (ModelCatalog.isInstalled(context, model)) OfflineWhisperStt(context, model) else null
            }
        }
    }

    /** True once the offline STT + TTS (+ VAD) are fully loaded/warm. */
    fun isWarm() = sttReady && ttsReady && (vad?.isAvailable != false)

    /** True when the listen loop is actually running (capturing). NOT a sticky flag:
     *  the Activity gates re-open on this, so it can restart after a stop/background/
     *  turn instead of being locked out forever. */
    fun isListening() = listening && loopActive

    /** Warm-up breakdown for diagnostics: which leg is not yet ready. */
    fun warmDiagnostics(): String =
        "sttReady=$sttReady ttsReady=$ttsReady vadAvailable=${vad?.isAvailable != false} vadPresent=${vad != null}"

    /** Set/replace the render callbacks (works for text turns too — the send path). */
    fun attachListeners(l: Listener) { listener = l }

    /** Begins the listening loop. Returns true when STT is actually running. */
    fun start(l: Listener, enabled: Boolean): Boolean {
        listener = l; bargeInEnabled = enabled
        // Re-initialize any pipeline leg that stopped warming (e.g. after stop() reset the
        // ready flags) so a fresh start doesn't listen against a dead TTS/STT/VAD.
        ensureWarm()
        // Don't open the mic until the models are fully warm (Christopher: the delayed
        // first turn + the double-fire both came from listening before the pipeline loaded).
        if (!isWarm()) {
            listener?.onState("warming")
            if (warmTries++ < 30) { main.postDelayed({ if (listener === l) start(l, enabled) }, 500); return true }
            warmTries = 0
        }
        warmTries = 0
        return if (stt != null) listenOffline() else postListen()   // on-device Whisper, else platform STT
    }

    // --- On-device Whisper loop: capture mic -> VAD/silence -> transcribe -> turn ---
    // Platform SpeechRecognizer must run on the MAIN thread; post it.
    private fun postListen(): Boolean {
        main.post { listen() }
        return true
    }

    private fun listenOffline(): Boolean {
        if (loopActive) return false
        listener?.onState("listening"); listening = true
        val sr = 16000
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return listen()
        return try {
            // Hardware noise suppression + echo cancel on the capture side (many devices
            // enable real AEC/NS from VOICE_COMMUNICATION). Default on; the mic settings
            // let the user switch back to raw MIC.
            val source = if (micBool("mic_aec", true)) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC
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
            val shortBuf = ShortArray(1024)
            // Mic settings (user-changeable, logged so their impact is visible).
            val silenceMs = micInt("vad_silence_ms", 800)      // "the pause" that ends your turn
            val maxMs = micInt("vad_max_ms", 15000)
            val minSpeechMs = micInt("vad_min_speech_ms", 300)
            val sourceName = if (source == MediaRecorder.AudioSource.VOICE_COMMUNICATION) "VOICE_COMMUNICATION(AEC/NS)" else "MIC"
            VoxLog.d("mic: source=$sourceName threshold=${"%.2f".format(micFloat("vad_threshold", 0.5f))} silence=${silenceMs}ms minSpeech=${minSpeechMs}ms max=${maxMs}ms")
            exec.execute {
                val seg = ArrayList<Float>(sr)
                // ONE owning loop. Half-duplex: it listens OR speaks, never both — so it
                // can NEVER hear its own reply (the self-trigger/echo). The hard speak-gate
                // (turnDone.await through speech-complete) enforces that.
                while (listening) {
                    loopActive = true
                    commitRequested = false
                    try { r.startRecording() } catch (_: Throwable) { break }
                    // #28: listener callbacks must land on the main thread (the
                    // documented contract) — the capture loop runs on the executor.
                    main.post { listener?.onState("listening") }
                    main.post { listener?.onLog("// hear → listening") }
                    var inSpeech = false; var silentMs = 0
                    // #18: ~250ms pre-roll ring (sr/4 samples @16kHz) so the VAD's
                    // start-detection delay doesn't clip the first word(s) the user says.
                    // O(1) append/overwrite — the old ArrayList<Float> did removeAt(0)
                    // per sample (an O(n) arraycopy + a box per sample, every read).
                    val preBuf = FloatRing(sr / 4)
                    val segStart = android.os.SystemClock.uptimeMillis()
                    seg.clear()
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
                        // Pre-roll ONLY the frames BEFORE the VAD fires. If we add the
                        // speech-detected batch here AND again in the seg-accumulator below,
                        // the utterance START is double-counted -> a short word like "Hello"
                        // repeats at the front and whisper-base hallucinates "Hello Hello ... 16x".
                        if (!inSpeech && !spoke) {
                            // roll a pre-roll (recent ~250ms) so we don't clip the utterance start
                            preBuf.add(frames)
                        }
                        // #17: time-slice silence from the ACTUAL frame duration (n / sr),
                        // not a fixed 64ms. A short read (a partial buffer) would make
                        // silence accrue faster than wall-clock and cut the user off
                        // mid-sentence when the pause threshold fired early.
                        if (spoke) { inSpeech = true; silentMs = 0 } else if (inSpeech) silentMs += n * 1000 / sr
                        if (inSpeech) {
                            if (seg.isEmpty() && preBuf.size > 0) preBuf.drainInto(seg)
                            for (f in frames) seg.add(f)
                        }
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
                                val sm = silentMs.toLong()   // immutable snapshot: don't read the mutable silentMs in the lambda
                                exec.execute partial@{
                                    try {
                                        val t = runCatching { stt?.transcribe(snap, sr) }.getOrNull()
                                        if (t.isNullOrBlank()) return@partial
                                        if (voiceState.mayStart(t, sm, now)) {
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
                    if (text != null) LatencyStats.pushStt(android.os.SystemClock.uptimeMillis() - segStart)   // #40 speech->text
                    val t = (earlyStartText ?: text ?: "").trim()
                    VoxLog.d("realtime: speech=$inSpeech ms=${seg.size * 1000 / sr} early=${earlyStartText != null} text=${if (logTranscripts()) t.take(120) else "<hidden>"}")
                    if (t.isBlank()) continue                           // noise / no-speech -> keep listening
                    // Reject non-speech (static/buzzing/[SOUND]) + too-short fragments.
                    if (t.length < 3 || t.startsWith("[") || t.startsWith("(")) continue
                    // HARD SPEAK-GATE: block until the reply AND its speech finish.
                    turnDone = java.util.concurrent.CountDownLatch(1)
                    val latch = turnDone
                    turnGen++
                    val myGen = turnGen
                    main.post { runStreamedTurn(t, myGen) }
                    val gateReleased = try { latch.await(TURN_GATE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Throwable) { false }
                    if (!gateReleased) VoxLog.w("event=gate-timeout await=turn-gate gen=$myGen after=${TURN_GATE_TIMEOUT_MS}ms still-locked speaking=$speaking streamed=$streamed sRunning=$sRunning bargeInArmed=$bargeInArmed turnInFlight=$turnInFlight")
                    // Post-turn cooldown + mic drain: don't re-capture the utterance we just sent.
                    try { r.stop() } catch (_: Throwable) {}
                    android.os.SystemClock.sleep(450L)
                    // Walkie (PTT): one turn, then stop listening until the user
                    // pushes-to-talk again. Realtime keeps going (hands-free).
                    if (!continuous) { listening = false; break }
                }
                loopActive = false
                // #19: the walkie no-repeat exit (and the stop path) never calls
                // stop(), so release the native AudioRecord here — otherwise the
                // next start() overwrites `record` and leaks one audio session per
                // PTT press until AudioRecord construction starts failing.
                try { record?.stop(); record?.release() } catch (_: Throwable) {}
                record = null
                main.post { listener?.onState("idle") }
            }
            true
        } catch (e: Throwable) {
            VoxLog.e("offline listen: ${e.message}")
            return listen()   // degrade to platform STT
        }
    }

    /** Walkie PTT release: commit the current utterance now (process the buffer). */
    fun commitUtterance() { commitRequested = true }

    fun stop() {
        listening = false
        recognizer?.destroy(); recognizer = null
        stopTts()
        stopStreaming()
        stopBargeInWatch()
        stopGenerationWatch()
        // #8: wait (bounded) for the capture loop + partial worker to exit their
        // native calls (vad.feed / stt.transcribe / record.read) before releasing
        // the native handles -> no use-after-free on a background/teardown race.
        val drainStart = android.os.SystemClock.uptimeMillis()
        var waited = 0L
        while ((loopActive || partialRunning) && waited < 500L) {
            android.os.SystemClock.sleep(20L); waited = android.os.SystemClock.uptimeMillis() - drainStart
        }
        try { record?.stop(); record?.release() } catch (_: Exception) {}
        try { bargeRecord?.stop(); bargeRecord?.release() } catch (_: Exception) {}
        record = null; bargeRecord = null
        try { ns?.release() } catch (_: Exception) {}; ns = null
        currentStream?.let { try { session.cancelStream(it) } catch (_: Exception) {} }
        currentStream = null
        tts?.shutdown()
        stt?.shutdown(); vad?.shutdown()
        // After shutting the legs down the pipeline is no longer warm: reset the ready
        // flags so isWarm() reflects reality (a stale true left transcribe returning null).
        ttsReady = false
        sttReady = false
        voiceState.reset()
        // #19: a stopped controller is terminal — shut the executor down
        // (bounded/graceful) so its threads aren't leaked. Reuse-after-stop is
        // forbidden: the activity nulls its reference so a dead executor is never
        // re-armed.
        exec.shutdown()
    }

    /** Idempotent re-init: for each leg whose ready flag is false, re-call its init so
     *  start() can warm the pipeline again after a stop(). Guarded so overlapping
     *  start() calls never double-init a leg. */
    private fun ensureWarm() {
        if (initializing) return
        initializing = true
        try {
            val t = tts; if (t != null && !ttsReady) t.init { ttsReady = it }
            val s = stt; if (s != null && !sttReady) s.init { sttReady = it }
            val v = vad; if (v != null && !v.isAvailable) v.init {}
        } finally { initializing = false }
    }

    /** A text turn (the Send path / the Realtime text-mode fallback). */
    fun sendText(text: String) {
        if (text.isBlank()) return
        stopTts()
        runStreamedTurn(text, turnGen)
    }

    private fun initTts(): Boolean {
        tts?.init { ttsReady = it }
        return true
    }

    // --- Listening (on-device STT; guarded for a headless emulator / missing service) ---
    private fun listen(): Boolean {
        stopTts()
        listening = true
        listener?.onState("listening")
        recognizer?.destroy()
        return try {
            val r = SpeechRecognizer.createSpeechRecognizer(context)
            r.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: Bundle) {}
                override fun onEvent(t: Int, p: Bundle?) {}
                override fun onResults(p: Bundle) {
                    val text = p.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                    if (text.isNotBlank()) runStreamedTurn(text, turnGen) else if (listening) listen()
                }
                override fun onError(e: Int) {
                    if (e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || e == SpeechRecognizer.ERROR_NO_MATCH) {
                        if (listening) listen()
                    } else {
                        listening = false
                        listener?.onError("speech unavailable ($e)")
                        listener?.onState("idle")
                    }
                }
            })
            recognizer = r
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            r.startListening(intent)
            listener?.onLog("// hear → listening")
            true
        } catch (e: Throwable) {
            listening = false
            listener?.onError("speech unavailable: ${e.message}")
            listener?.onState("idle")
            false
        }
    }

    // --- The streamed entity turn (REAL SSE) ---
    private fun runStreamedTurn(text: String, gen: Long) {
        if (turnInFlight) { VoxLog.d("turn suppressed (in flight)"); releaseTurnGate(turnGen, "suppressed-inflight"); return }
        turnInFlight = true
        voiceState.arm()        // #60: re-arm exactly-once for this turn (via VoiceLoopState)
        firstAudioLatch = false   // #40: per-turn first-audio latch
        genCancelled = false
        // D2: arm the generation-phase mic watch up front so a voice input interrupts
        // the agent while it is STILL GENERATING (thinking / tool-calls / first-token
        // latency) — not just during playback. It stops itself once speech begins.
        if (bargeInEnabled) armGenerationWatch()
        val t0 = android.os.SystemClock.uptimeMillis()
        listener?.onState("thinking")
        listener?.onLog(if (logTranscripts()) "// you → $text" else "// (you spoke)")
        if (shouldSpeak() && tts?.supportsStreaming == true) streamBegin()
        exec.execute {
            try {
                val sid = session.startStream(text)
                VoxLog.d("startStream -> $sid")
                currentStream = sid
                val firstByteAt = android.os.SystemClock.uptimeMillis()
                var firstByteDone = false
                var done = false
                var tries = 0
                while (!done && tries < 600) {
                    // D2: a barge-in during generation (before startStream returned) sets
                    // genCancelled; break so the turn's worker retires without settling a
                    // ghost reply (the gate was already released by bargeIn()).
                    if (genCancelled) break
                    // #39: wake on NEW data instead of sleeping a fixed 240ms tick. The Go
                    // side (WaitStream) signals the app the instant an SSE event lands, so
                    // deltas render as they arrive; the 100ms budget is only the idle floor
                    // (no data yet -> check again). End-of-stream completion wakes too, so
                    // `done` is observed promptly and never padded by a poll-tick.
                    try { session.waitStream(sid, 100) } catch (_: Exception) {} // gomobile raises on a retired stream
                    var payload: String? = null
                    try { payload = session.pollStreamJSON(sid) } catch (_: Exception) {} // gomobile raises on error
                    if (payload != null) {
                        if (!firstByteDone) { firstByteDone = true; LatencyStats.pushFirstByte(firstByteAt - t0) }   // #40
                        val obj = JSONObject(payload)
                        val evts = obj.optJSONArray("events")
                        VoxLog.d("poll ev=${evts?.length() ?: 0} done=${obj.optBoolean("done")} err=${obj.optString("error","").take(70)} textLen=${obj.optString("text","").length}")
                        emitEvents(evts, t0)
                        if (obj.optBoolean("done")) {
                            done = true
                            val err = obj.optString("error", "")
                            val finalText = obj.optString("text", "")
                            VoxLog.d("turn done: gen=" + gen + " resp=" + obj.optString("response_id", "") + " len=" + finalText.length + " err=" + err.take(40))
                            if (err.isNotBlank()) {
                                stopStreaming()   // abort: close the streaming worker + release the track
                                main.post {
                                    listener?.onError("hermes: $err")
                                    listener?.onState("idle")
                                    releaseTurnGate(gen, "reply-error")
                                }
                            } else if (finalText.isNotBlank()) {
                                LatencyStats.pushFullReply(android.os.SystemClock.uptimeMillis() - t0)   // #40 full reply
                                main.post { settleReply(finalText, gen) }
                            } else {
                                stopStreaming()   // empty reply: still close the streaming worker
                                main.post { listener?.onState("idle"); releaseTurnGate(gen, "empty-reply") }
                            }
                        }
                    }
                    if (done) break
                    tries++
                }
                if (genCancelled) { VoxLog.d("turn interrupted during generation") }
                else if (!done) throw Exception("timeout")
            } catch (e: Throwable) {
                stopStreaming()   // timeout/exception: close the streaming worker
                main.post {
                    listener?.onError("hermes: ${e.message}")
                    listener?.onState("idle")
                    releaseTurnGate(gen, "stream-error")
                }
            } finally {
                currentStream = null
                turnInFlight = false
                stopGenerationWatch()   // D2: always retire the generation watch with the turn
                // (speak-gate released in the speak-complete callback, not here)
            }
        }
    }

    private fun emitEvents(arr: JSONArray?, t0: Long) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            when (e.optString("type")) {
                "response.created" ->
                    main.post { listener?.onLog("// entity responding…") }
                "response.output_item.added" -> when (e.optString("item_type")) {
                    "function_call" -> {
                        val name = e.optString("name")
                        val args = e.optString("arguments")
                        main.post { listener?.onLog("◆ tool: $name ${args.take(80)}") }
                    }
                    "function_call_output" -> {
                        val out = e.optString("output").replace("\n", " ")
                        main.post { listener?.onLog("◆ tool · ${out.take(120)}") }
                    }
                }
                "response.output_text.delta" -> {
                    val d = e.optString("delta")
                    if (d.isNotBlank()) {
                        // #40: push first-audio once per turn (time from launch to first delta)
                        if (!firstAudioLatch) { firstAudioLatch = true; LatencyStats.pushFirstAudio(android.os.SystemClock.uptimeMillis() - t0) }
                        streamFeed(d)   // start speaking as it streams
                        main.post { listener?.onDelta(d); bumpSpeakLevel() }
                    }
                }
                "response.completed" -> main.post { listener?.onLog("// response completed") }
            }
        }
    }

    private fun settleReply(finalText: String, gen: Long) {
        listener?.onLog("// agent → ${finalText.take(120)}")
        listener?.onReply(finalText)
        stopGenerationWatch()   // D2: the generation watch yields once the reply is resolved
        if (streamed && (tts?.supportsStreaming == true)) {
            // STREAMED reply: the single canonical gate release. streamFinish() closes
            // the queue so the streaming worker drains + finishes; the gate releases
            // exactly once here, gated on the worker's completion (sDone). No other
            // release site runs for this reply (#60).
            streamFinish()
            exec.execute {
                val doneOk = try { sDone.await(120, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Throwable) { false }
                if (!doneOk) VoxLog.w("event=gate-timeout await=stream-worker gen=$gen after=120s sRunning=$sRunning streamed=$streamed")
                releaseTurnGate(gen, if (doneOk) "stream-done" else "stream-done-timeout")
            }
        } else if (shouldSpeak()) {
            speak(finalText, gen)
        } else {
            stopStreaming(); listener?.onState("idle"); releaseTurnGate(gen, "text-only")
        }
    }

    // ---- Streaming TTS engine ----
    @Volatile private var streamed = false
    private fun streamBegin() {
        streamed = false   // clear before arming a new streaming turn
        if (sRunning && sClosed) sRunning = false   // re-arm a drained worker (stuck sRunning) so this new turn can start
        synchronized(sLock) { sAccum.setLength(0); sQueue.clear(); sClosed = false }
        lastStreamFlush = android.os.SystemClock.uptimeMillis()   // arm the #44 timer for THIS turn
        streamed = true
        sDone = java.util.concurrent.CountDownLatch(1)
        try { (tts as? SherpaTts)?.startStreaming() } catch (_: Throwable) {}
        if (!sRunning) {
            sRunning = true
            exec.execute {
                try {
                    while (true) {
                        val chunk = synchronized(sLock) {
                            var c = sQueue.poll()
                            while (c == null && !sClosed) { try { sLock.wait(200) } catch (_: Throwable) {}; c = sQueue.poll() }
                            c
                        }
                        if (chunk == null) break            // closed + drained
                        // Play EVERY chunk: gate on the engine, NOT on !speaking. (The earlier
                        // keep-speaking-true change made speaking stuck true so only the first
                        // chunk played and the rest was drained silently -> "small chunks".)
                        if (tts?.supportsStreaming == true) {
                            speaking = true
                            stopGenerationWatch()   // D2: playback began -> generation watch yields
                            // Arm barge-in (user can interrupt the streamed reply) once.
                            if (bargeInEnabled && !bargeInArmed) {
                                main.postDelayed({ if (speaking && !bargeInArmed) startBargeInWatch() }, 700L)
                            }
                            try { (tts as? SherpaTts)?.streamChunk(chunk) } catch (_: Throwable) {}
                            // speaking stays true for the whole reply (barge-in armed, UI state)
                        }
                    }
                } finally {
                    speaking = false
                    stopBargeInWatch()
                    synchronized(sLock) { if (sQueue.isEmpty()) {} }
                    try { (tts as? SherpaTts)?.finishStreaming() } catch (_: Throwable) {}
                    sRunning = false
                }
                sDone.countDown()
            }
        }
    }
    private fun streamFeed(delta: String) {
        if (delta.isBlank()) return
        if (!streamed || sClosed) return   // not active, or stream closed
        if (tts?.supportsStreaming != true) return   // non-streaming TTS -> full-text fallback, no queue
        synchronized(sLock) {
            val now = android.os.SystemClock.uptimeMillis()
            sAccum.append(delta)
            // Slice from the LIVE accumulator, not a snapshot: after the first sentence
            // our boundary index is relative to the shortened sAccum, so slicing the
            // snapshot re-extracted the start (duplicate sentences -> garbage chunks).
            var b = indexOfSentenceEnd(sAccum.toString())
            var emitted = false
            while (b >= 0) {
                val sent = sAccum.substring(0, b + 1).trim()
                sAccum.replace(0, b + 1, "")
                if (sent.isNotBlank()) { sQueue.add(sent); emitted = true }
                b = indexOfSentenceEnd(sAccum.toString())
            }
            // #44: a punctuation-light open phrase that hasn't hit a terminator in the
            // flush window is emitted as a short partial so the first audio (and every
            // pause in a ramble) plays sooner, not at the next sentence terminator.
            // Cut at the LAST word break so we never synthesize a half word.
            if (!emitted && now - lastStreamFlush >= STREAM_CHUNK_FLUSH_MS) {
                val cut = sAccum.toString().trimEnd()
                val lastSpace = cut.lastIndexOf(' ') + 1   // offset after the final word break
                if (lastSpace > 0 && lastSpace < cut.length) {
                    val rest = cut.substring(0, lastSpace).trim()
                    if (rest.isNotBlank()) {
                        sQueue.add(rest)
                        sAccum.setLength(0); sAccum.append(cut.substring(lastSpace))
                        emitted = true
                    }
                }
            }
            if (emitted) lastStreamFlush = now
            sLock.notifyAll()
        }
    }
    private fun streamFinish() {
        streamed = false
        synchronized(sLock) {
            val rest = sAccum.toString().trim()
            if (rest.isNotBlank()) sQueue.add(rest)
            sAccum.setLength(0)
            sClosed = true
            sLock.notifyAll()
        }
    }
    private fun speakingEnabledByUser() = speakEnabled()
    private fun stopStreaming() {
        streamed = false
        synchronized(sLock) { sQueue.clear(); sClosed = true; sAccum.setLength(0); sLock.notifyAll() }
    }

    private fun bumpSpeakLevel() {
        // The avatar's speaking level follows real deltas; armed briefly for the voice animation.
        speakerPulse = 1f
        main.postDelayed({ speakerPulse = 0.5f }, 220)
    }
    @Volatile private var speakerPulse = 0f

    @Volatile private var glueSpeaking = false
    // Streaming TTS: speak the reply as it streams (sync with the crawl), not after the
    // whole response lands. A worker plays sentence-chunks sequentially.
    private val sAccum = StringBuilder()
    private val sQueue = java.util.ArrayDeque<String>()
    private val sLock = Object()
    @Volatile private var sClosed = false
    private var sDone = java.util.concurrent.CountDownLatch(1)
    @Volatile private var sRunning = false
    // #44: time-based partial-audio flush. `lastStreamFlush` is the wall-clock of
    // the last chunk emitted; an open phrase that hasn't hit a boundary (and so
    // hasn't been chunked) within STREAM_CHUNK_FLUSH_MS is cut at the last word
    // break and emitted so audio tracks the text rather than the terminator.
    private var lastStreamFlush = 0L

    /** Speak low-priority Gemma "phone-call glue" (acknowledgment/narration).
     *  Preempted by the authoritative Hermes reply (see speak). If the controller
     *  isn't in a reply, this just voices the presence glue. */
    fun speakGlue(text: String) {
        if (text.isBlank()) return
        if (!shouldSpeak()) return   // voice channel closed (or voice toggle off)
        if (speaking) return          // the authoritative reply has precedence — never talk over it
        // One voice at a time: stop any in-flight glue before the new one so we never
        // get two concurrent TTS (the double-voice bug). Latest narration wins.
        stopTts()
        glueSpeaking = true
        main.post {
            tts?.speak(text) { glueSpeaking = false }
        }
    }

    private fun speak(text: String, gen: Long) {
        val t = tts
        if (t == null || !ttsReady) {
            // No usable engine — never hang the "speaking" state; settle quietly.
            listener?.onState("idle")
            releaseTurnGate(gen, "no-engine")   // no speech will run -> release the loop's speak-gate
            return
        }
        glueSpeaking = false      // Hermes preempts Gemma
        stopTts()                 // cut any in-flight glue so the reply isn't truncated
        speaking = true
        stopGenerationWatch()   // D2: playback begins -> generation watch yields
        listener?.onState("speaking")
        t.speak(text) {
            speaking = false
            // #28: the TTS completion callback runs on the engine thread; the
            // listener must be dispatched on the main thread per the contract.
            main.post {
                listener?.onState("idle")
                if (bargeInArmed) stopBargeInWatch()
                releaseTurnGate(gen, "speak-done")   // release the realtime loop's speak-gate after speech finishes
            }
        }
        // Arm barge-in AFTER a short delay + at a higher threshold so the mic
        // doesn't cancel the TTS on its own output (the "hears itself" cut).
        if (bargeInEnabled) main.postDelayed({ if (speaking) startBargeInWatch() }, 700L)
    }

    // --- Barge-in: watch the mic while speaking; cut + cancel (Silero VAD, else RMS) ---
    private fun startBargeInWatch() {
        bargeInArmed = true
        val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return
        try {
            val ecSession = (tts as? SherpaTts)?.playbackSession ?: 0
            bargeRecord = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION).setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(minBuf * 2).build()
            bargeAec?.release()
            bargeAec = if (android.media.audiofx.AcousticEchoCanceler.isAvailable() && ecSession != 0) android.media.audiofx.AcousticEchoCanceler.create(ecSession)?.also { it.enabled = true } else null
            val r = bargeRecord ?: return
            if (r.state != AudioRecord.STATE_INITIALIZED) return
            r.startRecording()
            exec.execute {
                val buf = ShortArray(minBuf)
                while (bargeInArmed && r.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val n = r.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var rms = 0.0
                    val frames = FloatArray(n)
                    for (i in 0 until n) { frames[i] = buf[i] / 32768f; rms += buf[i].toDouble() * buf[i] }
                    val spoke = (Math.sqrt(rms / n) / Short.MAX_VALUE > 0.15f)
                    if (spoke) { main.post { bargeIn() }; break }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun bargeIn() {
        speaking = false
        bargeInArmed = false
        stopBargeInWatch()
        stopGenerationWatch()
        genCancelled = true
        stopTts()
        stopStreaming()   // stop the streaming worker: exit + release the track cleanly
        currentStream?.let { try { session.cancelStream(it) } catch (_: Exception) {} }
        currentStream = null
        listener?.onLog("// (interrupted)")
        listener?.onState("listening")
        releaseTurnGate(turnGen, "barge-in")   // a barge-in aborts the reply -> release the loop's speak-gate
    }

    // --- Generation-phase barge-in (D2): interrupt while the agent is STILL ---
    // generating, not just during playback. A separate, AEC-less recorder watches
    // the mic for the pre-audio window (thinking / tool-calls / first-token). It
    // never hears its own reply because it can only be armed while NOT speaking
    // and stops the instant `speaking` flips (the AEC'd playback barge-in then
    // takes over). The main capture record is already stopped for the turn, so
    // there is no double-recorder conflict.
    private fun armGenerationWatch() {
        if (!bargeInEnabled || genWatchArmed || speaking) return
        val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return
        genWatchArmed = true
        try {
            val r = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(minBuf * 2).build()
            genRecord = r
            if (r.state != AudioRecord.STATE_INITIALIZED) { genWatchArmed = false; genRecord = null; return }
            r.startRecording()
            exec.execute {
                val buf = ShortArray(minBuf)
                try {
                    while (genWatchArmed && !speaking && turnInFlight) {
                        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) break
                        val n = r.read(buf, 0, buf.size); if (n <= 0) continue
                        var rms = 0.0
                        for (i in 0 until n) rms += buf[i].toDouble() * buf[i]
                        val spoke = Math.sqrt(rms / n) / Short.MAX_VALUE > 0.15f
                        if (spoke) { main.post { if (turnInFlight && !speaking) bargeIn() }; break }
                    }
                } catch (_: Throwable) {}   // raced a stop() release on the record -> exit cleanly
            }
        } catch (_: Throwable) { genWatchArmed = false }
    }

    private fun stopGenerationWatch() {
        genWatchArmed = false
        try { genRecord?.stop(); genRecord?.release() } catch (_: Exception) {}
        genRecord = null
    }

    private fun releaseTurnGate(gen: Long, reason: String) {
        if (gen != turnGen) { VoxLog.w("event=gate-release result=stale gen=$gen current=$turnGen reason=$reason"); return }
        if (!voiceState.release()) { VoxLog.w("event=gate-release result=duplicate gen=$gen reason=$reason"); return }
        try { turnDone.countDown() } catch (_: Throwable) {}
        VoxLog.d("event=gate-release gen=$gen reason=$reason")          // (audit's epoch= dropped — private)
        LatencyStats.log("turn", reason, gen)
    }

    private fun stopBargeInWatch() {
        bargeInArmed = false
        bargeAec?.release(); bargeAec = null
        try { bargeRecord?.stop(); bargeRecord?.release() } catch (_: Exception) {}
        bargeRecord = null
    }

    private fun stopTts() { try { tts?.stop() } catch (_: Exception) {} }

    /** Release the realtime loop's speak-gate (idempotent). Called from EVERY
     *  settle/error/barge-in path so the loop NEVER deadlocks waiting for a
     *  speech-complete callback that didn't run (stream error, speak disabled,
     *  or no usable TTS). Without this the first failed/reply-less turn hangs
     *  the loop forever — the "one-turn-then-stops" symptom. */

    /** User "hush": stop the current reply + cancel the stream; the half-duplex
     *  loop is released back to listening. Bound to the presence tap (tap = STOP). */
    fun hush() {
        speaking = false
        bargeInArmed = false
        stopBargeInWatch()
        stopGenerationWatch()
        stopTts()
        currentStream?.let { try { session.cancelStream(it) } catch (_: Exception) {} }
        currentStream = null
        stopStreaming()
        releaseTurnGate(turnGen, "hush")
        listener?.onLog("// (stopped)")
        if (listening) listener?.onState("listening")
    }

    private fun speakEnabled() = context.getSharedPreferences("hv", android.content.Context.MODE_PRIVATE).getBoolean("speak_responses", true)

    /** WS4a: open/close the voice channel. The host drives it: a call / live voice
     *  line opens it (true); no call closes it (false) so replies are text-only. */
    fun setVoiceChannelOpen(open: Boolean) { voiceChannelOpen = open }
    private fun shouldSpeak(): Boolean = voiceChannelOpen && speakEnabled()


    fun testConnection(): String {
        val u = prefString("url", ""); val k = prefString("key", "")
        if (u.isBlank()) return "no endpoint set"
        var out = "endpoint=" + u
        try {
            val c = java.net.URL(u.trimEnd('/') + "/v1/models").openConnection() as java.net.HttpURLConnection
            c.requestMethod = "GET"; c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("Authorization", "Bearer " + k)
            out += "\nping -> " + c.responseCode
        } catch (e: Throwable) { out += "\nping error: " + e.message }
        try {
            val c = java.net.URL(u.trimEnd('/') + "/v1/responses").openConnection() as java.net.HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 8000; c.readTimeout = 8000; c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer " + k)
            c.setRequestProperty("Content-Type", "application/json")
            val payload = "{\"model\":\"\",\"input\":\"hello\",\"stream\":true}"
            c.outputStream.use { it.write(payload.toByteArray()) }
            val code = c.responseCode
            val body = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
            out += "\nstream -> " + code + " " + body.take(200)
        } catch (e: Throwable) { out += "\nstream error: " + e.message }
        VoxLog.d("conn-test: " + out)
        return out
    }

    /** Plain-language connection test (WS3): success/failure copy + the raw reason
     *  in small debug text. Same entity url + bearer key as testConnection(). */
    fun testConnectionHuman(): String {
        val u = prefString("url", ""); val k = prefString("key", "")
        if (u.isBlank()) return "Couldn't reach the gateway. Check that your network is on and the address is right.\n\n(no endpoint set)"
        var ping = true; var pingRe = ""
        try {
            val c = java.net.URL(u.trimEnd('/') + "/v1/models").openConnection() as java.net.HttpURLConnection
            c.requestMethod = "GET"; c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("Authorization", "Bearer " + k)
            if (c.responseCode != 200) { ping = false; pingRe = "HTTP ${c.responseCode}" }
        } catch (e: Throwable) { ping = false; pingRe = e.message ?: "unknown" }
        var stream = true; var streamRe = ""
        try {
            val c = java.net.URL(u.trimEnd('/') + "/v1/responses").openConnection() as java.net.HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 8000; c.readTimeout = 8000; c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer " + k)
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write("{\"model\":\"\",\"input\":\"hello\",\"stream\":true}".toByteArray()) }
            val code = c.responseCode
            if (code < 200 || code >= 400) { stream = false; streamRe = "HTTP $code" }
        } catch (e: Throwable) { stream = false; streamRe = e.message ?: "unknown" }
        VoxLog.d("conn-test: ping=$ping($pingRe) stream=$stream($streamRe)")
        val ok = ping && stream
        if (ok) return "Connected to your agent. Everything's working."
        val debug = listOfNotNull(if (!ping) "ping: $pingRe" else null, if (!stream) "stream: $streamRe" else null).joinToString(" · ")
        return "Couldn't reach the gateway. Check that your network is on and the address is right.\n\n(debug: $debug)"
    }

    /** Settings "Log spoken transcript" (default OFF): when false, the user's words are
     *  NOT written to the runtime log or dev console (a genuine privacy backstop). */
    private fun logTranscripts(): Boolean =
        context.getSharedPreferences("hv", android.content.Context.MODE_PRIVATE).getBoolean("log_transcripts", false)

    private fun micInt(k: String, d: Int): Int =
        context.getSharedPreferences("hv", android.content.Context.MODE_PRIVATE).getInt(k, d)
    private fun micFloat(k: String, d: Float): Float =
        context.getSharedPreferences("hv", android.content.Context.MODE_PRIVATE).getFloat(k, d)
    private fun micBool(k: String, d: Boolean): Boolean =
        context.getSharedPreferences("hv", android.content.Context.MODE_PRIVATE).getBoolean(k, d)

    private fun prefString(k: String, d: String) =
        context.getSharedPreferences("hv", Context.MODE_PRIVATE).getString(k, d) ?: d

    companion object { const val RMS_THRESHOLD = 0.09f }
}

/** #30/#44: chunk-seam helper (pure, unit-testable). Returns the index of the
 *  next text boundary to split a streaming-TTS chunk at, or -1. '!' / '?' /
 *  newline are always boundaries; '.' only when it genuinely ends a sentence —
 *  followed by end-of-text or whitespace + a letter, and NOT an abbreviation
 *  ("Dr.", "Mr.", "St.") — so "71.5%" and "Dr. Chen" never split into an
 *  audible seam. A comma/colon/semicolon FOLLOWED BY whitespace is a SOFT seam
 *  (#44) so a punctuation-light reply yields partial audio sooner, while
 *  "1,000" / "a:b" stay whole (no whitespace follows the punctuation). */
internal fun indexOfSentenceEnd(s: String): Int {
    for (i in s.indices) {
        when (s[i]) {
            '!', '?', '\n' -> return i
            '.' -> if (isSentenceDot(s, i)) return i
            ',', ';', ':' -> if (i + 1 < s.length && s[i + 1].isWhitespace()) return i
        }
    }
    return -1
}

private fun isSentenceDot(s: String, i: Int): Boolean {
    val j = i + 1
    if (j >= s.length) return true                // "Hello." at the very end -> end
    if (!s[j].isWhitespace()) return false        // "71.5%" -> '.' then a digit, no space
    var k = j
    while (k < s.length && s[k].isWhitespace()) k++
    if (k >= s.length) return true                // "Hello.   " trailing edge -> end
    if (!s[k].isLetter()) return false            // '.' + space + punctuation -> not a seam
    // Only treat as a sentence end if the token before '.' isn't a common
    // abbreviation (title/initial): "Dr. Chen", "Mr. Smith", "St. Louis".
    var w = i - 1
    while (w >= 0 && s[w].isLetterOrDigit()) w--
    return s.substring(w + 1, i).lowercase() !in STREAM_CHUNK_ABBREVIATIONS
}

private val STREAM_CHUNK_ABBREVIATIONS = setOf(
    "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc",
    "approx", "dept", "inc", "ltd", "co", "ave", "blvd", "mt"
)

/** #44: an open streaming phrase with no boundary emits as a partial chunk once
 *  this window elapses (so time-to-first-audio isn't gated on a terminator). */
private const val STREAM_CHUNK_FLUSH_MS = 500L

/** #18: a bounded FIFO ring of float samples — an O(1) append/overwrite sliding
 *  window used for the ~250ms capture pre-roll. The old `ArrayList<Float>` did
 *  add() + removeAt(0) per sample (an O(n) arraycopy under the loop + a Float box
 *  per sample, every read). Fixed capacity = the pre-roll window (sr/4 @16kHz).
 *  Pure JVM — unit-testable, no Android deps. */
internal class FloatRing(capacity: Int) {
    private val buf = FloatArray(capacity.coerceAtLeast(1))
    private var head = 0
    private var count = 0

    /** Current number of samples buffered (<= capacity). */
    val size: Int get() = count

    fun add(batch: FloatArray) {
        for (v in batch) {
            if (count == buf.size) {
                buf[head] = v                      // ring full: overwrite the oldest
                head = (head + 1) % buf.size
            } else {
                buf[(head + count) % buf.size] = v
                count++
            }
        }
    }

    /** Append the buffered samples in order to dst, then reset (drain-once). */
    fun drainInto(dst: MutableList<Float>) {
        for (i in 0 until count) dst.add(buf[(head + i) % buf.size])
        head = 0
        count = 0
    }

    fun clear() {
        head = 0
        count = 0
    }
}
