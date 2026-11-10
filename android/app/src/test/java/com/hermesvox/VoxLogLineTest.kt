package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * #538 (the reporter's log, 2026-11-10): every file line carries its date. The merged export
 * spans days; with `HH:mm:ss.SSS` alone a "596-second turn" was two days interleaved. Pure JVM —
 * the stamp pattern and the line shape, not the Android append path.
 */
class VoxLogLineTest {

    private fun stamp(epochMs: Long): String =
        SimpleDateFormat(VoxLog.LINE_STAMP, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(epochMs))

    @Test fun a_line_names_its_day() {
        // 2026-11-10T22:04:05.991Z
        val l = VoxLog.line(stamp(1_794_348_245_991L), "D", "event=turn gen=1")
        assertEquals("11-10 22:04:05.991 [D] event=turn gen=1\n", l)
    }

    @Test fun the_same_clock_on_two_days_is_two_different_lines() {
        val day = 86_400_000L
        val a = VoxLog.line(stamp(1_794_348_245_991L), "D", "x")
        val b = VoxLog.line(stamp(1_794_348_245_991L + day), "D", "x")
        assertTrue(a != b)
        assertEquals(a.substring(6), b.substring(6))   // only the date differs
    }

    @Test fun the_shape_stays_greppable_and_costs_six_bytes() {
        val l = VoxLog.line(stamp(0L), "W", "event=tts-drain result=forced")
        assertTrue(l, Regex("""^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} \[W] event=""").containsMatchIn(l))
        assertEquals("HH:mm:ss.SSS".length + 6, VoxLog.LINE_STAMP.length)
    }
}
