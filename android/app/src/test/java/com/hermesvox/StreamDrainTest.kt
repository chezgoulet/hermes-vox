package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #538, the reporter's log (2026-11-10): a reply with less than 4 s of audio never reached the
 * 4 s stream track's start threshold, so its playback head stayed at 0 and the drain waited the
 * full 120 s ceiling with the turn gate held ("The entity is working…", the mic deaf, the
 * gateway idle). These tests drive [StreamDrain] with the head the track actually reported.
 * Pure JVM — no Android deps.
 */
class StreamDrainTest {

    private val sr = 44_100

    /** Run a drain against a head function; returns (steps taken, ms elapsed, FORCE count). */
    private fun run(d: StreamDrain, tickMs: Long = 8L, capMs: Long = 120_000L,
                    playing: (Long) -> Boolean = { true }, head: (Long, Boolean) -> Long): Triple<Int, Long, Int> {
        var now = 0L; var steps = 0; var forces = 0
        while (now < capMs) {
            val step = d.step(now, head(now, d.forced), playing(now))
            steps++
            if (step == StreamDrain.Step.DONE) return Triple(steps, now, forces)
            if (step == StreamDrain.Step.FORCE) forces++
            now += tickMs
        }
        return Triple(steps, now, forces)
    }

    /** The 0.9.1 drain: wait while PLAYING until head >= written - 1, 8 ms ticks, 120 s cap. */
    private fun before538WaitMs(written: Long, head: (Long) -> Long): Long {
        var waited = 0L
        while (waited < 120_000L) {
            if (head(waited) >= written - 1L) break
            waited += 8
        }
        return waited
    }

    @Test fun before_538_a_short_reply_held_the_turn_for_the_whole_ceiling() {
        // The log's last turn: one 29-char chunk, 91,665 samples @ 44.1 kHz (2.08 s), head stuck at 0.
        assertEquals(120_000L, before538WaitMs(91_665L) { 0L })
    }

    @Test fun the_reporters_last_turn_retires_in_seconds_not_two_minutes() {
        val d = StreamDrain(written = 91_665L, sampleRate = sr, startMs = 0L, startHead = 0L)
        // Head stuck at 0 until stop() forces the play-out; then it advances in real time.
        var forcedAt = -1L
        val (_, ms, forces) = run(d) { now, forced ->
            if (!forced) 0L else { if (forcedAt < 0) forcedAt = now; (now - forcedAt) * sr / 1000 }
        }
        assertEquals(1, forces)
        assertEquals(StreamDrain.Result.FORCED, d.result)
        // stuck detection + the 2.08 s of audio itself
        assertTrue("drain took ${ms}ms", ms in 2_000L..3_000L)
    }

    @Test fun a_head_that_never_moves_even_after_stop_is_bounded_by_the_owed_audio() {
        val d = StreamDrain(written = 91_665L, sampleRate = sr, startMs = 0L, startHead = 0L)
        val (_, ms, forces) = run(d) { _, _ -> 0L }
        assertEquals(1, forces)
        assertEquals(StreamDrain.Result.FORCED, d.result)
        val bound = StreamDrain.STUCK_MS + StreamDrain.audioMs(91_665L, sr) + StreamDrain.FORCED_SLACK_MS
        assertTrue("drain took ${ms}ms, bound ${bound}ms", ms <= bound + 8)
    }

    @Test fun a_forced_track_that_resets_its_head_after_playing_out_is_done() {
        val d = StreamDrain(written = 44_100L, sampleRate = sr, startMs = 0L, startHead = 0L)
        var forcedAt = -1L
        val (_, ms, _) = run(d) { now, forced ->
            if (!forced) 0L else {
                if (forcedAt < 0) forcedAt = now
                val h = (now - forcedAt) * sr / 1000
                if (h >= 40_000L) 0L else h        // the platform zeroes the head when the stop completes
            }
        }
        assertEquals(StreamDrain.Result.FORCED, d.result)
        assertTrue("drain took ${ms}ms", ms < StreamDrain.STUCK_MS + 1_000L + StreamDrain.FORCED_SLACK_MS)
    }

    @Test fun a_long_reply_whose_head_moves_is_never_forced() {
        // The healthy shape: a 4 s+ reply, head advancing every 40 ms (a HAL period), tail 3 s owed.
        val written = 6L * sr
        val d = StreamDrain(written = written, sampleRate = sr, startMs = 0L, startHead = 3L * sr)
        val (_, ms, forces) = run(d) { now, _ -> 3L * sr + (now / 40) * 40 * sr / 1000 }
        assertEquals(0, forces)
        assertEquals(StreamDrain.Result.PLAYED, d.result)
        assertTrue("drain took ${ms}ms", ms in 2_900L..3_100L)
    }

    @Test fun a_bluetooth_sized_head_period_is_not_mistaken_for_stuck() {
        val written = 2L * sr
        val d = StreamDrain(written = written, sampleRate = sr, startMs = 0L, startHead = 0L)
        val (_, _, forces) = run(d) { now, _ -> (now / 200) * 200 * sr / 1000 }   // 200 ms steps
        assertEquals(0, forces)
        assertEquals(StreamDrain.Result.PLAYED, d.result)
    }

    @Test fun a_head_that_moves_then_stalls_mid_reply_is_forced() {
        val d = StreamDrain(written = 3L * sr, sampleRate = sr, startMs = 0L, startHead = 0L)
        val (_, _, forces) = run(d) { now, _ -> minOf(now, 1_000L) * sr / 1000 }
        assertEquals(1, forces)
        assertEquals(StreamDrain.Result.FORCED, d.result)
    }

    @Test fun a_track_someone_else_paused_ends_the_drain_at_once() {
        val d = StreamDrain(written = 2L * sr, sampleRate = sr, startMs = 0L, startHead = 0L)
        val (steps, _, forces) = run(d, playing = { false }) { _, _ -> 0L }
        assertEquals(1, steps)
        assertEquals(0, forces)
        assertEquals(StreamDrain.Result.NOT_PLAYING, d.result)
    }

    @Test fun a_head_that_crawls_past_its_deadline_times_out() {
        // Moves 1 frame per tick: never stuck, never done. Bounded by owed audio + slack.
        val d = StreamDrain(written = sr.toLong(), sampleRate = sr, startMs = 0L, startHead = 0L)
        val (_, ms, forces) = run(d) { now, _ -> now / 8 }
        assertEquals(0, forces)
        assertEquals(StreamDrain.Result.TIMEOUT, d.result)
        assertTrue(ms <= 1_000L + StreamDrain.SLACK_MS + 8)
    }

    @Test fun nothing_written_is_done_immediately() {
        val d = StreamDrain(written = 0L, sampleRate = sr, startMs = 0L, startHead = 0L)
        assertEquals(StreamDrain.Step.DONE, d.step(0L, 0L, true))
        assertFalse(d.forced)
    }

    @Test fun start_threshold_is_a_quarter_second_within_the_track() {
        val capacity = 4 * sr                                   // STREAM_BUFFER_SECS = 4
        assertEquals(sr / 4, StreamDrain.startThresholdFrames(sr, minBufFrames = 1_024, capacityFrames = capacity))
        // the platform minimum wins when it is larger
        assertEquals(20_000, StreamDrain.startThresholdFrames(sr, minBufFrames = 20_000, capacityFrames = capacity))
        // never more than the track holds, never zero
        assertEquals(5_000, StreamDrain.startThresholdFrames(sr, minBufFrames = 1_024, capacityFrames = 5_000))
        assertEquals(1, StreamDrain.startThresholdFrames(0, minBufFrames = 0, capacityFrames = 0))
    }

    @Test fun every_reply_in_the_log_shorter_than_the_buffer_now_starts() {
        // The log's sub-4 s replies ran 1.26–3.82 s (55,566–168,462 samples @ 44.1 kHz). Each is
        // above the new quarter-second threshold and below the 4 s capacity, i.e. exactly the band
        // that the default (full-buffer) threshold left silent.
        val threshold = StreamDrain.startThresholdFrames(sr, 1_024, 4 * sr)
        for (secs in listOf(1.26, 1.46, 2.08, 3.10, 3.82)) {
            val samples = (secs * sr).toLong()
            assertTrue(samples >= threshold)
            assertTrue(samples < 4L * sr)
        }
    }
}
