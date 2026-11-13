package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #417 — `/diagnostics` in the commands sheet, under "The agent".
 *
 * It exists only while Settings → About & Diagnostics → Diagnostic summary is on: a command
 * that cannot run is not listed. Its action copies the same `vox-diag` block the Settings action
 * produces — one builder, two entry points — because the aggregator parses that text.
 */
class DiagnosticCommandTest {

    private val allOn = CommandCatalog.ALL_GATES_OPEN
    private val allOff: (DiagnosticSummary.Gate) -> Boolean = { false }
    private val src = File("src/main/java/com/hermesvox")

    private fun names(gates: (DiagnosticSummary.Gate) -> Boolean) =
        CommandCatalog.grouped(gates = gates).flatMap { it.second }.map { it.name }

    @Test fun the_command_is_absent_when_the_switch_is_off() {
        assertFalse(names(allOff).contains(DiagnosticSummary.COMMAND))
        // Not in /help either: the card must not teach a row the sheet would not show.
        assertFalse(CommandCatalog.helpText(allOff).contains(DiagnosticSummary.COMMAND))
        // And it cannot be found by name or by what it does.
        assertNull(CommandCatalog.find(DiagnosticSummary.COMMAND, allOff))
        assertFalse(CommandCatalog.search("diagnostics", allOff)
            .any { it.name == DiagnosticSummary.COMMAND })
    }

    @Test fun the_command_is_present_when_the_switch_is_on() {
        assertTrue(names(allOn).contains(DiagnosticSummary.COMMAND))
        assertTrue(CommandCatalog.helpText(allOn).contains(DiagnosticSummary.COMMAND))
        val c = CommandCatalog.find(DiagnosticSummary.COMMAND, allOn)!!
        assertEquals(CommandCatalog.Group.AGENT, c.group)
        assertEquals("Copy a report block for a bug report", c.effect)
        // It copies here and reports on its row; there is no card to open.
        assertEquals(CommandCatalog.Kind.INLINE, c.kind)
        assertTrue(CommandCatalog.reportsOnRow(c))
        // Nothing is lost: it reads settings and measured numbers, and writes only the clipboard.
        assertNull(c.cost)
        // This is the switch it follows, not a copy of the key.
        assertEquals(DiagnosticSummary.PREF, c.gate!!.pref)
    }

    @Test fun the_switch_opens_the_sheet_command_and_nothing_else() {
        // Only the diagnostic command is gated today: turning its switch on adds exactly it.
        val gated = CommandCatalog.ALL.filter { it.gate != null }.map { it.name }
        assertEquals(listOf(DiagnosticSummary.COMMAND), gated)
        val off = names(allOff).toSet()
        val on = names(allOn).toSet()
        assertEquals(setOf(DiagnosticSummary.COMMAND), on - off)
    }

    /**
     * One builder, two entry points. Both the Settings action and the command call
     * [PresenceDiagnosticCollect.copy] — the same function, the same prefs, so the text is
     * byte-identical by construction. Neither activity builds a block of its own.
     */
    @Test fun both_entry_points_copy_the_same_builder() {
        val settings = File(src, "SettingsActivity.kt").readText()
        val main = File(src, "MainActivity.kt").readText()
        val collect = File(src, "PresenceDiagnosticCollect.kt").readText()

        assertTrue(settings.contains("PresenceDiagnosticCollect.copy(this, prefs)"))
        assertTrue(main.contains("PresenceDiagnosticCollect.copy(this, prefs)"))
        // Neither side assembles a block: the builder is only reached through copy().
        assertFalse(settings.contains("PresenceDiagnosticCollect.collect("))
        assertFalse(main.contains("PresenceDiagnostic.block("))
        // One clipboard write for the block, and it lives in copy() — not in either caller.
        assertEquals(1, Regex("""setPrimaryClip\(""").findAll(collect).count())
        assertTrue(collect.contains("ClipData.newPlainText(PresenceDiagnostic.MARKER, block)"))
        assertFalse(settings.contains("newPlainText(PresenceDiagnostic.MARKER"))
        assertFalse(main.contains("newPlainText(PresenceDiagnostic.MARKER"))
        assertTrue(collect.contains("fun copy(context: Context, prefs: SharedPreferences): Boolean"))
        assertEquals(1, Regex("""fun copy\(""").findAll(collect).count())
    }

    /** The block itself is unchanged by the second entry point: still the documented shape. */
    @Test fun the_copied_block_is_still_the_documented_shape() {
        val block = PresenceDiagnostic.block(PresenceDiagnostic.Input(
            build = "0.9.1-nightly-abc1234",
            device = PresenceDiagnostic.Device("Google", "Pixel 7", "14"),
            mind = "lan",
            session = PresenceDiagnostic.Session(e2P50Ms = 3120, turns = 12),
            model = "gemma-e2b",
        ))
        assertEquals(PresenceDiagnostic.MARKER, block.lineSequence().first())
        // Same input, same bytes — the aggregator parses one shape.
        assertEquals(block, PresenceDiagnostic.block(PresenceDiagnostic.Input(
            build = "0.9.1-nightly-abc1234",
            device = PresenceDiagnostic.Device("Google", "Pixel 7", "14"),
            mind = "lan",
            session = PresenceDiagnostic.Session(e2P50Ms = 3120, turns = 12),
            model = "gemma-e2b",
        )))
    }

    @Test fun the_sheet_reads_the_switches_and_the_command_reports_one_line() {
        val sheet = File(src, "CommandsSheet.kt").readText()
        assertTrue(sheet.contains("CommandCatalog.grouped(gates = gates)"))
        assertTrue(sheet.contains("CommandCatalog.search(query, gates)"))
        assertTrue(sheet.contains("CommandCatalog.find(name, gates)"))
        val main = File(src, "MainActivity.kt").readText()
        assertTrue(main.contains("gates = ::commandGate"))
        assertTrue(main.contains("setStatus(if (ok) DiagnosticSummary.COPIED else DiagnosticSummary.COPY_FAILED, true)"))
        // One line, no dialog: the success text is exactly this and nothing more.
        assertEquals("Diagnostic summary copied", DiagnosticSummary.COPIED)
    }
}
