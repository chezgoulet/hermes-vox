package com.hermesvox

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M1 (0.9.1 review): a barge-in that lands before the turn's stream id is recorded
 * cancels nothing, so the stream loop's own cancelled exits must retire the stream
 * (`session.cancelStream(sid)`). The loop is Android-bound, so the wiring is pinned at
 * source level; `RunsStreamTest` proves what the cancel does (a run admitted mid-cancel
 * is stopped, the state retired), and `StreamParityTest` that a second cancel is a no-op.
 */
class StreamLoopCancelTest {

    private val vc = File("src/main/java/com/hermesvox/VoiceController.kt").readText()

    @Test
    fun `the in-loop cancelled exit retires its own stream`() {
        val exit = vc.substringAfter("if (genCancelled || replies.isDead(gen)) {").substringBefore("break")
        assertTrue(exit, exit.contains("cancelOwnStream(sid)"))
    }

    @Test
    fun `the after-loop cancelled exit retires its own stream`() {
        val line = vc.lines().single { it.trim().startsWith("if (genCancelled) {") && "turn interrupted during generation" in it }
        assertTrue(line, line.contains("cancelOwnStream(sid)"))
    }

    @Test
    fun `the helper cancels through the session and never throws`() {
        val body = vc.substringAfter("private fun cancelOwnStream(sid: String) {").substringBefore("\n    }")
        assertTrue(body, body.contains("session.cancelStream(sid)"))
        assertTrue(body, body.contains("catch (_: Exception)"))
    }
}
