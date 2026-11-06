package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The one-button setup: advance only on evidence, never on prose, and never claim Connected
 * without the phone's own handshake.
 *
 * Each test is one way this could go wrong in the field:
 *
 *  * a polite "done" would be read as a handshake;
 *  * an agent that says the add failed would be treated as success;
 *  * a reply would connect the phone without any handshake;
 *  * a window would drift into Connected by timeout;
 *  * the flow would mention a restart, a dropped call or a job — none of which happen;
 *  * the token in the instruction would leak into a record, log or preference.
 */
class PhoneBootstrapTest {

    private val now = 1_700_000_000_000L
    private val host = "100.101.102.103"
    private val token = "0123456789abcdef0123456789abcdef"
    private val windowEnd = now + PhoneBootstrap.SETUP_WINDOW_MS
    private val success = "Added vox with `hermes mcp add`; it verified and saved. mcp_servers now lists vox."

    private fun adding(): PhoneBootstrap.Progress = PhoneBootstrap.start(now, windowEnd)

    private fun waiting(): PhoneBootstrap.Progress =
        PhoneBootstrap.answered(adding(), success, now + 15_000L)

    // ---- the press says what will happen before it happens ----

    @Test fun the_button_promises_the_token_and_the_window_before_the_press() {
        assertEquals("SET THIS UP WITH MY AGENT", PhoneBootstrap.BUTTON)
        val line = PhoneBootstrap.BUTTON_WARNING
        assertTrue(line, line.contains("this phone's address and its token"))
        assertTrue("the window is named and its length stated", line.contains("three minutes"))
        assertTrue("the listener is the thing that opens", line.contains("listener"))
        assertTrue("the restart is denied, not promised", line.contains("nothing restarts"))
        assertTrue("who acts is named", line.contains("Nothing is written to your host by Vox itself"))
        assertTrue("the agent acts", line.contains("your agent does the configuring"))
        assertFalse("no call is dropped", line.contains("drops this call"))
    }

    // ---- the reply advances to waiting, and only a successful add does ----

    @Test fun an_empty_answer_does_not_advance() {
        val failed = PhoneBootstrap.answered(adding(), "   ", now + 1_000L)
        assertEquals(PhoneBootstrap.Step.FAILED, failed.step)
        assertEquals(PhoneBootstrap.Failure.NO_ANSWER, failed.failure)
        assertEquals(PhoneBootstrap.Step.ADDING, failed.reached)
    }

    @Test fun a_reply_reporting_a_successful_add_moves_to_waiting_not_connected() {
        val ready = waiting()
        assertEquals(PhoneBootstrap.Step.WAITING, ready.step)
        assertNull(ready.failure)
        assertEquals("the reply's time starts the handshake window", now + 15_000L, ready.answeredAt)
        assertTrue("waiting is not proof", PhoneBootstrap.busy(ready))
        assertFalse(
            (PhoneBootstrap.heading(ready) + " " + PhoneBootstrap.meaning(ready))
                .contains("connected", ignoreCase = true),
        )
    }

    @Test fun a_reply_that_the_add_failed_is_named_and_does_not_advance() {
        val failed = PhoneBootstrap.answered(
            adding(),
            "I could not add vox: `hermes mcp add` could not reach 100.101.102.103:8765.",
            now + 1_000L,
        )
        assertEquals(PhoneBootstrap.Step.FAILED, failed.step)
        assertEquals(PhoneBootstrap.Failure.ADD_REFUSED, failed.failure)
        assertEquals(PhoneBootstrap.Step.ADDING, failed.reached)
        assertTrue(PhoneBootstrap.offersFallback(failed))
    }

    @Test fun a_failure_hint_in_an_otherwise_good_reply_does_not_fail_the_add() {
        assertFalse(
            "a sentence about a possible probe failure is not a report that the add failed",
            PhoneBootstrap.looksFailed(
                "Added vox. The command's probe succeeded; had it failed it would have refused to save.",
            ),
        )
        assertTrue(PhoneBootstrap.looksFailed("hermes mcp add: command not found"))
        assertFalse(PhoneBootstrap.looksFailed(success))
    }

    // ---- Connected is only the handshake's, and only this attempt's ----

    @Test fun a_handshake_from_before_the_press_is_not_evidence() {
        val adding = adding()
        assertEquals(adding, PhoneBootstrap.handshake(adding, now))
        assertEquals(adding, PhoneBootstrap.handshake(adding, now - 1L))
    }

    @Test fun a_new_handshake_while_adding_is_the_strongest_evidence() {
        val connected = PhoneBootstrap.handshake(adding(), now + 5_000L)
        assertEquals(PhoneBootstrap.Step.CONNECTED, connected.step)
        assertNull(connected.failure)
        assertFalse(PhoneBootstrap.busy(connected))
    }

    @Test fun a_new_handshake_while_waiting_is_what_connects() {
        val connected = PhoneBootstrap.settled(waiting(), now + 60_000L, now + 61_000L)
        assertEquals(PhoneBootstrap.Step.CONNECTED, connected.step)
        assertNull(connected.failure)
    }

    @Test fun a_missing_handshake_produces_the_fallback_not_connected() {
        val failed = PhoneBootstrap.settled(waiting(), handshakeAt = 0L, now = now + 200_000L)
        assertEquals(PhoneBootstrap.Step.FAILED, failed.step)
        assertEquals(PhoneBootstrap.Failure.NO_HANDSHAKE, failed.failure)
        assertEquals(PhoneBootstrap.Step.WAITING, failed.reached)
        assertTrue("the copyable prompt is the way forward", PhoneBootstrap.offersFallback(failed))
    }

    @Test fun a_late_handshake_does_not_resurrect_a_failed_attempt() {
        val failed = PhoneBootstrap.settled(waiting(), 0L, now + 200_000L)
        assertEquals(PhoneBootstrap.Step.FAILED, failed.step)
        assertEquals(failed, PhoneBootstrap.handshake(failed, now + 300_000L))
        assertEquals(failed, PhoneBootstrap.settled(failed, now + 301_000L, now + 302_000L))
    }

    // ---- the window is stated, measured, and closed by its own clock ----

    @Test fun the_setup_window_is_three_minutes_and_the_handshake_window_is_generous() {
        assertEquals(3 * 60_000L, PhoneBootstrap.SETUP_WINDOW_MS)
        assertEquals(90_000L, PhoneBootstrap.HANDSHAKE_WINDOW_MS)
        assertTrue(
            "the handshake window must fit inside the listener's window",
            PhoneBootstrap.HANDSHAKE_WINDOW_MS < PhoneBootstrap.SETUP_WINDOW_MS,
        )
    }

    @Test fun an_unanswered_agent_fails_when_the_listener_window_closes() {
        val failed = PhoneBootstrap.settled(adding(), 0L, windowEnd)
        assertEquals(PhoneBootstrap.Step.FAILED, failed.step)
        assertEquals(PhoneBootstrap.Failure.NO_HANDSHAKE, failed.failure)
        assertEquals(PhoneBootstrap.Step.ADDING, failed.reached)
    }

    @Test fun the_handshake_deadline_is_the_reply_plus_the_window() {
        assertEquals(now + 15_000L + PhoneBootstrap.HANDSHAKE_WINDOW_MS, PhoneBootstrap.deadline(waiting()))
        val stillWaiting = PhoneBootstrap.settled(
            waiting(),
            0L,
            now + 15_000L + PhoneBootstrap.HANDSHAKE_WINDOW_MS - 1L,
        )
        assertEquals(PhoneBootstrap.Step.WAITING, stillWaiting.step)
    }

    @Test fun the_listeners_close_caps_the_handshake_wait() {
        // A late reply leaves less than the full handshake window, because the listener cannot be
        // reached after the setup window ends.
        val late = PhoneBootstrap.answered(adding(), success, windowEnd - 10_000L)
        assertEquals(windowEnd, PhoneBootstrap.deadline(late))
        val expired = PhoneBootstrap.settled(late, 0L, windowEnd)
        assertEquals(PhoneBootstrap.Failure.NO_HANDSHAKE, expired.failure)
    }

    @Test fun the_remaining_window_is_in_the_owners_words() {
        val adding = adding()
        assertTrue(PhoneBootstrap.listeningLine(adding, now).contains("another 3 minutes"))
        assertTrue(PhoneBootstrap.listeningLine(adding, now + 61_000L).contains("about a minute"))
        assertTrue(PhoneBootstrap.listeningLine(adding, now + 170_000L).contains("less than a minute"))
        assertTrue(
            "and that it closes by itself",
            PhoneBootstrap.listeningLine(adding, now).contains("closes by itself"),
        )
        assertEquals("", PhoneBootstrap.listeningLine(PhoneBootstrap.Progress(), now))
        assertEquals("", PhoneBootstrap.listeningLine(waiting().copy(step = PhoneBootstrap.Step.CONNECTED), now))
    }

    // ---- the copy never says Connected except on the handshake ----

    @Test fun connected_is_the_only_step_or_failure_that_uses_the_word() {
        val steps = PhoneBootstrap.Step.values().filter { it != PhoneBootstrap.Step.FAILED }
        for (step in steps) {
            val copy = PhoneBootstrap.heading(PhoneBootstrap.Progress(step = step)) + " " +
                PhoneBootstrap.meaning(PhoneBootstrap.Progress(step = step))
            if (step == PhoneBootstrap.Step.CONNECTED) {
                assertTrue(copy, copy.contains("Connected"))
            } else {
                assertFalse("$step must not use the word: $copy", copy.contains("connected", ignoreCase = true))
            }
        }
        for (failure in PhoneBootstrap.Failure.values()) {
            val p = PhoneBootstrap.Progress(step = PhoneBootstrap.Step.FAILED, failure = failure)
            val copy = PhoneBootstrap.heading(p) + " " + PhoneBootstrap.meaning(p)
            assertFalse("$failure must not use the word: $copy", copy.contains("connected", ignoreCase = true))
        }
    }

    @Test fun every_step_and_failure_has_its_own_words() {
        val headings = PhoneBootstrap.Step.values().filter { it != PhoneBootstrap.Step.FAILED }.map {
            PhoneBootstrap.heading(PhoneBootstrap.Progress(step = it))
        } + PhoneBootstrap.Failure.values().map {
            PhoneBootstrap.heading(PhoneBootstrap.Progress(step = PhoneBootstrap.Step.FAILED, failure = it))
        }
        assertEquals("each heading is its own line", headings.size, headings.toSet().size)
        for (failure in PhoneBootstrap.Failure.values()) {
            val p = PhoneBootstrap.Progress(step = PhoneBootstrap.Step.FAILED, failure = failure)
            assertTrue("$failure heading", PhoneBootstrap.heading(p).isNotBlank())
            assertTrue("$failure meaning", PhoneBootstrap.meaning(p).isNotBlank())
            assertTrue("$failure reached", PhoneBootstrap.reachedLine(p).isNotBlank())
        }
    }

    @Test fun the_expired_window_says_the_add_may_not_have_taken_and_offers_the_prompt() {
        val p = PhoneBootstrap.Progress(
            step = PhoneBootstrap.Step.FAILED,
            failure = PhoneBootstrap.Failure.NO_HANDSHAKE,
        )
        assertEquals("The setup may not have taken", PhoneBootstrap.heading(p))
        assertTrue(PhoneBootstrap.meaning(p).contains("the add may not have taken"))
        assertTrue("its last words are shown by the sheet", PhoneBootstrap.meaning(p).contains("last said"))
        assertTrue(PhoneBootstrap.meaning(p).contains("Copy the setup prompt"))
        assertTrue(PhoneBootstrap.offersFallback(p))
    }

    // ---- the token goes out once and stays out of everything else ----

    @Test fun the_instruction_carries_the_token_and_the_flow_does_not() {
        assertTrue("the outgoing instruction carries it", PhoneSetup.prompt(host, token).contains(token))
        val copies = PhoneBootstrap.Step.values().map { step ->
            PhoneBootstrap.heading(PhoneBootstrap.Progress(step = step)) + " " +
                PhoneBootstrap.meaning(PhoneBootstrap.Progress(step = step))
        } + PhoneBootstrap.Failure.values().map { failure ->
            val p = PhoneBootstrap.Progress(step = PhoneBootstrap.Step.FAILED, failure = failure)
            PhoneBootstrap.heading(p) + " " + PhoneBootstrap.meaning(p) + " " + PhoneBootstrap.reachedLine(p)
        }
        for (copy in copies) assertFalse("the token must not be rendered: $copy", copy.contains(token))
    }

    @Test fun an_echoed_token_is_scrubbed_before_it_is_stored() {
        val echoed = "Added vox with Bearer $token."
        val clean = PhoneSetupSend.redact(echoed, token)
        assertNotNull(clean)
        assertFalse(clean!!.contains(token))
        assertTrue(clean.contains("[token]"))
        assertEquals("nothing else changes", "Added vox with Bearer [token].", clean)
        assertNull(PhoneSetupSend.redact(null, token))
    }

    /**
     * The store is the Android edge: the instruction is held in [PhoneSetupStore.lastPrompt] for
     * this process only. A source scan is the off-device proof that no preference, audit entry or
     * log line can contain it — the same class of check the app uses for its no-DELETE rule.
     */
    @Test fun the_bootstrap_never_writes_the_instruction_to_a_preference_an_audit_or_a_log() {
        val store = File("src/main/java/com/hermesvox/PhoneBootstrapStore.kt").readText()
        assertFalse("the bootstrap holds no preferences", store.contains("getSharedPreferences"))
        assertFalse("the bootstrap writes no audit entry", store.contains("PhoneAuditStore"))
        val logLines = store.lines().filter { it.contains("VoxLog") }
        for (line in logLines) {
            assertFalse("a log line must not name the instruction: $line", line.contains("prompt", true))
            assertFalse("a log line must not name the token: $line", line.contains("token", true))
        }

        val setup = File("src/main/java/com/hermesvox/PhoneSetupStore.kt").readText()
        assertTrue("the sent prompt is memory only", setup.contains("@Volatile private var lastSent"))
        assertTrue("the flow remembers the turn it sent, for the fallback",
            store.contains("PhoneSetupStore.rememberSent(prompt)"))
        assertTrue("a rotation forgets it, because it carries the old token", setup.contains("lastSent = null"))
        assertFalse("no prompt preference key exists", setup.contains("K_PROMPT"))
        val writes = setup.lines().filter { it.contains("putString") }
        assertTrue("the record is still written", writes.isNotEmpty())
        for (line in writes) {
            assertFalse("the persisted record never writes the instruction: $line", line.contains("prompt", true))
        }
    }

    // ---- nothing in the flow restarts, probes or triggers anything ----

    @Test fun the_flow_has_no_restart_job_or_job_trigger_left() {
        val store = File("src/main/java/com/hermesvox/PhoneBootstrapStore.kt").readText()
        val bootstrap = File("src/main/java/com/hermesvox/PhoneBootstrap.kt").readText()
        val prompt = File("src/main/java/com/hermesvox/PhoneSetup.kt").readText()
        for ((name, text) in listOf("store" to store, "bootstrap" to bootstrap, "setup" to prompt)) {
            assertFalse("$name must not trigger a job", text.contains("runJob"))
            assertFalse("$name must not call a jobs route", text.contains("/api/jobs"))
            assertFalse("$name must not parse a job id", text.contains("JOB_ID"))
        }
        for (forbidden in listOf("restart hermes-gateway", "hermes cron create", "systemctl --user restart")) {
            assertFalse("the instruction must not ask for $forbidden", prompt.contains(forbidden))
        }
        assertFalse(
            "no copy promises a restart or a dropped call",
            bootstrap.contains("Hermes is restarting") || bootstrap.contains("will restart"),
        )
        assertTrue(
            "the instruction says the opposite, so the agent does not restart the gateway",
            prompt.contains("No restart is needed and none is wanted"),
        )
    }

    // ---- the sheet draws the flow's own step ----

    @Test fun the_sheet_repaints_only_when_the_bootstrap_step_changes() {
        val status = PhoneMcp.Status(
            tailnet = host,
            listening = true,
            url = "http://$host:8765/mcp",
            served = 0,
            lastRequest = null,
            lastRequestAt = null,
            token = PhoneMcp.TokenState.STORED,
            dndGranted = true,
            volumeGranted = true,
        )
        val tag = PhoneMcpToken.fingerprint(token)
        val record = PhoneSetup.Record(requestedAt = now, tokenTag = tag)
        val shown = PhoneToolsSheet.drawn(status, record, tag, now + 1_000L, waiting())
        assertEquals(PhoneBootstrap.Step.WAITING, shown.bootstrap.step)
        assertTrue(shown.listening.contains("another 2 minutes"))
        val same = PhoneToolsSheet.drawn(status, record, tag, now + 2_000L, waiting())
        assertEquals("a second passing is not a change", shown, same)
        val later = PhoneToolsSheet.drawn(status, record, tag, now + 61_000L, waiting())
        assertTrue("a minute passing is: the window's own clock moved", PhoneToolsSheet.repaints(same, later))

        val next = PhoneToolsSheet.drawn(status, record, tag, now + 2_000L, adding())
        assertEquals(PhoneBootstrap.Step.ADDING, next.bootstrap.step)
        assertTrue("the step changed, so the sheet repaints", PhoneToolsSheet.repaints(shown, next))
    }

    @Test fun the_sheet_shows_the_handshake_and_not_connected_from_a_stored_attempt() {
        val status = PhoneMcp.Status(
            tailnet = host,
            listening = true,
            url = "http://$host:8765/mcp",
            served = 0,
            lastRequest = null,
            lastRequestAt = null,
            token = PhoneMcp.TokenState.STORED,
            dndGranted = true,
            volumeGranted = true,
        )
        val tag = PhoneMcpToken.fingerprint(token)
        val record = PhoneSetup.observedHandshake(
            PhoneSetup.Record(requestedAt = now, tokenTag = tag),
            now + 20_000L,
            tag,
        )
        val drawn = PhoneToolsSheet.drawn(status, record, tag, now + 21_000L, adding())
        assertEquals("the phone's handshake wins without another poll step", PhoneBootstrap.Step.CONNECTED, drawn.bootstrap.step)
    }
}
