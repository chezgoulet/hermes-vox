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

    @Test fun emit_is_read_only() {
        // Emit never mutates: two consecutive lines are identical, and the
        // counters between them are stable. (No [-] assumption: tests share
        // the static object — earlier tests may have filled the rings.)
        val a = ErTelemetry.line()
        val b = ErTelemetry.line()
        assertEquals(a, b)
    }

    // ---- 0.8/M1: the ER-delta counters ----

    private fun turnsOf(l: String) = Regex("turns=(\\d+)").find(l)!!.groupValues[1].toLong()
    private fun spokeOf(l: String) = Regex("soul-spoke=(\\d+)").find(l)!!.groupValues[1].toLong()

    @Test fun window_counts_the_er_delta() {
        // RELATIVE assertions only — the object is shared across tests, so absolute
        // counts are meaningless (see emit_is_read_only).
        val t0 = turnsOf(ErTelemetry.line()); val s0 = spokeOf(ErTelemetry.line())
        ErTelemetry.window(soulSpoke = false)
        ErTelemetry.window(soulSpoke = true)
        ErTelemetry.window(soulSpoke = true)
        val t1 = turnsOf(ErTelemetry.line()); val s1 = spokeOf(ErTelemetry.line())
        assertEquals(3L, t1 - t0)
        assertEquals(2L, s1 - s0)
    }

    @Test fun soul_decisions_are_counted_and_emitted() {
        // 0.8/M3c: a field session's central question — is the soul answering, handing over, or
        // coming back with nothing? Relative assertions only (the object is shared).
        fun n(l: String, k: String) = Regex("$k=(\\d+)").find(l)!!.groupValues[1].toLong()
        val a0 = n(ErTelemetry.line(), "answer"); val e0 = n(ErTelemetry.line(), "escalate")
        val z0 = n(ErTelemetry.line(), "nothing")
        ErTelemetry.soulDecision("answer")
        ErTelemetry.soulDecision("escalate")
        ErTelemetry.soulDecision("garbage")   // anything unrecognised counts as nothing
        val line = ErTelemetry.line()
        assertEquals(1L, n(line, "answer") - a0)
        assertEquals(1L, n(line, "escalate") - e0)
        assertEquals(1L, n(line, "nothing") - z0)
    }

    @Test fun should_emit_fires_exactly_once_per_ten_calls() {
        // Phase-independent: whatever the accumulated count, ten consecutive calls
        // contain exactly one emit.
        var emits = 0
        repeat(ErTelemetry.EVERY_N_TURNS.toInt()) { if (ErTelemetry.shouldEmit()) emits++ }
        assertEquals(1, emits)
    }

    @Test fun the_line_carries_the_delta_and_the_render_family() {
        ErTelemetry.gemmaRender(120L)
        val line = ErTelemetry.line()
        assertTrue(line.contains("turns="))
        assertTrue(line.contains("soul-spoke="))
        assertTrue(line.contains("gemma-render[p50="))
    }
}
