package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F3 (2026-11-04 Presence review): "Presence throws away what the caller says while the mind
 * is working." The two halves of the fix are tested here and in [ErBargeGateCarryTest]:
 *
 *  - the DECISION that an utterance must be carried ([ErBargeGate.carries], next to the
 *    verdict it belongs to), and
 *  - the one-slot queue that carries it into the next turn ([PendingTurn]).
 *
 * The property that matters to the caller: an utterance spoken during a live turn is still
 * answerable afterwards, once — not zero times, and not twice.
 */
class PendingTurnTest {

    @Test fun a_queued_utterance_is_the_next_turn_exactly_once() {
        val q = PendingTurn()
        assertTrue(q.offer("what about the second one", 1_000L))
        assertEquals("what about the second one", q.take(2_000L))
        assertNull("a pending turn is consumed by the turn it became", q.take(2_000L))
        assertFalse(q.pending())
    }

    @Test fun newest_wins_and_the_slot_stays_at_one() {
        val q = PendingTurn()
        q.offer("the first thing", 1_000L)
        q.offer("no, the other thing", 1_100L)
        assertEquals("no, the other thing", q.take(1_200L))
        assertFalse("one slot, never a backlog", q.pending())
    }

    @Test fun nothing_is_carried_from_silence_or_blank_text() {
        val q = PendingTurn()
        assertFalse(q.offer(null, 0L))
        assertFalse(q.offer("", 0L))
        assertFalse(q.offer("   ", 0L))
        assertNull(q.take(0L))
    }

    @Test fun stale_words_are_dropped_rather_than_answered_out_of_the_blue() {
        val q = PendingTurn(ttlMs = 5_000L)
        q.offer("what time is my train", 0L)
        assertNull("60 s of thinking is not a cue to answer", q.take(6_000L))
        assertFalse(q.pending())
    }

    @Test fun a_clock_that_went_backwards_does_not_discard_the_caller() {
        val q = PendingTurn(ttlMs = 5_000L)
        q.offer("wait — which one?", 10_000L)
        assertEquals("wait — which one?", q.take(9_000L))
    }

    @Test fun peek_does_not_consume_but_clear_does() {
        val q = PendingTurn()
        q.offer("and the third?", 0L)
        assertEquals("and the third?", q.peek(1L))
        assertTrue("peek leaves it for the turn that will take it", q.pending())
        q.clear()
        assertNull(q.take(2L))
    }

    @Test fun the_queued_turn_waits_for_the_floor_in_both_senses() {
        // The HOLD path releases the mic gate while the mind keeps working, and the reply's voice
        // outlives the stream loop: submitting on either of those would talk over the answer the
        // caller is listening to. So the floor is free only when the turn AND its voice are done.
        assertTrue(PendingTurn.floorFree(turnInFlight = false, speaking = false,
            glueSpeaking = false, streamingReply = false))
        assertFalse("the mind is still working", PendingTurn.floorFree(true, false, false, false))
        assertFalse("a one-shot reply is playing", PendingTurn.floorFree(false, true, false, false))
        assertFalse("a glue line is playing", PendingTurn.floorFree(false, false, true, false))
        assertFalse("the streaming reply is still draining",
            PendingTurn.floorFree(false, false, false, true))
    }

    @Test fun the_default_ttl_outlasts_a_slow_tool_run_but_not_a_conversation() {
        assertTrue(PendingTurn.DEFAULT_TTL_MS >= 60_000L)
        val q = PendingTurn()
        q.offer("hold on, what about the price?", 0L)
        assertEquals("a 20 s lookup is well inside it",
            "hold on, what about the price?", q.take(20_000L))
    }
}
