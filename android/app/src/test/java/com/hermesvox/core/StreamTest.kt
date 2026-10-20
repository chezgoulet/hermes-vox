package com.hermesvox.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The poll-drain surface's timing and lifecycle contract, ported from
 * voice/stream_test.go and voice/stream_contract_test.go. (Event content is pinned
 * by StreamParityTest against the Go goldens.)
 */
class StreamTest {

    private fun session(srv: TestServer) = HermesSession(srv.url, "testkey", "hermes-agent")

    private fun obj(json: String) = Json.parse(json) as JObj

    /** Polls until done; returns the done payload. */
    private fun drain(s: HermesSession, sid: String, budgetMs: Long = 5000): JObj {
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            s.waitStream(sid, 50)
            val p = obj(s.pollStreamJSON(sid))
            if ((p["done"] as JBool).value) return p
        }
        fail("stream never reported done"); error("unreachable")
    }

    /** Issue #16: the reader thread and the poller run concurrently on one state. */
    @Test
    fun `poll drain runs concurrently with the reader`() {
        val chunks = listOf(
            "event: response.created", """data: {"type":"response.created","response":{"id":"resp_r","status":"completed"}}""", "",
            "event: response.output_text.delta", """data: {"type":"response.output_text.delta","item_id":"i1","delta":"Hello "}""", "",
            "event: response.output_text.delta", """data: {"type":"response.output_text.delta","item_id":"i1","delta":"world"}""", "",
            "event: response.completed", """data: {"type":"response.completed","response":{"id":"resp_r","status":"completed"}}""", "",
            "data: [DONE]", "",
        )
        TestServer { _, w -> w.sse(); for (c in chunks) { w.write(c + "\n"); Thread.sleep(10) } }.use { srv ->
            val s = session(srv)
            val sid = s.startStream("hi")
            var finalText = ""
            for (i in 0 until 1000) {
                val p = try { obj(s.pollStreamJSON(sid)) } catch (_: HermesException) { break }
                finalText = Json.str(p, "text")
                if ((p["done"] as JBool).value) break
                Thread.sleep(2)
            }
            assertEquals("Hello world", finalText.trim())
            assertEquals("resp_r", s.lastID)
            assertFalse("the done poll retires the stream", Streams.isRegistered(sid))
        }
    }

    /** #39: waitStream wakes the moment a delta is buffered, not on a timer. */
    @Test
    fun `wait wakes on a delta`() {
        val burst = 60L
        TestServer { _, w ->
            w.sse()
            Thread.sleep(burst)
            w.write("event: response.output_text.delta\n" + """data: {"type":"response.output_text.delta","item_id":"i1","delta":"hello"}""" + "\n\n")
            Thread.sleep(burst)
            w.write("event: response.completed\n" + """data: {"type":"response.completed","response":{"id":"resp_r"}}""" + "\n\n")
        }.use { srv ->
            val s = session(srv)
            val sid = s.startStream("hi")
            val start = System.nanoTime()
            assertTrue("no data within the deadline", s.waitStream(sid, 1500))
            val wokeMs = (System.nanoTime() - start) / 1_000_000
            assertTrue("woke after ${wokeMs}ms, want an event-driven wake ~${burst}ms", wokeMs >= burst - 5 && wokeMs < 1500)
            val events = (obj(s.pollStreamJSON(sid))["events"] as JArr).items.map { it as JObj }
            assertEquals(listOf("hello"), events.filter { Json.str(it, "type") == "response.output_text.delta" }.map { Json.str(it, "delta") })
            drain(s, sid)
        }
    }

    @Test
    fun `wait times out on an idle stream`() {
        TestServer { _, w -> w.sse(); Thread.sleep(500); w.write("data: [DONE]\n\n") }.use { srv ->
            val s = session(srv)
            val sid = s.startStream("hi")
            val start = System.nanoTime()
            assertFalse("reported data, but the stream is idle", s.waitStream(sid, 40))
            assertTrue("returned before the deadline", (System.nanoTime() - start) / 1_000_000 >= 39)
            s.cancelStream(sid)
        }
    }

    /** A drained batch takes its wake with it: the next wait does not fire spuriously. */
    @Test
    fun `poll consumes the pending wake`() {
        TestServer { _, w ->
            w.sse()
            w.write("""data: {"type":"response.output_text.delta","item_id":"i1","delta":"a"}""" + "\n\n")
            w.holdUntilClientCloses(5000)
        }.use { srv ->
            val s = session(srv)
            val sid = s.startStream("hi")
            assertTrue(s.waitStream(sid, 2000))
            s.pollStreamJSON(sid)
            assertFalse(s.waitStream(sid, 50))
            val p = obj(s.pollStreamJSON(sid))
            assertTrue("an empty batch is null, as Go marshalled a nil slice", p["events"] is JNull)
            assertEquals("error is only present on the done poll", null, p["error"])
            s.cancelStream(sid)
        }
    }

    /** M1: cancel retires the entry synchronously and closes the connection. */
    @Test
    fun `cancel retires the stream and closes the connection`() {
        val closed = CountDownLatch(1)
        TestServer { _, w ->
            w.sse()
            w.write("event: response.created\n" + """data: {"type":"response.created","response":{"id":"resp_c","status":"in_progress"}}""" + "\n\n")
            if (w.holdUntilClientCloses(5000)) closed.countDown()
        }.use { srv ->
            val s = session(srv)
            val sid = s.startStream("hi")
            assertTrue(Streams.isRegistered(sid))
            assertTrue(s.waitStream(sid, 2000))
            s.cancelStream(sid)
            assertFalse("still registered after cancel — the M1 leak", Streams.isRegistered(sid))
            try { s.pollStreamJSON(sid); fail("poll after cancel must report no such stream") } catch (e: HermesException) {
                assertTrue(e.message!!.contains("no such stream"))
            }
            try { s.waitStream(sid, 10); fail("wait after cancel must report no such stream") } catch (_: HermesException) {}
            s.cancelStream(sid) // a second cancel is a no-op
            assertTrue("the gateway connection was not closed", closed.await(3, TimeUnit.SECONDS))
        }
    }

    /** A barge-in that lands before the request is even sent must not leak either. */
    @Test
    fun `cancel right after start is clean`() {
        TestServer { _, w -> w.sse(); w.holdUntilClientCloses(2000) }.use { srv ->
            val s = session(srv)
            repeat(20) {
                val sid = s.startStream("hi")
                s.cancelStream(sid)
                assertFalse(Streams.isRegistered(sid))
            }
        }
    }

    /** Keepalives reset the idle watchdog: a long tool call is not a dead link. */
    @Test
    fun `keepalives keep a slow turn alive`() {
        val old = Streams.idleTimeoutMs
        Streams.idleTimeoutMs = 200
        try {
            TestServer { _, w ->
                w.sse()
                repeat(6) { w.write(": keepalive\n\n"); Thread.sleep(100) }
                w.write("""data: {"type":"response.output_text.delta","item_id":"m","delta":"done"}""" + "\n\n")
                w.write("""data: {"type":"response.completed","response":{"id":"r"}}""" + "\n\n")
            }.use { srv ->
                val s = session(srv)
                val p = drain(s, s.startStream("hi"))
                assertEquals("", Json.str(p, "error"))
                assertEquals("done", Json.str(p, "text"))
            }
        } finally {
            Streams.idleTimeoutMs = old
        }
    }

    /** A gateway that accepts but never answers is caught by the same watchdog. */
    @Test
    fun `no response headers trips the watchdog`() {
        val old = Streams.idleTimeoutMs
        Streams.idleTimeoutMs = 150
        try {
            TestServer { _, w -> w.holdUntilClientCloses(3000); w.sse() }.use { srv ->
                val s = session(srv)
                val start = System.currentTimeMillis()
                val p = drain(s, s.startStream("hi"))
                assertEquals(Streams.STALLED, Json.str(p, "error"))
                assertTrue(System.currentTimeMillis() - start < 2000)
            }
        } finally {
            Streams.idleTimeoutMs = old
        }
    }

    @Test
    fun `a non-200 stream fails with a bounded body`() {
        TestServer { _, w -> w.send(502, "Bad Gateway", "x".repeat(100_000)) }.use { srv ->
            val s = session(srv)
            val err = Json.str(drain(s, s.startStream("hi")), "error")
            assertTrue(err, err.startsWith("hermes stream 502 Bad Gateway: xxx"))
            assertTrue("error not bounded: ${err.length}", err.length <= Entity.MAX_ERROR_BODY + 100)
        }
    }

    @Test
    fun `a body that ends without completion is an error`() {
        TestServer { _, w -> w.sse(); w.write("""data: {"type":"response.created","response":{"id":"r"}}""" + "\n\n") }.use { srv ->
            val s = session(srv)
            assertEquals("hermes stream: ended without completion", Json.str(drain(s, s.startStream("hi")), "error"))
        }
    }

    @Test
    fun `an unreachable gateway fails the turn`() {
        val port = java.net.ServerSocket(0).use { it.localPort } // closed: nothing listens
        val s = HermesSession("http://127.0.0.1:$port", "k", "m")
        val p = drain(s, s.startStream("hi"))
        assertTrue(Json.str(p, "error").isNotEmpty())
    }

    @Test
    fun `voice turns carry the prefix and plain turns do not`() {
        TestServer { _, w -> w.sse(); w.write("""data: {"type":"response.completed","response":{"id":"r"}}""" + "\n\n") }.use { srv ->
            val s = session(srv)
            drain(s, s.voiceTurn("what time is it"))
            drain(s, s.startStream("typed"))
            val (voice, plain) = srv.requests.map { Json.str(obj(it.body), "input") }
            assertEquals(ResponsesClient.USER_TURN_PREFIX + "what time is it", voice)
            assertEquals("typed", plain)
            val body = obj(srv.requests[0].body)
            assertEquals("BYOG: no voice field", null, body["voice"])
            assertEquals("never a system mutation", null, body["instructions"])
        }
    }

    /** #23: line boundaries survive; tool markers and blank lines do not. */
    @Test
    fun `plainText keeps line boundaries`() {
        assertEquals("First sentence.\nSecond sentence.\nThird.",
            Streams.plainText("[tool:read]\nFirst sentence.\n\nSecond sentence.\n[tool-done]\nThird."))
        assertEquals("", Streams.plainText("[tool:read]\n[tool-done]"))
    }

    @Test
    fun `lines split on LF and drop one trailing CR`() {
        val r = LineReader("a\r\nb\n\nc\rd\ne".byteInputStream())
        assertEquals(listOf("a", "b", "", "c\rd", "e"), generateSequence { r.next() }.toList())
        val long = LineReader(("x".repeat(70) + "\n").byteInputStream(), maxLine = 64)
        try { long.next(); fail("a line over the cap must fail the read") } catch (_: java.io.IOException) {}
    }
}
