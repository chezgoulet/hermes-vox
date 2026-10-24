package com.hermesvox

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves LatencyStats' per-turn outcome summary (emitted on every gate release)
 * and the rolling P50/P95 window (every 8th turn). Pure JVM — exercises
 * summaryLines()/windowCounts() directly, never touches VoxLog. C4: firstText
 * and firstAudio are BOTH recorded per turn — firstText at the first text delta,
 * firstAudio at the first real audible streamChunk write.
 */
class LatencyStatsTest {

    @Test fun per_turn_summary_every_call() {
        LatencyStats.reset()
        LatencyStats.pushStt(40)
        LatencyStats.pushFirstByte(120)
        LatencyStats.pushFirstText(150)
        LatencyStats.pushFirstAudio(180)
        LatencyStats.pushFullReply(900)
        val first = LatencyStats.summaryLines("turn", "stream-done", 1L)
        assertEquals(1, first.size)
        assertTrue(first[0], first[0].contains("event=turn label=turn gen=1 outcome=stream-done"))
        assertTrue(first[0], first[0].contains("stt=40 firstByte=120 firstText=150 firstAudio=180 fullReply=900"))
        // Phase 1: the speech-end anchored fields exist on the SAME line and are "-" when no
        // anchor was handed over (a typed turn), never 0 and never a stale one.
        assertTrue(first[0], first[0].contains("speechEndToFirstText=- speechEndToFirstAudio=- speechEndToRenderDone=- sttDecodeMs=-"))
        // per-turn values clear after every emit
        val second = LatencyStats.summaryLines("turn", "stream-done", 2L)
        assertEquals(1, second.size)
        assertTrue(second[0], second[0].contains("stt=- firstByte=- firstText=- firstAudio=- fullReply=-"))
        assertTrue(second[0], second[0].contains("speechEndToFirstText=- speechEndToFirstAudio=- speechEndToRenderDone=- sttDecodeMs=-"))
    }

    @Test fun rolling_p50_every_8th_then_clear() {
        LatencyStats.reset()
        fun seed() {
            LatencyStats.pushStt(40)
            LatencyStats.pushFirstByte(120)
            LatencyStats.pushFirstText(150)
            LatencyStats.pushFirstAudio(180)
            LatencyStats.pushFullReply(900)
        }
        for (i in 0L until 7L) { seed(); assertEquals(1, LatencyStats.summaryLines("turn", "stream-done", i).size) }
        seed()
        val eighth = LatencyStats.summaryLines("turn", "stream-done", 7L)
        assertEquals(10, eighth.size)                                 // 1 turn + 9 event=lat lines (5 legacy + 4 speech-end)
        assertTrue(eighth[0], eighth[0].startsWith("event=turn"))
        assertEquals(9, eighth.drop(1).count { it.startsWith("event=lat") })
        val text = eighth.joinToString("\n")
        assertTrue(text, text.contains("metric=first-text"))
        // the conversational metrics have their own rolling rows, so a memory/cadence run
        // reads p50/p95 from the same 8-turn window as the legacy ones
        for (m in listOf("speech-to-text", "speech-to-audio", "speech-to-render", "stt-decode"))
            assertTrue(text, text.contains("metric=$m"))
        // rolling window cleared after the 8th emit
        val cleared = LatencyStats.windowCounts()
        assertArrayEquals(IntArray(9) { 0 }, cleared)
        // a fresh push grows from 0
        LatencyStats.pushStt(50)
        val grown = LatencyStats.windowCounts()
        assertArrayEquals(intArrayOf(0, 0, 0, 0, 1, 0, 0, 0, 0), grown)
    }

    @Test fun reset_zeroes_window() {
        LatencyStats.reset()
        LatencyStats.pushStt(40)
        LatencyStats.pushFirstByte(120)
        LatencyStats.pushFirstText(150)
        LatencyStats.pushFirstAudio(180)
        LatencyStats.pushFullReply(900)
        assertArrayEquals(intArrayOf(1, 1, 1, 1, 1, 0, 0, 0, 0), LatencyStats.windowCounts())
        LatencyStats.reset()
        assertArrayEquals(IntArray(9) { 0 }, LatencyStats.windowCounts())
    }

    @Test fun session_turns_counts_since_reset() {
        LatencyStats.reset()
        // C3: event=turn carries a per-session cumulative count, reset only where the
        // host calls resetConversation (resetSessionTurns beside those call sites).
        val one = LatencyStats.summaryLines("turn", "stream-done", 1L)
        assertTrue(one[0], one[0].contains("session_turns=1"))
        val two = LatencyStats.summaryLines("turn", "stream-done", 2L)
        assertTrue(two[0], two[0].contains("session_turns=2"))
        val three = LatencyStats.summaryLines("turn", "stream-done", 3L)
        assertTrue(three[0], three[0].contains("session_turns=3"))
        LatencyStats.resetSessionTurns()   // what MainActivity does beside resetConversation
        val again = LatencyStats.summaryLines("turn", "stream-done", 4L)
        assertTrue(again[0], again[0].contains("session_turns=1"))
    }

    @Test fun speech_end_fields_ride_the_same_turn_line_and_clear() {
        LatencyStats.reset()
        // Phase 1: what a voice turn now reports — all four from the end of speech, plus the
        // recogniser's own time. 700/1400 ms is D18; these are the fields that can prove it.
        LatencyStats.pushSttDecode(420)
        LatencyStats.pushFirstText(2400)
        LatencyStats.pushSpeechToText(700)
        LatencyStats.pushFirstAudio(2100)
        LatencyStats.pushSpeechToAudio(1400)
        LatencyStats.pushSpeechToRender(4200)
        val line = LatencyStats.summaryLines("turn", "stream-done", 9L).first()
        assertTrue(line, line.contains("speechEndToFirstText=700"))
        assertTrue(line, line.contains("speechEndToFirstAudio=1400"))
        assertTrue(line, line.contains("speechEndToRenderDone=4200"))
        assertTrue(line, line.contains("sttDecodeMs=420"))
        // the legacy field is unchanged and still present (continuity with recorded baselines)
        assertTrue(line, line.contains("firstText=2400"))
        val next = LatencyStats.summaryLines("turn", "stream-done", 10L).first()
        assertTrue(next, next.contains("speechEndToFirstText=-"))
        assertTrue(next, next.contains("sttDecodeMs=-"))
    }
}
