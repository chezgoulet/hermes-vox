package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves ModelCatalog.resolveSource() enforces the canonical-upstream decision:
 * blank/null input and any scheme-less value (the "no protocol" / "unknown
 * protocol" bug) fall back to DEFAULT_SOURCE; only a genuine `https?://` URL
 * overrides. Device-free JVM table test.
 */
class ModelCatalogSourceTest {

    private val cases = listOf(
        "" to ModelCatalog.DEFAULT_SOURCE,
        "host:8899" to ModelCatalog.DEFAULT_SOURCE,
        "host.lan" to ModelCatalog.DEFAULT_SOURCE,
        "ftp://x" to ModelCatalog.DEFAULT_SOURCE,
        "http://ok" to "http://ok",
        "https://ok.com" to "https://ok.com"
    )

    @Test fun resolves_input_against_scheme_allowlist() {
        for ((input, expected) in cases) {
            assertEquals("input=\"$input\"", expected, ModelCatalog.resolveSource(input))
        }
    }

    @Test fun null_and_blank_fall_back_to_default_source() {
        assertEquals(ModelCatalog.DEFAULT_SOURCE, ModelCatalog.resolveSource(null))
        assertEquals(ModelCatalog.DEFAULT_SOURCE, ModelCatalog.resolveSource(""))
        assertEquals(ModelCatalog.DEFAULT_SOURCE, ModelCatalog.resolveSource("   "))
    }

    @Test fun scheme_is_case_insensitive() {
        assertEquals("HTTP://OK", ModelCatalog.resolveSource("HTTP://OK"))
        assertEquals("HTTPS://Ok.Com", ModelCatalog.resolveSource("HTTPS://Ok.Com"))
    }
}
