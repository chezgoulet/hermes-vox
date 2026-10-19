package com.hermesvox.wake

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Seals take bytes at rest. The app uses [KeystoreTakeCipher] (Android Keystore
 * AES-GCM); tests use a software key. [destroy] deletes the key so any ciphertext
 * left behind (a crash mid-delete) is unreadable.
 */
interface TakeCipher {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
    fun destroy()
}

/**
 * EnrolmentStore — the owner's wake-phrase takes. Voice biometric data (D6), so:
 *  - **device-local**: [dir] is under `noBackupFilesDir`, and nothing in this build
 *    sends a take anywhere (a gateway upload will need its own, separate consent);
 *  - **encrypted**: every take is sealed by [cipher] before it touches disk;
 *  - **consented**: [save] refuses until [grantConsent], and a consent recorded
 *    against older wording ([CONSENT_VERSION]) no longer counts;
 *  - **deletable**: [deleteAll] removes takes and consent and destroys the key.
 *
 * Only takes that pass [TakeMeasure] are stored, so [count] is the number of
 * measured, usable takes — the number the screen shows.
 */
class EnrolmentStore(
    private val dir: File,
    private val cipher: TakeCipher,
    private val slots: Int = EnrolmentPrompts.takes,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed class SaveResult {
        data class Saved(val measure: TakeMeasure) : SaveResult()
        data class Rejected(val measure: TakeMeasure, val reason: String) : SaveResult()
        object NoConsent : SaveResult()
    }

    private val consentFile get() = File(dir, CONSENT_FILE)

    fun hasConsent(): Boolean {
        val f = consentFile
        if (!f.isFile) return false
        val version = f.readText().trim().substringBefore(' ').toIntOrNull()
        return version == CONSENT_VERSION
    }

    fun grantConsent() {
        dir.mkdirs()
        writeAtomic(consentFile, "$CONSENT_VERSION ${clock()}".toByteArray())
    }

    /** Withdrawing consent is the same act as forgetting everything. */
    fun revokeConsent() = deleteAll()

    fun save(index: Int, pcm: ShortArray, sampleRate: Int = WakeWordEngine.SAMPLE_RATE): SaveResult {
        require(index in 0 until slots) { "take index $index outside 0 until $slots" }
        if (!hasConsent()) return SaveResult.NoConsent
        val m = TakeMeasure.of(pcm, sampleRate)
        m.problem?.let { return SaveResult.Rejected(m, it) }
        writeAtomic(takeFile(index), cipher.seal(encode(index, sampleRate, pcm)))
        return SaveResult.Saved(m)
    }

    /** The decrypted samples of take [index], or null if absent or unreadable. */
    fun load(index: Int): ShortArray? {
        val f = takeFile(index)
        if (!f.isFile) return null
        return try { decode(cipher.open(f.readBytes()), index) } catch (_: Exception) { null }
    }

    fun recorded(): Set<Int> =
        (0 until slots).filterTo(sortedSetOf()) { takeFile(it).isFile }

    fun count(): Int = recorded().size

    fun delete(index: Int) { takeFile(index).delete() }

    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
        cipher.destroy()
    }

    private fun takeFile(index: Int) = File(dir, "take-%02d.hvw".format(index))

    private fun writeAtomic(target: File, bytes: ByteArray) {
        dir.mkdirs()
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "could not write ${target.name}" }
        }
    }

    companion object {
        /** Bump when the consent wording changes; older consent stops counting. */
        const val CONSENT_VERSION = 1
        const val CONSENT_FILE = "consent"
        private const val MAGIC = 0x48565754 // "HVWT"
        private const val FORMAT = 1

        internal fun encode(index: Int, sampleRate: Int, pcm: ShortArray): ByteArray {
            val bos = ByteArrayOutputStream(16 + pcm.size * 2)
            DataOutputStream(bos).use { out ->
                out.writeInt(MAGIC); out.writeInt(FORMAT)
                out.writeInt(index); out.writeInt(sampleRate)
                pcm.forEach { out.writeShort(it.toInt()) }
            }
            return bos.toByteArray()
        }

        internal fun decode(bytes: ByteArray, expectIndex: Int): ShortArray {
            DataInputStream(bytes.inputStream()).use { inp ->
                check(inp.readInt() == MAGIC && inp.readInt() == FORMAT) { "not a take" }
                check(inp.readInt() == expectIndex) { "take index mismatch" }
                inp.readInt() // sample rate: always 16 kHz today
                val n = (bytes.size - 16) / 2
                return ShortArray(n) { inp.readShort() }
            }
        }
    }
}
