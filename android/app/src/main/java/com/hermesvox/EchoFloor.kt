package com.hermesvox

/**
 * EchoFloor — the barge floor during playback, measured from the reply's own echo
 * instead of assumed (0.9.1, docs/BARGE-WHY-0.7-WORKED.md).
 *
 * WHY. [BargeGate.DEFAULT_RMS_MIN] (0.10) and the 0.130 level bar were derived in
 * 0.4.0.3 from a residual echo of 0.081-0.095 at the mic, measured while Piper spoke
 * the reply. The floor sat just over that echo, so a person talking over the reply only
 * had to ADD a little energy to it: echo 0.088 plus a voice of 0.05 already reads 0.10.
 * 0.8.0 made Supertonic the default voice. It synthesizes ~6.5 dB quieter than Piper
 * (same text, the app's engine settings: active RMS 0.06 vs 0.12), and the 0.9.0 field
 * `event=barge-stat` lines show what is left at the mic during playback now: peak
 * 0.000-0.008, VAD 0%, for 110 of 121 seconds. The echo bed the floor was built to clear
 * is gone, and the owner's voice, ducked by the platform AEC while the reply plays,
 * reads 0.04-0.17 on its own — under a floor that no longer guards anything.
 *
 * WHAT. Measure the echo this line actually produces and put the floor over it:
 *
 *   learn   from every playback read where the reply is audibly playing (the loudest
 *           reply audio of the last [WINDOW_READS] reads, [SherpaTts.playbackRms] at the
 *           playback head, is at least [REF_ACTIVE]), the grace window included. Without
 *           the reference, the reply's gaps and stalls would teach a silence the next
 *           phrase does not keep. The VAD is NOT consulted: Silero can call exactly the
 *           loud echo reads speech, and leaving those out under-measures the echo.
 *   level   per [BLOCK_READS] learnt reads the block keeps its second-highest level; the
 *           echo level is the median of the last [BLOCKS] blocks (the max while fewer
 *           than three), so a person speaking through one or two blocks cannot raise it
 *           on their own.
 *   floor   [MARGIN] x the echo level, clamped to [rmsMin * MIN_FRACTION, rmsMin], for
 *           every playback read — gaps included.
 *
 * The floor is one number per line, not a read-by-read echo prediction. A prediction
 * from the playback head would have to know the output and input latencies to the read:
 * a per-read draft of this class self-cut in the replay whenever the echo arrived 128 ms
 * ahead of the head, at the start of a phrase after a gap. A constant over the measured
 * echo cannot be misaligned; BargeReplayTest keeps those early-echo cases (delay -2).
 *
 * The route-aware threshold falls out of the measurement: on a headset (or Supertonic on
 * this phone's speaker) the echo is near nothing and the floor goes to its minimum; with
 * Piper-era echo it stays at `barge_rms_min`. Nothing here reads the route.
 *
 * WHAT IT CANNOT DO, by construction:
 *  - raise the floor above `barge_rms_min`. A setup whose echo already beats the fixed
 *    floor self-cuts exactly as it did before; this only ever LOWERS the floor where the
 *    measurement says the echo is below it.
 *  - act before it has learnt. Until the first block (~1 s of audible reply, the grace
 *    window included) the floor is `barge_rms_min`, as before.
 *  - act without a reference. System TTS, the soul's clips and generation mode have no
 *    reference and keep `barge_rms_min`, as before. So does the no-VAD fallback: without
 *    the VAD vouching for speech, a low floor is only a noise trigger.
 *
 * A person talking long enough to fill three of the five blocks raises the measured level
 * with their voice — and the floor with it, up to `barge_rms_min` at most: the failure
 * mode is the old behaviour, not a self-cut.
 *
 * Pure JVM, no allocation per read. Owned by the capture thread; lives for one open line
 * (a route change rebuilds the line and relearns).
 */
internal class EchoFloor {

    companion object {
        /** Reads of reply audio the "is it playing" test looks back over (6 x 64 ms =
         *  384 ms): the playback head leads the speaker by the output latency, the mic
         *  lags it by the input latency plus a read, and the room adds its tail. */
        const val WINDOW_READS = 6

        /** Reply audio quieter than this (raw RMS over a read) is not playing: a read in
         *  a gap teaches nothing about the echo. Supertonic's speech reads 0.06-0.10. */
        const val REF_ACTIVE = 0.03f

        /** Learnt reads per block (~1 s of audible reply). */
        const val BLOCK_READS = 16

        /** Blocks the echo level is the median of. */
        const val BLOCKS = 5

        /** Headroom over the block-p90 echo level. The leaky sustain needs 43% of reads
         *  over the floor; at 1.5x its p90 the echo is over it on almost none. */
        const val MARGIN = 1.5f

        /** The lowest floor, as a fraction of `barge_rms_min` (0.030 at the default). The
         *  echo left at the mic during Supertonic playback peaks at 0.008 (L2); the owner's
         *  voice under playback reads 0.04-0.17. The VAD path still needs VAD agreement. */
        const val MIN_FRACTION = 0.3f

        /** The level-only escape ignores the VAD, so its bar never drops under this
         *  fraction of `barge_rms_min` (0.080): room noise and clatter must not fire it. */
        const val LEVEL_ONLY_MIN_FRACTION = 0.8f

        /**
         * RMS of the [win] samples before [head] across the phrase now on the track
         * ([cur], starting at absolute sample [curBase]) and the one before it ([prev],
         * at [prevBase]) — the track's buffer still holds the previous phrase's tail when
         * the next phrase is handed over. Samples covered by neither count as silence.
         */
        fun windowRms(head: Int, win: Int, cur: FloatArray?, curBase: Int, prev: FloatArray?, prevBase: Int): Float {
            if (win <= 0 || head <= 0) return 0f
            val from = maxOf(0, head - win)
            var acc = 0.0
            for (i in from until head) {
                val v = when {
                    cur != null && i >= curBase && i - curBase < cur.size -> cur[i - curBase]
                    prev != null && i >= prevBase && i - prevBase < prev.size -> prev[i - prevBase]
                    else -> 0f
                }
                acc += v * v
            }
            return Math.sqrt(acc / (head - from)).toFloat()
        }
    }

    private val recent = FloatArray(WINDOW_READS)
    private var recentAt = 0

    private val blockLevels = FloatArray(BLOCKS)
    private var blocks = 0
    private var blockAt = 0
    private var top1 = 0f
    private var top2 = 0f
    private var blockReads = 0

    /** True once one block has been learnt; before that the floor is `barge_rms_min`. */
    val calibrated: Boolean get() = blocks > 0

    /** The measured echo level at the mic during playback, or 0 before calibration. */
    val echoLevel: Float get() {
        if (blocks == 0) return 0f
        val l = blockLevels.copyOf(blocks)
        l.sort()
        return if (blocks < 3) l[blocks - 1] else l[blocks / 2]
    }

    /** Feed this read's reply level at the playback head; true when the reply is audibly
     *  playing (anything over [REF_ACTIVE] in the last [WINDOW_READS] reads). */
    fun playing(ref: Float): Boolean {
        recent[recentAt] = ref
        recentAt = (recentAt + 1) % WINDOW_READS
        for (r in recent) if (r >= REF_ACTIVE) return true
        return false
    }

    /** No reply is playing: forget the window so the next reply starts from its own audio. */
    fun clearReference() { recent.fill(0f) }

    /** Learn from a read taken while the reply is audibly playing. */
    fun learn(mic: Float) {
        if (mic > top1) { top2 = top1; top1 = mic } else if (mic > top2) top2 = mic
        if (++blockReads < BLOCK_READS) return
        blockLevels[blockAt] = top2
        blockAt = (blockAt + 1) % BLOCKS
        if (blocks < BLOCKS) blocks++
        top1 = 0f; top2 = 0f; blockReads = 0
    }

    /** The VAD-path floor during playback. */
    fun floor(rmsMin: Float): Float {
        if (!calibrated) return rmsMin
        return (MARGIN * echoLevel).coerceIn(rmsMin * MIN_FRACTION, rmsMin)
    }

    /** The level-only escape's bar over [floor]. */
    fun levelBar(rmsMin: Float, floor: Float): Float =
        maxOf(floor * BargeGate.LEVEL_ONLY_BOOST, rmsMin * LEVEL_ONLY_MIN_FRACTION)
}
