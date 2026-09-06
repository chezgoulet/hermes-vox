package com.hermesvox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the streaming-TTS immediate-silence fence (#D1): a chunk is allowed to
 * play iff the stream was STARTED and not yet STOPPED. stopStreaming() closes
 * the fence so a synth worker still iterating its queued-sentence loop after a
 * barge/hush/hangup sees allowed=false and returns instead of REBUILDING the
 * nulled AudioTrack (the old resurrection that kept a reply playing seconds
 * after the cut). Pure JVM — no Android deps.
 */
class StreamFenceTest {

    @Test fun fresh_fence_rejects_playback() {
        assertFalse(StreamFence().allowed)   // never started -> nothing may play
    }

    @Test fun start_allows_playback() {
        val f = StreamFence()
        f.start()
        assertTrue(f.allowed)
    }

    @Test fun stop_closes_the_fence_immediately() {
        val f = StreamFence(); f.start()
        f.stop()
        assertFalse(f.allowed)
    }

    @Test fun stop_stays_closed_until_a_new_start() {
        val f = StreamFence(); f.start()
        f.stop(); f.stop()                       // repeated stops stay closed
        assertFalse(f.allowed)
        f.start()                                // a NEW startStreaming() re-arms
        assertTrue(f.allowed)
    }

    @Test fun a_stopped_worker_cannot_resurrect_playback() {
        // The bug: streamChunk() treated the null track left by stopStreaming() as
        // "first chunk" and BUILT A NEW AudioTrack, so the still-running worker
        // resurrected playback after every stop. The fence makes every post-stop
        // chunk attempt a no-op no matter how many queued sentences remain.
        val f = StreamFence(); f.start()
        assertTrue(f.allowed)                    // the in-flight chunk is fine
        f.stop()                                 // barge/hush/endCall lands
        for (i in 0 until 5) assertFalse(f.allowed)   // queued chunks N+1.. are all refused
    }
}
