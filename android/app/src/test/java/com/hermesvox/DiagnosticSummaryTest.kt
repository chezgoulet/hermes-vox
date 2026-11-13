package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #417 — the diagnostic summary's switch: off by default, in About & Diagnostics beside the
 * logging switches, and the one gate `/diagnostics` follows.
 */
class DiagnosticSummaryTest {

    private class Fake(val m: MutableMap<String, Any> = HashMap()) : DiagnosticSummary.Reader {
        override fun getBoolean(key: String, default: Boolean) = (m[key] as? Boolean) ?: default
    }

    private val layout = File("src/main/res/layout/activity_settings.xml").readText()
    private val source = File("src/main/java/com/hermesvox/SettingsActivity.kt").readText()

    @Test fun the_switch_is_off_by_default() {
        assertFalse(DiagnosticSummary.DEFAULT_ON)
        assertEquals("debug_diag_summary", DiagnosticSummary.PREF)
        assertFalse(DiagnosticSummary.enabled(Fake()))
        // And nothing turns it on by itself: no caller writes the pref except the tap and the
        // About restore, so an install that never opens Settings never offers the command.
        assertFalse(source.contains("DiagnosticSummary.set(prefs, true)"))
    }

    @Test fun an_on_value_is_read_back() {
        assertTrue(DiagnosticSummary.enabled(Fake(mutableMapOf(DiagnosticSummary.PREF to true))))
    }

    @Test fun the_row_says_what_it_does_and_what_it_does_not_carry() {
        val about = layout.indexOf("@+id/sec_about")
        val row = layout.indexOf("@+id/row_diagsummary")
        assertTrue("the switch is in About & Diagnostics", row > about)
        // Beside the logging switches, not somewhere else in the section.
        assertTrue(layout.indexOf("@+id/row_logtranscripts") < row)
        assertTrue(layout.indexOf("@+id/row_debuglog") < row)
        assertTrue(row < layout.indexOf("@+id/row_restore_about"))
        val window = layout.substring(row, row + 2_000)
        assertTrue(window.contains("android:text=\"Diagnostic summary\""))
        assertTrue(window.contains(
            "Adds a command that copies a report block for a bug report; contains no conversations"))
        assertTrue(window.contains("@+id/set_diagsummary"))
    }

    /** The switch's tap is the whole rule: one pref, written here, read by the command's gate. */
    @Test fun the_settings_switch_writes_the_one_pref_the_gate_reads() {
        assertTrue(source.contains(
            "diag.setOnCheckedChangeListener { _, on -> DiagnosticSummary.set(prefs, on) }"))
        // About's restore counts: a restore that left the command listed would not be one.
        assertTrue(source.contains(
            "putBoolean(DiagnosticSummary.PREF, DiagnosticSummary.DEFAULT_ON)"))
        assertEquals(DiagnosticSummary.PREF, DiagnosticSummary.Gate.DIAGNOSTIC_SUMMARY.pref)
        assertFalse(DiagnosticSummary.Gate.DIAGNOSTIC_SUMMARY.default)
    }
}
