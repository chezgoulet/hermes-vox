package com.hermesvox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * L2 (0.9.1 review): the turn budget pauses while THIS turn has a pending card, wherever
 * it sits in the queue — not only when it heads it. `ApprovalGateTest` pins
 * `pendingFor` behind a held card; this pins that the controller's predicate uses it.
 */
class TurnApprovalWaitingTest {

    private val vc = File("src/main/java/com/hermesvox/VoiceController.kt").readText()
    private val fn = vc.substringAfter("private fun turnApprovalWaiting(): Boolean").substringBefore("\n\n")

    @Test
    fun `the budget pause keys on this turn's pending requests`() {
        assertTrue(fn, fn.contains("turnApprovalPending().isNotEmpty()"))
        assertFalse(fn, fn.contains("approvalGate.current()"))
        assertTrue(vc.contains("budget.approvalPending(turnApprovalWaiting(), tick)"))
    }
}
