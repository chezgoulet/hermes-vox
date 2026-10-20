package com.hermesvox

import android.content.Context
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.HomophoneReplacerConfig
import com.k2fsa.sherpa.onnx.OnlineCtcFstDecoderConfig
import com.k2fsa.sherpa.onnx.OnlineLMConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineNeMoCtcModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineToneCtcModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import java.io.File
import kotlin.concurrent.thread

/** The part of a streaming recognizer stream [StreamingTranscriber] drives. One instance is
 *  one utterance's stream; [release] frees it. */
internal interface StreamDecoder {
    fun accept(samples: FloatArray, sampleRate: Int)
    /** Decode every frame the stream has buffered so far. */
    fun decodeReady()
    fun text(): String
    /** No more audio: lets the decoder flush its last, partial chunk. */
    fun inputFinished()
    fun release()
}

/**
 * StreamingTranscriber — chunk/partial assembly over a [StreamDecoder], free of the native
 * library so it is unit-testable.
 *
 * [transcribeClip] runs the bench's procedure (android/bench StreamingSttBench) on a whole
 * clip, minus the wall-clock pacing: 20 ms chunks, each followed by a drain, then 500 ms of
 * trailing silence (the bench's endpoint tail) and inputFinished(), drained once more. Each
 * clip gets its own stream. [feed]/[partial] keep one live stream for a future continuous
 * caller; the turn loop does not use them.
 */
internal class StreamingTranscriber(private val open: () -> StreamDecoder) {
    private var live: StreamDecoder? = null

    fun transcribeClip(samples: FloatArray, sampleRate: Int): String {
        val d = open()
        try {
            val chunk = chunkSamples(sampleRate)
            var at = 0
            while (at < samples.size) {
                val n = minOf(chunk, samples.size - at)
                d.accept(samples.copyOfRange(at, at + n), sampleRate)
                d.decodeReady()
                at += n
            }
            d.accept(FloatArray(sampleRate * TAIL_MS / 1000), sampleRate)
            d.inputFinished()
            d.decodeReady()
            return d.text().trim()
        } finally { d.release() }
    }

    /** Append [chunk] to the live stream (opened on first use) and decode what is ready. */
    fun feed(chunk: FloatArray, sampleRate: Int) {
        val d = live ?: open().also { live = it }
        d.accept(chunk, sampleRate)
        d.decodeReady()
    }

    /** The live stream's text so far ("" before the first [feed]). */
    fun partial(): String = live?.text()?.trim().orEmpty()

    /** Drop the live stream; the next [feed] starts a fresh utterance. */
    fun reset() { live?.release(); live = null }

    companion object {
        /** Feed granularity, as the bench (chunk_ms). The model's own 160 ms chunk is baked
         *  into the encoder; this is only how often samples are handed over. */
        const val CHUNK_MS = 20
        /** Trailing silence after the clip, as the bench (trailing_silence_ms). */
        const val TAIL_MS = 500
        fun chunkSamples(sampleRate: Int): Int = (sampleRate * CHUNK_MS / 1000).coerceAtLeast(160)
    }
}

/** [StreamDecoder] over a sherpa-onnx [OnlineStream]. */
private class SherpaStreamDecoder(private val rec: OnlineRecognizer) : StreamDecoder {
    private val stream: OnlineStream = rec.createStream("")
    override fun accept(samples: FloatArray, sampleRate: Int) = stream.acceptWaveform(samples, sampleRate)
    override fun decodeReady() {
        var guard = 0   // a misbehaving model must not spin the decode thread forever
        while (rec.isReady(stream) && guard++ < 10_000) rec.decode(stream)
    }
    override fun text(): String = rec.getResult(stream).text
    override fun inputFinished() = stream.inputFinished()
    override fun release() = stream.release()
}

/**
 * OnlineNemotronStt — the streaming STT arm (Nemotron Speech Streaming 0.6B, 160 ms int8)
 * via sherpa-onnx's OnlineRecognizer, configured as the A/B bench configured it (NeMo
 * transducer, auto-detected from the ordinary transducer config; greedy search; 80-dim
 * features at 16 kHz). Loads filesDir/models/<id>/ ([STREAMING_TRANSDUCER_FILES]).
 *
 * For the turn loop it is a whole-clip recognizer like the others: transcribe() feeds the
 * finished utterance through a fresh stream and returns the text, so the capture loop is
 * unchanged. Endpointing is OFF: the turn's end is still the app's EndpointRule.
 */
class OnlineNemotronStt(private val context: Context, private val modelId: String = ModelCatalog.NEMOTRON_STREAM_ID) : VoxStt {
    override val name get() = "Nemotron-$modelId"
    /** Streaming decoding keeps a bounded state, so there is no whole-clip limit. */
    override val maxWindowMs: Int get() = 0

    private var rec: OnlineRecognizer? = null
    private var transcriber: StreamingTranscriber? = null
    @Volatile private var ready = false
    override val isAvailable get() = rec != null

    private val dir get() = File(context.filesDir, "models/$modelId")

    /** The app's STT thread budget (VoxThreads), as the offline legs use: on an 8-core
     *  phone that is the bench's 4. */
    private val sttThreads: Int = VoxThreads.stt(
        Runtime.getRuntime().availableProcessors(),
        context.getSharedPreferences("hv", Context.MODE_PRIVATE).getInt(VoxThreads.PREF, VoxThreads.AUTO),
    )

    /** The recognizer config, or null when the model files are not installed. */
    private fun buildConfig(): OnlineRecognizerConfig? {
        val f = STREAMING_TRANSDUCER_FILES.map { File(dir, it) }
        if (!f.all { it.exists() }) return null
        val (encoder, decoder, joiner, tokens) = f
        return OnlineRecognizerConfig(
            FeatureConfig(16000, 80, 0f),
            OnlineModelConfig(
                OnlineTransducerModelConfig(encoder.absolutePath, decoder.absolutePath, joiner.absolutePath),
                OnlineParaformerModelConfig(),
                OnlineZipformer2CtcModelConfig(),
                OnlineNeMoCtcModelConfig(),
                OnlineToneCtcModelConfig(),
                tokens.absolutePath,
                sttThreads,
                false,   // debug
                "cpu",   // provider
                "",      // modelType: NeMo transducers are auto-detected
                "",      // modelingUnit
                "",      // bpeVocab
            ),
            OnlineLMConfig("", 0.0f),
            OnlineCtcFstDecoderConfig("", 0),
            HomophoneReplacerConfig("", "", ""),
            // The bench's rules, inert here: enableEndpoint is false, so no endpoint is ever
            // detected and nothing in this leg decides when a turn ends.
            EndpointConfig(
                EndpointRule(true, 0.5f, 0f),
                EndpointRule(false, 0.5f, 0f),
                EndpointRule(false, 0f, 20f),
            ),
            false,   // enableEndpoint
            "greedy_search",
            4,
            "",
            0.0f,
            "",
            "",
            0.0f,
        )
    }

    override fun init(onReady: (Boolean) -> Unit) {
        thread {
            try {
                val cfg = buildConfig()
                if (cfg == null) { onReady(false); return@thread }
                val t0 = System.currentTimeMillis()
                val r = OnlineRecognizer(null, cfg)   // assetManager=null: absolute-path model
                val tr = StreamingTranscriber { SherpaStreamDecoder(r) }
                // READY MEANS WARM, as the offline legs: one discarded decode of faint noise.
                val tw = System.currentTimeMillis()
                try { tr.transcribeClip(FloatArray(16000) { ((it * 7919) % 200 - 100) / 100_000f }, 16000) } catch (_: Throwable) {}
                synchronized(this) { rec = r; transcriber = tr; ready = true }
                VoxLog.d("$name loaded: threads=$sttThreads loadMs=${tw - t0} warmMs=${System.currentTimeMillis() - tw}")
                onReady(true)
            } catch (e: Throwable) {
                VoxLog.e("$name init failed: ${e.message}")
                onReady(false)
            }
        }
    }

    @Synchronized override fun transcribe(samples: FloatArray, sampleRate: Int): String? {
        if (!ready) return null
        val tr = transcriber ?: return null
        return try { tr.transcribeClip(samples, sampleRate) }
        catch (e: Throwable) { VoxLog.e("$name decode: ${e.message}"); null }
    }

    /** Continuous-use surface (unused by the turn loop): append audio to the live stream. */
    @Synchronized fun feed(chunk: FloatArray, sampleRate: Int) {
        if (!ready) return
        try { transcriber?.feed(chunk, sampleRate) } catch (e: Throwable) { VoxLog.e("$name feed: ${e.message}") }
    }

    /** The live stream's text so far. */
    @Synchronized fun partial(): String = if (ready) transcriber?.partial().orEmpty() else ""

    /** Start the live stream over. */
    @Synchronized fun resetStream() { transcriber?.reset() }

    @Synchronized override fun shutdown() {
        ready = false
        try { transcriber?.reset() } catch (_: Throwable) {}
        transcriber = null
        rec?.release(); rec = null
    }
}
