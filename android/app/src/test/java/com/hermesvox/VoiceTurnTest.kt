package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A2 / F4 (2026-11-04 Presence review): "Presence voice turns tell the mind not to use tools",
 * because a prefix inside the user's message said *"Answer from what you know; do NOT do
 * exhaustive tool work …"*, and it was stored in the agent's own history every turn.
 *
 * The contract under test now:
 *  - the voice register tells the mind HOW it is heard (a phone call: short, plain speech) and
 *    says nothing about what it may do — the mind keeps its tools;
 *  - the register lives in `instructions` (one place, ephemeral), never in the caller's words;
 *  - the presence contexts (soul-sync, the phone's state) are appended there too, and an empty
 *    context leaves the register alone.
 *
 * The transport side — that `instructions` reaches the wire and the input does not change — is
 * pinned in core.StreamTest and core.HermesSessionTest.
 */
class VoiceTurnTest {

    @Test fun the_register_is_call_register() {
        assertTrue(VoiceTurn.REGISTER.contains("phone call"))
        assertTrue(VoiceTurn.REGISTER.contains("two to four sentences"))
        assertTrue(VoiceTurn.REGISTER.contains("no markdown"))
        assertEquals("one short paragraph", 0, Regex("\n").findAll(VoiceTurn.REGISTER).count())
    }

    @Test fun the_anti_tool_clause_is_gone_and_not_replaced() {
        // The review's finding, as an assertion: this is why a price question got a guess.
        for (phrase in listOf(
            "do NOT do exhaustive tool work",
            "unless the caller explicitly asks",
            "Answer from what you know",
            "re-audit",
        )) {
            assertFalse("'$phrase' steered voice turns away from tools: $phrase", VoiceTurn.REGISTER.contains(phrase))
        }
        // And nothing in its place restricts what the mind may do.
        for (phrase in listOf("do not use", "don't use", "without tools", "no tool")) {
            assertFalse(VoiceTurn.REGISTER.lowercase().contains(phrase))
        }
    }

    @Test fun no_false_claims_about_what_the_app_does() {
        // "the app adds its own spoken acknowledgments like \"let me think\"" was untrue from
        // 0.6.7 (silence-first) and is an invitation to double-talk now that the beat is a
        // separate render.
        assertFalse(VoiceTurn.REGISTER.contains("acknowledg"))
        assertFalse(VoiceTurn.REGISTER.contains("let me think"))
    }

    @Test fun instructions_are_the_register_plus_each_context() {
        val one = VoiceTurn.instructions("The phone's voice has already said: beat=\"On it.\"")
        assertTrue(one.startsWith(VoiceTurn.REGISTER))
        assertTrue(one.contains("beat=\"On it.\""))
        val two = VoiceTurn.instructions("first", "second")
        assertTrue(two.indexOf("first") < two.indexOf("second"))
        assertEquals("blank contexts add nothing", VoiceTurn.REGISTER, VoiceTurn.instructions("", "  "))
        assertEquals(VoiceTurn.REGISTER, VoiceTurn.instructions())
    }

    @Test fun the_reasoning_setting_is_a_voice_turn_key_with_a_low_default() {
        assertEquals("voice_reasoning_effort", VoiceTurn.PREF_REASONING)
        assertEquals("low", com.hermesvox.core.ResponsesClient.DEFAULT_EFFORT)
    }

    /**
     * Naturalness #1 (2026-11-05): with Presence off a spoken turn went out bare — no register,
     * no reasoning override — and its first text took 80–91 s where the same question with
     * Presence on took 7–13 s. The register and the effort belong to every SPOKEN turn; only
     * the presence context belongs to Presence. A typed turn still goes out bare.
     */
    @Test fun every_spoken_turn_carries_the_register_and_the_effort_presence_or_not() {
        val vc = java.io.File("src/main/java/com/hermesvox/VoiceController.kt").readText()
        val turn = vc.substringAfter("private fun runStreamedTurn(").substringBefore("private fun emitEvents(")
        assertTrue(turn.contains("val sid = if (fromVoice) session.voiceTurn(turnText, instructions, reasoning) else session.startStream(text)"))
        assertTrue(turn.contains("val reasoning = if (fromVoice)"))
        assertTrue("a spoken turn without Presence still gets the register",
            turn.contains("fromVoice -> VoiceTurn.instructions()"))
        assertTrue("the presence context stays Presence's",
            turn.contains("voiceTurn -> VoiceTurn.instructions(sync, presenceContextBlock())"))
        assertFalse("the submit must not key on Presence",
            turn.contains("val sid = if (voiceTurn)"))
    }
}
