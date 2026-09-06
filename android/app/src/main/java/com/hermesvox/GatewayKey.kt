package com.hermesvox

/**
 * C0 (0.4.0) — the Hermes gateway API key is USER-ENTERED ONLY. The two key
 * sources are onboarding and Settings → Entity re-entry, both stored via
 * SecureStore (encrypted at rest). There is deliberately NO compiled-in default,
 * no BuildConfig field, and no baked fallback anywhere: an empty store (or a
 * store that cannot be decrypted) resolves to "" — the connector must not start,
 * and the UI must prompt the user to add their key in Settings.
 *
 * Pure JVM so the rule is testable off-device (same pattern as BargeGate /
 * EndpointRule). [decrypt] is injected to keep Android's Keystore out of tests.
 */
object GatewayKey {
    /** User-facing prompt shown when no user-entered key is stored. Wording only —
     *  C0 adds no new UI, it makes the empty-key path explicit instead of silent. */
    const val MISSING_KEY_PROMPT = "Enter your gateway API key in Settings"

    /** Effective gateway key from the stored pref value. Never a default: blank
     *  storage or an undecryptable value resolve to "" (missing), never to some
     *  compiled-in key. */
    fun resolve(stored: String, decrypt: (String) -> String?): String {
        if (stored.isBlank()) return ""
        return decrypt(stored)?.trim().orEmpty()
    }

    fun isMissing(effectiveKey: String): Boolean = effectiveKey.isBlank()
}
