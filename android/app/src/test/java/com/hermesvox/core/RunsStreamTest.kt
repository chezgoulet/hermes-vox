package com.hermesvox.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The `/v1/runs` transport contract: the parts that are NOT a vocabulary change and so
 * are not covered by the golden replay — the run's own barge-in, the recorded-backlog
 * reattach, the chain head, and the clarify control event riding through untouched.
 */
class RunsStreamTest {

    private fun withRuns(block: () -> Unit) {
        val old = StreamRoute.active
        StreamRoute.active = TurnRoute.RUNS
        try {
            block()
        } finally {
            StreamRoute.active = old
        }
    }

    private fun drain(s: HermesSession, sid: String, budgetMs: Long = 5000): JObj {
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            s.waitStream(sid, 50)
            val p = Json.parse(s.pollStreamJSON(sid)) as JObj
            if ((p["done"] as JBool).value) return p
        }
        error("run never reported done")
    }

    /**
     * A barge-in on /v1/responses aborts generation by closing the connection. On
     * /v1/runs the events GET is only a subscriber: closing it leaves the run working,
     * so the reader must also POST the run's stop. The subscription is closed first.
     */
    @Test
    fun `a barge-in stops the run, not just the subscription`() = withRuns {
        val stopped = CountDownLatch(1)
        val stopPath = Collections.synchronizedList(ArrayList<String>())
        val subscriptionClosed = CountDownLatch(1)
        TestServer { r, w ->
            when {
                r.method == "POST" && r.path == "/v1/runs" ->
                    w.send(202, "Accepted", """{"run_id":"run_b","status":"started"}""")
                r.method == "POST" && r.path.endsWith("/stop") -> {
                    stopPath.add(r.path)
                    w.send(200, "OK", """{"run_id":"run_b","status":"stopping"}""")
                    stopped.countDown()
                }
                r.method == "GET" -> {
                    w.sse()
                    w.write("""id: 0
data: {"event":"message.delta","run_id":"run_b","seq":0,"delta":"working"}

""")
                    if (w.holdUntilClientCloses(5000)) subscriptionClosed.countDown()
                }
            }
        }.use { srv ->
            val s = HermesSession(srv.url, "testkey", "hermes-agent")
            val sid = s.startStream("hi")
            // Wait for a DELTA, not just the synthetic response.created: the subscription
            // must be open (and holding) before a barge-in can exercise it.
            val deadline = System.currentTimeMillis() + 5000
            var sawDelta = false
            while (!sawDelta && System.currentTimeMillis() < deadline) {
                s.waitStream(sid, 50)
                val p = Json.parse(s.pollStreamJSON(sid)) as JObj
                sawDelta = (p["events"] as? JArr)?.items?.any {
                    Json.str(it as JObj, "type") == "response.output_text.delta"
                } ?: false
            }
            assertTrue("no delta before the barge-in", sawDelta)
            s.cancelStream(sid)
            assertTrue("the run was never stopped", stopped.await(3, TimeUnit.SECONDS))
            assertEquals("/v1/runs/run_b/stop", stopPath.single())
            assertTrue("the events subscription stayed open", subscriptionClosed.await(3, TimeUnit.SECONDS))
        }
    }

    /**
     * The route's recorded backlog is why the port can survive a dropped socket: the
     * reader remembers the last `seq` it dispatched and reattaches with `last_seq`, so
     * the replay starts exactly after it. Nothing is duplicated, nothing is lost.
     */
    @Test
    fun `a dropped events socket reattaches from the last seq`() = withRuns {
        val gets = Collections.synchronizedList(ArrayList<String>())
        val attempt = AtomicInteger()
        TestServer { r, w ->
            when {
                r.method == "POST" && r.path == "/v1/runs" ->
                    w.send(202, "Accepted", """{"run_id":"run_r","status":"started"}""")
                r.method == "GET" -> {
                    gets.add(r.path)
                    w.sse()
                    if (attempt.incrementAndGet() == 1) {
                        // Two deltas then the socket drops: no terminal event.
                        w.write("""id: 0
data: {"event":"message.delta","run_id":"run_r","seq":0,"delta":"Hello "}

id: 1
data: {"event":"message.delta","run_id":"run_r","seq":1,"delta":"wor"}

""")
                    } else {
                        w.write("""id: 2
data: {"event":"message.delta","run_id":"run_r","seq":2,"delta":"ld"}

id: 3
data: {"event":"run.completed","run_id":"run_r","seq":3,"completed":true,"partial":false,"interrupted":false,"output":"Hello world"}

""")
                    }
                }
            }
        }.use { srv ->
            val s = HermesSession(srv.url, "testkey", "hermes-agent")
            val p = drain(s, s.startStream("hi"))
            assertEquals("", Json.str(p, "error"))
            assertEquals("Hello world", Json.str(p, "text"))
            assertEquals("two subscriptions", 2, gets.size)
            assertEquals("/v1/runs/run_r/events", gets[0])
            assertEquals("the reattach must resume after seq 1", "/v1/runs/run_r/events?last_seq=1", gets[1])
        }
    }

    /**
     * `/v1/runs` has no response id: chaining rides the session key. Sending the
     * Responses chain head as previous_response_id would pin every run to the same
     * response-store snapshot, so the runs body omits it — and a runs turn must not
     * overwrite the stored responses head it cannot advance.
     */
    @Test
    fun `the runs body carries no chain head and leaves it untouched`() = withRuns {
        TestServer { r, w ->
            when {
                r.method == "POST" && r.path == "/v1/runs" ->
                    w.send(202, "Accepted", """{"run_id":"run_c","status":"started"}""")
                r.method == "GET" -> {
                    w.sse()
                    w.write("""id: 0
data: {"event":"run.completed","run_id":"run_c","seq":0,"completed":true,"partial":false,"interrupted":false,"output":"ok"}

""")
                }
            }
        }.use { srv ->
            val s = HermesSession(srv.url, "testkey", "hermes-agent")
            s.lastID = "resp_before_the_flip"
            assertEquals("ok", Json.str(drain(s, s.startStream("hi")), "text"))
            val body = Json.parse(srv.requests.first().body) as JObj
            assertEquals("no previous_response_id on the runs route", null, body["previous_response_id"])
            assertEquals("the responses chain head is not advanced by a run",
                "resp_before_the_flip", s.lastID)
        }
    }

    /**
     * `clarify.request` has no consumer yet. The claim this port makes is narrow: it
     * arrives as its own event type, is not mistaken for an approval, and does not
     * disturb the turn. Surfacing a rendered clarify surface is a separate feature.
     */
    @Test
    fun `a clarify request arrives as its own event, not an approval`() {
        val fx = StreamReplay.parse(File(StreamReplay.fixtureDir(), "runs-clarify.sse"))
        val out = StreamReplay.replay(fx)
        val clarify = StreamReplay.events(out).filter { it["type"] == "clarify.request" }
        assertEquals("exactly one clarify event", 1, clarify.size)
        assertEquals("it is not an approval", null, clarify.single()["approval"])
        assertTrue("the turn still completed", StreamReplay.lastSnapshot(out)["done"] as Boolean)
        assertEquals("Which branch should I cut from?", StreamReplay.lastSnapshot(out)["text"])
    }
}
