package com.hermesvox

import com.hermesvox.core.JArr
import com.hermesvox.core.JBool
import com.hermesvox.core.JObj
import com.hermesvox.core.JStr
import com.hermesvox.core.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.Socket

/**
 * #421 step 3 / #503: the MCP wire, proven on **loopback** so it runs in CI.
 *
 * The app binds the tailnet address; this binds `127.0.0.1`, which is what makes the socket test
 * possible and is exactly the address `PhoneMcpServer.start` takes. The rule that only the tailnet
 * address is ever passed is [Tailnet]'s and [PhoneMcpWiringTest]'s job.
 *
 * What is pinned is the shape the gateway's client actually speaks — Streamable HTTP with a session:
 * `initialize` (and its `Mcp-Session-Id`), `notifications/initialized` (202), `tools/list`,
 * `tools/call`, `ping`, `DELETE`, and keep-alive on the socket. The reason this file is so explicit
 * about the session is #503: without one every response closed the connection, the gateway's client
 * charged five reconnects and parked the server, and no phone tool was ever registered.
 */
class PhoneMcpServerTest {

    private val token = "0123456789abcdef0123456789abcdef"
    private lateinit var server: PhoneMcpServer
    private val calls = ArrayList<Map<String, Any?>>()
    private val log = ArrayList<String>()

    @Before fun setUp() {
        server = PhoneMcpServer.start(
            bind = InetAddress.getLoopbackAddress(),
            token = token,
            handler = { args ->
                calls.add(args)
                """{"ok":true,"action":"${args["action"]}","id":"timer-1"}"""
            },
            port = 0,
            log = { log.add(it) },
        )
    }

    @After fun tearDown() {
        server.close()
    }

    private class Response(val status: Int, val headers: Map<String, String>, val body: String) {
        val sessionId: String? get() = headers[PhoneMcpServer.SESSION_HEADER.lowercase()]
    }

    /**
     * One TCP connection, used for more than one request.
     *
     * A response is read by its `Content-Length`, not by end-of-stream: that is the whole point of
     * the keep-alive tests, and it is what lets this file tell "the server ended the connection"
     * from "the test read one response".
     */
    private inner class Wire : AutoCloseable {
        private val socket = Socket(InetAddress.getLoopbackAddress(), server.port).apply {
            soTimeout = 5_000
        }
        private val out = socket.getOutputStream()
        private val input = BufferedInputStream(socket.getInputStream())

        fun send(
            method: String = "POST",
            target: String = PhoneMcpServer.PATH,
            body: String? = null,
            bearer: String? = token,
            extra: Map<String, String> = emptyMap(),
            close: Boolean = false,
        ): Response {
            val bytes = (body ?: "").toByteArray(Charsets.UTF_8)
            val sb = StringBuilder("$method $target HTTP/1.1\r\nHost: 127.0.0.1\r\n")
            bearer?.let { sb.append("Authorization: Bearer ").append(it).append("\r\n") }
            if (method == "POST") sb.append("Content-Type: application/json\r\n")
            sb.append("Content-Length: ").append(bytes.size).append("\r\n")
            for ((k, v) in extra) sb.append(k).append(": ").append(v).append("\r\n")
            if (close) sb.append("Connection: close\r\n")
            sb.append("\r\n")
            out.write(sb.toString().toByteArray(Charsets.UTF_8))
            if (method == "POST") out.write(bytes)
            out.flush()
            return read()
        }

        private fun read(): Response {
            val status = readLine(input)!!.split(' ')[1].toInt()
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val h = readLine(input).orEmpty()
                if (h.isEmpty()) break
                val i = h.indexOf(':')
                if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(length)
            var got = 0
            while (got < length) {
                val n = input.read(body, got, length - got)
                if (n < 0) break
                got += n
            }
            return Response(status, headers, String(body, 0, got, Charsets.UTF_8))
        }

        override fun close() {
            try { socket.close() } catch (_: Throwable) { }
        }
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().removeSuffix("\r")
            sb.append(c.toChar())
        }
    }

    /** One request on its own connection. */
    private fun request(
        method: String = "POST",
        target: String = PhoneMcpServer.PATH,
        body: String? = null,
        bearer: String? = token,
        extra: Map<String, String> = emptyMap(),
        close: Boolean = true,
    ): Response = Wire().use { it.send(method, target, body, bearer, extra, close) }

    private fun rpcBody(method: String, params: String? = null, id: String = "1"): String {
        val p = if (params == null) "" else ",\"params\":$params"
        return """{"jsonrpc":"2.0","id":$id,"method":"$method"$p}"""
    }

    private fun obj(r: Response): JObj = Json.parse(r.body) as JObj

    /**
     * A client that has completed the handshake: one connection, one session id, both reused.
     *
     * This is the shape the SDK has — its session id outlives the individual request — so the tests
     * that need "an ordinary request" get one without restating the handshake every time.
     */
    private inner class Client : AutoCloseable {
        val wire = Wire()
        val id: String

        init {
            val r = wire.send(body = rpcBody("initialize", """{"protocolVersion":"2025-06-18"}"""))
            assertEquals(200, r.status)
            id = r.sessionId ?: throw AssertionError("initialize returned no session id")
        }

        fun rpc(method: String, params: String? = null, id: String = "1", session: String? = this.id): Response =
            wire.send(body = rpcBody(method, params, id), extra = sessionHeader(session))

        fun call(arguments: String, id: String = "2"): Response = rpc(
            "tools/call",
            """{"name":"phone","arguments":$arguments}""",
            id = id,
        )

        override fun close() = wire.close()
    }

    private fun sessionHeader(id: String?): Map<String, String> =
        if (id == null) emptyMap() else mapOf(PhoneMcpServer.SESSION_HEADER to id)

    // ---- security ----

    @Test fun a_request_without_the_token_is_refused_and_nothing_runs() {
        val r = request(bearer = null, body = rpcBody("tools/list"))
        assertEquals(401, r.status)
        assertTrue(r.headers["www-authenticate"]!!.contains("Bearer"))
        assertTrue(calls.isEmpty())
    }

    @Test fun a_request_with_the_wrong_token_is_refused() {
        val r = request(bearer = token.reversed(), body = rpcBody("tools/list"))
        assertEquals(401, r.status)
        assertTrue(calls.isEmpty())
    }

    @Test fun the_token_is_never_logged() {
        request(bearer = null, body = rpcBody("tools/list"))
        assertTrue(log.isNotEmpty())
        for (line in log) assertFalse("a log line carried the token: $line", line.contains(token))
    }

    // ---- #452: the connection's own API key is a second valid bearer ----

    /**
     * The key this phone sends on its own turns. A different value from [token], same shape, so a
     * test that passes by comparison with the wrong field is visible.
     */
    private val connectionKey = "fedcba9876543210fedcba9876543210"

    private fun restartWithBoth(
        onTokenRead: () -> Unit = {},
        onKeyRead: () -> Unit = {},
        key: String = connectionKey,
    ) {
        server.close()
        server = PhoneMcpServer.start(
            bind = InetAddress.getLoopbackAddress(),
            tokenProvider = { onTokenRead(); token },
            handler = { "{}" },
            port = 0,
            log = { log.add(it) },
            keyProvider = { onKeyRead(); key },
        )
    }

    private fun listWith(bearer: String?): Response =
        request(bearer = bearer, body = rpcBody("tools/list"))

    /** A handshake on its own connection: 200 means the credential opened the door. */
    private fun handshake(bearer: String?): Response =
        request(bearer = bearer, body = rpcBody("initialize", """{"protocolVersion":"2025-06-18"}"""))

    @Test fun the_connections_own_key_is_accepted_as_a_bearer() {
        restartWithBoth()
        // A handshake with the key is what proves it: it mints a session and returns the tool list
        // on the next request, exactly as the phone's own token does.
        val r = request(bearer = connectionKey, body = rpcBody("initialize", """{"protocolVersion":"2025-06-18"}"""))
        assertEquals("the agent presents the key it already holds", 200, r.status)
        assertNotNull(r.sessionId)
        val tools = request(
            bearer = connectionKey,
            body = rpcBody("tools/list"),
            extra = sessionHeader(r.sessionId),
        )
        assertEquals(200, tools.status)
        assertTrue(tools.body, tools.body.contains("\"phone\""))
    }

    @Test fun the_phones_own_token_still_works() {
        restartWithBoth()
        val r = request(body = rpcBody("initialize", """{"protocolVersion":"2025-06-18"}"""))
        assertEquals(200, r.status)
        assertNotNull(r.sessionId)
    }

    @Test fun an_initialize_with_the_connections_key_is_the_handshake_the_phone_records() {
        val seen = ArrayList<Long>()
        server.close()
        server = PhoneMcpServer.start(
            bind = InetAddress.getLoopbackAddress(),
            tokenProvider = { token },
            handler = { "{}" },
            port = 0,
            log = { log.add(it) },
            onInitialize = { seen.add(it) },
            keyProvider = { connectionKey },
        )
        assertEquals(
            200,
            request(
                bearer = connectionKey,
                body = rpcBody("initialize", """{"protocolVersion":"2025-06-18"}"""),
            ).status,
        )
        assertEquals("the key is the proof, exactly as the token was", 1, seen.size)
    }

    @Test fun a_request_with_neither_credential_is_refused() {
        restartWithBoth()
        assertEquals(401, listWith(null).status)
        assertEquals(401, listWith("").status)
        assertTrue(calls.isEmpty())
    }

    @Test fun a_wrong_key_of_the_same_length_is_refused() {
        restartWithBoth()
        val nearMiss = "0" + connectionKey.drop(1)
        assertEquals("the test value must differ only in content", connectionKey.length, nearMiss.length)
        assertNotEquals(connectionKey, nearMiss)
        assertEquals(401, listWith(nearMiss).status)
        assertEquals(401, listWith(connectionKey.uppercase()).status)
        assertTrue(calls.isEmpty())
    }

    /**
     * No short-circuit: a request that matches the phone's token must still read the key, and the
     * reverse. If either branch decided whether the other ran, the timing would say which
     * credential was presented.
     */
    @Test fun both_credentials_are_read_on_every_request() {
        var tokenReads = 0
        var keyReads = 0
        restartWithBoth(onTokenRead = { tokenReads++ }, onKeyRead = { keyReads++ })
        val tokenAtStart = tokenReads
        val keyAtStart = keyReads
        assertEquals(200, handshake(token).status)
        assertEquals("the token branch still reads the key", keyAtStart + 1, keyReads)
        assertEquals(tokenAtStart + 1, tokenReads)
        assertEquals(200, handshake(connectionKey).status)
        assertEquals("the key branch still reads the token", tokenAtStart + 2, tokenReads)
        assertEquals(keyAtStart + 2, keyReads)
    }

    @Test fun a_refusal_is_one_401_with_no_hint_of_which_credential_was_tried() {
        restartWithBoth()
        val wrong = "0".repeat(32)
        val noHeader = listWith(null)
        val wrongKey = listWith(wrong)
        assertEquals(401, noHeader.status)
        assertEquals(401, wrongKey.status)
        assertEquals("the body names no credential", noHeader.body, wrongKey.body)
        assertEquals(noHeader.headers["www-authenticate"], wrongKey.headers["www-authenticate"])
    }

    @Test fun the_connection_key_is_never_logged() {
        restartWithBoth()
        listWith(null)
        listWith(connectionKey.reversed())
        listWith(connectionKey)
        assertTrue(log.isNotEmpty())
        for (line in log) {
            assertFalse("a log line carried the connection key: $line", line.contains(connectionKey))
        }
        assertTrue(
            "the refusal line keeps its shape and names no credential",
            log.any { it.startsWith("event=phone-mcp refused reason=") },
        )
    }

    @Test fun an_unconfigured_connection_leaves_only_the_phones_token_valid() {
        // No key provider (the default): a phone with no connection configured must not accept
        // an empty bearer as a second credential.
        assertEquals(200, handshake(token).status)
        assertEquals(401, handshake(connectionKey).status)
        assertEquals(401, handshake(null).status)
    }

    @Test fun one_address_is_required_and_any_local_is_refused() {
        try {
            PhoneMcpServer.start(
                bind = InetAddress.getByName("0.0.0.0"),
                token = token,
                handler = { "{}" },
                port = 0,
            )
            throw AssertionError("0.0.0.0 must never be bound")
        } catch (_: IllegalArgumentException) {
            // the point
        }
    }

    @Test fun another_path_is_not_a_route() {
        assertEquals(404, request(target = "/", body = "{}").status)
        assertEquals(404, request(target = "/files/secret", body = "{}").status)
    }

    @Test fun get_does_not_open_a_stream_we_do_not_have() {
        val r = request(method = "GET")
        assertEquals(405, r.status)
        assertTrue(r.headers["allow"]!!.contains("POST"))
        assertTrue("the client is told DELETE is a method too", r.headers["allow"]!!.contains("DELETE"))
    }

    @Test fun head_is_mcp_shaped_so_the_gateway_probe_passes() {
        val r = request(method = "HEAD")
        assertEquals(200, r.status)
        assertEquals("application/json", r.headers["content-type"])
        assertTrue(r.body.isEmpty())
    }

    // ---- the session (#503) ----

    @Test fun initialize_returns_a_session_id_that_is_unpredictable() {
        val first = request(body = rpcBody("initialize", """{"protocolVersion":"2025-06-18"}"""))
        val second = request(body = rpcBody("initialize", """{"protocolVersion":"2025-06-18"}"""))
        val a = first.sessionId!!
        val b = second.sessionId!!
        assertTrue(
            "the header the SDK stores is Mcp-Session-Id",
            first.headers.containsKey(PhoneMcpServer.SESSION_HEADER.lowercase()),
        )
        assertTrue("an id is at least 128 bits of hex, got $a", a.matches(Regex("[0-9a-f]{32,}")))
        assertNotEquals("every connection attempt gets its own session", a, b)
    }

    @Test fun a_request_without_the_session_id_is_refused_after_initialize() {
        val r = request(body = rpcBody("tools/list"))
        assertEquals("the spec's answer once initialize has happened", 404, r.status)
        val err = obj(r)["error"] as JObj
        assertTrue((err["message"] as JStr).value.contains("Mcp-Session-Id"))
        assertTrue("nothing ran", calls.isEmpty())
    }

    @Test fun a_request_with_an_unknown_session_id_is_refused() {
        val r = request(
            body = rpcBody("tools/list"),
            extra = sessionHeader("0123456789abcdef0123456789abcdef"),
        )
        assertEquals(404, r.status)
        assertTrue(calls.isEmpty())
    }

    @Test fun the_session_carries_every_later_request() {
        Client().use { c ->
            assertEquals(200, c.rpc("tools/list").status)
            assertEquals(200, c.call("""{"action":"timer","duration_ms":1000}""").status)
            assertEquals("the same session served both", 1, server.sessionCount)
        }
    }

    @Test fun delete_ends_the_session() {
        Client().use { c ->
            assertEquals(200, c.rpc("ping").status)
            assertEquals(200, c.wire.send(method = "DELETE", extra = sessionHeader(c.id)).status)
            assertEquals("the session is gone", 0, server.sessionCount)
            val after = c.wire.send(body = rpcBody("tools/list"), extra = sessionHeader(c.id))
            assertEquals("a deleted session is refused per the spec", 404, after.status)
        }
    }

    @Test fun delete_without_a_session_is_refused() {
        assertEquals(404, request(method = "DELETE").status)
        assertEquals(
            404,
            request(method = "DELETE", extra = sessionHeader("0123456789abcdef0123456789abcdef")).status,
        )
    }

    // ---- keep-alive ----

    @Test fun a_normal_response_does_not_close_the_connection() {
        Client().use { c ->
            val r = c.rpc("tools/list")
            assertNotEquals(
                "until #503 every response said this, and every exchange ended the connection",
                "close",
                r.headers["connection"]?.lowercase(),
            )
        }
    }

    @Test fun two_requests_share_one_connection_and_one_session() {
        Client().use { c ->
            val first = c.rpc("ping", id = "1")
            val second = c.rpc("tools/list", id = "2")
            assertEquals(200, first.status)
            assertEquals(200, second.status)
            assertTrue(first.body.contains("\"id\":1"))
            assertTrue(second.body.contains("\"id\":2"))
        }
    }

    @Test fun the_connection_closes_when_the_client_asks() {
        Client().use { c ->
            val r = c.wire.send(
                body = rpcBody("ping"),
                extra = sessionHeader(c.id),
                close = true,
            )
            assertEquals("close", r.headers["connection"]?.lowercase())
        }
    }

    @Test fun the_connection_closes_when_the_server_stops() {
        val c = Client()
        try {
            val r = c.wire.send(body = rpcBody("ping"), extra = sessionHeader(c.id))
            assertEquals(200, r.status)
            server.close()
            // The next write fails (or the read does) because the server closed the socket rather
            // than leaving a keep-alive thread to time out.
            var ended = false
            try {
                val after = c.wire.send(body = rpcBody("ping"), extra = sessionHeader(c.id))
                ended = after.status != 200
            } catch (_: Throwable) {
                ended = true
            }
            assertTrue("a stopped server ends its open connections", ended)
        } finally {
            c.close()
        }
    }

    // ---- the handshake ----

    @Test fun initialize_negotiates_a_version_and_advertises_tools() {
        val r = request(
            body = rpcBody(
                "initialize",
                """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}""",
            ),
        )
        assertEquals(200, r.status)
        val result = obj(r)["result"] as JObj
        assertEquals("2025-06-18", (result["protocolVersion"] as JStr).value)
        assertNotNull((result["capabilities"] as JObj)["tools"])
        assertEquals("hermes-vox-phone", ((result["serverInfo"] as JObj)["name"] as JStr).value)
    }

    @Test fun an_unknown_protocol_version_gets_the_one_we_speak() {
        val r = request(body = rpcBody("initialize", """{"protocolVersion":"2099-01-01"}"""))
        assertEquals(PhoneMcpServer.OURS, ((obj(r)["result"] as JObj)["protocolVersion"] as JStr).value)
    }

    @Test fun the_initialized_notification_is_acknowledged_with_no_body() {
        Client().use { c ->
            val r = c.wire.send(
                body = """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
                extra = sessionHeader(c.id),
            )
            assertEquals(202, r.status)
            assertTrue(r.body.isEmpty())
        }
    }

    @Test fun ping_answers_with_an_empty_result() {
        Client().use { c ->
            val r = c.rpc("ping")
            assertEquals(200, r.status)
            val result = obj(r)["result"] as JObj
            assertEquals("ping is optional to the SDK, and a real answer is better", 0, result.fields.size)
        }
    }

    @Test fun only_an_authenticated_initialize_is_the_handshake_the_phone_records() {
        val seen = ArrayList<Long>()
        server.close()
        server = PhoneMcpServer.start(
            bind = InetAddress.getLoopbackAddress(),
            tokenProvider = { token },
            handler = { "{}" },
            port = 0,
            log = { log.add(it) },
            onInitialize = { seen.add(it) },
        )

        request(bearer = null, body = rpcBody("initialize", """{"protocolVersion":"2025-06-18"}"""))
        assertTrue("an unauthenticated initialize is not a connection", seen.isEmpty())
        request(bearer = token.reversed(), body = rpcBody("initialize", """{"protocolVersion":"2025-06-18"}"""))
        assertTrue("a wrong token is not a connection", seen.isEmpty())

        Client().use { c ->
            assertEquals("one authenticated initialize is the evidence", 1, seen.size)
            c.rpc("tools/list")
            assertEquals("tools/list is not a handshake", 1, seen.size)
        }
    }

    @Test fun rotating_the_token_invalidates_the_old_one_on_the_next_request() {
        var current = token
        val fresh = "fedcba9876543210fedcba9876543210"
        server.close()
        server = PhoneMcpServer.start(
            bind = InetAddress.getLoopbackAddress(),
            tokenProvider = { current },
            handler = { "{}" },
            port = 0,
            log = { log.add(it) },
        )

        assertEquals(200, handshake(token).status)
        current = fresh
        assertEquals("the old credential stops working at rotation", 401, handshake(token).status)
        assertEquals(200, handshake(fresh).status)
    }

    // ---- tools ----

    @Test fun tools_list_is_one_phone_tool_with_the_decided_schema() {
        Client().use { c ->
            val r = c.rpc("tools/list")
            val tools = (obj(r)["result"] as JObj)["tools"] as JArr
            assertEquals(1, tools.items.size)
            val tool = tools.items[0] as JObj
            assertEquals("phone", (tool["name"] as JStr).value)
            val required = ((tool["inputSchema"] as JObj)["required"] as JArr).items.map { (it as JStr).value }
            assertEquals(listOf("action"), required)
        }
    }

    @Test fun tools_call_reaches_the_handler_and_returns_its_result_as_text() {
        Client().use { c ->
            val r = c.call("""{"action":"timer","duration_ms":60000}""")
            assertEquals(200, r.status)
            val result = obj(r)["result"] as JObj
            assertEquals(false, (result["isError"] as JBool).value)
            val content = (result["content"] as JArr).items[0] as JObj
            assertEquals("text", (content["type"] as JStr).value)
            assertEquals("""{"ok":true,"action":"timer","id":"timer-1"}""", (content["text"] as JStr).value)
            assertEquals(1, calls.size)
            assertEquals(60_000L, calls[0]["duration_ms"])
        }
    }

    @Test fun a_tool_call_with_no_arguments_is_an_empty_object_not_an_error() {
        Client().use { c ->
            val r = c.wire.send(
                body = rpcBody("tools/call", """{"name":"phone"}"""),
                extra = sessionHeader(c.id),
            )
            assertEquals(200, r.status)
            assertEquals(1, calls.size)
            assertTrue(calls[0].isEmpty())
        }
    }

    @Test fun an_unknown_tool_is_a_protocol_error_the_client_can_see() {
        Client().use { c ->
            val r = c.rpc("tools/call", """{"name":"shell","arguments":{}}""")
            val err = obj(r)["error"] as JObj
            assertEquals("-32602", (err["code"] as com.hermesvox.core.JNum).raw)
            assertTrue(calls.isEmpty())
        }
    }

    @Test fun an_unknown_method_is_a_json_rpc_error_not_a_500() {
        Client().use { c ->
            val r = c.rpc("tools/execute")
            assertEquals(200, r.status)
            val err = obj(r)["error"] as JObj
            assertEquals("-32601", (err["code"] as com.hermesvox.core.JNum).raw)
        }
    }

    @Test fun malformed_json_is_a_parse_error() {
        val r = request(body = "{not json")
        val err = obj(r)["error"] as JObj
        assertEquals("-32700", (err["code"] as com.hermesvox.core.JNum).raw)
    }

    @Test fun the_id_comes_back_in_the_same_json_type() {
        Client().use { c ->
            val numeric = c.rpc("ping", id = "42")
            assertTrue(numeric.body, numeric.body.contains("\"id\":42"))
            val textual = c.rpc("ping", id = "\"abc\"")
            assertTrue(textual.body, textual.body.contains("\"id\":\"abc\""))
        }
    }

    // ---- the tool surface over the wire (#503) ----

    /** Every card the engine raises; a group at Ask must raise one. */
    private class Surface : PhoneApproval.Surface {
        val raised = ArrayList<PhoneApproval.Card>()
        override fun raise(card: PhoneApproval.Card): Boolean {
            raised.add(card)
            return true
        }
    }

    /**
     * A server whose handler is a **real** engine over a recording fake platform, so the wire can
     * be asked what the tool surface actually does: list, run, refuse, and perform nothing.
     */
    private fun serveEngine(grants: PhoneGrants.Source): FakePhonePlatform {
        val platform = FakePhonePlatform()
        server.close()
        val engine = PhoneEngine(
            platform,
            grants = grants,
            approvalSurface = Surface(),
        )
        server = PhoneMcpServer.start(
            bind = InetAddress.getLoopbackAddress(),
            token = token,
            handler = { args -> engine.dispatch(args, 1_000L, 1_800_000_000_000L).json() },
            port = 0,
            log = { log.add(it) },
            grants = grants,
        )
        return platform
    }

    private fun actionEnum(c: Client): List<String> {
        val tools = (obj(c.rpc("tools/list"))["result"] as JObj)["tools"] as JArr
        val schema = (tools.items[0] as JObj)["inputSchema"] as JObj
        val action = (schema["properties"] as JObj)["action"] as JObj
        return (action["enum"] as JArr).items.map { (it as JStr).value }
    }

    private fun innerText(result: JObj): JObj =
        Json.parse(((result["content"] as JArr).items[0] as JObj).let { (it["text"] as JStr).value }) as JObj

    @Test fun tools_list_follows_the_grants_and_changes_when_they_do() {
        var timers = PhoneGrants.State.OFF
        serveEngine(PhoneGrants.Source { g ->
            if (g.id == "timers-alarms") timers else PhoneGrants.State.OFF
        })
        Client().use { c ->
            assertFalse("an Off group is absent", "timer" in actionEnum(c))
            assertTrue("the two that are always offered are still offered", "status" in actionEnum(c))

            timers = PhoneGrants.State.ASK
            assertTrue("Ask is present", "timer" in actionEnum(c))

            timers = PhoneGrants.State.ALLOW
            assertTrue("Allow is present", "timer" in actionEnum(c))

            timers = PhoneGrants.State.OFF
            assertFalse(
                "the sheet's promise: turning it off takes effect on the next call, no reconnect",
                "timer" in actionEnum(c),
            )
        }
    }

    @Test fun a_call_to_an_off_group_is_an_mcp_error_that_performs_nothing() {
        val platform = serveEngine(PhoneGrants.ALL_OFF)
        Client().use { c ->
            val r = c.call("""{"action":"timer","duration_ms":60000}""")
            assertEquals("the transport answered", 200, r.status)
            val result = obj(r)["result"] as JObj
            assertEquals(true, (result["isError"] as JBool).value)
            val inner = innerText(result)
            assertEquals(false, (inner["ok"] as JBool).value)
            assertEquals("not_granted", (inner["reason"] as JStr).value)
            assertTrue("the refusal is a sentence", (inner["detail"] as JStr).value.isNotBlank())
            assertTrue("nothing was armed: ${platform.called}", platform.called.isEmpty())
        }
    }

    @Test fun a_call_to_an_ask_group_is_an_error_with_a_card_until_it_is_approved() {
        val platform = serveEngine(PhoneGrants.Source { g ->
            if (g.id == "timers-alarms") PhoneGrants.State.ASK else PhoneGrants.State.OFF
        })
        Client().use { c ->
            assertTrue("Ask is advertised", "timer" in actionEnum(c))
            val r = c.call("""{"action":"timer","duration_ms":60000}""")
            val result = obj(r)["result"] as JObj
            assertEquals("asking is not success", true, (result["isError"] as JBool).value)
            val inner = innerText(result)
            assertEquals("approval-required", (inner["reason"] as JStr).value)
            assertNotNull("the mind needs the id for its second call", inner["approval_id"])
            assertTrue("no timer was armed on the way to the card", platform.performed.isEmpty())
        }
    }

    @Test fun a_performed_call_is_not_an_error() {
        serveEngine(PhoneGrants.Source { g ->
            if (g.id == "timers-alarms") PhoneGrants.State.ALLOW else PhoneGrants.State.OFF
        })
        Client().use { c ->
            val result = obj(c.call("""{"action":"timer","duration_ms":60000}"""))["result"] as JObj
            assertEquals(false, (result["isError"] as JBool).value)
            assertTrue((innerText(result)["ok"] as JBool).value)
        }
    }

    /**
     * A handler that throws is a described refusal, not a dead socket — and the session survives it.
     * The transport's job ends at "the client got a CallToolResult"; what the text and `isError`
     * say belongs to the tool surface and is pinned in `PhoneSurfaceTest`.
     */
    @Test fun a_handler_that_throws_inside_a_session_is_a_described_refusal() {
        val original = server
        val failing = PhoneMcpServer.start(
            bind = InetAddress.getLoopbackAddress(),
            token = token,
            handler = { throw IllegalStateException("boom") },
            port = 0,
            log = { log.add(it) },
        )
        try {
            server = failing
            Client().use { c ->
                val r = c.call("""{"action":"timer"}""")
                assertEquals("the socket survived the throw", 200, r.status)
                val result = obj(r)["result"] as JObj
                assertEquals("a refusal is an MCP error", true, (result["isError"] as JBool).value)
                val inner = Json.parse(
                    ((result["content"] as JArr).items[0] as JObj).let { (it["text"] as JStr).value }
                ) as JObj
                assertEquals(false, (inner["ok"] as JBool).value)
                assertEquals("unavailable", (inner["reason"] as JStr).value)
                // And the session is still usable: a throw is not a transport failure.
                assertEquals(200, c.rpc("ping").status)
            }
        } finally {
            failing.close()
            server = original
        }
    }
}
