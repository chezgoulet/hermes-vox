package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Field-2, item 2: mood markers were read aloud. The soul led narration with `<<CALM>>` and a
 * hybrid `<<ESCALM>>`; the only sanitiser knew `{calm}`. These pin the boundary sanitiser
 * ([VoiceMood.clean]), the wider [VoiceMood.split], and that the glue funnel uses it.
 */
class MoodMarkerTest {

    @Test
    fun every_known_bracket_form_is_stripped_and_its_mood_kept() {
        for (raw in listOf("{calm} Checking the file.", "<<CALM>> Checking the file.",
            "<</CALM>> Checking the file.", "[CALM] Checking the file.", "  << calm >>Checking the file.")) {
            assertEquals(raw, VoiceMood.CALM to "Checking the file.", VoiceMood.clean(raw))
            assertEquals(raw, VoiceMood.CALM to "Checking the file.", VoiceMood.split(raw))
        }
    }

    @Test
    fun an_invented_token_is_discarded_and_the_words_kept() {
        // The field shape: escalate and calm fused into one token.
        assertEquals(null to "Analyzing the timing.", VoiceMood.clean("<<ESCALM>> Analyzing the timing."))
        assertEquals(null to "Hi.", VoiceMood.clean("<</SLEEPY>> Hi."))
        // An unknown brace or square word is not a mood marker: the D20 markup bound owns it.
        assertEquals(null to "[thinking] Hi.", VoiceMood.clean("[thinking] Hi."))
        assertEquals("digits-or-markup", ErSoulTurn.workingReject("[thinking] just a moment"))
    }

    @Test
    fun a_marker_mid_sentence_is_content() {
        assertEquals(null to "I said <<CALM>> earlier", VoiceMood.clean("I said <<CALM>> earlier"))
        assertEquals(null to "keep [calm] and carry on", VoiceMood.clean("keep [calm] and carry on"))
    }

    @Test
    fun a_marker_leading_each_line_goes_and_a_marker_only_line_is_dropped() {
        val (mood, out) = VoiceMood.clean("<<CALM>> Timing is the issue.\n<<ESCALM>>\n{warm} Checking now.")
        assertEquals(VoiceMood.CALM, mood)
        assertEquals("Timing is the issue.\nChecking now.", out)
        assertEquals(VoiceMood.CALM to "", VoiceMood.clean("<<CALM>>"))
    }

    @Test
    fun split_leaves_the_escalate_token_and_unknown_braces_alone() {
        assertEquals(null to "<<ESCALATE>> hmm", VoiceMood.split("<<ESCALATE>> hmm"))
        assertEquals(null to "{sleepy} hi", VoiceMood.split("{sleepy} hi"))
        val (m, rest) = VoiceMood.split("<<TENSE>> <<ESCALATE>> Okay, let me look")
        assertEquals(VoiceMood.TENSE, m)
        assertTrue(ErSoulTurn.parse(rest) is ErSoulTurn.Outcome.Beat)
    }

    @Test
    fun a_working_line_led_by_a_marker_is_repaired_not_refused() {
        // Field shape: `why=digits-or-markup text=<<CALM>> Timing is the issue.`
        assertEquals("Timing is the issue.", ErSoulTurn.workingLine("<<CALM>> Timing is the issue."))
        assertEquals("Timing is the issue.", ErSoulTurn.workingLine("<<ESCALM>>\nTiming is the issue."))
    }

    @Test
    fun the_glue_funnel_cleans_before_the_log_the_tts_and_the_transcript() {
        val src = listOf(File("src/main/java/com/hermesvox/VoiceController.kt"),
            File("app/src/main/java/com/hermesvox/VoiceController.kt")).first { it.isFile }.readText()
        val body = src.substringAfter("fun speakGlue(raw: String").substringBefore("event=er-glue source=")
        assertTrue(body.contains("val text = VoiceMood.clean(raw).second"))
        // Nothing after the clean speaks or records `raw`.
        val after = src.substringAfter("fun speakGlue(raw: String").substringBefore("\n    fun ")
            .substringAfter("val text = VoiceMood.clean(raw).second")
        assertFalse(Regex("\\braw\\b(?!\\.trim\\(\\)\\))").containsMatchIn(after))
    }

    @Test
    fun the_heard_directive_asks_for_exactly_the_brace_form_the_sanitiser_accepts() {
        val d = ErSoulTurn.directive("hey", heard = true)
        for (mood in VoiceMood.values()) {
            val tag = "{${mood.name.lowercase()}}"
            assertTrue(d.contains(tag))
            assertEquals(mood to "x", VoiceMood.split("$tag x"))
        }
    }
}
