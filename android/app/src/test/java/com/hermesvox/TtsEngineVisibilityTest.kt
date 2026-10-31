package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #346 — the engine-visibility description that separates "no engine installed" from
 * "the engine is installed but this app cannot see it".
 *
 * On Android 11+ a missing `<queries>` entry for `android.intent.action.TTS_SERVICE`
 * hides the engine package, and `TextToSpeech` init returns ERROR (-1). The log must be
 * able to say which case happened, because they have different fixes.
 */
class TtsEngineVisibilityTest {

    @Test
    fun `an unreadable engine list is named as unavailable, not as empty`() {
        assertEquals("unavailable", describeEngines(null))
    }

    @Test
    fun `no visible engines is the package-visibility signature`() {
        assertEquals("none-visible", describeEngines(emptyList()))
    }

    @Test
    fun `visible engines are listed by package name`() {
        assertEquals(
            "com.google.android.tts,com.github.sherpatts",
            describeEngines(listOf("com.google.android.tts", "com.github.sherpatts")),
        )
    }
}
