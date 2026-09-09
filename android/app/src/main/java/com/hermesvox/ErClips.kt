package com.hermesvox

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * ErClips — the Tier-1 presence voice (0.6.7): a library of NATURAL nonverbal
 * acknowledgment sounds (mm / mm-hm / soft breath) shipped as raw PCM assets
 * and played DIRECTLY — never through Piper.
 *
 * Why this exists (the field lesson): Piper is a prose reader with
 * sentence-level prosody; a two-character interjection gives it nothing to
 * ride and reads as flat/robotic ("Mm?" — rejected by the field). A
 * text-TTS stack gets natural nonverbals one honest way: DON'T synthesize
 * them at call time — ship them as recorded/rendered audio. The clips are
 * the same voice family as the reply (single speaker), so the seam
 * glue→reply stays in one timbre.
 *
 * FORMAT: raw 16-bit mono PCM at 22050Hz (Piper's rate) in assets/clips/.
 * A clip is at most ~1.5s. Names carry their tone: mm_warm.pcm, mhm.pcm,
 * breath_soft.pcm, … (the player scans the dir; unknown names are ignored).
 *
 * ISOLATION (the 0.6.5 lesson): the clip player NEVER touches the reply's
 * track, the fence, or streamWritten — it owns a private one-shot AudioTrack
 * per clip (built at the clip's rate, released on completion). A clip cannot
 * corrupt a streamed reply because it shares nothing with it.
 */
object ErClips {

    private const val SR = 22050

    /** The pick. kind: warm (ack) | neutral (working) | lag (fail-soft) —
     *  the caller's context picks which family; rotation is by count.
     *  ((rotate % 3) + 3) % 3 keeps negative rotate values in range. */
    fun clipFor(kind: String, rotate: Int): String = when (kind) {
        "lag" -> listOf("mm_slow", "huh_letme", "breath_long")[((rotate % 3) + 3) % 3]
        "warm" -> listOf("mm_warm", "mhm_warm", "mm_hm")[((rotate % 3) + 3) % 3]
        else -> listOf("mm", "mhm", "mm_soft")[((rotate % 3) + 3) % 3]
    }

    /** Read a clip from assets as FloatArray PCM (mono, [SR]). Returns null
     *  when the asset is missing — the caller falls back to silence/motion. */
    fun load(context: Context, name: String): FloatArray? = try {
        val bytes = context.assets.open("clips/$name.pcm").use { it.readBytes() }
        val n = bytes.size / 2
        FloatArray(n) { i ->
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            (((hi shl 8) or lo).toShort()) / 32768f
        }
    } catch (_: Throwable) { null }

    /** List available clip names (for diagnostics). */
    fun available(context: Context): List<String> = try {
        context.assets.list("clips")?.map { it.removeSuffix(".pcm") } ?: emptyList()
    } catch (_: Throwable) { emptyList() }

    /** Playback USAGE mirrors SherpaTts's `tts_voice_usage` kill-switch
     *  (default false = USAGE_MEDIA — the 2026-09-06 field A/B default). */
    private fun voiceUsageMedia(context: Context): Boolean =
        !context.getSharedPreferences("hv", Context.MODE_PRIVATE)
            .getBoolean("tts_voice_usage", false)

    /** Play a clip on a PRIVATE track. Fire-and-forget; never touches the
     *  stream fence/track (the 0.6.5 isolation lesson is structural here).
     *  Returns false when the clip is missing (caller falls back). */
    fun play(context: Context, name: String): Boolean {
        val pcm = load(context, name) ?: return false
        val usage = if (voiceUsageMedia(context)) AudioAttributes.USAGE_MEDIA
        else AudioAttributes.USAGE_VOICE_COMMUNICATION
        Thread {
            var track: AudioTrack? = null
            try {
                val minBuf = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                val t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(maxOf(minBuf, SR / 2))
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
                track = t
                t.play()
                var at = 0
                while (at < pcm.size) {
                    val n = minOf(SR / 4, pcm.size - at)   // ~250ms slices
                    t.write(pcm, at, n, AudioTrack.WRITE_BLOCKING)
                    at += n
                }
                // Let the tail drain, then release — private track, private lifecycle.
                Thread.sleep(150)
            } catch (_: Throwable) {
            } finally {
                try { track?.stop() } catch (_: Throwable) {}
                try { track?.release() } catch (_: Throwable) {}
            }
        }.apply {
            isDaemon = true
            // 0.6.7 gate-fix: `name` is Thread.name — a val-overload clash inside
            // apply{}; set it explicitly on the thread, not via apply's receiver.
        }.let { t -> t.name = "er-clip"; t }.start()
        return true
    }
}
