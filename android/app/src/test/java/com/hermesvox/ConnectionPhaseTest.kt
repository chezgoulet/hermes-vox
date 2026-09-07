package com.hermesvox

import com.hermesvox.ConnectionPhase.Phase as P
import com.hermesvox.ConnectionPhase.Probe as Pr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.1 Part B — the phase machine and the honest verdict.
 *
 * The field bug, reproduced as a test: a connection test that runs before the local
 * pipeline is warm must never be shown as a gateway verdict, and a gateway that
 * answered must never be reported as unreachable.
 */
class ConnectionPhaseTest {

    private val NONE = ConnectionPhase.NO_RESPONSE
    private val SKIP = ConnectionPhase.NOT_RUN

    // ---- the phase machine ----

    @Test fun identity_problems_outrank_everything() {
        assertEquals(P.NO_ENDPOINT, ConnectionPhase.resolve(false, true, true, Pr.OK))
        assertEquals(P.NEEDS_KEY, ConnectionPhase.resolve(true, false, true, Pr.OK))
    }

    @Test fun a_cold_pipeline_is_WARMING_and_never_a_gateway_verdict() {
        // THE FIELD BUG: conn-test fires at pipe startup, both legs fail, and the pill
        // must NOT turn that into "unreachable" — the gateway was never dialled.
        for (probe in Pr.values()) {
            assertEquals("probe=$probe must not be rendered while warming",
                P.WARMING, ConnectionPhase.resolve(true, true, false, probe))
        }
    }

    @Test fun warm_and_untested_is_DIALING_not_connected() {
        assertEquals(P.DIALING, ConnectionPhase.resolve(true, true, true, Pr.NOT_TESTED))
        assertEquals(P.DIALING, ConnectionPhase.resolve(true, true, true, Pr.IN_FLIGHT))
    }

    @Test fun the_three_phases_the_user_asked_for_are_distinct() {
        val warming = ConnectionPhase.pill(P.WARMING)
        val dialing = ConnectionPhase.pill(P.DIALING)
        val connected = ConnectionPhase.pill(P.CONNECTED)
        assertTrue(warming.startsWith("Warming up"))
        assertTrue(dialing.startsWith("Dialing"))
        assertEquals("Connected", connected)
        assertEquals(3, setOf(warming, dialing, connected).size)
        // ...and a gateway that is warming is NOT the same word as the local warm-up.
        assertNotEquals(warming, ConnectionPhase.pill(P.GATEWAY_COLD))
    }

    @Test fun connected_is_the_only_quiet_phase() {
        assertFalse(ConnectionPhase.shows(P.CONNECTED))
        for (p in P.values()) if (p != P.CONNECTED) assertTrue("$p should show", ConnectionPhase.shows(p))
    }

    @Test fun each_probe_outcome_lands_on_its_own_phase() {
        assertEquals(P.CONNECTED, ConnectionPhase.resolve(true, true, true, Pr.OK))
        assertEquals(P.GATEWAY_COLD, ConnectionPhase.resolve(true, true, true, Pr.COLD))
        assertEquals(P.AUTH_REJECTED, ConnectionPhase.resolve(true, true, true, Pr.AUTH))
        assertEquals(P.GATEWAY_ERROR, ConnectionPhase.resolve(true, true, true, Pr.GATEWAY_ERROR))
        assertEquals(P.UNREACHABLE, ConnectionPhase.resolve(true, true, true, Pr.UNREACHABLE))
    }

    // ---- classification: reached vs never reached ----

    @Test fun both_legs_healthy_is_OK() {
        assertEquals(Pr.OK, ConnectionPhase.classify(200, "", 200, ""))
        assertEquals(Pr.OK, ConnectionPhase.classify(200, "", 307, ""))
        assertEquals(Pr.OK, ConnectionPhase.classify(200, "", SKIP, ""))   // ping-only dial
    }

    @Test fun no_ping_response_at_all_is_the_only_UNREACHABLE() {
        assertEquals(Pr.UNREACHABLE,
            ConnectionPhase.classify(NONE, "UnknownHostException: gw.local", SKIP, ""))
        assertEquals(Pr.UNREACHABLE,
            ConnectionPhase.classify(NONE, "ConnectException: Connection refused", NONE, "SocketTimeoutException"))
    }

    @Test fun a_gateway_that_answered_is_never_called_unreachable() {
        // Reached it, it is up, it is just not ready — the exact case the old blanket
        // "check your network" copy got wrong.
        for (code in intArrayOf(408, 425, 429, 502, 503, 504)) {
            assertEquals("ping HTTP $code", Pr.COLD, ConnectionPhase.classify(code, "", SKIP, ""))
            assertEquals("stream HTTP $code", Pr.COLD, ConnectionPhase.classify(200, "", code, ""))
        }
        // Ping fine, stream timed out: the socket was accepted, so this is a cold
        // gateway, not a missing one.
        assertEquals(Pr.COLD, ConnectionPhase.classify(200, "", NONE, "SocketTimeoutException: timeout"))
    }

    @Test fun a_rejected_key_is_its_own_answer() {
        assertEquals(Pr.AUTH, ConnectionPhase.classify(401, "", SKIP, ""))
        assertEquals(Pr.AUTH, ConnectionPhase.classify(403, "", SKIP, ""))
        assertEquals(Pr.AUTH, ConnectionPhase.classify(200, "", 401, ""))
    }

    @Test fun wrong_service_or_route_is_a_gateway_error_not_a_network_problem() {
        assertEquals(Pr.GATEWAY_ERROR, ConnectionPhase.classify(404, "", SKIP, ""))
        assertEquals(Pr.GATEWAY_ERROR, ConnectionPhase.classify(200, "", 400, ""))
        assertEquals(Pr.GATEWAY_ERROR, ConnectionPhase.classify(200, "", NONE, "SSLException: bad record"))
    }

    @Test fun a_test_blocked_before_the_network_is_reported_as_blocked() {
        // The 13:49 field line: `ping=false(unknown) stream=false(unknown)` — the test
        // ran on the UI thread and Android threw before a byte moved.
        val e = "NetworkOnMainThreadException"
        assertEquals(Pr.BLOCKED, ConnectionPhase.classify(NONE, e, NONE, e))
        // ...and a blocked test is NOT a verdict: the pill stays on Dialing.
        assertEquals(P.DIALING, ConnectionPhase.resolve(true, true, true, Pr.BLOCKED))
    }

    // ---- copy ----

    @Test fun failure_copy_distinguishes_cold_from_unreachable() {
        val cold = ConnectionPhase.copy(Pr.COLD, "")
        val gone = ConnectionPhase.copy(Pr.UNREACHABLE, "")
        assertTrue(cold.contains("Reached your gateway"))
        assertTrue(cold.contains("warming up"))
        assertFalse("a cold gateway must not be blamed on the network", cold.contains("network is on"))
        assertTrue(gone.contains("network is on"))
        assertNotEquals(cold, gone)
    }

    @Test fun success_copy_is_unchanged_and_debug_rides_along() {
        assertEquals("Connected to your agent. Everything's working.", ConnectionPhase.copy(Pr.OK, "ping: HTTP 500"))
        assertTrue(ConnectionPhase.copy(Pr.UNREACHABLE, "ping: ConnectException")
            .endsWith("(debug: ping: ConnectException)"))
    }

    @Test fun a_reason_is_never_the_word_unknown() {
        assertEquals("NetworkOnMainThreadException",
            ConnectionPhase.reason("NetworkOnMainThreadException", null))
        assertEquals("NetworkOnMainThreadException",
            ConnectionPhase.reason("NetworkOnMainThreadException", "   "))
        assertEquals("ConnectException: refused", ConnectionPhase.reason("ConnectException", "refused"))
    }
}
