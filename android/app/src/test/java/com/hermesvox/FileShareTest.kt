package com.hermesvox

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File
import java.net.InetAddress
import java.net.Socket

/**
 * The whole file flow, with fakes on the three edges (turn, sheet, tailnet) and a real staged
 * file on disk. What this proves: the route a file takes, the exact turn, the state the user
 * sees, and that the staged copy is deleted **on every ending** — collected, mismatched,
 * expired, cancelled.
 *
 * The server is real, but bound to `127.0.0.1`; that is why this runs in CI. The tailnet bind
 * itself needs a device (see the PR).
 */
class FileShareTest {

    private val dir: File = File.createTempFile("stage-", "").let { f ->
        f.delete(); f.mkdirs(); f
    }
    private var sent = mutableListOf<Pair<String, String>>()
    private var states = mutableListOf<FileShare.State>()
    private var logs = mutableListOf<String>()
    private var expiry: (() -> Unit)? = null
    private var now = 1_793_650_000_000L
    private var host: String? = "127.0.0.1"
    private var share: FileShare? = null

    private fun flow(): FileShare = FileShare(
        sendTurn = { text, transcript -> sent.add(text to transcript) },
        onState = { states.add(it) },
        log = { logs.add(it) },
        clock = { now },
        tailnetHost = { host },
        later = { _, action -> expiry = action },
    ).also { share = it; expiry = null }

    @After fun tearDown() {
        share?.close()
        dir.deleteRecursively()
    }

    private fun staged(name: String = "meeting-notes.md", body: ByteArray): Pair<FileOffer.File, File> {
        val f = File(dir, "copy-${System.nanoTime()}.bin")
        f.writeBytes(body)
        return FileOffer.File(name, "text/markdown", body.size.toLong(), FileOffer.sha256(body)) to f
    }

    private fun tokenOf(turn: String): String =
        turn.substringAfter(" url=").substringBefore(' ').substringAfterLast('/')

    private fun fetch(port: Int, token: String): Int =
        Socket(InetAddress.getLoopbackAddress(), port).use { s ->
            s.soTimeout = 5_000
            s.getOutputStream().write("GET /$token HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
            s.getOutputStream().flush()
            val input = BufferedInputStream(s.getInputStream())
            val line = generateSequence { input.read() }.takeWhile { it >= 0 && it != '\n'.code }
                .map { it.toChar() }.joinToString("")
            line.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
        }

    // ---- the offer ----

    @Test fun aBinaryFileIsServedAndTheTurnCarriesTheUrlAndHash() {
        val body = ByteArray(5_000) { 7 }
        val (file, copy) = staged(body = body)
        val f = flow()
        f.begin(file, copy, null)

        val state = states.last() as FileShare.State.Offering
        assertEquals("sharing meeting-notes.md · waiting for the agent to collect it", state.line)
        assertEquals(1, sent.size)
        val (turn, transcript) = sent.single()
        assertTrue(turn.startsWith("[[vox:file]] name=meeting-notes.md"))
        assertTrue("bytes in the turn", turn.contains(" bytes=5000 "))
        assertTrue("the hash is the staged file's", turn.contains(FileOffer.sha256(body)))
        assertTrue("the url is the bound server", turn.contains("url=http://127.0.0.1:${f.openServer!!.port}/"))
        assertEquals(1_793_650_300L, state.expiresAtSec)
        assertTrue(turn.contains("expires=1793650300"))
        assertTrue("the sentence follows the marker", turn.contains("\n" + FileOffer.OFFER_SENTENCE))
        assertFalse("no bytes in the turn", turn.contains(body.decodeToString()))
        assertFalse("no bytes in the transcript", transcript.contains(body.decodeToString()))
        assertEquals("You: shared meeting-notes.md (5.0 KB)", transcript)
    }

    @Test fun theTokenIsNeverLogged() {
        val (file, copy) = staged(body = ByteArray(50) { 3 })
        val f = flow()
        f.begin(file, copy, null)
        val token = tokenOf(sent.single().first)
        fetch(f.openServer!!.port, token)                    // a completed transfer
        fetch(f.openServer!!.port, token)                    // refused
        for (line in logs) {
            assertFalse("a token in a log is a defect: $line", line.contains(token))
        }
    }

    @Test fun aCompletedTransferAndAConfirmationBecomesCollected() {
        val (file, copy) = staged(body = ByteArray(1_000) { 1 })
        val f = flow()
        f.begin(file, copy, null)
        val token = tokenOf(sent.single().first)

        f.agentReplied("I fetched it, the sha256 matches, kept at ~/.hermes/inbox/meeting-notes.md")
        assertTrue("a reply before the transfer claims nothing", states.last() is FileShare.State.Offering)

        assertEquals(200, fetch(f.openServer!!.port, token))
        f.agentReplied("I fetched it, the sha256 matches, kept at ~/.hermes/inbox/meeting-notes.md")

        val state = states.last() as FileShare.State.Collected
        assertEquals(
            "collected · meeting-notes.md (1.0 KB) · ~/.hermes/inbox/meeting-notes.md",
            state.line,
        )
        assertFalse("the staged copy is gone", copy.exists())
        assertTrue("the server is closed", f.openServer == null)
    }

    @Test fun aReplyThatSaysNothingAboutTheFileDoesNotBecomeACollection() {
        // The transfer completed, but "Which file did you mean?" is not a collection. The app
        // says nothing new: the five-minute expiry is what resolves a silent agent, and it says
        // it never named a path.
        val (file, copy) = staged(body = ByteArray(1_000) { 1 })
        val f = flow()
        f.begin(file, copy, null)
        assertEquals(200, fetch(f.openServer!!.port, tokenOf(sent.single().first)))
        f.agentReplied("Which file did you mean?")
        assertTrue("silence is not a collection", states.last() is FileShare.State.Offering)
        expiry!!.invoke()
        assertEquals(
            "collected · meeting-notes.md (1.0 KB) · the agent did not say where it kept it",
            (states.last() as FileShare.State.Collected).line,
        )
    }

    @Test fun theAgentSayingMismatchIsNeverSuccess() {
        val (file, copy) = staged(body = ByteArray(1_000) { 1 })
        val f = flow()
        f.begin(file, copy, null)
        val token = tokenOf(sent.single().first)
        assertEquals(200, fetch(f.openServer!!.port, token))

        f.agentReplied("the file did not match: the sha256 does not match what you sent")

        val state = states.last() as FileShare.State.Mismatch
        assertEquals("the agent received a different file than we sent · nothing was kept", state.line)
        assertFalse(copy.exists())
        assertNull(f.openServer)
    }

    @Test fun expiryWithNoFetchIsTheExpiredLine() {
        val (file, copy) = staged(body = ByteArray(1_000) { 1 })
        val f = flow()
        f.begin(file, copy, null)
        assertNotNull("the expiry watcher is armed", expiry)
        expiry!!.invoke()
        val state = states.last() as FileShare.State.Expired
        assertEquals("the agent did not collect the file · tap to try again", state.line)
        assertFalse(copy.exists())
    }

    @Test fun expiryAfterATransferWithoutAReplyIsCollectedButInventsNoPath() {
        val (file, copy) = staged(body = ByteArray(1_000) { 1 })
        val f = flow()
        f.begin(file, copy, null)
        assertEquals(200, fetch(f.openServer!!.port, tokenOf(sent.single().first)))
        expiry!!.invoke()
        val state = states.last() as FileShare.State.Collected
        assertEquals(
            "collected · meeting-notes.md (1.0 KB) · the agent did not say where it kept it",
            state.line,
        )
        assertFalse(copy.exists())
    }

    // ---- inline ----

    @Test fun aSmallTextFileTravelsInlineAndStartsNoServer() {
        val body = "a small note\n".toByteArray()
        val (file, copy) = staged(name = "note.txt", body = body)
        val f = flow()
        f.begin(file, copy, FileOffer.decodeUtf8(body))

        val state = states.last() as FileShare.State.Inline
        assertEquals("sent inline · note.txt (13 bytes) · no server needed", state.line)
        assertNull(f.openServer)
        val (turn, transcript) = sent.single()
        assertTrue(turn.contains("```note.txt"))
        assertTrue(turn.contains("a small note"))
        assertTrue(turn.lineSequence().first().contains("inline=1"))
        assertFalse(turn.contains("url="))
        assertEquals("You: shared note.txt (13 bytes) inline", transcript)
        assertFalse("no staged copy is kept for an inline turn", copy.exists())
    }

    @Test fun aSmallTextFileWhileTailscaleIsDownIsOfferedInlineNotSilently() {
        host = null
        val body = "still here\n".toByteArray()
        val (file, copy) = staged(name = "note.txt", body = body)
        val f = flow()
        f.begin(file, copy, FileOffer.decodeUtf8(body))

        val state = states.last() as FileShare.State.Unreachable
        assertTrue(state.line.startsWith("Tailscale is not connected"))
        assertTrue(state.line.contains("send it inline instead"))
        assertNotNull(state.inlineTurn)
        assertTrue("nothing is sent without the tap", sent.isEmpty())
        assertNull(f.openServer)
        assertFalse(copy.exists())

        assertTrue(f.sendInline())
        assertEquals(1, sent.size)
        assertTrue(sent.single().first.contains("```note.txt"))
        assertEquals(FileShare.State.Inline::class, states.last()::class)
    }

    @Test fun aBigFileWhileTailscaleIsDownSaysSoAndStartsNothing() {
        host = null
        val (file, copy) = staged(body = ByteArray(9_000) { 2 })
        val f = flow()
        f.begin(file, copy, null)
        val state = states.last() as FileShare.State.Unreachable
        assertTrue(state.line.endsWith("connect Tailscale and try again"))
        assertNull(state.inlineTurn)
        assertNull(f.openServer)
        assertTrue(sent.isEmpty())
        assertFalse(copy.exists())
    }

    // ---- endings ----

    @Test fun cancelStopsTheServerAndDeletesTheCopy() {
        val (file, copy) = staged(body = ByteArray(2_000) { 5 })
        val f = flow()
        f.begin(file, copy, null)
        assertNotNull(f.openServer)
        val port = f.openServer!!.port
        f.cancel()
        assertNull(f.openServer)
        assertFalse(copy.exists())
        assertEquals(FileShare.State.Idle::class, states.last()::class)
        // The listener is gone: nothing answers on that port any more.
        val refused = try {
            Socket(InetAddress.getLoopbackAddress(), port).use { true }
        } catch (_: java.io.IOException) {
            false
        }
        assertFalse(refused)
    }

    @Test fun aStagingFailureIsOneStatedLine() {
        val f = flow()
        f.refuse("this file is 12.0 MB · the most I can offer is 100 MB")
        val state = states.last() as FileShare.State.Refused
        assertTrue(state.line.contains("the most I can offer"))
        assertNull(f.openServer)
    }

    @Test fun aSmallBinaryNeverReachesTheServerAnyway() {
        // A 900-byte image is not "text", so it is offered over the network — the inline
        // path is a text path, not a size path.
        val (file, copy) = staged(body = ByteArray(900) { 9 }).let { (meta, f) ->
            meta.copy(mime = "image/png") to f
        }
        val f = flow()
        f.begin(file, copy, null)
        assertNotNull(f.openServer)
        assertTrue(states.last() is FileShare.State.Offering)
    }
}
