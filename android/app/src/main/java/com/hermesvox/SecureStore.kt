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
 * SecureStore — stores secrets encrypted at rest (Android Keystore, AES/GCM). The values
 * are user-entered and never committed; this keeps them out of plaintext
 * SharedPreferences.
 *
 * Per-profile (0.9.1): every read and write names a Keystore [alias], so the gateway key,
 * the phone-MCP token and any future per-profile secret have their own key. The default is
 * the device-wide gateway key, so old call sites keep their meaning.
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
 *  * L6 (second pass): a failure is also never silent to the USER. Every save goes
 *    through [seal], which says whether the value went to disk unencrypted, and the
 *    save sites show [DEGRADED_LINE]; Settings keeps saying it ([isDegraded]) for as
 *    long as the stored value is still plaintext.
 *
 *    Why save-and-say rather than refuse: refusing would lock a user whose Keystore is
 *    broken (it happens on some ROMs and after some restores) out of the app entirely,
 *    with no way to work around it. The value lands in this app's private storage, the
 *    manifest excludes it from backup (`allowBackup=false`, `fullBackupContent=false`),
 *    and every start retries the encryption. A plaintext key the user was not told
 *    about was the defect; a plaintext key the user is told about is a choice.
 */
object SecureStore {
    /** The device-wide gateway key, and the alias of every pre-0.9.1 value. */
    const val ALIAS = "hv_api_key"

    /** Marks a ciphertext this build wrote. Absent = legacy value (see the class doc). */
    const val PREFIX = "enc1:"

    /** True when [stored] is already in the marked form (nothing to upgrade). */
    fun isVersioned(stored: String): Boolean = stored.startsWith(PREFIX)

    /** The marked ciphertext form. Pure, so the format is pinned off-device. */
    fun ciphertext(ivB64: String, ctB64: String): String = "$PREFIX$ivB64:$ctB64"

    /**
     * The device-only edge: Base64 and the per-alias Keystore lookup.
     *
     * Both are unreachable off-device — `android.util.Base64` throws in a JVM unit test and
     * the AndroidKeyStore provider does not exist — so behind this seam a unit test can
     * still prove the property a per-profile secret depends on: a value written under an
     * alias is read back under THAT alias. The cipher, the marked format and the
     * marked/unmarked dispatch are all the real code. Production never assigns [device]
     * (it is a `var` for the same reason `CleartextPolicy.tunnelUp` is one).
     */
    internal interface Device {
        fun encode(bytes: ByteArray): String
        fun decode(text: String): ByteArray
        fun key(alias: String): SecretKey
    }

    internal object AndroidDevice : Device {
        override fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
        override fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)
        override fun key(alias: String): SecretKey = keystoreKey(alias)
    }

    internal var device: Device = AndroidDevice

    private fun keystoreKey(alias: String): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return gen.generateKey()
    }

    /** What a save site writes, and whether it had to write it unencrypted. */
    data class Sealed(val stored: String, val degraded: Boolean)

    /** The line a save site shows when [Sealed.degraded]. Never contains the value. */
    const val DEGRADED_LINE =
        "Saved, but NOT encrypted: this phone's Keystore refused the key. It is kept in " +
            "Vox's private storage (never backed up), and Vox tries to encrypt it again on " +
            "every start."

    /** The short form Settings shows beside the connection while the key is plaintext. */
    const val DEGRADED_TAG = "key not encrypted"

    /**
     * The one way to store a secret: the marked ciphertext, or — when the Keystore refuses —
     * the plaintext with [Sealed.degraded] set so the caller tells the user. A blank value
     * is stored blank and is not degraded (there is nothing to protect).
     */
    fun seal(value: String, alias: String = ALIAS, encrypt: (String, String) -> String? = ::encrypt): Sealed {
        if (value.isBlank()) return Sealed(value, degraded = false)
        val ct = encrypt(value, alias)
        return if (ct != null) Sealed(ct, degraded = false) else Sealed(value, degraded = true)
    }

    /**
     * True when [stored] is on disk as plaintext: unmarked, and [decrypt] hands it back
     * unchanged (a legacy unmarked ciphertext decrypts to something else, so it is not
     * flagged). Pure, so Settings' persistent marker is pinned off-device.
     */
    fun isDegraded(stored: String?, decrypt: (String) -> String? = { s -> SecureStore.decrypt(s) }): Boolean {
        if (stored.isNullOrBlank() || isVersioned(stored)) return false
        return decrypt(stored) == stored
    }

    /** Encrypt a value for storage under [alias]. Returns null on failure — callers go
     *  through [seal], which turns that null into a visible degraded state. */
    fun encrypt(plaintext: String, alias: String = ALIAS): String? {
        if (plaintext.isBlank()) return plaintext
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, device.key(alias))
            val iv = c.iv
            val ct = c.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            ciphertext(device.encode(iv), device.encode(ct))
        } catch (e: Exception) {
            // L6: the fallback is plaintext, and the user can see it happened. The value is
            // never lost; the next start retries the encryption (VoxApplication).
            VoxLog.w("event=secure-store encrypt=failed err=${e.javaClass.simpleName} using=plaintext")
            null
        }
    }

    /**
     * Decrypt a stored value written under [alias].
     *
     * Marked ciphertext that cannot be decrypted returns null (missing key — the Settings
     * prompt, never a wrong key). An unmarked value is tried as legacy ciphertext and,
     * when that fails, returned as the legacy plaintext it is — including one with `:`.
     *
     * The alias is not optional on the read path: reading a per-profile secret with the
     * default key would decrypt to garbage or to null, i.e. silently the wrong secret.
     */
    fun decrypt(stored: String, alias: String = ALIAS): String? {
        if (stored.isBlank()) return ""
        if (isVersioned(stored)) {
            return try { decryptFields(stored.removePrefix(PREFIX), alias) } catch (_: Exception) { null }
        }
        return try {
            decryptFields(stored, alias)
        } catch (_: Exception) {
            stored   // legacy plaintext, not a ciphertext we failed to read
        }
    }

    private fun decryptFields(body: String, alias: String): String {
        val (ivB64, ctB64) = body.split(":", limit = 2)
        val iv = device.decode(ivB64)
        val ct = device.decode(ctB64)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, device.key(alias), GCMParameterSpec(128, iv))
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
