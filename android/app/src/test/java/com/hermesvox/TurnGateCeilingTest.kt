package com.hermesvox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drain's give-up rule, replayed on the 0.9.0 field timings (2026-10-20, gen=2):
 * gate close 00:17:30.483, text done 00:18:10.164 (40 s of generation), 2,077 chars
 * spoken until ~00:20:31. The 0.9.0 drain quit at 00:18:30.548 — the flat 60 s.
 */
class TurnGateCeilingTest {

    private fun s(hms: String): Long {   // "mm:ss.SSS" -> ms
        val (m, rest) = hms.split(":")
        return m.toLong() * 60_000L + (rest.toDouble() * 1000).toLong()
    }

    private val closed = s("17:30.483")
    private val speechEnds = s("20:31.000")

    @Test fun a_long_live_reply_keeps_the_barge_watch_to_its_end() {
        // Alive the whole way (stream in flight, then speaking): replay each 64 ms read.
        var t = closed
        while (t <= speechEnds) {
            assertFalse("ceiling cut a live turn at +${t - closed} ms",
                TurnGateCeiling.expired(t, closed, lastAliveAtMs = t))
            t += 64L
        }
    }

    @Test fun the_old_flat_rule_is_what_cut_the_field_turn() {
        // The moment the 0.9.0 drain quit, a flat-from-close 60 s rule already held...
        val quitAt = s("18:30.548")
        assertTrue(quitAt - closed >= TurnGateCeiling.IDLE_MS)
        // ...and the new rule does not, because the reply was speaking at that instant.
        assertFalse(TurnGateCeiling.expired(quitAt, closed, lastAliveAtMs = quitAt))
    }

    @Test fun a_wedged_gate_is_still_freed_60s_after_the_turn_went_quiet() {
        val diedAt = speechEnds   // the reply finished but nobody released the gate
        assertFalse(TurnGateCeiling.expired(diedAt + TurnGateCeiling.IDLE_MS - 1, closed, diedAt))
        assertTrue(TurnGateCeiling.expired(diedAt + TurnGateCeiling.IDLE_MS, closed, diedAt))
    }

    @Test fun a_liveness_flag_that_never_clears_is_bounded() {
        val t = closed + TurnGateCeiling.HARD_MS
        assertFalse(TurnGateCeiling.expired(t - 1, closed, lastAliveAtMs = t - 1))
        assertTrue(TurnGateCeiling.expired(t, closed, lastAliveAtMs = t))
    }
}
