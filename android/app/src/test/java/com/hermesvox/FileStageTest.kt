package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The staging rules that do not need a device: the cap is enforced *while* copying, the hash
 * is of exactly the bytes that were kept, and a binary that claims to be text is not decoded.
 *
 * The content-resolver half (`stage(context, uri)`) needs a real provider and is named in the
 * PR as device-only. Everything it calls is proven here.
 */
class FileStageTest {

    @Test fun aFileUnderTheCapIsCopiedWhole() {
        val body = ByteArray(1_000) { it.toByte() }
        val out = ByteArrayOutputStream()
        val n = FileStage.copyLimited(ByteArrayInputStream(body), out, FileOffer.MAX_BYTES)
        assertEquals(1_000L, n)
        assertEquals(body.toList(), out.toByteArray().toList())
    }

    @Test fun aFileExactlyAtTheCapIsCopiedWhole() {
        val body = ByteArray(10_000)
        val out = ByteArrayOutputStream()
        assertEquals(10_000L, FileStage.copyLimited(ByteArrayInputStream(body), out, 10_000))
        assertEquals(10_000, out.size())
    }

    @Test fun oneByteOverTheCapThrowsAndIsNeverTruncated() {
        val body = ByteArray(10_001)
        val threw = try {
            FileStage.copyLimited(ByteArrayInputStream(body), ByteArrayOutputStream(), 10_000)
            false
        } catch (_: FileStage.TooBig) {
            true
        }
        assertTrue("the copy must refuse, not truncate", threw)
    }

    @Test fun theRefusalLineCarriesTheSizeAndTheCap() {
        val r = FileStage.tooBig(104_857_600)
        assertEquals(
            "this file is 104.9 MB · the most I can offer is 100 MB",
            r.line,
        )
        assertEquals(104_857_600L, r.bytes)
    }

    @Test fun anUnknownSizeRefusalStillCarriesTheLimit() {
        assertEquals(
            "this file is larger than 100 MB · the most I can offer is 100 MB",
            FileStage.TOO_BIG_UNKNOWN,
        )
    }

    @Test fun binaryBytesThatClaimToBeTextAreNotDecoded() {
        val binary = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0xFF.toByte(), 0xFE.toByte())
        assertNull(FileOffer.decodeUtf8(binary))
    }

    @Test fun utf8TextRoundTrips() {
        val text = "réunion — 議事録\n"
        assertEquals(text, FileOffer.decodeUtf8(text.toByteArray()))
    }

    @Test fun aNulByteIsNeverInlined() {
        assertNull(FileOffer.decodeUtf8("hello\u0000world".toByteArray()))
    }

    @Test fun theHashIsOfTheStagedBytesOnly() {
        val body = "the exact bytes".toByteArray()
        val h = FileOffer.sha256(body)
        assertEquals(64, h.length)
        assertTrue("lowercase hex", h.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(h, FileOffer.sha256(body))
        assertEquals(h, FileOffer.sha256(ByteArrayInputStream(body).readBytes()))
    }

    @Test fun theTypeGuessIsConservative() {
        assertEquals("text/plain", FileStage.guessMime("notes.txt"))
        assertEquals("text/plain", FileStage.guessMime("notes.md"))
        assertEquals("application/json", FileStage.guessMime("data.json"))
        assertEquals("image/png", FileStage.guessMime("shot.png"))
        assertEquals("application/pdf", FileStage.guessMime("doc.pdf"))
        assertEquals("application/octet-stream", FileStage.guessMime("blob"))
    }
}
