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
 * plaintext SharedPreferences. Legacy plaintext values decrypt as-is.
 */
object SecureStore {
    private const val ALIAS = "hv_api_key"

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
            Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        } catch (e: Exception) { null }
    }

    /** Decrypt a stored value. A legacy (unencrypted, no ':') value returns as-is. */
    fun decrypt(stored: String): String? {
        if (!stored.contains(":")) return stored   // legacy plaintext
        return try {
            val (ivB64, ctB64) = stored.split(":", limit = 2)
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val ct = Base64.decode(ctB64, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }
}
