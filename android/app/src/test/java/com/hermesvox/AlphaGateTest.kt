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

    @Test fun switching_profiles_is_a_set_with_its_own_key_and_the_owners_words() {
        val set = AlphaGate.FeatureSet.PROFILES
        assertEquals("alpha_set_profiles", set.key)
        assertEquals("Switching profiles", set.label)
        assertTrue(set.blurb, set.blurb.contains("switching starts a fresh conversation"))
        assertTrue(set.blurb, set.blurb.contains("#436"))
        assertFalse("the gate is the only thing that enables it", AlphaGate.allows(Fake(), set))
    }

    /**
     * #437: the session browser is a set of its own, the sibling of Switching profiles — the
     * same class of unfinished surface, off until selected, with the owner's words on the panel.
     */
    @Test fun sessions_is_a_set_with_its_own_key_and_the_owners_words() {
        val set = AlphaGate.FeatureSet.SESSIONS
        assertEquals("alpha_set_sessions", set.key)
        assertEquals("Sessions", set.label)
        assertTrue(set.blurb, set.blurb.contains("See the conversations your agent already has"))
        assertTrue(set.blurb, set.blurb.contains("including ones you started at your desk"))
        assertTrue(set.blurb, set.blurb.contains("continues the same conversation"))
        // Selecting it without the master is not enough, exactly like every other set.
        assertFalse("the gate is the only thing that enables it", AlphaGate.allows(Fake(), set))
        assertTrue(AlphaGate.allows(Fake(mutableMapOf(AlphaGate.KEY_MASTER to true, set.key to true)), set))
        // It is the last set, after Switching profiles: the two leave Alpha together.
        assertEquals(set, AlphaGate.FeatureSet.entries.last())
        assertEquals(AlphaGate.FeatureSet.PROFILES,
            AlphaGate.FeatureSet.entries[AlphaGate.FeatureSet.entries.size - 2])
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
     * #437 §1: with the Sessions set off, the command is absent AND no other path adopts a
     * session. The catalogue gate is one half; the last door is the other, and both refuse.
     */
    @Test fun sessions_are_gated_at_every_door() {
        val main = File(src, "MainActivity.kt").readText()
        // The pick refuses before it writes anything.
        val adopt = main.substringAfter("private fun adoptSession(row: Sessions.Row): Boolean {")
            .substringBefore("val s = session")
        assertTrue(adopt, adopt.contains("Sessions.refusal(AlphaGate.reader(prefs))"))
        assertTrue(adopt, adopt.contains("return false"))
        // The card does not open either.
        val show = main.substringAfter("protected fun showSessions() {").substringBefore("if (missingKeyPrompt())")
        assertTrue(show, show.contains("Sessions.refusal(AlphaGate.reader(prefs))"))
        // A typed or dictated word goes through the same catalogue gate as the sheet.
        assertTrue(main.contains("Sessions.commandFor(text, commandGate())"))
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

    /** Sessions and Profiles were not promoted by this change; both still need the gate and a pick. */
    @Test fun sessions_and_profiles_stay_gated() {
        val masterOnly = Fake(mutableMapOf(AlphaGate.KEY_MASTER to true))
        for (set in listOf(AlphaGate.FeatureSet.PROFILES, AlphaGate.FeatureSet.SESSIONS)) {
            assertFalse("$set needs its own selection", AlphaGate.allows(masterOnly, set))
            masterOnly.putBoolean(set.key, true)
            assertTrue("$set works once selected behind the master", AlphaGate.allows(masterOnly, set))
            masterOnly.putBoolean(set.key, false)
        }
        val withoutMaster = Fake(mutableMapOf(
            AlphaGate.FeatureSet.PROFILES.key to true,
            AlphaGate.FeatureSet.SESSIONS.key to true,
        ))
        assertFalse(AlphaGate.allows(withoutMaster, AlphaGate.FeatureSet.PROFILES))
        assertFalse(AlphaGate.allows(withoutMaster, AlphaGate.FeatureSet.SESSIONS))
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

    @Test fun profile_switching_is_gated_at_every_door_but_the_header_stays() {
        val main = File(src, "MainActivity.kt").readText()
        val sheet = File(src, "ProfileSheet.kt").readText()
        val dialogs = File(src, "ProfileDialogs.kt").readText()
        val commands = File(src, "CommandsSheet.kt").readText()
        // The last door refuses before it writes anything.
        val switch = main.substringAfter("private fun switchProfile(p: Profiles.Profile) {")
            .substringBefore("// Settings → Entity edits")
        assertTrue(switch, switch.contains("AlphaGate.allows(prefs, AlphaGate.FeatureSet.PROFILES)"))
        assertTrue(switch, switch.contains("return"))
        // The row, the search and the manage rows all ask the same question.
        assertTrue(commands.contains("gate = gate"))
        assertTrue(main.contains("AlphaGate.allows(prefs, AlphaGate.FeatureSet.PROFILES)"))
        // #501: the composition lives in CommandGate, which MainActivity hands the sheet.
        assertTrue(main.contains("CommandGate.of(AlphaGate.reader(prefs), DiagnosticSummary.store(prefs))"))
        val gate = File(src, "CommandGate.kt").readText()
        assertTrue(gate.contains("\"/profiles\" -> AlphaGate.allows(reader, AlphaGate.FeatureSet.PROFILES)"))
        assertTrue(sheet.contains("if (!allowed())"))
        assertTrue(dialogs.contains("AlphaGate.allows(activity, AlphaGate.FeatureSet.PROFILES)"))
        // The active profile is a connection fact, not a control: the header is not gated.
        val header = main.substringAfter("private fun headerLabel(model: String): String {")
            .substringBefore("fun switchProfile")
        assertTrue(header, header.contains("profiles.activeName()"))
        assertFalse(header, header.contains("AlphaGate"))
    }
}
