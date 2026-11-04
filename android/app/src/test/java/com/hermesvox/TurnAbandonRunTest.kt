package com.hermesvox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * L1 (0.9.1 review): abandoning a turn stops THIS turn's run and nobody else's. While the
 * admission is in flight the stream has no run id, and the card heading the approval
 * queue may be an earlier turn's held card or the arm's. The controller is Android-bound,
 * so the choice is pinned at source level; `TurnAbandonTest` pins that an empty id still
 * closes the turn's own stream, and `RunsStreamTest` that a stream cancelled during its
 * admission stops the run the admission names.
 */
class TurnAbandonRunTest {

    private val abandon = File("src/main/java/com/hermesvox/VoiceController.kt").readText()
        .substringAfter("private fun abandonTurn(").substringBefore("\n    }")

    @Test
    fun `the abandoned run is the stream's own`() {
        assertTrue(abandon, abandon.contains("val runId = session.runIdOf(streamId)\n"))
    }

    @Test
    fun `the approval queue is never a source of the run to stop`() {
        assertFalse(abandon, abandon.contains("approvalGate.current()"))
    }
}
