package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Keeps the BLESSED catalogue's metadata honest against the artifacts it points at, so a
 * label cannot drift from its model card — or a size from its download — without this suite
 * saying so.
 *
 * Everything below was measured on 2026-11-06, not taken from the table it checks:
 *  - Piper licences + speaker counts: `MODEL_CARD` / `*.onnx.json` inside each tarball
 *    (identical to https://huggingface.co/rhasspy/piper-voices/raw/main/<path>/MODEL_CARD).
 *    Siwis: "License: CC-BY 4.0". Tom: "License: AGPLv3". UPMC: "License:
 *    http://creativecommons.org/licenses/by-sa/4.0/", "Speakers: 2".
 *  - The three multilingual Whisper archives ship NO model card and no licence file (a full
 *    `tar -tjf` of each lists only the onnx models, tokens.txt and test_wavs), so the licence
 *    is the upstream project's: "Whisper's code and model weights are released under the MIT
 *    License" (github.com/openai/whisper README + LICENSE).
 *  - Sizes: `curl -sI` Content-Length of the pinned release URL, i.e. bytes / 1_000_000.
 *
 * Pure JVM — the catalogue is data, no Android needed.
 */
class ModelMetadataTest {

    /** id -> the licence the artifact's card (or upstream project) actually states.
     *  null = the description names no licence, and must keep naming none. */
    private val verifiedLicences: Map<String, String?> = mapOf(
        "silero-vad" to null,
        "piper-lessac" to null,                 // LibriTTS-R: the card names no licence line we rely on
        "piper-siwis" to "CC-BY 4.0",
        "piper-tom" to "AGPLv3",                // NOT CC0 — the card says AGPLv3
        "piper-upmc" to "CC BY-SA 4.0",          // NOT CC-BY-NC-SA: the card says CC BY-SA 4.0
        "supertonic" to "OpenRAIL-M",
        "whisper-tiny" to null,
        "whisper-base" to null,
        "whisper-small" to null,
        "whisper-tiny-multi" to "MIT",
        "whisper-base-multi" to "MIT",
        "whisper-small-multi" to "MIT",
        "parakeet-v2" to null,                  // card CC-BY-4.0, not yet stated in the description
        "gemma-e2b" to null
    )

    /** The licences a description is allowed to name. Longest first so "CC BY-SA 4.0" is
     *  not read as "CC-BY 4.0"; `\b` keeps "MIT" from matching inside another word. */
    private val licenceTokens = listOf(
        Regex("""\bCC BY-SA 4\.0\b"""),
        Regex("""\bCC-BY 4\.0\b"""),
        Regex("""\bAGPLv3\b"""),
        Regex("""\bOpenRAIL-M\b"""),
        Regex("""\bMIT\b"""),
        Regex("""\bCC0\b""")
    )

    private fun licenceNamed(desc: String): String? =
        licenceTokens.firstNotNullOfOrNull { it.find(desc)?.value }

    /** The model card a corrected licence claim can be checked against. */
    private val cardPaths = mapOf(
        "piper-siwis" to "fr/fr_FR/siwis/medium",
        "piper-tom" to "fr/fr_FR/tom/medium",
        "piper-upmc" to "fr/fr_FR/upmc/medium"
    )

    /**
     * Measured Content-Length, in bytes, of each blessed artifact's pinned URL. Kept as bytes
     * (not MB) so the decimal-MB unit of `sizeMB` cannot silently become MiB again: an MiB
     * value is only 4.86% off, which would slip through the 5% band on its own.
     */
    private val measuredBytes: Map<String, Long> = mapOf(
        "silero-vad" to 643_854L,
        "piper-lessac" to 82_038_311L,
        "piper-siwis" to 67_207_459L,
        "piper-tom" to 67_202_955L,
        "piper-upmc" to 80_422_639L,
        "supertonic" to 84_692_981L,
        "whisper-tiny" to 118_071_777L,
        "whisper-base" to 208_576_005L,
        "whisper-small" to 635_693_775L,
        "whisper-tiny-multi" to 116_204_861L,
        "whisper-base-multi" to 207_557_382L,
        "whisper-small-multi" to 639_387_718L,
        "parakeet-v2" to 482_468_385L,
        "gemma-e2b" to 2_588_147_712L
    )

    /** The rows this change corrected: their decimal-MB value must be exact to one place. */
    private val correctedSizes = setOf(
        "piper-siwis", "piper-tom", "piper-upmc",
        "whisper-tiny-multi", "whisper-base-multi", "whisper-small-multi"
    )

    @Test fun every_blessed_model_is_in_the_verified_tables() {
        val ids = ModelCatalog.blessed.map { it.id }.toSet()
        assertEquals("verified licence table is stale", ids, verifiedLicences.keys)
        assertEquals("measured size table is stale", ids, measuredBytes.keys)
    }

    @Test fun the_licence_in_each_description_matches_its_card() {
        for (spec in ModelCatalog.blessed) {
            assertEquals(
                "${spec.id}: description must name the licence its artifact states",
                verifiedLicences.getValue(spec.id),
                licenceNamed(spec.desc)
            )
        }
    }

    @Test fun the_corrected_licences_cite_their_model_card() {
        for ((id, path) in cardPaths) {
            val desc = ModelCatalog.blessed.first { it.id == id }.desc
            assertTrue("$id must link its model card", desc.contains("MODEL_CARD"))
            assertTrue("$id must link the card for $path",
                desc.contains("huggingface.co/rhasspy/piper-voices") && desc.contains(path))
        }
    }

    @Test fun ids_files_and_hashes_are_unique_and_well_formed() {
        val blessed = ModelCatalog.blessed
        assertEquals("duplicate model id", blessed.size, blessed.map { it.id }.toSet().size)
        assertEquals("duplicate artifact file", blessed.size, blessed.map { it.file }.toSet().size)
        assertEquals("duplicate sha256", blessed.size, blessed.map { it.sha256 }.toSet().size)
        val hex = Regex("^[0-9a-f]{64}$")
        for (spec in blessed) {
            assertTrue("${spec.id}: sha256 must be 64 lowercase hex chars", hex.matches(spec.sha256))
        }
    }

    @Test fun every_size_is_the_measured_download_in_decimal_mb() {
        for (spec in ModelCatalog.blessed) {
            val bytes = measuredBytes.getValue(spec.id)
            val measuredMB = bytes / 1_000_000.0
            val delta = abs(spec.sizeMB - measuredMB)
            // 5% of the artifact — or half the last digit the column prints (0.05 MB), which is
            // all the accuracy a one-place value can carry for a small file like the VAD (0.644 MB).
            val allowed = maxOf(measuredMB * 0.05, 0.05)
            assertTrue(
                "${spec.id}: sizeMB ${spec.sizeMB} is ${"%.2f".format(delta / measuredMB * 100)}% off " +
                    "the measured ${"%.1f".format(measuredMB)} MB ($bytes bytes)",
                delta <= allowed
            )
        }
    }

    @Test fun the_corrected_rows_are_decimal_mb_not_mib() {
        val onePlace = { v: Double -> Math.round(v * 10.0) / 10.0 }
        for (id in correctedSizes) {
            val spec = ModelCatalog.blessed.first { it.id == id }
            assertEquals(id, onePlace(measuredBytes.getValue(id) / 1_000_000.0), spec.sizeMB, 0.0)
        }
    }
}
