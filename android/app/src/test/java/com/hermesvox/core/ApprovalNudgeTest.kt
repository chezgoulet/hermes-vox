package com.hermesvox.core

import com.hermesvox.ErSoulTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.9.1 soak — a non-answer while a card is up is told "say approve or no", once per card.
 * The prompt is advice, never a decision: it must not approve, deny, or end the request.
 */
class ApprovalNudgeTest {

    private fun request(id: String = "req-1") = ApprovalRequest(
        "run-1", id, "rm -rf /tmp/vox-probe", "recursive delete", "destructive",
        listOf("once", "session", "always", "deny"),
    )

    @Test fun a_non_answer_prompts_once_per_card_not_per_utterance() {
        val nudge = ApprovalNudge()
        assertEquals(ApprovalNudge.Outcome.SPEAK, nudge.onNoAnswer("req-1", voiceBusy = false))
        assertEquals(ApprovalNudge.Outcome.ALREADY, nudge.onNoAnswer("req-1", voiceBusy = false))
        assertEquals(ApprovalNudge.Outcome.ALREADY, nudge.onNoAnswer("req-1", voiceBusy = false))
    }

    @Test fun a_busy_voice_skips_the_prompt_without_spending_it() {
        val nudge = ApprovalNudge()
        assertEquals(ApprovalNudge.Outcome.BUSY, nudge.onNoAnswer("req-1", voiceBusy = true))
        assertEquals(ApprovalNudge.Outcome.SPEAK, nudge.onNoAnswer("req-1", voiceBusy = false))
        assertEquals(ApprovalNudge.Outcome.ALREADY, nudge.onNoAnswer("req-1", voiceBusy = true))
    }

    @Test fun answered_replaced_or_timed_out_resets_the_latch() {
        val nudge = ApprovalNudge()
        nudge.onNoAnswer("req-1", voiceBusy = false)
        // replaced: another card is its own card
        assertEquals(ApprovalNudge.Outcome.SPEAK, nudge.onNoAnswer("req-2", voiceBusy = false))
        // answered / timed out: the controller resets
        nudge.reset()
        assertEquals(ApprovalNudge.Outcome.SPEAK, nudge.onNoAnswer("req-2", voiceBusy = false))
        assertEquals(ApprovalNudge.Outcome.ALREADY, nudge.onNoAnswer("", voiceBusy = false))
    }

    @Test fun the_prompt_neither_approves_nor_denies_and_the_card_stays_up() {
        val gate = ApprovalGate()
        assertNotNull(gate.offer(request(), runAlive = true))
        val shown = gate.current()!!
        val nudge = ApprovalNudge()
        for (said in listOf("I'm not sure", "where does that go", "hmm let me think about it")) {
            assertNull(said, ApprovalVoice.resolve(said, shown.choices))
            nudge.onNoAnswer(shown.key(), voiceBusy = false)
        }
        // Nothing was decided: the same card is up, alone, and still answerable exactly once.
        assertSame(shown, gate.current())
        assertEquals(1, gate.pendingCount())
        val answer = gate.answer(shown.key(), "deny", "touch")
        assertNotNull(answer)
        assertFalse(answer!!.timedOut)
        assertEquals("touch", answer.source)
    }

    @Test fun an_echo_of_the_prompt_is_not_an_answer_and_claims_nothing() {
        val choices = listOf("once", "deny")
        assertNull(ApprovalVoice.resolve(ApprovalNudge.LINE, choices))
        assertNull(ApprovalVoice.resolve("Say approve or no.", choices))
        assertFalse(ErSoulTurn.claimsResult(ApprovalNudge.LINE))
    }

    @Test fun the_log_line_carries_the_id_and_reason_only() {
        assertEquals("event=approval-prompt request=req-9 reason=not-an-answer", ApprovalNudge.logLine("req-9"))
    }

    /** The wiring: both no-answer paths reach the nudge, the nudge path cannot answer,
     *  and every way a card leaves resets the latch. */
    @Test fun the_controller_prompts_from_both_voice_paths_and_never_answers_from_it() {
        val src = File("src/main/java/com/hermesvox/VoiceController.kt").readText()
        val body = src.substringAfter("private fun approvalNoAnswer(").substringBefore("\n    /**")
        assertTrue(body.contains("approvalNudge.onNoAnswer("))
        assertTrue(body.contains("ApprovalNudge.LINE"))
        assertTrue(body.contains("glueBlocked()") && body.contains("glueSpeaking"))
        for (forbidden in listOf("approvalGate.answer", "approvalGate.timeout", "submitApproval", "closeApprovals")) {
            assertFalse(forbidden, body.contains(forbidden))
        }
        assertEquals(2, Regex("approvalNoAnswer\\(request\\)").findAll(src).count())
        val submit = src.substringAfter("private fun submitApproval(").substringBefore("\n    /**")
        assertTrue(submit.contains("approvalNudge.reset()"))
        val close = src.substringAfter("private fun closeApprovals(").substringBefore("\n    /**")
        assertTrue(close.contains("approvalNudge.reset()"))
        val show = src.substringAfter("private fun showNextApproval(").substringBefore("\n    /**")
        assertTrue(show.contains("approvalNudge.reset()"))
    }
}
