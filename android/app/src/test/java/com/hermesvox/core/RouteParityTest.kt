package com.hermesvox.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The cross-route parity proof for #route parity: the same turn, expressed once in the
 * `/v1/responses` vocabulary and once in the `/v1/runs` vocabulary, must produce the
 * same rendered outcome through [HermesSession] — same reply text, same end event, same
 * tool lines, same presence asides, same approval request.
 *
 * This is the point of the runs reader. The goldens in [StreamParityTest] pin each
 * vocabulary separately; this compares the two vocabularies against each other, so a
 * route flip cannot silently change what the app says or shows.
 *
 * Each pair works on the SAME user turn. The prose differs only where the two fixture
 * sets were written for different scenarios; the assertions below name the exact
 * fields compared.
 */
class RouteParityTest {

    /** What the app consumes and renders, projected off the poll stream. */
    private data class Outcome(
        val text: String,
        val transcript: String,
        val done: Boolean,
        val error: String,
        /** the terminal event's normalized type ("" when the turn had none) */
        val endType: String,
        /** the answer's tool marker lines, in order — what plainText drops from speech */
        val toolMarkers: List<String>,
        /** every function_call item's tool name, in order */
        val toolNames: List<String>,
        /** the commentary VoiceController logs: closed commentary items, in order */
        val asides: List<String>,
        val approvals: List<Map<*, *>>,
    )

    private fun replay(name: String): Map<String, Any?> =
        StreamReplay.replay(StreamReplay.parse(File(StreamReplay.fixtureDir(), "$name.sse")))

    private fun outcome(out: Map<String, Any?>): Outcome {
        val snap = StreamReplay.lastSnapshot(out)
        val events = StreamReplay.events(out)
        val transcript = snap["transcript"].toString()
        return Outcome(
            text = snap["text"].toString(),
            transcript = transcript,
            done = snap["done"] as Boolean,
            error = snap["error"].toString(),
            endType = events.lastOrNull()?.get("type").toString(),
            toolMarkers = transcript.split("\n").filter { it.startsWith("[tool:") || it.startsWith("[tool-done]") },
            toolNames = events.filter {
                it["type"] == "response.output_item.added" && it["item_type"] == "function_call"
            }.map { it["name"].toString() },
            asides = events.filter {
                it["type"] == "response.output_item.done" && it["commentary"].toString().isNotBlank()
            }.map { it["commentary"].toString() },
            approvals = events.mapNotNull { it["approval"] as? Map<*, *> },
        )
    }

    /** The two readers must agree on everything the app renders. */
    private fun assertSameRendered(responses: Outcome, runs: Outcome) {
        assertEquals("reply text", responses.text, runs.text)
        assertEquals("transcript (tool marker lines included)", responses.transcript, runs.transcript)
        assertEquals("terminal done flag", responses.done, runs.done)
        assertEquals("terminal event", responses.endType, runs.endType)
        assertEquals("terminal error", responses.error, runs.error)
        assertEquals("tool marker lines", responses.toolMarkers, runs.toolMarkers)
        assertEquals("tool names", responses.toolNames, runs.toolNames)
        assertEquals("presence asides", responses.asides, runs.asides)
        assertEquals("approval requests", responses.approvals, runs.approvals)
    }

    /**
     * The one field that is NOT byte-identical across the routes, asserted explicitly
     * rather than hidden: the responses fixture carries the tool's raw JSON arguments
     * (double-encoded by the wire and the pinned trim), while `/v1/runs` `tool.started`
     * carries no `args` at all — only the gateway's human-readable `preview`. The
     * semantic tool call is the same; the detail text is a wire-format difference.
     */
    private fun toolDetail(out: Map<String, Any?>): Pair<String, String> {
        val ev = StreamReplay.events(out).first {
            it["type"] == "response.output_item.added" && it["item_type"] == "function_call"
        }
        return ev["name"].toString() to ev["arguments"].toString()
    }

    @Test
    fun `a normal reply renders identically on both routes`() {
        assertSameRendered(outcome(replay("normal")), outcome(replay("runs-normal")))
    }

    @Test
    fun `a tool run renders the same tool lines and answer`() {
        val responses = replay("tools")
        val runs = replay("runs-tools")
        assertSameRendered(outcome(responses), outcome(runs))
        // The marker lines are byte-identical; the call detail is not, and the reason
        // is the wire (see toolDetail).
        assertEquals("[tool:terminal]\n[tool-done]", outcome(runs).toolMarkers.joinToString("\n"))
        assertEquals("terminal", toolDetail(responses).first)
        assertEquals("terminal", toolDetail(runs).first)
        assertEquals("{\\\"command\\\":\\\"date\\\"}", toolDetail(responses).second)
        assertEquals("date", toolDetail(runs).second)
    }

    @Test
    fun `an error ends as the same failed terminal with the same reason`() {
        val responses = outcome(replay("error"))
        val runs = outcome(replay("runs-error"))
        assertEquals("response.failed", responses.endType)
        assertEquals(responses.endType, runs.endType)
        assertEquals(responses.done, runs.done)
        assertTrue("a failed turn has no reply text", responses.text.isEmpty() && runs.text.isEmpty())
        // The prefix names the route on purpose; the reason the user sees is identical.
        assertEquals("hermes response failed: provider quota exhausted", responses.error)
        assertEquals("hermes run failed: provider quota exhausted", runs.error)
        assertEquals(responses.error.substringAfter(": "), runs.error.substringAfter(": "))
    }

    @Test
    fun `an incomplete turn keeps its text and completes on both routes`() {
        val responses = outcome(replay("incomplete"))
        val runs = outcome(replay("runs-incomplete"))
        assertSameRendered(responses, runs)
        assertEquals("response.incomplete", responses.endType)
        assertEquals("Partial answer", runs.text)
    }

    @Test
    fun `the interim commentary becomes the same presence aside`() {
        val responses = outcome(replay("commentary"))
        val runs = outcome(replay("runs-commentary"))
        assertSameRendered(responses, runs)
        assertEquals(listOf("Checking the repo first."), runs.asides)
        assertEquals("All tests pass.", runs.text)
        // The aside never leaks into the spoken reply on either route.
        assertNotEquals(runs.text, runs.asides.first())
    }

    /**
     * `message.interim` with `already_streamed=true` is the runs analogue of the
     * commentary item the responses writer deliberately suppresses: that text already
     * went out as answer deltas, so surfacing it again would duplicate it as an aside.
     */
    @Test
    fun `already-streamed interim text is not replayed as an aside`() {
        val body = """
            id: 0
            data: {"event":"message.delta","run_id":"run_parity","seq":0,"delta":"All tests pass."}

            id: 1
            data: {"event":"message.interim","run_id":"run_parity","seq":1,"text":"All tests pass.","already_streamed":true}

            id: 2
            data: {"event":"run.completed","run_id":"run_parity","seq":2,"completed":true,"partial":false,"interrupted":false,"output":"All tests pass."}

        """.trimIndent()
        val runs = outcome(replayRuns("already-streamed", body))
        assertEquals(emptyList<String>(), runs.asides)
        assertEquals("All tests pass.", runs.text)
    }

    /**
     * The approval payload is route-independent: the same approval.request expressed in
     * the Responses envelope and in the runs envelope must reach the approval gate as
     * the same [ApprovalRequest] map.
     */
    @Test
    fun `an approval request is identical in both vocabularies`() {
        val responsesBody = """
            data: {"type":"approval.request","run_id":"run_parity","request_id":"req_1","command":"rm -rf /tmp/vox-scratch","description":"delete the scratch directory","choices":["once","session","deny"]}

            data: {"type":"response.completed","response":{"id":"resp_a","status":"completed"}}

        """.trimIndent()
        val runsBody = """
            id: 0
            data: {"event":"approval.request","run_id":"run_parity","seq":0,"request_id":"req_1","command":"rm -rf /tmp/vox-scratch","description":"delete the scratch directory","choices":["once","session","deny"]}

            id: 1
            data: {"event":"message.delta","run_id":"run_parity","seq":1,"delta":"Answer."}

            id: 2
            data: {"event":"run.completed","run_id":"run_parity","seq":2,"completed":true,"partial":false,"interrupted":false,"output":"Answer."}

        """.trimIndent()
        val expected = linkedMapOf<String, Any?>(
            "run_id" to "run_parity",
            "request_id" to "req_1",
            "command" to "rm -rf /tmp/vox-scratch",
            "description" to "delete the scratch directory",
            "reason" to "",
            "choices" to listOf("once", "session", "deny"),
        )
        val responsesApproval = outcome(
            StreamReplay.replay(inline("approval-responses", responsesBody, TurnRoute.RESPONSES))).approvals.single()
        val runsApproval = outcome(replayRuns("approval-runs", runsBody)).approvals.single()
        assertEquals(expected, responsesApproval)
        assertEquals(expected, runsApproval)
        // The committed runs fixture carries the same map.
        assertEquals(expected, outcome(replay("runs-approval")).approvals.single())
    }

    /**
     * The session-authority shape names the pending control `prompt_id` and fences it
     * with `execution_generation`; the plain lane names the same handle `request_id`.
     * Both must land in the same field, or the phone's answer cannot be fenced.
     */
    @Test
    fun `a session-authority control prompt keeps its request handle`() {
        val body = """
            id: 0
            data: {"event":"approval.request","run_id":"run_parity","seq":0,"prompt_id":"req_auth","execution_generation":3,"command":"rm -rf /tmp/x","description":"delete","choices":["once","deny"]}

            id: 1
            data: {"event":"message.delta","run_id":"run_parity","seq":1,"delta":"Done."}

            id: 2
            data: {"event":"run.completed","run_id":"run_parity","seq":2,"completed":true,"partial":false,"interrupted":false,"output":"Done."}

        """.trimIndent()
        val approval = outcome(replayRuns("session-authority", body)).approvals.single()
        assertEquals("req_auth", approval["request_id"])
        assertEquals("run_parity", approval["run_id"])
        assertEquals("3", approval["execution_generation"])
        assertEquals(listOf("once", "deny"), approval["choices"])
    }

    /** Replay a hand-built body on the runs route (no committed fixture). */
    private fun replayRuns(name: String, body: String): Map<String, Any?> =
        StreamReplay.replay(inline(name, body, TurnRoute.RUNS))

    private fun inline(name: String, body: String, route: TurnRoute): StreamReplay.Fixture =
        StreamReplay.Fixture(name, body).apply { this.route = route }
}
