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
import kotlin.concurrent.thread

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
    private var stt: OfflineWhisperStt? = null
    private var vad: SileroVadGate? = null
    private var record: AudioRecord? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var speaking = false
    @Volatile private var bargeInArmed = false
    @Volatile private var currentStream: String? = null
    @Volatile private var listening = false
    private var listener: Listener? = null
    private var bargeInEnabled = true
    @Volatile private var ttsReady = false
    @Volatile private var sttReady = false

    init {
        tts = buildTts(context, prefString("tts", "system"))
        // Load the TTS engine up front so a reply is always voiced (text OR mic).
        tts?.init { ttsReady = it }
        // On-device Whisper STT + Silero VAD when their blessed models are installed.
        if (ModelCatalog.isInstalled(context, "whisper-tiny")) {
            stt = OfflineWhisperStt(context); stt?.init { sttReady = it }
        }
        if (ModelCatalog.isInstalled(context, "silero-vad")) {
            vad = SileroVadGate(context); vad?.init {}
        }
        VoxLog.d("pipeline: sttReady=$sttReady vad=${vad?.isAvailable}")
    }

    /** Set/replace the render callbacks (works for text turns too — the send path). */
    fun attachListeners(l: Listener) { listener = l }

    /** Begins the listening loop. Returns true when STT is actually running. */
    fun start(l: Listener, enabled: Boolean): Boolean {
        listener = l; bargeInEnabled = enabled
        return if (sttReady) listenOffline() else listen()   // on-device Whisper, else platform STT
    }

    // --- On-device Whisper loop: capture mic -> VAD/silence -> transcribe -> turn ---
    private fun listenOffline(): Boolean {
        listener?.onState("listening"); listening = true
        val sr = 16000
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return listen()
        return try {
            val r = AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf)
            if (r.state != AudioRecord.STATE_INITIALIZED) return listen()
            record = r
            r.startRecording()
            listener?.onLog("// hear → listening (offline whisper)")
            thread {
                val shortBuf = ShortArray(1024)
                val collected = ArrayList<Float>(sr)  // ~1s headroom, grows
                var speechStarted = false; var silentMs = 0; val startMs = android.os.SystemClock.uptimeMillis()
                while (listening && collected.size < sr * 15) {  // max 15s
                    val n = r.read(shortBuf, 0, shortBuf.size)
                    if (n <= 0) continue
                    val frames = FloatArray(n); var rms = 0.0
                    for (i in 0 until n) { frames[i] = shortBuf[i] / 32768f; rms += shortBuf[i].toDouble() * shortBuf[i] }
                    val spoke = if (vad != null) vad!!.feed(frames) else (Math.sqrt(rms / n) / Short.MAX_VALUE > RMS_THRESHOLD)
                    if (spoke) { speechStarted = true; silentMs = 0 } else if (speechStarted) { silentMs += 64; if (silentMs > 800) break }
                    if (speechStarted) { for (f in frames) collected.add(f) }
                    if (android.os.SystemClock.uptimeMillis() - startMs > 15000) break
                }
                r.stop(); r.release()
                if (!speechStarted || collected.size < sr / 4) { if (listening) main.post { if (sttReady) listenOffline() else listen() }; return@thread }
                val text = stt?.transcribe(collected.toFloatArray(), sr)
                if (!text.isNullOrBlank()) runStreamedTurn(text) else if (listening) main.post { listenOffline() }
            }
            true
        } catch (e: Throwable) {
            VoxLog.e("offline listen: ${e.message}")
            return listen()   // degrade to platform STT
        }
    }

    fun stop() {
        listening = false
        recognizer?.destroy(); recognizer = null
        stopTts()
        stopBargeInWatch()
        try { record?.stop(); record?.release() } catch (_: Exception) {}
        record = null
        currentStream?.let { try { session.cancelStream(it) } catch (_: Exception) {} }
        currentStream = null
        tts?.shutdown()
        stt?.shutdown(); vad?.shutdown()
    }

    /** A text turn (the Send path / the Realtime text-mode fallback). */
    fun sendText(text: String) {
        if (text.isBlank()) return
        stopTts()
        runStreamedTurn(text)
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
                    if (text.isNotBlank()) runStreamedTurn(text) else if (listening) listen()
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
    private fun runStreamedTurn(text: String) {
        listener?.onState("thinking")
        listener?.onLog("// you → $text")
        thread {
            try {
                val sid = session.startStream(text)
                VoxLog.d("startStream -> $sid")
                currentStream = sid
                var done = false
                var tries = 0
                while (!done && tries < 600) {
                    var payload: String? = null
                    try { payload = session.pollStreamJSON(sid) } catch (_: Exception) {} // gomobile raises on error
                    if (payload != null) {
                        val obj = JSONObject(payload)
                        val evts = obj.optJSONArray("events")
                        VoxLog.d("poll ev=${evts?.length() ?: 0} done=${obj.optBoolean("done")} err=${obj.optString("error","").take(70)} textLen=${obj.optString("text","").length}")
                        emitEvents(evts)
                        if (obj.optBoolean("done")) {
                            done = true
                            val err = obj.optString("error", "")
                            val finalText = obj.optString("text", "")
                            if (err.isNotBlank()) {
                                main.post {
                                    listener?.onError("hermes: $err")
                                    listener?.onState("idle")
                                }
                            } else if (finalText.isNotBlank()) {
                                main.post { settleReply(finalText) }
                            } else {
                                main.post { listener?.onState("idle") }
                            }
                        }
                    }
                    if (done) break
                    Thread.sleep(240)
                    tries++
                }
                if (!done) throw Exception("timeout")
            } catch (e: Throwable) {
                main.post {
                    listener?.onError("hermes: ${e.message}")
                    listener?.onState("idle")
                }
            } finally {
                currentStream = null
            }
        }
    }

    private fun emitEvents(arr: JSONArray?) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            when (e.optString("type")) {
                "response.created" ->
                    listener?.onLog("// entity responding…")
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
                    if (d.isNotBlank()) main.post {
                        listener?.onDelta(d)
                        bumpSpeakLevel()
                    }
                }
                "response.completed" -> main.post { listener?.onLog("// response completed") }
            }
        }
    }

    private fun settleReply(finalText: String) {
        listener?.onLog("// agent → ${finalText.take(120)}")
        listener?.onReply(finalText)
        speak(finalText)
    }

    private fun bumpSpeakLevel() {
        // The avatar's speaking level follows real deltas; armed briefly for the voice animation.
        speakerPulse = 1f
        main.postDelayed({ speakerPulse = 0.5f }, 220)
    }
    @Volatile private var speakerPulse = 0f

    private fun speak(text: String) {
        val t = tts
        if (t == null || !ttsReady) {
            // No usable engine — never hang the "speaking" state; settle quietly.
            listener?.onState("idle")
            return
        }
        speaking = true
        listener?.onState("speaking")
        t.speak(text) {
            speaking = false
            listener?.onState("idle")
            if (bargeInArmed) stopBargeInWatch()
            if (listening) listen()
        }
        if (bargeInEnabled) startBargeInWatch()
    }

    // --- Barge-in: watch the mic while speaking; cut + cancel (Silero VAD, else RMS) ---
    private fun startBargeInWatch() {
        bargeInArmed = true
        val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return
        try {
            record = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
            val r = record ?: return
            if (r.state != AudioRecord.STATE_INITIALIZED) return
            r.startRecording()
            thread {
                val buf = ShortArray(minBuf)
                while (bargeInArmed && r.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val n = r.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var rms = 0.0
                    val frames = FloatArray(n)
                    for (i in 0 until n) { frames[i] = buf[i] / 32768f; rms += buf[i].toDouble() * buf[i] }
                    val spoke = if (vad != null) vad!!.feed(frames) else (Math.sqrt(rms / n) / Short.MAX_VALUE > RMS_THRESHOLD)
                    if (spoke) { main.post { bargeIn() }; break }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun bargeIn() {
        speaking = false
        bargeInArmed = false
        stopBargeInWatch()
        stopTts()
        currentStream?.let { try { session.cancelStream(it) } catch (_: Exception) {} }
        currentStream = null
        listener?.onLog("// (interrupted)")
        listener?.onState("listening")
        if (listening) listen()
    }

    private fun stopBargeInWatch() {
        bargeInArmed = false
        try { record?.stop(); record?.release() } catch (_: Exception) {}
        record = null
    }

    private fun stopTts() { try { tts?.stop() } catch (_: Exception) {} }

    private fun prefString(k: String, d: String) =
        context.getSharedPreferences("hv", Context.MODE_PRIVATE).getString(k, d) ?: d

    companion object { const val RMS_THRESHOLD = 0.02 }
}
