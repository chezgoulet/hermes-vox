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
    companion object {
        /** Playback RMS that drives the presence motion to full amplitude (previewB). */
        const val RMS_FULL = 0.22f
    }
    private var tts: OfflineTts? = null
    private var streamTrack: AudioTrack? = null
    val playbackSession: Int get() = streamTrack?.audioSessionId ?: 0
    private var streamWritten = 0
    private var streamSR = 0
    // 0.5.0-previewA speech-locked transcript (L2): the display needs to know what the
    // engine has actually PLAYED, not what the SSE stream delivered. Two additive reads,
    // no new thread — the head is sampled on demand by the reveal loop.
    /** Samples the playback track has actually rendered; falls back to what has been
     *  written when there is no track (not started / torn down). Never throws. */
    fun playedSamples(): Int = try { streamTrack?.playbackHeadPosition ?: streamWritten } catch (_: Throwable) { streamWritten }

    /** RMS of the ~20ms of audio the track is rendering RIGHT NOW, scaled to 0..1.
     *  0 when nothing is playing, when the head is outside the live buffer (the gap
     *  between phrases), or on any failure — silence reads as silence. Sampled on
     *  demand by the presence loop (~16Hz); no thread, no allocation per call. */
    fun speechLevel(): Float {
        val buf = liveBuf ?: return 0f
        val head = try { streamTrack?.playbackHeadPosition ?: return 0f } catch (_: Throwable) { return 0f }
        val i = head - liveBase
        if (i < 0 || i >= buf.size) return 0f
        val sr = if (streamSR > 0) streamSR else 22050
        val win = (sr / 50).coerceAtLeast(64)          // ~20ms: one syllable's envelope
        val end = minOf(buf.size, i + win)
        var acc = 0.0
        var n = 0
        var k = i
        while (k < end) { val v = buf[k].toDouble(); acc += v * v; n++; k++ }
        if (n == 0) return 0f
        val rms = Math.sqrt(acc / n).toFloat()
        // Piper emits float PCM in [-1,1]; conversational speech sits around 0.05-0.20
        // RMS, so RMS_FULL maps a normal speaking voice across the full 0..1 drive.
        return (rms / RMS_FULL).coerceIn(0f, 1f)
    }

    /** The voice model's ACTUAL sample rate for the current stream (0 until the first chunk). */
    val streamSampleRate: Int get() = streamSR
    /** Per-phrase audio accounting: (text, samples) reported in the ORDER the audio is
     *  written to the track, just before its first write. VoiceController turns this into
     *  a SpeechCursor; nothing in the audio path depends on it. */
    @Volatile var onAudioSegment: ((text: String, samples: Int) -> Unit)? = null
    // 0.5.0-previewB presence motion: the REAL voice amplitude. The avatar's speaking
    // motion used to pulse on a hardcoded 0.6 — a placeholder that looked identical
    // whether the entity was mid-word or mid-pause. These two fields hold the buffer
    // currently on the track and the sample offset it starts at, so speechLevel() can
    // RMS a short window AT THE PLAYBACK HEAD: the being moves with the syllable.
    // Read-only accounting — nothing in the audio path depends on them.
    @Volatile private var liveBuf: FloatArray? = null
    @Volatile private var liveBase = 0
    // #7: serialize stream-track writes vs teardown so release() never races a
    // WRITE_BLOCKING write (the SIGSEGV). The writer sets writing under the lock;
    // teardown never flushes/releases while writing==true.
    private val trackLock = Object()
    @Volatile private var writing = false
    // F1 pause-first silence: teardown (stop/flush/release) runs ONCE on a single
    // cleanup thread, never on the caller's thread — silence must not wait for the
    // very write it is killing. @Volatile-guarded single-flight (one at a time).
    @Volatile private var teardownActive = false
    // #D1 immediate-silence fence: a chunk may play ONLY while the stream is
    // started AND not stopped. stopStreaming() closes the fence BEFORE nulling
    // the track, so a synth worker that keeps iterating after a barge/hush/hangup
    // sees streamChunk() return false and CANNOT resurrect a playback track (the
    // null-track "first chunk" rebuild that kept the reply going up to 4s after
    // the cut). Pure + unit-tested (StreamFence) so the ordering is proven.
    private val streamFence = StreamFence()
    override val name: String get() = "Piper"
    override val isWarm: Boolean get() = tts != null
    override val supportsStreaming: Boolean get() = isWarm
    override val warmReason: String
        get() = if (tts != null) "" else "piper model not loaded"

    private val dir get() = File(context.filesDir, "models/piper-lessac")

    /** `tts_voice_usage` kill-switch (default false): route the playback AudioTracks
     *  through USAGE_VOICE_COMMUNICATION so the VOICE_COMMUNICATION capture's platform
     *  AEC has a proper echo reference for barge-in. false = the old USAGE_MEDIA
     *  attributes. This is a per-track USAGE attribute, NOT the MODE_IN_COMMUNICATION
     *  global toggle that the handoff lesson warned broke playback.
     *  Default OFF — field A/B 2026-09-06: OFF = loud volume AND working barge (single
     *  capture + double gate); ON = incall-quiet on Pixel. The toggle stays for leaky
     *  devices. */
    private fun voiceUsage(): Boolean =
        context.getSharedPreferences("hv", android.content.Context.MODE_PRIVATE).getBoolean("tts_voice_usage", false)

    private fun speechAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (voiceUsage()) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** The synthesis register from the `voice` pref (speed on the Piper engine).
     *  system=1.0 (shipped default) -> output identical when untouched. */
    private val voiceSpeed: Float by lazy { voiceRegister(currentVoiceRegister(context)).first }

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
            // 0.4.0.4: capture the cancel token BEFORE synthesis. generate() takes over
            // a second for a full reply (field log: 1.4s for 199 chars), and play() has
            // to OPEN the fence to write — so a stop that lands inside that window would
            // be undone by this very call. A changed token drops the utterance unplayed.
            val token = streamFence.stopEpoch
            try {
                val audio = t.generate(text, 0, voiceSpeed)
                val samples = audio.samples ?: return@thread onDone()
                val sr = audio.sampleRate
                VoxLog.d("piper generated ${samples.size} samples @ ${sr}Hz (text ${text.length} chars)")
                play(samples, sr, token, text)
                onDone()
            } catch (e: Throwable) {
                VoxLog.e("piper speak: ${e.message}")
                onDone()
            }
        }
    }

    /** One-shot utterance playback (speak / speakBlocking), 0.4.0.4 fence-aware.
     *
     *  It used to build a BARE, private AudioTrack: not the stream track, not under
     *  trackLock, not behind the fence — so stop()/hush/call-end could not touch it
     *  (they fence + tear down the STREAM track only), and one WRITE_BLOCKING call
     *  wrote the entire reply. Field log: a whole 199-char reply kept speaking for
     *  11s through two hushes, a call-end and the destroyed foreground service.
     *
     *  It now runs the SAME discipline as streamChunk(): the track is installed as
     *  the current stream track under trackLock, the fence is opened for this
     *  utterance, the write is sliced into ~2s sub-writes with a fence check before
     *  each one (F2 — a stop lands within one slice, not one whole reply), and the
     *  tail drains through finishStreaming() so nothing is truncated. stopStreaming()
     *  therefore pauses it on the caller's thread in ms (pause-first, F1) and hands
     *  stop/flush/release to the single teardown thread (#7) — the same msSinceBarge
     *  budget the streaming path already meets.
     *
     *  [cancelToken] is StreamFence.stopEpoch captured before synthesis; -1 skips the
     *  check (no caller does today). [text] is the utterance being played, reported to
     *  onAudioSegment so the speech-locked transcript can pace one-shot replies with the
     *  SAME cursor as streamed ones (a single segment — no special case). */
    private fun play(samples: FloatArray, sr: Int, cancelToken: Long = -1L, text: String = "") {
        var at = 0
        try {
            val t: AudioTrack
            synchronized(trackLock) {
                // A stop landed while we were synthesizing -> this utterance is dead.
                // Nothing is built and the fence is NOT re-opened.
                if (cancelToken >= 0L && streamFence.stopEpoch != cancelToken) {
                    VoxLog.d("event=tts-speak-drop reason=stopped-during-synth samples=${samples.size}")
                    return
                }
                val built = buildStreamTrack(sr) ?: return
                streamFence.start()          // open the fence for THIS utterance
                streamTrack = built          // stop()/hush/call-end can now reach it
                streamWritten = 0
                streamSR = sr
                liveBuf = samples; liveBase = 0   // previewB: RMS source for this utterance
                writing = true               // the async teardown waits for this to clear (#7)
                t = built
            }
            // L2: one segment for the whole utterance, registered before the first write
            // (the head starts advancing during it, so the cursor must already know it).
            try { onAudioSegment?.invoke(text, samples.size) } catch (_: Throwable) {}
            try {
                val twoSec = sr * 2                    // ~2s per sub-write, as streamChunk
                while (at < samples.size) {
                    if (!streamFence.allowed) {        // stop between sub-writes -> exit fast
                        VoxLog.d("event=tts-speak-cut at=$at of=${samples.size}")
                        return
                    }
                    val n = minOf(twoSec, samples.size - at)
                    t.write(samples, at, n, AudioTrack.WRITE_BLOCKING)
                    at += n
                    synchronized(trackLock) { streamWritten = at }
                }
            } finally { synchronized(trackLock) { writing = false; trackLock.notifyAll() } }
            // WAIT for the full audio to PLAY (stopping at the last write silences the
            // tail of every reply — the mid-synthesis truncation), then release. The
            // wait is the fence-aware one: a stop skips it and the track is already gone.
            finishStreaming()
            VoxLog.d("piper played $at samples")
        } catch (e: Throwable) {
            VoxLog.e("piper play: ${e.message}")
        }
    }

    /** Blocking synth + play on the calling thread (used by the streaming worker,
     *  which plays reply chunks sequentially so audio tracks the incoming text). */
    override fun speakBlocking(text: String): Boolean {
        val t = tts ?: return false
        val token = streamFence.stopEpoch   // 0.4.0.4: cancel a stop that lands mid-synth
        return try {
            val audio = t.generate(text, 0, voiceSpeed)
            val samples = audio.samples ?: return false
            VoxLog.d("piper gen ${samples.size} samples @${audio.sampleRate}Hz (${text.length} ch)")
            play(samples, audio.sampleRate, token, text)
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
                streamFence.start()   // #D1: open the fence — chunks may play again
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
                .setAudioAttributes(speechAttributes())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(bufBytes).setTransferMode(AudioTrack.MODE_STREAM).build()
            t.play()
            t
        } catch (e: Throwable) { VoxLog.e("buildStreamTrack: ${e.message}"); null }
    }

    /** Synthesize a chunk + append it to the persistent track (built at the first chunk's
     *  actual rate). Writing each chunk into ONE track keeps the speech continuous. Each
     *  chunk is sliced into ~2s sub-writes with a fence check before every slice, so a
     *  stop that lands mid-chunk exits between sub-writes instead of draining the whole
     *  sentence's WRITE_BLOCKING write (F2: bounds the drain-wait to ~2s). */
    fun streamChunk(text: String): Boolean {
        if (!streamFence.allowed) return false   // #D1: fence closed -> no synth, no rebuild
        val eng = tts ?: return false
        return try {
            val audio = eng.generate(text, 0, voiceSpeed)
            val samples = audio.samples ?: return false
            val sr = audio.sampleRate   // #25: the ACTUAL model rate, not a hardcoded one
            val t: AudioTrack
            synchronized(trackLock) {
                // #D1: re-check under the lock — a stop that landed while we were
                // synthesizing closed the fence, so a null track here must NOT be
                // treated as "first chunk" (that rebuild is the resurrection bug).
                if (!streamFence.allowed) return false
                if (streamTrack == null) {
                    val built = buildStreamTrack(sr)
                    streamTrack = built; streamWritten = 0
                    t = built ?: return false
                } else {
                    t = streamTrack ?: return false
                }
                streamSR = sr
                // previewB: this chunk starts where the track has been written to so far
                // (streamWritten only advances AFTER the whole chunk lands), so the head
                // maps straight into it.
                liveBuf = samples; liveBase = streamWritten
                writing = true                    // the async teardown waits for this to clear (#7)
            }
            VoxLog.d("piper chunk ${samples.size} smp @${sr}Hz (${text.length} ch)")
            // L2: this phrase is next on the track — register it BEFORE the first write so
            // the reveal can interpolate through it while the head is inside it.
            try { onAudioSegment?.invoke(text, samples.size) } catch (_: Throwable) {}
            try {
                val twoSec = sr * 2                    // ~2s of audio per sub-write (44100 @22050Hz)
                var at = 0
                while (at < samples.size) {
                    if (!streamFence.allowed) return false   // F2: stop between sub-writes exits fast
                    val n = minOf(twoSec, samples.size - at)
                    t.write(samples, at, n, AudioTrack.WRITE_BLOCKING)
                    at += n
                }
                streamWritten += samples.size
                true
            } finally { synchronized(trackLock) { writing = false; trackLock.notifyAll() } }
        } catch (e: Throwable) { VoxLog.e("streamChunk: ${e.message}"); false }
    }

    /** Wait for the whole reply to play out, then release the persistent track. */
    fun finishStreaming(timeoutMs: Int = 120000) {
        if (!streamFence.allowed) return          // #D1: stopped — nothing left to play out or release
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
    /** Pause-first stop (0.3.32.4): cut the AUDIBLE audio immediately, then hand the
     *  teardown to a single cleanup thread. Closing the fence + pause() run on the
     *  caller's thread and return in ms — the old stop waited SYNCHRONOUSLY on main
     *  for the in-flight WRITE_BLOCKING write to drain (up to ~6s of the very audio
     *  being silenced). pause() is safe concurrent with a write (it is stop/release
     *  that must never race the writer — #7). */
    fun stopStreaming() {
        val t: AudioTrack?
        synchronized(trackLock) {
            streamFence.stop()                   // #D1: close the fence BEFORE nulling the track —
            t = streamTrack                      // no chunk may start (or rebuild) after this point
            streamTrack = null                   // fence: no new write may start
            liveBuf = null                       // previewB: silence reports level 0, not the last RMS
            // F1: no wait for writing here — that belongs to the async teardown.
        }
        try { t?.pause() } catch (_: Throwable) {}   // the user-audible silence, NOW
        if (t != null) teardownTrackAsync(t)         // stop/flush/release off the caller's thread
    }

    /** F1: single-flight async teardown (@Volatile-guarded; a stop while one cleanup is
     *  already running is a no-op). t.stop() first — it unblocks a pending WRITE_BLOCKING
     *  write on-device — then a bounded poll for writing==false, then flush() + release().
     *  NEVER release while writing==true (#7 SIGSEGV invariant preserved exactly). */
    private fun teardownTrackAsync(t: AudioTrack) {
        synchronized(trackLock) {
            if (teardownActive) return
            teardownActive = true
        }
        thread(name = "tts-teardown") {
            try {
                try { t.stop() } catch (_: Throwable) {}   // unblocks a blocked WRITE_BLOCKING write
                var waited = 0
                while (writing && waited < 2000) {         // bounded poll for the writer to clear
                    Thread.sleep(20); waited += 20
                }
                if (writing) {
                    // Pathological backstop only: t.stop() above has already unblocked the
                    // writer, so this means a newer track is writing. Never flush/release
                    // over a live write — leak the track rather than crash (#7).
                    VoxLog.e("event=tts-teardown result=timeout writing=true ms=$waited")
                    return@thread
                }
                try { t.flush() } catch (_: Throwable) {}
                try { t.release() } catch (_: Throwable) {} // never concurrent with a WRITE -> no SIGSEGV #7
                // streamWritten reset stays after release (safe). Guard on the current track
                // so an already-started successor stream is not clobbered.
                synchronized(trackLock) { if (streamTrack == null) streamWritten = 0 }
                VoxLog.d("event=tts-teardown ms=$waited")
            } finally {
                synchronized(trackLock) { teardownActive = false }
            }
        }
    }

    override fun stop() { stopStreaming() }
    @Synchronized
    override fun shutdown() { tts = null }
}
