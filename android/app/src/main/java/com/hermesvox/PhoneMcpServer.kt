package com.hermesvox

import com.hermesvox.core.JArr
import com.hermesvox.core.JBool
import com.hermesvox.core.JNode
import com.hermesvox.core.JNull
import com.hermesvox.core.JNum
import com.hermesvox.core.JObj
import com.hermesvox.core.JStr
import com.hermesvox.core.Json
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * PhoneMcpServer — the phone as an MCP server on the tailnet (#421 step 3).
 *
 * This is the *mind-initiated* path: the agent decides it needs the phone to do something, calls the
 * `phone` tool, and gets a **result**. It is deliberately separate from [LocalActions]' user path,
 * which never leaves the device — different initiators, different guarantees (#421's third comment).
 *
 * ## The wire we implement, and why it is exactly this
 *
 * The gateway speaks **Streamable HTTP** (MCP 2025-03-26 and later) over a stateless JSON-RPC POST
 * endpoint; it does not need SSE from us. So this server is the simplest correct MCP endpoint:
 *
 *  * `POST /mcp` with a single JSON-RPC 2.0 object;
 *  * `initialize` → a result with a negotiated `protocolVersion` and the `tools` capability;
 *  * `notifications/initialized` → `202 Accepted`, no body (the client only wants the acknowledgement);
 *  * `tools/list` → one tool ([PhoneTool.schema]);
 *  * `tools/call` → `content:[{type:"text", text:<result JSON>}]`;
 *  * `ping` → `{}`;
 *  * anything else → a JSON-RPC error, never a 500.
 *
 * **No `Mcp-Session-Id`.** With no session id the SDK client never opens the GET stream, and every
 * call is independent — which is what the phone needs, because the phone can restart (or the call can
 * end) between two calls. The state that matters lives in [PhoneEngine], not in a session.
 *
 * **No SSE.** A server-initiated message would need a live GET stream; the phone has nothing to push
 * mid-call. Requests answer with `Content-Type: application/json`, which the client accepts.
 *
 * ## Security, because this listens on a network
 *
 *  * **One bind address**: the tailnet address [Tailnet] picked, never `0.0.0.0` and never a LAN
 *    interface. The caller passes it; [start] refuses an any-local address.
 *  * **One route**: `/mcp`. Everything else is 404, and nothing on disk is reachable.
 *  * **A bearer token on every request**, compared in constant time, generated once and stored
 *    encrypted ([PhoneMcpToken]). A request without it is 401 and is logged *without* the token.
 *  * **A bound on the body**, so a hostile peer cannot make the phone allocate.
 *
 * The server contains no Android API, so [PhoneMcpServerTest] runs it on loopback in CI.
 */
class PhoneMcpServer private constructor(
    private val server: ServerSocket,
    /**
     * Read on every request, not captured once: rotating the token in Settings invalidates the
     * old credential immediately without closing the listener.
     */
    private val tokenProvider: () -> String,
    private val handler: PhoneToolHandler,
    private val log: (String) -> Unit,
    /** (method + path, wall-clock millis) — the setup sheet's "last request" line. */
    private val onRequest: (String, Long) -> Unit,
    /**
     * Seen a valid `initialize` from an authenticated client. This is the *only* evidence the
     * setup sheet accepts for **Connected** (#421 step 5): the gateway really connected, rather
     * than the agent merely saying it edited its config.
     */
    private val onInitialize: (Long) -> Unit,
) : AutoCloseable {

    /** The port the OS gave us (a fixed one in the app: the gateway's config names it). */
    val port: Int get() = server.localPort

    /** `http://<host>:<port>/mcp` — what the gateway's `mcp_servers` entry must name. */
    val url: String = "http://${server.inetAddress.hostAddress ?: ""}:$port$PATH"

    @Volatile private var closed = false
    private val threads = AtomicInteger(0)
    private val acceptor: Thread

    init {
        server.soTimeout = 250
        acceptor = Thread({ acceptLoop() }, "phone-mcp-${server.localPort}").apply {
            isDaemon = true
            start()
        }
    }

    /** Requests handled, for the log line the controller writes when the server stops. */
    @Volatile var served: Int = 0
        private set

    override fun close() {
        if (closed) return
        closed = true
        try { server.close() } catch (_: IOException) { }
    }

    // ---- the listener ----

    private fun acceptLoop() {
        while (!closed) {
            val socket = try {
                server.accept()
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                break
            }
            // One thread per connection, bounded by the accept loop's own rate: a peer that holds a
            // connection open cannot stop the next call from being answered.
            val t = Thread({ handle(socket) }, "phone-mcp-req-${threads.incrementAndGet()}")
            t.isDaemon = true
            t.start()
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = READ_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val heads = readHeaders(input) ?: return
            val (method, target, headers) = heads
            try { onRequest("$method $target", System.currentTimeMillis()) } catch (_: Throwable) { }
            val authorized = authorized(headers["authorization"])
            if (!authorized) {
                // Log the shape, never the credential.
                note("event=phone-mcp refused reason=no-token method=$method path=${if (target == PATH) "<mcp>" else "<other>"}")
                respond(socket.getOutputStream(), 401, """{"ok":false,"reason":"unauthorized"}""",
                    extra = mapOf("WWW-Authenticate" to "Bearer"))
                return
            }
            if (target != PATH) {
                respond(socket.getOutputStream(), 404, """{"ok":false,"reason":"not-found"}""")
                return
            }
            when (method) {
                "POST" -> {
                    val body = readBody(input, headers) ?: run {
                        respond(socket.getOutputStream(), 413, """{"ok":false,"reason":"too-large"}""")
                        return
                    }
                    val (status, payload) = dispatch(body)
                    respond(socket.getOutputStream(), status, payload)
                }
                // The gateway probes with HEAD before connecting; an MCP content type on a 2xx is
                // what tells it this is an endpoint rather than a web page.
                "HEAD" -> respond(socket.getOutputStream(), 200, "", jsonContentType = true, headOnly = true)
                "GET" -> respond(
                    socket.getOutputStream(), 405, """{"ok":false,"reason":"method"}""",
                    extra = mapOf("Allow" to "POST"),
                )
                else -> respond(
                    socket.getOutputStream(), 405, """{"ok":false,"reason":"method"}""",
                    extra = mapOf("Allow" to "POST"),
                )
            }
        } catch (_: java.net.SocketTimeoutException) {
            note("event=phone-mcp refused reason=read-timeout")
        } catch (_: Throwable) {
            note("event=phone-mcp error=connection")
        } finally {
            try { socket.close() } catch (_: IOException) { }
        }
    }

    // ---- JSON-RPC ----

    /** The (status, body) for one request body. Never throws: a bad request is a JSON-RPC error. */
    internal fun dispatch(body: ByteArray): Pair<Int, String> {
        val text = try {
            String(body, Charsets.UTF_8)
        } catch (_: Throwable) {
            return 400 to rpcError(null, -32700, "parse error")
        }
        if (text.isBlank()) return 400 to rpcError(null, -32700, "empty request")
        val root = try {
            Json.parse(text)
        } catch (_: Exception) {
            note("event=phone-mcp refused reason=parse")
            return 400 to rpcError(null, -32700, "parse error")
        }
        val o = root as? JObj
            ?: return 200 to rpcError(null, -32600, "invalid request: one JSON-RPC object, not a batch")
        val idNode = o["id"]
        val id = jsonId(idNode)
        val method = (o["method"] as? JStr)?.value.orEmpty()
        if (method.isEmpty()) return 200 to rpcError(id, -32600, "invalid request: no method")
        // The handshake is the proof of connection. It is recorded here, after authentication and
        // before the reply, so a client that is turned away by the token never marks the phone
        // connected.
        if (method == "initialize") {
            try { onInitialize(System.currentTimeMillis()) } catch (_: Throwable) { }
        }
        val params = o["params"] as? JObj

        // A notification has no id. The client sends `notifications/initialized`; nothing is owed
        // back, and 202 is what the SDK expects for it.
        if (idNode == null) {
            if (method == "notifications/initialized" || method.startsWith("notifications/")) return 202 to ""
            return 202 to ""
        }

        val result: String = when (method) {
            "initialize" -> {
                val requested = (params?.get("protocolVersion") as? JStr)?.value.orEmpty()
                initializeResult(requested)
            }
            "ping" -> "{}"
            "tools/list" -> Json.encode(linkedMapOf("tools" to listOf(PhoneTool.schema())))
            "tools/call" -> when (val reply = callTool(params)) {
                is ToolReply.Fail -> return 200 to rpcError(id, reply.code, reply.message)
                is ToolReply.Ok -> reply.result
            }
            else -> return 200 to rpcError(id, -32601, "method not found: $method")
        }
        served += 1
        return 200 to """{"jsonrpc":"2.0","id":$id,"result":$result}"""
    }

    /** A `tools/call` either produces a result envelope or a protocol error. */
    private sealed class ToolReply {
        class Ok(val result: String) : ToolReply()
        class Fail(val code: Int, val message: String) : ToolReply()
    }

    /** `tools/call`: the arguments object in, the handler's result JSON out as a text block. */
    private fun callTool(params: JObj?): ToolReply {
        val name = (params?.get("name") as? JStr)?.value.orEmpty()
        if (name != PhoneTool.NAME) {
            note("event=phone-mcp refused reason=unknown-tool")
            return ToolReply.Fail(-32602, "unknown tool: $name")
        }
        val argsNode = params?.get("arguments")
        val args = when (argsNode) {
            null, is JNull -> emptyMap()
            is JObj -> plainObject(argsNode)
            else -> return ToolReply.Fail(-32602, "arguments must be an object")
        }
        val resultJson = try {
            handler.call(args)
        } catch (t: Throwable) {
            note("event=phone-mcp error=handler")
            PhoneResult.refused(
                (args["action"] as? String).orEmpty(),
                PhoneReason.UNAVAILABLE,
                "the phone failed to run that action",
            ).json()
        }
        // `isError` stays false: a described refusal is a legitimate result the mind reasons about,
        // not a transport failure. (Hermes counts `{"error":...}` payloads as server errors, which is
        // why the refusal vocabulary is `ok`/`reason`, never `error`.)
        return ToolReply.Ok(
            Json.encode(
                linkedMapOf<String, Any?>(
                    "content" to listOf(
                        linkedMapOf<String, Any?>("type" to "text", "text" to resultJson),
                    ),
                    "isError" to false,
                )
            )
        )
    }

    /** The id as JSON text, so a numeric id stays numeric. A missing id is `null`. */
    private fun jsonId(node: JNode?): String = when (node) {
        null, is JNull -> "null"
        is JNum -> node.raw
        is JStr -> Json.encode(node.value)
        else -> "null"
    }

    /**
     * A JSON-RPC *error* is reserved for protocol mistakes (an unknown tool, a bad envelope). It is
     * never used for the phone refusing to act: that is a result with `ok:false`, so the model can
     * read the reason instead of a transport failure.
     */
    private fun rpcError(id: String?, code: Int, message: String): String =
        """{"jsonrpc":"2.0","id":${id ?: "null"},"error":${Json.encode(linkedMapOf("code" to code, "message" to message))}}"""

    /** The `initialize` result. Echoes a version we implement; otherwise names ours. */
    internal fun initializeResult(requested: String): String = Json.encode(
        linkedMapOf<String, Any?>(
            "protocolVersion" to if (requested in SUPPORTED_VERSIONS) requested else OURS,
            "capabilities" to linkedMapOf(
                "tools" to linkedMapOf("listChanged" to false),
            ),
            "serverInfo" to linkedMapOf(
                "name" to "hermes-vox-phone",
                "version" to SERVER_VERSION,
            ),
            "instructions" to
                "This is the phone the conversation is happening on. Use the `phone` tool to act on " +
                "it; every call returns what actually happened.",
        )
    )

    // ---- HTTP plumbing ----

    private fun readHeaders(input: InputStream): Triple<String, String, Map<String, String>>? {
        val line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val headers = LinkedHashMap<String, String>()
        var total = 0
        while (true) {
            val h = readLine(input) ?: return null
            if (h.isEmpty()) break
            total += h.length
            if (total > MAX_HEADER_BYTES) return null
            val idx = h.indexOf(':')
            if (idx <= 0) continue
            headers[h.substring(0, idx).trim().lowercase()] = h.substring(idx + 1).trim()
        }
        return Triple(parts[0].uppercase(), parts[1], headers)
    }

    private fun readBody(input: InputStream, headers: Map<String, String>): ByteArray? {
        val length = headers["content-length"]?.toIntOrNull() ?: return ByteArray(0)
        if (length < 0 || length > MAX_BODY_BYTES) return null
        val out = ByteArrayOutputStream(length.coerceAtLeast(16))
        val buf = ByteArray(8192)
        var left = length
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size, left))
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
        return out.toByteArray()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().removeSuffix("\r")
            if (sb.length >= MAX_LINE) return null
            sb.append(c.toChar())
        }
    }

    private fun authorized(header: String?): Boolean {
        val presented = header?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring("Bearer ".length)?.trim()
            .orEmpty()
        return PhoneMcpToken.matches(presented, tokenProvider())
    }

    private fun respond(
        out: OutputStream,
        status: Int,
        body: String,
        jsonContentType: Boolean = true,
        headOnly: Boolean = false,
        extra: Map<String, String> = emptyMap(),
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
        if (jsonContentType) sb.append("Content-Type: application/json\r\n")
        sb.append("Content-Length: ").append(bytes.size).append("\r\n")
        sb.append("Connection: close\r\n")
        for ((k, v) in extra) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (!headOnly) out.write(bytes)
        out.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        else -> "Error"
    }

    private fun note(line: String) {
        try { log(line) } catch (_: Throwable) { }
    }

    companion object {
        /** The one path the server answers. */
        const val PATH = "/mcp"

        /** The port the app asks for, so the gateway's config can name it once. */
        const val PORT = 8765

        const val SERVER_VERSION = "0.9.1"

        /** Protocol revisions this server implements. Unknown requests get [OURS]. */
        val SUPPORTED_VERSIONS = setOf("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")

        /** The revision we answer with when the client asks for one we do not know. */
        const val OURS = "2025-06-18"

        private const val READ_TIMEOUT_MS = 10_000
        private const val MAX_BODY_BYTES = 256 * 1024
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_LINE = 8 * 1024

        /**
         * Bind one address and start serving. [bind] must be a single address — the tailnet one —
         * and an any-local address is refused here, not left to the caller's discipline.
         */
        fun start(
            bind: InetAddress,
            token: String,
            handler: PhoneToolHandler,
            port: Int = PORT,
            log: (String) -> Unit = {},
        ): PhoneMcpServer = start(
            bind = bind,
            tokenProvider = { token },
            handler = handler,
            port = port,
            log = log,
        )

        /**
         * Bind one address and start serving, reading the bearer from [tokenProvider] per request.
         * [bind] must be a single address — the tailnet one — and an any-local address is refused
         * here, not left to the caller's discipline.
         */
        fun start(
            bind: InetAddress,
            tokenProvider: () -> String,
            handler: PhoneToolHandler,
            port: Int = PORT,
            log: (String) -> Unit = {},
            onRequest: (String, Long) -> Unit = { _, _ -> },
            onInitialize: (Long) -> Unit = {},
        ): PhoneMcpServer {
            require(!bind.isAnyLocalAddress) { "refusing to bind 0.0.0.0 / :: — pick the tailnet address" }
            require(tokenProvider().isNotEmpty()) { "a bearer token is required" }
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(bind, port), 8)
            return PhoneMcpServer(socket, tokenProvider, handler, log, onRequest, onInitialize)
        }

        /** Converts a JSON object into the plain map the tool layer reads. */
        internal fun plainObject(o: JObj): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            for ((k, v) in o.fields) out[k] = plain(v)
            return out
        }

        private fun plain(v: JNode): Any? = when (v) {
            is JStr -> v.value
            is JNum -> v.raw.toLongOrNull() ?: v.raw.toDoubleOrNull()
            is JBool -> v.value
            is JNull -> null
            is JObj -> plainObject(v)
            is JArr -> v.items.map { plain(it) }
        }
    }
}

/** What the MCP server calls for one `tools/call`. The app's implementation is [LocalActions]. */
fun interface PhoneToolHandler {
    /** The result JSON, already rendered by [PhoneResult]. */
    fun call(arguments: Map<String, Any?>): String
}
