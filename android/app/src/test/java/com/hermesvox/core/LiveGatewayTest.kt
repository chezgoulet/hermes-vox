package com.hermesvox.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live integration against a REAL Hermes gateway (the same entity the phone fronts):
 * a turn, the previous_response_id chain, the SSE stream, tool progress, barge-in,
 * and /compress on the streamed chain. Ported from the Go voice/integration_live_test.go.
 *
 * Skipped unless BOTH are set:
 *   HERMES_VOX_LIVE=1
 *   HERMES_VOX_HERMES_API_KEY=<API_SERVER_KEY>   (secret; from your environment)
 * Optional: HERMES_VOX_HERMES_URL (default http://127.0.0.1:8642),
 *           HERMES_VOX_HERMES_MODEL (default hermes-agent),
 *           HERMES_VOX_HERMES_SESSION_KEY (rides every turn, as on a multi-user install).
 *
 *   HERMES_VOX_LIVE=1 HERMES_VOX_HERMES_API_KEY=… ./gradlew testDebugUnitTest --tests '*LiveGatewayTest'
 */
class LiveGatewayTest {

    private fun env(k: String) = System.getenv(k).orEmpty()

    private fun live(): HermesSession {
        assumeTrue("live integration disabled (set HERMES_VOX_LIVE=1)", env("HERMES_VOX_LIVE") == "1")
        val key = env("HERMES_VOX_HERMES_API_KEY")
        assumeTrue("no HERMES_VOX_HERMES_API_KEY in env", key.isNotEmpty())
        val s = HermesSession(env("HERMES_VOX_HERMES_URL").ifEmpty { "http://127.0.0.1:8642" }, key,
            env("HERMES_VOX_HERMES_MODEL").ifEmpty { "hermes-agent" })
        s.setSessionKey(env("HERMES_VOX_HERMES_SESSION_KEY"))
        return s
    }

    /** Drains a stream the way VoiceController does; returns every event and the done poll. */
    private fun drain(s: HermesSession, sid: String, budgetMs: Long = 180_000): Pair<List<JObj>, JObj> {
        val events = ArrayList<JObj>()
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            s.waitStream(sid, 100)
            val p = Json.parse(s.pollStreamJSON(sid)) as JObj
            (p["events"] as? JArr)?.items?.forEach { events += it as JObj }
            if ((p["done"] as JBool).value) return events to p
        }
        throw AssertionError("no done within ${budgetMs / 1000}s")
    }

    @Test
    fun `a stored turn returns a reply and a response id`() {
        val s = live()
        s.ping()
        val reply = s.turnStored("Health probe. Reply with exactly: vox-live-turn-ok")
        assertTrue(reply, reply.contains("vox-live-turn-ok"))
        assertTrue(s.lastID, s.lastID.startsWith("resp_"))
    }

    @Test
    fun `the server-side chain recalls a planted fact`() {
        val s = live()
        s.turnStored("Remember the codeword: cobalt-falcon-7. Reply ok.")
        val reply = s.turnStored("What codeword did I tell you? Reply with just the codeword.")
        assertTrue("chain broken: $reply", reply.contains("cobalt-falcon-7"))
    }

    @Test
    fun `a streamed turn yields created, deltas and completed`() {
        val s = live()
        val (events, done) = drain(s, s.startStream("Stream probe. Reply with exactly: vox-live-stream-ok"))
        val types = events.map { Json.str(it, "type") }
        assertTrue(types.toString(), "response.created" in types && "response.completed" in types)
        assertTrue("no output_text.delta", "response.output_text.delta" in types)
        assertEquals("", Json.str(done, "error"))
        assertTrue(Json.str(done, "text"), Json.str(done, "text").contains("vox-live-stream-ok"))
        assertTrue(Json.str(done, "response_id").isNotEmpty())
    }

    @Test
    fun `tool progress rides the stream`() {
        val s = live()
        val (events, _) = drain(s, s.startStream("Use your shell tool to run exactly: echo vox-tool-stream-live — then reply with just its output."))
        val added = events.filter { Json.str(it, "type") == "response.output_item.added" }
        val call = added.firstOrNull { Json.str(it, "item_type") == "function_call" }
        assertTrue("no function_call", call != null)
        assertTrue("function_call carried no tool name", Json.str(call, "name").isNotEmpty())
        assertTrue("no function_call_output", added.any { Json.str(it, "item_type") == "function_call_output" })
    }

    @Test
    fun `barge-in mid-flight retires the stream promptly`() {
        val s = live()
        val sid = s.startStream("Count from 1 to 400, one number per line.")
        Thread.sleep(3000)
        val start = System.currentTimeMillis()
        s.cancelStream(sid)
        assertTrue(System.currentTimeMillis() - start < 2000)
        assertTrue(!Streams.isRegistered(sid))
    }

    @Test
    fun `compress rides the streamed chain`() {
        val s = live()
        drain(s, s.voiceTurn("Remember the codeword: amber-heron-3. Reply ok."))
        val head = s.lastID
        assertTrue(head.isNotEmpty())
        s.turnStored("Reply with just the codeword I gave you.").let { assertTrue("chain broken: $it", it.contains("amber-heron-3")) }
        assertTrue("the stored reply's id is the new head", s.lastID != head)
    }
}
