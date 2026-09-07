package com.hermesvox

import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

/**
 * ExecGuard — the pure teardown-race guard for submissions to the controller's
 * executor (the 0.5.0.1 crash class; wired into EVERY submit site in 0.5.3 H3).
 *
 * VoiceController.stop() runs exec.shutdown() while turns/workers may still be
 * handing off to the executor. An UNGUARDED exec.execute() has two failure modes:
 *  - the executor is already shut down -> RejectedExecutionException, and
 *  - the check-then-act race: a shutdown lands BETWEEN an `isShutdown` test and the
 *    execute() call -> RejectedExecutionException.
 * Thrown from a main.post runnable (the streaming-TTS hand-off) or from the capture
 * thread (the partial-STT worker), that exception is uncaught, reaches the default
 * handler (VoxLog) and killProcess()es the app — the exact crash 0.5.0.1 was written
 * to prevent. Two of the four submit sites had NO guard at all before H3.
 *
 * [submit] folds the guard into one place: bail to a clean `false` when the controller
 * is stopping ([stopped]) or the executor is down, AND catch the RejectedExecutionException
 * from the race. Callers treat `false` as "not running — clean up and move on", never as a
 * crash. Extracted from VoiceController.execSubmit so the contract is unit-proven off-device
 * (ExecGuardTest): the executor and the stopped flag are plain JVM inputs.
 */
internal object ExecGuard {

    /** Submit [block] to [exec], or return false (NEVER throw) if it can't run: the
     *  controller is [stopped], the executor is already shut down, or a shutdown races
     *  the submit (RejectedExecutionException). True only when [block] was accepted. */
    fun submit(exec: ExecutorService, stopped: Boolean, block: () -> Unit): Boolean {
        if (stopped || exec.isShutdown) return false
        return try {
            exec.execute(block)
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }
}
