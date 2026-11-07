package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone tools screen's tick — what one second of it is allowed to draw.
 *
 * The owner's second complaint was a visible refresh every second while he was reading the grant
 * rows. The cause was two rules compounding: the tick was re-posted unconditionally for as long as
 * the screen was open, and anything in [PhoneToolsSheet.Drawn] that differed rebuilt the whole
 * column — and the lines that age always differ eventually.
 *
 * So the rule is in two parts, and both are proved here without a device:
 *  1. a tick exists only while an attempt is in flight ([PhoneToolsPoll]);
 *  2. a tick rewrites only the region whose own inputs moved ([PhoneToolsSheet.work]).
 *
 * [PhoneToolsPollTest] proves part 1 — including that a settled screen has no tick scheduled at
 * all. This file proves part 2 — including that a step change never re-inflates the grant list.
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

    private fun adding(at: Long = now) =
        PhoneBootstrap.Progress(step = PhoneBootstrap.Step.ADDING, startedAt = at)

    private fun answered(at: Long = now) = PhoneBootstrap.Progress(
        step = PhoneBootstrap.Step.WAITING,
        startedAt = at - 60_000L,
        answeredAt = at,
    )

    // ---- 1. a second passing with nothing new to say draws nothing ----

    @Test fun a_tick_a_second_later_while_waiting_draws_the_same_sheet() {
        val shown = PhoneToolsSheet.drawn(status(), waiting(), tag, now)
        val next = PhoneToolsSheet.drawn(status(), waiting(), tag, now + 1_000L)
        assertEquals(PhoneSetup.State.WAITING, shown.state)
        assertEquals("a second passing is not a change the owner can see", shown, next)
        assertEquals(
            "nothing is rewritten when nothing changed",
            PhoneToolsSheet.Work(lines = false, setup = false, body = false),
            PhoneToolsSheet.work(shown, next),
        )
    }

    /**
     * There is no timeout to cross. A setup that has been waiting for six hours draws exactly the
     * same sheet as one that has been waiting a second: *waiting* is a fact about the evidence, not
     * about a clock, and a tick is not a change.
     */
    @Test fun a_setup_that_has_waited_hours_draws_the_same_sheet() {
        val hoursLater = now + 6 * 60 * 60_000L
        val shown = PhoneToolsSheet.drawn(status(), waiting(), tag, now)
        val next = PhoneToolsSheet.drawn(status(), waiting(), tag, hoursLater)
        assertEquals(PhoneSetup.State.WAITING, shown.state)
        assertEquals("no deadline moved the state", PhoneSetup.State.WAITING, next.state)
        assertEquals("no timeout line exists to change", shown.bootstrap, next.bootstrap)
        assertEquals(shown.listening, next.listening)
        assertEquals(shown.activity, next.activity)
        assertEquals(shown.silence, next.silence)
        assertEquals("and the step did not move either", shown.step, next.step)
        // The handshake line's age is a line, not a rebuild: see the aging test below.
    }

    // ---- 2. an age is a setText, and the grant list is not part of it ----

    @Test fun an_aging_line_is_rewritten_in_place_and_nothing_is_rebuilt() {
        // The same status and record, ten seconds later: the "last request" line has aged from
        // "just now" to "11s", and that is the only difference the owner can see.
        val s = status("status", now - 1_000L)
        val shown = PhoneToolsSheet.drawn(s, waiting(), tag, now)
        val next = PhoneToolsSheet.drawn(s, waiting(), tag, now + 10_000L)
        assertNotEquals("the age moved", shown.lastRequest, next.lastRequest)
        val work = PhoneToolsSheet.work(shown, next)
        assertEquals("the phone's status did not change", false, work.body)
        assertEquals("the setup block did not change", false, work.setup)
        assertTrue("only the line is rewritten", work.lines)
    }

    @Test fun an_aging_handshake_line_is_also_only_a_line() {
        val record = PhoneSetup.observedHandshake(PhoneSetup.Record(), now - 60_000L, tag)
        val shown = PhoneToolsSheet.drawn(status(), record, tag, now)
        val next = PhoneToolsSheet.drawn(status(), record, tag, now + 6 * 60 * 60_000L)
        assertEquals(PhoneBootstrap.Step.CONNECTED, shown.step)
        val work = PhoneToolsSheet.work(shown, next)
        assertEquals(false, work.body)
        assertEquals(false, work.setup)
        assertTrue("the proof's age is a line", work.lines)
    }

    // ---- 3. the agent's own events redraw what they change ----

    @Test fun the_agent_answering_rebuilds_the_setup_block_only() {
        val shown = PhoneToolsSheet.drawn(status(), waiting(), tag, now, adding())
        val reply = PhoneSetup.replied(waiting(), "added the vox server")
        val boot = PhoneBootstrap.answered(adding(), "added the vox server", now + 1_000L)
        val next = PhoneToolsSheet.drawn(status(), reply, tag, now + 1_000L, boot)
        val work = PhoneToolsSheet.work(shown, next)
        assertTrue("the attempt's own words move the setup block", work.setup)
        assertEquals("and nothing else", false, work.body)
    }

    @Test fun a_handshake_rewrites_the_setup_block_and_the_state_line_never_the_grants() {
        val shown = PhoneToolsSheet.drawn(status(), waiting(), tag, now, adding())
        val connected = PhoneSetup.observedHandshake(waiting(), now + 2_000L, tag)
        val next = PhoneToolsSheet.drawn(status(), connected, tag, now + 3_000L, adding())
        assertEquals(PhoneSetup.State.CONNECTED, next.state)
        assertEquals(PhoneBootstrap.Step.CONNECTED, next.step)
        assertEquals("the state line is the step's own line", PhoneToolsView.CONNECTED_HEADING, next.stateLine)
        val work = PhoneToolsSheet.work(shown, next)
        assertTrue("the step moved, so the line and the setup block are rewritten", work.lines && work.setup)
        assertEquals("the grant list's inputs did not move, so it is not rebuilt", false, work.body)
    }

    /**
     * The blink, expressed as the rule: a step change must not touch the body. The body is what
     * holds the grant groups and Recent actions, so `body == false` is this codebase's equivalent of
     * "the adapter keeps its identity" — there is no adapter here; the column is the list, and its
     * views are only re-made by [PhoneToolsSheet.Work.body].
     */
    @Test fun no_step_change_ever_rebuilds_the_body() {
        val now = this.now
        val boots = listOf(
            PhoneBootstrap.Progress(),
            adding(now),
            answered(now),
            PhoneBootstrap.Progress(
                step = PhoneBootstrap.Step.FAILED,
                failure = PhoneBootstrap.Failure.NO_HANDSHAKE,
                reached = PhoneBootstrap.Step.WAITING,
                startedAt = now - 200_000L,
                answeredAt = now - 100_000L,
            ),
        )
        for (boot in boots) {
            val next = PhoneToolsSheet.drawn(status(), waiting(), tag, now, boot)
            val shown = PhoneToolsSheet.drawn(status(), waiting(), tag, now - 1_000L, PhoneBootstrap.Progress())
            assertEquals(
                "a step change to ${next.step} must not rebuild the grant list",
                false,
                PhoneToolsSheet.work(shown, next).body,
            )
        }
    }

    @Test fun a_request_landing_rebuilds_the_phone_rows_because_the_status_changed() {
        val shown = PhoneToolsSheet.drawn(status(), waiting(), tag, now)
        val next = PhoneToolsSheet.drawn(status("status", now), waiting(), tag, now + 1_000L)
        assertEquals("None yet", shown.lastRequest)
        assertTrue("the phone's own rows changed", PhoneToolsSheet.work(shown, next).body)
    }

    @Test fun the_first_paint_always_happens() {
        val work = PhoneToolsSheet.work(null, PhoneToolsSheet.drawn(status(), waiting(), tag, now))
        assertTrue(work.any && work.body && work.setup && work.lines)
    }

    /**
     * The step after the handover: the agent has written the entry, the gateway restarts, the tool
     * connects, and the phone's own server records the `initialize`. Nothing else happens — the
     * screen is not reopened, the prompt is not re-sent — and the next tick draws Connected.
     */
    @Test fun a_handshake_after_a_restart_clears_the_waiting_state_by_itself() {
        val asked = PhoneSetup.replied(waiting(), "Added vox to my configuration; the gateway needs a restart.")
        val shown = PhoneToolsSheet.drawn(status(), asked, tag, now)
        assertEquals("asked, and nothing has connected yet", PhoneSetup.State.WAITING, shown.state)
        assertEquals(PhoneToolsView.WAITING_HEADING, PhoneToolsView.setupHeadline(shown.state, shown.record.failure))

        val handshake = PhoneSetup.observedHandshake(asked, now + 30_000L, tag)
        val next = PhoneToolsSheet.drawn(status(), handshake, tag, now + 31_000L)
        assertEquals("the handshake alone is the transition", PhoneSetup.State.CONNECTED, next.state)
        assertTrue("the owner sees the change on the next tick", PhoneToolsSheet.work(shown, next).setup)
        assertEquals(PhoneToolsView.CONNECTED_HEADING, PhoneToolsView.setupHeadline(next.state, next.record.failure))
    }

    /**
     * The same clearing, from a screen that was never asked anything: a gateway already holding the
     * entry connects on its own, and the loud not-set-up block goes away without a setup turn.
     */
    @Test fun a_handshake_alone_connects_a_screen_that_was_never_asked() {
        val shown = PhoneToolsSheet.drawn(status(), PhoneSetup.Record(), tag, now)
        assertEquals(PhoneSetup.State.IDLE, shown.state)
        assertEquals(PhoneToolsView.NOT_SET_UP_HEADING, PhoneToolsView.setupHeadline(shown.state))

        val next = PhoneToolsSheet.drawn(
            status(),
            PhoneSetup.observedHandshake(PhoneSetup.Record(), now, tag),
            tag,
            now + 1_000L,
        )
        assertEquals(PhoneSetup.State.CONNECTED, next.state)
        assertTrue(PhoneToolsSheet.work(shown, next).setup)
    }
}
