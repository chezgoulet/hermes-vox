package com.hermesvox

/**
 * BargeCarry — keeps the words that interrupt a turn (#12 "the vanish").
 *
 * The defect, traced in VoiceController's single-capture drain: every frame the
 * drain read while the turn gate was locked was DISCARDED ("Frames are DISCARDED
 * (never appended to seg)"). When the barge fired and the gate released, the loop
 * then (1) reset the VAD, (2) STOPPED the recorder — throwing away everything it
 * had buffered — and (3) slept a 450 ms post-turn cooldown before a fresh
 * segmentation began from silence. So the interrupting utterance lost its first
 * ~200-400 ms (the barge sustain) + the main-queue hop + 450 ms + the VAD's
 * re-detection latency. A short barge ("wait, stop — what time is it?") is
 * finished before the mic reopens: the words never became a turn. ER's semantic
 * barge added a second path: a CANCEL verdict transcribed the utterance only to
 * classify it, then dropped it.
 *
 * The rule: the drain [push]es every frame it reads into a short history ring and
 * tracks the onset of the current speech-like run (VAD speech or level above the
 * barge floor). When the barge decision fires, [fire] freezes the ring from that
 * onset minus [preRollMs] and every later frame is appended — the recorder keeps
 * running. When the gate releases for anything but a HOLD, [shouldSeed] says the
 * carry becomes the START of the next segment (in speech, no recorder stop, no
 * cooldown), so the interrupting utterance is transcribed whole as the next turn.
 *
 * Pure JVM (no android.*), unit-tested in BargeCarryTest. Not thread-safe: owned
 * by the capture thread.
 */
class BargeCarry(
    private val sampleRate: Int,
    private val preRollMs: Int = 300,
    historyMs: Int = 2_000,
    maxMs: Int = 30_000,
    private val onsetQuietMs: Int = 400,
) {
    private val ring = FloatArray(maxOf(1, sampleRate * historyMs / 1000))
    private var written = 0L            // samples ever pushed into the ring (pre-fire)
    private var onsetAt = -1L           // absolute index where the current speech run began
    private var quiet = 0               // samples since the run last looked like speech
    private val maxSamples = sampleRate.toLong() * maxMs / 1000
    private var carry: FloatArray? = null
    private var carryLen = 0
    private var leadSamples = 0         // carried audio from BEFORE the fire

    val fired: Boolean get() = carry != null
    /** Carried audio length (ms) — for the event=barge-carry log. */
    val carriedMs: Long get() = carryLen * 1000L / sampleRate
    /** How much of the carry precedes the barge decision (onset + pre-roll). */
    val leadMs: Long get() = leadSamples * 1000L / sampleRate

    /** Feed one drain read. Before [fire]: history + onset tracking. After: append. */
    fun push(frames: FloatArray, speechLike: Boolean) {
        if (carry != null) { append(frames, frames.size); return }
        if (speechLike) {
            if (onsetAt < 0) onsetAt = written
            quiet = 0
        } else if (onsetAt >= 0) {
            quiet += frames.size
            if (quiet.toLong() * 1000 > onsetQuietMs.toLong() * sampleRate) { onsetAt = -1; quiet = 0 }
        }
        for (f in frames) { ring[(written % ring.size).toInt()] = f; written++ }
    }

    /** The barge decision fired: freeze [onset - preRoll, now) as the carry head.
     *  With no tracked onset (the level-only escape can fire without one), keep
     *  the last second — the sustain that fired the barge lives there. */
    fun fire() {
        if (carry != null) return
        val pre = sampleRate.toLong() * preRollMs / 1000
        val oldest = maxOf(0L, written - ring.size)
        val from = maxOf(oldest, if (onsetAt >= 0) onsetAt - pre else written - sampleRate)
        carry = FloatArray(maxOf(sampleRate, (written - from).toInt()))
        carryLen = 0
        var i = from
        while (i < written) { append1(ring[(i % ring.size).toInt()]); i++ }
        leadSamples = carryLen
    }

    /** Take the carry (if [fired]) and reset for the next turn. */
    fun take(): FloatArray? {
        val c = carry?.copyOf(carryLen)
        reset()
        return c
    }

    fun reset() {
        written = 0L; onsetAt = -1L; quiet = 0
        carry = null; carryLen = 0; leadSamples = 0
    }

    private fun append(frames: FloatArray, n: Int) { for (k in 0 until n) append1(frames[k]) }

    private fun append1(v: Float) {
        if (carryLen >= maxSamples) return          // cap: the next segment is bounded too
        var c = carry!!
        if (carryLen == c.size) { c = c.copyOf(c.size * 2); carry = c }
        c[carryLen++] = v
    }

    companion object {
        /** Seed the next segment with the carry? Only when the barge actually fired
         *  (the double gate — sustain + VAD — vouched it is the user, not the
         *  reply's echo), the gate did release (not a stop/timeout exit), and the
         *  release was NOT an ER HOLD: a backchannel ("okay, go on") that let the
         *  mind keep working is, by design, not a new turn. */
        fun shouldSeed(fired: Boolean, gateReleased: Boolean, held: Boolean): Boolean =
            fired && gateReleased && !held
    }
}
