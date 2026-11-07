package com.hermesvox

import com.hermesvox.core.ApprovalGate
import com.hermesvox.core.TurnBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The silent ceiling asks instead of abandoning (owner instruction, 2026-11-06): at 60 s
 * with no event, no tool and no approval card, the being asks "keep waiting, or stop?".
 * The question rides the approval CARD SURFACE but is not an approval: no id, no action,
 * no registry entry. "Keep waiting" extends the turn once; "stop", or the card's 10 s
 * window closing with no answer, ends it. A hedge is not an answer.
 */
class WaitPromptTest {

    private fun step(
        silentForMs: Long,
        nowMs: Long = silentForMs,
        askedAtMs: Long = -1L,
        answer: WaitPrompt.Answer? = null,
        extensionEndsAtMs: Long = -1L,
        toolRunning: Boolean = false,
        approvalWaiting: Boolean = false,
    ) = WaitPrompt.step(
        silentForMs, nowMs, askedAtMs, answer, extensionEndsAtMs, toolRunning, approvalWaiting,
    )

    @Test fun the_question_and_every_outcome_are_pinned_word_for_word() {
        assertEquals("STILL WORKING", WaitPrompt.TITLE)
        assertEquals("This is taking a while — keep waiting, or stop?", WaitPrompt.QUESTION)
        assertEquals(WaitPrompt.QUESTION, WaitPromptCard().question)
        assertEquals(10_000L, WaitPrompt.WINDOW_MS)
        assertEquals("Okay — I'll keep waiting.", WaitPrompt.LINE_KEPT)
        assertEquals("Stopped waiting.", WaitPrompt.LINE_STOPPED)
        assertEquals("I didn't hear an answer, so I stopped waiting.", WaitPrompt.LINE_NO_ANSWER)
        assertEquals("The extra time ran out with no answer, so I stopped waiting.", WaitPrompt.LINE_EXTENDED)

        // The abandonment path speaks the same lines, even after a tool ran: these say only
        // what the APP did ("stopped waiting"), which is true whatever the mind started.
        assertEquals(WaitPrompt.LINE_STOPPED, TurnRepair.line(TurnRepair.REASON_WAIT_STOP, workStarted = true))
        assertEquals(WaitPrompt.LINE_NO_ANSWER, TurnRepair.line(TurnRepair.REASON_WAIT_NO_ANSWER, workStarted = false))
        assertEquals(WaitPrompt.LINE_EXTENDED, TurnRepair.line(TurnRepair.REASON_WAIT_EXTENDED, workStarted = false))
    }

    @Test fun the_card_appears_at_the_ceiling_and_not_before() {
        assertTrue(TurnWait.SILENT_STREAM_MS == 60_000L)
        assertEquals(WaitPrompt.Step.NOT_YET, step(59_999))
        assertEquals(WaitPrompt.Step.ASK, step(60_000))
    }

    @Test fun a_running_tool_or_any_approval_card_suppresses_the_question() {
        assertEquals("a tool sends nothing until it ends", WaitPrompt.Step.NOT_YET,
            step(90_000, toolRunning = true))
        assertEquals("an approval owns the screen", WaitPrompt.Step.NOT_YET,
            step(90_000, approvalWaiting = true))
        // A held/arm approval is still an approval: no wait question over it.
        assertNull(WaitPrompt.reason(step(90_000, approvalWaiting = true)))
    }

    @Test fun keep_waiting_extends_once_and_does_not_ask_again_inside_the_window() {
        val run = WaitPromptRun()
        val askedAt = 60_000L
        val answeredAt = 61_000L
        assertTrue(run.markAsked(askedAt))
        assertTrue(run.answer(WaitPrompt.Answer.KEEP, answeredAt, WaitPrompt.extensionMs(null)))

        assertEquals(answeredAt + WaitPrompt.DEFAULT_EXTENSION_MS, run.extensionEndsAtMs())
        assertEquals(WaitPrompt.Step.KEEP_WAITING, run.step(answeredAt + 1, answeredAt + 1, false, false))
        assertEquals("the extension is not a new question", WaitPrompt.Step.KEEP_WAITING,
            run.step(120_000, 120_000, false, false))
        assertFalse("the one question is never asked twice", run.markAsked(120_000))
        assertFalse("a duplicate answer is a no-op", run.answer(WaitPrompt.Answer.STOP, 120_000, 0L))
        assertEquals(WaitPrompt.Answer.KEEP, run.answer())

        // Silent all the way to the extension's end: that is the extended-silence stop,
        // not another ask.
        val end = run.extensionEndsAtMs()
        assertEquals(WaitPrompt.Step.STOP_AFTER_EXTENSION, run.step(end, end, false, false))
        assertEquals(TurnRepair.REASON_WAIT_EXTENDED, WaitPrompt.reason(run.step(end, end, false, false)))
    }

    @Test fun a_stream_that_came_back_is_not_silent_at_the_end_of_the_extension() {
        val run = WaitPromptRun()
        run.markAsked(60_000)
        run.answer(WaitPrompt.Answer.KEEP, 61_000, 120_000)
        val end = run.extensionEndsAtMs()
        // Events resumed, so the current silent run is young even though the window closed.
        assertEquals(WaitPrompt.Step.KEEP_WAITING, run.step(5_000, end, false, false))
    }

    @Test fun the_host_states_the_remaining_allowance_or_the_default_is_120s() {
        assertEquals(120_000L, WaitPrompt.extensionMs(null))
        assertEquals(240_000L, WaitPrompt.extensionMs(240_000))
        assertEquals(0L, WaitPrompt.extensionMs(0))
        assertEquals(0L, WaitPrompt.extensionMs(-1))
    }

    @Test fun answering_keep_waiting_extends_the_agent_budget_past_the_old_ceiling() {
        var now = 0L
        val budget = TurnBudget(now = { now })
        now = 70_000L
        budget.extend(WaitPrompt.extensionMs(null))
        assertTrue("the old 120 s deadline must no longer end the turn",
            budget.keepPolling(done = false, atMs = now))
        now = 200_000L
        assertTrue(budget.keepPolling(done = false, atMs = now))
        now = 70_000L + 120_000L + 120_000L
        assertFalse("the extension is finite", budget.keepPolling(done = false, atMs = now))
    }

    @Test fun stop_stops_and_says_so() {
        val run = WaitPromptRun()
        run.markAsked(60_000)
        assertTrue(run.answer(WaitPrompt.Answer.STOP, 61_000, 0L))
        assertEquals(WaitPrompt.Step.STOP_ANSWERED, run.step(61_000, 61_000, false, false))
        assertEquals(TurnRepair.REASON_WAIT_STOP, WaitPrompt.reason(WaitPrompt.Step.STOP_ANSWERED))
        assertEquals(WaitPrompt.Outcome.STOP, WaitPrompt.stopOutcome(WaitPrompt.Step.STOP_ANSWERED))
        assertTrue(TurnRepair.line(TurnRepair.REASON_WAIT_STOP, workStarted = false).contains("Stopped waiting"))
    }

    @Test fun no_answer_stops_at_the_window_and_says_it_heard_nothing() {
        val run = WaitPromptRun()
        run.markAsked(60_000)
        assertEquals(WaitPrompt.Step.CARD_UP, run.step(60_000, 69_999, false, false))
        assertEquals(WaitPrompt.Step.STOP_NO_ANSWER, run.step(60_000, 70_000, false, false))
        assertEquals(TurnRepair.REASON_WAIT_NO_ANSWER, WaitPrompt.reason(WaitPrompt.Step.STOP_NO_ANSWER))
        val line = TurnRepair.line(TurnRepair.REASON_WAIT_NO_ANSWER, workStarted = false)
        assertTrue("it says why it stopped: $line", line.contains("didn't hear an answer"))
        assertTrue("and that it stopped: $line", line.contains("stopped waiting"))
    }

    @Test fun the_card_has_its_whole_window_even_at_the_agent_budget_edge() {
        // A run that goes quiet 55 s in is asked at 115 s, five seconds before the 120 s
        // budget. Asking grants the ten-second window, so the caller is never shown a card
        // that the timeout then yanks away — and it buys only that window.
        var now = 0L
        val budget = TurnBudget(now = { now })
        now = 115_000L
        budget.extend(WaitPrompt.WINDOW_MS)
        assertTrue("the question is answerable at the old budget",
            budget.keepPolling(done = false, atMs = 120_000L))
        assertTrue("and for the whole window",
            budget.keepPolling(done = false, atMs = 129_999L))
        assertFalse("but not a millisecond past it",
            budget.keepPolling(done = false, atMs = 130_000L))
    }

    @Test fun an_answer_cannot_be_recorded_before_the_question_or_replayed_after_it() {
        val run = WaitPromptRun()
        assertFalse("nothing is answerable before the card exists",
            run.answer(WaitPrompt.Answer.KEEP, 1_000L, 120_000L))
        assertEquals(-1L, run.extensionEndsAtMs())

        assertTrue(run.markAsked(60_000L))
        assertTrue(run.answer(WaitPrompt.Answer.KEEP, 61_000L, 120_000L))
        run.reset()
        assertFalse("a reset turn cannot replay the old answer",
            run.answer(WaitPrompt.Answer.STOP, 62_000L, 0L))
    }

    @Test fun a_question_during_the_card_is_the_callers_next_turn() {
        // The words are not an answer (see the hedge test) — and the controller's job is to
        // make them the NEXT TURN: queue them and cut the stalled one, exactly as a barge
        // does. It is never handed to the approval gate.
        val vc = File("src/main/java/com/hermesvox/VoiceController.kt").readText()
        val waitBlock = vc
            .substringAfter("// ---- The wait prompt (not an approval)")
            .substringBefore("0.9.1 soak: an utterance arrived while [request]'s card is up")
        assertTrue("the non-answer is queued as a turn",
            waitBlock.contains("pendingTurn.offer(text"))
        assertTrue("and the stalled run is cut like a barge",
            waitBlock.contains("genCancelled = true") && waitBlock.contains("silenceAll("))
        assertTrue("while the approval handshake is not involved",
            waitBlock.contains("if (approvalPending())") && !waitBlock.contains("submitApproval("))
    }

    @Test fun two_words_are_the_answers_and_a_hedge_is_not_keep_waiting() {
        assertEquals(WaitPrompt.Answer.KEEP, WaitPrompt.resolve("keep waiting"))
        assertEquals(WaitPrompt.Answer.KEEP, WaitPrompt.resolve("Yes, keep waiting."))
        assertEquals(WaitPrompt.Answer.KEEP, WaitPrompt.resolve("keep waiting please"))
        assertEquals(WaitPrompt.Answer.STOP, WaitPrompt.resolve("stop"))
        assertEquals(WaitPrompt.Answer.STOP, WaitPrompt.resolve("no thanks"))

        // Hedges are neither answer. A hedge is a NEW TURN, never an extension.
        for (hedge in listOf("maybe", "I'm not sure", "not sure", "I don't know", "okay", "hmm")) {
            assertNull("$hedge must not mean keep waiting", WaitPrompt.resolve(hedge))
        }
        // A real question is not an answer either, and the approval surface's long-utterance
        // cap still applies.
        for (question in listOf(
            "what's taking so long?", "what is happening", "why is this slow",
            "what are you doing right now", "keep waiting around for the thing to happen",
        )) {
            assertNull("$question is not an answer", WaitPrompt.resolve(question))
        }
    }

    @Test fun the_wait_card_leaves_no_approval_behind() {
        val card = WaitPromptCard()
        assertEquals(WaitPrompt.TITLE, card.title)
        assertEquals(listOf(WaitPrompt.Answer.KEEP, WaitPrompt.Answer.STOP), card.answers)
        assertEquals("KEEP WAITING", card.label(WaitPrompt.Answer.KEEP))
        assertEquals("STOP", card.label(WaitPrompt.Answer.STOP))

        // The card type carries no run id, request id, action id, key or arguments.
        val fields = WaitPromptCard::class.java.declaredFields.map { it.name.lowercase() }
        for (forbidden in listOf("runid", "requestid", "actionid", "key", "arguments", "command")) {
            assertFalse("WaitPromptCard must not carry $forbidden: $fields",
                fields.any { it.contains(forbidden) })
        }

        // It never enters the approval registry, and no id from it can answer one later.
        val gate = ApprovalGate()
        assertNull(gate.current())
        assertEquals(0, gate.pendingCount())
        assertNull(gate.answer("", "once", "touch"))
        assertNull(gate.timeout("keep"))
        assertNull(gate.timeout("stop"))

        // The wait path shares the SURFACE only: it never offers/answers/audits an approval.
        val vc = File("src/main/java/com/hermesvox/VoiceController.kt").readText()
        val waitBlock = vc
            .substringAfter("// ---- The wait prompt (not an approval)")
            .substringBefore("0.9.1 soak: an utterance arrived while [request]'s card is up")
        assertTrue("the wait path exists", waitBlock.contains("waitUtterance"))
        for (forbidden in listOf("approvalGate.", "submitApproval(", "ApprovalRequest(", "PhoneAudit")) {
            assertFalse("the wait path must not touch $forbidden", waitBlock.contains(forbidden))
        }
    }

    @Test fun the_controller_abandons_with_the_wait_reason_and_the_screen_titles_the_card() {
        val vc = File("src/main/java/com/hermesvox/VoiceController.kt").readText()
        assertTrue("the ceiling is the wait state machine",
            vc.contains("val step = waitRun.step("))
        assertTrue("the ask is posted once at the ceiling", vc.contains("main.post { showWaitPrompt(gen) }"))
        assertTrue("a stopping step abandons with its own reason",
            vc.contains("val why = WaitPrompt.reason(step)") &&
                vc.contains("abandonTurn(gen, sid, route, budget, why)"))
        assertTrue("the host may state the run's remaining allowance",
            vc.contains("obj.optLong(\"run_remaining_ms\""))
        assertTrue("keep waiting extends the budget",
            vc.contains("budget.extend(extra)"))
        assertTrue("the question itself grants its answer window",
            vc.contains("budget.extend(WaitPrompt.WINDOW_MS)"))

        val ui = File("src/main/java/com/hermesvox/MainActivity.kt").readText()
        assertTrue("the card is titled STILL WORKING", ui.contains("approvalTitle.text = card.title"))
        assertTrue("the question is the card body", ui.contains("approvalLine.text = card.question"))
        assertTrue("two choices on the same row", ui.contains("buildWaitButtons(card)"))
        assertTrue("a tap answers the controller's wait prompt",
            ui.contains("liveController?.answerWaitPrompt(answer)"))
        assertTrue("the wait card never sets approvalWaiting",
            ui.substringAfter("override fun onWaitPrompt(card: WaitPromptCard)")
                .substringBefore("override fun onWaitPromptResolved")
                .let { !it.contains("approvalWaiting = true") })
    }
}
