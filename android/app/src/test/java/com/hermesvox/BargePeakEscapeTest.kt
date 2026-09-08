package com.hermesvox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.6.9 — the peak-crest escape (the ER barge-in field failure, gen=5
 * 18:33:18.935: a soft short "hey—" peaked 0.110 in ONE 64ms frame — over the
 * 0.100 floor, under the 0.130 raised bar — and both accumulators drained
 * because the crest lived in a single read; the reply ran 9 more seconds).
 *
 * The contract: the level-only escape may be earned by the RUN PEAK clearing
 * the raised bar, but it still requires the SAME net-occupancy sustain — so
 * residual echo (peaks 0.081-0.095, under the bar) cannot pass.
 */
class BargePeakEscapeTest {

    private val floor = 0.10f
    private val bar = floor * BargeGate.LEVEL_ONLY_BOOST   // 0.130

    /** The exact field sequence, replayed: one crest at 0.110 in a 64ms frame,
     *  then dips. Under the OLD rule (current-read only) this can NEVER fire —
     *  and the sustain must not either, or every crest would cut audio. */
    @Test fun a_single_soft_crest_below_the_bar_still_does_not_fire() {
        val d = BargeGate.decide(
            rms = 0.095f, vadSpeech = false, sustainedMs = 64L, rmsMin = floor,
            vadAvailable = true, levelOnlyMs = 200L, sustainedLevelMs = 64L,
            peakLevel = 0.110f)
        assertFalse("a sub-bar crest alone must not fire", d)
    }

    @Test fun repeated_soft_crests_earning_the_sustain_fire_even_when_vad_refuses() {
        // The fix's shape: the crest clears the bar (peak > 0.130) AND the escape
        // has banked its net occupancy — VAD refusing on the current read no
        // longer blocks it. This is the gen=5 case with a slightly firmer voice.
        val d = BargeGate.decide(
            rms = 0.095f, vadSpeech = false, sustainedMs = 64L, rmsMin = floor,
            vadAvailable = true, levelOnlyMs = 200L, sustainedLevelMs = 200L,
            peakLevel = 0.145f)
        assertTrue("peak-over-bar + sustain must fire despite vad=false", d)
    }

    @Test fun the_sustain_is_still_required_for_the_peak_escape() {
        // A crest over the bar with NO banked occupancy: residual echo can crest
        // (measured 0.081-0.095; a hot-clip scenario could crest higher) — the
        // sustain is the anti-self-cut defence and must hold.
        val d = BargeGate.decide(
            rms = 0.095f, vadSpeech = false, sustainedMs = 64L, rmsMin = floor,
            vadAvailable = true, levelOnlyMs = 200L, sustainedLevelMs = 0L,
            peakLevel = 0.145f)
        assertFalse("peak without sustain must not fire", d)
    }

    @Test fun echo_cannot_reach_the_bar_so_the_escape_stays_closed_for_it() {
        // The measured residual-echo band tops out at 0.095 — under even the
        // floor, far under the bar. The escape cannot open for it.
        val d = BargeGate.decide(
            rms = 0.081f, vadSpeech = false, sustainedMs = 500L, rmsMin = floor,
            vadAvailable = true, levelOnlyMs = 200L, sustainedLevelMs = 500L,
            peakLevel = 0.095f)
        assertFalse("residual echo must not barge", d)
    }

    @Test fun the_normal_vad_path_is_unchanged() {
        val d = BargeGate.decide(
            rms = 0.15f, vadSpeech = true, sustainedMs = 264L, rmsMin = floor,
            vadAvailable = true, levelOnlyMs = 200L, sustainedLevelMs = 0L,
            peakLevel = 0f)
        assertTrue(d)
    }
}
