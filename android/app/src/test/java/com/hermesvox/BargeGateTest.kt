package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM proof of the single-capture barge double-gate (B1). The rule must
 * require SUSTAINED RMS above the active floor AND (when VAD is available) VAD
 * agreement, with the harsher RMS-only fallback when no VAD is present — so a
 * single loud frame (the app's own playback leaking through an AEC-less watch)
 * can never fire a barge-in.
 *
 * [BargeGate.decide] is stateless: `sustainedMs` is the contiguous time the
 * caller has measured the RMS above the ACTIVE floor (rmsMin with VAD,
 * rmsMin * NO_VAD_RMS_BOOST without), reset to 0 the instant a read drops below.
 */
class BargeGateTest {

    private val rmsMin = 0.15f

    @Test fun vad_available_requires_sustained_rms_above_min() {
        // 200ms of rms above the floor AND VAD speech -> fire
        assertTrue(BargeGate.decide(0.20f, vadSpeech = true, sustainedMs = 200, rmsMin = rmsMin, vadAvailable = true))
        // sustain short of the 200ms requirement -> no
        assertFalse(BargeGate.decide(0.20f, vadSpeech = true, sustainedMs = 199, rmsMin = rmsMin, vadAvailable = true))
    }

    @Test fun vad_available_requires_vad_agreement() {
        // sustained loud audio but VAD silent (e.g. the speaker's own echo) -> no
        assertFalse(BargeGate.decide(0.20f, vadSpeech = false, sustainedMs = 500, rmsMin = rmsMin, vadAvailable = true))
        // unknown VAD (null) is not agreement -> no
        assertFalse(BargeGate.decide(0.20f, vadSpeech = null, sustainedMs = 500, rmsMin = rmsMin, vadAvailable = true))
    }

    @Test fun vad_available_does_not_fire_below_the_rms_floor() {
        assertFalse(BargeGate.decide(0.10f, vadSpeech = true, sustainedMs = 500, rmsMin = rmsMin, vadAvailable = true))
    }

    @Test fun no_vad_fallback_uses_higher_floor_and_longer_sustain() {
        // rmsMin * 1.4 = 0.21; 350ms above that floor -> fire
        assertTrue(BargeGate.decide(0.22f, vadSpeech = null, sustainedMs = 350, rmsMin = rmsMin, vadAvailable = false))
        // just under the floor (0.20 < 0.21) -> no, even with a long sustain
        assertFalse(BargeGate.decide(0.20f, vadSpeech = null, sustainedMs = 1000, rmsMin = rmsMin, vadAvailable = false))
        // loud but not sustained long enough -> no
        assertFalse(BargeGate.decide(0.22f, vadSpeech = null, sustainedMs = 349, rmsMin = rmsMin, vadAvailable = false))
    }

    @Test fun defaults_match_the_spec() {
        assertEquals(0.15f, BargeGate.DEFAULT_RMS_MIN, 0.0f)
        assertEquals(500, BargeGate.DEFAULT_GRACE_MS)
        assertEquals(200L, BargeGate.VAD_SUSTAIN_MS)
        assertEquals(350L, BargeGate.NO_VAD_SUSTAIN_MS)
        assertEquals(1.4f, BargeGate.NO_VAD_RMS_BOOST, 0.0f)
    }

    @Test fun degenerate_rms_min_never_fires() {
        assertFalse(BargeGate.decide(1.0f, vadSpeech = true, sustainedMs = 500, rmsMin = 0f, vadAvailable = true))
    }
}
