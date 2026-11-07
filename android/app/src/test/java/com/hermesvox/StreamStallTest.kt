package com.hermesvox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stalled engine mid-reply must not turn the reply into a "silent midpoint".
 *
 * SherpaTts.streamChunk() never writes a silent buffer: it returns false when the
 * synthesized samples have no audible content (`audioIsSilent`) or when a stop
 * raced the chunk. VoiceController reads that result with [StreamSynthResult]:
 * a live reply reports an engine failure (the host re-warms) and keeps draining;
 * only a stop makes the false a non-failure. What it never does is treat the gap
 * as completion — the turn is still `sFinal`/drained, and the next chunk plays.
 */
class StreamStallTest {

    @Test
    fun silence_for_a_chunk_is_an_engine_failure_and_the_reply_keeps_draining() {
        // The shape streamChunk() rejects before it touches the track.
        assertTrue(audioIsSilent(FloatArray(4096)))
        assertTrue(
            "a live reply must report the engine, not read the gap as done",
            StreamSynthResult.failed(wrote = false, cancelled = false, closed = false),
        )
    }

    @Test
    fun a_chunk_cut_by_a_stop_is_not_an_engine_failure() {
        assertFalse(StreamSynthResult.failed(wrote = false, cancelled = true, closed = false))
        assertFalse(StreamSynthResult.failed(wrote = false, cancelled = false, closed = true))
        assertFalse(StreamSynthResult.failed(wrote = true, cancelled = false, closed = false))
    }
}
