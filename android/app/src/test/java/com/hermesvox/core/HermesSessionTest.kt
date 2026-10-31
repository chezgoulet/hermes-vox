package com.hermesvox.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The non-streaming half of the entity connector: ping, the gateway endpoints, the
 * stored turn and the chain head. Ported from the Go voice/ and mobile/ tests.
 */
class HermesSessionTest {

    private val okReply = """{"id":"resp_1","output":[{"type":"message","content":[{"type":"output_text","text":"the voice of Hermes"}]}]}"""

    private fun body(r: TestServer.Request) = Json.parse(r.body) as JObj

    private inline fun expectError(contains: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            assertTrue("error ${e.message} should contain $contains", e.message.orEmpty().contains(contains))
            return
        }
        fail("expected an error containing $contains")
    }

    @Test
    fun `entity URL tolerates a pasted trailing slash`() {
        val cases = listOf(
            "http://h:8642" to "http://h:8642/v1/models",
            "http://h:8642/" to "http://h:8642/v1/models",
            "http://h:8642///" to "http://h:8642/v1/models",
            "  http://h:8642/  " to "http://h:8642/v1/models",
            "http://h:8642/base" to "http://h:8642/base/v1/models",
        )
        for ((base, want) in cases) assertEquals(base, want, Entity.url(base, "/v1/models"))
        assertEquals("http://h:8642/v1/responses/r_1", Entity.url("http://h:8642/", "/v1/responses/r_1"))
    }

    @Test
    fun `ping hits v1 models from a trailing-slash base with bearer and scope`() {
        TestServer { _, w -> w.send(200, "OK", """{"object":"list","data":[]}""") }.use { srv ->
            val s = HermesSession(srv.url + "/", "testkey", "hermes-agent")
            s.setSessionKey("agent:vox:tablet:member-42")
            s.ping()
            val r = srv.requests.single()
            assertEquals("GET", r.method)
            assertEquals("/v1/models", r.path)
            assertEquals("Bearer testkey", r.header("Authorization"))
            assertEquals("agent:vox:tablet:member-42", r.header(Entity.SESSION_KEY_HEADER))
        }
    }

    @Test
    fun `ping reports the status on a non-200`() {
        TestServer { _, w -> w.send(401, "Unauthorized", "nope") }.use { srv ->
            expectError("hermes 401 Unauthorized") { HermesSession(srv.url, "bad", "m").ping() }
        }
    }

    @Test
    fun `turnStored sends the turn and chains the next one off the reply id`() {
        var n = 0
        TestServer { _, w ->
            n++
            w.send(200, "OK", """{"id":"resp_$n","output":[{"type":"message","content":[{"type":"output_text","text":"one "},{"type":"output_text","text":"two"}]},{"type":"reasoning","content":[{"type":"output_text","text":"hidden"}]}]}""")
        }.use { srv ->
            val s = HermesSession(srv.url, "testkey", "hermes-agent")
            assertEquals("one two", s.turnStored("hi"))
            s.turnStored("again")
            val first = body(srv.requests[0])
            val second = body(srv.requests[1])
            assertEquals("/v1/responses", srv.requests[0].path)
            assertEquals("POST", srv.requests[0].method)
            assertEquals("Bearer testkey", srv.requests[0].header("Authorization"))
            assertEquals("hermes-agent", Json.str(first, "model"))
            assertEquals("hi", Json.str(first, "input"))
            assertTrue(first["stream"] is JBool && !(first["stream"] as JBool).value)
            assertEquals("previous_response_id is omitted when empty", null, first["previous_response_id"])
            assertEquals("provider is omitted when empty", null, first["provider"])
            assertEquals("resp_1", Json.str(second, "previous_response_id"))
            assertEquals("resp_2", s.lastID)
        }
    }

    @Test
    fun `provider and model overrides ride the body`() {
        TestServer { _, w -> w.send(200, "OK", okReply) }.use { srv ->
            val s = HermesSession(srv.url, "k", "hermes-agent")
            s.setModel("claude-x")
            s.setProvider("anthropic")
            s.turnStored("hi")
            val b = body(srv.requests.single())
            assertEquals("claude-x", Json.str(b, "model"))
            assertEquals("anthropic", Json.str(b, "provider"))
        }
    }

    @Test
    fun `turnStored rejects a non-200 with a bounded body`() {
        TestServer { _, w -> w.send(502, "Bad Gateway", "x".repeat(100_000)) }.use { srv ->
            try {
                HermesSession(srv.url, "k", "m").turnStored("hi")
                fail("expected an error")
            } catch (e: HermesException) {
                assertTrue(e.message!!.startsWith("hermes responses 502 Bad Gateway: xxx"))
                assertTrue("error not bounded: ${e.message!!.length}", e.message!!.length <= Entity.MAX_ERROR_BODY + 100)
            }
        }
    }

    @Test
    fun `turnStored with no output_text is an error and keeps the head`() {
        TestServer { _, w -> w.send(200, "OK", """{"id":"resp_x","output":[]}""") }.use { srv ->
            val s = HermesSession(srv.url, "k", "m")
            s.lastID = "resp_live"
            expectError("no output_text in response") { s.turnStored("hi") }
            assertEquals("resp_live", s.lastID)
        }
    }

    @Test
    fun `resetConversation drops the chain head`() {
        TestServer { _, w -> w.send(200, "OK", okReply) }.use { srv ->
            val s = HermesSession(srv.url, "k", "m")
            s.turnStored("hi")
            s.resetConversation()
            s.turnStored("fresh")
            assertEquals(null, body(srv.requests[1])["previous_response_id"])
        }
    }

    @Test
    fun `deleteLastResponse deletes the head and is a no-op without one`() {
        TestServer { r, w ->
            if (r.method == "DELETE") w.send(204, "No Content", "") else w.send(200, "OK", okReply)
        }.use { srv ->
            val s = HermesSession(srv.url, "k", "m")
            s.setSessionKey("agent:vox:x")
            s.deleteLastResponse()
            assertTrue("nothing stored server-side, nothing sent", srv.requests.isEmpty())
            s.turnStored("hi")
            s.deleteLastResponse()
            val del = srv.requests.last()
            assertEquals("DELETE", del.method)
            assertEquals("/v1/responses/resp_1", del.path)
            assertEquals("Bearer k", del.header("Authorization"))
            assertEquals("gateway calls carry no scope", "", del.header(Entity.SESSION_KEY_HEADER))
        }
    }

    @Test
    fun `deleteLastResponse surfaces a failure`() {
        TestServer { r, w ->
            if (r.method == "DELETE") w.send(500, "Internal Server Error", "boom") else w.send(200, "OK", okReply)
        }.use { srv ->
            val s = HermesSession(srv.url, "k", "m")
            s.turnStored("hi")
            expectError("hermes delete response 500 Internal Server Error: boom") { s.deleteLastResponse() }
        }
    }

    /**
     * #351: the reset drops the chain head, so a delete that runs AFTER the reset must be
     * handed the id captured before it — reading the head at delete time would either find
     * nothing or, worse, the fresh turn the re-armed call already made.
     */
    @Test
    fun `deleteResponse deletes a captured id after the reset, not the new head`() {
        var n = 0
        TestServer { r, w ->
            if (r.method == "DELETE") w.send(204, "No Content", "")
            else { n++; w.send(200, "OK", """{"id":"resp_$n","output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}]}""") }
        }.use { srv ->
            val s = HermesSession(srv.url, "k", "m")
            s.turnStored("hi")                     // resp_1 is the head
            val doomed = s.lastResponseId()
            assertEquals("resp_1", doomed)
            s.resetConversation()                  // #351's step order: reset first
            s.turnStored("fresh")                  // the re-armed call makes resp_2 the head
            s.deleteResponse(doomed)               // the captured id, not the new head
            assertEquals("/v1/responses/resp_1", srv.requests.last().path)
            assertEquals("resp_2", s.lastID)
        }
    }

    @Test
    fun `gateway endpoints return the raw body and report the code`() {
        TestServer { r, w ->
            when (r.path) {
                "/api/model/options" -> w.send(200, "OK", """{"providers":[]}""")
                "/v1/health" -> w.send(503, "Service Unavailable", "down")
                else -> w.send(404, "Not Found", "")
            }
        }.use { srv ->
            val s = HermesSession(srv.url, "k", "m")
            assertEquals("""{"providers":[]}""", s.modelOptions())
            assertEquals("Bearer k", srv.requests.last().header("Authorization"))
            expectError("hermes health 503") { s.gatewayHealth() }
        }
    }

    @Test
    fun `no scope declared sends no session key header`() {
        TestServer { r, w ->
            if (r.path == "/v1/models") w.send(200, "OK", "{}") else w.send(200, "OK", okReply)
        }.use { srv ->
            val s = HermesSession(srv.url, "testkey", "m")
            s.ping(); s.turnStored("hi")
            for (r in srv.requests) {
                assertEquals("", r.header(Entity.SESSION_KEY_HEADER))
                assertEquals("Bearer testkey", r.header("Authorization"))
            }
        }
    }

    /** The port of entity_race_test: the app re-declares the scope from Settings while
     *  turns are in flight. Every request must carry one whole declared value. */
    @Test
    fun `scope re-declaration during turns is consistent`() {
        TestServer { _, w -> w.send(200, "OK", okReply) }.use { srv ->
            val s = HermesSession(srv.url, "testkey", "m")
            val scopes = (0 until 8).map { "agent:vox:dev:member-$it" }
            s.setSessionKey(scopes[0])
            val stop = AtomicBoolean(false)
            val flipper = Thread {
                var i = 0
                while (!stop.get()) { s.setSessionKey(scopes[i++ % 8]); s.setModel("m$i") }
            }.apply { start() }
            repeat(40) { s.turnStored("hi") }
            stop.set(true); flipper.join()
            for (r in srv.requests) assertTrue(r.header(Entity.SESSION_KEY_HEADER) in scopes)
        }
    }

    @Test
    fun `voice prefix contract`() {
        val p = ResponsesClient.USER_TURN_PREFIX
        assertTrue(p.startsWith("[Voice input"))
        assertTrue(p.endsWith("] "))
        assertFalse(p.contains("\n"))
    }
}
