package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #6 — a stream that fails part-way must deliver whatever assistant text was
 * already assembled, exactly once, through the normal settle path. These tests
 * drive the terminal with a fake stream: N deltas, then a failure.
 *
 * Pure JVM: [StreamTurnTerminal] has no Android deps.
 */
class StreamTurnTerminalTest {

    /** The fake stream: pushes deltas/chunks and then a terminal transition. */
    private class FakeStream(val terminal: StreamTurnTerminal = StreamTurnTerminal()) {
        fun emit(vararg deltas: String) = deltas.forEach { terminal.delta(it) }
        fun fail(reason: String): StreamAction = terminal.fail(reason)
        fun complete(): StreamAction = terminal.complete()
        fun cancel(): StreamAction = terminal.cancel()
    }

    @Test fun a_failure_after_deltas_delivers_the_assembled_reply_once() {
        // (a) the reply is delivered once
        val stream = FakeStream()
        stream.emit("The backup ", "finished at ", "3:04 am.")

        val first = stream.fail("poll-abort")
        assertTrue("assembled text must fall back, got $first", first is StreamAction.Fallback)
        first as StreamAction.Fallback
        assertEquals("The backup finished at 3:04 am.", first.text)
        assertEquals("poll-abort", first.reason)
        assertEquals(first.text.length, first.chars)
        assertEquals(StreamPhase.FALLBACK, stream.terminal.state)

        // Exactly once: a second failure for the same turn yields no action.
        assertEquals(StreamAction.None, stream.fail("poll-abort"))
        assertEquals(StreamAction.None, stream.fail("stream-error"))
        assertEquals(StreamPhase.FALLBACK, stream.terminal.state)
    }

    @Test fun a_failure_with_no_deltas_is_still_an_error() {
        // (b) nothing is delivered when no delta arrived
        val stream = FakeStream()
        val action = stream.fail("turn-timeout")
        assertTrue("an empty reply must stay an error, got $action", action is StreamAction.Failure)
        assertEquals("turn-timeout", (action as StreamAction.Failure).reason)
        assertEquals(StreamPhase.FAILED, stream.terminal.state)
        assertEquals(StreamAction.None, stream.fail("turn-timeout"))
    }

    @Test fun a_chunk_after_the_failure_cannot_double_deliver() {
        // (c) a late chunk arriving after fallback does not speak the reply again
        val stream = FakeStream()
        stream.emit("Partial answer.")
        val delivered = stream.fail("stream-error") as StreamAction.Fallback
        assertEquals("Partial answer.", delivered.text)

        // The late chunk is ignored by the state, not appended and not re-settled.
        assertFalse("a terminal turn must reject late chunks", stream.terminal.delta(" And more."))
        assertEquals("Partial answer.", stream.terminal.text())
        assertEquals(StreamAction.None, stream.fail("stream-error"))
        assertEquals(StreamAction.None, stream.complete())
        assertEquals(StreamPhase.FALLBACK, stream.terminal.state)
    }

    @Test fun a_barge_cancel_still_delivers_nothing() {
        // (d) the user-initiated cancel path never starts speaking
        val stream = FakeStream()
        stream.emit("Half a repl")
        assertEquals(StreamAction.None, stream.cancel())
        assertEquals(StreamPhase.CANCELLED, stream.terminal.state)

        // A failure or completion racing the barge is a no-op: the cancel owns the turn.
        assertEquals(StreamAction.None, stream.fail("stream-error"))
        assertEquals(StreamAction.None, stream.complete())
        assertEquals(StreamPhase.CANCELLED, stream.terminal.state)
    }

    @Test fun a_completed_turn_never_falls_back() {
        // A normal completion latches COMPLETED; a later error event cannot speak it twice.
        val stream = FakeStream()
        stream.emit("All done.")
        val done = stream.complete() as StreamAction.Completed
        assertEquals("All done.", done.text)
        assertEquals(StreamPhase.COMPLETED, stream.terminal.state)
        assertEquals(StreamAction.None, stream.fail("error"))
        assertEquals(StreamAction.None, stream.cancel())
    }

    @Test fun the_gateway_snapshot_is_the_authoritative_reply() {
        // The poll payload's `text` (tool markers already stripped upstream) replaces
        // the locally accumulated deltas; once terminal, it can no longer be rewritten.
        val stream = FakeStream()
        stream.emit("Hel")
        stream.terminal.assembled("Hello there.")
        val delivered = stream.fail("reply-error") as StreamAction.Fallback
        assertEquals("Hello there.", delivered.text)
        assertFalse(stream.terminal.assembled("a late snapshot"))
        assertEquals("Hello there.", stream.terminal.text())
    }
}
