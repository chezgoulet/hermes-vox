package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #477 — the spoken-language row, and the multilingual Whisper models that earn it.
 *
 * On Pierre's branch the row was offered for every recogniser and then ignored by the
 * English-only ones: [ModelCatalog.whisperLanguage] answered "en" whatever the pref said.
 * Here the row is a consequence of the selected recogniser — hidden unless that
 * recogniser can decode a language — and the one way to reach the mismatch (switching to
 * an English-only model with a language stored) is answered with the multilingual model
 * and its real download size, not with silence.
 */
class SpokenLanguageTest {

    private fun spec(id: String) = ModelCatalog.blessed.first { it.id == id }

    @Test fun the_three_multilingual_models_are_registered_and_told_apart_from_the_english_ones() {
        for (id in listOf("whisper-tiny-multi", "whisper-base-multi", "whisper-small-multi")) {
            val s = spec(id)
            assertEquals("stt", s.kind)
            assertEquals(64, s.sha256.length)
            assertFalse("$id is an opt-in download, not the required default", s.recommended)
            assertTrue("$id is in the Settings picker", ModelCatalog.sttModels.any { it.first == id })
            assertTrue(ModelCatalog.isMultilingualStt(id))
        }
        for (id in listOf("whisper-tiny", "whisper-base", "whisper-small", "parakeet-v2",
            ModelCatalog.NEMOTRON_STREAM_ID)) {
            assertFalse(id, ModelCatalog.isMultilingualStt(id))
        }
        // Nothing changes for a phone that never asks for another language.
        assertEquals("whisper-base", ModelCatalog.DEFAULT_STT_MODEL)
    }

    @Test fun the_multilingual_artifacts_are_pinned_like_every_other_model() {
        val pins = mapOf(
            "whisper-tiny-multi" to "c46116994e539aa165266d96b325252728429c12535eb9d8b6a2b10f129e66b1",
            "whisper-base-multi" to "911b2083efd7c0dca2ac3b358b75222660dc09fb716d64fbfc417ba6c99ff3de",
            "whisper-small-multi" to "486a46afbb7ba798507190ffe02fea2dd726049af212e774537efac6afb210a6",
        )
        for ((id, pin) in pins) {
            val s = spec(id)
            assertEquals(id, pin, s.sha256)
            assertTrue("$id is the multilingual (no .en) export", !s.file.contains(".en"))
            val url = s.url.ifBlank { ModelCatalog.DEFAULT_SOURCE + "/" + s.file }
            assertTrue("$id must come from an https release ($url)", url.startsWith("https://"))
        }
    }

    @Test fun the_language_row_is_hidden_for_an_english_only_recogniser_and_shown_for_a_multilingual_one() {
        val onDevice = ModelCatalog.BACKEND_ONDEVICE
        assertFalse("whisper-base is English only",
            SttLanguage.rowVisible(onDevice, "whisper-base", ""))
        assertFalse("parakeet is English only",
            SttLanguage.rowVisible(onDevice, "parakeet-v2", ""))
        assertFalse("the streaming arm replaces the shipped model and is English only",
            SttLanguage.rowVisible(onDevice, "whisper-base-multi", ModelCatalog.NEMOTRON_STREAM_ID))
        assertTrue("whisper-base-multi decodes a language",
            SttLanguage.rowVisible(onDevice, "whisper-base-multi", ""))
        assertTrue(SttLanguage.rowVisible(onDevice, "whisper-tiny-multi", ""))
        // The row is the on-device model's: the platform and remote recognisers have
        // their own language handling and never read this pref.
        assertFalse(SttLanguage.rowVisible(ModelCatalog.BACKEND_PLATFORM, "whisper-base-multi", ""))
        assertFalse(SttLanguage.rowVisible(ModelCatalog.BACKEND_REMOTE, "whisper-base-multi", ""))

        // The row exists in the layout, starts hidden, and is refreshed by the screen.
        val layout = File("src/main/res/layout/activity_settings.xml").readText()
        val row = Regex("""<LinearLayout\s+android:id="@\+id/row_stt_language"(.*?)</LinearLayout>""",
            RegexOption.DOT_MATCHES_ALL).find(layout)?.groupValues?.get(1)
            ?: error("row_stt_language is missing from the layout")
        assertTrue("the row must start hidden", row.contains("""android:visibility="gone""""))
        val settings = File("src/main/java/com/hermesvox/SettingsActivity.kt").readText()
        assertTrue(settings.contains("refreshSttLanguageRow()"))
        assertTrue("the row is shown only on SttLanguage's own verdict",
            settings.contains("SttLanguage.rowVisible("))
    }

    @Test fun the_chosen_language_reaches_the_recogniser() {
        // Every offered code survives to the decoder for a multilingual model…
        for (choice in SttLanguage.CHOICES) {
            for (id in listOf("whisper-tiny-multi", "whisper-base-multi", "whisper-small-multi")) {
                assertEquals("$id / '${choice.code}'", choice.code,
                    ModelCatalog.whisperLanguage(id, " ${choice.code.uppercase()} "))
            }
        }
        // …and an English-only model still decodes English, whatever the pref says.
        assertEquals("en", ModelCatalog.whisperLanguage("whisper-base", "fr"))
        // The wiring that hands it over: OfflineWhisperStt reads the pref and passes the
        // result to the sherpa Whisper config, so the code above is what the model gets.
        val stt = File("src/main/java/com/hermesvox/OfflineStt.kt").readText()
        assertTrue(stt.contains("ModelCatalog.KEY_STT_LANGUAGE"))
        assertTrue(stt.contains("ModelCatalog.whisperLanguage(modelId,"))
        assertTrue(stt.contains("OfflineWhisperModelConfig(e.absolutePath, d.absolutePath, language,"))
    }

    @Test fun an_english_only_model_with_a_stored_language_is_never_silently_ignored() {
        // The mismatch is exactly "English-only recogniser + a language it cannot decode".
        assertTrue(SttLanguage.englishOnlyMismatch("whisper-base", "", "fr"))
        assertTrue(SttLanguage.englishOnlyMismatch("parakeet-v2", "", "fr"))
        assertTrue(SttLanguage.englishOnlyMismatch("whisper-base-multi", ModelCatalog.NEMOTRON_STREAM_ID, "fr"))
        // Not a mismatch: nothing stored, English stored, or a model that can honour it.
        assertFalse(SttLanguage.englishOnlyMismatch("whisper-base", "", ""))
        assertFalse(SttLanguage.englishOnlyMismatch("whisper-base", "", "en"))
        assertFalse(SttLanguage.englishOnlyMismatch("whisper-base-multi", "", "fr"))

        // The offer names a real catalogue row, so its REAL size can be shown before
        // anything downloads.
        for (model in listOf("whisper-tiny", "whisper-base", "whisper-small", "parakeet-v2",
            ModelCatalog.NEMOTRON_STREAM_ID)) {
            val sibling = SttLanguage.multilingualSibling(model)
            val s = spec(sibling)
            assertTrue("$sibling must be multilingual", ModelCatalog.isMultilingualStt(sibling))
            assertTrue("$sibling must have a size to show", s.sizeMB > 0.0)
            assertEquals("$model keeps its own tier where one exists",
                when (model) {
                    "whisper-tiny" -> "whisper-tiny-multi"
                    "whisper-base" -> "whisper-base-multi"
                    "whisper-small" -> "whisper-small-multi"
                    else -> "whisper-base-multi"   // a transducer has no multilingual twin
                }, sibling)
        }
    }
}
