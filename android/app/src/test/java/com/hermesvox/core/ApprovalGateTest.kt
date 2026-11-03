package com.hermesvox.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #282 — the approval contract, proven off-device.
 *
 * The gateway's `approval.request` is the source of truth: a subset of choices must
 * render as that subset, an unknown word must never become a button, and the only
 * automatic decision is a deny. These are the safety properties, so they are pure JVM
 * over the same core the stream and the controller use.
 */
class ApprovalGateTest {

    private fun request(
        requestId: String = "req-1",
        runId: String = "resp-1",
        choices: List<String> = listOf("once", "session", "always", "deny"),
        command: String = "rm -rf /tmp/vox-probe",
        description: String = "recursive delete",
        reason: String = "destructive",
    ) = ApprovalRequest(runId, requestId, command, description, reason, choices)

    // ---- parsing -------------------------------------------------------------

    @Test
    fun `parses an approval request with a subset of choices`() {
        val env = Envelope.decode(
            """{"event":"approval.request","run_id":"resp-1","request_id":"req-1",
               "command":"rm -rf /tmp/vox-probe","description":"recursive delete",
               "reason":"destructive","choices":["once","deny"],"timestamp":1}""",
        )
        val approval = env.approval!!
        assertEquals("resp-1", approval.runId)
        assertEquals("req-1", approval.requestId)
        assertEquals("rm -rf /tmp/vox-probe", approval.command)
        assertEquals("recursive delete", approval.description)
        assertEquals("destructive", approval.reason)
        assertEquals(listOf("once", "deny"), approval.choices)
        assertEquals("Approve: recursive delete — rm -rf /tmp/vox-probe — why: destructive", approval.line())
    }

    @Test
    fun `an sse name without a type field still identifies the event`() {
        val env = Envelope.decode(
            """{"event":"approval.request","run_id":"resp-2","request_id":"req-2","choices":["deny"]}""",
        )
        assertEquals("approval.request", env.eventName)
        assertNotNull(env.approval)
    }

    @Test
    fun `a session-authority generation fence is carried back only when sent`() {
        val fenced = Envelope.decode(
            """{"event":"approval.request","run_id":"resp-4","request_id":"req-4",
               "choices":["once","deny"],"execution_generation":7}""",
        ).approval!!
        assertEquals(7, fenced.executionGeneration)
        assertEquals(7, fenced.toMap()["execution_generation"])

        val plain = Envelope.decode(
            """{"event":"approval.request","run_id":"resp-5","request_id":"req-5","choices":["deny"]}""",
        ).approval!!
        assertNull(plain.executionGeneration)
        assertFalse(plain.toMap().containsKey("execution_generation"))
    }

    // ---- choices -------------------------------------------------------------

    @Test
    fun `unknown gateway choices are not rendered`() {
        assertEquals(
            listOf("once", "always", "deny"),
            ApprovalChoices.sanitize(listOf("once", "sudo", "always", "deny")),
        )
    }

    @Test
    fun `deny is always offered even when the gateway list omits it`() {
        assertEquals(listOf("once", "deny"), ApprovalChoices.sanitize(listOf("once")))
        assertEquals(listOf("deny"), ApprovalChoices.sanitize(emptyList()))
    }

    @Test
    fun `a choice the gateway did not offer cannot be answered`() {
        val gate = ApprovalGate()
        gate.offer(request(choices = listOf("once", "deny")), runAlive = true)
        assertNull(gate.answer("req-1", "always", "touch"))
        assertNull(gate.answer("req-1", "sudo", "touch"))
        assertNotNull(gate.answer("req-1", "once", "touch"))
    }

    // ---- voice ---------------------------------------------------------------

    @Test
    fun `approve takes the least privilege choice offered`() {
        assertEquals("once", ApprovalVoice.resolve("approve", listOf("once", "session", "always", "deny")))
        assertEquals("session", ApprovalVoice.resolve("yes", listOf("session", "always", "deny")))
        assertEquals("always", ApprovalVoice.resolve("go ahead", listOf("always", "deny")))
        assertNull(ApprovalVoice.resolve("approve", listOf("deny")))
    }

    @Test
    fun `no always means deny`() {
        assertEquals("deny", ApprovalVoice.resolve("no", listOf("once", "deny")))
        assertEquals("deny", ApprovalVoice.resolve("No, don't approve", listOf("once", "deny")))
        assertEquals("deny", ApprovalVoice.resolve("cancel that", listOf("once", "deny")))
    }

    @Test
    fun `ordinary speech is not mistaken for an answer`() {
        assertNull(ApprovalVoice.resolve("what is the weather", listOf("once", "deny")))
        assertNull(ApprovalVoice.resolve("notify me later", listOf("once", "deny")))
        assertNull(ApprovalVoice.resolve("token bucket", listOf("once", "deny")))
    }

    // ---- H1 (0.9.1 review): thinking aloud is never a yes -------------------

    private val all = listOf("once", "session", "always", "deny")

    @Test
    fun `I'm not sure does not approve`() {
        assertNull(ApprovalVoice.resolve("I'm not sure", all))
        assertNull(ApprovalVoice.resolve("I’m not sure.", all))
        assertNull(ApprovalVoice.resolve("not sure", all))
    }

    @Test
    fun `okay what is that does not approve`() {
        assertNull(ApprovalVoice.resolve("okay what is that", all))
        assertNull(ApprovalVoice.resolve("OK, what does that command do?", all))
    }

    @Test
    fun `where does that go does not approve`() {
        assertNull(ApprovalVoice.resolve("where does that go", all))
        assertNull(ApprovalVoice.resolve("where does that go?", all))
    }

    @Test
    fun `bare soft words are not an approval`() {
        for (soft in listOf("sure", "ok", "okay", "go", "allow", "yeah sure", "ok go")) {
            assertNull(soft, ApprovalVoice.resolve(soft, all))
        }
    }

    @Test
    fun `an approval inside a longer sentence is not an approval`() {
        assertNull(ApprovalVoice.resolve("yes but first tell me what it does", all))
        assertNull(ApprovalVoice.resolve("I would approve that later", all))
        assertNull(ApprovalVoice.resolve("do it?", all))
        assertNull(ApprovalVoice.resolve("don't know, approve?", all))
    }

    @Test
    fun `a negation that is not a plain no re-prompts rather than denies`() {
        assertNull(ApprovalVoice.resolve("not yet", all))
        assertNull(ApprovalVoice.resolve("no I'm not sure", all))
        assertEquals("deny", ApprovalVoice.resolve("No.", all))
        assertEquals("deny", ApprovalVoice.resolve("not now", all))
    }

    @Test
    fun `plain approvals still approve`() {
        for (yes in listOf("Approve.", "yes", "Yes, approve.", "approve once", "do it", "go ahead")) {
            assertEquals(yes, "once", ApprovalVoice.resolve(yes, all))
        }
    }

    // ---- safety: timeout is no ----------------------------------------------

    @Test
    fun `nothing is answered before a timeout`() {
        val gate = ApprovalGate(timeoutMs = 1)
        gate.offer(request(), runAlive = true)
        assertEquals(1, gate.pendingCount())
        // Merely offering a request must never approve it — the surface has to be shown.
        assertNotNull(gate.current())
        assertEquals(1, gate.pendingCount())
    }

    @Test
    fun `timeout decides deny and never a positive choice`() {
        val gate = ApprovalGate()
        gate.offer(request(choices = listOf("once", "session", "always", "deny")), runAlive = true)
        val first = gate.timeout("req-1")!!
        assertEquals("deny", first.choice)
        // A second timeout for the same request is a local no-op.
        assertNull(gate.timeout("req-1"))

        val fresh = ApprovalGate()
        fresh.offer(request(), runAlive = true)
        val timedOut = fresh.timeout("req-1")!!
        assertEquals("deny", timedOut.choice)
        assertEquals("timeout", timedOut.decision)
        assertTrue(timedOut.timedOut)
        assertEquals(0, fresh.pendingCount())
    }

    @Test
    fun `a human answer wins a race against the timeout`() {
        val gate = ApprovalGate()
        gate.offer(request(), runAlive = true)
        assertEquals("once", gate.answer("req-1", "once", "voice")?.choice)
        assertNull(gate.timeout("req-1"))   // the late timeout loses and posts nothing
    }

    // ---- exactly once --------------------------------------------------------

    @Test
    fun `a duplicate answer is suppressed`() {
        val gate = ApprovalGate()
        gate.offer(request(), runAlive = true)
        val first = gate.answer("req-1", "once", "touch")!!
        assertEquals("once", first.decision)
        assertNull(gate.answer("req-1", "once", "touch"))   // double tap
        assertNull(gate.answer("req-1", "deny", "voice"))   // a later different answer
        assertEquals(0, gate.pendingCount())
    }

    @Test
    fun `the decision log line carries the run and request ids`() {
        val gate = ApprovalGate()
        gate.offer(request(), runAlive = true)
        val line = gate.timeout("req-1")!!.logLine()
        assertEquals(
            "event=approval decision=timeout choice=deny run=resp-1 request=req-1 cause=timeout",
            line,
        )

        val answered = ApprovalGate()
        answered.offer(request(), runAlive = true)
        assertEquals(
            "event=approval decision=once choice=once run=resp-1 request=req-1 cause=touch",
            answered.answer("req-1", "once", "touch")!!.logLine(),
        )
    }

    // ---- run lifecycle -------------------------------------------------------

    @Test
    fun `a request arriving after the run ended is ignored`() {
        val gate = ApprovalGate()
        assertNull(gate.offer(request(), runAlive = false))
        assertNull(gate.current())
        assertEquals(0, gate.pendingCount())
    }

    @Test
    fun `closing the run withdraws its request without answering it`() {
        val gate = ApprovalGate()
        gate.offer(request(), runAlive = true)
        assertEquals(1, gate.closeRun("resp-1").size)
        assertNull(gate.current())
        assertNull(gate.answer("req-1", "deny", "touch"))
        // A duplicate event for the same request is not surfaced again.
        assertNull(gate.offer(request(), runAlive = true))
    }

    @Test
    fun `a pending request is shown and the next one follows it`() {
        val gate = ApprovalGate()
        gate.offer(request(requestId = "req-1"), runAlive = true)
        gate.offer(request(requestId = "req-2"), runAlive = true)
        assertEquals("req-1", gate.current()?.key())
        gate.answer("req-1", "once", "touch")
        assertEquals("req-2", gate.current()?.key())
        gate.answer("req-2", "deny", "touch")
        assertNull(gate.current())
    }

    @Test
    fun `a malformed choices list still leaves deny`() {
        val env = Envelope.decode(
            """{"event":"approval.request","run_id":"resp-3","request_id":"req-3","choices":"once"}""",
        )
        val approval = env.approval!!
        assertTrue(approval.choices.isEmpty())
        val gate = ApprovalGate()
        val accepted = gate.offer(approval, runAlive = true)!!
        assertEquals(listOf("deny"), accepted.choices)
        assertNull(gate.answer(accepted.key(), "once", "touch"))
    }
}
