package com.hermesvox.wake

import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrolmentStoreTest {

    /** Same layout as KeystoreTakeCipher (IV || ct), with a software key. */
    private class SoftCipher : TakeCipher {
        var key: SecretKey? = null
        var destroyed = 0
        private fun k() = key ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
        override fun seal(plain: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, k(), GCMParameterSpec(128, iv))
            return iv + c.doFinal(plain)
        }
        override fun open(sealed: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k(), GCMParameterSpec(128, sealed, 0, 12))
            return c.doFinal(sealed, 12, sealed.size - 12)
        }
        override fun destroy() { key = null; destroyed++ }
    }

    private fun dir(): File = File(Files.createTempDirectory("vox-wake").toFile(), "enrol")

    /** 1.2 s of a 220 Hz tone at about -12 dBFS: a take that passes measurement. */
    private fun goodTake(seconds: Double = 1.2): ShortArray =
        ShortArray((16_000 * seconds).toInt()) { i -> (8_000 * sin(2 * PI * 220 * i / 16_000)).toInt().toShort() }

    @Test
    fun save_is_refused_without_consent_and_nothing_is_written() {
        val d = dir()
        val store = EnrolmentStore(d, SoftCipher())
        assertFalse(store.hasConsent())
        assertEquals(EnrolmentStore.SaveResult.NoConsent, store.save(0, goodTake()))
        assertEquals(0, store.count())
        assertTrue(d.listFiles().isNullOrEmpty())
    }

    @Test
    fun a_measured_take_is_stored_and_counted() {
        val store = EnrolmentStore(dir(), SoftCipher())
        store.grantConsent()
        val r = store.save(0, goodTake())
        assertTrue(r is EnrolmentStore.SaveResult.Saved)
        assertEquals(1200, (r as EnrolmentStore.SaveResult.Saved).measure.durationMs)
        store.save(3, goodTake())
        assertEquals(2, store.count())
        assertEquals(sortedSetOf(0, 3), store.recorded())
    }

    @Test
    fun a_take_that_fails_measurement_is_rejected_and_not_counted() {
        val store = EnrolmentStore(dir(), SoftCipher())
        store.grantConsent()
        val silent = store.save(0, ShortArray(16_000))
        assertTrue(silent is EnrolmentStore.SaveResult.Rejected)
        val tooShort = store.save(1, goodTake(0.2))
        assertTrue(tooShort is EnrolmentStore.SaveResult.Rejected)
        assertEquals(0, store.count())
    }

    @Test
    fun recording_a_prompt_again_replaces_its_take() {
        val store = EnrolmentStore(dir(), SoftCipher())
        store.grantConsent()
        store.save(2, goodTake(1.0))
        store.save(2, goodTake(1.5))
        assertEquals(1, store.count())
        assertEquals(24_000, store.load(2)!!.size)
    }

    @Test
    fun takes_are_encrypted_on_disk_and_round_trip() {
        val d = dir()
        val store = EnrolmentStore(d, SoftCipher())
        store.grantConsent()
        val pcm = goodTake()
        store.save(0, pcm)
        val onDisk = File(d, "take-00.hvw").readBytes()
        val plain = EnrolmentStore.encode(0, 16_000, pcm)
        // No 64-byte window of the plaintext appears in the file.
        val window = plain.copyOfRange(1_000, 1_064)
        assertFalse(onDisk.toList().windowed(64).any { it == window.toList() })
        assertArrayEquals(pcm, store.load(0))
        assertTrue("no temp file left behind", d.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun a_take_is_unreadable_once_the_key_is_destroyed() {
        val d = dir()
        val cipher = SoftCipher()
        val store = EnrolmentStore(d, cipher)
        store.grantConsent()
        store.save(0, goodTake())
        cipher.destroy()
        assertNull(store.load(0))
    }

    @Test
    fun delete_all_removes_takes_consent_and_the_key() {
        val d = dir()
        val cipher = SoftCipher()
        val store = EnrolmentStore(d, cipher)
        store.grantConsent()
        store.save(0, goodTake()); store.save(1, goodTake())
        store.deleteAll()
        assertEquals(0, store.count())
        assertFalse(store.hasConsent())
        assertFalse(d.exists())
        assertEquals(1, cipher.destroyed)
    }

    @Test
    fun revoking_consent_forgets_everything() {
        val cipher = SoftCipher()
        val store = EnrolmentStore(dir(), cipher)
        store.grantConsent()
        store.save(0, goodTake())
        store.revokeConsent()
        assertEquals(0, store.count())
        assertFalse(store.hasConsent())
        assertEquals(EnrolmentStore.SaveResult.NoConsent, store.save(0, goodTake()))
    }

    @Test
    fun consent_given_to_older_wording_does_not_count() {
        val d = dir().apply { mkdirs() }
        File(d, EnrolmentStore.CONSENT_FILE).writeText("${EnrolmentStore.CONSENT_VERSION - 1} 1700000000000")
        val store = EnrolmentStore(d, SoftCipher())
        assertFalse(store.hasConsent())
        store.grantConsent()
        assertTrue(store.hasConsent())
    }

    @Test
    fun deleting_one_take_lowers_the_count() {
        val store = EnrolmentStore(dir(), SoftCipher())
        store.grantConsent()
        store.save(0, goodTake()); store.save(1, goodTake())
        store.delete(0)
        assertEquals(sortedSetOf(1), store.recorded())
    }

    @Test(expected = IllegalArgumentException::class)
    fun a_take_index_outside_the_script_is_a_bug() {
        val store = EnrolmentStore(dir(), SoftCipher(), slots = 8)
        store.grantConsent()
        store.save(8, goodTake())
    }

    @Test
    fun a_file_for_the_wrong_slot_does_not_load() {
        val d = dir()
        val store = EnrolmentStore(d, SoftCipher())
        store.grantConsent()
        store.save(0, goodTake())
        File(d, "take-00.hvw").copyTo(File(d, "take-01.hvw"))
        assertNull(store.load(1))
    }
}
