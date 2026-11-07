package com.hermesvox.wake

import com.hermesvox.AlphaGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The wake word is alpha-gated: the master switch plus its own set, like every other set. The
 * row is one half of that (dimmed, taking no tap); this is the other half — with the set off
 * the microphone is not opened, so "off" stops it listening rather than only hiding it.
 */
class WakeListeningTest {

    private fun reader(vararg keys: String) = AlphaGate.Reader { k, _ -> k in keys }

    @Test fun withTheMasterOffNothingListens() {
        assertFalse("a selected set is inert without the master",
            WakeListening.listening(reader(AlphaGate.FeatureSet.WAKE_WORD.key)))
        assertFalse(WakeListening.listening(reader()))
    }

    @Test fun withTheMasterOnAndTheSetSelectedItListens() {
        assertTrue(WakeListening.listening(
            reader(AlphaGate.KEY_MASTER, AlphaGate.FeatureSet.WAKE_WORD.key)))
        // The master alone is not a selection.
        assertFalse(WakeListening.listening(reader(AlphaGate.KEY_MASTER)))
        // Somebody else's set does not open this one.
        assertFalse(WakeListening.listening(
            reader(AlphaGate.KEY_MASTER, AlphaGate.FeatureSet.PRESENCE.key)))
    }

    /** The capture path asks the same question — the switch is not a cosmetic row. */
    @Test fun theCapturePathAsksTheGateBeforeItOpensTheMicrophone() {
        val act = File("src/main/java/com/hermesvox/WakeEnrolActivity.kt").readText()
        val take = act.substringAfter("private fun startTake() {").substringBefore("if (!store.hasConsent()")
        assertTrue("startTake does not ask the gate", take.contains("WakeListening.listening(prefs)"))
        assertTrue("the screen says why it records nothing", act.contains("WakeListening.REFUSAL"))
        assertTrue("the record button follows the gate",
            act.contains("record.isEnabled = consented && WakeListening.listening(prefs)"))
    }

    @Test fun theGateOwnsTheSetAndItsRowIsInTheBlock() {
        val set = AlphaGate.FeatureSet.WAKE_WORD
        assertTrue("the set exists", AlphaGate.FeatureSet.entries.contains(set))
        assertFalse("nothing stored means it is off",
            AlphaGate.allows(AlphaGate.Reader { _, d -> d }, set))
        assertTrue("its row is in the alpha block",
            com.hermesvox.AlphaBlock.ROWS.any { it.row == "row_grp_wake" && it.set == set })
        val settings = File("src/main/java/com/hermesvox/SettingsActivity.kt").readText()
        assertTrue("the row's door asks the gate",
            settings.contains("if (!AlphaBlock.canOpen(prefs, \"wake\")) return@setOnClickListener"))
    }
}
