package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tick's schedule, without a device and without a `Handler`.
 *
 * The owner's complaint was a screen that refreshed itself every second while nothing was
 * happening. `PhoneToolsSheet` re-posted its tick unconditionally (`main.postDelayed(this, 1_000L)`)
 * — a leftover from when the listening window counted down. [PhoneToolsPoll] exists so the rule is
 * an object a test can hold: a tick is posted while an attempt is in flight, and a settled screen
 * has none scheduled at all.
 *
 * The scheduling primitives are injected, so what is asserted here is the real decision the sheet
 * makes — [PhoneToolsPoll.follow] — not a copy of it.
 */
class PhoneToolsPollTest {

    /** Every tick the poll asked for, in order. */
    private val posted = mutableListOf<Runnable>()

    /** Every tick the poll took back. */
    private val cancelled = mutableListOf<Runnable>()

    /** How many times a tick has run. */
    private var ticks = 0

    private val poll = PhoneToolsPoll(
        post = { _, what -> posted += what },
        cancel = { cancelled += it },
        onTick = { ticks++ },
    )

    private val settled = listOf(
        PhoneBootstrap.Step.IDLE,
        PhoneBootstrap.Step.CONNECTED,
        PhoneBootstrap.Step.FAILED,
    )

    private val inFlight = listOf(
        PhoneBootstrap.Step.ADDING,
        PhoneBootstrap.Step.WAITING,
    )

    // ---- a settled screen has no clock ----

    @Test fun a_settled_step_schedules_no_tick_at_all() {
        for (step in settled) {
            poll.follow(step)
            assertFalse("$step must not schedule a tick", poll.scheduled)
        }
        assertEquals("not one tick was posted", emptyList<Runnable>(), posted)
    }

    @Test fun a_screen_that_settles_stops_the_tick_it_had() {
        poll.follow(PhoneBootstrap.Step.ADDING)
        assertTrue(poll.scheduled)
        val tick = posted.single()

        poll.follow(PhoneBootstrap.Step.CONNECTED)
        assertFalse("the tick is gone the moment the step settles", poll.scheduled)
        assertEquals("and it was taken back, not left to fire", listOf(tick), cancelled)
        assertEquals("no second tick was posted", 1, posted.size)
    }

    @Test fun each_settled_step_cancels_a_running_tick() {
        for (step in settled) {
            val p = PhoneToolsPoll(
                post = { _, what -> posted += what },
                cancel = { cancelled += it },
                onTick = {},
            )
            p.follow(PhoneBootstrap.Step.WAITING)
            assertTrue(p.scheduled)
            p.follow(step)
            assertFalse("$step stopped the tick", p.scheduled)
        }
        assertEquals("three ticks posted, three taken back", posted.size, cancelled.size)
    }

    // ---- an attempt in flight has one ----

    @Test fun an_attempt_in_flight_schedules_exactly_one_tick_a_second() {
        for (step in inFlight) {
            posted.clear()
            val p = PhoneToolsPoll(post = { _, what -> posted += what }, cancel = {}, onTick = {})
            p.follow(step)
            assertTrue("$step needs a tick", p.scheduled)
            assertEquals("one tick for $step, not five", 1, posted.size)
        }
    }

    @Test fun following_again_does_not_double_post() {
        poll.follow(PhoneBootstrap.Step.ADDING)
        poll.follow(PhoneBootstrap.Step.ADDING)
        poll.follow(PhoneBootstrap.Step.WAITING)
        assertEquals("at most one tick is posted at a time", 1, posted.size)
        assertTrue(poll.scheduled)
    }

    @Test fun a_tick_runs_once_and_re_arms_only_when_the_screen_asks_again() {
        poll.follow(PhoneBootstrap.Step.ADDING)
        assertEquals(0, ticks)

        // What the Handler does a second later.
        posted.single().run()
        assertEquals("the tick ran once", 1, ticks)
        assertFalse("and it is no longer posted", poll.scheduled)
        assertEquals("nothing re-armed it by itself — the screen's follow() does that", 1, posted.size)

        // The screen's tick ends by following the step it just drew; still in flight, so one more.
        poll.follow(PhoneBootstrap.Step.ADDING)
        assertTrue(poll.scheduled)
        assertEquals(2, posted.size)
    }

    @Test fun a_tick_that_reveals_a_settled_step_does_not_re_arm() {
        poll.follow(PhoneBootstrap.Step.WAITING)
        posted.single().run()
        assertFalse(poll.scheduled)
        // The screen's tick reads the state, finds FAILED, and follows it: nothing is posted.
        poll.follow(PhoneBootstrap.Step.FAILED)
        assertEquals("no tick is scheduled for a failed attempt", 1, posted.size)
        assertFalse(poll.scheduled)
    }

    @Test fun the_rule_names_the_two_steps_of_an_attempt() {
        assertEquals(
            inFlight.toSet(),
            PhoneBootstrap.Step.entries.filter { PhoneToolsPoll.ticks(it) }.toSet(),
        )
        assertEquals(1_000L, PhoneToolsPoll.PERIOD_MS)
    }
}
