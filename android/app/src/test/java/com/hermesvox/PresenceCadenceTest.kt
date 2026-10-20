package com.hermesvox

import com.hermesvox.PresenceCadence.Density
import com.hermesvox.PresenceCadence.Run
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PresenceCadence — the gap is held. The field turn this pins: a 76-second `sleep 70` where the
 * soul spoke at 2.5 s and 4 s and then left the caller in 71 seconds of silence.
 */
class PresenceCadenceTest {

    private val t0 = 100_000L

    /** Drive a run on a 1 s tick, as ErPresence does, and return the times lines were taken. */
    private fun lines(density: Density, untilMs: Long): List<Long> {
        var run = Run.start(t0)
        val out = ArrayList<Long>()
        var now = t0
        while (now <= t0 + untilMs) {
            if (PresenceCadence.shouldSpeak(now, run, density)) { out += now - t0; run = run.said(now) }
            now += 1_000L
        }
        return out
    }

    @Test fun nothing_before_the_four_second_rule() {
        for (d in Density.entries) {
            assertFalse(PresenceCadence.shouldSpeak(t0, t0, 0L, 0, d))
            assertFalse(PresenceCadence.shouldSpeak(t0 + ErFillers.LAG_AFTER_MS - 1, t0, 0L, 0, d))
            assertTrue(PresenceCadence.shouldSpeak(t0 + ErFillers.LAG_AFTER_MS, t0, 0L, 0, d))
        }
    }

    @Test fun each_register_speaks_at_its_interval_and_not_before() {
        for (d in Density.entries) {
            val last = t0 + 20_000L
            assertFalse(d.name, PresenceCadence.shouldSpeak(last + d.intervalMs - 1, t0, last, 1, d))
            assertTrue(d.name, PresenceCadence.shouldSpeak(last + d.intervalMs, t0, last, 1, d))
        }
        assertEquals(30_000L, Density.QUIET.intervalMs)
        assertEquals(18_000L, Density.CONVERSATIONAL.intervalMs)
        assertEquals(10_000L, Density.CHATTY.intervalMs)
    }

    @Test fun the_field_gap_is_held() {
        // The 76 s turn, Conversational: the lag line at 4 s, then a line every 18 s — no
        // silence longer than the interval until the reply at 75.7 s.
        val said = lines(Density.CONVERSATIONAL, 75_685L)
        assertEquals(listOf(4_000L, 22_000L, 40_000L, 58_000L), said)
        assertTrue(75_685L - said.last() <= Density.CONVERSATIONAL.intervalMs)
    }

    @Test fun the_cap_ends_a_wedged_turn() {
        for (d in Density.entries) {
            val said = lines(d, 10 * 60_000L)   // ten minutes of a mind that never answers
            assertEquals(d.name, d.cap, said.size)
            val capped = Run(t0, t0 + 500_000L, d.cap)
            assertTrue(PresenceCadence.capped(capped, d))
            assertFalse(PresenceCadence.shouldSpeak(t0 + 10 * 60_000L, capped, d))
        }
        assertEquals(listOf(3, 6, 12), Density.entries.map { it.cap })
    }

    @Test fun a_reply_resets_everything() {
        var run = Run.start(t0)
        run = run.said(t0 + 4_000L).said(t0 + 22_000L)
        // The reply lands: the run is idle — no line at any later time.
        run = Run.IDLE
        assertFalse(PresenceCadence.shouldSpeak(t0 + 40_000L, run, Density.CHATTY))
        assertFalse(PresenceCadence.shouldSpeak(Long.MAX_VALUE / 2, run, Density.CHATTY))
        // The next turn is a fresh run: the 4 s rule again, then line 1 again.
        val t1 = t0 + 90_000L
        run = Run.start(t1)
        assertEquals(0, run.linesSaid)
        assertFalse(PresenceCadence.shouldSpeak(t1 + 3_999L, run, Density.CONVERSATIONAL))
        assertTrue(PresenceCadence.shouldSpeak(t1 + 4_000L, run, Density.CONVERSATIONAL))
    }

    @Test fun quiet_still_says_the_one_lag_line() {
        val said = lines(Density.QUIET, 75_685L)
        assertEquals(4_000L, said.first())
        assertEquals(listOf(4_000L, 34_000L, 64_000L), said)
        assertTrue(Density.QUIET.fillerCap >= 1)   // the filler tick's lag line is never capped to zero
    }

    @Test fun the_gap_is_measured_from_the_previous_line() {
        val run = Run.start(t0)
        assertEquals(4_000L, run.gap(t0 + 4_000L))
        assertEquals(18_000L, run.said(t0 + 4_000L).gap(t0 + 22_000L))
    }

    @Test fun the_old_slider_migrates() {
        assertEquals(Density.CONVERSATIONAL, PresenceCadence.resolve(null, null))
        assertEquals(Density.QUIET, PresenceCadence.resolve(null, 0))
        assertEquals(Density.CONVERSATIONAL, PresenceCadence.resolve(null, 1))
        assertEquals(Density.CONVERSATIONAL, PresenceCadence.resolve(null, 2))
        assertEquals(Density.CHATTY, PresenceCadence.resolve(null, 3))
        assertEquals(Density.CHATTY, PresenceCadence.resolve(null, 4))
        // A stored register wins over the legacy slider; a garbage value falls back to migration.
        assertEquals(Density.QUIET, PresenceCadence.resolve("quiet", 4))
        assertEquals(Density.CHATTY, PresenceCadence.resolve("bogus", 3))
    }

    @Test fun the_register_governs_the_filler_cap() {
        assertEquals(ErFillers.MAX_FILLERS_PER_WINDOW, PresenceCadence.DEFAULT.fillerCap)
        assertTrue(Density.QUIET.fillerCap < Density.CHATTY.fillerCap)
    }
}
