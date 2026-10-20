package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The per-second duty-cycle witness: one line per window, percentages of checked reads. */
class BargeStatTest {

    @Test fun one_line_per_window_with_the_duty_cycle() {
        // 16 reads stamped 0..960 ms: the window closes on the read 960 ms after the first.
        val st = BargeStat(windowMs = 960L)
        // 8 over the bar, 4 between floor and bar, 4 under.
        val levels = List(8) { 0.16f } + List(4) { 0.11f } + List(4) { 0.05f }
        var line: BargeStat.Line? = null
        levels.forEachIndexed { i, lv ->
            val out = st.add(lv, floor = 0.10f, bar = 0.13f, vadSpeech = i % 4 == 0, nowMs = i * 64L)
            if (i < levels.size - 1) assertNull("no line before the window closes (read $i)", out) else line = out
        }
        val l = line!!
        assertEquals(16, l.frames)
        assertEquals(75, l.floorPct)
        assertEquals(50, l.barPct)
        assertEquals(25, l.vadPct)
        assertEquals(0.16f, l.peak, 1e-6f)
        assertEquals((8 * 0.16 + 4 * 0.11 + 4 * 0.05) / 16, l.mean.toDouble(), 1e-6)
        assertEquals(0.10f, l.floor, 1e-6f)
        assertEquals(0f, l.refPeak, 0f)
        assertEquals("frames=16 floorPct=75 barPct=50 vadPct=25 peak=0.160 mean=0.120 floor=0.100 refPeak=0.000", l.render())
    }

    @Test fun reset_drops_a_partial_window() {
        val st = BargeStat(windowMs = 1000L)
        repeat(10) { st.add(0.2f, 0.10f, 0.13f, true, it * 64L) }
        st.reset()
        // A new window starts at the next read's clock, not the dropped one's.
        assertNull(st.add(0.05f, 0.10f, 0.13f, false, 5_000L))
        val l = st.add(0.05f, 0.10f, 0.13f, false, 6_000L)!!
        assertEquals(2, l.frames)
        assertEquals(0, l.floorPct)
    }
}
