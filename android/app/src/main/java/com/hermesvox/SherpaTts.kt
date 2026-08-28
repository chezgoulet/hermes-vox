package com.hermesvox

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
    private var streamTrack: AudioTrack? = null
    val playbackSession: Int get() = streamTrack?.audioSessionId ?: 0
    private var streamWritten = 0
    private var streamSR = 0
    // #7: serialize stream-track writes vs stopStreaming() so release() never races a
    // WRITE_BLOCKING (the SIGSEGV). stopStreaming() nulls the track under the lock and
    // waits for an in-flight write to clear before pause/stop/flush/release.
    private val trackLock = Object()
    @Volatile private var writing = false
    override val name: String get() = "Piper"
    override val isWarm: Boolean get() = tts != null
    override val supportsStreaming: Boolean get() = isWarm
    override val warmReason: String
        get() = if (tts != null) "" else "piper model not loaded"

    private val dir get() = File(context.filesDir, "models/piper-lessac")

    override fun init(onReady: (Boolean) -> Unit) {
        thread {
            try {
                val model = File(dir, "model.onnx")
                val tokens = File(dir, "tokens.txt")
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
                val audio = t.generate(text, 0, 1.0f)
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
            // Use a normal playback buffer (~250 ms), NOT tied to the whole audio.
            val bufBytes = maxOf(minBuf, (sr / 4) * 4)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            t.play()
            t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            // WAIT for the full audio to PLAY (the old code stopped immediately,
            // silencing the tail of every reply — the mid-synthesis truncation).
            var waited = 0
            while (t.playState == AudioTrack.PLAYSTATE_PLAYING && waited < 120000) {
                if (t.getPlaybackHeadPosition().toLong() >= samples.size.toLong() - 1L) break
                Thread.sleep(8); waited += 8
            }
            t.stop(); t.release()
            VoxLog.d("piper played ${samples.size} samples (waited ${waited}ms)")
        } catch (e: Throwable) {
            VoxLog.e("piper play: ${e.message}")
        }
    }

    /** Blocking synth + play on the calling thread (used by the streaming worker,
     *  which plays reply chunks sequentially so audio tracks the incoming text). */
    override fun speakBlocking(text: String): Boolean {
        val t = tts ?: return false
        return try {
            val audio = t.generate(text, 0, 1.0f)
            val samples = audio.samples ?: return false
            VoxLog.d("piper gen ${samples.size} samples @${audio.sampleRate}Hz (${text.length} ch)")
            play(samples, audio.sampleRate)
            true
        } catch (e: Throwable) { VoxLog.e("piper speakBlocking: ${e.message}"); false }
    }

    /** #25: arm reply-streaming TTS. The persistent playback track is built LAZILY on
     *  the first synthesized chunk at the voice model's ACTUAL sample rate — a hardcoded
     *  rate (22050Hz) played a different-rate voice pitch- and speed-shifted, silently.
     *  Do NOT build the track here; the real rate is only known once a chunk is generated. */
    fun startStreaming() {
        try {
            synchronized(trackLock) {
                streamTrack = null
                streamWritten = 0
                streamSR = 0
            }
        } catch (e: Throwable) { VoxLog.e("startStreaming: ${e.message}") }
    }

    /** Build + play a single persistent AudioTrack at the given rate (the actual one). */
    private fun buildStreamTrack(sr: Int): AudioTrack? {
        return try {
            val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val bufBytes = maxOf(minBuf, (sr / 4) * 4)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(bufBytes).setTransferMode(AudioTrack.MODE_STREAM).build()
            t.play()
            t
        } catch (e: Throwable) { VoxLog.e("buildStreamTrack: ${e.message}"); null }
    }

    /** Synthesize a chunk + append it to the persistent track (built at the first chunk's
     *  actual rate). Writing each chunk into ONE track keeps the speech continuous. */
    fun streamChunk(text: String): Boolean {
        val eng = tts ?: return false
        return try {
            val audio = eng.generate(text, 0, 1.0f)
            val samples = audio.samples ?: return false
            val sr = audio.sampleRate   // #25: the ACTUAL model rate, not a hardcoded one
            val t: AudioTrack
            synchronized(trackLock) {
                if (streamTrack == null) {
                    val built = buildStreamTrack(sr)
                    streamTrack = built; streamWritten = 0
                    t = built ?: return false
                } else {
                    t = streamTrack ?: return false
                }
                streamSR = sr
                writing = true                    // stopStreaming() waits for this to clear
            }
            VoxLog.d("piper chunk ${samples.size} smp @${sr}Hz (${text.length} ch)")
            try { t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING); streamWritten += samples.size; true }
            finally { synchronized(trackLock) { writing = false; trackLock.notifyAll() } }
        } catch (e: Throwable) { VoxLog.e("streamChunk: ${e.message}"); false }
    }

    /** Wait for the whole reply to play out, then release the persistent track. */
    fun finishStreaming(timeoutMs: Int = 120000) {
        val t = streamTrack ?: return
        try {
            var waited = 0
            while (t.playState == AudioTrack.PLAYSTATE_PLAYING && waited < timeoutMs) {
                if (t.getPlaybackHeadPosition().toLong() >= streamWritten.toLong() - 1L) break
                Thread.sleep(8); waited += 8
            }
        } catch (_: Throwable) {}
        stopStreaming()
    }
    fun stopStreaming() {
        val t: AudioTrack?
        synchronized(trackLock) {
            t = streamTrack
            streamTrack = null                       // fence: no new write may start
            while (writing) { try { trackLock.wait(50) } catch (_: Throwable) { break } }
        }
        try { t?.pause() } catch (_: Throwable) {}   // stop might block on a full buffer in some builds
        try { t?.stop() } catch (_: Throwable) {}
        try { t?.flush() } catch (_: Throwable) {}
        try { t?.release() } catch (_: Throwable) {} // never concurrent with a WRITE -> no SIGSEGV #7
        streamWritten = 0
    }

    override fun stop() { stopStreaming() }
    @Synchronized
    override fun shutdown() { tts = null }
}
