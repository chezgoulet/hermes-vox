package com.hermesvox.core

import com.hermesvox.StreamPollGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 120 s turn budget measures AGENT time, not wall time.
 *
 * The bug this pins: the deadline was fixed at turn submit, so a human deciding an
 * approval spent the agent's budget; when it expired the finally withdrew the card
 * while the run was still parked in `waiting_for_approval`. The fix pauses the clock
 * while the card is up and accumulates that time — it never restarts the clock.
 */
class TurnBudgetTest {

    private class Clock(var now: Long = 0L) {
        fun advance(ms: Long) { now += ms }
    }

    private fun request(runId: String = "run-1", requestId: String = "req-1") =
        ApprovalRequest(
            runId = runId,
            requestId = requestId,
            command = "rm -rf /tmp/vox-probe",
            description = "recursive delete",
            reason = "destructive",
            choices = listOf("once", "deny"),
        )

    @Test
    fun `a turn with an approval pending past the 120s budget does not time out`() {
        val clock = Clock()
        val budget = TurnBudget(now = clock::now)
        val gate = ApprovalGate()
        gate.offer(request(), runAlive = true)

        val answeredAtMs = 250_000L
        while (true) {
            budget.approvalPending(gate.current() != null, clock.now)
            if (!budget.keepPolling(done = false, atMs = clock.now)) break
            clock.advance(1_000)
            assertTrue("the turn ran away; the pause is not holding", clock.now < 1_000_000)
            if (clock.now == answeredAtMs) gate.answer("req-1", "once", "touch")
        }

        assertTrue(
            "the turn must survive an approval held past the ${StreamPollGate.STREAM_TURN_TIMEOUT_MS}ms budget",
            clock.now > StreamPollGate.STREAM_TURN_TIMEOUT_MS,
        )
        // 250 s of human time + the 120 s agent budget: the clock moved, the budget did not.
        assertEquals(370_000L, clock.now)
        assertEquals(250_000L, budget.pausedMs(clock.now))
    }

    @Test
    fun `the same turn without an approval still times out at 120s`() {
        val clock = Clock()
        val budget = TurnBudget(now = clock::now)

        while (budget.keepPolling(done = false, atMs = clock.now)) {
            budget.approvalPending(pending = false, atMs = clock.now)
            clock.advance(1_000)
        }

        assertEquals(StreamPollGate.STREAM_TURN_TIMEOUT_MS, clock.now)
    }

    @Test
    fun `paused time accumulates across approvals and never restarts the clock`() {
        val clock = Clock()
        val budget = TurnBudget(now = clock::now)

        budget.approvalPending(true)        // first card at 0 s
        clock.advance(60_000)
        budget.approvalPending(true)        // a poll tick must NOT restart the pause
        clock.advance(30_000)
        budget.approvalPending(false)       // answered at 90 s -> 90 s paused
        assertEquals(90_000L, budget.pausedMs(clock.now))
        assertEquals(0L, budget.elapsedMs(clock.now))

        clock.advance(10_000)
        budget.approvalPending(true)        // second card at 100 s
        clock.advance(20_000)
        budget.approvalPending(false)       // answered at 120 s -> +20 s
        assertEquals(110_000L, budget.pausedMs(clock.now))
        assertEquals(10_000L, budget.elapsedMs(clock.now))
        assertEquals(230_000L, budget.deadlineMs(clock.now))
    }

    @Test
    fun `an approval that arrives just before the deadline still extends it`() {
        val clock = Clock()
        val budget = TurnBudget(now = clock::now)

        clock.advance(119_000)
        budget.approvalPending(true, clock.now)   // card with 1 s of agent budget left
        clock.advance(60_000)
        assertTrue("a pending approval may never expire", budget.keepPolling(false, clock.now))
        assertEquals(119_000L, budget.elapsedMs(clock.now))   // the minute was the human's
    }

    @Test
    fun `a held card from an earlier turn is not this turn's pending request`() {
        val gate = ApprovalGate()
        val held = request(runId = "run-old", requestId = "req-old")
        val mine = request(runId = "run-new", requestId = "req-new")
        gate.offer(held, runAlive = true)
        gate.offer(mine, runAlive = true)

        // The new turn's keys select only its own card, even though the OLD card is the
        // one currently on screen (ApprovalGate shows the oldest first).
        val thisTurn = setOf(mine.key())
        assertEquals(listOf("req-new"), gate.pendingFor(thisTurn).map { it.key() })
        assertEquals("req-old", gate.current()?.key())
        assertTrue("a turn that raised nothing selects nothing", gate.pendingFor(emptySet()).isEmpty())
    }

    @Test
    fun `a lost stream holds an answerable card but a finished turn withdraws it`() {
        val pending = listOf(request())
        assertEquals(setOf("run-1"), TurnApprovalHold.keep(false, abandoned = false, cancelled = false, pending))
        assertEquals(emptySet<String>(), TurnApprovalHold.keep(true, abandoned = false, cancelled = false, pending))
        assertEquals(emptySet<String>(), TurnApprovalHold.keep(false, abandoned = true, cancelled = false, pending))
        assertEquals(emptySet<String>(), TurnApprovalHold.keep(false, abandoned = false, cancelled = true, pending))
        assertEquals(emptySet<String>(), TurnApprovalHold.keep(false, abandoned = false, cancelled = false, emptyList()))
    }
}
