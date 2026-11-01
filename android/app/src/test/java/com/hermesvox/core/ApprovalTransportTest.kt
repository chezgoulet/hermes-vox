package com.hermesvox.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #282 — the wire path against a fake transport: the SSE `approval.request` is parsed
 * on the live stream, and the answer is a real `POST /v1/runs/{run_id}/approval` with
 * the event's `request_id` and one of the offered choices. The fake blocks the turn on
 * the answer, so the same test proves the run continues afterwards.
 */
class ApprovalTransportTest {

    private lateinit var server: HttpServer
    private lateinit var pool: java.util.concurrent.ExecutorService
    private val posted = LinkedBlockingQueue<Pair<String, String>>()
    private val answered = CountDownLatch(1)

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        pool = Executors.newCachedThreadPool { r -> Thread(r, "approval-fake").apply { isDaemon = true } }
        server.executor = pool
        server.createContext("/v1/responses") { ex -> responses(ex) }
        server.createContext("/v1/runs") { ex -> approval(ex) }
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
        pool.shutdownNow()
    }

    private fun responses(ex: HttpExchange) {
        try {
            val raw = ex.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            val req = Json.parse(raw) as JObj
            val stream = (req["stream"] as? JBool)?.value ?: false
            if (!stream) {
                ex.sendResponseHeaders(400, -1); return
            }
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            val out = ex.responseBody
            write(out, "response.created",
                """{"type":"response.created","response":{"id":"resp_approval_1","status":"in_progress"}}""")
            // The run event shape: the name rides both the SSE event line and `event`;
            // there is deliberately no `type`, exactly like the gateway's `_run_event`.
            write(out, "approval.request",
                """{"event":"approval.request","run_id":"resp_approval_1","request_id":"req_1","command":"rm -rf /tmp/vox-approval-probe","description":"recursive delete","reason":"destructive","choices":["once","deny"],"execution_generation":7,"timestamp":1}""")
            answered.await(5, TimeUnit.SECONDS)
            write(out, "response.output_text.delta",
                """{"type":"response.output_text.delta","item_id":"msg_1","delta":"continued after approval"}""")
            write(out, "response.completed",
                """{"type":"response.completed","response":{"id":"resp_approval_1","status":"completed"}}""")
            out.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
            out.flush()
        } finally {
            ex.close()
        }
    }

    private fun approval(ex: HttpExchange) {
        try {
            val body = ex.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            posted.add(ex.requestURI.path to body)
            ex.responseHeaders.add("Content-Type", "application/json")
            val out = """{"object":"hermes.run.approval_response","run_id":"resp_approval_1","choice":"deny","resolved":1}"""
                .toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(200, out.size.toLong())
            ex.responseBody.use { it.write(out) }
            answered.countDown()
        } finally {
            ex.close()
        }
    }

    private fun write(out: java.io.OutputStream, name: String, data: String) {
        out.write("event: $name\ndata: $data\n\n".toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun url() = "http://127.0.0.1:${server.address.port}"

    @Test
    fun `an approval on the live stream is answered once and the run continues`() {
        val session = HermesSession(url(), "fake-key", "hermes-agent")
        val sid = session.startStream("trigger the approval")
        var approval: ApprovalRequest? = null
        var done: JObj? = null
        val deadline = System.currentTimeMillis() + 10_000

        while (System.currentTimeMillis() < deadline) {
            session.waitStream(sid, 100)
            val poll = Json.parse(session.pollStreamJSON(sid)) as JObj
            (poll["events"] as? JArr)?.items?.forEach { node ->
                val e = node as JObj
                if (Json.str(e, "type") == "approval.request") {
                    val a = (e["approval"] as JObj)
                    approval = ApprovalRequest(
                        runId = Json.str(a, "run_id"),
                        requestId = Json.str(a, "request_id"),
                        command = Json.str(a, "command"),
                        description = Json.str(a, "description"),
                        reason = Json.str(a, "reason"),
                        choices = Json.arr(a, "choices").map { (it as JStr).value },
                        executionGeneration = (a["execution_generation"] as? JNum)?.raw?.toIntOrNull(),
                    )
                    // The same real transport the controller uses.
                    session.answerApproval(
                        approval!!.runId, "deny", approval!!.requestId, approval!!.executionGeneration)
                }
            }
            if ((poll["done"] as JBool).value) { done = poll; break }
        }

        assertNotNull(approval)
        val request = approval!!
        assertEquals("resp_approval_1", request.runId)
        assertEquals("req_1", request.requestId)
        assertEquals(listOf("once", "deny"), request.choices)
        assertEquals("recursive delete — rm -rf /tmp/vox-approval-probe", request.action())

        val sent = posted.poll(2, TimeUnit.SECONDS)
        assertNotNull(sent)
        assertEquals("/v1/runs/resp_approval_1/approval", sent!!.first)
        val body = Json.parse(sent.second) as JObj
        assertEquals("deny", Json.str(body, "choice"))
        assertEquals("req_1", Json.str(body, "request_id"))
        assertEquals(7, (body["execution_generation"] as JNum).raw.toInt())

        assertNotNull(done)
        val terminal = done!!
        assertEquals("", Json.str(terminal, "error"))
        assertTrue(Json.str(terminal, "text").contains("continued after approval"))
    }
}
