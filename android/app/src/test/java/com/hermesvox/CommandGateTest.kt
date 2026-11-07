package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #4/#501, as amended by #436/#437 (D39, 2026-11-06): `/sessions` and `/profiles` have both left
 * the Alpha gate, so they are on the sheet, in the search and in /help for every install — with
 * the master off and nothing selected. The diagnostic summary is the one command left with a
 * gate of its own, and it still answers from the switch's state when asked, not when the sheet
 * was built.
 */
class CommandGateTest {

    /** The diagnostic switch, in memory. */
    private class Diagnostics : DiagnosticSummary.Store {
        val flags = HashMap<String, Boolean>()
        override fun contains(key: String) = key in flags
        override fun getBoolean(key: String, default: Boolean) = flags[key] ?: default
        override fun putBoolean(key: String, value: Boolean) { flags[key] = value }
    }

    private fun rows(gate: CommandCatalog.Gate) =
        CommandCatalog.grouped(gate = gate).flatMap { it.second }.map { it.name }

    /** D39: no master, no set — and both promoted commands are there anyway. */
    @Test fun withAlphaOffThePromotedCommandsAreStillOnTheSheet() {
        val d = Diagnostics()
        val rows = rows(CommandGate.of(d))
        assertTrue("/sessions left the gate", rows.contains("/sessions"))
        assertTrue("/profiles left the gate", rows.contains("/profiles"))
        assertTrue("the rest of the catalogue is untouched", rows.contains("/new"))
        assertTrue(rows.contains("/models"))
        // Both words reach their cards too: no gate stands between the two.
        assertEquals("/sessions", Sessions.commandFor("/sessions")?.name)
        assertTrue(CommandCatalog.search("profiles", gate = CommandGate.of(d))
            .any { it.name == "/profiles" })
        // And the gate itself never mentions Alpha again.
        val gate = java.io.File("src/main/java/com/hermesvox/CommandGate.kt").readText()
        assertFalse(gate.contains("AlphaGate"))
    }

    @Test fun theSessionsRowIsInItsGroupWithItsDescription() {
        val d = Diagnostics()
        val groups = CommandCatalog.grouped(gate = CommandGate.of(d))
        val conversation = groups.first { it.first == CommandCatalog.Group.CONVERSATION }
        val row = conversation.second.firstOrNull { it.name == "/sessions" }
        assertTrue("/sessions is not under This conversation", row != null)
        assertEquals("Pick up a thread the agent already has", row!!.effect)
        assertEquals(Marks.Mark.SESSIONS, row.glyph)
        assertEquals("nothing is lost, so there is no cost line", null, row.cost)
        // /profiles is under The agent, beside /models.
        val agent = groups.first { it.first == CommandCatalog.Group.AGENT }
        assertTrue(agent.second.any { it.name == "/profiles" })
        assertEquals("Talk to another of this host's agents",
            agent.second.first { it.name == "/profiles" }.effect)
    }

    /** The diagnostic switch is the one optional command left, and it keeps its own gate. */
    @Test fun theDiagnosticSwitchKeepsItsOwnGate() {
        val d = Diagnostics()
        val gate = CommandGate.of(d)
        assertFalse("/diagnostics", rows(gate).contains("/diagnostics"))
        d.putBoolean(DiagnosticSummary.PREF, true)
        assertTrue(rows(gate).contains("/diagnostics"))
        // Its switch is read when asked: that is what makes it appear without a restart.
        d.putBoolean(DiagnosticSummary.PREF, false)
        assertFalse("the gate cached its answer", rows(gate).contains("/diagnostics"))
    }

    /**
     * The panel and the catalogue agree on the same fact: the gate has six sets, and the two
     * that left it are ordinary commands.
     */
    @Test fun theGateHasSixSetsAndNeitherPromotedFeatureIsOne() {
        assertEquals(6, AlphaGate.FeatureSet.entries.size)
        assertFalse(AlphaGate.FeatureSet.entries.any { it.key == "alpha_set_profiles" })
        assertFalse(AlphaGate.FeatureSet.entries.any { it.key == "alpha_set_sessions" })
    }
}
