package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #538: the streaming worker's reading of a chunk that wrote nothing.
 *
 * The field shape: Supertonic, then the system voice, then "spoken replies off" until /new.
 * #549 closed one way in (a one-shot play() with no track). This pins the other: a
 * streamed reply. One genuine failure re-warms the engine; the re-warmed engine is a NEW
 * SherpaTts whose fence no stream ever opened, so every later chunk of the same reply
 * returned false and was reported as `stream-synth` — the degradation, and then the
 * steady-off, were spent by chunks that nothing was wrong with.
 */
class StreamChunkResultTest {

    // ---- the fence can tell "new" from "stopped" -------------------------------

    @Test fun a_fresh_fence_was_never_opened_but_a_stopped_one_was() {
        val fresh = StreamFence()
        assertFalse(fresh.allowed)
        assertFalse("a replacement engine's fence: closed because new", fresh.opened)

        val stopped = StreamFence().apply { start(); stop() }
        assertFalse(stopped.allowed)
        assertTrue("closed because stopped", stopped.opened)
    }

    // ---- who is to blame -------------------------------------------------------

    @Test fun only_the_engine_s_own_faults_spend_the_budget() {
        assertEquals("stream-synth", StreamSynthResult.failureCode(ChunkResult.SILENT, false, false))
        assertEquals("stream-synth", StreamSynthResult.failureCode(ChunkResult.ERROR, false, false))
        assertEquals("track-unavailable", StreamSynthResult.failureCode(ChunkResult.TRACK_UNAVAILABLE, false, false))
        assertTrue("track-unavailable" in TtsEngineHost.NOT_THE_ENGINE)
        for (r in listOf(ChunkResult.WROTE, ChunkResult.NOT_OPEN, ChunkResult.CUT, ChunkResult.NO_ENGINE)) {
            assertNull("$r blames nobody", StreamSynthResult.failureCode(r, false, false))
        }
    }

    @Test fun a_stop_blames_nobody_whatever_the_chunk_said() {
        for (r in ChunkResult.entries) {
            assertNull(StreamSynthResult.failureCode(r, cancelled = true, closed = false))
            assertNull(StreamSynthResult.failureCode(r, cancelled = false, closed = true))
        }
    }

    @Test fun only_a_never_opened_fence_on_a_live_reply_is_adopted() {
        assertTrue(StreamSynthResult.adopt(ChunkResult.NOT_OPEN, cancelled = false, closed = false))
        assertFalse(StreamSynthResult.adopt(ChunkResult.NOT_OPEN, cancelled = true, closed = false))
        assertFalse(StreamSynthResult.adopt(ChunkResult.NOT_OPEN, cancelled = false, closed = true))
        assertFalse("a stop is never re-opened over", StreamSynthResult.adopt(ChunkResult.CUT, false, false))
    }

    // ---- the cascade, replayed against the real host ---------------------------

    /** A streaming engine stand-in: its own fence, like every SherpaTts instance. */
    private class StreamFake(token: String) : VoxTts {
        val fence = StreamFence()
        override val name = token
        override val isWarm = true
        override val warmReason = ""
        override fun init(onReady: (Boolean) -> Unit) = onReady(true)
        override fun speak(text: String, onDone: () -> Unit) = onDone()
        override fun stop() = fence.stop()
        override fun shutdown() {}
        /** What streamChunk() answers when the engine itself is healthy. */
        fun chunk(): ChunkResult = when {
            fence.allowed -> ChunkResult.WROTE
            fence.opened -> ChunkResult.CUT
            else -> ChunkResult.NOT_OPEN
        }
    }

    private val warns = ArrayList<String>()

    private fun host() = TtsEngineHost(
        requested = { "supertonic" },
        installed = { true },
        factory = { token -> if (token == "system") StreamFake("system") else StreamFake(token) },
        warn = { warns += it },
    )

    /** One reply: the stream opens on the current engine, chunk 1 is a genuine engine
     *  failure, the rest are healthy. [read] is the worker's reading of each result. */
    private fun reply(h: TtsEngineHost, read: (ChunkResult, StreamFake) -> Unit) {
        (h.engine as StreamFake).fence.start()                  // streamBegin()
        h.noteFailure("stream-synth")                           // chunk 1: the one real fault
        repeat(4) {                                             // chunks 2..5, all healthy
            val e = h.engine as? StreamFake ?: return
            read(e.chunk(), e)
        }
    }

    @Test fun before_538_one_bad_phrase_spent_the_whole_voice_budget() {
        val h = host(); h.ensure()
        // The old reading: any chunk that did not write is engine death.
        reply(h) { r, _ -> if (r != ChunkResult.WROTE) h.noteFailure("stream-synth") }
        assertTrue("the old reading degraded a healthy voice: $warns",
            warns.any { it.startsWith("event=tts-degrade from=supertonic to=system") })
        assertTrue("...and then switched spoken replies off, in ONE reply: $warns",
            warns.any { it.startsWith("event=tts-unavailable") && it.contains("call continues text-only") })
    }

    @Test fun after_538_the_rewarmed_engine_is_adopted_and_keeps_the_voice() {
        val h = host(); h.ensure()
        var adopted = 0
        var wrote = 0
        reply(h) { r, e ->
            var res = r
            if (StreamSynthResult.adopt(res, cancelled = false, closed = false)) {
                adopted++; e.fence.start(); res = e.chunk()
            }
            if (res == ChunkResult.WROTE) wrote++
            StreamSynthResult.failureCode(res, false, false)?.let { h.noteFailure(it) }
        }
        assertEquals("the new engine is adopted once", 1, adopted)
        assertEquals("every healthy chunk after the fault plays", 4, wrote)
        assertTrue("no degradation: $warns", warns.none { it.startsWith("event=tts-degrade") })
        assertEquals("still Supertonic", "supertonic", h.state.engine)
        assertTrue(h.state.speaking)
    }

    @Test fun a_streamed_track_failure_is_logged_and_costs_nothing() {
        val h = host(); h.ensure()
        val first = h.engine
        repeat(3) { h.noteFailure("track-unavailable") }
        assertEquals("the same engine, never re-warmed", first, h.engine)
        assertEquals(3, warns.count { it.startsWith("event=tts-fail engine=supertonic code=track-unavailable") })
        assertTrue(warns.none { it.contains("tts-unavailable") || it.contains("tts-degrade") })
        assertTrue(h.state.speaking)
    }
}
