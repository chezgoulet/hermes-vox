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
import android.speech.tts.TextToSpeech
import com.hermesvox.mobile.HermesSession
import java.util.Locale
import kotlin.concurrent.thread

/**
 * VoiceController is the phone-side "front of house." It owns the warm voice
 * turn: listen (SpeechRecognizer, on-device STT) -> the entity (HermesSession, a
 * CANCELLABLE run — StartRun + RunStatus poll) -> speak (TextToSpeech). While the
 * reply is spoken it watches the mic (AudioRecord RMS) and, on speech over the
 * threshold, barge-in: CancelRun (abort the agent) + stop the TTS + re-listen.
 */
class VoiceController(private val context: Context, private val session: HermesSession) {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var record: AudioRecord? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var speaking = false
    @Volatile private var bargeInArmed = false
    @Volatile private var currentRunId: String? = null
    private var onListening: (() -> Unit)? = null
    private var onReplyText: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    fun start(onListening: () -> Unit, onReply: (String) -> Unit, onError: (String) -> Unit) {
        this.onListening = onListening
        this.onReplyText = onReply
        this.onError = onError
        tts = TextToSpeech(context) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.US }
        listen()
    }

    fun stop() {
        recognizer?.destroy()
        recognizer = null
        stopTts()
        stopBargeInWatch()
        currentRunId?.let { try { session.cancelRun(it) } catch (_: Exception) {} }
        currentRunId = null
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

    // --- Turn: start a cancellable run, poll to completion, then speak ---
    private fun runTurn(text: String) {
        thread {
            try {
                val runId = session.startRun(text)
                currentRunId = runId
                var reply = ""
                var done = false
                var tries = 0
                while (!done && tries < 120) {
                    val r = session.runStatus(runId)
                    reply = r
                    done = r.isNotBlank() // empty = the run is still working
                    if (done) break
                    Thread.sleep(250)
                    tries++
                }
                currentRunId = null
                val finalReply = reply.takeIf { it.isNotBlank() } ?: "(no reply)"
                main.post {
                    onReplyText?.invoke(finalReply)
                    speaking = true
                    bargeInArmed = true
                    tts?.speak(finalReply, TextToSpeech.QUEUE_FLUSH, null, "hv")
                    startBargeInWatch()
                }
            } catch (e: Throwable) {
                main.post { onError?.invoke("hermes: ${e.message}"); listen() }
            }
        }
    }

    // --- Barge-in: watch the mic RMS while the reply is spoken; cut + cancel ---
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
                if (rms > RMS_THRESHOLD) { main.post { bargeIn() }; break }
            }
        }
    }

    private fun bargeIn() {
        speaking = false
        bargeInArmed = false
        stopBargeInWatch()
        stopTts()
        currentRunId?.let { try { session.cancelRun(it) } catch (_: Exception) {} }  // abort the agent
        currentRunId = null
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
