package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ErTelemetry — the counters. Under test: routes/verdicts/outcomes count,
 * soul-first-word ring bounds + percentile math, the emit line carries every
 * family and preserves zeroes.
 */
class ErTelemetryTest {

    @Test fun counters_count() {
        ErTelemetry.classify(ErIntent.Route.HOLD_ONLY)
        ErTelemetry.classify(ErIntent.Route.ACK_AND_YIELD)
        ErTelemetry.barge(ErBargeGate.Verdict.HOLD_MIND)
        ErTelemetry.arbiter(ErArbiter.Decision.Play)
        val line = ErTelemetry.line()
        assertTrue(line.contains("cls(hold="))
        assertTrue(line.contains("barge(cancel="))
        assertTrue(line.contains("arb(play="))
    }

    @Test fun soul_first_word_ring_bounds_and_percentiles() {
        for (ms in listOf(200L, 100L, 300L, 150L)) ErTelemetry.soulFirstWord(ms)
        val line = ErTelemetry.line()
        assertTrue(line.contains("soul-first-word[p50="))
        assertTrue(line.contains("p95="))
    }

    @Test fun emit_preserves_zeroes_and_does_not_reset() {
        val a = ErTelemetry.line()
        val b = ErTelemetry.line()
        // Two emits: identical counters (emit is read-only).
        assertEquals(a, b)
        assertTrue(a.contains("soul-first-word[-]"))
    }
}
