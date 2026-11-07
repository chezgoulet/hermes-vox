package com.hermesvox.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #376 — the approval arm's wire reading and its one rule: the request the phone shows is
 * the run's own pending control, answered exactly once through the same gate a stream
 * event uses.
 *
 * The two gateway shapes are pinned here because they come from the real 0.22.6 code:
 * session authority parks a `pending_controls[kind=approval]` on `GET /v1/runs/{id}`,
 * while the plain runs lane puts the `approval.request` event under `approval`. The arm
 * must read both without inventing anything.
 */
class ApprovalArmTest {

    private lateinit var server: HttpServer
    private lateinit var pool: java.util.concurrent.ExecutorService
    private val runBodies = LinkedBlockingQueue<String>()

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        pool = Executors.newCachedThreadPool { r -> Thread(r, "arm-fake").apply { isDaemon = true } }
        server.executor = pool
        server.createContext("/v1/runs") { ex -> runs(ex) }
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
        pool.shutdownNow()
    }

    /** POST /v1/runs -> 202 {run_id}; GET /v1/runs/{id} -> the authority projection. */
    private fun runs(ex: HttpExchange) {
        try {
            val path = ex.requestURI.path
            if (ex.requestMethod == "POST" && path == "/v1/runs") {
                runBodies.add(ex.requestBody.use { it.readBytes().toString(Charsets.UTF_8) })
                reply(ex, 202, """{"run_id":"run_arm_1","status":"started"}""")
                return
            }
            if (ex.requestMethod == "GET" && path == "/v1/runs/run_arm_1") {
                reply(
                    ex, 200,
                    """{"object":"hermes.run","run_id":"run_arm_1","status":"waiting_for_approval",
                       "execution_generation":3,
                       "pending_controls":[{"kind":"approval","prompt_id":"prompt_9",
                         "execution_generation":3,
                         "command":"mkdir -p /tmp/vox-approval-arm && rm -rf /tmp/vox-approval-arm",
                         "description":"delete in root path",
                         "choices":["once","deny"]}]}"""
                )
                return
            }
            ex.sendResponseHeaders(404, -1)
        } finally {
            ex.close()
        }
    }

    private fun reply(ex: HttpExchange, code: Int, body: String) {
        val raw = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, raw.size.toLong())
        ex.responseBody.use { it.write(raw) }
    }

    private fun session() = HermesSession("http://127.0.0.1:${server.address.port}", "k", "hermes-agent")

    // ---- the wire reading ----------------------------------------------------

    @Test
    fun `authority projection yields the pending approval with its fence`() {
        val req = ApprovalArm.requestFromRunStatus(
            """{"run_id":"run_1","status":"waiting_for_approval","execution_generation":4,
                "pending_controls":[{"kind":"approval","prompt_id":"p_7","execution_generation":4,
                  "command":"rm -rf /tmp/vox-approval-arm","description":"delete in root path",
                  "choices":["once","deny"]}]}"""
        )
        assertNotNull(req)
        assertEquals("run_1", req!!.runId)
        assertEquals("p_7", req.requestId)
        assertEquals(4, req.executionGeneration)
        assertEquals(listOf("once", "deny"), req.choices)
        assertEquals("delete in root path", req.description)
    }

    @Test
    fun `plain runs lane yields the event itself and falls back to the run as the key`() {
        val req = ApprovalArm.requestFromRunStatus(
            """{"run_id":"run_2","status":"waiting_for_approval",
                "approval":{"event":"approval.request","run_id":"run_2","timestamp":1,
                  "command":"rm -rf /tmp/vox-approval-arm","description":"recursive delete",
                  "choices":["once","session","deny"]}}"""
        )
        assertNotNull(req)
        assertEquals("", req!!.requestId)
        assertEquals("run:run_2", req.key())
        assertEquals(listOf("once", "session", "deny"), req.choices)
    }

    @Test
    fun `a run that is still working yields nothing`() {
        assertNull(ApprovalArm.requestFromRunStatus("""{"run_id":"r","status":"running"}"""))
        assertNull(ApprovalArm.requestFromRunStatus("""{"run_id":"r","status":"queued"}"""))
    }

    @Test
    fun `a settled run yields nothing even if a stale control is still in the body`() {
        assertNull(
            ApprovalArm.requestFromRunStatus(
                """{"run_id":"r","status":"completed",
                    "pending_controls":[{"kind":"approval","prompt_id":"p","choices":["deny"]}]}"""
            )
        )
    }

    @Test
    fun `a clarification is not an approval`() {
        assertNull(
            ApprovalArm.requestFromRunStatus(
                """{"run_id":"r","status":"waiting_for_approval",
                    "pending_controls":[{"kind":"clarify","prompt_id":"p","question":"which?",
                      "choices":["a","b"]}]}"""
            )
        )
    }

    @Test
    fun `an unreadable body is not a request`() {
        assertNull(ApprovalArm.requestFromRunStatus("not json"))
        assertEquals("", ApprovalArm.statusOf("not json"))
    }

    @Test
    fun `the prompt is one harmless command on a path it creates first`() {
        val prompt = ApprovalArm.PROMPT
        assertTrue(prompt, prompt.contains("mkdir -p ${ApprovalArm.PATH}"))
        assertTrue(prompt, prompt.contains("rm -rf ${ApprovalArm.PATH}"))
        // One command, so the delete always targets a directory that exists — the exact
        // failure of the voice attempts (an unresolvable path the agent refused).
        assertTrue(prompt, prompt.indexOf("&&") > prompt.indexOf("mkdir"))
        assertTrue(ApprovalArm.PATH.startsWith("/tmp/"))
    }

    // ---- the exactly-once rule on the arm's request --------------------------

    @Test
    fun `the arm's request takes the same gate and is answered once`() {
        val gate = ApprovalGate()
        val req = requireNotNull(
            ApprovalArm.requestFromRunStatus(
                """{"run_id":"run_3","status":"waiting_for_approval",
                    "pending_controls":[{"kind":"approval","prompt_id":"p_1","execution_generation":2,
                      "command":"rm -rf /tmp/vox-approval-arm","description":"delete in root path",
                      "choices":["once","deny"]}]}"""
            )
        )
        val accepted = requireNotNull(gate.offer(req, runAlive = true))
        assertEquals(listOf("once", "deny"), accepted.choices)

        val answer = requireNotNull(gate.answer(accepted.key(), "deny", "touch"))
        assertEquals(
            "event=approval decision=deny choice=deny run=run_3 request=p_1 cause=touch",
            answer.logLine()
        )
        // A second tap, a voice match or a late timeout must not answer again.
        assertNull(gate.answer(accepted.key(), "deny", "voice"))
        assertNull(gate.timeout(accepted.key()))
    }

    @Test
    fun `an arm request survives the end of an unrelated turn but not its own run closing`() {
        val gate = ApprovalGate()
        val arm = requireNotNull(
            ApprovalArm.requestFromRunStatus(
                """{"run_id":"run_arm","status":"waiting_for_approval",
                    "pending_controls":[{"kind":"approval","prompt_id":"p_arm","choices":["deny"]}]}"""
            )
        )
        val turn = ApprovalRequest(
            runId = "resp_turn", requestId = "p_turn", command = "x", description = "",
            reason = "", choices = listOf("deny"),
        )
        gate.offer(arm, runAlive = true)
        gate.offer(turn, runAlive = true)

        // A turn finishing withdraws the turn's request and keeps the arm's.
        val dropped = gate.closeAllExcept(setOf("run_arm"))
        assertEquals(listOf("p_turn"), dropped.map { it.key() })
        assertEquals("p_arm", gate.current()?.key())

        // Its own run ending still withdraws it.
        assertEquals(listOf("p_arm"), gate.closeRun("run_arm").map { it.key() })
        assertNull(gate.current())
    }

    // ---- the arm's two calls against a gateway-shaped server ------------------

    @Test
    fun `placing the run posts the input and the status carries the pending approval`() {
        val s = session()
        val runId = s.startArmRun(ApprovalArm.PROMPT)
        assertEquals("run_arm_1", runId)

        val body = runBodies.poll(5, TimeUnit.SECONDS)
        assertNotNull(body)
        assertEquals(ApprovalArm.PROMPT, Json.str(Json.parse(body!!) as JObj, "input"))

        val req = requireNotNull(ApprovalArm.requestFromRunStatus(s.armRunStatus(runId)))
        assertEquals("prompt_9", req.requestId)
        assertEquals(3, req.executionGeneration)
    }
}
