package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #223 — the transcript layout was empty in Presence: `appendConvo()` was fed only by
 * the plain path, so the caller who chose the readable transcript (the accessibility
 * reason the option exists) got a blank pane.
 *
 * These drive the same [Transcript] the activity paints — no device, no UI. They prove
 * the Presence path's turn text and asides land in the order they arrive, the reply is
 * in the transcript while it is still streaming, and the plain path's output is
 * byte-for-byte what it was before this class existed.
 */
class TranscriptTest {

    // ---- the Presence path -------------------------------------------------

    @Test fun two_consecutive_turns_land_in_order() {
        val t = Transcript()
        t.heard("what is the weather")
        t.delta("Tomorrow ")                       // the reply arrives as it streams…
        t.delta("is sunny.")
        t.replyFinal("Tomorrow is sunny.")         // …and the turn then reconciles
        t.heard("and the trail")
        t.delta("Dry.")
        t.replyFinal("Dry.")
        assertEquals(
            "You: what is the weather\n" +
                "Agent: Tomorrow is sunny.\n" +
                "You: and the trail\n" +
                "Agent: Dry.",
            t.text
        )
    }

    @Test fun the_reply_is_in_the_transcript_before_the_turn_completes() {
        val t = Transcript()
        t.heard("tell me about the trail")
        t.delta("The trail is")
        // The turn has not settled yet: this is the state the reader is in while the
        // entity is still speaking. The old path showed nothing here.
        assertEquals("You: tell me about the trail\nAgent: The trail is", t.text)
        t.delta(" dry.")
        t.replyFinal("The trail is dry.")
        assertEquals("You: tell me about the trail\nAgent: The trail is dry.", t.text)
    }

    @Test fun an_aside_lands_between_two_turns_in_the_right_position() {
        val t = Transcript()
        t.heard("compile the release")
        t.presence("One moment.", "soul-beat")     // the soul opens the mind's run
        t.delta("Built.")
        t.replyFinal("Built.")
        t.heard("thanks")
        t.presence("Any time.", "soul-answer")     // the soul answered this turn itself
        assertEquals(
            "You: compile the release\n" +
                "· One moment.\n" +
                "Agent: Built.\n" +
                "You: thanks\n" +
                "Agent: Any time.",
            t.text
        )
    }

    @Test fun a_working_line_is_an_aside_not_a_reply() {
        // The source strings are the ones VoiceController actually speaks with: the
        // working line is Presence's processing noise, not the answer to the turn.
        val t = Transcript()
        t.heard("search the web")
        t.presence("Still looking.", "soul-working")
        t.presence("hmm", "presence-filler")
        assertEquals("You: search the web\n· Still looking.\n· hmm", t.text)
    }

    @Test fun the_greeting_is_an_aside_before_the_first_turn() {
        val t = Transcript()
        t.presence("Evening.", "soul-greeting")
        t.heard("hello")
        assertEquals("· Evening.\nYou: hello", t.text)
    }

    @Test fun a_soul_answer_is_the_turn_reply_whatever_the_mind_says_next() {
        val t = Transcript()
        t.heard("how are you")
        t.presence("Steady.", Transcript.SOURCE_SOUL_ANSWER)
        assertTrue(t.text.endsWith("Agent: Steady."))
    }

    @Test fun the_final_text_replaces_the_streamed_block_without_duplicating_it() {
        val t = Transcript()
        t.heard("hi")
        t.delta("Hello ther")                       // a delta the guard then repairs
        t.replyFinal("Hello.")
        assertEquals("You: hi\nAgent: Hello.", t.text)
    }

    @Test fun deltas_join_with_their_spaces_intact() {
        val t = Transcript()
        t.heard("hi")
        listOf("Word ", "by ", "word.").forEach { t.delta(it) }
        t.replyFinal("Word by word.")
        assertEquals("You: hi\nAgent: Word by word.", t.text)
    }

    @Test fun an_aside_closes_the_open_reply_block() {
        // A critical aside can preempt mid-reply; the final reply must be a new line,
        // not a replacement that silently erases the aside.
        val t = Transcript()
        t.heard("hi")
        t.delta("Half a sen")
        t.presence("Careful — that is your last one.", "presence-hold")
        t.replyFinal("Half a sentence.")
        assertEquals(
            "You: hi\nAgent: Half a sen\n· Careful — that is your last one.\nAgent: Half a sentence.",
            t.text
        )
    }

    @Test fun a_head_clamp_keeps_the_open_reply_replaceable() {
        val t = Transcript(maxChars = 40)
        t.heard("a very long question that overflows the tiny buffer")
        t.delta("an answer that is also too long for such a small buffer")
        t.replyFinal("an answer that is also too long for such a small buffer")
        assertEquals(40, t.text.length)
        assertTrue(t.text.endsWith("such a small buffer"))   // the head goes, exactly as takeLast did
    }

    // ---- the plain path (regression) ---------------------------------------

    @Test fun plain_path_is_unchanged() {
        val t = Transcript()
        t.line("You: hello")
        t.line("Agent: hi")
        assertEquals("You: hello\nAgent: hi", t.text)
    }

    @Test fun a_turn_without_deltas_lands_exactly_like_the_plain_path() {
        val streamed = Transcript().apply { heard("hi"); replyFinal("Hello.") }
        val plain = Transcript().apply { line("You: hi"); line("Agent: Hello.") }
        assertEquals(plain.text, streamed.text)
    }

    @Test fun plain_path_join_trim_and_cap_are_the_old_expression() {
        // The independent oracle is the expression this class replaced in MainActivity:
        //   convoBuf = (convoBuf + "\n" + line).trim().takeLast(4000)
        var old = ""
        val lines = (1..100).map { "You: turn $it ${"x".repeat(40)}" } + listOf("", "  Agent: padded  ")
        val t = Transcript()
        for (line in lines) {
            old = (old + "\n" + line).trim().takeLast(4000)
            t.line(line)
            assertEquals("after ${line.take(20)}", old, t.text)
        }
        assertEquals(4000, t.text.length)
    }

    @Test fun clear_empties_the_transcript() {
        val t = Transcript()
        t.heard("hi"); t.delta("Hello.")
        t.clear()
        assertEquals("", t.text)
        t.heard("again")
        assertEquals("You: again", t.text)
    }
}
