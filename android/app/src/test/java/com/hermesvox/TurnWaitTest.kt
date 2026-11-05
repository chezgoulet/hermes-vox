package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wait registers and the silent-stream ceiling (naturalness pass, 2026-11-05). A stream that
 * stays open and sends nothing used to have no failure path short of the 120 s budget; with
 * Presence off the caller heard pings and nothing else.
 */
class TurnWaitTest {

    private fun at(ms: Long, said: Int, reply: Boolean = false, presence: Boolean = false,
                   approval: Boolean = false, quiet: Long = 60_000L) =
        TurnWait.checkIn(ms, said, reply, presence, approval, quiet)

    @Test fun nothing_is_said_before_fifteen_seconds() {
        assertNull(at(2_000, 0)); assertNull(at(5_000, 0)); assertNull(at(14_999, 0))
        assertEquals(TurnWait.LINE_FIRST, at(15_000, 0))
    }

    @Test fun the_second_line_waits_for_forty_and_then_the_cap_holds() {
        assertNull(at(39_999, 1))
        assertEquals(TurnWait.LINE_SECOND, at(40_000, 1))
        assertNull("capped", at(119_000, TurnWait.MAX_CHECK_INS))
        assertEquals(2, TurnWait.MAX_CHECK_INS)
    }

    @Test fun the_reply_presence_a_card_or_the_caller_silence_it() {
        assertNull("the reply owns the floor", at(20_000, 0, reply = true))
        assertNull("Presence voices its own wait", at(20_000, 0, presence = true))
        assertNull("a card is the caller's wait", at(20_000, 0, approval = true))
        assertNull("never over the caller", at(20_000, 0, quiet = 500))
    }

    @Test fun the_lines_are_short_and_claim_nothing() {
        for (l in listOf(TurnWait.LINE_FIRST, TurnWait.LINE_SECOND)) {
            assertTrue(l.split(" ").size <= 10)
            assertFalse("a wait line claims no result", ErSoulTurn.claimsResult(l))
        }
    }

    @Test fun a_silent_stream_is_dead_after_forty_five_seconds_unless_a_tool_or_card_explains_it() {
        assertFalse(TurnWait.silentStreamExpired(44_999, toolRunning = false, approvalWaiting = false))
        assertTrue(TurnWait.silentStreamExpired(45_000, toolRunning = false, approvalWaiting = false))
        assertFalse("a running tool sends nothing until it ends",
            TurnWait.silentStreamExpired(90_000, toolRunning = true, approvalWaiting = false))
        assertFalse("an approval wait is the human's",
            TurnWait.silentStreamExpired(90_000, toolRunning = false, approvalWaiting = true))
    }

    @Test fun the_silent_ceiling_is_inside_the_agent_budget() {
        assertTrue(TurnWait.SILENT_STREAM_MS < StreamPollGate.STREAM_TURN_TIMEOUT_MS)
        assertTrue(TurnWait.CHECK_IN_SECOND_MS < TurnWait.SILENT_STREAM_MS)
    }
}
