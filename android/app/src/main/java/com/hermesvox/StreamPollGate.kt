package com.hermesvox

/**
 * StreamPollGate — the pure decision rule for the streamed-turn poll loop's exit
 * (H1, 0.5.3).
 *
 * The loop used to run `while (!done && tries < 600)`: a poll-ITERATION cap that
 * masqueraded as a ~60s time budget. It only behaves as a clock when the stream is
 * IDLE (waitStream blocks the full 100ms per iteration). Under active streaming
 * WaitStream returns the instant an SSE event is buffered, so the loop spins at the
 * delta rate and the cap becomes an EVENT COUNTER, not a clock. A ~600-token reply
 * (≈2400 chars — an ordinary long answer, or a shorter one carrying several
 * function_call / function_call_output events, which also consume iterations) hit
 * the cap at event #600 and the whole buffered reply was thrown away as "timeout" —
 * often just 15-20s in. Ironically the FASTER the provider streamed, the sooner the
 * reply was destroyed.
 *
 * The ceiling is now WALL-CLOCK, which is what the "timeout" always intended: the
 * loop keeps polling until the turn completes or the deadline passes, indifferent to
 * how many events (or how few) arrived along the way. Iteration count is deliberately
 * NOT an input to [keepPolling].
 *
 * Truth table:
 *  done   now < deadline  -> keepPolling
 *  true   any             NO   (turn complete — settle/retire owns it from here)
 *  false  true            YES  (still streaming, inside the budget — keep polling)
 *  false  false           NO   (deadline passed with no completion — caller throws "timeout")
 */
internal object StreamPollGate {

    /** The wall-clock budget for one streamed turn's poll loop. Generous on purpose:
     *  a single voice reply — even a long one with tool calls — completes well inside
     *  this, while a genuinely wedged stream still gives up. It replaces the old
     *  600-iteration cap that cut real replies at ~15-20s. A reply that runs longer
     *  than this is still cut, which is the intended timeout (and ~6-8x the old
     *  effective ceiling). */
    const val STREAM_TURN_TIMEOUT_MS = 120_000L

    /** True while the poll loop should keep going: the turn hasn't completed AND the
     *  wall-clock deadline hasn't passed. Event/iteration count is NOT a parameter —
     *  that was the H1 bug. */
    fun keepPolling(done: Boolean, nowMs: Long, deadlineMs: Long): Boolean =
        !done && nowMs < deadlineMs
}
