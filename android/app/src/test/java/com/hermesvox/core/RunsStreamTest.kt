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

    /** A run has no response id: the route is only taken with a declared scope. */
    private fun session(srv: TestServer) =
        HermesSession(srv.url, "testkey", "hermes-agent").also { it.setSessionKey("agent:vox:runs") }

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
            val s = session(srv)
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
            val s = session(srv)
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
            val s = session(srv)
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

    // ---- #404: the flip ----

    @Test
    fun `the shipped route is runs`() {
        assertEquals(TurnRoute.RUNS, StreamRoute.active)
    }

    /**
     * With no declared scope the gateway keys a run's session by its own run id, so
     * every turn would start a new conversation. Such an install stays on the responses
     * route — chained by previous_response_id — even with the runs route active.
     */
    @Test
    fun `with no session key a turn stays on the responses route`() = withRuns {
        TestServer { r, w ->
            if (r.method == "POST" && r.path == "/v1/responses") {
                w.sse()
                w.write("""event: response.completed
data: {"type":"response.completed","response":{"id":"resp_k","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"kept"}]}]}}

""")
            } else w.send(500, "Wrong Route", "{}")
        }.use { srv ->
            val s = HermesSession(srv.url, "testkey", "hermes-agent")
            s.lastID = "resp_prev"
            drain(s, s.startStream("hi"))
            val r = srv.requests.single()
            assertEquals("/v1/responses", r.path)
            assertEquals("resp_prev", Json.str(Json.parse(r.body) as JObj, "previous_response_id"))
            assertEquals("resp_k", s.lastID)
        }
    }

    /**
     * A stopped run that already finished answers 404 (record gone) or 409 (another
     * process). Neither is the caller's error: cancel returns, nothing throws, and the
     * next turn on the same session runs normally.
     */
    @Test
    fun `a stop answered 404 or 409 is not an error the caller sees`() = withRuns {
        for (code in listOf(404, 409)) {
            val outcome = CountDownLatch(1)
            val verdict = Collections.synchronizedList(ArrayList<String>())
            Streams.stopOutcome = { _, v -> verdict.add(v); outcome.countDown() }
            try {
                val turns = AtomicInteger()
                TestServer { r, w ->
                    when {
                        r.method == "POST" && r.path == "/v1/runs" ->
                            w.send(202, "Accepted", """{"run_id":"run_${turns.incrementAndGet()}","status":"started"}""")
                        r.method == "POST" && r.path.endsWith("/stop") ->
                            w.send(code, "Gone", """{"error":{"code":"run_not_found"}}""")
                        r.method == "GET" && r.path.startsWith("/v1/runs/run_1/") -> {
                            w.sse()
                            w.write("""id: 0
data: {"event":"message.delta","run_id":"run_1","seq":0,"delta":"x"}

""")
                            w.holdUntilClientCloses(5000)
                        }
                        r.method == "GET" -> {
                            w.sse()
                            w.write("""id: 0
data: {"event":"run.completed","run_id":"run_2","seq":0,"completed":true,"partial":false,"interrupted":false,"output":"next"}

""")
                        }
                    }
                }.use { srv ->
                    val s = session(srv)
                    val sid = s.startStream("hi")
                    s.waitStream(sid, 2000)
                    s.pollStreamJSON(sid)
                    s.cancelStream(sid)   // must not throw
                    s.cancelStream(sid)   // a second cancel is a no-op
                    assertTrue("the stop never ran ($code)", outcome.await(3, TimeUnit.SECONDS))
                    assertEquals("already finished", verdict.single())
                    val p = drain(s, s.startStream("again"))
                    assertEquals("", Json.str(p, "error"))
                    assertEquals("next", Json.str(p, "text"))
                }
            } finally {
                Streams.stopOutcome = null
            }
        }
    }

    /**
     * A barge-in while the run is being admitted must not lose the run. Once the POST
     * is out the gateway may have created the run; cutting the admission would drop the
     * only id that can stop it. The admission is allowed to finish and the run it names
     * is stopped — and no events are ever subscribed for it.
     */
    @Test
    fun `a barge-in during admission still stops the admitted run`() = withRuns {
        val admitting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val stopPath = Collections.synchronizedList(ArrayList<String>())
        TestServer { r, w ->
            when {
                r.method == "POST" && r.path == "/v1/runs" -> {
                    admitting.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    w.send(202, "Accepted", """{"run_id":"run_late","status":"started"}""")
                }
                r.method == "POST" && r.path.endsWith("/stop") -> {
                    stopPath.add(r.path)
                    w.send(200, "OK", """{"run_id":"run_late","status":"stopping"}""")
                    stopped.countDown()
                }
                else -> w.send(500, "Unexpected", "{}")
            }
        }.use { srv ->
            val s = session(srv)
            val sid = s.startStream("hi")
            assertTrue(admitting.await(3, TimeUnit.SECONDS))
            s.cancelStream(sid)
            release.countDown()
            assertTrue("the admitted run was never stopped", stopped.await(3, TimeUnit.SECONDS))
            assertEquals("/v1/runs/run_late/stop", stopPath.single())
            Thread.sleep(200)
            assertTrue("a cancelled run must not be subscribed",
                srv.requests.none { it.method == "GET" })
        }
    }

    /** The stop goes out through the stream's own authenticated client: same bearer,
     *  same declared scope. */
    @Test
    fun `the stop carries the stream's own credentials`() = withRuns {
        val stopped = CountDownLatch(1)
        TestServer { r, w ->
            when {
                r.method == "POST" && r.path == "/v1/runs" ->
                    w.send(202, "Accepted", """{"run_id":"run_h","status":"started"}""")
                r.method == "POST" && r.path.endsWith("/stop") -> {
                    w.send(200, "OK", "{}"); stopped.countDown()
                }
                r.method == "GET" -> {
                    w.sse()
                    w.write("""id: 0
data: {"event":"message.delta","run_id":"run_h","seq":0,"delta":"x"}

""")
                    w.holdUntilClientCloses(5000)
                }
            }
        }.use { srv ->
            val s = session(srv)
            val sid = s.startStream("hi")
            s.waitStream(sid, 2000)
            s.pollStreamJSON(sid)
            s.cancelStream(sid)
            assertTrue(stopped.await(3, TimeUnit.SECONDS))
            val stop = srv.requests.single { it.path.endsWith("/stop") }
            assertEquals("Bearer testkey", stop.header("Authorization"))
            assertEquals("agent:vox:runs", stop.header("X-Hermes-Session-Key"))
        }
    }

    /** A runs gateway that answers every admission and finishes each run at once. */
    private fun completingRuns(reply: String = "done"): TestServer {
        val n = AtomicInteger()
        return TestServer { r, w ->
            when {
                r.method == "POST" && r.path == "/v1/runs" ->
                    w.send(202, "Accepted", """{"run_id":"run_${n.incrementAndGet()}","status":"started"}""")
                r.method == "GET" && r.path.contains("/events") -> {
                    w.sse()
                    w.write("""id: 0
data: {"event":"run.completed","seq":0,"output":"$reply"}

""")
                }
                else -> w.send(404, "Not Found", "{}")
            }
        }
    }

    private fun runBodies(srv: TestServer): List<JObj> =
        srv.requests.filter { it.method == "POST" && it.path == "/v1/runs" }.map { Json.parse(it.body) as JObj }

    /**
     * H2 (0.9.1 review): the transcript is a client-owned `session_id` in the run body —
     * the gateway ranks it above `X-Hermes-Session-Key`. Every turn of one conversation
     * carries the same id, and the key still rides as the memory scope.
     */
    @Test
    fun `every run of a conversation carries the same client session id`() = withRuns {
        completingRuns().use { srv ->
            val s = session(srv)
            drain(s, s.startStream("one"))
            drain(s, s.voiceTurn("two"))
            val ids = runBodies(srv).map { Json.str(it, "session_id") }
            assertEquals(2, ids.size)
            assertTrue(ids[0].startsWith("vox_"))
            assertEquals(ids[0], ids[1])
            assertEquals(s.conversationId(), ids[0])
            for (r in srv.requests.filter { it.path == "/v1/runs" }) {
                assertEquals("agent:vox:runs", r.header("X-Hermes-Session-Key"))
            }
        }
    }

    /**
     * H2: `/new` (RESET_CHAIN → resetConversation) must start a new transcript on the
     * runs route. Clearing the response head alone changed nothing the run sends, so the
     * next run landed in the old conversation with its full history.
     */
    @Test
    fun `a reset conversation files the next run under a new session id`() = withRuns {
        completingRuns().use { srv ->
            val s = session(srv)
            drain(s, s.startStream("before"))
            s.resetConversation()
            drain(s, s.startStream("after"))
            val ids = runBodies(srv).map { Json.str(it, "session_id") }
            assertEquals(2, ids.size)
            assertTrue(ids.all { it.isNotEmpty() })
            assertTrue("the reset must rotate the transcript id", ids[0] != ids[1])
            // The scope (memory) is unchanged across the reset: only the transcript rotates.
            val keys = srv.requests.filter { it.path == "/v1/runs" }.map { it.header("X-Hermes-Session-Key") }.toSet()
            assertEquals(setOf("agent:vox:runs"), keys)
        }
    }

    @Test
    fun `two sessions never share a transcript id`() {
        val a = HermesSession("http://127.0.0.1:1", "k", "m")
        val b = HermesSession("http://127.0.0.1:1", "k", "m")
        assertTrue(a.conversationId() != b.conversationId())
    }

    /**
     * H2 on the runs route: /compress follows the streamed turns into the SAME transcript.
     * `/v1/responses` cannot address a body session_id, so the stored turn is a run too:
     * same session_id, no stored chain head, and the run's output is the reply.
     */
    @Test
    fun `a stored turn on a runs session rides the conversation's run session`() = withRuns {
        completingRuns("compacted").use { srv ->
            val s = session(srv)
            s.lastID = "resp_stale"
            drain(s, s.startStream("hi"))
            assertEquals("compacted", s.turnStored("/compress"))
            assertTrue(srv.requests.none { it.path == "/v1/responses" })
            val bodies = runBodies(srv)
            assertEquals(2, bodies.size)
            assertEquals(Json.str(bodies[0], "session_id"), Json.str(bodies[1], "session_id"))
            assertEquals(null, bodies[1]["previous_response_id"])
            assertEquals("resp_stale", s.lastID)
        }
    }

    /** A stored run that never answers is stopped at its deadline, not left running. */
    @Test
    fun `a stored run past its deadline is stopped`() = withRuns {
        val stopped = CountDownLatch(1)
        TestServer { r, w ->
            when {
                r.method == "POST" && r.path == "/v1/runs" ->
                    w.send(202, "Accepted", """{"run_id":"run_slow","status":"started"}""")
                r.method == "POST" && r.path.endsWith("/stop") -> {
                    w.send(200, "OK", """{"status":"stopping"}"""); stopped.countDown()
                }
                r.method == "GET" -> { w.sse(); w.write(": keepalive\n\n"); w.holdUntilClientCloses(5000) }
            }
        }.use { srv ->
            val client = ResponsesClient(srv.url, "testkey", "hermes-agent").also { it.setSessionKey("agent:vox:runs") }
            try {
                Streams.complete(client, "/compress", 300)
                error("a silent run must not produce a reply")
            } catch (e: HermesException) {
                assertTrue(e.message!!.contains("no answer"))
            }
            assertTrue(stopped.await(3, TimeUnit.SECONDS))
        }
    }
}
