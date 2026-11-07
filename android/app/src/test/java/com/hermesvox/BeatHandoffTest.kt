package com.hermesvox

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Naturalness (2026-11-05): the beat races the mind. When the answer's first audio is ready, a
 * beat the caller is already hearing may finish (bounded by HANDOFF_WAIT_MS); a beat that is
 * still synthesizing — nothing of it audible — is abandoned, so the reply never waits on a line
 * nobody has heard yet.
 */
class BeatHandoffTest {
    private val vc = File("src/main/java/com/hermesvox/VoiceController.kt").readText()

    @Test fun the_reply_waits_only_for_a_beat_that_is_already_audible() {
        assertTrue(vc.contains("val heard = glueAudible"))
        assertTrue(vc.contains("while (glueSpeaking && heard && !glueCutAtHandoff"))
    }

    @Test fun audible_means_the_tts_start_callback_of_this_line() {
        assertTrue(vc.contains("if (speakSeq.get() == seq) glueAudible = true"))
        assertTrue("every new glue line starts unheard", vc.contains("glueSpeaking = true\n        glueAudible = false"))
    }
}
