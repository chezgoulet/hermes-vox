package com.hermesvox.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #538 (second report): "The entity is working…" for a minute or two, with nothing on the
 * gateway. The app could not say whether the turn's `POST /v1/runs` was ever answered:
 * admission logged nothing, and the synthetic `response.created` after it is a debug-only
 * line. These pin the witness, and the cost of an admission nobody answers: one idle
 * timeout, one retry, and — if that is unanswered too — a second timeout before the turn
 * fails (2 × [Streams.idleTimeoutMs], 120 s in the field).
 */
class RunAdmissionWitnessTest {

    private val lines = java.util.Collections.synchronizedList(ArrayList<String>())
    private val logBefore = StreamRoute.log
    private val idleBefore = Streams.idleTimeoutMs

    @After
    fun clean() {
        StreamRoute.log = logBefore
        Streams.idleTimeoutMs = idleBefore
    }

    private fun drain(s: HermesSession, sid: String, budgetMs: Long = 8000): JObj {
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            s.waitStream(sid, 50)
            val p = Json.parse(s.pollStreamJSON(sid)) as JObj
            if ((p["done"] as JBool).value) return p
        }
        error("stream never reported done")
    }

    private fun session(fake: FakeGateway) = HermesSession(fake.url, "testkey", "hermes-agent").also {
        it.setSessionKey("agent:vox:admission-witness")
        it.setProfile("admission-witness")
    }

    private fun admitLines() = lines.filter { it.startsWith("event=run-admit ") }

    @Test
    fun `an admitted turn says so, with its run id`() {
        FakeGateway().use { fake ->
            StreamRoute.log = { lines.add(it) }
            val s = session(fake)
            assertEquals(TurnRoute.RUNS, s.route())
            val p = drain(s, s.startStream("Stream probe. Reply with exactly: vox-live-stream-ok"))
            assertEquals("", Json.str(p, "error"))
            val admits = admitLines()
            assertEquals(admits.toString(), 1, admits.size)
            assertTrue(admits[0], Regex("event=run-admit attempt=1 result=admitted ms=\\d+ code=202 run=run_fake_\\d+").matches(admits[0]))
        }
    }

    @Test
    fun `an unanswered admission is named, retried once, and the retry is named`() {
        Streams.idleTimeoutMs = 300
        FakeGateway(FakeGateway.Faults.ADMISSION_UNANSWERED_ONCE).use { fake ->
            StreamRoute.log = { lines.add(it) }
            val s = session(fake)
            val p = drain(s, s.startStream("Stream probe. Reply with exactly: vox-live-stream-ok"))
            assertEquals("the retry carried the turn", "", Json.str(p, "error"))
            val admits = admitLines()
            assertEquals(admits.toString(), 2, admits.size)
            assertTrue(admits[0], admits[0].startsWith("event=run-admit attempt=1 result=lost "))
            assertTrue(admits[0], admits[0].endsWith("err=SocketTimeoutException"))
            assertTrue(admits[1], admits[1].startsWith("event=run-admit attempt=2 result=admitted "))
            assertEquals(2, fake.runAdmissions.get())
        }
    }

    @Test
    fun `an admission nobody answers costs two idle timeouts before the turn fails`() {
        Streams.idleTimeoutMs = 300
        FakeGateway(FakeGateway.Faults.ADMISSION_UNANSWERED).use { fake ->
            StreamRoute.log = { lines.add(it) }
            val s = session(fake)
            val start = System.currentTimeMillis()
            val p = drain(s, s.startStream("hi"))
            val took = System.currentTimeMillis() - start
            assertEquals(Streams.STALLED, Json.str(p, "error"))
            assertTrue("two timeouts, not one: ${took}ms", took >= 2 * 300)
            val admits = admitLines()
            assertEquals(admits.toString(), 2, admits.size)
            assertTrue(admits.all { it.contains("result=lost") && it.endsWith("err=SocketTimeoutException") })
        }
    }
}
