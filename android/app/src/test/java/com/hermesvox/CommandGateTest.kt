package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #4/#501: `/sessions` is registered under This conversation but gated behind the Alpha
 * **Sessions** set, so with the gate off it is not on the sheet. This pins the state the
 * owner asked about — with the gate ON and Sessions selected it must be there, in its group,
 * with its one-line description — and that turning the set on takes effect without a restart.
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

    @Test fun withAlphaOffSessionsIsSimplyNotThere() {
        val a = Alpha(); val d = Diagnostics()
        val gate = CommandGate.of(a, d)
        val rows = rows(gate)
        assertFalse("/sessions", rows.contains("/sessions"))
        assertTrue("the rest of the catalogue is untouched", rows.contains("/new"))
        assertTrue(rows.contains("/models"))
    }

    @Test fun withTheSessionsSetOnTheRowIsInItsGroupWithItsDescription() {
        val a = Alpha(); val d = Diagnostics()
        a.master(true); a.set(AlphaGate.FeatureSet.SESSIONS, true)
        val gate = CommandGate.of(a, d)
        val rows = CommandCatalog.grouped(gate = gate)
        val conversation = rows.first { it.first == CommandCatalog.Group.CONVERSATION }
        val row = conversation.second.firstOrNull { it.name == "/sessions" }
        assertTrue("/sessions is not under This conversation", row != null)
        assertEquals("Pick up a thread the agent already has", row!!.effect)
        assertEquals(Marks.Mark.SESSIONS, row.glyph)
        assertEquals("nothing is lost, so there is no cost line", null, row.cost)
    }

    @Test fun theMasterSwitchAloneIsNotEnough() {
        val a = Alpha(); val d = Diagnostics()
        a.master(true)   // master on, Sessions not selected
        assertFalse("/sessions", rows(CommandGate.of(a, d)).contains("/sessions"))
        a.set(AlphaGate.FeatureSet.SESSIONS, true)
        assertTrue("/sessions", rows(CommandGate.of(a, d)).contains("/sessions"))
        a.master(false)  // master off overrides the set
        assertFalse("/sessions", rows(CommandGate.of(a, d)).contains("/sessions"))
    }

    /**
     * The sheet is built once and kept; the gate must not answer from that moment. A person
     * turns Sessions on in Settings, comes back, and the row is there.
     */
    @Test fun aSetTurnedOnAfterTheSheetWasBuiltShowsWithoutARestart() {
        val a = Alpha(); val d = Diagnostics()
        val gate = CommandGate.of(a, d)          // the sheet captures this object
        assertFalse(rows(gate).contains("/sessions"))
        a.master(true); a.set(AlphaGate.FeatureSet.SESSIONS, true)
        assertTrue("the gate cached its answer", rows(gate).contains("/sessions"))
        a.set(AlphaGate.FeatureSet.SESSIONS, false)
        assertFalse(rows(gate).contains("/sessions"))
    }

    @Test fun theOtherTwoDoorsKeepTheirOwnSwitches() {
        val a = Alpha(); val d = Diagnostics()
        a.master(true); a.set(AlphaGate.FeatureSet.SESSIONS, true)
        val gate = CommandGate.of(a, d)
        assertFalse("/profiles", rows(gate).contains("/profiles"))
        assertFalse("/diagnostics", rows(gate).contains("/diagnostics"))
        a.set(AlphaGate.FeatureSet.PROFILES, true)
        d.putBoolean(DiagnosticSummary.PREF, true)
        assertTrue(rows(gate).contains("/profiles"))
        assertTrue(rows(gate).contains("/diagnostics"))
        // And the sessions set does not depend on the other two.
        a.set(AlphaGate.FeatureSet.PROFILES, false)
        d.putBoolean(DiagnosticSummary.PREF, false)
        assertTrue(rows(gate).contains("/sessions"))
    }
}
