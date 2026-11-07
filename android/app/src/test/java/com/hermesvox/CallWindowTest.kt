package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launch path that started this feature: `apps.launch` / `open` mid-call.
 *
 * Android 12+ shrinks the call when the *caller* swipes home; it does not know why the
 * app itself is starting an activity. So the app arranges the order by hand — enter PiP,
 * then start — and this test proves it with a fake host, no Activity and no device:
 *
 *  * live call -> PiP first, the intent after the window exists;
 *  * no live call -> straight to the intent, the window never touched;
 *  * PiP refused -> the intent still starts (a launch is never failed because of PiP).
 */
class CallWindowTest {

    /** Records each step in the order it happened. */
    private class FakeHost(
        private var live: Boolean,
        private val accepts: Boolean = true,
        private val acceptsPost: Boolean = true,
    ) : CallWindow.Host {
        val events = mutableListOf<String>()
        private val posted = mutableListOf<() -> Unit>()

        override fun callLive(): Boolean = live

        override fun enterPip(): Boolean {
            events += "pip"
            return accepts
        }

        override fun post(delayMs: Long, action: () -> Unit): Boolean {
            assertTrue("a posted launch is not immediate", delayMs > 0)
            events += "post"
            if (!acceptsPost) return false
            posted += action
            return true
        }

        /** Run what the host posted — the device's main-thread handler, in the test. */
        fun runPosted() {
            val due = posted.toList()
            posted.clear()
            due.forEach { it() }
        }
    }

    @Test
    fun apps_launch_enters_pip_before_the_intent_when_a_call_is_live() {
        val host = FakeHost(live = true)
        var launched = 0
        val plan = CallWindow.launch(host) { launched++; host.events += "launch" }

        assertEquals(CallWindow.Order.PIP_THEN_LAUNCH, plan.order)
        assertTrue("the window took the posted start", plan.posted)
        assertEquals("PiP first", listOf("pip", "post"), host.events)
        assertEquals("the intent waits for the window", 0, launched)

        host.runPosted()
        assertEquals(listOf("pip", "post", "launch"), host.events)
        assertEquals(1, launched)
    }

    @Test
    fun a_window_that_is_closing_does_not_report_a_posted_launch() {
        val host = FakeHost(live = true, acceptsPost = false)
        var launched = 0
        val plan = CallWindow.launch(host) { launched++ }

        assertEquals(CallWindow.Order.PIP_THEN_LAUNCH, plan.order)
        assertFalse("the host refused the post", plan.posted)
        assertEquals("nothing ran", 0, launched)
    }

    @Test
    fun apps_launch_does_not_touch_pip_without_a_live_call() {
        val host = FakeHost(live = false)
        val plan = CallWindow.launch(host) { host.events += "launch" }

        assertEquals(CallWindow.Order.LAUNCH_NOW, plan.order)
        assertEquals(listOf("launch"), host.events)
    }

    @Test
    fun the_launch_still_happens_when_pip_cannot_be_entered() {
        // A device without the feature, an OEM that refused, or a window that would not
        // float: the call continues in the background exactly as before.
        val host = FakeHost(live = true, accepts = false)
        var launched = 0
        val plan = CallWindow.launch(host) { launched++; host.events += "launch" }

        assertEquals(CallWindow.Order.LAUNCH_NOW, plan.order)
        assertEquals(listOf("pip", "launch"), host.events)
        assertEquals(1, launched)
    }

    @Test
    fun the_window_exists_before_the_intent_but_not_long_after_the_tap() {
        assertTrue(
            "a quarter second: past the PiP transition, still immediate to a hand",
            CallWindow.AFTER_PIP_MS in 1..500,
        )
    }
}
