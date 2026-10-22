package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * #207 — the chunk/partial assembly behind OnlineNemotronStt, driven through a fake
 * decoder that records exactly what it was given. No native library is involved; what is
 * proven is the feeding protocol (every sample, in order, chunked, then the tail, then
 * inputFinished, then a final drain, then release), not the model's accuracy.
 */
class StreamingTranscriberTest {

    /** Records every call. Its "text" is one token per 100 ms of NON-silent audio decoded,
     *  so a dropped, duplicated or undrained chunk shows up in the result. */
    private class FakeDecoder(private val failOnAccept: Int = -1) : StreamDecoder {
        val calls = mutableListOf<String>()
        val fed = mutableListOf<Float>()
        val chunkSizes = mutableListOf<Int>()
        var buffered = 0; var decodedVoiced = 0; var released = false; var finished = false
        private var voicedBuffered = 0
        override fun accept(samples: FloatArray, sampleRate: Int) {
            if (chunkSizes.size == failOnAccept) throw IllegalStateException("boom")
            check(!finished) { "audio after inputFinished" }
            calls += "accept"; chunkSizes += samples.size
            samples.forEach { fed += it }
            buffered += samples.size; voicedBuffered += samples.count { it != 0f }
        }
        override fun decodeReady() { calls += "decode"; decodedVoiced += voicedBuffered; voicedBuffered = 0; buffered = 0 }
        override fun text(): String = " " + List(decodedVoiced / 1600) { "w" }.joinToString(" ") + " "
        override fun inputFinished() { calls += "finished"; finished = true }
        override fun release() { calls += "release"; released = true }
    }

    private fun voiced(n: Int) = FloatArray(n) { 0.01f + (it % 7) / 1000f }

    @Test fun clip_is_fed_whole_in_order_in_bench_sized_chunks_then_tail_then_finished() {
        val d = FakeDecoder()
        val clip = voiced(16000 * 3 + 123)   // 3 s + a ragged last chunk
        val text = StreamingTranscriber { d }.transcribeClip(clip, 16000)

        val tail = 16000 * StreamingTranscriber.TAIL_MS / 1000
        assertEquals(clip.size + tail, d.fed.size)
        assertEquals(clip.toList(), d.fed.subList(0, clip.size))                // every sample, in order
        assertTrue(d.fed.subList(clip.size, d.fed.size).all { it == 0f })         // then pure silence
        val chunks = d.chunkSizes.dropLast(1)
        assertTrue(chunks.all { it <= 320 })                                      // 20 ms at 16 kHz
        assertEquals(clip.size, chunks.sum())
        assertEquals(123, chunks.last())
        assertEquals(tail, d.chunkSizes.last())
        // Each chunk is drained before the next; the tail is flushed after inputFinished.
        assertEquals(listOf("accept", "finished", "decode", "release"), d.calls.takeLast(4))
        assertEquals(chunks.size, d.calls.windowed(2).count { it == listOf("accept", "decode") })
        assertTrue(d.released)
        assertEquals(List(clip.size / 1600) { "w" }.joinToString(" "), text)    // trimmed, nothing lost
    }

    @Test fun chunk_size_follows_the_sample_rate() {
        assertEquals(320, StreamingTranscriber.chunkSamples(16000))
        assertEquals(160, StreamingTranscriber.chunkSamples(8000))
        assertEquals(960, StreamingTranscriber.chunkSamples(48000))
        val d = FakeDecoder()
        StreamingTranscriber { d }.transcribeClip(voiced(8000), 8000)
        assertTrue(d.chunkSizes.dropLast(1).all { it == 160 })
        assertEquals(4000, d.chunkSizes.last())   // 500 ms of tail at 8 kHz
    }

    @Test fun empty_clip_still_flushes_and_releases() {
        val d = FakeDecoder()
        assertEquals("", StreamingTranscriber { d }.transcribeClip(FloatArray(0), 16000))
        assertEquals(listOf("accept", "finished", "decode", "release"), d.calls)
    }

    @Test fun a_decoder_failure_still_releases_the_stream() {
        val d = FakeDecoder(failOnAccept = 3)
        try { StreamingTranscriber { d }.transcribeClip(voiced(16000), 16000); fail("expected the decoder error") }
        catch (_: IllegalStateException) {}
        assertTrue(d.released)
    }

    @Test fun each_clip_gets_its_own_stream() {
        val opened = mutableListOf<FakeDecoder>()
        val t = StreamingTranscriber { FakeDecoder().also { opened += it } }
        assertEquals("w w w w w w w w w w", t.transcribeClip(voiced(16000), 16000))
        assertEquals("w w w w w", t.transcribeClip(voiced(8000), 16000))   // no carry-over from clip 1
        assertEquals(2, opened.size)
        assertTrue(opened.all { it.released })
    }

    @Test fun feed_accumulates_a_live_partial_until_reset() {
        val opened = mutableListOf<FakeDecoder>()
        val t = StreamingTranscriber { FakeDecoder().also { opened += it } }
        assertEquals("", t.partial())                 // nothing fed: no stream, no text
        assertEquals(0, opened.size)
        t.feed(voiced(1600), 16000)
        assertEquals("w", t.partial())
        t.feed(voiced(3200), 16000)
        assertEquals("w w w", t.partial())            // decoded as it arrives, on one stream
        assertEquals(1, opened.size)
        // #235: fed as the clip is — 20 ms pieces, each drained.
        assertTrue(opened[0].chunkSizes.all { it <= 320 })
        assertEquals(4800, opened[0].chunkSizes.sum())
        assertEquals(opened[0].chunkSizes.size, opened[0].calls.windowed(2).count { it == listOf("accept", "decode") })
        t.reset()
        assertTrue(opened[0].released)
        assertEquals("", t.partial())
        t.feed(voiced(1600), 16000)
        assertEquals(2, opened.size)                  // a fresh utterance
        assertEquals("w", t.partial())
    }

    @Test fun a_whole_clip_does_not_disturb_the_live_stream() {
        val t = StreamingTranscriber { FakeDecoder() }
        t.feed(voiced(3200), 16000)
        t.transcribeClip(voiced(16000), 16000)
        assertEquals("w w", t.partial())
    }

    // ---- #235: one live stream per utterance, fed only the new audio ----

    @Test fun a_two_minute_turn_decodes_every_sample_exactly_once() {
        val opened = mutableListOf<FakeDecoder>()
        val u = LiveUtterance(StreamingTranscriber { FakeDecoder().also { opened += it } })
        val sr = 16000
        val total = sr * 120                      // a two-minute turn
        val tick = sr * 900 / 1000                // the partial worker's cadence
        var fed = 0
        var snapshotDecoded = 0L                  // what the old re-decode-the-snapshot worker cost
        var captured = 0
        while (captured + tick < total) {
            captured += tick
            snapshotDecoded += captured
            u.feed(1, voiced(captured - fed), sr)  // only what arrived since the last feed
            fed = captured
        }
        val text = u.finish(1, voiced(total - fed), sr)
        assertEquals(1, opened.size)                                    // one stream, the whole turn
        assertEquals(total.toLong(), u.lastFinishedFed)                 // no sample decoded twice
        assertEquals(total + sr * StreamingTranscriber.TAIL_MS / 1000, opened[0].fed.size)
        assertTrue(opened[0].released)
        assertEquals(List(total / 1600) { "w" }.joinToString(" "), text)
        // Linear, not quadratic: the old worker decoded ~66x the turn's audio.
        assertTrue(snapshotDecoded > 60L * total)
    }

    @Test fun live_text_matches_the_whole_clip_text() {
        val clip = voiced(16000 * 7 + 55)
        val whole = StreamingTranscriber { FakeDecoder() }.transcribeClip(clip, 16000)
        val u = LiveUtterance(StreamingTranscriber { FakeDecoder() })
        u.feed(4, clip.copyOfRange(0, 30000), 16000)
        u.feed(4, clip.copyOfRange(30000, 90000), 16000)
        assertEquals(whole, u.finish(4, clip.copyOfRange(90000, clip.size), 16000))
    }

    @Test fun a_new_segment_starts_a_new_stream() {
        val opened = mutableListOf<FakeDecoder>()
        val u = LiveUtterance(StreamingTranscriber { FakeDecoder().also { opened += it } })
        assertEquals("w", u.feed(1, voiced(1600), 16000))
        // Segment 1 was dropped (an echo tail, say); segment 2 must not inherit its audio.
        assertEquals("w", u.feed(2, voiced(1600), 16000))
        assertEquals(2, opened.size)
        assertTrue(opened[0].released)
        assertEquals("w w", u.finish(2, voiced(1600), 16000))
        assertEquals(3200L, u.lastFinishedFed)
    }

    @Test fun finishing_an_unfed_segment_decodes_it_whole() {
        // Partial STT off: nothing was fed live, so the finish is the whole clip.
        val u = LiveUtterance(StreamingTranscriber { FakeDecoder() })
        assertEquals("w w w", u.finish(9, voiced(4800), 16000))
        assertEquals(4800L, u.lastFinishedFed)
    }
}
