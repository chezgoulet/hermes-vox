package com.hermesvox

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * L5 (0.9.1 review): Presence glue is generated from the caller's words, so its text in
 * the `event=er-glue` line obeys `log_transcripts` like every other transcript-bearing
 * line (`<hidden>` when off). Pinned at source level: `speakGlue` is Android-bound.
 */
class GlueLogPrivacyTest {

    private val vc = File("src/main/java/com/hermesvox/VoiceController.kt").readText()

    @Test
    fun `the glue text is logged only with transcript logging on`() {
        val start = vc.indexOf("VoxLog.er(\"event=er-glue source=")
        assertTrue(start > 0)
        val line = vc.substring(start, vc.indexOf(")\n", vc.indexOf("text=", start)) + 1)
        assertTrue(line, line.contains("if (logTranscripts()) text.take(120) else \"<hidden>\""))
    }
}
