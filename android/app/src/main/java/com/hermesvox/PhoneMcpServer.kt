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
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * PhoneMcpServer — the phone as an MCP server on the tailnet (#421 step 3, session fixed in #503).
 *
 * This is the *mind-initiated* path: the agent decides it needs the phone to do something, calls the
 * `phone` tool, and gets a **result**. It is deliberately separate from [LocalActions]' user path,
 * which never leaves the device — different initiators, different guarantees (#421's third comment).
 *
 * ## The wire the client actually speaks
 *
 * The Hermes gateway does not call this endpoint by hand: it hands the URL to the MCP Python SDK's
 * **streamable HTTP** client (`mcp.client.streamable_http`). That client has a session lifecycle, and
 * a server that ignores it is not merely old-fashioned — it is unusable:
 *
 *  * `initialize` must answer with an **`Mcp-Session-Id`** response header. The SDK stores it and
 *    sends it on every later request; without one the SDK treats the request as a one-off.
 *  * After `notifications/initialized` the SDK opens the session's **GET** stream. Answering `405`
 *    is allowed by the spec and the SDK gives up after its retries, so this server stays a POST-only
 *    endpoint but says so honestly instead of pretending.
 *  * The gateway's keepalive is `tools/list` and then `ping`. A server whose session dies between
 *    two of those is charged a reconnect; five in a row and the gateway **parks** the server, which
 *    is exactly what happened before this file had sessions (#503): every response said
 *    `Connection: close`, every exchange ended the connection, and no tool was ever registered.
 *
 * So a session here is a real, server-side, per-connection-attempt identity:
 *
 *  * `initialize` mints an unpredictable id ([SecureRandom], 128 bits, hex) and returns it;
 *  * every later request must carry it — a request without it (or with one this server no longer
 *    knows) is `404` per the spec, which is the client's cue to initialize again;
 *  * `DELETE /mcp` ends the session; an idle session expires on its own;
 *  * `notifications/initialized` → `202`, `ping` → `{}` (never `-32601`: the SDK latches "ping
 *    unsupported" and downgrades its probe, which is a worse signal than a real answer),
 *    `tools/list`, `tools/call`;
 *  * HTTP/1.1 **keep-alive is honoured** — a response ends the connection only when the client asked
 *    (`Connection: close`), the request was HTTP/1.0 without `keep-alive`, or this server is
 *    shutting down. A session lives on the server, not on a socket, so the phone closing an idle
 *    TCP connection costs the client a reconnect, never a session.
 *
 * **No SSE.** A server-initiated message would need a live GET stream; the phone has nothing to push
 * mid-call. Requests answer with `Content-Type: application/json`, which the client accepts.
 *
 * ## Security, because this listens on a network
 *
 *  * **One bind address**: the tailnet address [Tailnet] picked, never `0.0.0.0` and never a LAN
 *    interface. The caller passes it; [start] refuses an any-local address.
 *  * **One route**: `/mcp`. Everything else is 404, and nothing on disk is reachable.
 *  * **A bearer credential on every request**, compared in constant time ([PhoneMcpToken]). A
 *    request without one is 401 and is logged *without* the credential.
 *  * **Two credentials are valid** (#452). The phone's own token ([tokenProvider]) authenticates a
 *    host that is not the one this phone talks to. The API key of the connection in force
 *    ([keyProvider]) authenticates the agent this phone already has: the key it uses for its own
 *    turns is the key it presents here. That is what removes the pairing step — nothing has to be
 *    copied out of the sheet and into the gateway's config, and no credential has to travel in the
 *    setup turn. Both are read per request, so rotating the token or re-saving the key in Settings
 *    takes effect on the next request.
 *  * **A bound on the body and on connections**, so a hostile peer cannot make the phone allocate
 *    or exhaust its threads. A session is also bounded ([MAX_SESSIONS]) and expires when idle.
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
    /**
     * The API key of the connection this phone talks to — the credential the phone itself presents
     * to the gateway on every turn (#452). Read on every request, from the same store the turn path
     * resolves it from, so a key re-saved in Settings is in force on the next request. Empty when no
     * connection is configured; then only [tokenProvider] authenticates.
     *
     * It is never logged, never echoed and never stored by this class: the phone already holds it.
     */
    private val keyProvider: () -> String,
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
    /**
     * The owner's grants, read by `tools/list` on every call (#429). Never a captured snapshot: the
     * advertised `enum` has to lose an action the moment the owner revokes its group.
     */
    private val grants: PhoneGrants.Source,
    /**
     * The labels of the folders the owner granted, read by `tools/list` on every call. The
     * `folder` enum is this list — the grants are made in a system picker while the phone is
     * running, so a captured list would advertise a folder that is gone (or omit one just given).
     */
    private val folderLabels: () -> List<String> = { emptyList() },
) : AutoCloseable {

    /** The port the OS gave us (a fixed one in the app: the gateway's config names it). */
    val port: Int get() = server.localPort

    /** `http://<host>:<port>/mcp` — what the gateway's `mcp_servers` entry must name. */
    val url: String = "http://${server.inetAddress.hostAddress ?: ""}:$port$PATH"

    @Volatile private var closed = false
    private val threads = AtomicInteger(0)
    private val live = AtomicInteger(0)
    private val acceptor: Thread

    /** One Streamable HTTP session: an id, and when the client last used it. */
    private class Session(val id: String, val protocolVersion: String) {
        @Volatile var lastSeenMs: Long = System.currentTimeMillis()
    }

    /** Live sessions by id, and the lock that owns them. Never read outside it. */
    private val sessions = LinkedHashMap<String, Session>()
    private val sessionsLock = Any()

    /** Open connections, so [close] can end a keep-alive socket instead of waiting for it. */
    private val sockets = LinkedHashSet<Socket>()
    private val socketsLock = Any()

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

    /** Sessions the server is holding, for the log line when it stops. */
    val sessionCount: Int get() = synchronized(sessionsLock) { sessions.size }

    override fun close() {
        if (closed) return
        closed = true
        try { server.close() } catch (_: IOException) { }
        // A keep-alive connection would otherwise hold a thread until its idle timeout. Closing the
        // socket is also what makes `Connection: close` on an in-flight response true.
        val open = synchronized(socketsLock) { sockets.toList().also { sockets.clear() } }
        for (s in open) try { s.close() } catch (_: IOException) { }
    }

    // ---- the listener ----

    private fun acceptLoop() {
        while (!closed) {
            val socket = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                break
            }
            if (live.incrementAndGet() > MAX_CONNECTIONS) {
                // Say why, and close: a peer that opens more connections than the client needs must
                // not be able to park the phone's threads. The gateway holds one or two.
                live.decrementAndGet()
                note("event=phone-mcp refused reason=too-many-connections")
                try { socket.close() } catch (_: IOException) { }
                continue
            }
            synchronized(socketsLock) { sockets.add(socket) }
            val t = Thread({ handle(socket) }, "phone-mcp-req-${threads.incrementAndGet()}")
            t.isDaemon = true
            t.start()
        }
    }

    /**
     * Serve one connection until the client closes it, asks for it, or this server stops.
     *
     * The loop is the keep-alive: a response no longer ends the socket, so the gateway's next
     * `tools/list`/`ping` reuses the same connection and — more importantly — the same session.
     */
    private fun handle(socket: Socket) {
        var servedOnSocket = 0
        try {
            socket.soTimeout = READ_TIMEOUT_MS
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val out = BufferedOutputStream(socket.getOutputStream())
            while (!closed) {
                val heads = try {
                    readRequest(input)
                } catch (_: SocketTimeoutException) {
                    // An idle keep-alive connection. Not an error, and not worth a log line once
                    // this connection has done its work.
                    if (servedOnSocket == 0) note("event=phone-mcp refused reason=read-timeout")
                    break
                } ?: break
                try { onRequest("${heads.method} ${heads.target}", System.currentTimeMillis()) } catch (_: Throwable) { }
                if (heads.target == PATH) servedOnSocket++
                if (heads.chunked) {
                    // This endpoint answers `Content-Length` only. A chunked body cannot be drained
                    // reliably, so the connection ends rather than desynchronising the next request.
                    note("event=phone-mcp refused reason=chunked")
                    write(
                        out, heads,
                        Reply(411, rpcError(null, -32600, "send Content-Length; chunked bodies are not served")),
                        keepAlive = false,
                    )
                    break
                }
                val body = readBody(input, heads.headers)
                if (body == null) {
                    write(out, heads, Reply(413, """{"ok":false,"reason":"too-large"}"""), keepAlive = false)
                    break
                }
                val reply = route(heads, body)
                // Keep the connection unless the client said otherwise, this server is stopping,
                // or the reply cannot leave the stream in a knowable state (a malformed request
                // line already handled above, and an oversized body just above).
                val keepAlive = !closed && !heads.closeRequested
                write(out, heads, reply, keepAlive)
                if (!keepAlive) break
            }
        } catch (_: SocketTimeoutException) {
            note("event=phone-mcp refused reason=read-timeout")
        } catch (_: Throwable) {
            note("event=phone-mcp error=connection")
        } finally {
            synchronized(socketsLock) { sockets.remove(socket) }
            live.decrementAndGet()
            try { socket.close() } catch (_: IOException) { }
        }
    }

    // ---- routing ----

    /** One parsed request: the first line and the headers. */
    private class Heads(
        val method: String,
        val target: String,
        val version: String,
        val headers: Map<String, String>,
    ) {
        /** True when the client asked this response to be the last on the connection. */
        val closeRequested: Boolean
            get() {
                val connection = headers["connection"]?.lowercase().orEmpty()
                if (connection.contains("close")) return true
                // HTTP/1.0 keeps alive only when it says so; HTTP/1.1 keeps alive by default.
                return version == "HTTP/1.0" && !connection.contains("keep-alive")
            }

        val bearer: String? get() = headers["authorization"]

        /** Header names are lowercased when read; the wire spelling is `Mcp-Session-Id`. */
        val sessionId: String? get() = headers[SESSION_HEADER.lowercase()]
        val chunked: Boolean
            get() = headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true
    }

    /** One response: status, body, and the headers that are not HTTP plumbing. */
    private class Reply(
        val status: Int,
        val body: String = "",
        val json: Boolean = true,
        val headOnly: Boolean = false,
        val headers: Map<String, String> = emptyMap(),
    )

    /** Authenticate, route, and answer. Never throws: a bad request is a described refusal. */
    private fun route(heads: Heads, body: ByteArray): Reply {
        if (!authorized(heads.bearer)) {
            // Log the shape, never the credential.
            note(
                "event=phone-mcp refused reason=no-token method=${heads.method} " +
                    "path=${if (heads.target == PATH) "<mcp>" else "<other>"}"
            )
            return Reply(401, """{"ok":false,"reason":"unauthorized"}""",
                headers = mapOf("WWW-Authenticate" to "Bearer"))
        }
        if (heads.target != PATH) {
            return Reply(404, """{"ok":false,"reason":"not-found"}""")
        }
        return when (heads.method) {
            "POST" -> dispatchPost(body, heads)
            // The gateway probes with HEAD before connecting; an MCP content type on a 2xx is
            // what tells it this is an endpoint rather than a web page.
            "HEAD" -> Reply(200, "", headOnly = true)
            // We push nothing mid-call, so there is no stream to open. 405 is the spec's own
            // answer, and the SDK stops asking after this.
            "GET" -> Reply(
                405, """{"ok":false,"reason":"no-sse"}""",
                headers = mapOf("Allow" to "POST, DELETE"),
            )
            "DELETE" -> endSession(heads.sessionId)
            else -> Reply(
                405, """{"ok":false,"reason":"method"}""",
                headers = mapOf("Allow" to "POST, DELETE"),
            )
        }
    }

    /**
     * `DELETE /mcp`: the client is done with this session.
     *
     * Per the spec, a session the server does not know is `404`; a known one is retired here, so a
     * later request carrying the id is refused rather than answered from a session the client
     * believes it closed.
     */
    private fun endSession(id: String?): Reply {
        if (id.isNullOrEmpty()) {
            note("event=phone-mcp refused reason=no-session method=DELETE")
            return Reply(404, rpcError(null, -32600, "DELETE needs Mcp-Session-Id"))
        }
        val ended = synchronized(sessionsLock) { sessions.remove(id) != null }
        if (!ended) {
            note("event=phone-mcp refused reason=unknown-session method=DELETE")
            return Reply(404, rpcError(null, -32600, "no such session"))
        }
        note("event=phone-mcp session=ended")
        return Reply(200, """{"ok":true,"ended":true}""", headers = mapOf(SESSION_HEADER to id))
    }

    // ---- JSON-RPC ----

    /**
     * The (reply) for one POST body.
     *
     * `initialize` is the one method that arrives without a session and mints one; everything else
     * must name a session this server still holds. That is the spec's rule, and it is what makes the
     * gateway's keepalive see a healthy session instead of a new one every 30 seconds.
     */
    private fun dispatchPost(body: ByteArray, heads: Heads): Reply {
        val text = try {
            String(body, Charsets.UTF_8)
        } catch (_: Throwable) {
            return Reply(400, rpcError(null, -32700, "parse error"))
        }
        if (text.isBlank()) return Reply(400, rpcError(null, -32700, "empty request"))
        val root = try {
            Json.parse(text)
        } catch (_: Exception) {
            note("event=phone-mcp refused reason=parse")
            return Reply(400, rpcError(null, -32700, "parse error"))
        }
        val o = root as? JObj
            ?: return Reply(200, rpcError(null, -32600, "invalid request: one JSON-RPC object, not a batch"))
        val idNode = o["id"]
        val id = jsonId(idNode)
        val method = (o["method"] as? JStr)?.value.orEmpty()
        val params = o["params"] as? JObj

        // A response to a request this server would have sent. We send none, but a client that
        // posts one is not making a request, and the spec's answer is 202.
        if (method.isEmpty() && (o["result"] != null || o["error"] != null)) return Reply(202, "")

        if (method == "initialize") {
            val requested = (params?.get("protocolVersion") as? JStr)?.value.orEmpty()
            val negotiated = if (requested in SUPPORTED_VERSIONS) requested else OURS
            val session = openSession(negotiated)
            // The handshake is the proof of connection. It is recorded here, after authentication
            // and before the reply, so a client that is turned away by the token never marks the
            // phone connected.
            try { onInitialize(System.currentTimeMillis()) } catch (_: Throwable) { }
            served += 1
            return Reply(
                200,
                """{"jsonrpc":"2.0","id":$id,"result":${initializeResult(requested)}}""",
                headers = mapOf(SESSION_HEADER to session.id),
            )
        }

        // A notification has no id. The client sends `notifications/initialized`; nothing is owed
        // back, and 202 is what the SDK expects for it.
        if (idNode == null) {
            // The session is still required: a notification without one belongs to no connection.
            if (session(heads.sessionId) == null) return noSession(method)
            return Reply(202, "")
        }

        val session = session(heads.sessionId) ?: return noSession(method)
        val result: String = when (method) {
            "ping" -> "{}"
            "tools/list" -> Json.encode(
                linkedMapOf("tools" to listOf(PhoneTool.schema(grants, folderLabels())))
            )
            "tools/call" -> when (val reply = callTool(params)) {
                is ToolReply.Fail -> return Reply(
                    200, rpcError(id, reply.code, reply.message),
                    headers = mapOf(SESSION_HEADER to session.id),
                )
                is ToolReply.Ok -> reply.result
            }
            else -> return Reply(
                200, rpcError(id, -32601, "method not found: $method"),
                headers = mapOf(SESSION_HEADER to session.id),
            )
        }
        served += 1
        return Reply(
            200,
            """{"jsonrpc":"2.0","id":$id,"result":$result}""",
            headers = mapOf(SESSION_HEADER to session.id),
        )
    }

    /** The refusal a request earns when it names no session, or one this server has forgotten. */
    private fun noSession(method: String): Reply {
        note("event=phone-mcp refused reason=no-session method=${method.ifEmpty { "<response>" }}")
        return Reply(
            404,
            rpcError(
                null,
                -32600,
                "this endpoint requires Mcp-Session-Id after initialize; call initialize first",
            ),
        )
    }

    /** A `tools/call` either produces a result envelope or a protocol error. */
    private sealed class ToolReply {
        class Ok(val result: String) : ToolReply()
        class Fail(val code: Int, val message: String) : ToolReply()
    }

    /**
     * `tools/call`: the arguments object in, the handler's result JSON out as a text block.
     *
     * `isError` is the three-way signal the mind needs, and it is read from the result itself: a
     * performed action is `isError:false`; a refusal — `not_granted`, `approval-required`,
     * `permission`, `unavailable`, … — is `isError:true`, with the human sentence in the JSON's
     * `detail` and the stable word in `reason`. An HTTP 500, an empty result, or a refusal dressed
     * as success would each leave the mind unable to tell "you are not allowed" from "it did not
     * work" from "done".
     */
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
        // The result is the handler's own JSON, and `ok` is the only field that decides `isError`:
        // the transport must not out-vote the phone about whether something happened.
        val ok = runCatching { (Json.parse(resultJson) as? JObj)?.get("ok") as? JBool }
            .getOrNull()?.value ?: false
        return ToolReply.Ok(
            Json.encode(
                linkedMapOf<String, Any?>(
                    "content" to listOf(
                        linkedMapOf<String, Any?>("type" to "text", "text" to resultJson),
                    ),
                    "isError" to !ok,
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
     * A JSON-RPC *error* is reserved for protocol mistakes (an unknown tool, a bad envelope, a
     * missing session). It is never used for the phone refusing to act: that is a result with
     * `ok:false` and `isError:true`, so the model can read the reason instead of a transport
     * failure.
     */
    private fun rpcError(id: String?, code: Int, message: String): String =
        """{"jsonrpc":"2.0","id":${id ?: "null"},"error":${Json.encode(linkedMapOf("code" to code, "message" to message))}}"""

    /** The `initialize` result. Echoes a version we implement; otherwise names ours. */
    internal fun initializeResult(requested: String): String = Json.encode(
        linkedMapOf<String, Any?>(
            "protocolVersion" to if (requested in SUPPORTED_VERSIONS) requested else OURS,
            "capabilities" to linkedMapOf(
                // No `listChanged`: the client re-lists on its own schedule anyway, and claiming a
                // change notification we never send would be a promise we cannot keep.
                "tools" to linkedMapOf("listChanged" to false),
            ),
            "serverInfo" to linkedMapOf(
                "name" to "hermes-vox-phone",
                "version" to SERVER_VERSION,
            ),
            "instructions" to
                "This is the phone the conversation is happening on. Use the `phone` tool to act on " +
                "it; every call returns what actually happened. Only the actions the owner has " +
                "granted are offered; a refusal names its reason and means nothing was performed, " +
                "unless it carries `outcome_uncertain:true` — then the action may still complete " +
                "and `status` says what did.",
        )
    )

    // ---- sessions ----

    /** A new session for one `initialize`. The id is minted here and never reused. */
    private fun openSession(protocolVersion: String): Session {
        val now = System.currentTimeMillis()
        synchronized(sessionsLock) {
            expireSessions(now)
            while (sessions.size >= MAX_SESSIONS) {
                // Oldest first: `LinkedHashMap` preserves insertion order, and a client that has
                // not touched a session in the whole expiry window is the one to lose.
                val oldest = sessions.entries.minByOrNull { it.value.lastSeenMs } ?: break
                sessions.remove(oldest.key)
            }
            val session = Session(newSessionId(), protocolVersion)
            session.lastSeenMs = now
            sessions[session.id] = session
            return session
        }
    }

    /** The session [id] names, touched, or null when there is none (expired included). */
    private fun session(id: String?): Session? {
        if (id.isNullOrEmpty()) return null
        val now = System.currentTimeMillis()
        return synchronized(sessionsLock) {
            expireSessions(now)
            sessions[id]?.also { it.lastSeenMs = now }
        }
    }

    /** Drop every session the client has not used for [SESSION_IDLE_MS]. */
    private fun expireSessions(now: Long) {
        sessions.entries.removeAll { now - it.value.lastSeenMs > SESSION_IDLE_MS }
    }

    // ---- HTTP plumbing ----

    private fun readRequest(input: InputStream): Heads? {
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
        return Heads(
            method = parts[0].uppercase(),
            target = parts[1],
            version = parts.getOrNull(2)?.uppercase().orEmpty(),
            headers = headers,
        )
    }

    /** The body, `Content-Length` bytes. Null means "too large to serve" (413, then close). */
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

    /**
     * Either credential opens the door: the phone's own token, or the API key of the connection this
     * phone talks to (#452). Both comparisons are always performed — neither result decides whether
     * the other is computed — so the work done here (and the single 401 that follows a refusal) says
     * nothing about which credential was tried. A rejected request gets the same status, the same
     * body and the same log line whatever the reason.
     */
    private fun authorized(header: String?): Boolean {
        val presented = header?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring("Bearer ".length)?.trim()
            .orEmpty()
        if (presented.isEmpty()) return false
        val asPhoneToken = PhoneMcpToken.matches(presented, tokenProvider())
        val asConnectionKey = PhoneMcpToken.matches(presented, keyProvider())
        return asPhoneToken or asConnectionKey
    }

    private fun write(out: OutputStream, heads: Heads, reply: Reply, keepAlive: Boolean) {
        val bytes = reply.body.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(reply.status).append(' ').append(reason(reply.status)).append("\r\n")
        if (reply.json && (bytes.isNotEmpty() || reply.headOnly)) {
            sb.append("Content-Type: application/json\r\n")
        }
        sb.append("Content-Length: ").append(bytes.size).append("\r\n")
        // HTTP/1.1 keeps alive unless told otherwise, so the only header worth sending is the one
        // that ends the connection — or the one HTTP/1.0 needs to keep it.
        if (!keepAlive) sb.append("Connection: close\r\n")
        else if (heads.version == "HTTP/1.0") sb.append("Connection: keep-alive\r\n")
        for ((k, v) in reply.headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (!reply.headOnly && bytes.isNotEmpty()) out.write(bytes)
        out.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        411 -> "Length Required"
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

        /** The Streamable HTTP session header. Sent by `initialize`, required after it. */
        const val SESSION_HEADER = "Mcp-Session-Id"

        /**
         * Protocol revisions this server implements. Unknown requests get [OURS].
         *
         * The revision decides the *shape* of the transport, so this list is documentation of what
         * was implemented, not a claim about clients: every revision listed has the same session
         * requirement, which is why one implementation serves all of them.
         */
        val SUPPORTED_VERSIONS = setOf("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")

        /** The revision we answer with when the client asks for one we do not know. */
        const val OURS = "2025-06-18"

        /**
         * How long an unused session is kept. Longer than any keepalive interval a client uses (the
         * gateway's is seconds), so an idle session is never the reason a probe fails; short enough
         * that a phone which was away for a while does not hold identities nobody will use.
         */
        const val SESSION_IDLE_MS = 30 * 60_000L

        /** Sessions held at once. A phone serves one gateway; this is slack, not a load target. */
        const val MAX_SESSIONS = 8

        /**
         * How long an idle keep-alive connection waits for its next request. Closing it costs the
         * client a TCP reconnect, never a session, so this can be modest.
         */
        private const val READ_TIMEOUT_MS = 180_000

        /** Connections served at once. The gateway holds one or two; this is a bound, not a target. */
        private const val MAX_CONNECTIONS = 16

        private const val MAX_BODY_BYTES = 256 * 1024
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_LINE = 8 * 1024

        /** 128 bits from the platform CSPRNG, hex — unpredictable, and safe in a header. */
        private val random = SecureRandom()

        private fun newSessionId(): String {
            val bytes = ByteArray(16)
            random.nextBytes(bytes)
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            return sb.toString()
        }

        private const val HEX = "0123456789abcdef"

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
            grants: PhoneGrants.Source = PhoneGrants.ALL_OFF,
            folderLabels: () -> List<String> = { emptyList() },
        ): PhoneMcpServer = start(
            bind = bind,
            tokenProvider = { token },
            handler = handler,
            port = port,
            log = log,
            grants = grants,
            folderLabels = folderLabels,
        )

        /**
         * Bind one address and start serving, reading both credentials per request: the phone's own
         * token from [tokenProvider] and the connection's API key from [keyProvider] (#452).
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
            grants: PhoneGrants.Source = PhoneGrants.ALL_OFF,
            keyProvider: () -> String = { "" },
            folderLabels: () -> List<String> = { emptyList() },
        ): PhoneMcpServer {
            require(!bind.isAnyLocalAddress) { "refusing to bind 0.0.0.0 / :: — pick the tailnet address" }
            require(tokenProvider().isNotEmpty()) { "a bearer token is required" }
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(bind, port), 8)
            return PhoneMcpServer(
                socket, tokenProvider, keyProvider, handler, log, onRequest, onInitialize, grants,
                folderLabels,
            )
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
