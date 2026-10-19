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
 * EnrolmentStore — the owner's enrolment recordings: the wake-phrase takes
 * (`take-NN.hvw`), one free-speech take (`speech.hvw`) and one room take
 * (`room.hvw`), each labelled with its [TakeKind] inside the sealed header so a file
 * renamed into another slot does not load. Voice biometric data (D6), so:
 *  - **device-local**: [dir] is under `noBackupFilesDir`, and nothing in this build
 *    sends a take anywhere (a gateway upload will need its own, separate consent);
 *  - **encrypted**: every take is sealed by [cipher] before it touches disk;
 *  - **consented**: [save] refuses until [grantConsent], and a consent recorded
 *    against older wording ([CONSENT_VERSION]) no longer counts;
 *  - **deletable**: [deleteAll] removes takes and consent and destroys the key.
 *
 * Only takes that pass [TakeMeasure] for their kind are stored, so [count] is the
 * number of measured, usable phrase takes — the number the screen shows.
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

    /** Phrase take [index]. */
    fun save(index: Int, pcm: ShortArray, sampleRate: Int = WakeWordEngine.SAMPLE_RATE): SaveResult {
        require(index in 0 until slots) { "take index $index outside 0 until $slots" }
        return put(TakeKind.PHRASE, index, pcm, sampleRate)
    }

    /** The single [TakeKind.SPEECH] or [TakeKind.ROOM] take; recording it again replaces it. */
    fun save(kind: TakeKind, pcm: ShortArray, sampleRate: Int = WakeWordEngine.SAMPLE_RATE): SaveResult {
        require(kind != TakeKind.PHRASE) { "phrase takes are saved by index" }
        return put(kind, 0, pcm, sampleRate)
    }

    /** The decrypted samples of phrase take [index], or null if absent or unreadable. */
    fun load(index: Int): ShortArray? = get(TakeKind.PHRASE, index)

    fun load(kind: TakeKind): ShortArray? {
        require(kind != TakeKind.PHRASE) { "phrase takes are loaded by index" }
        return get(kind, 0)
    }

    /** Recorded phrase-take indices. */
    fun recorded(): Set<Int> =
        (0 until slots).filterTo(sortedSetOf()) { file(TakeKind.PHRASE, it).isFile }

    fun count(): Int = recorded().size

    fun has(kind: TakeKind): Boolean = file(kind, 0).isFile

    fun has(step: EnrolmentPrompts.Step): Boolean = file(step.kind, step.index).isFile

    /**
     * Everything a verifier is trained from: enough phrase takes, the free speech, and
     * the room. Nothing trains yet — this is what the screen calls complete.
     */
    fun verifierMaterialComplete(): Boolean =
        EnrolmentPrompts.isReady(count()) && has(TakeKind.SPEECH) && has(TakeKind.ROOM)

    fun delete(index: Int) { file(TakeKind.PHRASE, index).delete() }

    fun delete(kind: TakeKind) { file(kind, 0).delete() }

    private fun put(kind: TakeKind, index: Int, pcm: ShortArray, sampleRate: Int): SaveResult {
        if (!hasConsent()) return SaveResult.NoConsent
        val m = TakeMeasure.of(pcm, sampleRate)
        m.problemFor(kind)?.let { return SaveResult.Rejected(m, it) }
        writeAtomic(file(kind, index), cipher.seal(encode(kind, index, sampleRate, pcm)))
        return SaveResult.Saved(m)
    }

    private fun get(kind: TakeKind, index: Int): ShortArray? {
        val f = file(kind, index)
        if (!f.isFile) return null
        return try { decode(cipher.open(f.readBytes()), kind, index) } catch (_: Exception) { null }
    }

    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
        cipher.destroy()
    }

    private fun file(kind: TakeKind, index: Int) = File(dir, when (kind) {
        TakeKind.PHRASE -> "take-%02d.hvw".format(index)
        TakeKind.SPEECH -> "speech.hvw"
        TakeKind.ROOM -> "room.hvw"
    })

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
        /**
         * Bump when the consent wording changes; older consent stops counting.
         * 2: consent now covers the free-speech and room recordings.
         */
        const val CONSENT_VERSION = 2
        const val CONSENT_FILE = "consent"
        private const val MAGIC = 0x48565754 // "HVWT"
        /** 2: the header carries the [TakeKind]. Format-1 files (phrase only) no longer load. */
        private const val FORMAT = 2
        private const val HEADER = 20

        internal fun encode(kind: TakeKind, index: Int, sampleRate: Int, pcm: ShortArray): ByteArray {
            val bos = ByteArrayOutputStream(HEADER + pcm.size * 2)
            DataOutputStream(bos).use { out ->
                out.writeInt(MAGIC); out.writeInt(FORMAT)
                out.writeInt(kind.ordinal); out.writeInt(index); out.writeInt(sampleRate)
                pcm.forEach { out.writeShort(it.toInt()) }
            }
            return bos.toByteArray()
        }

        internal fun decode(bytes: ByteArray, expectKind: TakeKind, expectIndex: Int): ShortArray {
            DataInputStream(bytes.inputStream()).use { inp ->
                check(inp.readInt() == MAGIC && inp.readInt() == FORMAT) { "not a take" }
                check(inp.readInt() == expectKind.ordinal) { "take kind mismatch" }
                check(inp.readInt() == expectIndex) { "take index mismatch" }
                inp.readInt() // sample rate: always 16 kHz today
                val n = (bytes.size - HEADER) / 2
                return ShortArray(n) { inp.readShort() }
            }
        }
    }
}
