package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C0 proof — the Hermes gateway connector has NO compiled-in key default. The
 * ONLY key sources are onboarding + Settings re-entry (user-entered, stored via
 * SecureStore). [GatewayKey.resolve] is the single rule the app uses to turn the
 * stored (encrypted) pref into the effective key, and it must return "" for every
 * empty/unusable store — never a baked constant, never an env fallback.
 */
class GatewayKeyTest {

    /** A SecureStore that successfully decrypts (stands in for the Keystore). */
    private fun storeOf(key: String): (String) -> String? = { _ -> key }

    @Test fun connector_has_no_compiled_in_key_default() {
        // Brand-new install / cleared Settings: nothing stored -> resolve("") is
        // empty and the connector is missing a key. There is no default to fall
        // back to, so the app must prompt the user (never start an empty-auth or
        // fake-keyed session).
        assertEquals("", GatewayKey.resolve("", storeOf("")))
        assertTrue(GatewayKey.isMissing(GatewayKey.resolve("", storeOf(""))))
        // A decrypt that yields blank is also missing.
        assertTrue(GatewayKey.isMissing(GatewayKey.resolve("blob", storeOf(""))))
    }

    @Test fun undecryptable_store_is_missing_not_a_fallback_key() {
        // The stored blob cannot be decrypted (wrong Keystore / tampered) -> the
        // effective key must be "" (treated as missing), NOT some compiled-in key.
        val undecryptable: (String) -> String? = { _ -> null }
        assertEquals("", GatewayKey.resolve("cipher:blob", undecryptable))
        assertTrue(GatewayKey.isMissing(GatewayKey.resolve("cipher:blob", undecryptable)))
    }

    @Test fun user_entered_key_is_used_exactly() {
        // The ONLY path to a non-empty key: the user stored one and it decrypts.
        assertEquals("sk-user-entered", GatewayKey.resolve("enc:ct", storeOf("sk-user-entered")))
        assertFalse(GatewayKey.isMissing(GatewayKey.resolve("enc:ct", storeOf("sk-user-entered"))))
    }

    @Test fun legacy_plaintext_storage_passes_through() {
        // Legacy (pre-Keystore) plaintext decrypts as-is via SecureStore.
        assertEquals("legacy-key", GatewayKey.resolve("legacy-key", storeOf("legacy-key")))
    }

    @Test fun empty_store_prompts_for_settings() {
        // The empty-key state is user-facing wording that points to Settings —
        // the connector error is surfaced, never silent.
        assertEquals("Enter your gateway API key in Settings", GatewayKey.MISSING_KEY_PROMPT)
        assertTrue(GatewayKey.MISSING_KEY_PROMPT.contains("Settings"))
    }
}
