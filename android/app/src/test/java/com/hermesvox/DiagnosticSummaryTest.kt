package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #418 — `/diagnostics` exists only while its own switch is on, and Alpha turns that switch on
 * for a tester exactly once: the state is three-valued (never touched / on / off by hand), so it
 * is stored as two facts and the two rules are pinned here.
 *
 * The copy itself is #417's block: the command is a second *door*, not a second report, so this
 * also proves the bytes the two doors copy are the same.
 */
class DiagnosticSummaryTest {

    /** The `hv` prefs the switch lives in, in memory. */
    private class Fake(val m: MutableMap<String, Any> = LinkedHashMap()) : DiagnosticSummary.Store {
        override fun contains(key: String) = m.containsKey(key)
        override fun getBoolean(key: String, default: Boolean) = (m[key] as? Boolean) ?: default
        override fun putBoolean(key: String, value: Boolean) { m[key] = value }
    }

    private val off = Fake()
    private val on = Fake().apply { DiagnosticSummary.setByUser(this, true) }

    // ---- the switch's own default ---------------------------------------------------------

    @Test fun the_switch_is_off_by_default() {
        assertFalse(DiagnosticSummary.DEFAULT_ON)
        assertFalse(DiagnosticSummary.on(Fake()))
        assertFalse(DiagnosticSummary.chosen(Fake()))   // nothing decided yet
    }

    /**
     * With the switch off the command is not a hidden row — it is a name no build answers to.
     * The sheet, the search, the /help card and a typed word all reach the same conclusion, and
     * the typed word is the one that must never copy silently.
     */
    @Test fun the_hidden_case_is_the_same_treatment_an_unknown_name_gets() {
        val gate = DiagnosticSummary.gate(off)
        assertNull(DiagnosticSummary.commandFor("/diagnostics", off))
        assertNull(DiagnosticSummary.commandFor("diagnostics", off))     // as dictated
        assertNull(CommandCatalog.find("/diagnostics", gate))
        assertNull(CommandCatalog.find("/diagnostics"))                  // the fresh-install default
        assertTrue(CommandCatalog.grouped().none { (_, cs) -> cs.any { it.name == DiagnosticSummary.COMMAND } })
        assertTrue(CommandCatalog.search("bug", gate).none { it.name == DiagnosticSummary.COMMAND })
        assertFalse(CommandCatalog.helpText(gate).contains(DiagnosticSummary.COMMAND))
        // and it is exactly what an unknown name gets: no command, no special case
        assertNull(CommandCatalog.find("/nope", gate))
        assertNull(DiagnosticSummary.commandFor("/nope", off))
    }

    @Test fun with_the_switch_on_the_command_exists_and_a_typed_word_reaches_it() {
        val c = DiagnosticSummary.commandFor("/diagnostics", on)
        assertNotNull(c)
        assertEquals(DiagnosticSummary.COMMAND, c!!.name)
        assertEquals(c, DiagnosticSummary.commandFor("  /DIAGNOSTICS ", on))   // case and space
        assertEquals(c, CommandCatalog.find("/diagnostics", DiagnosticSummary.gate(on)))
    }

    /** Only the word: a sentence that happens to contain it is conversation, not a command. */
    @Test fun only_the_word_itself_is_the_command() {
        assertNull(DiagnosticSummary.commandFor("/diagnostics now please", on))
        assertNull(DiagnosticSummary.commandFor("what's in the diagnostics", on))
        assertNull(DiagnosticSummary.commandFor("", on))
    }

    // ---- Alpha ----------------------------------------------------------------------------

    /** The table the owner asked for, in one place: toggle × alpha in every combination. */
    @Test fun the_four_combinations_of_toggle_and_alpha() {
        // 1. never touched, alpha off — off (the shipped default)
        val a = Fake()
        assertFalse(DiagnosticSummary.on(a))

        // 2. never touched, alpha on — on, because alpha is turned on (see below)
        val b = Fake().apply { DiagnosticSummary.alphaTurnedOn(this) }
        assertTrue(DiagnosticSummary.on(b))

        // 3. on by hand, alpha off — still on: the Alpha rule is one-way
        val c = Fake().apply { DiagnosticSummary.setByUser(this, true) }
        assertTrue(DiagnosticSummary.on(c))

        // 4. on by hand, alpha on — on
        val d = Fake().apply {
            DiagnosticSummary.setByUser(this, true)
            DiagnosticSummary.alphaTurnedOn(this)
        }
        assertTrue(DiagnosticSummary.on(d))

        // and the command follows the switch in every one of them, never Alpha alone
        for ((store, exists) in listOf(a to false, b to true, c to true, d to true))
            assertEquals(exists, DiagnosticSummary.commandFor("/diagnostics", store) != null)
    }

    @Test fun turning_alpha_on_turns_the_command_on() {
        val s = Fake()
        DiagnosticSummary.alphaTurnedOn(s)
        assertTrue(DiagnosticSummary.on(s))
        assertTrue(DiagnosticSummary.commandFor("/diagnostics", s) != null)
    }

    /** One-way: Alpha going off is not a reason to take a command away. */
    @Test fun turning_alpha_off_leaves_it_on() {
        val s = Fake().apply { DiagnosticSummary.alphaTurnedOn(this) }
        // Alpha's own switch is not this object's to write; nothing here turns the command off.
        assertTrue(DiagnosticSummary.on(s))
        assertEquals(1, s.m.size)   // alphaTurnedOn wrote the one pref and nothing else
    }

    /**
     * The case the owner named: a user who turned it *off* while Alpha was on keeps it off, and
     * the next Alpha-on does not quietly re-enable it. Without the "chosen" fact, "off" and
     * "never touched" would look the same and Alpha would overrule a deliberate choice.
     */
    @Test fun a_hand_set_off_survives_the_next_alpha_on() {
        val s = Fake()
        DiagnosticSummary.alphaTurnedOn(s)          // alpha turned it on
        assertTrue(DiagnosticSummary.on(s))
        DiagnosticSummary.setByUser(s, false)       // the user says no
        assertFalse(DiagnosticSummary.on(s))
        assertTrue(DiagnosticSummary.chosen(s))
        assertNull(DiagnosticSummary.commandFor("/diagnostics", s))

        DiagnosticSummary.alphaTurnedOn(s)          // alpha on again
        assertFalse("an explicit off must win", DiagnosticSummary.on(s))
        assertNull(DiagnosticSummary.commandFor("/diagnostics", s))
    }

    /** The mirror case: turning it on by hand also outranks Alpha, so Alpha-off cannot be
     *  mistaken for a reason to hide it. */
    @Test fun a_hand_set_on_is_left_alone() {
        val s = Fake().apply { DiagnosticSummary.setByUser(this, true) }
        DiagnosticSummary.setByUser(s, true)
        assertTrue(DiagnosticSummary.on(s))
        assertTrue(DiagnosticSummary.chosen(s))
    }

    // ---- the switch in Settings ------------------------------------------------------------

    /**
     * It sits where the owner asked: the developer section of Settings, beside the three
     * logging switches, with the subtitle that says what it adds and what it never contains.
     */
    @Test fun the_switch_sits_beside_the_logging_switches_in_about() {
        val layout = File("src/main/res/layout/activity_settings.xml").readText()
        val row = layout.indexOf("@+id/row_diag_summary")
        val about = layout.indexOf("@+id/sec_about")
        assertTrue("the row is missing", row > 0 && about >= 0)
        assertTrue("the row must be inside About & Diagnostics", row > about)
        // Beside the logging toggles: after the last of them.
        assertTrue("the row should follow the logging switches",
            row > layout.indexOf("@+id/set_debuglog"))
        assertTrue(layout.contains("@+id/set_diag_summary"))
        assertTrue(layout.contains(
            "Adds a command that copies a report block for a bug report; contains no conversations."))
        // The switch is bound, and the key itself is never read here: the screen asks the
        // policy object, so "nothing else reads that pref" is checkable, not a promise.
        val activity = File("src/main/java/com/hermesvox/SettingsActivity.kt").readText()
        assertTrue(activity.contains("DiagnosticSummary.setByUser(DiagnosticSummary.store(prefs), on)"))
        assertFalse(activity.contains("\"diag_summary\""))
    }

    // ---- the two doors, byte for byte -----------------------------------------------------

    /**
     * The command copies #417's block, not a second wording of it. Both doors are run here —
     * the Presence section's collector path and the command's — and the strings compared.
     */
    @Test fun the_command_copies_the_same_bytes_as_the_presence_row() {
        val settings = PresenceDiagnostic.Settings(
            presenceEnabled = true, soulNarration = false, voice = "spoken",
            density = "chatty", hears = false, semanticBarge = true,
            reasoning = "medium", echoMs = 1500,
        )
        val facts = PresenceDiagnosticCollect.Facts(
            versionName = "0.9.3-nightly-abc1234",
            device = PresenceDiagnostic.Device("Google", "Pixel 7", "14"),
            mind = "lan",
            session = PresenceDiagnostic.Session(
                e2P50Ms = 3120, turns = 12, answeredByVoice = 3, escalated = 4),
            model = PresenceDiagnosticCollect.MODEL_ID,
        )

        val fromThePresenceRow = PresenceDiagnosticCollect.block(settings, facts)
        val fromTheCommand = DiagnosticSummary.block(settings, facts)

        assertEquals(fromThePresenceRow, fromTheCommand)
        // and that block is the collector's, printed once — not re-worded by a door
        assertEquals(
            PresenceDiagnostic.block(
                PresenceDiagnostic.Input(
                    build = facts.versionName, settings = settings, device = facts.device,
                    mind = facts.mind, session = facts.session, model = facts.model)),
            fromTheCommand,
        )
        assertTrue(fromTheCommand.startsWith(PresenceDiagnostic.MARKER))
        // The command's own line says so in the report, so a pasted block is attributable.
        assertTrue(fromTheCommand.contains("build: 0.9.3-nightly-abc1234"))
    }

    /**
     * Two doors, one copy path: both hand the clipboard to [DiagnosticClipboard], and neither
     * assembles a block of its own. A third door (or a door that "just tweaks the wording")
     * fails here instead of shipping a second report.
     */
    @Test fun both_doors_go_through_the_same_copy_path() {
        val src = "src/main/java/com/hermesvox"
        val settings = File(src, "SettingsActivity.kt").readText()
        val main = File(src, "MainActivity.kt").readText()
        assertTrue("the Presence row no longer copies through the shared helper",
            settings.contains("DiagnosticClipboard.copy(this, prefs)"))
        assertTrue("the /diagnostics command does not copy through the shared helper",
            main.contains("DiagnosticSummary.COMMAND -> done(DiagnosticClipboard.copy(this, prefs))"))
        for ((name, text) in listOf("SettingsActivity.kt" to settings, "MainActivity.kt" to main)) {
            assertTrue("$name assembles a block of its own", !text.contains("PresenceDiagnostic.block("))
            assertTrue("$name writes the report block to the clipboard directly",
                !text.contains("newPlainText(\"vox-diag\""))
        }
        val helper = File(src, "DiagnosticClipboard.kt").readText()
        assertTrue("the helper does not use #417's collector",
            helper.contains("PresenceDiagnosticCollect.collect(activity, prefs)"))
    }
}
