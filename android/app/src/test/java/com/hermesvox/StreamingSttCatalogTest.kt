package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #207 — the streaming STT catalogue entry and its licence gate. */
class StreamingSttCatalogTest {

    private val spec = ModelCatalog.blessed.first { it.id == ModelCatalog.NEMOTRON_STREAM_ID }

    @Test fun entry_is_a_pinned_opt_in_stt_model_with_its_true_size() {
        assertEquals("stt", spec.kind)
        assertTrue(spec.blessed)
        assertFalse(spec.recommended)   // never part of the required set
        assertEquals("0ae73a41cd51599dc7cac9ac083d9d35de53d762ca45923505fde47a3751814b", spec.sha256)
        assertEquals("sherpa-onnx-nemotron-speech-streaming-en-0.6b-160ms-int8-2026-04-25.tar.bz2", spec.file)
        // 463,945,198 bytes = 463.9 decimal MB (the artifact's Content-Length).
        assertEquals(463_945_198 / 1e6, spec.sizeMB, 0.05)
        assertTrue(spec.purpose.isNotBlank())
        assertTrue(spec.url.isBlank())   // <DEFAULT_SOURCE>/<file>: the asr-models release
        assertFalse(spec in ModelCatalog.required)
    }

    @Test fun entry_requires_licence_acceptance_with_name_attribution_and_card() {
        val l = requireNotNull(spec.licence)
        assertEquals("NVIDIA Open Model License Agreement", l.name)
        assertEquals("Licensed by NVIDIA Corporation under the NVIDIA Open Model License", l.attribution)
        assertTrue(l.modelCardUrl.startsWith("https://huggingface.co/nvidia/"))
        // The permissively licensed models stay one-tap downloads.
        assertTrue(ModelCatalog.blessed.filter { it.id != spec.id }.all { it.licence == null })
    }

    @Test fun streaming_arm_is_selectable_apart_from_the_shipped_models() {
        assertTrue(ModelCatalog.isStreamingStt(ModelCatalog.NEMOTRON_STREAM_ID))
        assertFalse(ModelCatalog.sttModels.any { it.first == ModelCatalog.NEMOTRON_STREAM_ID })
        assertFalse(ModelCatalog.isStreamingStt("parakeet-v2"))
        assertFalse(ModelCatalog.isStreamingStt(""))
        assertFalse(ModelCatalog.isStreamingStt(null))
        assertFalse(ModelCatalog.isTransducerStt(ModelCatalog.NEMOTRON_STREAM_ID))   // not the offline NeMo path
        assertEquals("whisper-base", ModelCatalog.DEFAULT_STT_MODEL)                 // default unchanged
    }

    @Test fun install_markers_are_the_upstream_export_names() {
        assertEquals(listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
            STREAMING_TRANSDUCER_FILES)
    }

    @Test fun download_is_refused_without_acceptance_and_allowed_with_it() {
        assertNotNull(ModelLicences.refusal(spec) { false })
        assertNull(ModelLicences.refusal(spec) { it == spec.id })
        // An acceptance of another model does not count.
        assertNotNull(ModelLicences.refusal(spec) { it == "parakeet-v2" })
    }

    @Test fun unlicensed_models_never_ask() {
        val parakeet = ModelCatalog.blessed.first { it.id == "parakeet-v2" }
        assertNull(ModelLicences.refusal(parakeet) { false })
        assertTrue(ModelLicences.matches(parakeet, null))
    }

    @Test fun stored_acceptance_must_name_the_current_licence() {
        assertFalse(ModelLicences.matches(spec, null))
        assertFalse(ModelLicences.matches(spec, "true"))
        assertFalse(ModelLicences.matches(spec, "Some Older Licence v0"))
        assertTrue(ModelLicences.matches(spec, spec.licence!!.name))
    }

    @Test fun small_memory_class_shows_the_trade_off_instead_of_hiding_the_option() {
        val base = "Nemotron streaming 0.6B"
        assertEquals(base, ModelCatalog.streamingSttLabel(base, 256, lowRam = false))
        assertEquals(base, ModelCatalog.streamingSttLabel(base, 512, lowRam = false))
        assertTrue(ModelCatalog.streamingSttLabel(base, 192, lowRam = false).contains("900 MB"))
        assertTrue(ModelCatalog.streamingSttLabel(base, 512, lowRam = true).contains("900 MB"))
        assertEquals(base, ModelCatalog.streamingSttLabel(base, 0, lowRam = false))   // unknown: no claim
    }
}
