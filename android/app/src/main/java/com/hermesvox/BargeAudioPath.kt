package com.hermesvox

/**
 * BargeAudioPath — the per-frame audio path of the single-capture barge-in drain,
 * extracted from VoiceController so it can be driven deterministically off-device
 * (#42).
 *
 * The production loop reads 1024 PCM-16 shorts from the live AudioRecord, converts
 * them to floats, feeds the VAD, and then does this on every read:
 *
 *   RMS level -> BargeGate's leaky sustain accumulators -> the double-gate
 *   decision -> BargeCarry history / onset / fire.
 *
 * That last chain is the part that decides "the user is talking over the reply"
 * and "keep every word from the onset minus pre-roll". It used to be inline in a
 * 200-line loop that needed a microphone, a device VAD and wall-clock time to
 * exercise. Here the caller supplies the frame, the VAD verdict, and a monotonic
 * clock (`SystemClock.uptimeMillis()` in the loop; a frame counter in tests), so
 * a synthetic signal can be pushed through the SAME code the app runs.
 *
 * No Android deps. The grace window and the `!speaking && !turnInFlight` skip
 * state stay in the caller (they depend on playback/turn state, not audio), and
 * call [hold] for those reads — exactly the reset + history-only push the loop
 * did inline.
 */
internal class BargeAudioPath(
    private val sampleRate: Int,
    private val rmsMin: Float,
    private val vadAvailable: Boolean,
    private val levelOnlyMs: Long,
    private val carry: BargeCarry,
) {

    /** The active RMS floor: rmsMin with VAD, rmsMin * NO_VAD_RMS_BOOST without. */
    val floor: Float = if (vadAvailable) rmsMin else rmsMin * BargeGate.NO_VAD_RMS_BOOST

    /** The level-only escape's own (raised) bar. */
    val levelFloor: Float = floor * BargeGate.LEVEL_ONLY_BOOST

    /** The escape's cap admits a slider set above the default ceiling. */
    private val levelOnlyCap = maxOf(BargeGate.SUSTAIN_CAP_MS, levelOnlyMs)

    var sustainedMs: Long = 0L
        private set
    var sustainedLevelMs: Long = 0L
        private set
    var peakRms: Float = 0f
        private set

    /** True once the barge decision fired for this turn. Latched: one cancel only. */
    var fired: Boolean = false
        private set

    private var lastVadSpeechAt = 0L

    /** One read's worth of telemetry + the decision, for logging and the caller. */
    data class Frame(
        val level: Float,
        val vadSpeech: Boolean,
        val fired: Boolean,
        val sustainedMs: Long,
        val sustainedLevelMs: Long,
        val peakRms: Float,
        val nearFloor: Float,
        /** The near-miss probe condition; the caller applies `speaking` + rate limiting. */
        val nearMiss: Boolean,
    )

    /**
     * One pre-fire read: RMS, carry history/onset, leaky sustains, the barge decision.
     * On the deciding read the carry is frozen ([BargeCarry.fire]) and [fired] latches.
     */
    fun process(frames: FloatArray, vadSpeech: Boolean, nowMs: Long): Frame {
        var acc = 0.0
        for (f in frames) acc += f * f
        val level = Math.sqrt(acc / frames.size)

        // #12: onset tracking for the carry — speech-like = the VAD agrees or the
        // level clears the barge floor. Pushed BEFORE the decision so the carry
        // includes the read that fired it.
        carry.push(frames, vadSpeech || level > floor)

        val frameMs = frames.size * 1000L / sampleRate
        sustainedMs = BargeGate.accumulate(sustainedMs, level > floor, frameMs)
        sustainedLevelMs = BargeGate.accumulate(sustainedLevelMs, level > levelFloor, frameMs, levelOnlyCap)
        if (vadSpeech) lastVadSpeechAt = nowMs
        val msSinceVadSpeech = if (lastVadSpeechAt == 0L) Long.MAX_VALUE else nowMs - lastVadSpeechAt
        val nearFloor = floor * 0.7f

        if (BargeGate.decide(level.toFloat(), if (vadAvailable) vadSpeech else null, sustainedMs,
                rmsMin, vadAvailable, levelOnlyMs, sustainedLevelMs, msSinceVadSpeech, peakLevel = peakRms)) {
            fired = true
            carry.fire()   // #12: freeze onset-minus-pre-roll .. now; later reads keep appending
            sustainedMs = 0L
            sustainedLevelMs = 0L
            return Frame(level.toFloat(), vadSpeech, true, sustainedMs, sustainedLevelMs, peakRms,
                nearFloor, nearMiss = false)
        }

        // T1 CAUSE C probe state: peak of the current near-floor run, and the
        // condition the caller rate-limits into event=barge-nearmiss.
        if (level >= nearFloor) { if (level > peakRms) peakRms = level.toFloat() } else peakRms = 0f
        val nearMiss = sustainedMs > 0L || sustainedLevelMs > 0L || (vadSpeech && level >= nearFloor)
        return Frame(level.toFloat(), vadSpeech, false, sustainedMs, sustainedLevelMs, peakRms, nearFloor, nearMiss)
    }

    /** After the barge fired: keep every word the user is still saying. */
    fun keep(frames: FloatArray) {
        carry.push(frames, true)
    }

    /**
     * A read that must not be checked (playback grace, or `!speaking && !turnInFlight`):
     * drain the accumulators and keep the audio as history only (pre-roll), never an
     * onset. Matches the loop's inline reset exactly — peakRms is deliberately NOT
     * reset there.
     */
    fun hold(frames: FloatArray) {
        sustainedMs = 0L
        sustainedLevelMs = 0L
        lastVadSpeechAt = 0L
        carry.push(frames, false)
    }
}
