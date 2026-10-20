package com.hermesvox

/**
 * BargeStat — the per-second duty-cycle witness for the barge drain (0.9.1).
 *
 * BargeGate is a leaky sustain: its discriminator is the DUTY CYCLE of reads over
 * the floor / raised bar, not any single level. The near-miss probe could not show
 * that: it is rate-limited to one line per 2 s and prints the FIRST read of a run,
 * so every 0.9.0 field near-miss reads `sustainedMs=64` whether the run that
 * followed held the bar for one frame or for five. A duty-cycle gate cannot be
 * tuned without the duty cycle, so the drain now feeds every checked read here and
 * emits one `event=barge-stat` line per [windowMs]:
 *
 *   frames, % of reads above the floor, % above the level-only bar, % with VAD
 *   speech, the window's peak and mean level — and (0.9.1) the mean floor the reads
 *   were judged against, which moves with the reply's echo ([EchoFloor]), and the
 *   peak reply level at the playback head (the echo reference; 0 = none).
 *
 * Pure JVM, no allocation per read. Owned by the capture thread.
 */
internal class BargeStat(private val windowMs: Long = 1000L) {

    data class Line(
        val frames: Int, val floorPct: Int, val barPct: Int, val vadPct: Int,
        val peak: Float, val mean: Float, val floor: Float = 0f, val refPeak: Float = 0f,
    ) {
        fun render(): String =
            "frames=$frames floorPct=$floorPct barPct=$barPct vadPct=$vadPct " +
                "peak=${"%.3f".format(peak)} mean=${"%.3f".format(mean)} " +
                "floor=${"%.3f".format(floor)} refPeak=${"%.3f".format(refPeak)}"
    }

    private var startedAt = -1L
    private var frames = 0
    private var aboveFloor = 0
    private var aboveBar = 0
    private var vad = 0
    private var peak = 0f
    private var sum = 0.0
    private var floorSum = 0.0
    private var refPeak = 0f

    /** One checked read. Returns the window's line when [windowMs] has elapsed
     *  (and starts the next window), else null. */
    fun add(level: Float, floor: Float, bar: Float, vadSpeech: Boolean, nowMs: Long, ref: Float = 0f): Line? {
        if (startedAt < 0L) startedAt = nowMs
        frames++
        if (level > floor) aboveFloor++
        if (level > bar) aboveBar++
        if (vadSpeech) vad++
        if (level > peak) peak = level
        sum += level
        floorSum += floor
        if (ref > refPeak) refPeak = ref
        if (nowMs - startedAt < windowMs) return null
        val line = Line(frames, pct(aboveFloor), pct(aboveBar), pct(vad), peak, (sum / frames).toFloat(),
            (floorSum / frames).toFloat(), refPeak)
        reset()
        return line
    }

    /** Drop a partial window (playback ended, the barge fired, grace began). */
    fun reset() {
        startedAt = -1L; frames = 0; aboveFloor = 0; aboveBar = 0; vad = 0; peak = 0f; sum = 0.0
        floorSum = 0.0; refPeak = 0f
    }

    private fun pct(n: Int): Int = if (frames == 0) 0 else (n * 100 + frames / 2) / frames
}
