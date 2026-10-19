package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * #42 — a deterministic, device-free test of the AUDIO barge-in path.
 *
 * BargeCarryTest proves the carry bookkeeping with constant frames. This test
 * proves the path that actually runs: a synthetic 16 kHz signal (silence, then a
 * speech-like burst) goes through [BargeAudioPath] — the same production class
 * VoiceController's single-capture drain calls — frame by frame. It asserts
 *
 *   - the barge decision fires within a bounded number of reads of the burst
 *     onset (the level-only escape needs ~200 ms, so <= 4 × 64 ms reads),
 *   - playback is cancelled at that decision (the caller's fake playback flag),
 *   - the segment handed to the next turn starts at onset minus the 300 ms
 *     pre-roll and contains EVERY sample after the decision, byte-for-byte.
 *
 * No sleeps, no wall clock, no AudioRecord, no VAD model, no emulator: the clock
 * is the frame index, so the result is reproducible in CI.
 */
class BargeAudioPathTest {

    private val sr = 16000
    private val read = 1024                       // the drain's 64 ms read
    private val frameMs = read * 1000L / sr       // 64
    private val preRollMs = 300
    private val preRollSamples = sr * preRollMs / 1000

    /**
     * Silence up to [onset], then a speech-like burst: a 180 Hz carrier with a
     * 3 Hz syllable envelope. Deterministic floats, no RNG.
     */
    private fun signal(totalSamples: Int, onset: Int): FloatArray {
        val out = FloatArray(totalSamples)
        for (i in onset until totalSamples) {
            val t = (i - onset).toDouble() / sr
            val envelope = 0.75 + 0.25 * sin(2.0 * PI * 3.0 * t)
            out[i] = (0.55 * envelope * cos(2.0 * PI * 180.0 * t)).toFloat()
        }
        return out
    }

    private fun frame(sig: FloatArray, index: Int): FloatArray =
        sig.copyOfRange(index * read, index * read + read)

    @Test fun barge_fires_near_the_onset_cancels_playback_and_carries_the_next_segment() {
        val onsetFrame = 20                        // 1.28 s of silence first
        val onset = onsetFrame * read
        val totalFrames = 60                       // 3.84 s: plenty after the decision
        val sig = signal(totalFrames * read, onset)
        val carry = BargeCarry(sr, preRollMs = preRollMs, historyMs = 2000)
        val path = BargeAudioPath(
            sampleRate = sr,
            rmsMin = BargeGate.DEFAULT_RMS_MIN,
            vadAvailable = false,                  // the off-device fallback gate
            levelOnlyMs = BargeGate.DEFAULT_LEVEL_ONLY_MS,
            carry = carry,
        )

        var playing = true                         // fake playback at the bargeIn() seam
        var firedFrame = -1
        for (f in 0 until totalFrames) {
            val frames = frame(sig, f)
            if (path.fired) {
                // The production drain keeps every post-decision read for the next turn.
                path.keep(frames)
                continue
            }
            val bf = path.process(frames, vadSpeech = false, nowMs = f * frameMs)
            if (bf.fired) {
                firedFrame = f
                playing = false                    // bargeIn() cancels playback
            }
        }

        // The decision fired during the burst, within the 200 ms level-only sustain.
        assertFalse("the barge must cancel playback", playing)
        assertTrue("the barge must not fire before the burst", firedFrame > onsetFrame)
        assertTrue(
            "barge fired $firedFrame, onset $onsetFrame — more than 4 reads (256 ms) late",
            firedFrame - onsetFrame <= 4,
        )

        // The carry is the next turn's opening segment: onset - pre-roll .. now, whole.
        val carried = carry.take()!!
        val from = onset - preRollSamples
        val expected = sig.copyOfRange(from, totalFrames * read)
        assertEquals("the carry must start exactly at onset - pre-roll", expected.size, carried.size)
        assertEquals(sig[from], carried[0], 0f)
        assertSamplesEqual("every frame after the decision must survive", expected, carried)

        // Explicitly: the last read (all of it) is in the carry, not dropped at the cut.
        val lastRead = frame(sig, totalFrames - 1)
        assertSamplesEqual("the last read", lastRead, carried.copyOfRange(carried.size - read, carried.size))
        // And the onset itself is present at the pre-roll offset (not clipped).
        assertEquals(sig[onset], carried[preRollSamples], 0f)
    }

    @Test fun silence_never_barges() {
        val sig = signal(30 * read, onset = 30 * read)   // 1.92 s of pure silence
        val carry = BargeCarry(sr, preRollMs = preRollMs, historyMs = 2000)
        val path = BargeAudioPath(sr, BargeGate.DEFAULT_RMS_MIN, vadAvailable = false,
            levelOnlyMs = BargeGate.DEFAULT_LEVEL_ONLY_MS, carry = carry)

        for (f in 0 until 30) {
            assertFalse(path.process(frame(sig, f), vadSpeech = false, nowMs = f * frameMs).fired)
        }
        assertFalse(path.fired)
        assertFalse(carry.fired)
    }

    private fun assertSamplesEqual(msg: String, expected: FloatArray, actual: FloatArray) {
        assertEquals(msg, expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("$msg (sample $i)", expected[i], actual[i], 0f)
        }
    }
}
