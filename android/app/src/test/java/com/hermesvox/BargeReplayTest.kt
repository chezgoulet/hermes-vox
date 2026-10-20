package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 0.9.1 — the off-device barge replay: (level, vad) sequences through the production
 * [BargeAudioPath] (RMS -> BargeGate's leaky sustains -> decide -> BargeCarry), one
 * 64 ms read at a time, clock = read index. No microphone, no VAD model, no device.
 *
 * WHAT THE INPUTS ARE. Every level is per 64 ms read. Two kinds of input:
 *
 *  - FIELD (0.9.0 Pixel 7 log, 2026-10-20): the three near-miss run peaks 0.113 /
 *    0.160 / 0.197 at floor 0.100, bar 0.130, all `vad=false`; the residual-echo band
 *    measured on this single-capture AEC path, 0.081-0.095 (BargeGate class doc).
 *  - MODELLED: syllabic speech envelopes at 3-6 Hz (the conversational syllable-rate
 *    range), peak in the field's barge band, mixed in power with a reply-echo bed at
 *    the measured residual level. Two shapes: a raised cosine (only the crest frames
 *    clear a bar — the pessimistic case for detection) and a vowel-plateau envelope
 *    (45% at the peak, 15% cosine ramps, 25% trough — closer to voiced speech).
 *    Ten phase offsets against the 64 ms read grid per case. These are models, not
 *    recordings; they encode the field's AMPLITUDES, not a field waveform.
 *
 * VAD is held false throughout the speech cases: that is the field's state at every
 * near-miss (echo-dominant frames read as non-speech to Silero), so only the
 * level-only escape can fire. Any VAD agreement only makes firing easier.
 */
class BargeReplayTest {

    private val sr = 16000
    private val read = 1024
    private val frameMs = read * 1000L / sr    // 64
    private val oldLeak = 96L                  // 0.4.0.3 .. 0.9.0

    // ---- signal models (per-read level, seconds -> level) ----

    private fun cosEnv(t: Double, rate: Double, peak: Double, trough: Double) =
        trough + (peak - trough) * (0.5 + 0.5 * cos(2 * PI * rate * t))

    private fun plateauEnv(t: Double, rate: Double, peak: Double, trough: Double): Double {
        val ph = (t * rate) % 1.0
        return when {
            ph < 0.45 -> peak
            ph < 0.60 -> trough + (peak - trough) * (0.5 + 0.5 * cos(PI * (ph - 0.45) / 0.15))
            ph < 0.85 -> trough
            else -> trough + (peak - trough) * (0.5 - 0.5 * cos(PI * (ph - 0.85) / 0.15))
        }
    }

    /** The reply's residual echo: TTS syllables at ~4.3 Hz, crest at the measured top. */
    private fun echoBed(t: Double, peak: Double = 0.090) = cosEnv(t + 0.37, 4.3, peak, 0.03)

    private enum class Shape { COS, PLATEAU }

    /** [leadReads] of echo-only playback, then the user's voice over the echo. */
    private fun bargeLevels(shape: Shape, rate: Double, peak: Double, phase: Double,
                            leadReads: Int, speechReads: Int): List<Double> =
        List(leadReads + speechReads) { i ->
            val t = i * frameMs / 1000.0 + phase
            val e = echoBed(t)
            if (i < leadReads) e else {
                val s = when (shape) {
                    Shape.COS -> cosEnv(t, rate, peak, 0.02)
                    Shape.PLATEAU -> plateauEnv(t, rate, peak, 0.02)
                }
                sqrt(s * s + e * e)
            }
        }

    /** One 64 ms read whose RMS is exactly [level] (a +/- level square wave). */
    private fun frameAt(level: Double) = FloatArray(read) { if (it % 2 == 0) level.toFloat() else -level.toFloat() }

    private class Outcome(val fireRead: Int?, val leadMs: Long)

    private fun replay(levels: List<Double>, leakMs: Long, vad: (Int) -> Boolean = { false }): Outcome {
        val carry = BargeCarry(sr)   // production defaults: 300 ms pre-roll, 2 s history
        val path = BargeAudioPath(sr, BargeGate.DEFAULT_RMS_MIN, vadAvailable = true,
            levelOnlyMs = BargeGate.DEFAULT_LEVEL_ONLY_MS, carry = carry, leakMs = leakMs)
        levels.forEachIndexed { i, lv ->
            if (path.process(frameAt(lv), vad(i), nowMs = 1 + i * frameMs).fired) return Outcome(i, carry.leadMs)
        }
        return Outcome(null, 0L)
    }

    private val lead = 20                                  // 1.28 s of echo-only playback first
    private val speech = (3000 / frameMs).toInt()          // 3 s of the user talking over it

    /** Fires/10 and the worst time-to-fire (ms from speech onset) for one case. */
    private fun sweep(shape: Shape, rate: Double, peak: Double, leakMs: Long,
                      checkCarry: Boolean = true): Pair<Int, Long> {
        var fired = 0
        var worst = 0L
        for (p in 0 until 10) {
            val o = replay(bargeLevels(shape, rate, peak, phase = p / 10.0, lead, speech), leakMs)
            val at = o.fireRead ?: continue
            assertTrue("fired on echo before the user spoke ($shape $rate Hz $peak, read $at)", at >= lead)
            val ttf = (at - lead + 1) * frameMs
            // The carry must still hold the utterance's onset: lead >= time since onset.
            if (checkCarry)
                assertTrue("carry lost the onset ($shape $rate Hz $peak: ttf=$ttf leadMs=${o.leadMs})", o.leadMs >= ttf)
            fired++
            worst = maxOf(worst, ttf)
        }
        return fired to worst
    }

    private val rates = listOf(3.0, 4.0, 5.0, 6.0)

    // ---- 1. the field near-misses ----

    @Test fun field_near_misses_as_logged_cannot_fire_and_must_not() {
        // What the log actually shows: a run's FIRST read (sustainedMs=64), then the
        // probe is rate-limited for 2 s. Read literally — one crest read, then the level
        // falls back into the echo band — it is indistinguishable from an echo crest, and
        // no leaky 200 ms sustain fires on it, at either leak. So these three lines alone
        // do NOT prove a missed barge; what the gate does with a voice whose crests reach
        // those peaks is the envelope sweep below.
        for (peak in listOf(0.113, 0.160, 0.197)) {
            val levels = List(5) { 0.05 } + List(4) { listOf(peak, 0.08, 0.07, 0.06, 0.09, 0.05) }.flatten()
            for (leak in listOf(oldLeak, BargeGate.SUSTAIN_LEAK_MS))
                assertNull("lone crest $peak fired at leak $leak", replay(levels, leak).fireRead)
        }
    }

    // ---- 2. a person talking over the reply ----

    @Test fun plateau_speech_in_the_field_band_fires_every_time_within_1500ms() {
        for (peak in listOf(0.13, 0.16, 0.21)) for (rate in rates) {
            val (fired, worst) = sweep(Shape.PLATEAU, rate, peak, BargeGate.SUSTAIN_LEAK_MS)
            assertEquals("plateau $rate Hz peak $peak fired $fired/10", 10, fired)
            assertTrue("plateau $rate Hz peak $peak worst ttf $worst ms", worst <= 1500L)
        }
    }

    @Test fun crest_only_speech_at_the_fired_level_fires_every_time() {
        // The field's one playback barge fired at rms=0.208. A voice at that level whose
        // frames clear the bar only at crests (raised cosine) must fire at every rate.
        for (rate in rates) {
            val (fired, worst) = sweep(Shape.COS, rate, 0.21, BargeGate.SUSTAIN_LEAK_MS)
            assertEquals("cos $rate Hz peak 0.21 fired $fired/10", 10, fired)
            assertTrue("cos $rate Hz peak 0.21 worst ttf $worst ms", worst <= 2000L)
        }
    }

    @Test fun the_old_96ms_leak_misses_the_same_speech() {
        // The regression record: the identical inputs through the 0.9.0 constant.
        // Plateau speech at 0.16 (the middle near-miss) at 3 and 6 Hz, and crest-only
        // speech at 0.21 at 3/4/6 Hz, mostly never fire inside 3 s.
        assertTrue(sweep(Shape.PLATEAU, 3.0, 0.16, oldLeak, checkCarry = false).first <= 2)
        assertTrue(sweep(Shape.PLATEAU, 6.0, 0.16, oldLeak, checkCarry = false).first <= 5)
        for (rate in listOf(3.0, 4.0, 6.0))
            assertTrue(sweep(Shape.COS, rate, 0.21, oldLeak, checkCarry = false).first <= 2)
    }

    @Test fun a_late_fire_still_carries_the_onset() {
        // The 2 s history ring lost the start of the sentence whenever the fire came
        // more than ~1.7 s after the onset (seen above on the old leak: ttf 2,688 ms,
        // carry 2,000 ms). The 4 s ring keeps it for any fire this replay produces.
        val (fired, worst) = sweep(Shape.PLATEAU, 3.0, 0.16, oldLeak)   // checkCarry = true
        assertTrue("expected at least one late fire to exercise the ring", fired >= 1 && worst > 1700L)
    }

    @Test fun crest_only_speech_at_016_is_still_unreliable_and_this_says_so() {
        // Not fixed, by choice: a voice that clears the 0.130 bar ONLY at 0.16 crests
        // with VAD false sits at the edge of the new 0.43 break-even. Lowering the leak
        // further (32 ms) would fix it but halves the echo margin (stress test below).
        val fired = rates.sumOf { sweep(Shape.COS, it, 0.16, BargeGate.SUSTAIN_LEAK_MS).first }
        assertTrue("cos 0.16 fired $fired/40", fired in 10..35)
    }

    // ---- 3. the reply's own echo must never fire ----

    private fun echoOnly(shape: Shape, peak: Double, phase: Double, reads: Int): List<Double> =
        List(reads) { i ->
            val t = i * frameMs / 1000.0 + phase
            when (shape) {
                Shape.COS -> cosEnv(t, 4.3, peak, 0.03)
                Shape.PLATEAU -> plateauEnv(t, 4.3, peak, 0.03)
            }
        }

    @Test fun measured_echo_band_never_fires_in_two_minutes() {
        val reads = (120_000 / frameMs).toInt()
        for (peak in listOf(0.081, 0.095, 0.12)) for (shape in Shape.values()) for (p in 0 until 7)
            assertNull("echo $shape $peak fired", replay(echoOnly(shape, peak, p / 7.0, reads), BargeGate.SUSTAIN_LEAK_MS).fireRead)
    }

    @Test fun crest_echo_well_above_the_measured_band_still_never_fires() {
        // Stress: crest-shaped echo up to 0.17 — 1.8x the measured top, the AEC-less
        // second-recorder era's level — at three TTS syllable rates.
        val reads = (120_000 / frameMs).toInt()
        for (peak in listOf(0.13, 0.15, 0.17)) for (rate in listOf(3.5, 4.3, 5.5)) for (p in 0 until 7) {
            val levels = List(reads) { cosEnv(it * frameMs / 1000.0 + p / 7.0, rate, peak, 0.03) }
            assertNull("cos echo $peak @ $rate Hz fired", replay(levels, BargeGate.SUSTAIN_LEAK_MS).fireRead)
        }
    }

    @Test fun break_even_duty_is_documented() {
        // 48 ms leak vs 64 ms reads: break-even duty 48/(64+48) = 0.43.
        val duty = BargeGate.SUSTAIN_LEAK_MS.toDouble() / (frameMs + BargeGate.SUSTAIN_LEAK_MS)
        assertEquals(0.43, duty, 0.005)
        // Sanity on the model: a raised cosine from 0.02 to 0.21 spends
        // acos(2*(0.13-0.02)/0.19 - 1)/pi = 0.45 of its time over the 0.130 bar — under
        // the old 0.6 break-even (why the old gate could not see it), over the new 0.43.
        val crestDuty = acos(2 * (0.13 - 0.02) / 0.19 - 1) / PI
        assertEquals(0.45, crestDuty, 0.005)
        assertTrue(crestDuty < 0.6 && crestDuty > duty)
    }
}
