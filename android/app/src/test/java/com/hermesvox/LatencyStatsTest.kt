package com.hermesvox

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves LatencyStats' per-turn outcome summary (emitted on every gate release)
 * and the rolling P50/P95 window (every 8th turn). Pure JVM — exercises
 * summaryLines()/windowCounts() directly, never touches VoxLog.
 */
class LatencyStatsTest {

    @Test fun per_turn_summary_every_call() {
        LatencyStats.reset()
        LatencyStats.pushStt(40)
        LatencyStats.pushFirstByte(120)
        LatencyStats.pushFirstAudio(180)
        LatencyStats.pushFullReply(900)
        val first = LatencyStats.summaryLines("turn", "stream-done", 1L)
        assertEquals(1, first.size)
        assertTrue(first[0], first[0].contains("event=turn label=turn gen=1 outcome=stream-done"))
        assertTrue(first[0], first[0].contains("stt=40 firstByte=120 firstAudio=180 fullReply=900"))
        // per-turn values clear after every emit
        val second = LatencyStats.summaryLines("turn", "stream-done", 2L)
        assertEquals(1, second.size)
        assertTrue(second[0], second[0].contains("stt=- firstByte=- firstAudio=- fullReply=-"))
    }

    @Test fun rolling_p50_every_8th_then_clear() {
        LatencyStats.reset()
        fun seed() {
            LatencyStats.pushStt(40)
            LatencyStats.pushFirstByte(120)
            LatencyStats.pushFirstAudio(180)
            LatencyStats.pushFullReply(900)
        }
        for (i in 0L until 7L) { seed(); assertEquals(1, LatencyStats.summaryLines("turn", "stream-done", i).size) }
        seed()
        val eighth = LatencyStats.summaryLines("turn", "stream-done", 7L)
        assertEquals(5, eighth.size)                                  // 1 turn + 4 event=lat lines
        assertTrue(eighth[0], eighth[0].startsWith("event=turn"))
        assertEquals(4, eighth.drop(1).count { it.startsWith("event=lat") })
        // rolling window cleared after the 8th emit
        val cleared = LatencyStats.windowCounts()
        assertArrayEquals(intArrayOf(0, 0, 0, 0), cleared)
        // a fresh push grows from 0
        LatencyStats.pushStt(50)
        val grown = LatencyStats.windowCounts()
        assertArrayEquals(intArrayOf(0, 0, 0, 1), grown)
    }

    @Test fun reset_zeroes_window() {
        LatencyStats.reset()
        LatencyStats.pushStt(40)
        LatencyStats.pushFirstByte(120)
        LatencyStats.pushFirstAudio(180)
        LatencyStats.pushFullReply(900)
        assertArrayEquals(intArrayOf(1, 1, 1, 1), LatencyStats.windowCounts())
        LatencyStats.reset()
        assertArrayEquals(intArrayOf(0, 0, 0, 0), LatencyStats.windowCounts())
    }
}
