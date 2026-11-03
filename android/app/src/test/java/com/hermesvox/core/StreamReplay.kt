package com.hermesvox.core

import org.junit.Assert.fail
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The shared stream replay harness: one `testdata/stream/<name>.sse` served by a fake
 * gateway, driven through [HermesSession] by the app's own wait → poll loop, and
 * recorded as plain values for comparison with a golden.
 *
 * Two routes are replayable. A responses fixture is one `POST /v1/responses` SSE body.
 * A runs fixture (`#! route runs`) is the route's real two-step shape: `POST /v1/runs`
 * admits the run (202 `{run_id}`), then `GET /v1/runs/run_parity/events` serves the
 * fixture body. Both end in the same [Streams] state, which is what makes a
 * cross-route golden comparison meaningful (RouteParityTest).
 *
 * Format and directives: testdata/stream/README.md.
 */
internal object StreamReplay {

    const val RUN_ID = "run_parity"

    class Fixture(val name: String, val body: String) {
        var voice = false
        var hold = false
        var idleMs = 0
        var cancelAfter = 0
        var stored = ""
        var route = TurnRoute.RESPONSES
    }

    fun fixtureDir(): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            val f = File(d, "testdata/stream")
            if (f.isDirectory) return f
            d = d.parentFile
        }
        error("testdata/stream not found above ${System.getProperty("user.dir")}")
    }

    fun parse(f: File): Fixture {
        var rest = f.readText()
        val directives = ArrayList<String>()
        while (rest.startsWith("#! ")) {
            val i = rest.indexOf('\n')
            directives += if (i >= 0) rest.substring(3, i) else rest.substring(3)
            rest = if (i >= 0) rest.substring(i + 1) else ""
        }
        val fx = Fixture(f.name.removeSuffix(".sse"), rest)
        for (d in directives) {
            val key = d.substringBefore(' ')
            val v = d.substringAfter(' ', "")
            when (key) {
                "voice" -> fx.voice = true
                "hold" -> fx.hold = true
                "idle_timeout_ms" -> fx.idleMs = v.toInt()
                "cancel_after_events" -> fx.cancelAfter = v.toInt()
                "stored" -> fx.stored = v
                "route" -> fx.route = when (v) {
                    "runs" -> TurnRoute.RUNS
                    "responses" -> TurnRoute.RESPONSES
                    else -> { fail("${f.name}: unknown route $v"); error("unreachable") }
                }
                else -> fail("${f.name}: unknown directive $key")
            }
        }
        return fx
    }

    fun replay(fx: Fixture): Map<String, Any?> {
        val oldIdle = Streams.idleTimeoutMs
        val oldRoute = StreamRoute.active
        if (fx.idleMs > 0) Streams.idleTimeoutMs = fx.idleMs
        StreamRoute.active = fx.route
        val closed = LinkedBlockingQueue<Boolean>()
        val requests = ArrayList<Map<String, Any?>>()
        try {
            TestServer { r, w ->
                when {
                    fx.route == TurnRoute.RUNS && r.method == "POST" && r.path == "/v1/runs" -> {
                        val b = Json.parse(r.body) as JObj
                        synchronized(requests) {
                            requests += linkedMapOf<String, Any?>(
                                "method" to r.method, "path" to r.path,
                                "authorization" to r.header("Authorization"),
                                "session_key" to r.header(Entity.SESSION_KEY_HEADER),
                                "model" to Json.str(b, "model"), "input" to Json.str(b, "input"),
                                "provider" to Json.str(b, "provider"),
                                "previous_response_id" to Json.str(b, "previous_response_id"),
                            )
                        }
                        w.send(202, "Accepted", """{"run_id":"$RUN_ID","status":"started"}""")
                    }
                    fx.route == TurnRoute.RUNS && r.method == "GET" && r.path.startsWith("/v1/runs/") -> {
                        synchronized(requests) {
                            requests += linkedMapOf<String, Any?>(
                                "method" to r.method, "path" to r.path,
                                "authorization" to r.header("Authorization"),
                                "session_key" to r.header(Entity.SESSION_KEY_HEADER),
                                "accept" to r.header("Accept"),
                            )
                        }
                        w.sse()
                        for (line in fx.body.split("\n")) w.write(line + "\n")
                        if (fx.hold) closed.put(w.holdUntilClientCloses(TURN_BUDGET_MS))
                    }
                    else -> {
                        val b = Json.parse(r.body) as JObj
                        val stream = (b["stream"] as? JBool)?.value ?: false
                        val rec = linkedMapOf<String, Any?>(
                            "method" to r.method, "path" to r.path,
                            "authorization" to r.header("Authorization"), "session_key" to r.header(Entity.SESSION_KEY_HEADER),
                        )
                        if (stream) rec["accept"] = r.header("Accept")
                        rec["model"] = Json.str(b, "model"); rec["input"] = Json.str(b, "input"); rec["stream"] = stream
                        rec["previous_response_id"] = Json.str(b, "previous_response_id")
                        synchronized(requests) { requests += rec }
                        if (!stream) { w.send(200, "OK", fx.stored); return@TestServer }
                        w.sse()
                        var i = 0
                        while (i < fx.body.length) {   // line by line, flushed after each
                            val j = fx.body.indexOf('\n', i).let { if (it < 0) fx.body.length else it + 1 }
                            w.write(fx.body.substring(i, j))
                            i = j
                        }
                        if (fx.hold) closed.put(w.holdUntilClientCloses(TURN_BUDGET_MS))
                    }
                }
            }.use { srv ->
                val s = HermesSession(srv.url, "parity-key", "hermes-agent")
                s.setSessionKey("agent:vox:parity")
                val out = linkedMapOf<String, Any?>("fixture" to fx.name)
                val turns = arrayListOf(turn(s, fx, "hi", fx.voice))
                if (fx.stored.isNotEmpty()) {
                    val stored = try {
                        mapOf("reply" to s.turnStored("/compress"), "error" to "")
                    } catch (e: Exception) {
                        mapOf("reply" to "", "error" to e.message)
                    }
                    out["stored"] = stored
                    turns += turn(s, fx, "after", false)
                }
                out["turns"] = turns
                if (fx.hold) out["held_connection_closed"] = closed.poll(TURN_BUDGET_MS, TimeUnit.MILLISECONDS) ?: false
                out["requests"] = synchronized(requests) { requests.toList() }
                return out
            }
        } finally {
            Streams.idleTimeoutMs = oldIdle
            StreamRoute.active = oldRoute
        }
    }

    /** The VoiceController loop: wait, poll, until done (or barge in). */
    fun turn(s: HermesSession, fx: Fixture, text: String, voice: Boolean): Map<String, Any?> {
        val sid = if (voice) s.voiceTurn(text) else s.startStream(text)
        val events = ArrayList<Any?>()
        val snapshots = ArrayList<Any?>()
        val deadline = System.currentTimeMillis() + TURN_BUDGET_MS
        while (System.currentTimeMillis() < deadline) {
            s.waitStream(sid, 50)
            val p = Json.parse(s.pollStreamJSON(sid)) as JObj
            (p["events"] as? JArr)?.items?.forEach { events += plain(it) }
            val snapshot = plain(p) as MutableMap<String, Any?>
            snapshot.remove("events")
            if (fx.cancelAfter > 0 && events.size >= fx.cancelAfter) {
                snapshots += snapshot
                s.cancelStream(sid)
                val pollFails = try { s.pollStreamJSON(sid); false } catch (_: Exception) { true }
                val secondOk = try { s.cancelStream(sid); true } catch (_: Exception) { false }
                return mapOf("events" to events, "snapshots" to snapshots,
                    "cancel" to mapOf("poll_after_cancel_fails" to pollFails, "second_cancel_ok" to secondOk))
            }
            if ((p["done"] as JBool).value) {
                snapshots += snapshot
                return mapOf("events" to events, "snapshots" to snapshots)
            }
        }
        fail("${fx.name}: turn did not finish within ${TURN_BUDGET_MS}ms")
        error("unreachable")
    }

    /** A JSON tree as plain Kotlin values (numbers by their text), for equality. */
    fun plain(n: JNode): Any? = when (n) {
        is JObj -> LinkedHashMap<String, Any?>().apply { n.fields.forEach { (k, v) -> put(k, plain(v)) } }
        is JArr -> n.items.map { plain(it) }
        is JStr -> n.value
        is JBool -> n.value
        is JNum -> n.raw
        is JNull -> null
    }

    /** The golden layout: two-space indent, one key per line. */
    fun pretty(v: Any?, ind: String): String = when (v) {
        is Map<*, *> -> if (v.isEmpty()) "{}" else v.entries.joinToString(",\n", "{\n", "\n$ind}") {
            "$ind  ${Json.encode(it.key.toString())}: ${pretty(it.value, "$ind  ")}"
        }
        is List<*> -> if (v.isEmpty()) "[]" else v.joinToString(",\n", "[\n", "\n$ind]") { "$ind  ${pretty(it, "$ind  ")}" }
        else -> Json.encode(v)
    }

    /** The done poll's snapshot (the rendered outcome), from a replay result. */
    @Suppress("UNCHECKED_CAST")
    fun lastSnapshot(out: Map<String, Any?>): Map<String, Any?> {
        val turns = out["turns"] as List<*>
        val snapshots = (turns.last() as Map<String, Any?>)["snapshots"] as List<*>
        return snapshots.last() as Map<String, Any?>
    }

    /** Every event the turn delivered, in order. */
    @Suppress("UNCHECKED_CAST")
    fun events(out: Map<String, Any?>): List<Map<String, Any?>> {
        val turns = out["turns"] as List<*>
        return (turns.last() as Map<String, Any?>)["events"] as List<Map<String, Any?>>
    }

    const val TURN_BUDGET_MS = 10_000L
}
