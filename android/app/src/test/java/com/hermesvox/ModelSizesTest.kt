package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue's `sizeMB` is the number the Voice-models store renders, so it has to be
 * the true cost of the download: decimal MB (`bytes / 1_000_000`) to one decimal, from
 * each pinned artifact's `Content-Length`.
 *
 * The table below was measured on 2026-11-06 with a HEAD request per release URL (and by
 * downloading every artifact this change adds, whose sha256 is pinned elsewhere). It
 * caught one row still drifting: `supertonic` claimed 80.3 MB for a 84,692,981-byte
 * artifact — 84.7 MB, a 5.5% understatement — and that is corrected alongside this test.
 *
 * A new catalogue row has no measured size here, which fails: measure it, do not copy a
 * number from a branch or a README.
 */
class ModelSizesTest {

    /** `Content-Length` of each pinned artifact, in bytes. */
    private val measuredBytes = mapOf(
        "silero-vad" to 643_854L,
        "supertonic" to 84_692_981L,
        "whisper-tiny" to 118_071_777L,
        "whisper-tiny-multi" to 116_204_861L,
        "whisper-base" to 208_576_005L,
        "whisper-base-multi" to 207_557_382L,
        "whisper-small" to 635_693_775L,
        "whisper-small-multi" to 639_387_718L,
        "parakeet-v2" to 482_468_385L,
        ModelCatalog.NEMOTRON_STREAM_ID to 463_945_198L,
        "piper-lessac" to 82_038_311L,
        "piper-siwis" to 67_207_459L,
        "piper-tom" to 67_202_955L,
        "piper-upmc" to 80_422_639L,
        "gemma-e2b" to 2_588_147_712L,
    )

    @Test fun every_catalogued_size_is_the_measured_decimal_megabytes_of_its_artifact() {
        assertEquals("a row with no measured size — measure it, do not estimate",
            measuredBytes.keys, ModelCatalog.blessed.map { it.id }.toSet())
        for ((id, bytes) in measuredBytes) {
            val spec = ModelCatalog.blessed.first { it.id == id }
            val expected = bytes / 1_000_000.0
            // One decimal is the catalogue's precision, so rounding contributes at most
            // half a step; a 0.6 MB model cannot express 5% of itself in 0.1 MB steps.
            val tolerance = maxOf(expected * 0.05, 0.05)
            assertEquals("$id: sizeMB ${spec.sizeMB} vs measured ${expected} MB",
                expected, spec.sizeMB, tolerance)
            assertEquals("$id must be catalogued to one decimal",
                Math.round(spec.sizeMB * 10) / 10.0, spec.sizeMB, 1e-9)
        }
    }

    @Test fun the_new_voices_stay_opt_in_and_out_of_the_required_set() {
        // Guards against a size edit accidentally changing what "required" means.
        assertTrue(ModelCatalog.required.isNotEmpty())
        assertTrue(ModelCatalog.required.all { it.recommended })
        for (id in listOf("piper-siwis", "piper-tom", "piper-upmc",
            "whisper-tiny-multi", "whisper-base-multi", "whisper-small-multi")) {
            val spec = ModelCatalog.blessed.first { it.id == id }
            assertEquals("$id stays an opt-in download", false, spec in ModelCatalog.required)
        }
    }
}
