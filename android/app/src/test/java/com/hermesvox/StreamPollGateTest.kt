package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H1 regression: the streamed-turn poll loop exits on a WALL-CLOCK deadline, not a
 * poll-iteration cap. The old `while (!done && tries < 600)` counted SSE events once
 * the stream was active (WaitStream returns the instant a delta is buffered), so a
 * long reply was discarded as "timeout" at event #600 — often ~15-20s in, and sooner
 * the faster the provider streamed. These prove the decision is indifferent to event
 * count and stops only on `done` or the deadline. Pure JVM (same pattern as
 * StreamRetirementState / BargeGate).
 */
class StreamPollGateTest {

    private val deadline = StreamPollGate.STREAM_TURN_TIMEOUT_MS   // 120_000ms

    @Test fun a_long_fast_reply_completes_instead_of_timing_out() {
        // A provider streaming ~1 event/ms: 5000 events land in ~5s, far inside the
        // 120s budget. The OLD `tries < 600` cap threw "timeout" at event #600. The
        // wall-clock gate polls until the turn reports done.
        var now = 0L
        var events = 0
        var done = false
        while (StreamPollGate.keepPolling(done, now, deadline)) {
            events++
            now += 1L                              // 1ms of wall-clock per event
            if (events == 5000) done = true        // the reply completes at event 5000
        }
        assertEquals("all 5000 events were delivered (no 600 cap)", 5000, events)
        assertTrue("the turn completed — never thrown as timeout", done)
        assertTrue("...well inside the wall-clock budget", now < deadline)
    }

    @Test fun event_count_alone_never_ends_the_loop() {
        // Way past the old 600 cap, still inside the deadline -> keep polling.
        assertTrue(StreamPollGate.keepPolling(done = false, nowMs = 601L, deadlineMs = deadline))
        assertTrue(StreamPollGate.keepPolling(done = false, nowMs = 5_000L, deadlineMs = deadline))
        assertTrue(StreamPollGate.keepPolling(done = false, nowMs = deadline - 1, deadlineMs = deadline))
    }

    @Test fun done_ends_the_loop_regardless_of_remaining_budget() {
        assertFalse(StreamPollGate.keepPolling(done = true, nowMs = 0L, deadlineMs = deadline))
        assertFalse(StreamPollGate.keepPolling(done = true, nowMs = deadline - 1, deadlineMs = deadline))
    }

    @Test fun an_idle_stream_still_times_out_on_the_wall_clock() {
        // No completion, clock at/past the deadline -> stop (the caller throws "timeout").
        assertFalse(StreamPollGate.keepPolling(done = false, nowMs = deadline, deadlineMs = deadline))
        assertFalse(StreamPollGate.keepPolling(done = false, nowMs = deadline + 5_000L, deadlineMs = deadline))
    }
}
