package com.hermesvox.core

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The live gateway suite: a stored turn, the server-side chain, the SSE stream,
 * tool progress, barge-in and /compress on the streamed chain. Ported from the Go
 * voice/integration_live_test.go. Issue #167 made it a gate that actually runs.
 *
 * The cases always run. The base URL is all that changes:
 *  - HERMES_VOX_LIVE=1 AND HERMES_VOX_HERMES_API_KEY set → the REAL gateway, exactly
 *    as before (optional HERMES_VOX_HERMES_URL, default http://127.0.0.1:8642;
 *    HERMES_VOX_HERMES_MODEL, default hermes-agent; HERMES_VOX_HERMES_SESSION_KEY).
 *  - otherwise → [FakeGateway], a deterministic JDK HttpServer on an ephemeral
 *    loopback port, serving the same wire shapes.
 *
 * There is no assumeTrue: a skipped case is a failure here. HERMES_VOX_LIVE=1 with
 * no key fails loudly instead of quietly testing the fake, so a nightly that meant
 * to hit the real gateway can never go green against a substitute.
 *
 * The last case is the harness's own negative control: it points the streamed-turn
 * assertions at a fake that truncates the SSE body and requires them to fail.
 *
 *   HERMES_VOX_LIVE=1 HERMES_VOX_HERMES_API_KEY=… ./gradlew testDebugUnitTest --tests '*LiveGatewayTest'
 */
class LiveGatewayTest {

    companion object {
        private const val FAKE_KEY = "fake-gateway-key"

        private var fake: FakeGateway? = null

        private fun env(k: String) = System.getenv(k).orEmpty()

        /** "Use the real gateway" — both the switch and a key are required. */
        private fun realRequested() = env("HERMES_VOX_LIVE") == "1"

        @BeforeClass
        @JvmStatic
        fun startFake() {
            if (!realRequested()) fake = FakeGateway()
        }

        @AfterClass
        @JvmStatic
        fun stopFake() {
            fake?.close()
            fake = null
        }
    }

    private fun live(): HermesSession {
        if (realRequested()) {
            val key = env("HERMES_VOX_HERMES_API_KEY")
            assertTrue(
                "HERMES_VOX_LIVE=1 but HERMES_VOX_HERMES_API_KEY is empty: refusing to " +
                    "run the live suite against the fake when a real gateway was requested",
                key.isNotEmpty())
            return HermesSession(
                env("HERMES_VOX_HERMES_URL").ifEmpty { "http://127.0.0.1:8642" }, key,
                env("HERMES_VOX_HERMES_MODEL").ifEmpty { "hermes-agent" },
            ).also { it.setSessionKey(env("HERMES_VOX_HERMES_SESSION_KEY")) }
        }
        val f = fake ?: error("fake gateway was not started")
        return HermesSession(f.url, FAKE_KEY, "hermes-agent")
            .also { it.setSessionKey("agent:vox:live-suite") }
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

    // ---- the six cases, split into assertion functions so the negative control
    // ---- can drive the very same checks at a misbehaving fake ----

    private fun assertStoredTurn(s: HermesSession) {
        s.ping()
        val reply = s.turnStored("Health probe. Reply with exactly: vox-live-turn-ok")
        assertTrue(reply, reply.contains("vox-live-turn-ok"))
        assertTrue(s.lastID, s.lastID.startsWith("resp_"))
    }

    private fun assertChainRecalls(s: HermesSession) {
        s.turnStored("Remember the codeword: cobalt-falcon-7. Reply ok.")
        val reply = s.turnStored("What codeword did I tell you? Reply with just the codeword.")
        assertTrue("chain broken: $reply", reply.contains("cobalt-falcon-7"))
    }

    private fun assertStreamShape(s: HermesSession) {
        val (events, done) = drain(s, s.startStream("Stream probe. Reply with exactly: vox-live-stream-ok"))
        val types = events.map { Json.str(it, "type") }
        assertTrue(types.toString(), "response.created" in types)
        assertTrue("stream ended without response.completed: $types", "response.completed" in types)
        assertTrue("no output_text.delta", "response.output_text.delta" in types)
        assertEquals("", Json.str(done, "error"))
        assertTrue(Json.str(done, "text"), Json.str(done, "text").contains("vox-live-stream-ok"))
        assertTrue(Json.str(done, "response_id").isNotEmpty())
    }

    private fun assertToolProgress(s: HermesSession) {
        val (events, _) = drain(s, s.startStream("Use your shell tool to run exactly: echo vox-tool-stream-live — then reply with just its output."))
        val added = events.filter { Json.str(it, "type") == "response.output_item.added" }
        val call = added.firstOrNull { Json.str(it, "item_type") == "function_call" }
        assertTrue("no function_call", call != null)
        assertTrue("function_call carried no tool name", Json.str(call, "name").isNotEmpty())
        assertTrue("no function_call_output", added.any { Json.str(it, "item_type") == "function_call_output" })
    }

    private fun assertBargeIn(s: HermesSession) {
        val sid = s.startStream("Count from 1 to 400, one number per line.")
        Thread.sleep(3000)
        val start = System.currentTimeMillis()
        s.cancelStream(sid)
        assertTrue(System.currentTimeMillis() - start < 2000)
        assertTrue(!Streams.isRegistered(sid))
    }

    private fun assertCompressChain(s: HermesSession) {
        drain(s, s.voiceTurn("Remember the codeword: amber-heron-3. Reply ok."))
        val head = s.lastID
        assertTrue(head.isNotEmpty())
        s.turnStored("Reply with just the codeword I gave you.")
            .let { assertTrue("chain broken: $it", it.contains("amber-heron-3")) }
        assertTrue("the stored reply's id is the new head", s.lastID != head)
    }

    // ---- the cases as CI runs them ----

    @Test
    fun `a stored turn returns a reply and a response id`() {
        assertStoredTurn(live())
    }

    @Test
    fun `the server-side chain recalls a planted fact`() {
        assertChainRecalls(live())
    }

    @Test
    fun `a streamed turn yields created, deltas and completed`() {
        assertStreamShape(live())
    }

    @Test
    fun `tool progress rides the stream`() {
        assertToolProgress(live())
    }

    @Test
    fun `barge-in mid-flight retires the stream promptly`() {
        assertBargeIn(live())
    }

    @Test
    fun `compress rides the streamed chain`() {
        assertCompressChain(live())
    }

    /**
     * The harness can fail. A fake that truncates the SSE body must make the
     * streamed-turn assertion fail — if this case passes, the suite above is
     * incapable of catching a broken stream, which is exactly the silent-gate
     * failure this repo has been bitten by before.
     */
    @Test
    fun `the harness fails when the fake truncates the stream`() {
        FakeGateway(FakeGateway.Faults.TRUNCATED_STREAM).use { broken ->
            val s = HermesSession(broken.url, FAKE_KEY, "hermes-agent")
            val failure = try {
                assertStreamShape(s)
                null
            } catch (e: AssertionError) {
                e
            }
            assertTrue(
                "the streamed-turn assertions PASSED against a truncated SSE stream: " +
                    "the live harness cannot detect a broken gateway",
                failure != null)
            assertTrue(
                "the harness failed, but not on the missing completion: ${failure!!.message}",
                failure.message.orEmpty().contains("response.completed"))
        }
    }
}
