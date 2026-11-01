package com.hermesvox.core

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * One parsed Server-Sent Event from the Hermes /v1/responses stream, in the shape
 * the app reads (the JSON keys are the contract with VoiceController).
 *
 * The gateway emits the OpenAI Responses event shape (verified live 2026-08-25
 * against Hermes 0.20.0): response.created → output_item.added (message |
 * function_call | function_call_output) → output_text.delta → response.completed.
 * Mid-turn commentary (phase=commentary message items: progress preambles, text
 * written alongside tool calls) is surfaced as [commentary] — never as [delta] —
 * so the app can show it as progress without speaking it as the answer.
 */
internal class StreamEvent(val type: String) {
    var responseId = ""
    var itemType = ""   // "message" | "function_call" | "function_call_output"
    var itemId = ""
    var name = ""       // tool name (function_call)
    var arguments = ""  // raw JSON arguments (function_call)
    var output = ""     // tool output text (function_call_output)
    var delta = ""      // incremental ANSWER text
    var commentary = "" // mid-turn progress text — never spoken as the reply
    var text = ""       // accumulated assistant text so far
    var done = false    // true once the turn completed
    /** #282: the run's approval request, when this event is `approval.request`. */
    var approval: ApprovalRequest? = null

    fun toMap(): Map<String, Any?> {
        val map = linkedMapOf<String, Any?>(
            "type" to type, "response_id" to responseId, "item_type" to itemType, "item_id" to itemId,
            "name" to name, "arguments" to arguments, "output" to output, "delta" to delta,
            "commentary" to commentary, "text" to text, "done" to done,
        )
        // Only approval events carry the field: every other event's shape is pinned by
        // testdata/stream goldens, so an always-present null would rewrite them all.
        approval?.let { map["approval"] = it.toMap() }
        return map
    }
}

/**
 * One in-flight stream: written by its reader thread, drained by the app's poll
 * thread, cancelled from a third (barge-in).
 *
 * Every field below is guarded by [lock]. That is the issue #16 fix carried over
 * from Go: dispatch wrote the text, response id and done flag without the lock
 * while the poller read them.
 */
internal class StreamState {
    val lock = Any()
    var events = ArrayList<StreamEvent>()
    val text = StringBuilder()
    var respId = ""
    var done = false
    var err = ""
    /** ids of phase=commentary message items, so their deltas stay out of the reply. */
    val commentaryItems = HashSet<String>()
    /** The gateway's own reason when the turn ends in response.failed / an error
     *  event — surfaced instead of a generic "ended without completion". */
    var failure = ""

    /**
     * The push-side wake (#39): each buffered event, and the terminal done, offers a
     * token so a waiting poller wakes the moment data lands instead of sleeping a
     * fixed tick. Capacity 1 coalesces a burst into one wake; the poll drains the
     * whole batch. Not guarded by [lock] — the queue is its own synchronization.
     */
    val notify = ArrayBlockingQueue<Unit>(1)

    private var conn: HttpURLConnection? = null
    @Volatile
    var cancelled = false
        private set

    fun signal() { notify.offer(Unit) }

    /** Registers the live connection; false if the turn was already cancelled. */
    fun attach(c: HttpURLConnection): Boolean = synchronized(lock) {
        if (cancelled) false else { conn = c; true }
    }

    /** Barge-in: closing the connection is what aborts the gateway's generation. */
    fun cancel() {
        val c = synchronized(lock) { cancelled = true; conn }
        c?.disconnect()
    }
}

/** What one poll returns: the payload for the app, and the chain id it carried. */
internal class PollResult(val json: String, val responseId: String)

/**
 * The poll-drain surface. The app starts a stream, then alternates [wait] and
 * [poll] on its worker thread until a poll reports done, or calls [cancel].
 */
internal object Streams {

    /**
     * How long a stream may go without receiving ANY bytes. Every Hermes SSE stream
     * sends a ": keepalive" comment after 10s of silence, so a minute of nothing
     * means the connection is gone (a Wi-Fi to cellular hand-off, a tailnet drop),
     * not a slow tool call. It is the socket read timeout, so any bytes — keepalives
     * included — restart it. It also bounds the wait for response headers. The app's
     * wall-clock turn budget (StreamPollGate, H1) sits above this. Tests shorten it.
     */
    @Volatile
    var idleTimeoutMs = 60_000

    /** An unreachable gateway fails in seconds instead of the OS connect timeout. */
    const val CONNECT_TIMEOUT_MS = 10_000

    const val STALLED = "hermes stream: connection lost (no data from the gateway)"

    private val streams = HashMap<String, StreamState>()
    private var seq = 0

    private fun lookup(id: String): StreamState =
        synchronized(streams) { streams[id] }
            ?: throw HermesException("voice: no such stream \"$id\" (already drained or never started)")

    /**
     * Launches a streamed turn on its own thread and returns its id. The reader
     * consumes the SSE into the SAME registered state [poll] drains, so the events
     * the app renders are the events the gateway sent.
     */
    fun start(client: ResponsesClient, input: String, previousResponseId: String): String {
        val st = StreamState()
        val (id, n) = synchronized(streams) {
            seq++
            val id = "hvstream_${System.currentTimeMillis() * 1_000_000}_$seq"
            streams[id] = st
            id to seq
        }
        Thread({
            val err = try {
                consume(client, input, previousResponseId, st)
                ""
            } catch (e: Exception) {
                e.message ?: e.javaClass.name
            }
            synchronized(st.lock) {
                st.done = true
                st.err = err
            }
            // Wake a waiter on the terminal path too: a connection error can end the
            // turn without a buffered response.completed to signal it. The entry
            // stays registered until the done poll drains it — no final-poll race.
            st.signal()
        }, "hermes-stream-$n").apply { isDaemon = true }.start()
        return id
    }

    /**
     * The events buffered since the last poll, as one JSON payload:
     * {"done","error"(done only),"events"(null when none),"response_id","text","transcript"}.
     * The done poll retires the stream.
     */
    fun poll(id: String): PollResult {
        val st = lookup(id)
        val batch: List<StreamEvent>
        val done: Boolean
        val err: String
        val transcript: String
        val respId: String
        synchronized(st.lock) {
            batch = st.events
            st.events = ArrayList()
            done = st.done
            err = st.err
            transcript = st.text.toString()
            respId = st.respId
            // The pending wake belongs to the batch just drained; leaving it would
            // make the next wait return at once with nothing to read.
            st.notify.poll()
        }
        if (done) synchronized(streams) { streams.remove(id) }
        val payload = linkedMapOf<String, Any?>("done" to done)
        if (done) payload["error"] = err
        payload["events"] = if (batch.isEmpty()) null else batch.map { it.toMap() }
        payload["response_id"] = respId
        payload["text"] = plainText(transcript)
        payload["transcript"] = transcript
        return PollResult(Json.encode(payload), respId)
    }

    /**
     * Blocks until the stream has new data buffered, the turn completes, or
     * [deadlineMs] passes. True = poll now; false = an idle timeout (not an error).
     * The app calls this instead of sleeping a fixed tick, so deltas render as they
     * arrive.
     */
    fun wait(id: String, deadlineMs: Long): Boolean {
        val st = lookup(id)
        synchronized(st.lock) {
            if (st.events.isNotEmpty() || st.done) {
                st.notify.poll()
                return true
            }
        }
        return st.notify.poll(deadlineMs, TimeUnit.MILLISECONDS) != null
    }

    /**
     * Aborts an in-flight turn (the barge-in) AND retires its entry.
     *
     * M1 (0.5.3): cancel owns the removal. On a barge-in the app's worker leaves its
     * loop BEFORE the done poll that would otherwise retire the entry, so the state —
     * reply text, buffered events — leaked for the life of the process and an
     * interrupt-heavy session grew without bound. A later poll/wait on the id gets
     * the "no such stream" error the app already swallows; a second cancel is a no-op.
     */
    fun cancel(id: String) {
        val st = synchronized(streams) { streams.remove(id) } ?: return
        st.cancel()
    }

    /** Test hook: is [id] still registered? */
    internal fun isRegistered(id: String): Boolean = synchronized(streams) { streams.containsKey(id) }

    /**
     * The SSE consumer: POST stream:true, then frame and dispatch events into [st]
     * until the body ends. Throws the turn's failure; returns normally on a
     * completed turn (or a cancelled one — nobody is left to read the outcome).
     */
    private fun consume(client: ResponsesClient, input: String, previousResponseId: String, st: StreamState) {
        val body = client.buildBody(input, previousResponseId, true)
        val c = Entity.open("POST", Entity.url(client.baseURL, "/v1/responses"),
            client.entityHeaders() + mapOf("Content-Type" to "application/json", "Accept" to "text/event-stream"),
            CONNECT_TIMEOUT_MS, idleTimeoutMs)
        if (!st.attach(c)) return
        var clean = false
        try {
            c.doOutput = true
            c.setFixedLengthStreamingMode(body.size)
            try {
                c.connect()
            } catch (e: IOException) {
                if (st.cancelled) return
                throw e
            }
            // From here a read timeout is the idle watchdog firing while waiting for
            // the gateway's headers: the same "connection lost" as mid-stream.
            val code = try {
                c.outputStream.use { it.write(body) }
                c.responseCode
            } catch (e: IOException) {
                if (st.cancelled) return
                if (e is SocketTimeoutException) throw HermesException(STALLED)
                throw e
            }
            if (code != 200) throw HermesException("hermes stream ${Entity.status(c)}: ${Entity.readErrorBody(c)}")

            val framer = SseFramer { name, data -> dispatch(st, name, data) }
            var readErr: IOException? = null
            try {
                c.inputStream.use { ins ->
                    val lines = LineReader(ins)
                    while (true) framer.line(lines.next() ?: break)
                }
                clean = true
            } catch (e: IOException) {
                readErr = e
            }
            framer.flush()
            if (st.cancelled) return
            if (readErr is SocketTimeoutException) throw HermesException(STALLED)
            if (readErr != null) throw HermesException("hermes stream read: ${readErr.message ?: readErr.javaClass.name}")

            val completed: Boolean
            val failure: String
            val reply: String
            synchronized(st.lock) {
                completed = st.done
                failure = st.failure
                reply = plainText(st.text.toString())
            }
            if (!completed && failure.isNotEmpty()) throw HermesException(failure)
            if (reply.isEmpty() && !completed) throw HermesException("hermes stream: ended without completion")
        } finally {
            // A body read to its end goes back to the keep-alive pool (the next turn
            // skips the TCP + TLS handshake). Anything else is torn down.
            if (!clean) c.disconnect()
        }
    }

    /** Decodes one event payload and applies it to [st]. Undecodable payloads are dropped. */
    private fun dispatch(st: StreamState, evName: String, data: String) {
        val env = try { Envelope.decode(data) } catch (_: JsonException) { return }
        // Run events (`approval.request`) carry their name in `event`, OpenAI response
        // events carry it in `type`; the SSE `event:` line is the fallback for both.
        val name = env.type.ifEmpty { env.eventName.ifEmpty { evName } }
        val ev = StreamEvent(name)
        synchronized(st.lock) {
            when (name) {
                "response.created" -> env.response?.let { ev.responseId = it.id; st.respId = it.id }
                "response.output_item.added", "response.output_item.done" -> env.item?.let { item ->
                    ev.itemId = item.id
                    ev.itemType = item.type
                    when (item.type) {
                        "function_call" -> {
                            ev.name = item.name
                            ev.arguments = item.arguments?.raw.orEmpty().trim('"')
                            st.text.append("\n[tool:").append(ev.name).append(']')
                            ev.text = st.text.toString()
                        }
                        "function_call_output" -> {
                            ev.output = toolOutput(item.output)
                            // Close the marker line. Without the trailing newline the next
                            // output_text delta was appended to "[tool-done]" itself, so
                            // plainText() dropped that whole line and a turn that used a
                            // tool spoke nothing. The Go core had the same bug; the port
                            // preserved it faithfully, and this fixes it deliberately.
                            st.text.append("\n[tool-done]\n")
                            ev.text = st.text.toString()
                        }
                        "message" -> {
                            val commentary = item.phase == "commentary"
                            if (commentary) st.commentaryItems.add(item.id)
                            for (part in item.content) {
                                if (part.type == "output_text" && part.text.isNotEmpty() && name == "response.output_item.done") {
                                    if (commentary) ev.commentary += part.text else ev.delta = part.text
                                }
                            }
                            ev.text = st.text.toString()
                        }
                    }
                }
                "response.output_text.delta" -> {
                    ev.itemId = env.itemId
                    if (env.itemId in st.commentaryItems) {
                        ev.commentary = env.delta
                    } else {
                        ev.delta = env.delta
                        st.text.append(env.delta)
                    }
                    ev.text = st.text.toString()
                }
                "response.completed" -> {
                    env.response?.let { ev.responseId = it.id; st.respId = it.id }
                    ev.done = true
                    ev.text = st.text.toString()
                    st.done = true
                }
                "response.incomplete" -> {
                    // Stopped short (e.g. an output budget). Whatever was said is still the
                    // answer, so it completes when there is text; with nothing said it is
                    // a failure carrying the gateway's reason.
                    env.response?.takeIf { it.id.isNotEmpty() }?.let { ev.responseId = it.id; st.respId = it.id }
                    if (plainText(st.text.toString()).isNotEmpty()) {
                        ev.done = true
                        st.done = true
                    } else {
                        val reason = env.response?.incompleteReason.orEmpty()
                        st.failure = "hermes response " + if (reason.isEmpty()) "incomplete" else "incomplete: $reason"
                    }
                    ev.text = st.text.toString()
                }
                "response.failed", "error" -> {
                    var msg = env.message
                    env.response?.errorMessage?.takeIf { it.isNotEmpty() }?.let { msg = it }
                    if (msg.isEmpty()) msg = "the gateway reported a failure"
                    st.failure = "hermes response failed: $msg"
                    ev.text = st.text.toString()
                }
                // #282: the gateway's run event. The command is already redacted
                // upstream; this layer carries it without rewriting it. The controller
                // decides whether the request is still answerable (a request that
                // arrives after response.completed is ignored there).
                "approval.request" -> {
                    ev.approval = env.approval
                    ev.text = st.text.toString()
                }
                else -> ev.text = st.text.toString()
            }
            st.events.add(ev)
        }
        st.signal()
    }

    /** Readable text from a function_call_output's output: a string, or
     *  [{type:input_text,text:...}] as shipped live; anything else verbatim. */
    private fun toolOutput(n: JNode?): String = when (n) {
        null, is JNull -> ""
        is JStr -> n.value
        is JArr -> try {
            n.items.joinToString("") { Json.str(Json.elem(it), "text") }
        } catch (_: JsonException) {
            n.raw
        }
        else -> n.raw
    }

    /**
     * The spoken reply from the live transcript: drops the [tool:*] / [tool-done]
     * marker lines and blank lines, trims the rest. Line boundaries are kept (#23):
     * gluing lines joined "…done." to "Next…" and removed the pause the voice
     * engine takes at a break.
     */
    fun plainText(transcript: String): String =
        transcript.split("\n")
            .filterNot { it.startsWith("[tool:") || it.startsWith("[tool-done]") }
            .map { Entity.goTrimSpace(it) }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
}

/**
 * SSE framing: an event is its "event:"/"data:" lines up to a blank line; multiple
 * data lines join with "\n"; lines starting with ":" are comments (Hermes'
 * keepalive). A data line arriving while a complete JSON payload is already
 * buffered dispatches that payload first, which tolerates producers that omit the
 * blank separator.
 */
internal class SseFramer(private val dispatch: (String, String) -> Unit) {
    private var evName = ""
    private val data = ArrayList<String>()

    fun flush() {
        if (data.isNotEmpty()) {
            val payload = data.joinToString("\n")
            if (payload != "[DONE]" && payload.isNotEmpty()) dispatch(evName, payload)
        }
        evName = ""
        data.clear()
    }

    fun line(line: String) {
        when {
            line.isEmpty() -> flush()
            line.startsWith(":") -> {}
            line.startsWith("event:") -> {
                if (data.isNotEmpty()) flush()
                evName = Entity.goTrimSpace(line.removePrefix("event:"))
            }
            line.startsWith("data:") -> {
                val d = line.removePrefix("data:").removePrefix(" ")
                if (data.isNotEmpty() && Json.valid(data.joinToString("\n"))) {
                    val name = evName
                    flush()
                    evName = name
                }
                data.add(d)
            }
        }
    }
}

/** Splits a byte stream on '\n', dropping one trailing '\r' (bufio.ScanLines), with
 *  the Go scanner's 1 MiB cap on one line. */
internal class LineReader(private val ins: InputStream, private val maxLine: Int = 1024 * 1024) {
    private val buf = ByteArray(16 * 1024)
    private var pos = 0
    private var end = 0
    private var line = ByteArray(1024)

    /** The next line, or null at end of stream. A final unterminated line is returned. */
    fun next(): String? {
        var n = 0
        while (true) {
            if (pos == end) {
                end = ins.read(buf)
                pos = 0
                if (end < 0) {
                    end = 0
                    return if (n == 0) null else decode(n)
                }
            }
            val b = buf[pos++]
            if (b == '\n'.code.toByte()) return decode(n)
            if (n >= maxLine) throw IOException("SSE line exceeds $maxLine bytes")
            if (n == line.size) line = line.copyOf(minOf(line.size * 2, maxLine))
            line[n++] = b
        }
    }

    private fun decode(n: Int): String {
        val len = if (n > 0 && line[n - 1] == '\r'.code.toByte()) n - 1 else n
        return String(line, 0, len, Charsets.UTF_8)
    }
}

/**
 * The fields of one SSE payload, type-checked the way Go's json.Unmarshal into the
 * envelope struct did: a field of the wrong type drops the whole event.
 */
internal class Envelope(
    val type: String,
    val eventName: String,
    val itemId: String,
    val delta: String,
    val message: String,
    val item: Item?,
    val response: Response?,
    val approval: ApprovalRequest?,
) {
    class Part(val type: String, val text: String)
    class Item(val id: String, val type: String, val phase: String, val name: String,
               val arguments: JNode?, val output: JNode?, val content: List<Part>)
    class Response(val id: String, val errorMessage: String, val incompleteReason: String)

    companion object {
        /** A JSON `null` payload is the zero envelope (Go decoded it into the zero
         *  struct), so it still dispatches under the SSE event name. Fields read
         *  only for their type ("code", "status") are checked because Go checked them. */
        fun decode(data: String): Envelope {
            val o = when (val root = Json.parse(data)) {
                is JObj -> root
                is JNull -> null
                else -> throw JsonException("want object")
            }
            Json.str(o, "code")
            val item = Json.obj(o, "item")?.let { i ->
                Item(
                    id = Json.str(i, "id"), type = Json.str(i, "type"), phase = Json.str(i, "phase"),
                    name = Json.str(i, "name"), arguments = i["arguments"], output = i["output"],
                    content = Json.arr(i, "content").map { p ->
                        val e = Json.elem(p)
                        Part(Json.str(e, "type"), Json.str(e, "text"))
                    },
                )
            }
            val response = Json.obj(o, "response")?.let { r ->
                Json.str(r, "status")
                val err = Json.obj(r, "error")
                Json.str(err, "code")
                Response(
                    id = Json.str(r, "id"),
                    errorMessage = Json.str(err, "message"),
                    incompleteReason = Json.str(Json.obj(r, "incomplete_details"), "reason"),
                )
            }
            val type = Json.str(o, "type")
            val eventName = Json.str(o, "event")
            val approval = if (type == "approval.request" || eventName == "approval.request") {
                ApprovalRequest(
                    runId = Json.str(o, "run_id"),
                    requestId = Json.str(o, "request_id"),
                    command = Json.str(o, "command"),
                    description = Json.str(o, "description"),
                    reason = Json.str(o, "reason").ifEmpty { Json.str(o, "why") },
                    // A malformed choices field must not drop the whole request: with no
                    // renderable positive choice the surface still offers the safe deny.
                    choices = try {
                        Json.arr(o, "choices").mapNotNull { (it as? JStr)?.value }
                    } catch (_: JsonException) {
                        emptyList()
                    },
                    // Session-authority gateways fence the answer on this; plain ones omit it.
                    executionGeneration = (o?.get("execution_generation") as? JNum)?.raw?.toIntOrNull(),
                )
            } else null
            return Envelope(type, eventName, Json.str(o, "item_id"), Json.str(o, "delta"),
                Json.str(o, "message"), item, response, approval)
        }
    }
}
