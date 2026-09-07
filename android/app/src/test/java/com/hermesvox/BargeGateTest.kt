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
 * [BargeGate.decide] is stateless: `sustainedMs` is the time the caller has
 * measured the RMS above the ACTIVE floor (rmsMin with VAD, rmsMin *
 * NO_VAD_RMS_BOOST without). `sustainedLevelMs` is the second accumulator, against
 * the raised LEVEL_ONLY_BOOST bar, that fires regardless of VAD once it reaches
 * `levelOnlyMs` — the 0.4.0.2 escape.
 *
 * 0.4.0.3 changes what those accumulators MEAN: contiguous time became LEAKY net
 * occupancy ([BargeGate.accumulate]), because the field log proved the contiguous
 * form unreachable. The series rows at the bottom drive the real drain-loop math
 * (64ms frames, both accumulators, decide per frame) over the two shapes that
 * matter — a person talking over the reply, and the app's own echo envelope — so
 * the duty-cycle discriminator is proven end to end and not just at a single frame.
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
        // 0.4.0.3 SPEC REVERSAL (was 1.6f / 400L). The 1.6x bar (0.160) was derived
        // from the AEC-less second-recorder era. On the shipped single-capture path
        // the field log measures residual echo at 0.081-0.095 mid-reply and the
        // user's own barge peaking at only 0.172-0.182 — the bar sat in the top
        // decile of the signal it was supposed to detect, so it never fired once in
        // a whole session. Re-derived to 1.3x (0.130): above the measured echo band,
        // below the measured barge band. levelOnlyMs also changed UNIT (contiguous
        // -> leaky net occupancy) and is aligned with VAD_SUSTAIN_MS.
        assertEquals(1.3f, BargeGate.LEVEL_ONLY_BOOST, 0.0f)
        assertEquals(200L, BargeGate.DEFAULT_LEVEL_ONLY_MS)
        assertEquals(96L, BargeGate.SUSTAIN_LEAK_MS)
        assertEquals(800L, BargeGate.SUSTAIN_CAP_MS)
        assertEquals(250L, BargeGate.VAD_RECENT_MS)
    }

    // ---- 0.4.0.3 leaky accumulator (the primitive the drain loop now uses) ----

    @Test fun accumulate_charges_above_the_bar_and_leaks_below() {
        assertEquals(64L, BargeGate.accumulate(0L, above = true, frameMs = 64L))
        assertEquals(128L, BargeGate.accumulate(64L, above = true, frameMs = 64L))
        // a sub-bar read costs SUSTAIN_LEAK_MS, it no longer wipes the accumulator
        assertEquals(104L, BargeGate.accumulate(200L, above = false, frameMs = 64L))
        // ...and cannot drive it negative
        assertEquals(0L, BargeGate.accumulate(64L, above = false, frameMs = 64L))
        assertEquals(0L, BargeGate.accumulate(0L, above = false, frameMs = 64L))
    }

    @Test fun accumulate_is_capped_so_loud_stretches_cannot_bank_credit() {
        assertEquals(BargeGate.SUSTAIN_CAP_MS, BargeGate.accumulate(BargeGate.SUSTAIN_CAP_MS, above = true, frameMs = 64L))
        // an explicit cap above the default is honoured (levelOnlyMs slider up to 800+)
        assertEquals(1000L, BargeGate.accumulate(1000L, above = true, frameMs = 64L, capMs = 1000L))
    }

    @Test fun leak_break_even_duty_is_sixty_percent() {
        // the anti-self-cut invariant: 96ms leak vs 64ms frames. Below 60% duty at the
        // bar the accumulator must trend to zero, above it must trend up.
        assertEquals(0L, driftAfter(dutyAbove = 1, dutyBelow = 1, cycles = 12))   // 50% -> drains
        assertTrue(driftAfter(dutyAbove = 3, dutyBelow = 1, cycles = 12) > 0L)    // 75% -> charges
    }

    /** Net accumulator value after `cycles` of (dutyAbove above-bar, dutyBelow sub-bar) reads. */
    private fun driftAfter(dutyAbove: Int, dutyBelow: Int, cycles: Int): Long {
        var acc = 0L
        repeat(cycles) {
            repeat(dutyAbove) { acc = BargeGate.accumulate(acc, true, 64L) }
            repeat(dutyBelow) { acc = BargeGate.accumulate(acc, false, 64L) }
        }
        return acc
    }

    // ---- 0.4.0.3 VAD recency (the same-frame conjunction was brittle) ----

    @Test fun vad_agreement_may_be_recent_rather_than_this_frame() {
        // the log shows VAD agreeing on neighbouring frames, not the loud one; a
        // qualifying level with agreement inside VAD_RECENT_MS fires
        assertTrue(BargeGate.decide(0.20f, vadSpeech = false, sustainedMs = 200, rmsMin = rmsMin,
            vadAvailable = true, msSinceVadSpeech = 250L))
        // one millisecond past the window is stale -> no
        assertFalse(BargeGate.decide(0.20f, vadSpeech = false, sustainedMs = 200, rmsMin = rmsMin,
            vadAvailable = true, msSinceVadSpeech = 251L))
        // recency never substitutes for the level requirement
        assertFalse(BargeGate.decide(0.10f, vadSpeech = false, sustainedMs = 500, rmsMin = rmsMin,
            vadAvailable = true, msSinceVadSpeech = 0L))
        // ...nor for the sustain requirement
        assertFalse(BargeGate.decide(0.20f, vadSpeech = false, sustainedMs = 199, rmsMin = rmsMin,
            vadAvailable = true, msSinceVadSpeech = 0L))
    }

    // 0.4.0.2 level-only escape (field evidence: echo-dominant frames hold Silero
    // VAD false, so the VAD path starves even at the user's loud barge levels).
    // The escape is a SECOND, independent path: it fires on a SUSTAINED level at the
    // raised bar (rmsMin * LEVEL_ONLY_BOOST — the SHIPPED floor 0.10 => a 0.130 bar
    // as of 0.4.0.3) regardless of VAD, once that level-only sustain reaches
    // levelOnlyMs. rmsMin here is the shipped 0.10 default so the rows sit on the
    // real raised bar.
    private val levelRmsMin = 0.10f

    @Test fun level_only_escape_fires_on_sustained_raised_bar_without_vad() {
        // 400ms at the raised bar (0.161 > 0.130) fires even though VAD says false
        assertTrue(BargeGate.decide(0.161f, vadSpeech = false, sustainedMs = 0L,
            sustainedLevelMs = 400L, rmsMin = levelRmsMin, vadAvailable = true, levelOnlyMs = 400L))
        // longer sustain at the same level also fires
        assertTrue(BargeGate.decide(0.165f, vadSpeech = false, sustainedMs = 450L,
            sustainedLevelMs = 450L, rmsMin = levelRmsMin, vadAvailable = true, levelOnlyMs = 400L))
    }

    @Test fun level_only_escape_does_not_fire_below_the_raised_bar() {
        // 0.4.0.3 SPEC REVERSAL: this row used to assert 0.155 could not fire. It now
        // sits ABOVE the re-derived 0.130 bar and MUST be able to fire — 0.155 is
        // inside the band the field log records for a real barge (peaks 0.172-0.182,
        // troughs well under). The "echo must not fire" guarantee it used to carry
        // moved to duty cycle: see echo_shaped_series_never_fires_the_escape.
        // What still cannot fire is a level under the bar, however long it holds:
        assertFalse(BargeGate.decide(0.129f, vadSpeech = false, sustainedMs = 450L,
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

    // ---- 0.4.0.3 series rows: the real drain-loop math over real shapes ----

    private data class Fire(val fired: Boolean, val atMs: Long)

    /**
     * Mirror of the VoiceController drain loop for one turn, VAD available and
     * disagreeing (vad=false, never recently true — the echo-masked case): 64ms
     * frames, both leaky accumulators, [BargeGate.decide] per frame. Returns when
     * the escape fires. `leaky=false` replays the 0.4.0.2 reset-to-0 rule instead,
     * so the same series can prove what the old build did.
     */
    private fun drain(levels: FloatArray, rmsMin: Float = 0.10f,
                      levelOnlyMs: Long = BargeGate.DEFAULT_LEVEL_ONLY_MS,
                      levelBoost: Float = BargeGate.LEVEL_ONLY_BOOST,
                      leaky: Boolean = true, frameMs: Long = 64L): Fire {
        val floor = rmsMin                       // vadAvailable = true
        val bar = floor * levelBoost
        var sustained = 0L
        var levelSustain = 0L
        for ((i, lv) in levels.withIndex()) {
            sustained = if (leaky) BargeGate.accumulate(sustained, lv > floor, frameMs)
                        else if (lv > floor) sustained + frameMs else 0L
            levelSustain = if (leaky) BargeGate.accumulate(levelSustain, lv > bar, frameMs,
                                maxOf(BargeGate.SUSTAIN_CAP_MS, levelOnlyMs))
                           else if (lv > bar) levelSustain + frameMs else 0L
            // the escape's own predicate, evaluated on the bar this series is driving
            if (levelOnlyMs > 0L && levelSustain >= levelOnlyMs && lv > bar) return Fire(true, (i + 1) * frameMs)
            if (BargeGate.decide(lv, vadSpeech = false, sustainedMs = sustained, rmsMin = rmsMin,
                    vadAvailable = true, levelOnlyMs = levelOnlyMs, sustainedLevelMs = levelSustain))
                return Fire(true, (i + 1) * frameMs)
        }
        return Fire(false, -1L)
    }

    /**
     * The turn-10 failure shape: a person at conversational volume over a loud
     * reply. Level rides 0.11-0.18 (the log's peakRms for that turn tops out at
     * 0.172/0.182) with brief dips back across the bar between phonemes, and the
     * VAD stays false the whole time because the frame is echo-dominant.
     */
    private val bargeOverTts = floatArrayOf(
        0.16f, 0.14f, 0.18f, 0.12f, 0.17f, 0.18f, 0.15f, 0.11f, 0.17f, 0.18f, 0.16f, 0.14f)

    /**
     * The app's own echo envelope, as the mic actually sees it under the shipped
     * single-capture AEC: crests in the feared 0.15-0.17 self-cut band, troughs back
     * into the measured residual band (the log's mid-reply peakRms readings of
     * 0.081/0.090/0.095 bound the quiet stretches). Amplitude-modulated, ~50% duty
     * at the bar — that is what must never fire.
     */
    private val echoEnvelope = floatArrayOf(
        0.15f, 0.09f, 0.17f, 0.06f, 0.16f, 0.08f, 0.15f, 0.07f, 0.17f, 0.05f,
        0.16f, 0.09f, 0.15f, 0.06f, 0.17f, 0.08f, 0.16f, 0.07f, 0.15f, 0.09f,
        0.17f, 0.06f, 0.16f, 0.08f, 0.15f, 0.07f, 0.17f, 0.05f, 0.16f, 0.09f)

    @Test fun barge_over_tts_fires_within_400ms_even_with_vad_false() {
        val fire = drain(bargeOverTts)
        assertTrue("real barge shape must fire the escape", fire.fired)
        assertTrue("fired at ${fire.atMs}ms, want <= 400ms", fire.atMs <= 400L)
    }

    @Test fun barge_over_tts_could_not_fire_under_the_0402_rule() {
        // ROOT CAUSE, replayed: the same series against 0.4.0.2's contiguous
        // accumulator and 1.6x (0.160) bar never fires — not at 400ms, not ever.
        assertFalse(drain(bargeOverTts, levelOnlyMs = 400L, levelBoost = 1.6f, leaky = false).fired)
        // and neither half of the change is sufficient alone:
        assertFalse("the 0.160 bar is above the shape's own peak run",
            drain(bargeOverTts, levelOnlyMs = 400L, levelBoost = 1.6f).fired)
        // reset-to-0 does compound once the bar is reachable, but only across a clean
        // contiguous run: on this series it needs the last four frames, twice as long.
        val strict = drain(bargeOverTts, leaky = false)
        val leaky = drain(bargeOverTts)
        assertTrue(strict.fired && leaky.fired)
        assertTrue("leaky ${leaky.atMs}ms must beat reset-to-0 ${strict.atMs}ms",
            leaky.atMs < strict.atMs)
    }

    @Test fun echo_shaped_series_never_fires_the_escape() {
        // ~2s of amplitude-modulated echo at 0.15-0.17: the leak drains it every
        // trough, so the accumulator never reaches levelOnlyMs. This is the duty-cycle
        // guarantee that replaces the old amplitude margin.
        assertFalse(drain(echoEnvelope).fired)
        // still safe if the echo band shifts up: crests 0.17-0.19, same modulation
        assertFalse(drain(FloatArray(echoEnvelope.size) { echoEnvelope[it] + 0.02f }).fired)
    }

    @Test fun echo_shaped_series_never_fires_the_escape_even_with_shallow_troughs() {
        // harder case: troughs only just under the bar (0.12) rather than down in the
        // residual band, 50% duty. Must still drain.
        val shallow = FloatArray(30) { if (it % 2 == 0) 0.17f else 0.12f }
        assertFalse(drain(shallow).fired)
        // 3-on/3-off bursts are also 50% duty and must stay under the threshold
        val bursty = FloatArray(30) { if ((it / 3) % 2 == 0) 0.17f else 0.09f }
        assertFalse(drain(bursty).fired)
    }

    @Test fun raising_the_rms_floor_restores_a_bar_above_the_self_cut_band() {
        // documented mitigation for the honest risk: if a device's AEC leaves echo
        // riding SUSTAINED at 0.15-0.17, barge_rms_min 0.10 -> 0.14 puts the bar at
        // 0.182, back above that band — a steady 0.17 echo cannot fire.
        val steadyEcho = FloatArray(40) { 0.17f }
        assertTrue("at the shipped floor a sustained 0.17 does fire (the stated risk)",
            drain(steadyEcho).fired)
        assertFalse(drain(steadyEcho, rmsMin = 0.14f).fired)
        // ...and the escape hatch still disables the path outright
        assertFalse(drain(steadyEcho, levelOnlyMs = 0L).fired)
    }
}
