package com.hermesvox

import com.hermesvox.TranscriptValidator.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fix 2 — Whisper hallucination defenses. The failure strings below are the ones
 * the sttbench corpus actually produced on whisper-base.en (tools/sttbench).
 */
class TranscriptValidatorTest {

    private fun accept(v: Verdict): String = (v as? Verdict.Accept)?.text
        ?: throw AssertionError("expected Accept, got $v")
    private fun reason(v: Verdict): String = (v as? Verdict.Reject)?.reason
        ?: throw AssertionError("expected Reject, got $v")

    // ---- real speech passes untouched ----

    @Test fun ordinary_speech_passes_verbatim() {
        assertEquals("What's the weather tomorrow?", accept(TranscriptValidator.validate("What's the weather tomorrow?", 1400, 2500)))
        assertEquals("no", accept(TranscriptValidator.validate("no", 250, 1300)))   // short answers are turns
    }

    @Test fun a_real_thank_you_is_heard() {
        // ~0.5 s of speech in the usual 1.6 s segment (pre-roll + 800 ms close).
        assertEquals("Thank you.", accept(TranscriptValidator.validate("Thank you.", 520, 1600)))
        assertEquals("Bye!", accept(TranscriptValidator.validate("Bye!", 400, 1400)))
    }

    // ---- bracketed tags (the 0.6.8 rule, kept) ----

    @Test fun noise_tags_alone_are_refused_and_stripped_when_appended() {
        assertEquals("noise-tag", reason(TranscriptValidator.validate("(buzzer)", 600, 3000)))
        assertEquals("noise-tag", reason(TranscriptValidator.validate("(clippers buzzing)", 600, 4000)))
        assertEquals("noise-tag", reason(TranscriptValidator.validate("[BLANK_AUDIO]", 600, 3000)))
        assertEquals("noise-tag", reason(TranscriptValidator.validate("♪ ♪", 600, 3000)))
        assertEquals("Turn it up", accept(TranscriptValidator.validate("Turn it up (buzzing)", 900, 2000)))
    }

    // ---- the classic non-speech phrases: only on low-speech clips ----

    @Test fun whisper_priors_on_near_silence_are_refused() {
        assertEquals("non-speech-phrase", reason(TranscriptValidator.validate("Thank you.", 250, 1400)))
        assertEquals("non-speech-phrase", reason(TranscriptValidator.validate("Thanks for watching!", 900, 4200)))  // ratio 0.21
        assertEquals("non-speech-phrase", reason(TranscriptValidator.validate("you", 300, 1300)))
    }

    // ---- speech evidence ----

    @Test fun too_little_speech_in_the_clip_is_not_a_turn() {
        assertEquals("low-speech-ratio", reason(TranscriptValidator.validate("Okay then.", 150, 3000)))
    }

    @Test fun implausible_words_per_second_is_invented_text() {
        // 14 words over 0.6 s of VAD speech = 23 w/s.
        val text = "and the other thing I wanted to say was that we should go there"
        assertEquals("implausible-rate", reason(TranscriptValidator.validate(text, 600, 2000)))
        // The same words over 3.5 s of speech are ordinary.
        assertEquals(text, accept(TranscriptValidator.validate(text, 3500, 4600)))
    }

    @Test fun unknown_speech_measure_applies_text_rules_only() {
        // The barge classifier has no VAD measure (-1): no ratio / rate / blocklist.
        assertEquals("Thank you.", accept(TranscriptValidator.validate("Thank you.", -1, -1)))
        assertEquals("noise-tag", reason(TranscriptValidator.validate("(static)", -1, -1)))
    }

    // ---- runaway repetition ----

    @Test fun a_decoder_loop_is_refused() {
        // sttbench short_04 at 300-frame tail padding: ten repeats of a bigram.
        val loop = List(10) { "this was" }.joinToString(" ").replaceFirstChar { it.uppercase() }
        assertEquals("runaway-repetition", reason(TranscriptValidator.validate(loop, 3000, 3400)))
        assertEquals("runaway-repetition",
            reason(TranscriptValidator.validate("hello hello hello hello hello hello hello hello", 2500, 3000)))
    }

    @Test fun a_short_repeat_run_inside_real_speech_is_collapsed() {
        val v = TranscriptValidator.validate(
            "Set a timer for ten minutes minutes minutes minutes minutes please", 3200, 4200)
        assertTrue(v is Verdict.Accept && v.collapsed)
        assertEquals("Set a timer for ten minutes please", accept(v))
    }

    @Test fun natural_repetition_is_kept() {
        assertEquals("no no no", accept(TranscriptValidator.validate("no no no", 700, 1600)))
        assertEquals("very very good", accept(TranscriptValidator.validate("very very good", 800, 1700)))
    }

    @Test fun collapse_compares_normalized_ngrams() {
        assertEquals(listOf("Thank", "you,"),
            TranscriptValidator.collapseRepeats("Thank you, thank you. Thank you, thank you".split(' ')))
    }
}
