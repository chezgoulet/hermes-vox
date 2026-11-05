package com.hermesvox

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * L6 (0.9.1 review): the stored form is distinguishable, a legacy plaintext key with `:`
 * survives, and a plaintext fallback is upgraded on the next start.
 *
 * Per-profile (0.9.1): a secret written under an alias is read back under THAT alias — the
 * gateway key and the phone-MCP token have separate Keystore keys, so a decrypt that
 * ignored the alias would hand a caller the wrong secret.
 *
 * The Keystore itself is device-only; everything here is the format, the alias routing and
 * the upgrade rule. [JvmDevice] replaces only the two pieces that do not exist off-device
 * (`android.util.Base64` throws in a JVM unit test, AndroidKeyStore is absent): the AES-GCM
 * cipher, the marked format and the marked/unmarked dispatch are the real code.
 */
class SecureStoreTest {

    private fun fakeEncrypt(s: String) = SecureStore.ciphertext("IV", "CT($s)")
    /** The real decrypt contract: marked ciphertext decrypts, anything else is plaintext. */
    private fun fakeDecrypt(stored: String): String? =
        if (stored.startsWith(SecureStore.PREFIX) && stored.contains("CT(")) {
            stored.substringAfter("CT(").removeSuffix(")")
        } else stored

    private object JvmDevice : SecureStore.Device {
        private val keys = HashMap<String, SecretKey>()

        override fun encode(bytes: ByteArray): String =
            java.util.Base64.getEncoder().encodeToString(bytes)

        override fun decode(text: String): ByteArray =
            java.util.Base64.getDecoder().decode(text)

        override fun key(alias: String): SecretKey = keys.getOrPut(alias) {
            KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        }
    }

    @Before fun useJvmDevice() { SecureStore.device = JvmDevice }

    @After fun restoreAndroidDevice() { SecureStore.device = SecureStore.AndroidDevice }

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

    /**
     * The per-profile property: a secret written under an alias round-trips under it, and
     * under no other. Both halves matter — a decrypt that ignored the alias would return
     * the wrong profile's secret rather than failing.
     */
    @Test fun aPerProfileSecretRoundTripsUnderItsOwnAlias() {
        val profile = "hv_phone_mcp"
        val stored = SecureStore.encrypt("tok-per-profile", profile)
        assertNotNull("encrypt under an alias returns the marked form", stored)
        stored!!
        assertTrue(SecureStore.isVersioned(stored))
        assertFalse("never at rest in plaintext", stored.contains("tok-per-profile"))

        assertEquals("tok-per-profile", SecureStore.decrypt(stored, profile))
        // The same bytes under another alias are another key: missing, never a wrong secret.
        assertNull(SecureStore.decrypt(stored, SecureStore.ALIAS))
        assertNull(SecureStore.decrypt(stored))
    }

    @Test fun theDefaultAliasIsStillTheDeviceWideKey() {
        val stored = SecureStore.encrypt("sk-device-wide")!!
        assertEquals("sk-device-wide", SecureStore.decrypt(stored))
        assertEquals("sk-device-wide", SecureStore.decrypt(stored, SecureStore.ALIAS))
    }

    /**
     * A legacy unmarked value is plaintext, `:` included — the L6 rule the old
     * `contains(":")` test got wrong. Both the alias-free and the aliased read agree.
     */
    @Test fun aLegacyPlaintextContainingAColonStillDecrypts() {
        val legacy = "sk-legacy:with:colon"
        assertFalse(SecureStore.isVersioned(legacy))
        assertEquals(legacy, SecureStore.decrypt(legacy))
        assertEquals(legacy, SecureStore.decrypt(legacy, "hv_phone_mcp"))
        // Also the value that looks like ciphertext but is not: unmarked, undecryptable —
        // still the plaintext it is, not a missing key.
        val coloned = "aGVsbG8=:d29ybGQ="
        assertEquals(coloned, SecureStore.decrypt(coloned))
    }

    /**
     * The VoxApplication migration in miniature: an unmarked ciphertext written under a
     * per-profile alias is re-marked and still decrypts under THAT alias afterwards.
     */
    @Test fun aLegacyUnmarkedCiphertextMigratesUnderItsAlias() {
        val profile = "hv_phone_mcp"
        val ciphertext = SecureStore.encrypt("tok-legacy", profile)!!
        val unmarked = ciphertext.removePrefix(SecureStore.PREFIX)
        assertFalse(SecureStore.isVersioned(unmarked))
        assertEquals("tok-legacy", SecureStore.decrypt(unmarked, profile))

        val upgraded = SecureStore.storageUpgrade(
            unmarked,
            decrypt = { SecureStore.decrypt(it, profile) },
            encrypt = { SecureStore.encrypt(it, profile) },
        )
        assertNotNull(upgraded)
        assertTrue(SecureStore.isVersioned(upgraded!!))
        assertEquals("tok-legacy", SecureStore.decrypt(upgraded, profile))
        // Re-encrypting under the default alias would have lost the profile's plaintext.
        assertNull(SecureStore.decrypt(upgraded))
    }
}
