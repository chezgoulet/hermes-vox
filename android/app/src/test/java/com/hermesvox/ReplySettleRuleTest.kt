package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves the 0.4.0.4 settle rule: a completed reply may NEVER (re)speak audio the
 * user already heard or already cancelled, while an ordinary text-only turn still
 * speaks and a streamed turn still retires through its worker (#71).
 *
 * The field failure this table encodes (docs/evidence-0404-failure.log): hush at
 * 22:17:31.460 closed the stream (clearing `streamed`) and released gen=1's gate;
 * done=true for the same gen landed at 22:17:31.462 and the old branch re-spoke all
 * 199 chars as one uncancellable utterance. Pure JVM — no Android deps.
 */
class ReplySettleRuleTest {

    private fun decide(
        streamed: Boolean = false,
        streamingEngine: Boolean = true,
        genSpokeAudio: Boolean = false,
        gateReleased: Boolean = false,
        speakAllowed: Boolean = true,
        hasText: Boolean = true,
    ) = ReplySettleRule.decide(streamed, streamingEngine, genSpokeAudio, gateReleased, speakAllowed, hasText)

    @Test fun hush_then_done_must_not_respeak() {
        // THE regression under test: streamed already cleared by the hush's
        // stopStreaming(), gate already released, 5 chunks already audible.
        assertEquals(ReplySettle.DROP,
            decide(streamed = false, genSpokeAudio = true, gateReleased = true))
    }

    @Test fun hush_before_any_audio_still_must_not_speak() {
        // A hush that lands mid-FIRST-chunk leaves firstAudioPushed false (streamChunk
        // returns true only after a completed write), so the gate is the only witness.
        assertEquals(ReplySettle.DROP,
            decide(streamed = false, genSpokeAudio = false, gateReleased = true))
    }

    @Test fun already_spoken_never_respeaks_even_with_a_live_gate() {
        // Belt and braces for a cancel path that closed the stream without releasing:
        // audible audio for this gen is on its own sufficient to refuse speech.
        assertEquals(ReplySettle.DROP,
            decide(streamed = false, genSpokeAudio = true, gateReleased = false))
    }

    @Test fun clean_text_only_turn_must_speak() {
        // Never streamed, never audible, gate live, voice channel open -> speak normally.
        assertEquals(ReplySettle.SPEAK, decide())
        // Same for a non-streaming engine (SystemTts): the full-text fallback still runs.
        assertEquals(ReplySettle.SPEAK, decide(streamingEngine = false))
    }

    @Test fun done_while_streaming_must_retire() {
        // #71 single-owner retirement: an OPEN streamed turn marks the queue final and
        // lets the worker close. genSpokeAudio is TRUE for every healthy streamed reply,
        // so retirement must be decided BEFORE the "already spoken" refusal.
        assertEquals(ReplySettle.RETIRE_STREAM, decide(streamed = true, genSpokeAudio = true))
        assertEquals(ReplySettle.RETIRE_STREAM, decide(streamed = true, genSpokeAudio = false))
        // ...and even if speech was toggled off mid-reply, the open stream still retires
        // (the worker owns the close; hijacking it here is what wedged the gate).
        assertEquals(ReplySettle.RETIRE_STREAM, decide(streamed = true, speakAllowed = false))
    }

    @Test fun a_streamed_flag_without_a_streaming_engine_is_not_a_stream() {
        // streamBegin() only arms for a streaming engine; if the engine changed under
        // us this is an ordinary text-only settle, exactly as before 0.4.0.4.
        assertEquals(ReplySettle.SPEAK, decide(streamed = true, streamingEngine = false))
    }

    @Test fun speech_off_settles_quietly_and_still_releases() {
        // Voice channel closed / speak toggle off, live turn: no speech, but the gate
        // must still be released by the settle (SILENT_SETTLE), not dropped.
        assertEquals(ReplySettle.SILENT_SETTLE, decide(speakAllowed = false))
        assertEquals(ReplySettle.SILENT_SETTLE, decide(hasText = false))
    }

    @Test fun cancelled_beats_speech_off() {
        // A cancelled turn must not re-release the gate the cancel path already owns.
        assertEquals(ReplySettle.DROP, decide(speakAllowed = false, gateReleased = true))
    }

    @Test fun full_table_is_exhaustive_and_stable() {
        // Every combination lands on exactly one outcome, and only the two documented
        // witnesses can turn a would-be SPEAK into a DROP.
        var speak = 0; var drop = 0; var retire = 0; var silent = 0
        for (streamed in listOf(false, true))
            for (engine in listOf(false, true))
                for (audio in listOf(false, true))
                    for (released in listOf(false, true))
                        for (allowed in listOf(false, true))
                            for (text in listOf(false, true))
                                when (decide(streamed, engine, audio, released, allowed, text)) {
                                    ReplySettle.SPEAK -> speak++
                                    ReplySettle.DROP -> drop++
                                    ReplySettle.RETIRE_STREAM -> retire++
                                    ReplySettle.SILENT_SETTLE -> silent++
                                }
        assertEquals(64, speak + drop + retire + silent)
        assertEquals(16, retire)   // streamed && engine: all 16 such combinations
        assertEquals(36, drop)     // of the other 48: released || audio (3 of 4)
        assertEquals(9, silent)    // of the 12 live+unspoken: !allowed || !text
        assertEquals(3, speak)     // live, unspoken, allowed, with text
    }
}
