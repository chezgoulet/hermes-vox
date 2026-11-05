package com.hermesvox

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.ExecutionException

/**
 * PhoneDispatch — the one place a mind-initiated phone action is run off the thread that draws.
 *
 * Before this, [LocalActions.phone] posted the **whole dispatch** to the main looper and waited.
 * That put SAF child queries for a depth-5 walk, `readRange`, `countBytes`, `FileStage.stage`
 * copying up to 100 MB, and the calendar/contact providers on the UI thread, under
 * [PhoneEngine]'s lock, while a call was live (H5). An ANR did not fail one action; it took the
 * voice loop with it.
 *
 * **One action at a time.** A single-thread executor replaces the main looper as the serialization
 * point. Two actions cannot interleave, so a timer is never cancelled by a call that arrived later,
 * and the approval handshake keeps its synchronous meaning: an approved id is burned by
 * [PhoneApproval] before execution, and only one execution can be in flight.
 *
 * **A timeout is not a refusal of the work.** [await] stops *waiting* at the caller's bound; it
 * does not cancel the future, so the action carries on and is audited with its real outcome. The
 * caller gets [Outcome.TimedOut] and must answer with
 * [PhoneResult.uncertain] — never "nothing was performed".
 *
 * Pure JVM: the executor is injected, so [PhoneDispatchTest] proves the caller returns while the
 * fake work is still running, and that two submitted actions never enter together.
 */
class PhoneDispatch(
    private val timeoutMs: Long,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "phone-engine").apply { isDaemon = true }
    },
) {

    /** What the caller learned by the time its bound ran out. */
    sealed class Outcome {
        /** The work finished and produced its JSON answer. */
        data class Returned(val json: String) : Outcome()

        /** The bound ran out. The work may still be running, and may still complete. */
        data object TimedOut : Outcome()
    }

    /** Queue one action on the engine thread. */
    fun submit(work: () -> String): Future<String> = executor.submit<String> { work() }

    /** Wait up to [timeoutMs] for [future]. A timeout leaves the work running on purpose. */
    fun await(future: Future<String>): Outcome = try {
        Outcome.Returned(future.get(timeoutMs, TimeUnit.MILLISECONDS))
    } catch (_: TimeoutException) {
        Outcome.TimedOut
    } catch (e: ExecutionException) {
        // The engine reported its own failure; the caller decides whether that is uncertain.
        throw e.cause ?: e
    }

    /** Submit and wait in one step. */
    fun call(work: () -> String): Outcome = await(submit(work))
}
