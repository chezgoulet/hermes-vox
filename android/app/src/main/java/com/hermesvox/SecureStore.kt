package com.hermesvox

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SecureStore — stores the entity API key encrypted at rest (Android Keystore,
 * AES/GCM). The key is user-entered + never committed; this keeps it out of
 * plaintext SharedPreferences.
 *
 * L6 (0.9.1 review): the stored form is now marked.
 *
 *  * [PREFIX] + iv + ":" + ciphertext is what this build writes, so a decrypt
 *    failure is a decrypt failure — not a guess about a plaintext value.
 *  * A value without the marker is either the pre-0.9.1 ciphertext (`iv:ct`) or
 *    legacy plaintext. The legacy decrypt is tried first; when it fails the value
 *    is returned as-is, which is what keeps a plaintext key containing `:` from
 *    being lost. A corrupt prefixed value still reads as missing (the Settings
 *    prompt), never as a wrong key.
 *  * [storageUpgrade] is the next-start retry: a plaintext fallback (an encrypt
 *    that failed) and an unversioned ciphertext are both rewritten in the marked
 *    form. A failure to encrypt is logged; it is never silent.
 */
object SecureStore {
    private const val ALIAS = "hv_api_key"

    /** Marks a ciphertext this build wrote. Absent = legacy value (see the class doc). */
    const val PREFIX = "enc1:"

    /** True when [stored] is already in the marked form (nothing to upgrade). */
    fun isVersioned(stored: String): Boolean = stored.startsWith(PREFIX)

    /** The marked ciphertext form. Pure, so the format is pinned off-device. */
    fun ciphertext(ivB64: String, ctB64: String): String = "$PREFIX$ivB64:$ctB64"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return gen.generateKey()
    }

    /** Encrypt a value for storage. Returns null on failure (caller keeps plaintext). */
    fun encrypt(plaintext: String): String? {
        if (plaintext.isBlank()) return plaintext
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val iv = c.iv
            val ct = c.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            ciphertext(Base64.encodeToString(iv, Base64.NO_WRAP), Base64.encodeToString(ct, Base64.NO_WRAP))
        } catch (e: Exception) {
            // L6: the fallback is plaintext, and the user can see it happened. The value is
            // never lost; the next start retries the encryption (VoxApplication).
            VoxLog.w("event=secure-store encrypt=failed err=${e.javaClass.simpleName} using=plaintext")
            null
        }
    }

    /**
     * Decrypt a stored value.
     *
     * Marked ciphertext that cannot be decrypted returns null (missing key — the Settings
     * prompt, never a wrong key). An unmarked value is tried as legacy ciphertext and,
     * when that fails, returned as the legacy plaintext it is — including one with `:`.
     */
    fun decrypt(stored: String): String? {
        if (stored.isBlank()) return ""
        if (isVersioned(stored)) {
            return try { decryptFields(stored.removePrefix(PREFIX)) } catch (_: Exception) { null }
        }
        return try {
            decryptFields(stored)
        } catch (_: Exception) {
            stored   // legacy plaintext, not a ciphertext we failed to read
        }
    }

    private fun decryptFields(body: String): String {
        val (ivB64, ctB64) = body.split(":", limit = 2)
        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        val ct = Base64.decode(ctB64, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(c.doFinal(ct), Charsets.UTF_8)
    }

    /**
     * The stored value to write on the next start, or null when nothing should change.
     *
     * Both inputs are injected so the rule is pure: [decrypt] must return the same string
     * for a plaintext value and null for an unreadable one; [encrypt] returns the marked
     * ciphertext or null when the Keystore refused.
     */
    fun storageUpgrade(
        stored: String,
        decrypt: (String) -> String?,
        encrypt: (String) -> String?,
    ): String? {
        if (stored.isBlank() || isVersioned(stored)) return null
        val plain = decrypt(stored) ?: return null
        val next = encrypt(plain) ?: return null
        return next.takeIf { it != stored }
    }
}
