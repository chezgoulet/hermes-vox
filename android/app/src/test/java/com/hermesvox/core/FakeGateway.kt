package com.hermesvox.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A deterministic stand-in for the Hermes API server, so the live gateway suite
 * (#167) runs on every CI build instead of skipping on a missing key.
 *
 * It serves exactly what [HermesSession] calls, over the JDK's HttpServer on an
 * ephemeral loopback port (127.0.0.1:0) — nothing here knows about 8642:
 *   GET  /v1/health, GET /v1/models   (the shapes GatewayClient/ResponsesClient read)
 *   POST /v1/responses stream:false   a stored turn: reply + a resp_ id
 *   POST /v1/responses stream:true    SSE: response.created → output_text.delta(s)
 *                                     → response.completed, with the tool-progress
 *                                     item shapes when the turn asks for a tool
 *   DELETE /v1/responses/{id}         the /new reset
 *   GET  /api/model/options           the catalog shape GatewayClient returns raw
 *
 * The event names and field shapes are copied from the testdata/stream SSE fixtures
 * (the frozen spec of the gateway wire format), not invented: `item.type`, `item.name`,
 * `item.arguments`, `item.output[].text`, `item_id`, `delta`, and
 * `response.id`/`response.status`.
 *
 * The one piece of server state it models is the server-side chain: a response id
 * is the handle to the conversation state that produced it, so a request carrying
 * previous_response_id sees the codeword planted earlier on that chain. That is
 * what the suite's chain and /compress cases actually assert; the fake is not an
 * agent and does not try to be one.
 *
 * [Faults] exists so the suite can prove its own assertions can fail: a fake that
 * can only behave is a harness that proves nothing.
 */
internal class FakeGateway(private val faults: Faults = Faults.NONE) : AutoCloseable {

    enum class Faults {
        NONE,

        /**
         * Ends the SSE body after response.created and one delta, with no
         * response.completed — a gateway that drops the connection mid-turn. The
         * client tolerates it (it has partial text), so only the suite's own
         * "created AND completed" assertion catches it.
         */
        TRUNCATED_STREAM,
    }

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "fake-gateway").apply { isDaemon = true }
    }
    private val seq = AtomicInteger()
    /** response id → the codeword remembered at that point in its chain. */
    private val chains = ConcurrentHashMap<String, String>()

    init {
        server.executor = pool
        server.createContext("/v1/health") { ex -> guarded(ex) { health(ex) } }
        server.createContext("/v1/models") { ex -> guarded(ex) { models(ex) } }
        server.createContext("/api/model/options") { ex -> guarded(ex) { modelOptions(ex) } }
        server.createContext("/v1/responses") { ex -> guarded(ex) { responses(ex) } }
        server.start()
    }

    /** The base URL to hand [HermesSession] — ephemeral, loopback, no fixed port. */
    val url: String get() = "http://127.0.0.1:${server.address.port}"

    override fun close() {
        server.stop(0)
        pool.shutdownNow()
    }

    // ---- endpoints ----

    private fun health(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return reject(ex, 405)
        json(ex, 200, """{"status":"ok","platform":"fake","version":"0.0.0-fake"}""")
    }

    private fun models(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return reject(ex, 405)
        // The real OpenAI surface is deliberately thin: only hermes-agent (see
        // docs/HANDOFF-2026-08-28.md). ResponsesClient.ping only requires a 200.
        json(ex, 200, """{"object":"list","data":[{"id":"hermes-agent","object":"model","created":0,"owned_by":"hermes"}]}""")
    }

    private fun modelOptions(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return reject(ex, 405)
        json(ex, 200, """{"providers":[{"slug":"hermes","name":"Hermes","models":["hermes-agent"],"total_models":1,"is_current":true,"authenticated":true,"source":"fake","capabilities":[],"warning":null}]}""")
    }

    private fun responses(ex: HttpExchange) {
        when (ex.requestMethod) {
            "POST" -> storedOrStreamed(ex)
            "DELETE" -> deleteResponse(ex)
            else -> reject(ex, 405)
        }
    }

    private fun deleteResponse(ex: HttpExchange) {
        val id = ex.requestURI.path.substringAfterLast('/')
        if (id.isEmpty()) return reject(ex, 400)
        chains.remove(id)
        ex.sendResponseHeaders(204, -1)
        ex.close()
    }

    private fun storedOrStreamed(ex: HttpExchange) {
        val raw = ex.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
        val req = try { Json.parse(raw) as? JObj } catch (_: JsonException) { null }
            ?: return rejectJson(ex, 400, "request body is not a JSON object")
        val input = Json.str(req, "input")
        val prev = Json.str(req, "previous_response_id")
        val stream = (req["stream"] as? JBool)?.value ?: false

        // Server-side chain: the previous id selects the state this turn continues.
        val remembered = chains[prev].orEmpty()
        val codeword = CODEWORD.find(input)?.groupValues?.get(1) ?: remembered
        val id = "resp_fake_${seq.incrementAndGet()}"
        chains[id] = codeword

        if (stream) sse(ex, id, input) else stored(ex, id, input, codeword)
    }

    // ---- stored turn ----

    private fun stored(ex: HttpExchange, id: String, input: String, codeword: String) {
        val text = storedReply(input, codeword)
        // The exact fields ResponsesClient.parseResponse reads: id, output[].type,
        // output[].content[].{type,text,annotations}.
        val body = Json.encode(linkedMapOf<String, Any?>(
            "id" to id,
            "object" to "response",
            "status" to "completed",
            "output" to listOf(linkedMapOf<String, Any?>(
                "type" to "message",
                "id" to "msg_$id",
                "role" to "assistant",
                "status" to "completed",
                "content" to listOf(linkedMapOf<String, Any?>(
                    "type" to "output_text",
                    "text" to text,
                    "annotations" to emptyList<Any>(),
                )),
            )),
        ))
        json(ex, 200, body)
    }

    private fun storedReply(input: String, codeword: String): String = when {
        input.trim() == "/compress" -> "compacted"
        CODEPHRASE_QUESTION.containsMatchIn(input) -> codeword.ifEmpty { "no codeword" }
        input.contains("Remember the codeword", ignoreCase = true) -> "ok"
        input.contains("vox-live-turn-ok") -> "vox-live-turn-ok"
        else -> "ok"
    }

    // ---- streamed turn ----

    private fun sse(ex: HttpExchange, id: String, input: String) {
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.responseHeaders.add("Cache-Control", "no-cache")
        // length 0 = chunked; the body ends when this handler returns/closes.
        ex.sendResponseHeaders(200, 0)
        val out = ex.responseBody

        event(out, "response.created",
            """{"type":"response.created","response":{"id":"$id","status":"in_progress"}}""")

        if (faults == Faults.TRUNCATED_STREAM) {
            event(out, "response.output_text.delta",
                """{"type":"response.output_text.delta","item_id":"msg_$id","delta":"vox-live-"}""")
            out.flush()
            return   // no response.completed: the connection just ends
        }

        when {
            input.contains("echo vox-tool-stream-live", ignoreCase = true) -> {
                // shapes copied from testdata/stream/tools.sse
                event(out, "response.output_item.added",
                    """{"type":"response.output_item.added","item":{"id":"fc_1","type":"function_call","name":"terminal","arguments":"{\"command\":\"echo vox-tool-stream-live\"}"}}""")
                event(out, "response.output_item.added",
                    """{"type":"response.output_item.added","item":{"id":"fo_1","type":"function_call_output","output":[{"type":"input_text","text":"vox-tool-stream-live"}]}}""")
                event(out, "response.output_text.delta",
                    """{"type":"response.output_text.delta","item_id":"msg_$id","delta":"vox-tool-stream-live"}""")
            }
            input.contains("Count from 1 to 400", ignoreCase = true) -> {
                // The barge-in case cancels after ~3s; keep producing until the
                // client hangs up, so the cancel is what ends it.
                for (n in 1..400) {
                    event(out, "response.output_text.delta",
                        """{"type":"response.output_text.delta","item_id":"msg_$id","delta":"${jsonStringBody("$n\n")}"}""")
                    try { Thread.sleep(25) } catch (_: InterruptedException) { return }
                }
            }
            else -> {
                // Two deltas, like testdata/stream/normal.sse; together they spell
                // the reply the suite expects.
                val text = if (input.contains("vox-live-stream-ok")) "vox-live-stream-ok" else "ok"
                val pivot = if (text.length > 4) 4 else text.length
                for (part in listOf(text.substring(0, pivot), text.substring(pivot))) {
                    if (part.isEmpty()) continue
                    event(out, "response.output_text.delta",
                        """{"type":"response.output_text.delta","item_id":"msg_$id","delta":"${jsonStringBody(part)}"}""")
                }
            }
        }

        event(out, "response.completed",
            """{"type":"response.completed","response":{"id":"$id","status":"completed"}}""")
        // Real Hermes streams end with the OpenAI sentinel; SseFramer drops it.
        out.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
        out.flush()
    }

    // ---- wire plumbing ----

    private fun guarded(ex: HttpExchange, block: () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
            // the client hung up (a barge-in, a truncated read) — expected
        } finally {
            ex.close()
        }
    }

    private fun json(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun reject(ex: HttpExchange, code: Int) {
        val body = """{"error":{"message":"fake gateway: method ${ex.requestMethod} not allowed"}}"""
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun rejectJson(ex: HttpExchange, code: Int, message: String) {
        json(ex, code, """{"error":{"message":"fake gateway: ${jsonStringBody(message)}"}}""")
    }

    private fun event(out: OutputStream, name: String, data: String) {
        out.write("event: $name\ndata: $data\n\n".toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** Escapes a plain string for embedding inside a hand-written JSON payload. */
    private fun jsonStringBody(s: String): String = Json.encode(s).removeSurrounding("\"")

    companion object {
        /** "Remember the codeword: cobalt-falcon-7." */
        private val CODEWORD = Regex("""codeword:\s*([A-Za-z0-9-]+)""", RegexOption.IGNORE_CASE)

        /** The two questions the chain cases ask. */
        private val CODEPHRASE_QUESTION =
            Regex("""what codeword|codeword i gave you|codeword did i""", RegexOption.IGNORE_CASE)
    }
}
