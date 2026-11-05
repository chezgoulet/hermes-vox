package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L6 (0.9.1 review): the stored form is distinguishable, a legacy plaintext key with `:`
 * survives, and a plaintext fallback is upgraded on the next start.
 *
 * The Keystore itself is device-only; everything here is the format and the upgrade rule,
 * with the cipher injected.
 */
class SecureStoreTest {

    private fun fakeEncrypt(s: String) = SecureStore.ciphertext("IV", "CT($s)")
    /** The real decrypt contract: marked ciphertext decrypts, anything else is plaintext. */
    private fun fakeDecrypt(stored: String): String? =
        if (stored.startsWith(SecureStore.PREFIX) && stored.contains("CT(")) {
            stored.substringAfter("CT(").removeSuffix(")")
        } else stored

    @Test fun newCiphertextIsMarked() {
        assertTrue(SecureStore.isVersioned(SecureStore.ciphertext("iv", "ct")))
        assertEquals("enc1:iv:ct", SecureStore.ciphertext("iv", "ct"))
        assertFalse(SecureStore.isVersioned("iv:ct"))
    }

    @Test fun aMarkedValueIsNeverUpgradedAgain() {
        assertNull(SecureStore.storageUpgrade("enc1:iv:ct", ::fakeDecrypt, ::fakeEncrypt))
    }

    @Test fun aPlaintextFallbackIsEncryptedOnTheNextStart() {
        // A plaintext key containing ':' is exactly the value the old decrypt lost.
        assertEquals("enc1:IV:CT(key:with:colons)",
            SecureStore.storageUpgrade("key:with:colons", ::fakeDecrypt, ::fakeEncrypt))
        assertEquals("enc1:IV:CT(plain)",
            SecureStore.storageUpgrade("plain", ::fakeDecrypt, ::fakeEncrypt))
    }

    @Test fun aLegacyUnversionedCiphertextIsMovedToTheMarkedForm() {
        val legacyDecrypt: (String) -> String? = { stored ->
            if (stored == "oldiv:oldct") "the-key" else null
        }
        assertEquals("enc1:IV:CT(the-key)",
            SecureStore.storageUpgrade("oldiv:oldct", legacyDecrypt, ::fakeEncrypt))
    }

    @Test fun nothingIsWrittenWhenTheKeyStoreStillRefusesOrTheValueIsUnreadable() {
        assertNull(SecureStore.storageUpgrade("plain", ::fakeDecrypt) { null })
        assertNull(SecureStore.storageUpgrade("x", { null }, ::fakeEncrypt))
        assertNull(SecureStore.storageUpgrade("", ::fakeDecrypt, ::fakeEncrypt))
    }

    @Test fun anUnencryptedValueWithoutAColonIsLegacyPlaintext() {
        // SecureStore.decrypt is the Android edge; the legacy decision is: unmarked and not
        // decryptable as ciphertext = plaintext, including one with ':'.
        val legacyPlain = "sk-legacy:with:colon"
        assertFalse(SecureStore.isVersioned(legacyPlain))
    }
}
