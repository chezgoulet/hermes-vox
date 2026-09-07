package com.hermesvox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * H3 regression: the teardown-race guard returns a clean false (NEVER throws) when a
 * submit can't run. This is the 0.5.0.1 crash class — an unguarded exec.execute()
 * racing stop()'s shutdown threw RejectedExecutionException out of a main.post runnable
 * (the streaming-TTS hand-off) or the capture thread (the partial-STT worker), reached
 * the default uncaught handler and killProcess()ed the app. Two of the four submit sites
 * had no guard at all before H3. Pure JVM: the executor + the stopped flag are ordinary
 * inputs (same pattern as StreamRetirementState / BargeGate).
 */
class ExecGuardTest {

    @Test fun a_live_executor_accepts_and_runs_the_block() {
        val exec = Executors.newSingleThreadExecutor()
        try {
            val ran = AtomicBoolean(false)
            val ok = ExecGuard.submit(exec, stopped = false) { ran.set(true) }
            assertTrue("a live, non-stopped submit is accepted", ok)
            exec.shutdown()
            exec.awaitTermination(2, TimeUnit.SECONDS)
            assertTrue("...and the block actually runs", ran.get())
        } finally {
            exec.shutdownNow()
        }
    }

    @Test fun a_stopped_controller_never_submits() {
        val exec = Executors.newSingleThreadExecutor()
        try {
            val ran = AtomicBoolean(false)
            val ok = ExecGuard.submit(exec, stopped = true) { ran.set(true) }
            assertFalse("stopped -> a clean false (no throw)", ok)
            assertFalse("...and the block is never handed to the executor", ran.get())
        } finally {
            exec.shutdownNow()
        }
    }

    @Test fun a_shutdown_executor_never_submits() {
        val exec = Executors.newSingleThreadExecutor()
        exec.shutdown()   // stop() already ran — the executor is down
        val ran = AtomicBoolean(false)
        val ok = ExecGuard.submit(exec, stopped = false) { ran.set(true) }
        assertFalse("a shut-down executor -> a clean false (no throw)", ok)
        assertFalse("...and the block never runs", ran.get())
    }

    @Test fun a_shutdown_racing_the_submit_is_caught_not_thrown() {
        // The check-then-act race the guard exists for: isShutdown() still reads false,
        // but execute() rejects because a shutdown landed in between. The guard must
        // CATCH the RejectedExecutionException and return false — never let it escape.
        val ran = AtomicBoolean(false)
        val ok = ExecGuard.submit(RacingExecutor(), stopped = false) { ran.set(true) }
        assertFalse("the race returns a clean false, not a thrown exception", ok)
        assertFalse("...and the block never runs", ran.get())
    }

    /** An executor whose isShutdown() lies (false) but whose execute() always rejects —
     *  reproduces the check-then-act race deterministically (a real executor can't be
     *  caught mid-shutdown on demand). Only execute() + the lifecycle queries matter. */
    private class RacingExecutor : AbstractExecutorService() {
        override fun execute(command: Runnable) { throw RejectedExecutionException("shutdown raced the submit") }
        override fun shutdown() {}
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown(): Boolean = false
        override fun isTerminated(): Boolean = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = false
    }
}
