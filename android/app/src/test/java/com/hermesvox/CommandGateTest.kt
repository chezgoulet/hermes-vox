package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #4/#501, as amended by #437/D39: `/sessions` left the Alpha gate on 2026-11-06, so it is on
 * the sheet for every install — with the master off and nothing selected. `/profiles` is still
 * behind Alpha here; the diagnostic summary keeps its own switch. The gate is read when asked,
 * not when the sheet is built.
 */
class CommandGateTest {

    /** Alpha's reader, in memory: the same keys the panel writes. */
    private class Alpha : AlphaGate.Reader {
        val on = HashSet<String>()
        override fun getBoolean(key: String, default: Boolean) = if (key in on) true else default
        fun master(m: Boolean) { if (m) on += AlphaGate.KEY_MASTER else on -= AlphaGate.KEY_MASTER }
        fun set(s: AlphaGate.FeatureSet, v: Boolean) { if (v) on += s.key else on -= s.key }
    }

    /** The diagnostic switch, in memory. */
    private class Diagnostics : DiagnosticSummary.Store {
        val flags = HashMap<String, Boolean>()
        override fun contains(key: String) = key in flags
        override fun getBoolean(key: String, default: Boolean) = flags[key] ?: default
        override fun putBoolean(key: String, value: Boolean) { flags[key] = value }
    }

    private fun rows(gate: CommandCatalog.Gate) =
        CommandCatalog.grouped(gate = gate).flatMap { it.second }.map { it.name }

    /** D39: no master, no set — and `/sessions` is there anyway. */
    @Test fun withAlphaOffSessionsIsStillOnTheSheet() {
        val a = Alpha(); val d = Diagnostics()
        val rows = rows(CommandGate.of(a, d))
        assertTrue("/sessions left the gate", rows.contains("/sessions"))
        assertTrue("the rest of the catalogue is untouched", rows.contains("/new"))
        assertTrue(rows.contains("/models"))
        // And the word reaches it too: no gate stands between the two.
        assertEquals("/sessions", Sessions.commandFor("/sessions")?.name)
    }

    @Test fun theSessionsRowIsInItsGroupWithItsDescription() {
        val a = Alpha(); val d = Diagnostics()
        val groups = CommandCatalog.grouped(gate = CommandGate.of(a, d))
        val conversation = groups.first { it.first == CommandCatalog.Group.CONVERSATION }
        val row = conversation.second.firstOrNull { it.name == "/sessions" }
        assertTrue("/sessions is not under This conversation", row != null)
        assertEquals("Pick up a thread the agent already has", row!!.effect)
        assertEquals(Marks.Mark.SESSIONS, row.glyph)
        assertEquals("nothing is lost, so there is no cost line", null, row.cost)
    }

    /** Profiles' turn had not come yet: it still needs the master and its own set. */
    @Test fun profilesStillNeedsTheMasterAndItsSet() {
        val a = Alpha(); val d = Diagnostics()
        assertFalse("/profiles", rows(CommandGate.of(a, d)).contains("/profiles"))
        a.master(true)   // master on, Switching profiles not selected
        assertFalse("/profiles", rows(CommandGate.of(a, d)).contains("/profiles"))
        a.set(AlphaGate.FeatureSet.PROFILES, true)
        assertTrue("/profiles", rows(CommandGate.of(a, d)).contains("/profiles"))
        a.master(false)  // master off overrides the set
        assertFalse("/profiles", rows(CommandGate.of(a, d)).contains("/profiles"))
    }

    /**
     * The sheet is built once and kept; the gate must not answer from that moment. A person
     * turns Switching profiles on in Settings, comes back, and the row is there.
     */
    @Test fun aSetTurnedOnAfterTheSheetWasBuiltShowsWithoutARestart() {
        val a = Alpha(); val d = Diagnostics()
        val gate = CommandGate.of(a, d)          // the sheet captures this object
        assertFalse(rows(gate).contains("/profiles"))
        a.master(true); a.set(AlphaGate.FeatureSet.PROFILES, true)
        assertTrue("the gate cached its answer", rows(gate).contains("/profiles"))
        a.set(AlphaGate.FeatureSet.PROFILES, false)
        assertFalse(rows(gate).contains("/profiles"))
    }

    /** The diagnostic switch is the one optional command left, and it keeps its own gate. */
    @Test fun theDiagnosticSwitchKeepsItsOwnGate() {
        val a = Alpha(); val d = Diagnostics()
        val gate = CommandGate.of(a, d)
        assertFalse("/diagnostics", rows(gate).contains("/diagnostics"))
        d.putBoolean(DiagnosticSummary.PREF, true)
        assertTrue(rows(gate).contains("/diagnostics"))
        // And it does not depend on Alpha at all.
        a.master(false)
        assertTrue(rows(gate).contains("/diagnostics"))
    }
}
