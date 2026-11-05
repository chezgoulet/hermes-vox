package com.hermesvox.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #404 follow-up: a gateway without `POST /v1/runs` falls back to the responses route once,
 * and only on a 404. The fallback belongs to the profile, so it is remembered for that name
 * and another profile is judged on its own answer.
 */
class RouteFallbackTest {

    private val profile = "route-fallback-test"
    private var logBefore: (String) -> Unit = {}
    private val lines = java.util.Collections.synchronizedList(ArrayList<String>())

    @After
    fun clean() {
        StreamRoute.forgetForTest(profile)
        StreamRoute.forgetForTest("fresh")
        StreamRoute.bindStore({ false }, { })
        StreamRoute.log = logBefore
    }

    private fun drain(s: HermesSession, sid: String, budgetMs: Long = 5000): JObj {
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            s.waitStream(sid, 50)
            val p = Json.parse(s.pollStreamJSON(sid)) as JObj
            if ((p["done"] as JBool).value) return p
        }
        error("stream never reported done")
    }

    private fun session(fake: FakeGateway, name: String = profile): HermesSession =
        HermesSession(fake.url, "testkey", "hermes-agent").also {
            it.setSessionKey("agent:vox:route-fallback")
            it.setProfile(name)
        }

    @Test
    fun `a gateway with no runs route succeeds on responses and the choice sticks per profile`() {
        FakeGateway(FakeGateway.Faults.NO_RUNS_ROUTE).use { fake ->
            logBefore = StreamRoute.log
            StreamRoute.log = { lines.add(it) }
            val s = session(fake)
            assertEquals(TurnRoute.RUNS, StreamRoute.active)

            val first = drain(s, s.startStream("Stream probe. Reply with exactly: vox-live-stream-ok"))
            assertEquals("", Json.str(first, "error"))
            assertTrue(Json.str(first, "text").contains("vox-live-stream-ok"))
            assertEquals("the turn fell back", TurnRoute.RESPONSES, s.route())
            assertEquals(1, fake.runAdmissions.get())
            assertEquals(1, fake.responseStreams.get())
            assertTrue(lines.toString(), lines.any {
                it == "event=route-fallback reason=no-runs-route profile=$profile"
            })

            // The next turn is already on responses: the probe is not paid again.
            val second = drain(s, s.startStream("Stream probe. Reply with exactly: vox-live-stream-ok"))
            assertEquals("", Json.str(second, "error"))
            assertEquals("no second admission", 1, fake.runAdmissions.get())
            assertEquals("responses carried the second turn", 2, fake.responseStreams.get())

            // A new session for the same profile inherits the remembered answer.
            val again = session(fake)
            assertEquals(TurnRoute.RESPONSES, again.route())
            val third = drain(again, again.startStream("Stream probe. Reply with exactly: vox-live-stream-ok"))
            assertEquals("", Json.str(third, "error"))
            assertEquals("still no new admission", 1, fake.runAdmissions.get())
        }
    }

    @Test
    fun `a 500 from the runs admission is a real error and never a fallback`() {
        FakeGateway(FakeGateway.Faults.RUNS_500).use { fake ->
            logBefore = StreamRoute.log
            StreamRoute.log = { lines.add(it) }
            val s = session(fake)

            val done = drain(s, s.startStream("Say hello"))

            assertTrue(Json.str(done, "error"), Json.str(done, "error").contains("500"))
            assertEquals("the route did not move", TurnRoute.RUNS, s.route())
            assertEquals("one admission", 1, fake.runAdmissions.get())
            assertEquals("nothing was tried on responses", 0, fake.responseStreams.get())
            assertTrue(lines.toString(), lines.none { it.contains("route-fallback") })
            assertNotEquals("", Json.str(done, "error"))
        }
    }

    @Test
    fun `the answer is saved once and loaded only for the profile that gave it`() {
        val activeBefore = StreamRoute.active
        StreamRoute.active = TurnRoute.RUNS
        val saved = mutableListOf<String>()
        try {
            StreamRoute.bindStore(load = { it == "old" }, save = { saved.add(it) })
            StreamRoute.useProfile("old")
            assertEquals("a remembered profile starts on responses", TurnRoute.RESPONSES, StreamRoute.routeFor(false))
            StreamRoute.useProfile("fresh")
            assertEquals("another profile is judged on its own answer", TurnRoute.RUNS, StreamRoute.routeFor(false))
            assertTrue(StreamRoute.markNoRuns())
            assertEquals(listOf("fresh"), saved)
            assertFalse("a second turn does not write again", StreamRoute.markNoRuns())
            assertEquals("the persisted answer is read back by name", true, saved.contains("fresh"))
        } finally {
            StreamRoute.active = activeBefore
            StreamRoute.forgetForTest("old")
        }
    }
}
