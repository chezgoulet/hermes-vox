package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #402/D29 — the Alpha gate: one master switch, off by default, and feature sets selected one by
 * one, Presence first. Acceptance: a fresh install reaches nothing alpha; turning the master on
 * and selecting one set enables exactly that set; turning it off returns everything to dimmed.
 */
class AlphaGateTest {

    private class Fake(val m: MutableMap<String, Any> = HashMap()) : AlphaGate.Store {
        override fun getBoolean(key: String, default: Boolean) = (m[key] as? Boolean) ?: default
        override fun contains(key: String) = m.containsKey(key)
        override fun getString(key: String) = m[key] as? String
        override fun putBoolean(key: String, value: Boolean) { m[key] = value }
    }

    private val P = AlphaGate.FeatureSet.PRESENCE
    private val L = AlphaGate.FeatureSet.CONTINUOUS_LISTENING

    @Test fun presence_is_the_first_set_and_every_set_has_its_own_key() {
        assertEquals(P, AlphaGate.FeatureSet.entries.first())
        val keys = AlphaGate.FeatureSet.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertFalse(keys.contains(AlphaGate.KEY_MASTER))
    }

    /** 0.9.1 soak: the panel states the trade it asks for, in measured round numbers. */
    @Test fun the_panel_says_what_presence_and_continuous_listening_cost() {
        assertTrue(P.blurb, P.blurb.contains("While a call is live it holds about 3 GB of memory."))
        assertTrue(L.blurb, L.blurb.contains("Costs about 300 MB more memory during a call."))
        // Each cost lives on its own set: Presence does not claim the listening, nor the reverse.
        assertFalse(P.blurb.contains("300 MB"))
        assertFalse(L.blurb.contains("3 GB"))
        // The panel row shows the blurb itself, so this is the text on screen.
        val panel = File("src/main/java/com/hermesvox/AlphaPanel.kt").readText()
        assertTrue(panel.contains("this.text = set.blurb"))
    }

    @Test fun a_fresh_install_reaches_nothing_alpha() {
        val s = Fake()
        AlphaGate.migrate(s, presenceModelInstalled = false)
        assertEquals(false, s.m[AlphaGate.KEY_MASTER])
        assertFalse(AlphaGate.masterOn(s))
        AlphaGate.FeatureSet.entries.forEach { assertFalse(it.name, AlphaGate.allows(s, it)) }
    }

    @Test fun a_fresh_install_with_the_model_already_on_disk_is_still_off() {
        // A model on disk is not a choice. Only a Presence key written by an earlier version is.
        val s = Fake()
        AlphaGate.migrate(s, presenceModelInstalled = true)
        assertFalse(AlphaGate.masterOn(s))
        assertFalse(AlphaGate.allows(s, P))
    }

    @Test fun master_on_and_one_set_selected_enables_exactly_that_set() {
        val phone = AlphaGate.FeatureSet.PHONE_TOOLS
        val s = Fake(mutableMapOf(AlphaGate.KEY_MASTER to true, phone.key to true))
        assertTrue(AlphaGate.allows(s, phone))
        assertFalse(AlphaGate.allows(s, P))
        assertEquals(1, AlphaGate.selectedCount(s))
    }

    @Test fun master_off_returns_everything_to_off_and_keeps_the_selections() {
        val s = Fake(mutableMapOf(AlphaGate.KEY_MASTER to true, P.key to true, L.key to true))
        assertTrue(AlphaGate.allows(s, P))
        s.putBoolean(AlphaGate.KEY_MASTER, false)
        AlphaGate.FeatureSet.entries.forEach { assertFalse(AlphaGate.allows(s, it)) }
        // The tester's picks survive, so turning Alpha back on restores the same sets.
        assertEquals(2, AlphaGate.selectedCount(s))
        s.putBoolean(AlphaGate.KEY_MASTER, true)
        assertTrue(AlphaGate.allows(s, P))
    }

    @Test fun a_selected_set_without_the_master_is_not_allowed() {
        val s = Fake(mutableMapOf(P.key to true))
        assertFalse(AlphaGate.allows(s, P))
    }

    @Test fun phone_tools_is_a_set_with_its_own_key_and_the_owners_words() {
        val set = AlphaGate.FeatureSet.PHONE_TOOLS
        assertEquals("alpha_set_phone_tools", set.key)
        assertEquals("Phone tools", set.label)
        assertTrue(set.blurb, set.blurb.contains("Vox runs a small server on your tailnet with its own token"))
        assertTrue(set.blurb, set.blurb.contains("Off until you set it up."))
        assertFalse("the gate is the only thing that enables it", AlphaGate.allows(Fake(), set))
    }

    /**
     * #437: the session browser is a set of its own, the sibling of Switching profiles — the
     * same class of unfinished surface, off until selected, with the owner's words on the panel.
     *
     * Superseded: Sessions left Alpha (D39, 2026-11-06), so the set is gone from
     * [AlphaGate.FeatureSet]. The claims below are what remains true of it as a feature.
     */
    @Test fun sessions_is_no_longer_a_set_and_the_word_needs_no_gate() {
        assertFalse("the set left the panel",
            AlphaGate.FeatureSet.entries.any { it.key == "alpha_set_sessions" })
        assertFalse("nothing selects it any more",
            AlphaGate.FeatureSet.entries.any { it.label == "Sessions" })
        // The word reaches the card with the master off and nothing selected.
        assertEquals(Sessions.COMMAND, Sessions.commandFor("/sessions")?.name)
        assertEquals(Sessions.COMMAND, Sessions.commandFor("  Sessions ")?.name)
        // It is not a gated command: the catalogue lists it even for a gate that allows nothing.
        assertTrue("the catalogue no longer hides it",
            CommandCatalog.commands(CommandCatalog.Gate.NOTHING).any { it.name == Sessions.COMMAND })
        // It is still the last-but-one row of the conversation group, beside /new.
        val c = CommandCatalog.find("/sessions", CommandCatalog.Gate.EVERYTHING)!!
        assertEquals("This conversation", c.group.label)
        assertNull("nothing is lost, so there is no cost line", c.cost)
    }

    @Test fun quiet_calls_is_not_an_alpha_set_any_more() {        val gate = File(src, "AlphaGate.kt").readText()
        val callQuiet = File(src, "CallQuiet.kt").readText()
        val settings = File(src, "SettingsActivity.kt").readText()
        assertFalse("no enum entry", gate.contains("QUIET_CALLS"))
        assertFalse("no key use", gate.contains("alpha_set_quiet_calls"))
        assertFalse("enabled is its own preference alone", callQuiet.contains("AlphaGate"))
        assertFalse("the Hearing row is not gated", settings.contains("row_call_quiet, AlphaGate"))
        assertFalse(AlphaGate.FeatureSet.entries.any { it.key == "alpha_set_quiet_calls" })
    }

    /**
     * #437 §1, as amended by D39: Sessions left Alpha, so no door asks the gate any more —
     * with the master off, `/sessions` is on the sheet, opens the card and may adopt. What
     * remains is that the pick still runs: nothing else adopts a session.
     */
    @Test fun sessions_are_reachable_with_the_master_gate_off() {
        val main = File(src, "MainActivity.kt").readText()
        assertFalse("no door refuses on Alpha", main.contains("Sessions.refusal("))
        // The pick is still the only path, and it still sets the conversation.
        val adopt = main.substringAfter("private fun adoptSession(row: Sessions.Row): Boolean {")
            .substringBefore("clearConversationUi()")
        assertFalse(adopt, adopt.contains("AlphaGate"))
        assertTrue(adopt, adopt.contains("s.adoptConversation(row.id)"))
        // The card's door is not gated either.
        val show = main.substringAfter("protected fun showSessions() {").substringBefore("\n    }")
        assertFalse(show, show.contains("AlphaGate"))
        assertTrue(show, show.contains("sessionsSheet.show()"))
        // A typed or dictated word goes straight to the command.
        assertTrue(main.contains("Sessions.commandFor(text)?.let"))
        assertTrue(main.contains("Sessions.COMMAND ->"))
    }

    @Test fun an_upgrade_that_had_presence_running_keeps_it() {
        // 0.9.0 wrote presence_enabled=true on first start; with the model installed it ran.
        val s = Fake(mutableMapOf(PresenceGate.KEY_PRESENCE_ENABLED to true))
        AlphaGate.migrate(s, presenceModelInstalled = true)
        assertTrue(AlphaGate.allows(s, P))
        assertFalse("continuous capture shipped off and was not on", AlphaGate.allows(s, L))
    }

    @Test fun an_upgrade_that_chose_presence_without_its_model_was_not_running_it() {
        val s = Fake(mutableMapOf(PresenceGate.KEY_PRESENCE_ENABLED to true))
        AlphaGate.migrate(s, presenceModelInstalled = false)
        assertFalse(AlphaGate.masterOn(s))
    }

    @Test fun an_upgrade_that_had_presence_off_stays_off() {
        val s = Fake(mutableMapOf(PresenceGate.KEY_PRESENCE_ENABLED to false))
        AlphaGate.migrate(s, presenceModelInstalled = true)
        assertFalse(AlphaGate.masterOn(s))
    }

    @Test fun an_upgrade_with_continuous_capture_on_keeps_the_choice_but_it_is_inert_without_presence() {
        val s = Fake(mutableMapOf(PresenceGate.KEY_PRESENCE_ENABLED to false, ContinuousCapture.KEY to true))
        AlphaGate.migrate(s, presenceModelInstalled = false)
        // The tester's pick survives, but the dependency refuses it: an earlier build cannot
        // leave continuous listening running with no being to hear for.
        assertTrue("the stored selection is kept", AlphaGate.selected(s, L))
        assertFalse("and is inert until Presence is selected", AlphaGate.allows(s, L))
        assertFalse(AlphaGate.allows(s, P))
        s.putBoolean(P.key, true)
        assertTrue(AlphaGate.allows(s, L))
    }

    @Test fun the_legacy_enhanced_choice_counts_as_presence_running() {
        val s = Fake(mutableMapOf(PresenceGate.LEGACY_KEY_VOICE_MODE to PresenceGate.LEGACY_ENHANCED))
        AlphaGate.migrate(s, presenceModelInstalled = true)
        assertTrue(AlphaGate.allows(s, P))
    }

    @Test fun the_migration_runs_once() {
        val s = Fake(mutableMapOf(AlphaGate.KEY_MASTER to false, PresenceGate.KEY_PRESENCE_ENABLED to true))
        AlphaGate.migrate(s, presenceModelInstalled = true)
        assertFalse("a stored master is never rewritten", AlphaGate.masterOn(s))
    }

    @Test fun continuous_listening_switches_default_on_behind_the_gate() {
        // D29: their preferences default on; the gate is what keeps them from running.
        assertTrue(ContinuousCapture.DEFAULT_ON)
        assertTrue(SemanticEndpoint.DEFAULT_ON)
    }

    /**
     * D38: continuous listening is how the being hears, so it belongs with Presence. The stored
     * preference is not enough — an earlier build could have left it selected without Presence.
     */
    @Test fun continuous_listening_cannot_run_without_presence() {
        val s = Fake(mutableMapOf(AlphaGate.KEY_MASTER to true, L.key to true))
        assertFalse("selected alone is inert", AlphaGate.allows(s, L))
        assertEquals(
            "Requires Presence: continuous listening is how the being hears.",
            AlphaGate.dependencyRefusal(s, L),
        )
        s.putBoolean(P.key, true)
        assertNull("Presence makes it reachable", AlphaGate.dependencyRefusal(s, L))
        assertTrue(AlphaGate.allows(s, L))
        // Turning Presence off leaves the stored selection in place but inert.
        s.putBoolean(P.key, false)
        assertTrue(AlphaGate.selected(s, L))
        assertFalse(AlphaGate.allows(s, L))
        // The master still rules everything.
        s.putBoolean(AlphaGate.KEY_MASTER, false)
        assertFalse(AlphaGate.allows(s, L))
    }

    /** D38: Phone tools is the mind acting on the phone; Presence is the being. Neither needs the other. */
    @Test fun phone_tools_and_presence_are_independent_sets() {
        val phoneOnly = Fake(mutableMapOf(
            AlphaGate.KEY_MASTER to true,
            AlphaGate.FeatureSet.PHONE_TOOLS.key to true,
        ))
        assertTrue("the phone is the mind's capability", AlphaGate.allows(phoneOnly, AlphaGate.FeatureSet.PHONE_TOOLS))
        assertFalse("and not a reason to run the being", AlphaGate.allows(phoneOnly, P))

        val presenceOnly = Fake(mutableMapOf(
            AlphaGate.KEY_MASTER to true,
            P.key to true,
        ))
        assertTrue("the being is a voice", AlphaGate.allows(presenceOnly, P))
        assertFalse("and it does not open the phone tool", AlphaGate.allows(presenceOnly, AlphaGate.FeatureSet.PHONE_TOOLS))
        assertNull("the dependency table only names continuous listening",
            AlphaGate.dependencyRefusal(phoneOnly, AlphaGate.FeatureSet.PHONE_TOOLS))
    }

    /** The picker refuses the impossible selection where the user makes it, with the reason on screen. */
    @Test fun the_picker_refuses_continuous_listening_without_presence() {
        val panel = File(src, "AlphaPanel.kt").readText()
        assertTrue("the row asks the one dependency question", panel.contains("AlphaGate.dependencyRefusal("))
        assertTrue("a refused tap does not store the preference", panel.contains("if (refusal != null)"))
        assertTrue("the row shows the returned line", panel.contains("text = if (inert) \"Inert — \$why\" else why"))
        val gate = File(src, "AlphaGate.kt").readText()
        assertTrue("the line says why", gate.contains("Requires Presence: continuous listening is how the being hears."))
        assertTrue("allows() refuses the stale preference too",
            gate.contains("dependencyRefusal(r, set) == null"))
    }

    /**
     * #436/D39: Switching profiles left Alpha on 2026-11-06, its device pass made, so the set is
     * gone and a profile switch is reachable with the master off and nothing selected.
     */
    @Test fun profiles_are_reachable_with_the_master_gate_off() {
        assertFalse("the set left the panel",
            AlphaGate.FeatureSet.entries.any { it.key == "alpha_set_profiles" })
        assertFalse("nothing selects it any more",
            AlphaGate.FeatureSet.entries.any { it.label == "Switching profiles" })
        val main = File(src, "MainActivity.kt").readText()
        // The last door asks nothing of the gate any more.
        val switch = main.substringAfter("private fun switchProfile(p: Profiles.Profile) {")
            .substringBefore("protected fun connectFromPrefs")
        assertFalse(switch, switch.contains("AlphaGate"))
        assertTrue("the switch still switches", switch.contains("putString(Profiles.PREF_ACTIVE"))
        // Nor does the command, nor the picker's opening.
        assertTrue(main.contains("\"/profiles\" -> {"))
        val dialogs = File(src, "ProfileDialogs.kt").readText()
        assertFalse(dialogs, dialogs.contains("AlphaGate"))
        // The catalogue no longer hides it either.
        assertTrue(CommandCatalog.commands(CommandCatalog.Gate.NOTHING)
            .any { it.name == "/profiles" })
    }

    /**
     * The panel is drawn from [AlphaGate.FeatureSet.entries], so the list the owner sees is this
     * list: four sets, and no row left behind for the two that left Alpha.
     */
    @Test fun the_panel_lists_four_sets_and_no_stale_row() {
        assertEquals(
            listOf("Presence", "Continuous listening", "Turn-taking", "Phone tools"),
            AlphaGate.FeatureSet.entries.map { it.label },
        )
        assertEquals(4, AlphaGate.FeatureSet.entries.size)
        val gate = File(src, "AlphaGate.kt").readText()
        assertFalse(gate.contains("alpha_set_profiles"))
        assertFalse(gate.contains("alpha_set_sessions"))
        // The panel renders exactly the entries and nothing else.
        val panel = File(src, "AlphaPanel.kt").readText()
        assertTrue(panel.contains("for (set in AlphaGate.FeatureSet.entries) list.addView(row(set))"))
        assertFalse("no hand-written row survives", panel.contains("\"Switching profiles\""))
        assertFalse(panel.contains("\"Sessions\""))
    }

    // ---- the wiring, pinned at source level (the readers are Android-bound) ----

    private val src = File("src/main/java/com/hermesvox")

    @Test fun presence_runs_only_through_the_gate() {
        val gate = File(src, "PresenceGate.kt").readText()
        assertTrue(gate.contains("AlphaGate.allows(prefs, AlphaGate.FeatureSet.PRESENCE) && chosen(prefs)"))
    }

    @Test fun the_call_reads_continuous_listening_through_the_gate() {
        val vc = File(src, "VoiceController.kt").readText()
        assertTrue(vc.contains("listeningAlpha && micBool(ContinuousCapture.KEY"))
        assertTrue(vc.contains("listeningAlpha && micBool(SemanticEndpoint.KEY"))
    }

    @Test fun the_master_switch_is_the_first_row_of_settings() {
        val layout = File("src/main/res/layout/activity_settings.xml").readText()
        val list = layout.substringAfter("@+id/grp_list\"")
        assertTrue(list.indexOf("@+id/row_grp_alpha\"") in 0 until list.indexOf("@+id/row_grp_models\""))
    }

    @Test fun settings_dims_every_alpha_row() {
        val s = File(src, "SettingsActivity.kt").readText()
        for (row in listOf("row_grp_presence", "row_er_hears", "row_mic_continuous", "row_mic_semantic")) {
            assertTrue(row, s.contains("gate(R.id.$row,"))
        }
    }

    /**
     * D39, met: the header is still a connection fact and not a control, and it is unchanged by
     * the promotion. The switcher's doors are pinned by
     * [profiles_are_reachable_with_the_master_gate_off].
     */
    @Test fun the_header_names_the_active_profile_and_is_not_a_control() {
        val main = File(src, "MainActivity.kt").readText()
        val header = main.substringAfter("private fun headerLabel(model: String): String {")
            .substringBefore("fun switchProfile")
        assertTrue(header, header.contains("profiles.activeName()"))
        assertFalse(header, header.contains("AlphaGate"))
        // The commands sheet is still the way in, and it still asks the one gate object.
        assertTrue(File(src, "CommandsSheet.kt").readText().contains("gate = gate"))
        assertTrue(main.contains("CommandGate.of(DiagnosticSummary.store(prefs))"))
    }
}
