package com.hermesvox

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H5 of the 0.9.1 surface review: the phone's engine runs on a dedicated single thread, a slow
 * platform call leaves the caller free, and a timeout tells the truth.
 *
 * The properties this file exists to keep:
 *
 *  * **the work is not on the caller's thread** — a slow platform call runs on the engine thread
 *    while the caller returns at its bound (a latch in the fake proves the work is still running);
 *  * **a timeout is not a cancellation** — the action finishes after the caller has stopped
 *    waiting, and `status` reports the real result;
 *  * **the timeout answer is uncertain** — `outcome_uncertain:true`, the words "may still
 *    complete", a pointer to `status`, and never "nothing was performed";
 *  * **one action at a time** — two submitted actions never enter together, which is what stops a
 *    later call cancelling a timer an earlier one set.
 */
class PhoneDispatchTest {

    /** A platform whose timer arm blocks until the test releases it. Everything else is inert. */
    private class SlowPlatform(
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : PhoneEngine.PhonePlatform {
        override fun startTimer(id: String, durationMs: Long, atEpochMs: Long): Boolean {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            return true
        }

        override fun startAlarm(id: String, hour: Int, minute: Int, atEpochMs: Long): Boolean = true
        override fun cancelTimer(id: String): Boolean = true
        override fun dndGranted(): Boolean = true
        override fun currentDnd(): Boolean? = false
        override fun setDnd(on: Boolean): Boolean = true
        override fun currentVolume(): Int? = 30
        override fun setVolume(percent: Int): Int? = percent
        override fun adjustVolume(direction: Int): Int? = 30 + direction
        override fun saveNote(id: String, text: String, atEpochMs: Long): Boolean = true
        override fun deleteNote(id: String): Boolean = true
        override fun mediaKey(key: PhoneEngine.PhonePlatform.MediaKey): Boolean = true
        override fun playback(): PhoneEngine.PhonePlatform.Playback =
            PhoneEngine.PhonePlatform.Playback(false, null, null, null)
        override fun deviceState(): PhoneEngine.PhonePlatform.DeviceSnapshot =
            PhoneEngine.PhonePlatform.DeviceSnapshot(
                null, null, null, null, null, null, null, null, null, null, null,
            )
        override fun clip(text: String): PhoneEngine.PhonePlatform.ClipWrite =
            PhoneEngine.PhonePlatform.ClipWrite.Done
    }

    private fun executor(): ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "phone-engine-test").apply { isDaemon = true } }

    private val t0 = 1_000_000L
    private val e0 = 1_700_000_000_000L

    @Test
    fun slow_work_runs_on_the_engine_thread_and_the_caller_returns_while_it_is_still_running() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exec = executor()
        try {
            val dispatch = PhoneDispatch(timeoutMs = 100, executor = exec)
            val caller = Thread.currentThread()
            var workThread: Thread? = null

            val outcome = dispatch.call {
                workThread = Thread.currentThread()
                started.countDown()
                // The caller's bound expires while this is still waiting: the work is alive.
                release.await(5, TimeUnit.SECONDS)
                "done"
            }

            assertEquals(
                "the caller stops waiting at its bound rather than blocking on the work",
                PhoneDispatch.Outcome.TimedOut,
                outcome,
            )
            assertTrue("the slow platform call had started", started.await(1, TimeUnit.SECONDS))
            assertNotEquals(
                "the platform work is not on the thread that made the call",
                caller,
                workThread,
            )
        } finally {
            release.countDown()
            exec.shutdownNow()
        }
    }

    @Test
    fun a_timeout_leaves_the_action_running_and_status_shows_its_real_outcome() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exec = executor()
        try {
            val engine = PhoneEngine(SlowPlatform(started, release), grants = PhoneGrants.Source {
                PhoneGrants.State.ALLOW
            })
            val dispatch = PhoneDispatch(timeoutMs = 100, executor = exec)

            val future = dispatch.submit {
                engine.dispatch(mapOf("action" to "timer", "duration_ms" to 60_000), t0, e0).json()
            }
            assertTrue("the timer arm is in progress", started.await(1, TimeUnit.SECONDS))
            assertEquals(PhoneDispatch.Outcome.TimedOut, dispatch.await(future))

            // The caller's answer is uncertain, never "nothing was performed".
            val uncertain = PhoneResult.uncertain("timer", LocalActions.TIMEOUT_DETAIL)
            assertFalse(uncertain.ok)
            assertEquals(PhoneReason.UNAVAILABLE, uncertain.reason)
            assertEquals(true, uncertain.facts["outcome_uncertain"])
            val detail = uncertain.detail!!
            assertTrue("it says the action may still complete: $detail", detail.contains("may still complete"))
            assertTrue("it points at status: $detail", detail.contains("status"))
            assertFalse(
                "it never claims nothing happened: $detail",
                detail.contains("nothing was performed"),
            )
            assertTrue(uncertain.json(), uncertain.json().contains("\"outcome_uncertain\":true"))

            // The work was not cancelled: it completes, and status reports the real result.
            release.countDown()
            val real = future.get(2, TimeUnit.SECONDS)
            assertTrue("the timer really was set: $real", real.contains("\"ok\":true"))
            val status = engine.dispatch(mapOf("action" to "status"), t0, e0)
            assertTrue(status.json(), status.ok)
            val running = status.facts["running"] as List<*>
            assertEquals("status shows the timer the timed-out call actually set", 1, running.size)
            assertTrue(
                (status.facts["registry"] as List<*>).contains("timer(remaining=1m00s)"),
            )
        } finally {
            release.countDown()
            exec.shutdownNow()
        }
    }

    @Test
    fun two_actions_in_flight_do_not_interleave() {
        val events = Collections.synchronizedList(ArrayList<String>())
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val exec = executor()
        try {
            val dispatch = PhoneDispatch(timeoutMs = 5_000, executor = exec)
            val first = dispatch.submit {
                events.add("enter-1")
                firstEntered.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS)
                events.add("exit-1")
                "one"
            }
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
            val second = dispatch.submit {
                events.add("enter-2")
                events.add("exit-2")
                "two"
            }

            // If a second thread could run it, the second action would enter while the first sleeps.
            Thread.sleep(100)
            assertFalse(
                "the second action must not start while the first is in flight: $events",
                events.contains("enter-2"),
            )

            releaseFirst.countDown()
            assertEquals("one", first.get(2, TimeUnit.SECONDS))
            assertEquals("two", second.get(2, TimeUnit.SECONDS))
            assertEquals(
                "one complete action, then the next",
                listOf("enter-1", "exit-1", "enter-2", "exit-2"),
                events.toList(),
            )
        } finally {
            releaseFirst.countDown()
            exec.shutdownNow()
        }
    }
}
