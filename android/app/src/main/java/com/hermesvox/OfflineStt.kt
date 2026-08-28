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

/** Abstraction over the STT leg so the pipeline can pick on-device Whisper, the
 *  house GPU, or the platform recognizer. transcribe() is BLOCKING — call it on
 *  a worker thread. */
interface VoxStt {
    val name: String
    val isAvailable: Boolean
    fun init(onReady: (Boolean) -> Unit)
    fun transcribe(samples: FloatArray, sampleRate: Int): String?
    fun shutdown()
}

/**
 * OfflineWhisperStt — on-device Whisper via sherpa-onnx, MODEL-SELECTABLE
 * (tiny / base / small, chosen in Settings; "whisper-base" is the blessed
 * default). Loads filesDir/models/<modelId>/ (encoder.onnx, decoder.onnx,
 * tokens.txt). Fully offline; isAvailable=false when the model isn't installed.
 */
class OfflineWhisperStt(private val context: Context, private val modelId: String = "whisper-base") : VoxStt {
    private var rec: OfflineRecognizer? = null
    @Volatile private var ready = false
    override val name get() = "Whisper-$modelId"
    override val isAvailable get() = rec != null

    private val dir get() = File(context.filesDir, "models/$modelId")

    override fun init(onReady: (Boolean) -> Unit) {
        thread {
            try {
                val e = File(dir, "encoder.onnx"); val d = File(dir, "decoder.onnx"); val t = File(dir, "tokens.txt")
                if (!e.exists() || !d.exists() || !t.exists()) { onReady(false); return@thread }
                // sherpa-off-community shape (official kotlin-api + issue #2071):
                // tokens path + modelType belong on OfflineModelConfig, built via
                // the NO-ARG ctor + setters (NOT the full-arg ctor).
                val whisper = OfflineWhisperModelConfig(e.absolutePath, d.absolutePath, "en", "transcribe", 0, false, false)
                val modelCfg = OfflineModelConfig().apply {
                    this.whisper = whisper
                    this.tokens = t.absolutePath
                    this.modelType = "whisper"
                    this.numThreads = 1
                    this.provider = "cpu"
                }
                val feat = FeatureConfig(16000, 80, 0f)
                val cfg = OfflineRecognizerConfig(feat, modelCfg, HomophoneReplacerConfig("", "", ""), "greedy_search", 4, "", 0f, "", "", 0f)
                rec = OfflineRecognizer(null, cfg)   // assetManager=null: absolute-path model
                ready = true
                VoxLog.d("OfflineWhisperStt loaded: $modelId")
                onReady(true)
            } catch (e: Throwable) {
                VoxLog.e("OfflineWhisperStt($modelId) init failed: ${e.message}")
                onReady(false)
            }
        }
    }

    @Synchronized override fun transcribe(samples: FloatArray, sampleRate: Int): String? {
        if (!ready) return null
        val r = rec ?: return null
        return try {
            val s = r.createStream()
            val fed = if (sampleRate == 16000) samples else resample(samples, sampleRate, 16000)
            s.acceptWaveform(fed, 16000)
            r.decode(s)
            r.getResult(s).text
        } catch (e: Throwable) { VoxLog.e("whisper decode: ${e.message}"); null }
    }

    @Synchronized override fun shutdown() { rec?.release(); rec = null }

    companion object {
        /** PROOF HOOK: build a fresh STT, transcribe a WAV file, return text. */
        fun transcribeWave(context: Context, modelId: String, wavePath: String): String? {
            val stt = OfflineWhisperStt(context, modelId)
            var ready = false
            stt.init { ready = it }
            for (i in 0 until 60) { if (ready) break; Thread.sleep(100) }
            if (!ready) return null
            val samples = readWav16kMono(File(wavePath)) ?: return null
            return stt.transcribe(samples, 16000)
        }

        /** Read a 16-bit PCM mono 16kHz WAV into a FloatArray (-1..1). */
        fun readWav16kMono(f: File): FloatArray? {
            return try {
                val bytes = f.readBytes()
                var i = 12; var dataOff = -1; var dataLen = 0
                while (i + 8 <= bytes.size) {
                    val id = String(bytes, i, 4); val len = leInt(bytes, i + 4)
                    if (id == "data") { dataOff = i + 8; dataLen = len; break }
                    i += 8 + len + (len and 1)
                }
                if (dataOff < 0) return null
                val n = dataLen / 2; val out = FloatArray(n)
                for (j in 0 until n) {
                    val lo = bytes[dataOff + j * 2].toInt() and 0xFF
                    val hi = bytes[dataOff + j * 2 + 1].toInt()
                    out[j] = ((hi shl 8) or lo).toShort() / 32768f
                }
                out
            } catch (e: Throwable) { VoxLog.e("readWav: ${e.message}"); null }
        }
        private fun leInt(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o+1].toInt() and 0xFF) shl 8) or ((b[o+2].toInt() and 0xFF) shl 16) or ((b[o+3].toInt() and 0xFF) shl 24)
    }

    private fun resample(inp: FloatArray, fromHz: Int, toHz: Int): FloatArray {
        if (fromHz == toHz) return inp
        val ratio = toHz.toFloat() / fromHz; val nOut = (inp.size * ratio).toInt(); val out = FloatArray(nOut)
        for (i in 0 until nOut) { val idx = i / ratio; val i0 = idx.toInt(); val i1 = (i0 + 1).coerceAtMost(inp.size - 1); val fr = idx - i0; out[i] = inp[i0] * (1 - fr) + inp[i1] * fr }
        return out
    }
}

/**
 * SileroVadGate — on-device voice-activity detection for the barge-in / wake
 * trigger. Replaces the RMS threshold when the model is installed.
 */
class SileroVadGate(private val context: Context, private val threshold: Float = 0.5f) {
    private var vad: Vad? = null
    val isAvailable get() = vad != null
    val dir get() = File(context.filesDir, "models/silero-vad")

    fun init(onReady: (Boolean) -> Unit) {
        thread {
            try {
                val m = File(dir, "silero_vad.onnx")
                if (!m.exists()) { onReady(false); return@thread }
                val sil = SileroVadModelConfig(m.absolutePath, threshold.coerceIn(0.01f, 0.99f), 0.5f, 0.25f, 512, 20f)
                val cfg = VadModelConfig(sileroVadModelConfig = sil, sampleRate = 16000, numThreads = 1, provider = "cpu")
                vad = Vad(null, cfg)
                VoxLog.d("SileroVadGate loaded: silero-vad")
                onReady(true)
            } catch (e: Throwable) { VoxLog.e("SileroVadGate init failed: ${e.message}"); onReady(false) }
        }
    }

    @Synchronized fun feed(samples: FloatArray): Boolean {
        val v = vad ?: return false
        return try { v.acceptWaveform(samples); v.isSpeechDetected() } catch (_: Throwable) { false }
    }

    @Synchronized fun shutdown() { vad?.release(); vad = null }
}
