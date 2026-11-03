package com.hermesvox

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.Socket

/**
 * The one-shot server, proven on **loopback** so it runs in CI.
 *
 * This is the only test in the §B set that needs a socket. It binds `127.0.0.1` — never the
 * tailnet address, never `0.0.0.0` — which is exactly what `FileServer.start` takes as an
 * argument. The app passes the tailnet address from [Tailnet]; the rule that only the tailnet
 * address is ever passed is [TailnetTest]'s job, and the actual bind on a tailnet interface
 * needs a device.
 */
class FileServerTest {

    private lateinit var file: File
    private val body = "meeting notes\n".repeat(40).toByteArray()   // 560 bytes
    private val token = "0123456789abcdef0123456789abcdef"
    private var log = mutableListOf<String>()

    @Before fun setUp() {
        file = File.createTempFile("offer-", ".bin")
        file.writeBytes(body)
        log = mutableListOf()
    }

    @After fun tearDown() {
        file.delete()
    }

    private fun start(
        expiresAtMs: Long = System.currentTimeMillis() + 300_000,
        clock: () -> Long = System::currentTimeMillis,
        onTransfer: () -> Unit = {},
    ) = FileServer.start(
        bind = InetAddress.getLoopbackAddress(),
        token = token,
        file = file,
        bytes = body.size.toLong(),
        mime = "text/plain",
        sha256 = FileOffer.sha256(body),
        expiresAtMs = expiresAtMs,
        log = { log.add(it) },
        onTransfer = onTransfer,
    )

    /** A raw request; returns (status, headers, body). */
    private fun request(
        port: Int,
        target: String = "/$token",
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
    ): Triple<Int, Map<String, String>, ByteArray> {
        Socket(InetAddress.getLoopbackAddress(), port).use { s ->
            s.soTimeout = 5_000
            val out = s.getOutputStream()
            val sb = StringBuilder("$method $target HTTP/1.1\r\nHost: 127.0.0.1\r\n")
            for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray()); out.flush()
            val input = BufferedInputStream(s.getInputStream())
            val status = parseStatus(readLine(input) ?: "")
            val hs = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) hs[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val len = hs["content-length"]?.toInt() ?: 0
            val buf = ByteArray(len)
            var n = 0
            while (n < len) {
                val r = input.read(buf, n, len - n)
                if (r < 0) break
                n += r
            }
            return Triple(status, hs, buf.copyOf(n))
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("ISO-8859-1")
            if (c == '\n'.code) return b.toString("ISO-8859-1").trimEnd('\r')
            b.write(c)
        }
    }

    private fun parseStatus(line: String): Int =
        line.split(' ').getOrNull(1)?.toIntOrNull() ?: -1

    // ---- the whole point: one GET is served, the second is 410 ----

    @Test fun servesOneGetThenRefusesTheSecond() {
        val s = start()
        val before = file.length()
        try {
            val first = request(s.port)
            assertEquals(200, first.first)
            assertArrayEquals(body, first.third)
            assertEquals(body.size.toString(), first.second["content-length"])
            assertEquals(FileOffer.sha256(body), first.second["x-vox-sha256"])
            assertEquals("the transfer is complete", true, s.served)

            val second = request(s.port)
            assertEquals("a second fetch is 410 Gone", 410, second.first)
            assertTrue(log.any { it.contains("reason=spent") })

            assertEquals("the server never touches the source", before, file.length())
        } finally {
            s.close()
        }
    }

    @Test fun theTransferCallbackFiresOnceAndOnlyOnce() {
        var transfers = 0
        val s = start(onTransfer = { transfers++ })
        try {
            assertEquals(200, request(s.port).first)
            assertTrue("the transfer completes once the body is written", s.awaitTransfer(2_000))
            assertEquals(1, transfers)
            assertEquals(410, request(s.port).first)
            assertEquals(410, request(s.port).first)
            assertEquals("a refused fetch never repeats the callback", 1, transfers)
        } finally {
            s.close()
        }
    }

    // ---- every refusal, and the token never in a log ----

    @Test fun anotherPathIsRefusedAndLogged() {
        val s = start()
        try {
            val r = request(s.port, target = "/not-the-token")
            assertEquals(404, r.first)
            assertTrue(log.any { it.contains("refused") && it.contains("path=<refused>") })
        } finally {
            s.close()
        }
    }

    @Test fun pathTraversalIsRefusedAndLogged() {
        val s = start()
        try {
            val r = request(s.port, target = "/../$token")
            assertEquals(404, r.first)
            assertTrue(log.any { it.contains("reason=traversal") })
            // The literal comparison means even the encoded forms cannot match.
            assertEquals(404, request(s.port, target = "/%2e%2e/$token").first)
            assertEquals(404, request(s.port, target = "/..%2f").first)
        } finally {
            s.close()
        }
    }

    @Test fun anotherMethodIsRefusedAndLogged() {
        val s = start()
        try {
            val r = request(s.port, method = "POST")
            assertEquals(405, r.first)
            assertEquals("GET", r.second["allow"])
            assertTrue(log.any { it.contains("reason=method") && it.contains("method=POST") })
            assertEquals(405, request(s.port, method = "HEAD").first)
            assertEquals(405, request(s.port, method = "DELETE").first)
        } finally {
            s.close()
        }
    }

    @Test fun aRangeRequestIsRefused() {
        val s = start()
        try {
            val r = request(s.port, headers = mapOf("Range" to "bytes=0-9"))
            assertEquals(416, r.first)
            assertTrue(log.any { it.contains("reason=range") })
            assertFalse("a range request must not transfer anything", s.served)
        } finally {
            s.close()
        }
    }

    @Test fun aQueryStringIsRefused() {
        val s = start()
        try {
            assertEquals(404, request(s.port, target = "/$token?x=1").first)
            assertTrue(log.any { it.contains("reason=query") })
        } finally {
            s.close()
        }
    }

    @Test fun theRootListsNothing() {
        val s = start()
        try {
            assertEquals(404, request(s.port, target = "/").first)
            assertTrue(log.any { it.contains("reason=listing") })
        } finally {
            s.close()
        }
    }

    @Test fun theTokenIsNeverInALogLine() {
        val s = start()
        try {
            request(s.port)                                   // the served request
            request(s.port)                                   // 410
            request(s.port, target = "/$token/../x")          // traversal-ish
            request(s.port, method = "POST")                  // method
            assertTrue("something was logged", log.isNotEmpty())
            for (line in log) {
                assertFalse("a capability URL in a log is a defect: $line", line.contains(token))
            }
        } finally {
            s.close()
        }
    }

    // ---- expiry and the bind rule ----

    @Test fun theListenerClosesItselfAtExpiry() {
        val s = start(expiresAtMs = System.currentTimeMillis() + 60)
        try {
            assertTrue("the listener must close itself", s.awaitClosed(3_000))
            assertTrue(s.logLines().any { it.contains("reason=expired") })
            assertTrue(s.closed)
        } finally {
            s.close()
        }
    }

    @Test fun aRequestAfterExpiryGetsNothing() {
        var now = System.currentTimeMillis()
        val s = start(expiresAtMs = now + 20, clock = { now })
        try {
            assertTrue(s.awaitClosed(3_000))
            now += 1_000
            val refused = try {
                request(s.port).first
            } catch (_: java.io.IOException) {
                0     // connection refused is a fine refusal once the listener is gone
            }
            assertTrue("expired means 410, or no listener at all — got $refused", refused == 0 || refused == 410)
        } finally {
            s.close()
        }
    }

    @Test fun bindingTheWildcardAddressIsRefused() {
        val threw = try {
            FileServer.start(
                bind = InetAddress.getByName("0.0.0.0"),
                token = token, file = file, bytes = body.size.toLong(), mime = "text/plain",
                sha256 = FileOffer.sha256(body), expiresAtMs = System.currentTimeMillis() + 1_000,
            )
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue("0.0.0.0 is never a bind address for this server", threw)
    }

    @Test fun theUrlMatchesTheBoundAddressAndPort() {
        val s = start()
        try {
            assertNotNull(s.url)
            assertTrue(s.url.startsWith("http://127.0.0.1:${s.port}/$token"))
        } finally {
            s.close()
        }
    }

    // ---- the copy on disk must still be the file we hashed, and a hostile line cannot wedge it ----

    @Test fun aStagedCopyThatChangedIsRefusedNotHalfServed() {
        // Content-Length is a promise. If the copy is not the length we announced, sending it
        // anyway would leave the agent waiting for bytes that never come and then calling a
        // half-response a truncation — so the request is refused (500) instead.
        val s = FileServer.start(
            bind = InetAddress.getLoopbackAddress(),
            token = token, file = file, bytes = body.size + 5L, mime = "text/plain",
            sha256 = FileOffer.sha256(body),
            expiresAtMs = System.currentTimeMillis() + 60_000,
            log = { log.add(it) },
        )
        try {
            val r = request(s.port)
            assertEquals("a copy that is not the one we hashed is refused", 500, r.first)
            assertFalse("nothing was served", s.served)
            assertTrue(log.any { it.contains("error=size-changed") })
        } finally {
            s.close()
        }
    }

    @Test fun anOverLongRequestLineIsRefusedAndDrained() {
        // A 40 KB request line must be refused, and the part past the reader's limit must be
        // drained with it — never re-read as if it were a header or a second request.
        val s = start()
        try {
            val huge = "/" + "a".repeat(40_000)
            Socket(InetAddress.getLoopbackAddress(), s.port).use { sock ->
                sock.soTimeout = 5_000
                sock.getOutputStream().write("GET $huge HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray())
                sock.getOutputStream().flush()
                val status = readLine(BufferedInputStream(sock.getInputStream())) ?: ""
                assertTrue("the over-long request is refused, got '$status'", status.startsWith("HTTP/1.1 4"))
            }
            // The single listener survived it and still serves the real request.
            assertEquals(200, request(s.port).first)
        } finally {
            s.close()
        }
    }

    @Test fun aTruncatedFetchIsNotASuccessAndMayBeRetried() {
        // A body far larger than any socket buffer, so hanging up genuinely interrupts the
        // write instead of fitting into the kernel's buffer and "succeeding".
        val big = File.createTempFile("offer-big-", ".bin")
        val bigBytes = ByteArray(4 * 1024 * 1024) { (it * 31 and 0xFF).toByte() }
        big.writeBytes(bigBytes)
        val s = FileServer.start(
            bind = InetAddress.getLoopbackAddress(),
            token = token, file = big, bytes = bigBytes.size.toLong(), mime = "application/octet-stream",
            sha256 = FileOffer.sha256(bigBytes),
            expiresAtMs = System.currentTimeMillis() + 300_000,
            log = { log.add(it) },
        )
        try {
            // Ask, read ten bytes, hang up: the response did not complete.
            Socket(InetAddress.getLoopbackAddress(), s.port).use { sock ->
                sock.getOutputStream().write(
                    "GET /$token HTTP/1.1\r\nHost: h\r\n\r\n".toByteArray(),
                )
                sock.getOutputStream().flush()
                val buf = ByteArray(10)
                sock.getInputStream().read(buf)
            }
            // The abort is noticed asynchronously; the next completed fetch still works.
            val again = request(s.port)
            assertEquals(200, again.first)
            assertEquals(bigBytes.size, again.third.size)
            assertTrue(s.served)
        } finally {
            s.close()
            big.delete()
        }
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) =
        assertEquals(expected.toList(), actual.toList())
}
