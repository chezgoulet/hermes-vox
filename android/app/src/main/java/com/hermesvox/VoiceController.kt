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
    private var record: AudioRecord? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var speaking = false
    @Volatile private var bargeInArmed = false
    @Volatile private var currentStream: String? = null
    @Volatile private var listening = false
    private var listener: Listener? = null
    private var bargeInEnabled = true
    @Volatile private var ttsReady = false

    init { tts = buildTts(context, prefString("tts", "system")) }

    /** Set/replace the render callbacks (works for text turns too — the send path). */
    fun attachListeners(l: Listener) { listener = l }

    /** Begins the listening loop. Returns true when STT is actually running. */
    fun start(l: Listener, enabled: Boolean): Boolean {
        listener = l; bargeInEnabled = enabled
        // Best-effort TTS init (never gates listening; speak() guards on ttsReady).
        tts?.init { ttsReady = it }
        return listen()
    }

    fun stop() {
        listening = false
        recognizer?.destroy(); recognizer = null
        stopTts()
        stopBargeInWatch()
        currentStream?.let { try { session.cancelStream(it) } catch (_: Exception) {} }
        currentStream = null
        tts?.shutdown()
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

    // --- Barge-in: watch the mic RMS while speaking; cut + cancel + re-listen ---
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
                    var sum = 0.0
                    for (i in 0 until n) sum += (buf[i] * buf[i]).toDouble()
                    val rms = Math.sqrt(sum / n) / Short.MAX_VALUE
                    if (rms > RMS_THRESHOLD) { main.post { bargeIn() }; break }
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
