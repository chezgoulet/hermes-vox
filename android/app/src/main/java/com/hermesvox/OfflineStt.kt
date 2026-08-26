package com.hermesvox

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.HomophoneReplacerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import kotlin.concurrent.thread

/** Abstraction over the STT leg so the pipeline can pick on-device Whisper or a fallback. */
interface VoxStt {
    val name: String
    val isAvailable: Boolean
    fun init(onReady: (Boolean) -> Unit)
    /** Transcribe raw PCM (FloatArray, -1..1) at sampleRate -> text, or null on failure. BLOCKING; call on a worker thread. */
    fun transcribe(samples: FloatArray, sampleRate: Int): String?
    fun shutdown()
}

/**
 * OfflineWhisperStt — on-device Whisper (tiny.en) via sherpa-onnx. Replaces the
 * flaky Google SpeechRecognizer; fully offline, no cloud. Loads the model the
 * app downloaded into filesDir/models/whisper-tiny/ (encoder.onnx, decoder.onnx,
 * tokens.txt). If absent/unloadable, isAvailable=false and the pipeline keeps the
 * platform STT fallback.
 */
class OfflineWhisperStt(private val context: Context) : VoxStt {
    private var rec: OfflineRecognizer? = null
    override val name get() = "Whisper"
    override val isAvailable get() = rec != null

    private val dir get() = File(context.filesDir, "models/whisper-tiny")

    override fun init(onReady: (Boolean) -> Unit) {
        thread {
            try {
                val e = File(dir, "encoder.onnx"); val d = File(dir, "decoder.onnx"); val t = File(dir, "tokens.txt")
                if (!e.exists() || !d.exists() || !t.exists()) { onReady(false); return@thread }
                // sherpa-off-community shape (official kotlin-api + issue #2071):
                // the whisper model's TOKENS path + modelType belong on
                // OfflineModelConfig, and the config is built via the no-arg ctor
                // + setters (NOT the full-arg ctor, whose non-whisper model fields
                // default to empty instances and confuse the native picker).
                val whisper = OfflineWhisperModelConfig(e.absolutePath, d.absolutePath, "en", "transcribe", 0, false, false)
                val modelCfg = OfflineModelConfig().apply {
                    this.whisper = whisper
                    this.tokens = t.absolutePath
                    this.modelType = "whisper"
                    this.numThreads = 1
                    this.provider = "cpu"
                }
                val feat = FeatureConfig(16000, 80, 0f)
                val hr = HomophoneReplacerConfig("", "", "")
                val cfg = OfflineRecognizerConfig(feat, modelCfg, hr, "greedy_search", 4, "", 0f, "", "", 0f)
                // assetManager=null: absolute filesystem model (same rule as TTS).
                rec = OfflineRecognizer(null, cfg)
                VoxLog.d("OfflineWhisperStt loaded: whisper-tiny")
                onReady(true)
            } catch (e: Throwable) {
                VoxLog.e("OfflineWhisperStt init failed: ${e.message}")
                onReady(false)
            }
        }
    }

    override fun transcribe(samples: FloatArray, sampleRate: Int): String? {
        val r = rec ?: return null
        return try {
            val s = r.createStream()
            // Whisper expects 16 kHz mono; resample if the source differs.
            val fed = if (sampleRate == 16000) samples else resample(samples, sampleRate, 16000)
            s.acceptWaveform(fed, 16000)
            r.decode(s)
            r.getResult(s).text
        } catch (e: Throwable) { VoxLog.e("whisper decode: ${e.message}"); null }
    }

    override fun shutdown() { rec?.release(); rec = null }

    private fun resample(inp: FloatArray, fromHz: Int, toHz: Int): FloatArray {
        if (fromHz == toHz) return inp
        val ratio = toHz.toFloat() / fromHz
        val nOut = (inp.size * ratio).toInt()
        val out = FloatArray(nOut)
        for (i in 0 until nOut) {
            val idx = i / ratio
            val i0 = idx.toInt(); val i1 = (i0 + 1).coerceAtMost(inp.size - 1)
            val frac = idx - i0
            out[i] = inp[i0] * (1 - frac) + inp[i1] * frac
        }
        return out
    }
}

/**
 * SileroVadGate — on-device voice-activity detection for the barge-in / wake
 * trigger. Feeds mic float frames; isSpeech() = someone is talking. Replaces the
 * crude RMS threshold when the model is installed.
 */
class SileroVadGate(private val context: Context) {
    private var vad: Vad? = null
    val isAvailable get() = vad != null
    val dir get() = File(context.filesDir, "models/silero-vad")

    fun init(onReady: (Boolean) -> Unit) {
        thread {
            try {
                val m = File(dir, "silero_vad.onnx")
                if (!m.exists()) { onReady(false); return@thread }
                val sil = SileroVadModelConfig(m.absolutePath, 0.5f, 0.5f, 0.25f, 512, 20f)
                val cfg = VadModelConfig(sileroVadModelConfig = sil, sampleRate = 16000, numThreads = 1, provider = "cpu")
                vad = Vad(null, cfg)
                VoxLog.d("SileroVadGate loaded: silero-vad")
                onReady(true)
            } catch (e: Throwable) { VoxLog.e("SileroVadGate init failed: ${e.message}"); onReady(false) }
        }
    }

    /** Feed one frame of 16kHz mono PCM; returns true when speech is detected. */
    fun feed(samples: FloatArray): Boolean {
        val v = vad ?: return false
        return try { v.acceptWaveform(samples); v.isSpeechDetected() } catch (_: Throwable) { false }
    }

    fun shutdown() { vad?.release(); vad = null }
}
