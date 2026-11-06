package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #451: the poll's rule — a tick repaints the phone tools sheet only when the sheet would draw
 * something new.
 *
 * The sheet polls once a second so the clock can move *waiting* → *timed out* and so the "last
 * request" line can age. It used to repaint on the clock itself: for as long as a setup was
 * WAITING or TIMED_OUT the whole content view was replaced every second, which put the scroll back
 * at the top and dropped a drag in flight. On a phone that had ever asked its agent to set itself
 * up, nothing below the fold could be reached at all — the sheet read as unscrollable.
 *
 * What the sheet draws is data ([PhoneToolsSheet.Drawn]), so the rule is proved without a device:
 * a second passing is not, on its own, a reason to rebuild the column. [PhoneToolsSheetLayoutTest]
 * measures the same thing on a phone.
 */
class PhoneToolsSheetPollTest {

    private val now = 1_700_000_000_000L
    private val tag = PhoneMcpToken.fingerprint("0123456789abcdef0123456789abcdef")

    private fun status(
        lastRequest: String? = null,
        at: Long? = null,
        listening: Boolean = false,
    ) = PhoneMcp.Status(
        tailnet = "100.101.102.103",
        listening = listening,
        url = if (listening) "http://100.101.102.103:8765/mcp" else null,
        served = 0,
        lastRequest = lastRequest,
        lastRequestAt = at,
        token = PhoneMcp.TokenState.STORED,
        dndGranted = false,
        volumeGranted = true,
    )

    private fun waiting(at: Long = now) = PhoneSetup.Record(requestedAt = at, tokenTag = tag)

    @Test fun a_tick_a_second_later_while_waiting_draws_the_same_sheet() {
        val shown = PhoneToolsSheet.drawn(status(), waiting(), tag, now)
        val next = PhoneToolsSheet.drawn(status(), waiting(), tag, now + 1_000L)
        assertEquals(PhoneSetup.State.WAITING, shown.state)
        assertEquals("a second passing is not a change the owner can see", shown, next)
        assertFalse("a tick that draws the same sheet must not repaint", PhoneToolsSheet.repaints(shown, next))
    }

    @Test fun a_tick_a_second_later_after_the_timeout_draws_the_same_sheet() {
        val late = now + PhoneSetup.TIMEOUT_MS + 60_000L
        val shown = PhoneToolsSheet.drawn(status(), waiting(), tag, late)
        val next = PhoneToolsSheet.drawn(status(), waiting(), tag, late + 1_000L)
        assertEquals(PhoneSetup.State.TIMED_OUT, shown.state)
        assertEquals(shown, next)
        assertFalse(
            "a timed-out setup used to repaint the sheet every second for ever",
            PhoneToolsSheet.repaints(shown, next),
        )
    }

    @Test fun the_tick_that_crosses_the_timeout_repaints() {
        val shown = PhoneToolsSheet.drawn(status(), waiting(), tag, now)
        val next = PhoneToolsSheet.drawn(status(), waiting(), tag, now + PhoneSetup.TIMEOUT_MS)
        assertEquals(PhoneSetup.State.WAITING, shown.state)
        assertEquals(PhoneSetup.State.TIMED_OUT, next.state)
        assertTrue("waiting → timed out is a change, so it is drawn", PhoneToolsSheet.repaints(shown, next))
    }

    @Test fun a_request_landing_repaints_because_the_last_request_line_changed() {
        val shown = PhoneToolsSheet.drawn(status(), waiting(), tag, now)
        val next = PhoneToolsSheet.drawn(status("status", now), waiting(), tag, now + 1_000L)
        assertEquals("None yet", shown.lastRequest)
        assertTrue(PhoneToolsSheet.repaints(shown, next))
    }

    @Test fun the_agent_answering_repaints() {
        val shown = PhoneToolsSheet.drawn(status(), waiting(), tag, now)
        val answered = PhoneSetup.replied(waiting(), "added the vox server")
        assertTrue(PhoneToolsSheet.repaints(shown, PhoneToolsSheet.drawn(status(), answered, tag, now + 1_000L)))
    }

    @Test fun a_handshake_repaints_because_the_state_and_the_line_change() {
        val shown = PhoneToolsSheet.drawn(status(), waiting(), tag, now)
        val connected = PhoneSetup.observedHandshake(waiting(), now + 2_000L, tag)
        val next = PhoneToolsSheet.drawn(status(), connected, tag, now + 3_000L)
        assertEquals(PhoneSetup.State.CONNECTED, next.state)
        assertTrue(PhoneToolsSheet.repaints(shown, next))
    }

    @Test fun the_first_paint_always_happens() {
        assertTrue(PhoneToolsSheet.repaints(null, PhoneToolsSheet.drawn(status(), waiting(), tag, now)))
    }

    /**
     * The step after the handover: the agent has written the entry, the gateway restarts, the tool
     * connects, and the phone's own server records the `initialize`. Nothing else happens — the
     * sheet is not reopened, the prompt is not re-sent — and the next tick draws Connected.
     */
    @Test fun a_handshake_after_a_restart_clears_the_waiting_state_by_itself() {
        val asked = PhoneSetup.replied(waiting(), "Added vox to my configuration; the gateway needs a restart.")
        val shown = PhoneToolsSheet.drawn(status(), asked, tag, now)
        assertEquals("asked, and nothing has connected yet", PhoneSetup.State.WAITING, shown.state)
        assertEquals(PhoneToolsView.WAITING_HEADING, PhoneToolsView.setupHeadline(shown.state, shown.record.failure))

        val handshake = PhoneSetup.observedHandshake(asked, now + 30_000L, tag)
        val next = PhoneToolsSheet.drawn(status(), handshake, tag, now + 31_000L)
        assertEquals("the handshake alone is the transition", PhoneSetup.State.CONNECTED, next.state)
        assertTrue("the owner sees the change on the next tick", PhoneToolsSheet.repaints(shown, next))
        assertEquals(PhoneToolsView.CONNECTED_HEADING, PhoneToolsView.setupHeadline(next.state, next.record.failure))
    }

    /**
     * The same clearing, from a sheet that was never asked anything: a gateway already holding the
     * entry connects on its own, and the loud not-set-up block goes away without a setup turn.
     */
    @Test fun a_handshake_alone_connects_a_sheet_that_was_never_asked() {
        val shown = PhoneToolsSheet.drawn(status(), PhoneSetup.Record(), tag, now)
        assertEquals(PhoneSetup.State.IDLE, shown.state)
        assertEquals(PhoneToolsView.NOT_SET_UP_HEADING, PhoneToolsView.setupHeadline(shown.state))

        val next = PhoneToolsSheet.drawn(status(), PhoneSetup.observedHandshake(PhoneSetup.Record(), now, tag), tag, now + 1_000L)
        assertEquals(PhoneSetup.State.CONNECTED, next.state)
        assertTrue(PhoneToolsSheet.repaints(shown, next))
    }
}
