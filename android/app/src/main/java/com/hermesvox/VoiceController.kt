package com.hermesvox

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import com.hermesvox.mobile.HermesSession
import java.util.Locale
import kotlin.concurrent.thread

/**
 * VoiceController is the phone-side "front of house." It owns the warm voice
 * turn: listen (SpeechRecognizer, on-device STT) -> the entity (HermesSession
 * turnStored, server-side context) -> speak (TextToSpeech). While the reply is
 * spoken it watches the mic (AudioRecord RMS) and, on speech above the threshold,
 * barge-in: stop the TTS + signal a cancel + listen again.
 */
class VoiceController(private val context: Context, private val session: HermesSession) {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var record: AudioRecord? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var speaking = false
    @Volatile private var bargeInArmed = false
    private var onListening: (() -> Unit)? = null
    private var onReply: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    fun start(onListening: () -> Unit, onReply: (String) -> Unit, onError: (String) -> Unit) {
        this.onListening = onListening
        this.onReply = onReply
        this.onError = onError
        tts = TextToSpeech(context) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.US }
        listen()
    }

    fun stop() {
        recognizer?.destroy()
        recognizer = null
        stopTts()
        stopBargeInWatch()
        tts?.shutdown()
    }

    // --- Listening (on-device STT via SpeechRecognizer) ---
    private fun listen() {
        stopTts()
        onListening?.invoke()
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { r ->
            r.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) { onListening?.invoke() }
                override fun onBeginningOfSpeech() { onListening?.invoke() }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: Bundle) {}
                override fun onEvent(t: Int, p: Bundle?) {}
                override fun onResults(p: Bundle) {
                    val text = p.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                    if (text.isNotBlank()) runTurn(text) else listen()
                }
                override fun onError(e: Int) {
                    if (e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || e == SpeechRecognizer.ERROR_NO_MATCH) listen()
                    else onError?.invoke("speech: error $e")
                }
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer?.startListening(intent)
    }

    // --- Turn: the entity reasons server-side, then we speak ---
    private fun runTurn(text: String) {
        thread {
            val reply = try {
                session.turnStored(text) // /v1/responses: server-side context
            } catch (e: Throwable) {
                main.post { onError?.invoke("hermes: ${e.message}"); listen() }
                return@thread
            }
            main.post {
                onReply?.invoke(reply)
                speak(reply)
            }
        }
    }

    private fun speak(reply: String) {
        speaking = true
        bargeInArmed = true
        tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "hv")
        startBargeInWatch()
    }

    // --- Barge-in: watch the mic (RMS) while the reply is spoken; cut + stop ---
    private fun startBargeInWatch() {
        val minBuf = AudioRecord.getMinBufferSize(
            16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return
        record = AudioRecord(
            MediaRecorder.AudioSource.MIC, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
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
                if (rms > RMS_THRESHOLD) { // speech over the threshold -> barge-in
                    main.post { bargeIn() }
                    break
                }
            }
        }
    }

    private fun bargeIn() {
        speaking = false
        bargeInArmed = false
        stopBargeInWatch()
        stopTts()
        // TODO: send a cancel to the Hermes run (cancellation is supported by the
        // /v1/responses + runs API on the server) so the generation aborts.
        onError?.invoke("(interrupted)")
        listen()
    }

    private fun stopBargeInWatch() {
        bargeInArmed = false
        try { record?.stop(); record?.release() } catch (_: Exception) {}
        record = null
    }

    private fun stopTts() { try { tts?.stop() } catch (_: Exception) {} }

    companion object { const val RMS_THRESHOLD = 0.02 } // tune: the "hear you" threshold
}
