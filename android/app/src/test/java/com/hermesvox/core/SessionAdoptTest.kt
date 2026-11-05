package com.hermesvox.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #437 — adopting a session is setting one field.
 *
 * `POST /v1/runs` ranks an explicit body `session_id` above the response chain and above the
 * declared `X-Hermes-Session-Key` (`api_server_runs.py`), so pointing Vox at a session the
 * host already has is [HermesSession.adoptConversation] and nothing else. This pins the two
 * turns that matter: the next streamed turn, and the stored turn `/compress` sends.
 */
class SessionAdoptTest {

    private val hostId = "20261030_235403_045e2835"

    private fun withRuns(block: () -> Unit) {
        val old = StreamRoute.active
        StreamRoute.active = TurnRoute.RUNS
        try {
            block()
        } finally {
            StreamRoute.active = old
        }
    }

    private fun session(srv: TestServer) =
        HermesSession(srv.url, "testkey", "hermes-agent").also { it.setSessionKey("agent:vox:runs") }

    /** A gateway whose every run answers "compacted" — enough to complete a streamed and a
     *  stored turn without modelling a conversation. */
    private fun runs(): TestServer = TestServer { r, w ->
        when {
            r.method == "POST" && r.path == "/v1/runs" ->
                w.send(202, "Accepted", """{"run_id":"run_1","status":"started"}""")
            r.method == "POST" && r.path.endsWith("/stop") ->
                w.send(200, "OK", """{"run_id":"run_1","status":"stopping"}""")
            r.method == "GET" -> {
                w.sse()
                w.write(
                    """data: {"event":"run.started","run_id":"run_1","seq":0}
                        |
                        |data: {"event":"message.delta","run_id":"run_1","seq":1,"delta":"compacted"}
                        |
                        |data: {"event":"run.completed","run_id":"run_1","seq":2,"completed":true,"output":"compacted"}
                        |
                        |""".trimMargin()
                )
            }
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

    private fun runBodies(srv: TestServer): List<JObj> =
        srv.requests.filter { it.method == "POST" && it.path == "/v1/runs" }
            .map { Json.parse(it.body) as JObj }

    @Test
    fun `an adopted session is the body session_id of the next turn and of a stored turn`() = withRuns {
        runs().use { srv ->
            val s = session(srv)
            s.adoptConversation(hostId)
            drain(s, s.startStream("what did we decide about the wiki"))
            // /compress on this route is a run too (H2): the stored turn must land in the
            // same conversation, or a compaction would compact the wrong transcript.
            assertEquals("compacted", s.turnStored("/compress"))
            val ids = runBodies(srv).map { Json.str(it, "session_id") }
            assertEquals(listOf(hostId, hostId), ids)
            // The declared scope is untouched: it is long-term memory, not the transcript.
            assertEquals("agent:vox:runs",
                srv.requests.first { it.path == "/v1/runs" }.header("X-Hermes-Session-Key"))
        }
    }

    @Test
    fun `adopting drops a chain head that belongs to the conversation being left`() = withRuns {
        runs().use { srv ->
            val s = session(srv)
            s.lastID = "resp_from_the_old_thread"
            s.adoptConversation(hostId)
            assertEquals("", s.lastResponseId())
            drain(s, s.startStream("hello"))
            // A runs body carries no chain: the id alone names the conversation.
            assertEquals(null, runBodies(srv).single()["previous_response_id"])
            assertEquals(hostId, Json.str(runBodies(srv).single(), "session_id"))
        }
    }

    @Test
    fun `a new conversation leaves the adopted one behind`() = withRuns {
        runs().use { srv ->
            val s = session(srv)
            s.adoptConversation(hostId)
            s.resetConversation()
            assertTrue("a reset rotates the id", s.conversationId() != hostId)
            assertTrue("and it is this app's own again", s.conversationId().startsWith("vox_"))
        }
    }

    @Test
    fun `an adopted id is sent verbatim`() = withRuns {
        runs().use { srv ->
            val s = session(srv)
            // The host's ids carry no spaces, but the field is the host's: nothing is
            // rewritten on the way out (a trimmed id is still the same session).
            s.adoptConversation("  $hostId  ")
            assertEquals(hostId, s.conversationId())
            drain(s, s.startStream("hi"))
            assertEquals(hostId, Json.str(runBodies(srv).single(), "session_id"))
        }
    }
}
