package com.hermesvox.wake

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore AES-256-GCM for enrolment takes. Its own alias, separate from
 * SecureStore's API-key alias, so forgetting the takes destroys only this key.
 * Sealed layout: 12-byte IV || ciphertext+tag.
 */
object KeystoreTakeCipher : TakeCipher {
    private const val ALIAS = "hv_wake_enrol"
    private const val IV_LEN = 12

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        (keyStore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return gen.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_LEN))
        return c.doFinal(sealed, IV_LEN, sealed.size - IV_LEN)
    }

    override fun destroy() {
        try { keyStore().deleteEntry(ALIAS) } catch (_: Exception) {}
    }

    /** The one place takes live: excluded from backup by construction. */
    fun store(context: Context): EnrolmentStore =
        EnrolmentStore(File(context.noBackupFilesDir, "wake/enrol"), this)
}
