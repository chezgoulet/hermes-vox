package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #235's acceptance harness — decoded audio-seconds per utterance, over a deterministic
 * synthetic source, with no device, no model and no wall clock.
 *
 * The unit of cost is the number of audio samples handed to [StreamDecoder.accept]: a fake
 * decoder counts them, and the real recogniser decodes exactly what it is given. The
 * endpoint tail both paths add at the end is tracked separately and excluded, so the
 * measured ratio is "utterance audio decoded ÷ utterance length". A ratio of 1.0 means every
 * sample of the turn was decoded exactly once, however long the turn ran.
 *
 * Two workers are measured through the same harness:
 *  - [liveCost]   — the shape #235 ships: ONE stream per utterance, fed only the audio that
 *                   arrived since the previous tick ([LiveUtterance] + [StreamingTranscriber.feed]).
 *  - [legacyCost] — the shape before it, and the control for this harness: a fresh
 *                   whole-snapshot decode every tick, which is the quadratic curve.
 *
 * The live worker is asserted against the linear rule; the legacy worker is asserted to
 * violate that same rule, so a harness that could not tell the two apart would fail here.
 */
class PartialDecodeLinearTest {

    private companion object {
        const val SR = 16000
        const val TAIL = SR * StreamingTranscriber.TAIL_MS / 1000
        /** 15 s → 2 min: the two-minute turn is the issue's stated acceptance. */
        val LENGTHS_MS = listOf(15_000, 30_000, 60_000, 120_000)

        /** The fixed audio source. Deterministic and non-silent so a fake decoder can count
         *  it; the values themselves never matter, only their number. */
        fun source(n: Int) = FloatArray(n) { 0.01f + (it % 7) * 0.001f }
    }

    /** Fake decoder counting what it is given, per stream and across streams. */
    private class Meter {
        var decoded = 0L
            private set
        var opened = 0
            private set
        fun open(): StreamDecoder {
            opened++
            return object : StreamDecoder {
                override fun accept(samples: FloatArray, sampleRate: Int) { decoded += samples.size }
                override fun decodeReady() {}
                override fun text(): String = ""
                override fun inputFinished() {}
                override fun release() {}
            }
        }
    }

    /** One measured utterance: samples decoded (tail excluded) and the length it cost. */
    private class Cost(val lengthMs: Int, decoded: Long, tail: Long) {
        val utteranceSamples = decoded - tail
        val utteranceSeconds = utteranceSamples.toDouble() / SR
        val ratio = utteranceSeconds / (lengthMs / 1000.0)
    }

    /** The #235 shape: one live stream, fed only the delta since the last tick. */
    private fun liveCost(lengthMs: Int, cadenceMs: Int): Cost {
        val meter = Meter()
        val u = LiveUtterance(StreamingTranscriber { meter.open() })
        val total = SR * lengthMs / 1000
        val tick = SR * cadenceMs / 1000
        var fed = 0
        var at = 0
        while (at + tick <= total) {
            at += tick
            u.feed(1, source(at - fed), SR)   // exactly what the capture loop hands over
            fed = at
        }
        u.finish(1, source(total - fed), SR)
        return Cost(lengthMs, meter.decoded, TAIL.toLong())
    }

    /** The pre-#235 shape: every tick re-transcribes the whole buffer so far. */
    private fun legacyCost(lengthMs: Int, cadenceMs: Int): Cost {
        val meter = Meter()
        val tr = StreamingTranscriber { meter.open() }
        val total = SR * lengthMs / 1000
        val tick = SR * cadenceMs / 1000
        var at = 0
        var ticks = 0L
        while (at + tick <= total) {
            at += tick
            tr.transcribeClip(source(at), SR)   // the old worker's snapshot decode
            ticks++
        }
        return Cost(lengthMs, meter.decoded, ticks * TAIL)
    }

    private fun report(name: String, costs: List<Cost>): String = buildString {
        append("$name ")
        append(costs.joinToString(" | ") {
            "${it.lengthMs / 1000}s → ${"%.1f".format(it.utteranceSeconds)}s decoded (x${"%.2f".format(it.ratio)})"
        })
    }

    @Test fun a_two_minute_turn_decodes_two_minutes_of_audio_once() {
        val c = liveCost(120_000, ContinuousCapture.LEGACY_PARTIAL_MS.toInt())
        assertEquals(120_000L * SR / 1000, c.utteranceSamples)
        assertEquals(1.0, c.ratio, 0.01)
    }

    @Test fun live_decodes_each_utterance_second_once_across_four_lengths() {
        val costs = LENGTHS_MS.map { liveCost(it, ContinuousCapture.LEGACY_PARTIAL_MS.toInt()) }
        costs.forEach {
            assertEquals("${it.lengthMs} ms utterance decoded at the wrong rate", 1.0, it.ratio, 0.01)
        }
        // Linear: the per-second cost does not move as the utterance grows.
        val ratios = costs.map { it.ratio }
        val spread = ratios.maxOrNull()!! - ratios.minOrNull()!!
        assertTrue("per-second cost moved by $spread across ${costs.size} lengths: $ratios", spread <= 0.01)
        println(report("live@900ms", costs))
    }

    @Test fun continuous_capture_keeps_the_per_second_cost_at_one() {
        val costs = LENGTHS_MS.map { liveCost(it, ContinuousCapture.PARTIAL_MS.toInt()) }
        costs.forEach {
            assertEquals("${it.lengthMs} ms utterance decoded at the wrong rate", 1.0, it.ratio, 0.01)
        }
        val spread = costs.maxOf { it.ratio } - costs.minOf { it.ratio }
        assertTrue("per-second cost moved by $spread at the 100 ms cadence", spread <= 0.01)
        println(report("live@100ms", costs))
    }

    @Test fun the_old_snapshot_worker_fails_the_same_check_and_grows_with_the_square() {
        val costs = LENGTHS_MS.map { legacyCost(it, ContinuousCapture.LEGACY_PARTIAL_MS.toInt()) }
        // The rule the live path must pass (ratio ≈ 1) is exactly what the old path violates.
        costs.forEach {
            assertTrue(
                "${it.lengthMs} ms: the old worker decoded x${"%.2f".format(it.ratio)} its own length",
                it.ratio > 1.01,
            )
        }
        // Quadratic: each doubling of the utterance roughly doubles the total cost ratio.
        costs.zipWithNext { a, b ->
            val factor = b.ratio / a.ratio
            assertTrue(
                "doubling ${a.lengthMs / 1000}s → ${b.lengthMs / 1000}s should roughly double cost, was x${"%.2f".format(factor)}",
                factor in 1.8..2.2,
            )
        }
        println(report("legacy@900ms", costs))
    }
}
