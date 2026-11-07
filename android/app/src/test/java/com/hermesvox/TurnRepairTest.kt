package com.hermesvox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F9 (2026-11-04 Presence review): "Errors and empty replies are silent in a car."
 *
 * The property: a turn that fails for a reason the caller did not cause ends with words; a turn
 * the caller cut does not (they know they cut it, and an apology on top of an interruption is
 * noise). The same line offers the one recovery the caller can perform.
 */
class TurnRepairTest {

    @Test fun a_failed_turn_ends_with_words() {
        for (reason in listOf(TurnRepair.REASON_EMPTY, TurnRepair.REASON_LOST, TurnRepair.REASON_ERROR)) {
            assertTrue("$reason must be spoken",
                TurnRepair.speaks(cancelled = false, stopped = false, listening = true,
                    voiceOn = true, sameTurn = true))
        }
        assertTrue("the line says what happened", TurnRepair.LINE.contains("didn't get an answer"))
        assertTrue("and offers the one action left", TurnRepair.LINE.contains("try again"))
    }

    @Test fun a_turn_the_caller_cut_is_not_a_failure_and_gets_no_apology() {
        assertFalse("a barge/hush is the caller's own cut",
            TurnRepair.speaks(cancelled = true, stopped = false, listening = true,
                voiceOn = true, sameTurn = true))
    }

    @Test fun nothing_is_said_after_the_call_ended_or_into_a_newer_turn() {
        assertFalse("the call is over",
            TurnRepair.speaks(cancelled = false, stopped = true, listening = true,
                voiceOn = true, sameTurn = true))
        assertFalse("the mic is closed",
            TurnRepair.speaks(cancelled = false, stopped = false, listening = false,
                voiceOn = true, sameTurn = true))
        assertFalse("a newer turn owns the floor",
            TurnRepair.speaks(cancelled = false, stopped = false, listening = true,
                voiceOn = true, sameTurn = false))
    }

    @Test fun the_repair_respects_the_spoken_replies_setting() {
        assertFalse("text-only: the transcript is the answer",
            TurnRepair.speaks(cancelled = false, stopped = false, listening = true,
                voiceOn = false, sameTurn = true))
    }

    /**
     * Naturalness pass (2026-11-05): a turn that ran out of time says the app stopped waiting —
     * and once the mind had started a tool, it says the outcome is unknown instead of inviting a
     * retry that could do the work twice.
     */
    @Test fun a_timeout_says_the_app_stopped_waiting_and_never_claims_nothing_happened() {
        for (reason in listOf(TurnRepair.REASON_TIMEOUT, TurnRepair.REASON_SILENT)) {
            assertTrue(TurnRepair.line(reason, workStarted = false).contains("stopped waiting"))
        }
        for (reason in listOf(TurnRepair.REASON_TIMEOUT, TurnRepair.REASON_SILENT, TurnRepair.REASON_EMPTY,
                TurnRepair.REASON_LOST, TurnRepair.REASON_ERROR)) {
            val l = TurnRepair.line(reason, workStarted = true)
            assertTrue("$reason after work started must say the outcome is unknown", l.contains("can't say what got done"))
            assertFalse("$reason after work started must not invite a blind retry", l.contains("try again") || l.contains("ask again"))
        }
        assertTrue("nothing started: the old line stands",
            TurnRepair.line(TurnRepair.REASON_ERROR, workStarted = false) == TurnRepair.LINE)
    }
}
