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

    @Test fun stop_epoch_is_the_one_shot_cancel_token() {
        // 0.4.0.4: `allowed` is level-triggered, so a one-shot speak() that synthesizes
        // for ~1.4s and then opens the fence itself cannot use it to ask "did a stop
        // land while I was busy?" — it would re-open the fence the hush just closed and
        // speak the whole cancelled reply (the ghost voice). stopEpoch answers that.
        val f = StreamFence(); f.start()
        val token = f.stopEpoch                  // captured before synthesis
        assertTrue(f.stopEpoch == token)         // no stop yet -> the play may proceed
        f.stop()                                 // the hush lands mid-synthesis
        assertFalse(f.stopEpoch == token)        // -> the finished utterance is dropped
        f.start()                                // a later, legitimate turn re-opens...
        assertTrue(f.allowed)
        assertFalse(f.stopEpoch == token)        // ...without validating the dead token
    }

    @Test fun stop_epoch_advances_on_every_stop() {
        val f = StreamFence(); f.start()
        val a = f.stopEpoch
        f.stop(); val b = f.stopEpoch
        f.stop(); val c = f.stopEpoch
        assertTrue(b > a && c > b)               // monotonic: a repeat stop still invalidates
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
