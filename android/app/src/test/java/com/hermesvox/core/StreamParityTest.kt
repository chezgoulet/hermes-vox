package com.hermesvox.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The stream parity harness (#126). Every fixture under testdata/stream is replayed
 * through [HermesSession] by the app's own wait → poll loop, and the outcome must
 * equal the committed golden. The goldens were produced by the Go core (the
 * since-removed tools/streamdump) and matched by this Kotlin core before the Go code
 * was deleted, so they are the frozen spec of the streaming behaviour.
 *
 * Fixture format and how to add one: testdata/stream/README.md. To write a golden
 * for a NEW fixture, run with STREAM_GOLDEN_UPDATE=1 — it writes only the missing
 * goldens, never overwrites one; review the output before committing it.
 */
class StreamParityTest {

    private class Fixture(val name: String, val body: String) {
        var voice = false
        var hold = false
        var idleMs = 0
        var cancelAfter = 0
        var stored = ""
    }

    private fun parse(f: File): Fixture {
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
                else -> fail("${f.name}: unknown directive $key")
            }
        }
        return fx
    }

    private fun replay(fx: Fixture): Map<String, Any?> {
        val oldIdle = Streams.idleTimeoutMs
        if (fx.idleMs > 0) Streams.idleTimeoutMs = fx.idleMs
        val closed = LinkedBlockingQueue<Boolean>()
        val requests = ArrayList<Map<String, Any?>>()
        try {
            TestServer { r, w ->
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
        }
    }

    /** The VoiceController loop: wait, poll, until done (or barge in). */
    private fun turn(s: HermesSession, fx: Fixture, text: String, voice: Boolean): Map<String, Any?> {
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
    private fun plain(n: JNode): Any? = when (n) {
        is JObj -> LinkedHashMap<String, Any?>().apply { n.fields.forEach { (k, v) -> put(k, plain(v)) } }
        is JArr -> n.items.map { plain(it) }
        is JStr -> n.value
        is JBool -> n.value
        is JNum -> n.raw
        is JNull -> null
    }

    /** The golden layout: two-space indent, one key per line. */
    private fun pretty(v: Any?, ind: String): String = when (v) {
        is Map<*, *> -> if (v.isEmpty()) "{}" else v.entries.joinToString(",\n", "{\n", "\n$ind}") {
            "$ind  ${Json.encode(it.key.toString())}: ${pretty(it.value, "$ind  ")}"
        }
        is List<*> -> if (v.isEmpty()) "[]" else v.joinToString(",\n", "[\n", "\n$ind]") { "$ind  ${pretty(it, "$ind  ")}" }
        else -> Json.encode(v)
    }

    private fun fixtureDir(): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            val f = File(d, "testdata/stream")
            if (f.isDirectory) return f
            d = d.parentFile
        }
        error("testdata/stream not found above ${System.getProperty("user.dir")}")
    }

    @Test
    fun `every fixture replays to its golden`() {
        val fixtures = fixtureDir().listFiles { f -> f.name.endsWith(".sse") }!!.sortedBy { it.name }
        assertTrue("no fixtures found", fixtures.isNotEmpty())
        val drifted = ArrayList<String>()
        for (f in fixtures) {
            val golden = File(f.parentFile, f.name.removeSuffix(".sse") + ".golden.json")
            val got = plain(Json.parse(Json.encode(replay(parse(f)))))
            if (!golden.isFile && System.getenv("STREAM_GOLDEN_UPDATE") == "1") golden.writeText(pretty(got, "") + "\n")
            assertTrue("${f.name} has no golden (see testdata/stream/README.md)", golden.isFile)
            val want = plain(Json.parse(golden.readText()))
            if (got != want) drifted += "${f.name}\n--- kotlin\n${Json.encode(got)}\n--- golden\n${Json.encode(want)}"
        }
        assertEquals("drifted from the goldens:\n" + drifted.joinToString("\n\n"), 0, drifted.size)
    }

    companion object {
        const val TURN_BUDGET_MS = 10_000L
    }

    /**
     * #126 review: a turn that used a tool appended its answer to the "[tool-done]"
     * marker line, and plainText() drops any line beginning with that marker — so
     * such a turn spoke nothing. The Go core had the same bug and the original
     * goldens therefore pinned it; the port preserved it faithfully and it was
     * fixed deliberately, with the goldens regenerated.
     */
    @Test
    fun `a turn that used a tool speaks its answer`() {
        val out = plain(Json.parse(Json.encode(replay(parse(File(fixtureDir(), "tools.sse")))))) as Map<*, *>
        val turns = out["turns"] as List<*>
        val snapshots = (turns.last() as Map<*, *>)["snapshots"] as List<*>
        val last = snapshots.last() as Map<*, *>
        assertEquals("It is ten.", last["text"])
    }
}
