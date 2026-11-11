package com.hermesvox

import com.hermesvox.CallReadiness.Gate
import com.hermesvox.CallReadiness.Leg
import com.hermesvox.CallReadiness.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The call readiness gate: green only when every present leg is observed ready. */
class CallReadinessTest {
    private val early = 1_000L
    private val late = CallReadiness.TALK_ANYWAY_MS

    @Test
    fun nothing_reported_is_not_green_and_says_the_first_leg() {
        val v = Gate(presence = true).view(early)
        assertEquals(Phase.ASSEMBLING, v.phase)
        assertEquals("Connecting to gateway", v.line)
        assertTrue(v.ready.isEmpty())
        assertFalse(v.talkAnyway)
    }

    @Test
    fun green_only_when_all_four_are_ready() {
        val g = Gate(presence = true)
        g.report(Leg.MIND, true); g.report(Leg.HEARING, true); g.report(Leg.VOICE, true)
        val v = g.view(early)
        assertEquals(Phase.ASSEMBLING, v.phase)
        assertEquals("Loading local model", v.line)
        assertEquals(3, v.ready.size)
        g.report(Leg.PRESENCE, true)
        assertEquals(Phase.GREEN, g.view(early).phase)
        assertEquals(CallReadiness.GREEN_LINE, g.view(early).line)
    }

    @Test
    fun presence_off_is_absent_not_green() {
        val g = Gate(presence = false)
        assertEquals(listOf(Leg.MIND, Leg.HEARING, Leg.VOICE), g.legs)
        assertNull(g.state(Leg.PRESENCE))
        g.report(Leg.PRESENCE, true)   // ignored: not part of this gate
        assertFalse(Leg.PRESENCE in g.view(early).ready)
        g.report(Leg.MIND, true); g.report(Leg.HEARING, true); g.report(Leg.VOICE, true)
        val v = g.view(early)
        assertEquals(Phase.GREEN, v.phase)
        assertEquals(1f, v.fraction)
    }

    @Test
    fun the_lines_follow_the_legs_in_order() {
        val g = Gate(presence = true)
        g.report(Leg.MIND, true)
        assertEquals("Loading speech recognition", g.view(early).line)
        g.report(Leg.HEARING, true)
        assertEquals("Loading voice models", g.view(early).line)
    }

    @Test
    fun a_leg_that_never_reports_never_advances_and_the_wait_offers_talk_anyway_for_presence() {
        val g = Gate(presence = true)
        g.report(Leg.MIND, true); g.report(Leg.HEARING, true); g.report(Leg.VOICE, true)
        assertEquals(Phase.ASSEMBLING, g.view(late - 1).phase)
        val v = g.view(late)
        assertEquals(Phase.STALLED, v.phase)
        assertEquals(Leg.PRESENCE, v.pending)
        assertEquals("Local model still loading", v.line)
        assertTrue(v.talkAnyway)
        assertEquals("Local model still loading; presence disabled", v.missing)
    }

    @Test
    fun talk_anyway_is_never_offered_without_ears_or_voice() {
        val g = Gate(presence = false)
        g.report(Leg.MIND, true); g.report(Leg.VOICE, true)
        val v = g.view(late * 3)
        assertEquals(Phase.STALLED, v.phase)
        assertEquals(Leg.HEARING, v.pending)
        assertFalse(v.talkAnyway)
    }

    @Test
    fun a_slow_mind_can_be_talked_past_after_the_wait() {
        val g = Gate(presence = false)
        g.report(Leg.HEARING, true); g.report(Leg.VOICE, true)
        val v = g.view(late)
        assertTrue(v.talkAnyway)
        assertTrue(v.missing.startsWith("Gateway not responding"))
    }

    @Test
    fun a_refused_voice_fails_in_its_own_words_and_offers_only_the_refusal() {
        val g = Gate(presence = false)
        g.report(Leg.MIND, true); g.report(Leg.HEARING, true)
        val refusal = "Can't start the call — no voice can speak."
        g.fail(Leg.VOICE, refusal)
        val v = g.view(early)
        assertEquals(Phase.FAILED, v.phase)
        assertEquals(Leg.VOICE, v.failed)
        assertEquals(refusal, v.line)
        assertFalse(v.talkAnyway)
    }

    @Test
    fun an_unreachable_gateway_is_fatal_in_the_pills_words() {
        val g = Gate(presence = true)
        g.report(Leg.HEARING, true); g.report(Leg.VOICE, true); g.report(Leg.PRESENCE, true)
        g.fail(Leg.MIND, "Can't reach the gateway")
        val v = g.view(late)
        assertEquals(Phase.FAILED, v.phase)
        assertEquals("Can't reach the gateway", v.line)
        assertFalse(v.talkAnyway)
    }

    @Test
    fun presence_failing_is_said_and_talk_anyway_is_offered_at_once() {
        val g = Gate(presence = true)
        g.report(Leg.MIND, true); g.report(Leg.HEARING, true); g.report(Leg.VOICE, true)
        g.fail(Leg.PRESENCE, "presence could not start on this phone")
        val v = g.view(early)
        assertEquals(Phase.FAILED, v.phase)
        assertTrue(v.talkAnyway)
        assertEquals("presence could not start on this phone; asides are off", v.missing)
    }

    @Test
    fun a_recovered_leg_clears_its_failure() {
        val g = Gate(presence = false)
        g.fail(Leg.MIND, "Gateway is warming up…")
        g.report(Leg.MIND, true); g.report(Leg.HEARING, true); g.report(Leg.VOICE, true)
        assertEquals(Phase.GREEN, g.view(early).phase)
    }

    @Test
    fun the_probe_maps_to_ready_waiting_or_failed() {
        assertEquals(true, CallReadiness.mindReady(ConnectionPhase.Probe.OK))
        assertEquals(false, CallReadiness.mindReady(ConnectionPhase.Probe.IN_FLIGHT))
        assertEquals(false, CallReadiness.mindReady(ConnectionPhase.Probe.NOT_TESTED))
        assertEquals(false, CallReadiness.mindReady(ConnectionPhase.Probe.COLD))
        for (p in listOf(ConnectionPhase.Probe.AUTH, ConnectionPhase.Probe.UNREACHABLE,
                ConnectionPhase.Probe.GATEWAY_ERROR, ConnectionPhase.Probe.PROFILE_MISSING, ConnectionPhase.Probe.BLOCKED))
            assertNull(p.name, CallReadiness.mindReady(p))
    }

    @Test
    fun the_being_assembles_as_legs_arrive() {
        val g = Gate(presence = true)
        val a0 = CallReadiness.assembly(g.view(early))
        assertEquals("idle", a0.state)            // the diffuse nebula: nothing has answered
        g.report(Leg.MIND, true)
        val a1 = CallReadiness.assembly(g.view(early))
        assertEquals("thinking", a1.state); assertEquals("memory", a1.tool)   // the constellation
        assertTrue(a1.density > a0.density)
        g.report(Leg.HEARING, true)
        val a2 = CallReadiness.assembly(g.view(early))
        assertTrue(a2.density > a1.density && a2.workload > a1.workload)
        g.report(Leg.VOICE, true); g.report(Leg.PRESENCE, true)
        val green = CallReadiness.assembly(g.view(early))
        assertEquals("listening", green.state)
        assertEquals(1f, green.density)
        assertTrue(CallReadiness.assembly(Gate(true).view(late)).stalled)
    }

    @Test
    fun the_log_line_names_the_phase_and_the_legs() {
        val g = Gate(presence = false); g.report(Leg.MIND, true)
        assertEquals("event=call-ready phase=assembling ready=mind pending=hearing failed=- legs=3 waitedMs=5",
            CallReadiness.logLine(g.view(5), 5))
    }
}
