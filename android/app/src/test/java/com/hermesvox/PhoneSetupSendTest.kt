package com.hermesvox

import com.hermesvox.core.Entity
import com.hermesvox.core.HermesException
import com.hermesvox.core.JObj
import com.hermesvox.core.Json
import com.hermesvox.core.StreamRoute
import com.hermesvox.core.TestServer
import com.hermesvox.core.TurnRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.util.Collections

/**
 * #430: the setup turn goes out on the active profile's client, and a send that cannot be made says
 * why.
 *
 * Three properties, each a way this went wrong in the field:
 *
 *  1. **the request is the active connection's.** The turn is built from the profile's url, key and
 *     scope — not from a session object that keeps the address it was created with. Proved with a
 *     fake gateway that records the request it received.
 *  2. **a missing or stale session is a build, not a failure.** [PhoneSetupSend.plan] rides the
 *     live session only when it was built for the connection in force; otherwise it builds one from
 *     that connection. Only an unconfigured connection is a refusal — and that has its own words.
 *  3. **each failure names itself.** "no connection configured", "key rejected", "your agent did not
 *     answer" and "turn failed: …" are four different lines; a reply — however confident — never
 *     becomes *Connected*.
 */
class PhoneSetupSendTest {

    private val profile = "/p/mcp-test"
    private val key = "profile-key-9f1c"
    private val scope = "agent:vox:mcp-test"

    /** The active profile as the live prefs hold it — the source the connection test uses too. */
    private val prefs = linkedMapOf(
        "url" to "http://127.0.0.1:1$profile",   // the port is replaced with the fake's below
        "key" to key,
        "model" to "hermes-agent",
        "provider" to "openrouter",
        SessionScope.PREF to scope,
    )

    private fun connectionFor(url: String) =
        PhoneSetupSend.connection({ k -> if (k == "url") url else prefs[k].orEmpty() }, { it })

    private fun withRuns(block: () -> Unit) {
        val old = StreamRoute.active
        StreamRoute.active = TurnRoute.RUNS
        try {
            block()
        } finally {
            StreamRoute.active = old
        }
    }

    /**
     * The fake gateway: it admits the run, then answers the events stream the way Hermes does.
     * Every request is recorded, so the test asserts what actually went on the wire.
     */
    private fun gateway(seen: MutableList<String>, body: MutableList<String>) = TestServer { r, w ->
        synchronized(seen) { seen.add("${r.method} ${r.path}") }
        when {
            r.method == "POST" && r.path == "$profile/v1/runs" -> {
                synchronized(body) { body.add(r.body) }
                w.send(202, "Accepted", """{"run_id":"run_setup","status":"started"}""")
            }
            r.method == "GET" && r.path == "$profile/v1/runs/run_setup/events" -> {
                w.sse()
                w.write(
                    """data: {"event":"message.delta","run_id":"run_setup","seq":0,"delta":"added vox"}

data: {"event":"run.completed","run_id":"run_setup","seq":1}

""",
                )
            }
            else -> w.send(404, "Not Found", """{"detail":"no route ${r.path}"}""")
        }
    }

    // ---- 1. the turn is the active profile's client ----

    @Test
    fun `the setup turn rides the active profiles url key and scope`() = withRuns {
        val seen = Collections.synchronizedList(ArrayList<String>())
        val bodies = Collections.synchronizedList(ArrayList<String>())
        gateway(seen, bodies).use { srv ->
            val active = connectionFor(srv.url + profile)
            assertTrue("the profile is a configured connection", active.configured)
            val s = PhoneSetupStore.build(active)
            val reply = s.turnStored(PhoneSetup.prompt("100.82.156.1"))
            assertEquals("added vox", reply)

            val request = srv.requests.first { it.method == "POST" }
            assertEquals(
                "the request must go to the profile's own path, not the host root",
                "$profile/v1/runs",
                request.path,
            )
            assertEquals("Bearer $key", request.header("Authorization"))
            assertEquals(scope, request.header(Entity.SESSION_KEY_HEADER))
            val body = Json.parse(bodies.single()) as JObj
            assertEquals("hermes-agent", Json.str(body, "model"))
            assertEquals("openrouter", Json.str(body, "provider"))
        }
    }

    @Test
    fun `the connection is read from the same prefs the connection test reads`() {
        val c = connectionFor("http://mikoa.leopard-vibes.ts.net:8642$profile")
        assertEquals("http://mikoa.leopard-vibes.ts.net:8642$profile", c.url)
        assertEquals(key, c.key)
        assertEquals("hermes-agent", c.model)
        assertEquals("openrouter", c.provider)
        assertEquals(scope, c.scope)
        assertTrue(c.configured)
    }

    @Test
    fun `an unconfigured connection is not a connection`() {
        fun c(url: String, key: String) = PhoneSetupSend.Connection(url = url, key = key)
        assertFalse("no address", c("", key).configured)
        assertFalse("no key", c("http://host:8642", "").configured)
        assertFalse("no scheme: the request would never leave the phone", c("host:8642/p/x", key).configured)
        assertFalse("a placeholder is not an address", c("<tailnet-ip>", key).configured)
        assertTrue(c("https://host/p/x", key).configured)
        assertTrue("plain HTTP on the tailnet is allowed", c("http://100.82.156.1:8642/p/x", key).configured)
    }

    // ---- 2. a missing or stale session is built, not failed ----

    @Test
    fun `a session built for another connection is never reused`() {
        val active = connectionFor("http://new-host:8642/p/mcp-test")
        val stale = connectionFor("http://old-host:8642/p/other")
        assertEquals(
            "no session at all: build one from the connection in force",
            PhoneSetupSend.Plan.BUILD,
            PhoneSetupSend.plan(null, hasSession = false, active = active),
        )
        assertEquals(
            "a session for the previous connection must not carry the turn",
            PhoneSetupSend.Plan.BUILD,
            PhoneSetupSend.plan(stale, hasSession = true, active = active),
        )
        assertEquals(
            "a session for this very connection keeps the turn in its chain",
            PhoneSetupSend.Plan.RIDE_LIVE,
            PhoneSetupSend.plan(active, hasSession = true, active = active),
        )
        assertEquals(
            "the connection is the identity, key included",
            PhoneSetupSend.Plan.BUILD,
            PhoneSetupSend.plan(active.copy(key = "another-key"), hasSession = true, active = active),
        )
        assertEquals(
            "nothing configured: the one honest refusal",
            PhoneSetupSend.Plan.NO_CONNECTION,
            PhoneSetupSend.plan(null, hasSession = false, active = null),
        )
    }

    @Test
    fun `a session keeps the address it was built with, so a held one is the bug`() = withRuns {
        // The proof, executable: a session built before the connection was re-pointed sends the
        // turn to the OLD address, while the gateway in force — the one the owner is watching, and
        // the one the connection test proved — logs nothing. That is #430's field symptom.
        val oldSeen = Collections.synchronizedList(ArrayList<String>())
        val newSeen = Collections.synchronizedList(ArrayList<String>())
        gateway(oldSeen, Collections.synchronizedList(ArrayList<String>())).use { oldGateway ->
            gateway(newSeen, Collections.synchronizedList(ArrayList<String>())).use { newGateway ->
                val atLaunch = connectionFor(oldGateway.url + profile)
                val held = PhoneSetupStore.build(atLaunch)          // what MainActivity holds
                val inForce = connectionFor(newGateway.url + profile)   // what the prefs say now

                assertEquals(
                    "the connection changed, so the held session may not carry the turn",
                    PhoneSetupSend.Plan.BUILD,
                    PhoneSetupSend.plan(atLaunch, hasSession = true, active = inForce),
                )
                assertEquals("added vox", held.turnStored("setup"))
                assertTrue("the held session went to the replaced address", oldGateway.requests.isNotEmpty())
                assertTrue("and the gateway in force saw nothing", newGateway.requests.isEmpty())
            }
        }
    }

    // ---- 3. each failure names itself ----

    @Test
    fun `each failure reason maps to a distinct message`() {
        val noConnection = PhoneSetupSend.message(PhoneSetupSend.Failure.NO_CONNECTION)
        val keyRejected = PhoneSetupSend.message(PhoneSetupSend.Failure.KEY_REJECTED)
        val noAnswer = PhoneSetupSend.message(PhoneSetupSend.Failure.NO_ANSWER)
        val turnFailed = PhoneSetupSend.message(PhoneSetupSend.Failure.TURN_FAILED, "no output_text in response")

        assertTrue(noConnection, noConnection.contains("No connection configured"))
        assertTrue(keyRejected, keyRejected.contains("Key rejected"))
        assertTrue(noAnswer, noAnswer.contains("did not answer"))
        assertTrue(turnFailed, turnFailed.contains("Turn failed: no output_text in response"))

        val four = setOf(noConnection, keyRejected, noAnswer, turnFailed)
        assertEquals("a message per cause, never one for every case", 4, four.size)
        for (line in four) assertFalse("no generic 'could not reach' left", line.contains("could not reach", true))
    }

    @Test
    fun `an outcome is classified by what happened, not flattened`() {
        // A 401 from the gateway's own status line.
        assertEquals(
            PhoneSetupSend.Failure.KEY_REJECTED,
            PhoneSetupSend.classify(HermesException("hermes run 401 Unauthorized: {\"detail\":\"bad key\"}"), null),
        )
        // The transport never answered.
        assertEquals(
            PhoneSetupSend.Failure.NO_ANSWER,
            PhoneSetupSend.classify(ConnectException("Failed to connect to /100.84.47.125:8642"), null),
        )
        assertEquals(
            PhoneSetupSend.Failure.NO_ANSWER,
            PhoneSetupSend.classify(HermesException("hermes run: no answer within 120s"), null),
        )
        // An empty answer with no error is still no answer, not a success.
        assertEquals(PhoneSetupSend.Failure.NO_ANSWER, PhoneSetupSend.classify(null, "   "))
        // The gateway answered something unusable: the turn failed, and its reason is kept.
        assertEquals(
            PhoneSetupSend.Failure.TURN_FAILED,
            PhoneSetupSend.classify(HermesException("hermes run: unreadable body: want object"), null),
        )
        // A real reply is a success — for the turn, not for the connection.
        assertNull(PhoneSetupSend.classify(null, "Done — added vox."))
    }

    @Test
    fun `a failed send is recorded with its own line and the token scrubbed`() {
        val error = IOException("connection reset: Bearer phone-token-123 went out")
        val line = PhoneSetupSend.reason(error, null, token = "phone-token-123").orEmpty()
        assertTrue(line, line.startsWith("Your agent did not answer"))
        assertFalse("the phone's token must never reach the record or the screen", line.contains("phone-token-123"))
        assertTrue("the transport's own words survive", line.contains("connection reset"))

        val now = 1_700_000_000_000L
        val tag = PhoneMcpToken.fingerprint("phone-token-123")
        val asked = PhoneSetup.begin(PhoneSetup.Record(), now, tag)
        val failed = PhoneSetup.failed(asked, line)
        assertNull("a failed turn has no reply to show", failed.reply)
        assertEquals(PhoneSetup.State.UNREACHABLE, PhoneSetup.state(failed, now + 1_000L, tag))
        assertEquals(
            "the sheet says the cause, not a verdict of its own",
            line,
            PhoneToolsView.setupHeadline(PhoneSetup.state(failed, now + 1_000L, tag), failed.failure),
        )
    }

    @Test
    fun `a send that cannot be made still names its cause on screen`() {
        // The IDLE -> refusal path: nothing is configured, so no turn is sent at all. The attempt
        // is still stamped, because "not set up yet" would hide the reason the press did nothing.
        val now = 1_700_000_000_000L
        val tag = PhoneMcpToken.fingerprint("phone-token-123")
        val line = PhoneSetupSend.message(PhoneSetupSend.Failure.NO_CONNECTION)
        val attempted = PhoneSetup.failed(PhoneSetup.begin(PhoneSetup.Record(), now, tag), line)
        assertEquals(PhoneSetup.State.UNREACHABLE, PhoneSetup.state(attempted, now + 1_000L, tag))
        assertEquals(line, PhoneToolsView.setupHeadline(PhoneSetup.State.UNREACHABLE, attempted.failure))
    }

    // ---- Connected is the handshake's, and nothing else's ----

    @Test
    fun `an answer is never a connection`() {
        val now = 1_700_000_000_000L
        val tag = PhoneMcpToken.fingerprint("phone-token-123")
        val answered = PhoneSetup.replied(
            PhoneSetup.begin(PhoneSetup.Record(), now, tag),
            "Connected. The gateway has the vox server and the handshake succeeded.",
        )
        assertNull("the reply is not a failure", answered.failure)
        assertEquals(
            "an agent saying 'connected' is not a handshake",
            PhoneSetup.State.WAITING,
            PhoneSetup.state(answered, now + 1_000L, tag),
        )
        val handedOff = PhoneSetup.observedHandshake(answered, now + 2_000L, tag)
        assertEquals(PhoneSetup.State.CONNECTED, PhoneSetup.state(handedOff, now + 3_000L, tag))
    }

    @Test
    fun `the sheet has no client of its own to go stale`() {
        // The sheet asks the store at the press; it does not capture a session when it is built.
        val sheet = java.io.File("src/main/java/com/hermesvox/PhoneToolsSheet.kt").readText()
        assertTrue("the press resolves the connection in force", sheet.contains("PhoneSetupStore.session(act)"))
        assertFalse("no session is handed in at construction", sheet.contains("session: HermesSession"))
        assertTrue("and the cause is the record's, not a fixed line", sheet.contains("record.failure"))
    }
}
