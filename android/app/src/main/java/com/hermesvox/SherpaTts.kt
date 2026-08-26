package com.hermesvox

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import kotlin.concurrent.thread

/**
 * SherpaTts — a REAL warm on-device voice: Piper (sherpa-onnx) synthesizes the
 * reply into float PCM, streamed to an AudioTrack. Loads the model the app
 * downloaded into filesDir/models/piper-lessac/. If the model is missing or the
 * engine fails to load, it reports isWarm=false and the pipeline uses system TTS
 * (a seamless fallback — never a broken turn). Fully offline, no cloud.
 */
class SherpaTts(private val context: Context) : VoxTts {
    private var tts: OfflineTts? = null
    override val name: String get() = "Piper"
    override val isWarm: Boolean get() = tts != null
    override val warmReason: String
        get() = if (tts != null) "" else "piper model not loaded"

    private val dir get() = File(context.filesDir, "models/piper-lessac")

    override fun init(onReady: (Boolean) -> Unit) {
        thread {
            try {
                val model = File(dir, "voz.onnx")
                val tokens = File(dir, "voz.txt")
                if (!model.exists() || !tokens.exists()) { onReady(false); return@thread }
                // data_dir must point at the espeak-ng-data dir itself (it holds
                // the phontab + G2P dicts the phoneme-based model needs).
                val dataDir = File(dir, "espeak-ng-data")
                val vits = OfflineTtsVitsModelConfig(
                    model.absolutePath, "", tokens.absolutePath, dataDir.absolutePath, "", 0.667f, 0.8f, 1.0f)
                val modelCfg = OfflineTtsModelConfig(vits = vits, numThreads = 1, provider = "cpu")
                val cfg = OfflineTtsConfig(modelCfg, "", "", 256, 1.0f)
                // IMPORTANT: sherpa requires assetManager=null when loading from
                // an absolute filesystem path (filesDir) — else it tries to read
                // the file as an asset and aborts (issue #2562).
                tts = OfflineTts(null, cfg)
                VoxLog.d("SherpaTts loaded: piper model")
                onReady(true)
            } catch (e: Throwable) {
                VoxLog.e("SherpaTts init failed: ${e.message}")
                onReady(false)
            }
        }
    }

    override fun speak(text: String, onDone: () -> Unit) {
        val t = tts ?: return onDone()
        thread {
            try {
                val audio: GeneratedAudio = t.generate(text, 0, 1.0f)
                val samples = audio.samples ?: return@thread onDone()
                val sr = audio.sampleRate
                VoxLog.d("piper generated ${samples.size} samples @ ${sr}Hz (text ${text.length} chars)")
                play(samples, sr)
                onDone()
            } catch (e: Throwable) {
                VoxLog.e("piper speak: ${e.message}")
                onDone()
            }
        }
    }

    private fun play(samples: FloatArray, sr: Int) {
        try {
            val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(minBuf.coerceAtLeast(samples.size * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            t.play()
            t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            t.stop()
            t.release()
            VoxLog.d("piper played ${samples.size} samples")
        } catch (e: Throwable) {
            VoxLog.e("piper play: ${e.message}")
        }
    }

    override fun stop() {}
    override fun shutdown() { tts = null }
}
