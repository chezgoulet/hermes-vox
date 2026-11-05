package com.hermesvox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * D38 — **the phone does not decide what an utterance means; the mind does.**
 *
 * A local grammar used to match the caller's words before the mind was asked, so a regex on the
 * phone was a second actor and "set a timer" never reached the mind, with Presence on or off. The
 * turn path now has no local branch: every voice turn is submitted, and the mind decides whether to
 * call the `phone` tool with that path's grant and card.
 */
class MindOwnsThePhoneTest {

    private val src = File("src/main/java/com/hermesvox")
    private val vc = File(src, "VoiceController.kt").readText()

    @Test
    fun every_voice_turn_goes_to_the_mind_and_no_utterance_is_taken_locally() {
        val turn = vc.substringAfter("private fun runStreamedTurn(")
            .substringBefore("private fun emitEvents(")
        // No parser, no local dispatch, no early return that keeps a turn off the wire.
        assertFalse("the turn path must not parse the caller's words", turn.contains("LocalActionGrammar"))
        assertFalse("the turn path must not take a local action", turn.contains("takeLocalAction"))
        assertFalse("the turn path must not run the phone's owner lane", turn.contains("localActions.run"))
        // Both submit branches are the mind: a voice turn through voiceTurn, a typed turn through
        // startStream. There is no third branch for a phrase such as "set a timer".
        assertTrue(turn.contains("session.voiceTurn(turnText, instructions, reasoning)"))
        assertTrue(turn.contains("session.startStream(text)"))
    }

    @Test
    fun no_source_file_parses_an_utterance_into_a_phone_action() {
        val files = src.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
        for (f in files) {
            val body = f.readText()
            assertFalse("${f.name} still references the deleted grammar", body.contains("LocalActionGrammar"))
            assertFalse("${f.name} still dispatches a local action", body.contains("takeLocalAction"))
        }
        assertFalse(
            "the receipt no longer has a local-action reason",
            File(src, "ErHandoff.kt").readText().contains("REASON_LOCAL_ACTION"),
        )
    }
}
