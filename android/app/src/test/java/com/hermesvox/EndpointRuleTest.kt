package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM proof of the VAD-extended endpointing rule (B2). The old capture loop
 * cut an utterance unconditionally at `vad_max_ms`, chopping a long continuous
 * utterance mid-sentence. [EndpointRule.shouldStop] only stops at maxMs once the
 * speaker has actually paused (> [EndpointRule.EXTEND_PAUSE_MS] of VAD silence)
 * and otherwise lets the segment run to the hard ceiling
 * [EndpointRule.DEFAULT_HARD_MS]. The caller's normal pause-break (silentMs >
 * silenceMs) is untouched and owns everything below maxMs.
 */
class EndpointRuleTest {

    private val maxMs = 15000L
    private val hardMs = 60000L

    @Test fun spec_rows() {
        // well under maxMs: keep going (rule is not consulted < maxMs)
        assertFalse(EndpointRule.shouldStop(14000, 0, maxMs, hardMs))
        // past maxMs with a real pause (>100ms silence) -> stop
        assertTrue(EndpointRule.shouldStop(15100, 120, maxMs, hardMs))
        // past the hard ceiling even while talking continuously -> stop
        assertTrue(EndpointRule.shouldStop(61000, 0, maxMs, hardMs))
        // silentMs=900 under maxMs belongs to the EXISTING silence rule
        // (silentMs > silenceMs), not EndpointRule -> false
        assertFalse(EndpointRule.shouldStop(8000, 900, maxMs, hardMs))
    }

    @Test fun extends_past_max_ms_while_still_speaking() {
        // continuous speech (no pause) just past maxMs -> keep the segment going
        assertFalse(EndpointRule.shouldStop(15100, 0, maxMs, hardMs))
        assertFalse(EndpointRule.shouldStop(20000, 0, maxMs, hardMs))
        assertFalse(EndpointRule.shouldStop(55000, 0, maxMs, hardMs))
    }

    @Test fun pause_of_100ms_is_not_yet_a_stop() {
        // boundary: the rule requires silentMs STRICTLY > 100
        assertFalse(EndpointRule.shouldStop(15100, 100, maxMs, hardMs))
        assertTrue(EndpointRule.shouldStop(15100, 101, maxMs, hardMs))
    }

    @Test fun defaults_match_the_spec() {
        assertEquals(60000, EndpointRule.DEFAULT_HARD_MS)
        assertEquals(100L, EndpointRule.EXTEND_PAUSE_MS)
    }
}
