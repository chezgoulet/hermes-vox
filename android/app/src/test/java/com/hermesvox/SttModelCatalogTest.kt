package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fix 3 + 6 — the STT model catalog: language routing and the Parakeet entry. */
class SttModelCatalogTest {

    @Test fun english_only_whisper_always_decodes_english() {
        assertEquals("en", ModelCatalog.whisperLanguage("whisper-base", "de"))
        assertEquals("en", ModelCatalog.whisperLanguage("whisper-tiny", ""))
        assertEquals("en", ModelCatalog.whisperLanguage("whisper-small", null))
    }

    @Test fun a_multilingual_model_takes_the_pref_or_auto_detects() {
        assertEquals("de", ModelCatalog.whisperLanguage("whisper-base-multi", " DE "))
        assertEquals("", ModelCatalog.whisperLanguage("whisper-base-multi", null))   // "" = auto-detect
    }

    @Test fun parakeet_is_a_pinned_selectable_transducer_model_and_whisper_stays_default() {
        val spec = ModelCatalog.blessed.first { it.id == "parakeet-v2" }
        assertEquals("stt", spec.kind)
        assertEquals(64, spec.sha256.length)
        assertFalse(spec.recommended)                         // opt-in, not a required download
        assertTrue(ModelCatalog.sttModels.any { it.first == "parakeet-v2" })
        assertTrue(ModelCatalog.isTransducerStt("parakeet-v2"))
        assertFalse(ModelCatalog.isTransducerStt("whisper-base"))
        assertEquals("whisper-base", ModelCatalog.DEFAULT_STT_MODEL)
    }

    @Test fun whisper_tail_padding_is_explicit_and_leaves_room_in_the_window() {
        // 1000 frames x 10 ms = 10 s of padding; the 25 s window keeps >= 5 s of it
        // inside Whisper's 30 s input (the rest is clamped by sherpa-onnx).
        assertEquals(1000, OfflineWhisperStt.TAIL_PADDING_FRAMES)
        assertTrue(SttWindows.WHISPER_WINDOW_MS <= 25_000)
    }
}
