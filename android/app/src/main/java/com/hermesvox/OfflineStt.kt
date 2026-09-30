package com.hermesvox

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.HomophoneReplacerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
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
    /** The longest clip one transcribe() call decodes faithfully (ms); 0 = no
     *  limit. The capture loop windows longer utterances (SttWindows) so the turn
     *  text covers the WHOLE utterance. */
    val maxWindowMs: Int get() = 0
    fun init(onReady: (Boolean) -> Unit)
    fun transcribe(samples: FloatArray, sampleRate: Int): String?
    fun shutdown()
}

/** Transcribe a whole utterance, windowed per [VoxStt.maxWindowMs] (SttWindows):
 *  the committed turn text always covers every sample, however long the turn. */
fun VoxStt.transcribeWhole(samples: FloatArray, sampleRate: Int): String? {
    val win = maxWindowMs
    if (win <= 0) return transcribe(samples, sampleRate)
    val spans = SttWindows.spans(samples, sampleRate, win)
    if (spans.size == 1) return transcribe(samples, sampleRate)
    val parts = spans.map { (a, b) -> transcribe(samples.copyOfRange(a, b), sampleRate) }
    if (parts.all { it == null }) return null
    VoxLog.d("event=stt-windowed windows=${spans.size} ms=${samples.size * 1000L / sampleRate}")
    return SttWindows.join(parts)
}

/** The on-device recognizer for a catalog STT model id: Parakeet (NeMo TDT
 *  transducer) or Whisper. */
fun onDeviceSttFor(context: Context, modelId: String): VoxStt =
    if (ModelCatalog.isTransducerStt(modelId)) OfflineParakeetStt(context, modelId)
    else OfflineWhisperStt(context, modelId)

/**
 * SherpaOfflineStt — the shared load/decode shell for the sherpa-onnx offline
 * recognizers (Whisper, Parakeet): model dir, thread count, background init,
 * a synchronized decode, resampling to the 16 kHz the models expect.
 */
abstract class SherpaOfflineStt(protected val context: Context, protected val modelId: String) : VoxStt {
    private var rec: OfflineRecognizer? = null
    @Volatile private var ready = false
    override val isAvailable get() = rec != null

    protected val dir get() = File(context.filesDir, "models/$modelId")

    /** The decoder thread count (VoxThreads): the Settings override, else half the
     *  cores capped at 4. numThreads is baked into the recognizer at construction,
     *  so a Settings change lands on the next model load, not mid-turn. */
    protected val sttThreads: Int = VoxThreads.stt(
        Runtime.getRuntime().availableProcessors(),
        context.getSharedPreferences("hv", Context.MODE_PRIVATE).getInt(VoxThreads.PREF, VoxThreads.AUTO),
    )

    /** The recognizer config, or null when the model files are not installed. */
    protected abstract fun buildConfig(): OfflineRecognizerConfig?

    override fun init(onReady: (Boolean) -> Unit) {
        thread {
            try {
                val cfg = buildConfig()
                if (cfg == null) { onReady(false); return@thread }
                val r = OfflineRecognizer(null, cfg)   // assetManager=null: absolute-path model
                // READY MEANS WARM: one throwaway decode of a second of faint noise before the
                // leg reports ready, so the caller's first utterance is not the cold ONNX pass
                // (first-run graph init + allocation). The output is discarded.
                val tw = System.currentTimeMillis()
                try {
                    val warm = FloatArray(16000) { ((it * 7919) % 200 - 100) / 100_000f }
                    val s = r.createStream(); s.acceptWaveform(warm, 16000); r.decode(s); s.release()
                } catch (_: Throwable) {}
                rec = r
                ready = true
                VoxLog.d("$name loaded: threads=$sttThreads cores=${Runtime.getRuntime().availableProcessors()} warmMs=${System.currentTimeMillis() - tw}")
                onReady(true)
            } catch (e: Throwable) {
                VoxLog.e("$name init failed: ${e.message}")
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
            val text = r.getResult(s).text
            s.release()
            text
        } catch (e: Throwable) { VoxLog.e("$name decode: ${e.message}"); null }
    }

    @Synchronized override fun shutdown() { rec?.release(); rec = null; ready = false }

    protected fun modelConfig(block: OfflineModelConfig.() -> Unit): OfflineModelConfig =
        OfflineModelConfig().apply {
            numThreads = sttThreads
            provider = "cpu"
            block()
        }

    protected fun recognizerConfig(model: OfflineModelConfig): OfflineRecognizerConfig =
        OfflineRecognizerConfig(FeatureConfig(16000, 80, 0f), model, HomophoneReplacerConfig("", "", ""),
            "greedy_search", 4, "", 0f, "", "", 0f)

    private fun resample(inp: FloatArray, fromHz: Int, toHz: Int): FloatArray {
        if (fromHz == toHz) return inp
        val ratio = toHz.toFloat() / fromHz; val nOut = (inp.size * ratio).toInt(); val out = FloatArray(nOut)
        for (i in 0 until nOut) { val idx = i / ratio; val i0 = idx.toInt(); val i1 = (i0 + 1).coerceAtMost(inp.size - 1); val fr = idx - i0; out[i] = inp[i0] * (1 - fr) + inp[i1] * fr }
        return out
    }
}

/**
 * OfflineWhisperStt — on-device Whisper via sherpa-onnx, MODEL-SELECTABLE
 * (tiny / base / small, chosen in Settings; "whisper-base" is the blessed
 * default). Loads filesDir/models/<modelId>/ (encoder.onnx, decoder.onnx,
 * tokens.txt). Fully offline; isAvailable=false when the model isn't installed.
 */
class OfflineWhisperStt(context: Context, modelId: String = "whisper-base") : SherpaOfflineStt(context, modelId) {
    override val name get() = "Whisper-$modelId"
    /** 30 s encoder limit minus tail-padding room (SttWindows). */
    override val maxWindowMs: Int get() = SttWindows.WHISPER_WINDOW_MS

    /** The weights file for [part] ("encoder"/"decoder"): int8 when present (canonical name, or
     *  the upstream `<model>-<part>.int8.onnx` of an install that predates the rename), else fp32. */
    private fun whisperFile(part: String): File? {
        File(dir, "$part.int8.onnx").takeIf { it.exists() }?.let { return it }
        dir.listFiles()?.firstOrNull { it.name.endsWith("-$part.int8.onnx") }?.let { return it }
        return File(dir, "$part.onnx").takeIf { it.exists() }
    }

    /** Once the int8 weights are in use, the fp32 copies are dead weight (~200 MB for base,
     *  ~480 MB for small) on the user's storage. */
    private fun pruneFp32IfInt8() {
        for (part in listOf("encoder", "decoder")) {
            val int8 = whisperFile(part) ?: continue
            if (!int8.name.contains(".int8.")) continue
            val fp32 = File(dir, "$part.onnx")
            if (fp32.exists() && fp32.delete()) VoxLog.d("$name: pruned unused fp32 $part (int8 in use)")
        }
    }

    /** The decode language: "en" for the .en models (they only speak English);
     *  a multilingual model gets the `stt_language` pref ("" = auto-detect). */
    private val language: String = ModelCatalog.whisperLanguage(modelId,
        context.getSharedPreferences("hv", Context.MODE_PRIVATE).getString(ModelCatalog.KEY_STT_LANGUAGE, "") ?: "")

    override fun buildConfig(): OfflineRecognizerConfig? {
        // INT8 FIRST. The upstream package ships int8 weights beside the fp32 ones, and the app
        // used to load fp32 only (the rename rule never matched the int8 files). The bench
        // (tools/sttbench, 25 clips) measured whisper-base int8 at 4.5% WER vs 5.6% fp32 (noisy
        // 16% vs 32%), a little faster even on x86, at a quarter of the memory — and ARM phone
        // cores have dedicated int8 dot-product instructions. fp32 stays the fallback for an
        // install that only has it.
        val e = whisperFile("encoder"); val d = whisperFile("decoder"); val t = File(dir, "tokens.txt")
        if (e == null || d == null || !t.exists()) return null
        pruneFp32IfInt8()
        // sherpa-off-community shape (official kotlin-api + issue #2071):
        // tokens path + modelType belong on OfflineModelConfig, built via
        // the NO-ARG ctor + setters (NOT the full-arg ctor).
        //
        // tailPaddings: sherpa-onnx 1.13.6 kotlin-api OfflineWhisperModelConfig
        // declares `tailPaddings: Int = 1000` ("Padding added at the end of the
        // samples"); the C++ decoder (offline-recognizer-whisper-impl.h) appends that
        // many zero FEATURE frames (10 ms each) so Whisper can find its end-of-text
        // token, using 1000 whenever the value is <= 0. So the old literal 0 was
        // silently 1000 — harmless, but it read as "no padding". It is now explicit.
        // The upstream help text suggests 50 for English models; the sttbench corpus
        // disagrees on whisper-base.en: WER 5.6% at 1000 vs 11.0% at 300 and 12.4% at
        // 50, the smaller paddings looping ("This was this was …") or dropping the
        // final words. 1000 frames is also why SttWindows caps a window at 25 s: the
        // padding is clamped into the 30 s encoder input.
        val whisper = OfflineWhisperModelConfig(e.absolutePath, d.absolutePath, language, "transcribe",
            TAIL_PADDING_FRAMES, false, false)
        VoxLog.d("$name config: language=${language.ifEmpty { "auto" }} tailPaddings=$TAIL_PADDING_FRAMES")
        return recognizerConfig(modelConfig {
            this.whisper = whisper
            this.tokens = t.absolutePath
            this.modelType = "whisper"
        })
    }

    companion object {
        /** Zero feature frames appended after the audio (10 ms each). See buildConfig. */
        const val TAIL_PADDING_FRAMES = 1000

        /** PROOF HOOK: build a fresh STT, transcribe a WAV file, return text. */
        fun transcribeWave(context: Context, modelId: String, wavePath: String): String? {
            val stt = onDeviceSttFor(context, modelId)
            var ready = false
            stt.init { ready = it }
            for (i in 0 until 60) { if (ready) break; Thread.sleep(100) }
            if (!ready) return null
            val samples = readWav16kMono(File(wavePath)) ?: return null
            return stt.transcribeWhole(samples, 16000)
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
}

/**
 * OfflineParakeetStt — NVIDIA Parakeet-TDT 0.6B (int8) via sherpa-onnx's NeMo
 * transducer path (kotlin-api-examples: OfflineTransducerModelConfig(encoder,
 * decoder, joiner) + modelType "nemo_transducer"). Loads filesDir/models/<id>/
 * (encoder.int8.onnx, decoder.int8.onnx, joiner.int8.onnx, tokens.txt — the
 * upstream tarball's names, kept as-is). On the sttbench corpus it is the most
 * accurate AND fastest on-device option (host CPU: WER 3.4% vs whisper-base's
 * 5.6%, RTF 0.08 vs 0.19, no hallucinated text on the non-speech clips); its cost
 * is the 482 MB download and a larger resident model — so it is opt-in.
 */
class OfflineParakeetStt(context: Context, modelId: String = "parakeet-v2") : SherpaOfflineStt(context, modelId) {
    override val name get() = "Parakeet-$modelId"
    /** Not a hard encoder limit (TDT decodes long audio) — a memory bound: the
     *  encoder's attention grows with length, so a monologue is windowed at 60 s. */
    override val maxWindowMs: Int get() = 60_000

    override fun buildConfig(): OfflineRecognizerConfig? {
        val e = File(dir, "encoder.int8.onnx"); val d = File(dir, "decoder.int8.onnx")
        val j = File(dir, "joiner.int8.onnx"); val t = File(dir, "tokens.txt")
        if (!e.exists() || !d.exists() || !j.exists() || !t.exists()) return null
        return recognizerConfig(modelConfig {
            this.transducer = OfflineTransducerModelConfig().apply {
                encoder = e.absolutePath; decoder = d.absolutePath; joiner = j.absolutePath
            }
            this.tokens = t.absolutePath
            this.modelType = "nemo_transducer"
        })
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

    /** Drop the VAD's internal windowed state (sherpa Vad.reset()). The barge-in feed
     *  shares this same SileroVadGate with next-turn segmentation, so the controller
     *  MUST call reset() at gate close — otherwise frames captured while the agent is
     *  speaking can corrupt the next utterance's start/end decisions. */
    @Synchronized fun reset() {
        try { vad?.reset() } catch (_: Throwable) {}
    }

    @Synchronized fun shutdown() { vad?.release(); vad = null }
}
