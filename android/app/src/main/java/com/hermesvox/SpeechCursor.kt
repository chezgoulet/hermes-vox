package com.hermesvox

/**
 * SpeechCursor — the pure "how much of the reply has actually been SPOKEN" rule
 * (0.5.0-previewA speech-locked transcript).
 *
 * The transcript used to be driven by DELTA ARRIVAL: the SSE stream hands over a
 * whole reply in ~1s while Piper renders it to audio over 8-15s, so the crawl ran
 * three or four sentences ahead of the voice. The cursor replaces arrival-time with
 * PLAYBACK-time: each phrase handed to the engine is recorded as a segment
 * (textLen chars, samples of audio), in the order it is written to the playback
 * track, and the reveal boundary is derived from the track's playback head.
 *
 * The rule is a walk over the segments:
 *   - every segment the head has fully passed contributes all of its chars,
 *   - the segment the head is inside contributes a linear interpolation
 *     (samplesInto / segSamples) * segTextLen — one phrase is short enough that
 *     within-phrase timing is not worth modelling,
 *   - past the end clamps to the total (never reveals what was never fed).
 *
 * Monotonic in samplesPlayed and pure (same samples in -> same chars out), which
 * is what makes the freeze-on-cancel guard in VoiceController honest: hold the
 * sample count and the revealed text cannot creep forward.
 *
 * Pure JVM — no Android deps, no AudioTrack — so it is unit-proven off-device.
 */
class SpeechCursor(
    private val charSegments: List<Int>,
    private val sampleSegments: List<Int>,
) {
    /** Total chars fed to the engine so far (the reveal ceiling). */
    val totalChars: Int = charSegments.sum()
    /** Total samples fed to the engine so far. */
    val totalSamples: Int = sampleSegments.sum()

    /** Chars the voice has actually uttered by [samplesPlayed] of playback. */
    fun charsSpoken(samplesPlayed: Int): Int {
        if (charSegments.isEmpty()) return 0
        if (samplesPlayed <= 0) return 0
        var samplesBefore = 0
        var chars = 0
        for (i in charSegments.indices) {
            val segSamples = sampleSegments.getOrElse(i) { 0 }
            val segChars = charSegments[i]
            if (samplesPlayed >= samplesBefore + segSamples) {
                // fully covered — the phrase has been said
                chars += segChars
                samplesBefore += segSamples
                continue
            }
            // partial — interpolate inside the phrase being spoken right now
            if (segSamples > 0) {
                val into = (samplesPlayed - samplesBefore).toLong()
                chars += ((into * segChars) / segSamples).toInt()
            }
            return chars.coerceIn(0, totalChars)
        }
        return totalChars   // past the end -> clamp, never over-reveal
    }

    companion object {
        /** Build from the controller's ordered (textLen, samples) accounting. */
        fun of(stats: List<Pair<Int, Int>>): SpeechCursor =
            SpeechCursor(stats.map { it.first }, stats.map { it.second })
    }
}

/**
 * PlaybackClock — the device-reliability fallback for the cursor's sample source.
 *
 * `AudioTrack.getPlaybackHeadPosition()` is the truth when it works, but it is not
 * uniformly reliable across devices/HALs (some report 0 until the track has been
 * playing a while, some on offloaded paths barely move). The rule: trust the head
 * the moment it EVER advances; only if it has never moved after [STALL_MS] of
 * audio being on the wire do we fall back to a wall-clock estimate at the model's
 * sample rate. A head that moves and then stops is NOT a fallback case — that is a
 * real stall (underrun/pause), and freezing the reveal with the voice is correct.
 */
object PlaybackClock {
    /** How long a never-advancing head is tolerated before the wall clock takes over. */
    const val STALL_MS = 700L

    /**
     * @param head            playbackHeadPosition, in samples (0 if unavailable)
     * @param headEverMoved   has the head reported >0 at any point this turn
     * @param msSinceAudio    ms since the first phrase was handed to the engine
     * @param sampleRate      the voice model's actual rate (0 = unknown)
     */
    fun samples(head: Int, headEverMoved: Boolean, msSinceAudio: Long, sampleRate: Int): Int {
        if (headEverMoved || head > 0) return head
        if (sampleRate <= 0 || msSinceAudio < STALL_MS) return 0
        return ((msSinceAudio * sampleRate) / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
