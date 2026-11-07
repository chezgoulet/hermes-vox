package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pins in [ModelCatalog] are the only thing standing between the app and a corrupted or
 * substituted model file: `ModelDownloader` refuses an artifact whose sha256 does not match, so a
 * pin that is malformed, duplicated or copied from the wrong row fails **on a user's phone**, at
 * download time, after hundreds of megabytes.
 *
 * This test cannot check a pin against upstream — that needs the artifact itself. What it can do is
 * make the cheap mistakes impossible before they reach a device: a placeholder, a truncated hash, a
 * hash reused from another model, two models claiming the same artifact, a non-https source.
 */
class ModelChecksumPinsTest {

    private val hex64 = Regex("^[0-9a-f]{64}$")

    private val placeholders = setOf(
        "0".repeat(64),
        "f".repeat(64),
        "a".repeat(64),
    )

    @Test
    fun every_blessed_model_pins_a_well_formed_sha256() {
        val problems = ArrayList<String>()
        for (m in ModelCatalog.blessed) {
            val pin = m.sha256.trim().lowercase()
            when {
                pin.isEmpty() -> problems += "${m.id}: no sha256 pinned"
                !hex64.matches(pin) -> problems += "${m.id}: sha256 is not 64 hex chars (${m.sha256})"
                pin in placeholders -> problems += "${m.id}: sha256 is a placeholder ($pin)"
            }
        }
        assertTrue("malformed checksum pins:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun no_two_models_pin_the_same_sha256() {
        // A copy-paste between rows is the failure this catches: both models then "verify" against
        // one artifact, and the second one installs the wrong file or fails at the user's expense.
        val byPin = ModelCatalog.blessed.groupBy { it.sha256.trim().lowercase() }
        val shared = byPin.filter { (pin, models) -> pin.isNotEmpty() && models.size > 1 }
        assertTrue(
            "the same sha256 is pinned by more than one model: " +
                shared.entries.joinToString("; ") { (pin, models) ->
                    "${models.map { it.id }} all pin ${pin.take(12)}…"
                },
            shared.isEmpty(),
        )
    }

    @Test
    fun every_model_has_a_unique_id_and_an_artifact() {
        val ids = ModelCatalog.blessed.map { it.id }
        assertEquals("model ids must be unique: $ids", ids.size, ids.toSet().size)

        val missing = ModelCatalog.blessed.filter { it.id.isBlank() || (it.file.isBlank() && it.url.isBlank()) }
        assertTrue("models with no id or no artifact: ${missing.map { it.id }}", missing.isEmpty())

        val bad = ModelCatalog.blessed.filter { !it.id.matches(Regex("^[a-z0-9][a-z0-9._-]*$")) }
        assertTrue("model ids must be lower-case slugs: ${bad.map { it.id }}", bad.isEmpty())
    }

    @Test
    fun every_model_declares_a_positive_size() {
        val bad = ModelCatalog.blessed.filter { it.sizeMB <= 0.0 }
        assertTrue("models with a non-positive download size: ${bad.map { it.id }}", bad.isEmpty())
    }

    @Test
    fun every_artifact_is_fetched_over_https() {
        // The pin is only worth anything if the bytes come from where we think: a plain-http source
        // lets the file be swapped in transit, and a substituted file cannot match the pin — but the
        // failure lands on the user as "download failed" rather than "your source was tampered with".
        val bad = ModelCatalog.blessed.filter { m ->
            val url = m.url.ifBlank { ModelCatalog.DEFAULT_SOURCE + "/" + m.file }
            !url.startsWith("https://")
        }
        assertTrue("artifacts fetched over something other than https: ${bad.map { it.id }}", bad.isEmpty())
    }
}
