package com.hermesvox.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Abandoning a turn is route-aware.
 *
 * On /v1/responses the SSE request is the generation: closing the socket aborts it.
 * On /v1/runs the events GET is only a subscriber, so the run must be stopped with
 * `POST /v1/runs/{id}/stop`; otherwise the client times out and the run keeps going.
 */
class TurnAbandonTest {

    private class Calls {
        val lines = ArrayList<String>()
        fun close(id: String) { lines.add("close:$id") }
        fun stop(id: String) { lines.add("stop:$id") }
    }

    @Test
    fun `the responses route closes the socket and never stops a run`() {
        val calls = Calls()
        val stopped = TurnAbandon.perform(
            route = TurnRoute.RESPONSES,
            streamId = "hvstream_1",
            runId = "run_ignored",           // a responses turn has no run to stop
            closeSocket = calls::close,
            stopRun = calls::stop,
        )

        assertFalse("the responses route has no run to stop", stopped)
        assertEquals(listOf("close:hvstream_1"), calls.lines)
    }

    @Test
    fun `the runs route posts a stop and closes the subscriber`() {
        val calls = Calls()
        val stopped = TurnAbandon.perform(
            route = TurnRoute.RUNS,
            streamId = "hvstream_2",
            runId = "run_1",
            closeSocket = calls::close,
            stopRun = calls::stop,
        )

        assertTrue("the run itself must be stopped", stopped)
        assertEquals(listOf("stop:run_1", "close:hvstream_2"), calls.lines)
    }

    @Test
    fun `a runs turn with no known run id still closes its subscriber`() {
        val calls = Calls()
        val stopped = TurnAbandon.perform(
            route = TurnRoute.RUNS,
            streamId = "hvstream_3",
            runId = "",
            closeSocket = calls::close,
            stopRun = calls::stop,
        )

        assertFalse(stopped)
        assertEquals(listOf("close:hvstream_3"), calls.lines)
    }

    @Test
    fun `the runs stop is a real POST to the run's stop path`() {
        val paths = ArrayList<String>()
        TestServer { r, w ->
            when {
                r.method == "POST" && r.path == "/v1/runs/run_1/stop" -> {
                    paths.add(r.path)
                    w.send(200, "OK", """{"run_id":"run_1","status":"stopping"}""")
                }
                else -> w.send(404, "Not Found", """{"error":"unexpected"}""")
            }
        }.use { srv ->
            val session = HermesSession(srv.url, "testkey", "hermes-agent")
            session.stopRun("run_1")
        }

        assertEquals(listOf("/v1/runs/run_1/stop"), paths)
    }
}
