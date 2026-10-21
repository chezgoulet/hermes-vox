package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WorkingRender — the ordering that decides whether a mid-run line lands.
 *
 * The field fault (docs/PRESENCE-LATE-DIAGNOSIS.md): the render was requested when the slot
 * came due, so its ~2.5 s sat between "due" and "delivered" and the mind's first text could
 * land inside it. Requesting it while the run is still working moves the render before the
 * slot, and the window disappears.
 */
class WorkingRenderTest {

    /** The Conversational cadence as the field ran it: line 1 at 4 s, line 2 one interval later. */
    private val lagMs = 4_000L
    private val intervalMs = 18_000L
    private val slotOpensAt = lagMs + intervalMs           // 22 s
    private val renderMs = 2_500L                          // the field's ~2.5 s render
    private val firstTextAt = 22_500L                      // a reply that lands just after the slot

    @Test fun a_render_started_with_the_run_is_in_hand_when_the_slot_opens() {
        // Requested as soon as the run is known to be working, it finished long before the slot.
        val warm = WorkingRender(gen = 1, requestedAtMs = lagMs)
        warm.settle(ErSoulTurn.Outcome.Spoken("Still on it"), lagMs + renderMs)

        assertEquals(lagMs + renderMs, warm.doneAtMs())
        assertTrue("in hand when the slot opens", warm.doneAtMs() < slotOpensAt)
        assertTrue("and therefore delivered before the mind's first text", warm.doneAtMs() < firstTextAt)
        assertFalse("so the late rule does not fire", WorkingRender.late(firstTextAt, slotOpensAt))
        assertEquals("Still on it", (warm.take() as ErSoulTurn.Outcome.Spoken).text)
    }

    @Test fun a_render_started_when_the_slot_opens_lands_after_the_first_text_and_is_late() {
        // The old ordering: the request happens at the slot, so the line cannot land until the
        // render has run — and here the reply beat it. This must still be refused as late.
        val onDemand = WorkingRender(gen = 1, requestedAtMs = slotOpensAt)
        onDemand.settle(ErSoulTurn.Outcome.Spoken("Still on it"), slotOpensAt + renderMs)

        assertTrue("the render lands after the mind's first text", onDemand.doneAtMs() > firstTextAt)
        assertTrue("so it is late", WorkingRender.late(firstTextAt, onDemand.doneAtMs()))
    }

    @Test fun a_line_that_lands_exactly_as_the_first_text_arrives_is_late() {
        assertTrue(WorkingRender.late(1_000L, 1_000L))
    }

    @Test fun no_first_text_is_never_late() {
        // The run is still working: there is nothing for the line to lose to.
        assertFalse(WorkingRender.late(0L, 30_000L))
    }

    @Test fun a_blank_render_is_not_usable_so_the_slot_renders_again() {
        // The render rails drop a narration while another render runs, and inside the spacing
        // floor they return blank. That is a miss, not the soul choosing silence.
        val warm = WorkingRender(gen = 1, requestedAtMs = lagMs)
        assertFalse("still in flight", warm.usable())
        warm.settle(ErSoulTurn.Outcome.Nothing, lagMs + 10L)
        assertFalse("a blank render leaves the slot to render on demand", warm.usable())
    }

    @Test fun an_escalate_is_a_decision_and_is_kept() {
        val warm = WorkingRender(gen = 1, requestedAtMs = lagMs)
        warm.settle(ErSoulTurn.Outcome.Escalate, lagMs + renderMs)
        assertTrue("silence is a line the soul chose, not a failed render", warm.usable())
        assertEquals(ErSoulTurn.Outcome.Escalate, warm.take())
    }

    @Test fun the_first_settle_wins() {
        val warm = WorkingRender(gen = 1, requestedAtMs = lagMs)
        warm.settle(ErSoulTurn.Outcome.Spoken("first"), 5_000L)
        warm.settle(ErSoulTurn.Outcome.Spoken("second"), 6_000L)
        assertEquals("first", (warm.take() as ErSoulTurn.Outcome.Spoken).text)
        assertEquals(5_000L, warm.doneAtMs())
    }

    @Test fun an_unsettled_render_takes_nothing() {
        val warm = WorkingRender(gen = 1, requestedAtMs = lagMs)
        assertNull(warm.take())
        assertEquals(0L, warm.doneAtMs())
    }

    @Test fun the_voucher_travels_with_the_render_so_delivery_cannot_re_measure_it() {
        // #218: the line says the wait as it was when the line was WRITTEN, and delivery happens
        // seconds later. The voucher is the render's, so an honest line is never refused for the
        // drift between composing it and speaking it.
        val warm = WorkingRender(gen = 1, requestedAtMs = lagMs, vouchedSeconds = 22L)
        warm.settle(ErSoulTurn.Outcome.Spoken("We're about 22 seconds in"), slotOpensAt + renderMs)

        assertEquals(22L, warm.vouchedSeconds)
        assertEquals(
            "We're about 22 seconds in...",
            ErSoulTurn.workingLine(warm.take()!!, emptyList(), warm.vouchedSeconds),
        )
        // Re-measured at delivery (25 s) the same honest line would be refused — which is why the
        // voucher is not recomputed here.
        assertNull(ErSoulTurn.workingLine(warm.take()!!, emptyList(), 25L))
    }

    @Test fun a_render_with_no_wait_vouches_for_nothing() {
        // Before a turn has started there is no wait to offer, and the guard keeps the old rule.
        assertNull(WorkingRender(gen = 1, requestedAtMs = lagMs).vouchedSeconds)
        assertNull(ErSoulTurn.workingLine("About 90 seconds in", emptyList(), WorkingRender(1, lagMs).vouchedSeconds))
    }
}
