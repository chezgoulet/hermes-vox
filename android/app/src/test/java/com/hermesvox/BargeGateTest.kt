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
 * 0.4.0.2 adds the level-only escape: a SECOND caller accumulator
 * `sustainedLevelMs` (contiguous time above the raised floor*1.6 bar) that fires
 * regardless of VAD once it reaches `levelOnlyMs` — the escape rows below prove it
 * is additive and never blocks the existing vad-path rows.
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
        // 0.3.32.1 field log: real user speech lands rms 0.15-0.17, so the old 0.15
        // floor ate the user's own barge-in (echo levels 0.153-0.172 overlapped it).
        // Default lowered to 0.10 — still below real speech; the VAD agreement +
        // 200ms sustain remain the echo defense. assert 0.10, not 0.15.
        assertEquals(0.10f, BargeGate.DEFAULT_RMS_MIN, 0.0f)
        assertEquals(500, BargeGate.DEFAULT_GRACE_MS)
        assertEquals(200L, BargeGate.VAD_SUSTAIN_MS)
        assertEquals(350L, BargeGate.NO_VAD_SUSTAIN_MS)
        assertEquals(1.4f, BargeGate.NO_VAD_RMS_BOOST, 0.0f)
        assertEquals(1.6f, BargeGate.LEVEL_ONLY_BOOST, 0.0f)
        assertEquals(400L, BargeGate.DEFAULT_LEVEL_ONLY_MS)
    }

    // 0.4.0.2 level-only escape (field evidence: echo-dominant frames hold Silero
    // VAD false, so the VAD path starves even at the user's loud barge levels).
    // The escape is a SECOND, independent path: it fires on a SUSTAINED level at the
    // raised bar (rmsMin * 1.6 — the SHIPPED floor 0.10 => a ~0.16 bar) regardless
    // of VAD, once that level-only sustain reaches levelOnlyMs. rmsMin here is the
    // shipped 0.10 default so the rows sit on the real 0.16 raised bar.
    private val levelRmsMin = 0.10f

    @Test fun level_only_escape_fires_on_sustained_raised_bar_without_vad() {
        // 400ms at the raised bar (0.161 > 0.16) fires even though VAD says false
        assertTrue(BargeGate.decide(0.161f, vadSpeech = false, sustainedMs = 0L,
            sustainedLevelMs = 400L, rmsMin = levelRmsMin, vadAvailable = true, levelOnlyMs = 400L))
        // longer sustain at the same level also fires
        assertTrue(BargeGate.decide(0.165f, vadSpeech = false, sustainedMs = 0L,
            sustainedLevelMs = 450L, rmsMin = levelRmsMin, vadAvailable = true, levelOnlyMs = 400L))
    }

    @Test fun level_only_escape_does_not_fire_below_the_raised_bar() {
        // 0.155 < the 0.16 raised bar: no fire even at 450ms of level-only sustain
        // (and even though the VAD-path sustain would be long enough, vad=false blocks it)
        assertFalse(BargeGate.decide(0.155f, vadSpeech = false, sustainedMs = 450L,
            sustainedLevelMs = 450L, rmsMin = levelRmsMin, vadAvailable = true, levelOnlyMs = 400L))
        // level-only sustain short of levelOnlyMs never fires, however loud the frame
        assertFalse(BargeGate.decide(0.161f, vadSpeech = false, sustainedMs = 0L,
            sustainedLevelMs = 399L, rmsMin = levelRmsMin, vadAvailable = true, levelOnlyMs = 400L))
    }

    @Test fun level_only_escape_is_disabled_at_zero() {
        // levelOnlyMs = 0 is the escape hatch: no level-only fire, however loud+long
        assertFalse(BargeGate.decide(0.30f, vadSpeech = false, sustainedMs = 500L,
            sustainedLevelMs = 1000L, rmsMin = levelRmsMin, vadAvailable = true, levelOnlyMs = 0L))
    }

    @Test fun level_only_escape_is_additive_never_blocks_the_vad_path() {
        // a momentary loud spike with a SHORT level-only sustain does not escape...
        assertFalse(BargeGate.decide(0.30f, vadSpeech = false, sustainedMs = 500L,
            sustainedLevelMs = 100L, rmsMin = levelRmsMin, vadAvailable = true, levelOnlyMs = 400L))
        // ...but the same successful-barge frames still fire via the VAD path when
        // the VAD agrees (the escape must never veto an existing fire)
        assertTrue(BargeGate.decide(0.30f, vadSpeech = true, sustainedMs = 500L,
            sustainedLevelMs = 100L, rmsMin = 0.15f, vadAvailable = true, levelOnlyMs = 400L))
    }

    @Test fun degenerate_rms_min_never_fires() {
        assertFalse(BargeGate.decide(1.0f, vadSpeech = true, sustainedMs = 500, rmsMin = 0f, vadAvailable = true))
        assertFalse(BargeGate.decide(1.0f, vadSpeech = false, sustainedMs = 500, sustainedLevelMs = 1000L,
            rmsMin = 0f, vadAvailable = true, levelOnlyMs = 400L))
    }
}
