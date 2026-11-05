package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * D38 — **the Presence model exists to make the conversation better, and it does not act on the
 * phone. Phone actions belong to the mind.**
 *
 * Presence may read the registry so it can say "your timer has about four minutes left"; it may not
 * set, cancel or change anything. [PhoneReadout] is the type-level guard, and the source checks
 * below keep the presence-path files off every mutating entry point.
 */
class PresenceReadOnlyTest {

    private val src = File("src/main/java/com/hermesvox")
    private val vc = File(src, "VoiceController.kt").readText()

    @Test
    fun the_presence_path_may_read_the_phone_and_may_never_act_on_it() {
        // The interface is the rule: one read, and no mutating method to call.
        assertEquals(
            "Presence's phone view must expose the registry and nothing else",
            setOf("registry"),
            PhoneReadout::class.java.declaredMethods.map { it.name }.toSet(),
        )

        val block = vc.substringAfter("private fun presenceContextBlock()")
            .substringBefore("// --- The streamed entity turn")
        assertTrue("the context reads the read-only view", block.contains("presenceReadout.registry("))
        for (mutating in listOf(
            "localActions.run(",
            "localActions.phone(",
            "localActions.currentApproval(",
            "localActions.approvalVoice(",
            "localActions.tickApprovals(",
            "localActions.undo(",
        )) {
            assertFalse("the presence context must not call $mutating", block.contains(mutating))
        }

        // The being's own files are pure/read-only: no Android action API, no phone engine, no
        // phone tool. This fails if a future change hands the model a lever.
        for (name in listOf(
            "PresenceContext.kt", "ErPresence.kt", "ErSoulTurn.kt",
            "VoxSoul.kt", "GemmaExpress.kt", "VoxExpress.kt",
        )) {
            val body = File(src, name).readText()
            for (capability in listOf(
                "setStreamVolume(", "adjustStreamVolume(", "setInterruptionFilter(",
                "setPrimaryClip(", "AlarmManager", "PhoneEngine(", "PhoneTool.Call",
                "LocalActions(",
            )) {
                assertFalse("$name reaches a mutating capability: $capability", body.contains(capability))
            }
        }
    }
}
