package com.hermesvox.core

import com.hermesvox.ConnectionPhase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * A host that says `404 Unknown or unconfigured profile` must not read as connected.
 *
 * The field report: the header named the profile and the phone said *Connected*, while
 * every turn failed with exactly that 404. This suite pins the correction:
 *
 *  * the answer is its own state — not auth, not the network, not transient;
 *  * the state is "not connected", and the line names the profile;
 *  * a later `GET /v1/models` 200 clears it, so the next attempt re-checks;
 *  * nothing is ever sent to a delete path (the app cannot create or remove profiles).
 */
class MissingProfileStateTest {

    private val body = """{"error": "Unknown or unconfigured profile"}"""
    private val base = "https://gw.example/p/gone"

    @Before fun reset() {
        ProfileReach.clear()
        StreamRoute.clearForTest()
    }

    @After fun tidy() {
        ProfileReach.clear()
        StreamRoute.clearForTest()
        StreamRoute.active = TurnRoute.RUNS
    }

    private fun failure(block: () -> Unit): String = try {
        block()
        fail("expected a HermesException")
        ""
    } catch (e: HermesException) {
        e.message.orEmpty()
    }

    @Test fun theExactUnknownProfile404IsItsOwnStateAndNotConnected() {
        assertTrue(ProfileReach.observe(404, body, base))
        assertTrue(ProfileReach.isMissing())
        assertEquals(ProfileReach.Missing("gone", base), ProfileReach.missing())

        // It is a distinct classification: not gateway-error, not auth, not unreachable.
        assertEquals(ConnectionPhase.Probe.PROFILE_MISSING,
            ConnectionPhase.classifyAnswer(404, body))
        assertEquals(ConnectionPhase.Phase.PROFILE_MISSING,
            ConnectionPhase.resolve(true, true, true, ConnectionPhase.Probe.PROFILE_MISSING))
        assertNotEquals(ConnectionPhase.Phase.CONNECTED,
            ConnectionPhase.resolve(true, true, true, ConnectionPhase.Probe.PROFILE_MISSING))
        assertTrue("a missing profile is never the quiet Connected phase",
            ConnectionPhase.shows(ConnectionPhase.Phase.PROFILE_MISSING))
    }

    @Test fun theOneLineNamesTheProfile() {
        ProfileReach.observe(404, body, base)
        assertEquals("the host has no profile called gone", ProfileReach.line())
        assertEquals("the host has no profile called gone",
            ConnectionPhase.pill(ConnectionPhase.Phase.PROFILE_MISSING, "gone"))
    }

    @Test fun onlyThat404BodySetsIt() {
        val other = listOf(
            404 to "",
            404 to """{"error":"run_not_found"}""",
            400 to body,
            401 to body,
            403 to body,
            500 to body,
        )
        for ((code, text) in other) {
            ProfileReach.clear()
            assertFalse("$code $text must not be the missing-profile state",
                ProfileReach.observe(code, text, base))
            assertFalse(ProfileReach.isMissing())
        }
        assertEquals("a plain 404 is still a gateway error",
            ConnectionPhase.Probe.GATEWAY_ERROR, ConnectionPhase.classifyAnswer(404, ""))
    }

    @Test fun aModels200ClearsItButAnotherProfiles200DoesNot() {
        ProfileReach.observe(404, body, base)
        assertFalse("a different profile's 200 is not proof this one exists",
            ProfileReach.clearedByModels("https://gw.example/p/other/v1/models"))
        assertTrue(ProfileReach.isMissing())

        assertTrue(ProfileReach.clearedByModels("$base/v1/models"))
        assertFalse(ProfileReach.isMissing())
    }

    @Test fun aFakeGatewaySayingThat404PutsTheAppInTheStateAndALater200ClearsIt() {
        FakeGateway().use { gw ->
            val url = gw.url + "/p/gone"
            val s = HermesSession(url, "k", "m")
            s.setProfile("gone")

            gw.profileMissing = true
            val first = failure { s.ping() }
            assertTrue("the host's own words survive: $first", first.contains("Unknown or unconfigured profile"))
            assertTrue(ProfileReach.isMissing())
            assertEquals("gone", ProfileReach.missing()?.name)
            assertEquals("the host has no profile called gone", ProfileReach.line())

            // The next attempt re-checks: the host answers 200, the state clears.
            gw.profileMissing = false
            s.ping()
            assertFalse(ProfileReach.isMissing())
            assertFalse(ProfileReach.clearedByModels("$base/v1/models"))

            // And it never asked the host to remove anything.
            assertTrue("only reads and turn requests: ${gw.seen}",
                gw.seen.all { it.method == "GET" || it.method == "POST" })
            assertTrue("no request went to a delete path: ${gw.seen}",
                gw.seen.none { it.path.trimEnd('/').endsWith("delete", ignoreCase = true) })
        }
    }

    @Test fun theMissing404OnRunsIsNotMistakenForTheNoRunsRouteFallback() {
        FakeGateway(FakeGateway.Faults.UNKNOWN_PROFILE).use { gw ->
            val s = HermesSession(gw.url + "/p/gone", "k", "m")
            s.setProfile("gone")
            val err = failure { s.turnStored("hi") }
            assertTrue("the turn failed with the host's missing-profile answer: $err",
                err.contains("Unknown or unconfigured profile"))
            assertTrue(ProfileReach.isMissing())
            assertFalse("a missing profile must not be remembered as a gateway without /v1/runs",
                StreamRoute.requiresResponses("gone"))
            assertTrue("nothing was sent to a delete path: ${gw.seen}",
                gw.seen.none { it.path.trimEnd('/').endsWith("delete", ignoreCase = true) })
        }
    }

    @Test fun theMissing404OnResponsesAlsoSetsIt() {
        StreamRoute.active = TurnRoute.RESPONSES
        FakeGateway(FakeGateway.Faults.UNKNOWN_PROFILE).use { gw ->
            val s = HermesSession(gw.url + "/p/gone", "k", "m")
            s.setProfile("gone")
            val err = failure { s.turnStored("hi") }
            assertTrue("the stored turn surfaces the host's answer: $err",
                err.contains("Unknown or unconfigured profile"))
            assertTrue(ProfileReach.isMissing())
            assertTrue("nothing was sent to a delete path: ${gw.seen}",
                gw.seen.none { it.path.trimEnd('/').endsWith("delete", ignoreCase = true) })
        }
    }
}
