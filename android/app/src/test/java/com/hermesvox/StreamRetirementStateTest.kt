package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM proof of the single-owner retirement truth table (R1). The worker's
 * exit condition is `sClosed || (sFinal && queue empty)`, decided here so the rule
 * is testable off-device (same pattern as BargeGate/EndpointRule).
 *
 * RETIRE is the NATURAL exit: the text side marked the stream final (done=true,
 * ok text) AND the sentence queue is fully drained — ONLY then may the worker close
 * the stream itself. ABORT (sClosed) always wins over a final flag, so a late
 * barge/hush/stop keeps the immediate fence the field log validated.
 */
class StreamRetirementStateTest {

    @Test fun stream_open_with_chunks_waits() {
        assertEquals(Retirement.WAIT, StreamRetirementState.decide(sFinal = false, sClosed = false, queueEmpty = false))
    }

    @Test fun stream_open_empty_queue_is_a_synth_gap_not_the_end() {
        // final text has NOT arrived yet: an empty queue is just a synth gap —
        // the worker must keep waiting for the next sentence, never close.
        assertEquals(Retirement.WAIT, StreamRetirementState.decide(sFinal = false, sClosed = false, queueEmpty = true))
    }

    @Test fun final_but_tail_still_queued_keeps_playing() {
        // done=true arrived, but sentences remain queued — keep draining the whole reply.
        assertEquals(Retirement.WAIT, StreamRetirementState.decide(sFinal = true, sClosed = false, queueEmpty = false))
    }

    @Test fun final_and_drained_retires() {
        // The ONLY natural-exit row: every sentence handed to the engine AND played.
        assertEquals(Retirement.RETIRE, StreamRetirementState.decide(sFinal = true, sClosed = false, queueEmpty = true))
    }

    @Test fun abort_wins_over_open_stream_and_pending_chunks() {
        assertEquals(Retirement.ABORT, StreamRetirementState.decide(sFinal = false, sClosed = true, queueEmpty = false))
        assertEquals(Retirement.ABORT, StreamRetirementState.decide(sFinal = false, sClosed = true, queueEmpty = true))
    }

    @Test fun abort_wins_over_final_even_while_draining() {
        // a late barge during the tail still aborts — the worker never settles it.
        assertEquals(Retirement.ABORT, StreamRetirementState.decide(sFinal = true, sClosed = true, queueEmpty = false))
        assertEquals(Retirement.ABORT, StreamRetirementState.decide(sFinal = true, sClosed = true, queueEmpty = true))
    }
}
