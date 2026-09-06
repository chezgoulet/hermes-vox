package com.hermesvox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2 (REVISED) — silence-driven endpointing. Past maxMs the mic keeps listening
 * while the user is talking; only a real pause (>100ms) closes an extended
 * utterance; the absolute ceiling exists ONLY when opted into (hardMs > 0).
 */
class EndpointRuleTest {

    private val maxMs = 15000L

    // ---- default contract: NO hard ceiling (hardMs = 0) ----

    @Test fun default_ceiling_disabled_never_stops_a_continuous_speaker() {
        assertFalse(EndpointRule.shouldStop(14000, 0, maxMs, 0))
        assertFalse(EndpointRule.shouldStop(15100, 0, maxMs, 0))     // past max, still talking
        assertFalse(EndpointRule.shouldStop(60000, 0, maxMs, 0))     // a full minute, no pause
        assertFalse(EndpointRule.shouldStop(600000, 50, maxMs, 0))   // even 10 min with only blips
    }

    @Test fun default_past_max_closes_on_a_real_pause_only() {
        assertFalse(EndpointRule.shouldStop(15100, 100, maxMs, 0))   // 100ms is a blip, not a stop
        assertTrue(EndpointRule.shouldStop(15100, 101, maxMs, 0))    // real breath -> done
        assertTrue(EndpointRule.shouldStop(15100, 500, maxMs, 0))
    }

    @Test fun below_max_is_never_the_rule_that_stops() {
        assertFalse(EndpointRule.shouldStop(8000, 900, maxMs, 0))    // caller's 800ms rule owns this
    }

    // ---- opt-in ceiling: positive hardMs acts as the crash-guard ----

    @Test fun opt_in_ceiling_stops_regardless_of_silence() {
        val guard = 300000L
        assertFalse(EndpointRule.shouldStop(240000, 50, maxMs, guard))
        assertTrue(EndpointRule.shouldStop(301000, 50, maxMs, guard))
    }

    @Test fun spec_rows_with_opt_in_guard() {
        val hardMs = 60000L
        assertFalse(EndpointRule.shouldStop(14000, 0, maxMs, hardMs))
        assertTrue(EndpointRule.shouldStop(15100, 120, maxMs, hardMs))
        assertTrue(EndpointRule.shouldStop(61000, 0, maxMs, hardMs))
        assertFalse(EndpointRule.shouldStop(15100, 0, maxMs, hardMs))  // extended: no pause
    }
}
