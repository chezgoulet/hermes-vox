package com.hermesvox

import com.hermesvox.core.JArr
import com.hermesvox.core.JBool
import com.hermesvox.core.JObj
import com.hermesvox.core.JStr
import com.hermesvox.core.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket

/**
 * #421 step 3: the MCP wire, proven on **loopback** so it runs in CI.
 *
 * The app binds the tailnet address; this binds `127.0.0.1`, which is what makes the socket test
 * possible and is exactly the address `PhoneMcpServer.start` takes. The rule that only the tailnet
 * address is ever passed is [Tailnet]'s and [PhoneMcpWiringTest]'s job.
 *
 * What is pinned is the shape the gateway's client actually speaks (Streamable HTTP, stateless
 * JSON-RPC): `initialize`, `notifications/initialized`, `tools/list`, `tools/call`, and a refusal
 * that is a *result* rather than a transport error.
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

    private class Response(val status: Int, val headers: Map<String, String>, val body: String)

    private fun request(
        method: String = "POST",
        target: String = PhoneMcpServer.PATH,
        body: String? = null,
        bearer: String? = token,
        extra: Map<String, String> = emptyMap(),
    ): Response {
        Socket(InetAddress.getLoopbackAddress(), server.port).use { s ->
            s.soTimeout = 5_000
            val out = s.getOutputStream()
            val sb = StringBuilder("$method $target HTTP/1.1\r\nHost: 127.0.0.1\r\n")
            bearer?.let { sb.append("Authorization: Bearer ").append(it).append("\r\n") }
            for ((k, v) in extra) sb.append(k).append(": ").append(v).append("\r\n")
            val bytes = (body ?: "").toByteArray(Charsets.UTF_8)
            if (method == "POST") sb.append("Content-Type: application/json\r\n")
            sb.append("Content-Length: ").append(bytes.size).append("\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.UTF_8))
            if (method == "POST") out.write(bytes)
            out.flush()
            val input = BufferedInputStream(s.getInputStream())
            val status = readLine(input)!!.split(' ')[1].toInt()
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val h = readLine(input).orEmpty()
                if (h.isEmpty()) break
                val i = h.indexOf(':')
                if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
            }
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
            }
            return Response(status, headers, buf.toString("UTF-8"))
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

    private fun rpc(method: String, params: String? = null, id: String = "1"): Response {
        val p = if (params == null) "" else ",\"params\":$params"
        return request(body = """{"jsonrpc":"2.0","id":$id,"method":"$method"$p}""")
    }

    private fun obj(r: Response): JObj = Json.parse(r.body) as JObj

    // ---- security ----

    @Test fun a_request_without_the_token_is_refused_and_nothing_runs() {
        val r = request(bearer = null, body = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        assertEquals(401, r.status)
        assertTrue(r.headers["www-authenticate"]!!.contains("Bearer"))
        assertTrue(calls.isEmpty())
    }

    @Test fun a_request_with_the_wrong_token_is_refused() {
        val r = request(bearer = token.reversed(), body = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        assertEquals(401, r.status)
        assertTrue(calls.isEmpty())
    }

    @Test fun the_token_is_never_logged() {
        request(bearer = null, body = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        assertTrue(log.isNotEmpty())
        for (line in log) assertFalse("a log line carried the token: $line", line.contains(token))
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
        assertEquals("POST", r.headers["allow"])
    }

    @Test fun head_is_mcp_shaped_so_the_gateway_probe_passes() {
        val r = request(method = "HEAD")
        assertEquals(200, r.status)
        assertEquals("application/json", r.headers["content-type"])
        assertTrue(r.body.isEmpty())
    }

    // ---- the handshake ----

    @Test fun initialize_negotiates_a_version_and_advertises_tools() {
        val r = rpc("initialize", """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}""")
        assertEquals(200, r.status)
        val result = obj(r)["result"] as JObj
        assertEquals("2025-06-18", (result["protocolVersion"] as JStr).value)
        assertNotNull((result["capabilities"] as JObj)["tools"])
        assertEquals("hermes-vox-phone", ((result["serverInfo"] as JObj)["name"] as JStr).value)
    }

    @Test fun an_unknown_protocol_version_gets_the_one_we_speak() {
        val r = rpc("initialize", """{"protocolVersion":"2099-01-01"}""")
        assertEquals(PhoneMcpServer.OURS, ((obj(r)["result"] as JObj)["protocolVersion"] as JStr).value)
    }

    @Test fun the_initialized_notification_is_acknowledged_with_no_body() {
        val r = request(body = """{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(202, r.status)
        assertTrue(r.body.isEmpty())
    }

    @Test fun ping_answers() {
        val r = rpc("ping")
        assertEquals(200, r.status)
        assertNotNull(obj(r)["result"])
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

        request(bearer = null, body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")
        assertTrue("an unauthenticated initialize is not a connection", seen.isEmpty())
        request(bearer = token.reversed(), body = """{"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")
        assertTrue("a wrong token is not a connection", seen.isEmpty())

        rpc("initialize", """{"protocolVersion":"2025-06-18"}""")
        assertEquals("one authenticated initialize is the evidence", 1, seen.size)
        rpc("tools/list")
        assertEquals("tools/list is not a handshake", 1, seen.size)
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

        assertEquals(200, rpc("tools/list").status)
        current = fresh
        assertEquals("the old credential stops working at rotation", 401, rpc("tools/list").status)
        assertEquals(200, request(bearer = fresh, body = """{"jsonrpc":"2.0","id":3,"method":"tools/list"}""").status)
    }

    // ---- tools ----

    @Test fun tools_list_is_one_phone_tool_with_the_decided_schema() {
        val r = rpc("tools/list")
        val tools = (obj(r)["result"] as JObj)["tools"] as JArr
        assertEquals(1, tools.items.size)
        val tool = tools.items[0] as JObj
        assertEquals("phone", (tool["name"] as JStr).value)
        val required = ((tool["inputSchema"] as JObj)["required"] as JArr).items.map { (it as JStr).value }
        assertEquals(listOf("action"), required)
    }

    @Test fun tools_call_reaches_the_handler_and_returns_its_result_as_text() {
        val r = request(
            body = """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"phone","arguments":{"action":"timer","duration_ms":60000}}}"""
        )
        assertEquals(200, r.status)
        val result = obj(r)["result"] as JObj
        assertEquals(false, (result["isError"] as JBool).value)
        val content = (result["content"] as JArr).items[0] as JObj
        assertEquals("text", (content["type"] as JStr).value)
        assertEquals("""{"ok":true,"action":"timer","id":"timer-1"}""", (content["text"] as JStr).value)
        assertEquals(1, calls.size)
        assertEquals(60_000L, calls[0]["duration_ms"])
    }

    @Test fun a_tool_call_with_no_arguments_is_an_empty_object_not_an_error() {
        val r = request(body = """{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"phone"}}""")
        assertEquals(200, r.status)
        assertEquals(1, calls.size)
        assertTrue(calls[0].isEmpty())
    }

    @Test fun an_unknown_tool_is_a_protocol_error_the_client_can_see() {
        val r = request(body = """{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"shell","arguments":{}}}""")
        val err = obj(r)["error"] as JObj
        assertEquals("-32602", (err["code"] as com.hermesvox.core.JNum).raw)
        assertTrue(calls.isEmpty())
    }

    @Test fun an_unknown_method_is_a_json_rpc_error_not_a_500() {
        val r = rpc("tools/execute")
        assertEquals(200, r.status)
        val err = obj(r)["error"] as JObj
        assertEquals("-32601", (err["code"] as com.hermesvox.core.JNum).raw)
    }

    @Test fun malformed_json_is_a_parse_error() {
        val r = request(body = "{not json")
        val err = obj(r)["error"] as JObj
        assertEquals("-32700", (err["code"] as com.hermesvox.core.JNum).raw)
    }

    @Test fun the_id_comes_back_in_the_same_json_type() {
        val numeric = request(body = """{"jsonrpc":"2.0","id":42,"method":"ping"}""")
        assertTrue(numeric.body, numeric.body.contains("\"id\":42"))
        val textual = request(body = """{"jsonrpc":"2.0","id":"abc","method":"ping"}""")
        assertTrue(textual.body, textual.body.contains("\"id\":\"abc\""))
    }

    @Test fun a_handler_that_throws_is_a_described_refusal_not_a_dead_socket() {
        val failing = PhoneMcpServer.start(
            bind = InetAddress.getLoopbackAddress(),
            token = token,
            handler = { throw IllegalStateException("boom") },
            port = 0,
        )
        try {
            Socket(InetAddress.getLoopbackAddress(), failing.port).use { s ->
                s.soTimeout = 5_000
                val body = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"phone","arguments":{"action":"timer"}}}"""
                val out = s.getOutputStream()
                out.write(("POST ${PhoneMcpServer.PATH} HTTP/1.1\r\nHost: h\r\nAuthorization: Bearer $token\r\n" +
                    "Content-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray())
                out.write(body.toByteArray())
                out.flush()
                val text = s.getInputStream().readBytes().toString(Charsets.UTF_8)
                // The refusal rides a normal result: isError stays false, and the text is a JSON
                // object the model can read (`ok:false` + a reason), not a transport failure.
                val payload = text.substringAfter("\r\n\r\n")
                val result = (Json.parse(payload) as JObj)["result"] as JObj
                assertEquals(false, (result["isError"] as JBool).value)
                val inner = Json.parse(((result["content"] as JArr).items[0] as JObj)
                    .let { (it["text"] as JStr).value }) as JObj
                assertEquals(false, (inner["ok"] as JBool).value)
                assertEquals("unavailable", (inner["reason"] as JStr).value)
            }
        } finally {
            failing.close()
        }
    }
}
