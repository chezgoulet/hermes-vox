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
 *  * a polite "done" with no job id would trigger something guessed;
 *  * a connection drop would be reported as an error instead of the expected restart;
 *  * a live-but-unchanged gateway would be called "restarting" for ever;
 *  * a missing handshake would drift into Connected by timeout;
 *  * the token in the instruction would leak into a record, log or preference.
 */
class PhoneBootstrapTest {

    private val now = 1_700_000_000_000L
    private val host = "100.101.102.103"
    private val token = "0123456789abcdef0123456789abcdef"
    private val jobId = "a1b2c3d4e5f6"

    private fun asked(): PhoneBootstrap.Progress = PhoneBootstrap.start(now)

    private fun answered(): PhoneBootstrap.Progress =
        PhoneBootstrap.answered(asked(), "Done — added vox.\nJOB_ID: $jobId", now + 1_000L)

    private fun restarting(): PhoneBootstrap.Progress =
        PhoneBootstrap.triggered(answered(), now + 2_000L)

    private fun awaiting(): PhoneBootstrap.Progress =
        PhoneBootstrap.probe(PhoneBootstrap.probe(restarting(), up = false, now = now + 3_000L), up = true, now = now + 4_000L)

    // ---- the press says what will happen before it happens ----

    @Test fun the_button_promises_the_token_and_the_restart_before_the_press() {
        assertEquals("SET THIS UP WITH MY AGENT", PhoneBootstrap.BUTTON)
        val line = PhoneBootstrap.BUTTON_WARNING
        assertTrue(line, line.contains("this phone's address and its token"))
        assertTrue("the restart is named", line.contains("Hermes will restart"))
        assertTrue("the cost is named", line.contains("drops this call for a few seconds"))
        assertTrue("who acts is named", line.contains("Nothing is written to your host by Vox itself"))
        assertTrue("the agent acts", line.contains("your agent does the configuring"))
    }

    // ---- the flow advances only on a marked, valid job id ----

    @Test fun an_empty_answer_does_not_advance() {
        val failed = PhoneBootstrap.answered(asked(), "   ", now + 1_000L)
        assertEquals(PhoneBootstrap.Step.FAILED, failed.step)
        assertEquals(PhoneBootstrap.Failure.NO_ANSWER, failed.failure)
        assertEquals(PhoneBootstrap.Step.ASKING, failed.reached)
        assertNull(failed.jobId)
    }

    @Test fun the_job_id_is_read_only_from_one_marked_line_in_the_gateways_form() {
        assertEquals(jobId, PhoneBootstrap.jobId("Done.\nJOB_ID: $jobId"))
        assertEquals(jobId, PhoneBootstrap.jobId("JOB_ID: `$jobId`"))
        assertNull("no marker is not an id", PhoneBootstrap.jobId("Done — the job is $jobId"))
        assertNull("uppercase is not the gateway's form", PhoneBootstrap.jobId("JOB_ID: A1B2C3D4E5F6"))
        assertNull("a short string is not a job id", PhoneBootstrap.jobId("JOB_ID: abc"))
        assertNull(
            "two different marked ids are ambiguous, never a guess",
            PhoneBootstrap.jobId("JOB_ID: $jobId\nJOB_ID: ffffffffffff"),
        )
    }

    @Test fun an_answer_without_a_job_id_refuses_rather_than_guessing() {
        val failed = PhoneBootstrap.answered(asked(), "Done — I added vox.", now + 1_000L)
        assertEquals(PhoneBootstrap.Step.FAILED, failed.step)
        assertEquals(PhoneBootstrap.Failure.NO_JOB_ID, failed.failure)
        assertEquals(PhoneBootstrap.Step.ASKING, failed.reached)
        assertNull("no id was invented", failed.jobId)
    }

    @Test fun a_marked_job_id_moves_the_flow_to_the_trigger() {
        val ready = answered()
        assertEquals(PhoneBootstrap.Step.TRIGGERING, ready.step)
        assertEquals(jobId, ready.jobId)
        assertNull(ready.failure)
    }

    @Test fun a_job_cannot_be_triggered_without_an_id() {
        val asked = asked()
        assertEquals("nothing to trigger", asked, PhoneBootstrap.triggered(asked, now + 1_000L))
        val noId = PhoneBootstrap.answered(asked, "Done.", now + 1_000L)
        assertEquals("a refusal stays a refusal", noId, PhoneBootstrap.triggered(noId, now + 2_000L))
    }

    @Test fun a_gateway_refusal_is_named_and_stops_the_flow() {
        val refused = PhoneBootstrap.triggerFailed(answered())
        assertEquals(PhoneBootstrap.Step.FAILED, refused.step)
        assertEquals(PhoneBootstrap.Failure.TRIGGER_REFUSED, refused.failure)
        assertEquals(PhoneBootstrap.Step.TRIGGERING, refused.reached)
        assertTrue(PhoneBootstrap.offersFallback(refused))
    }

    @Test fun a_transport_failure_is_not_a_refusal_but_a_status_is() {
        assertFalse(PhoneBootstrap.triggerRefused(java.io.IOException("connection reset")))
        assertFalse(PhoneBootstrap.triggerRefused(Exception("timed out after 20s")))
        assertTrue(PhoneBootstrap.triggerRefused(Exception("hermes job run 404 Not Found: Job not found")))
        assertTrue(PhoneBootstrap.triggerRefused(Exception("hermes job run 400 Bad Request: Invalid job ID format")))
        assertTrue(PhoneBootstrap.triggerRefused(Exception("hermes job run 401 Unauthorized: bad key")))
    }

    // ---- the drop is the expected event, not an error ----

    @Test fun the_connection_drop_is_expected_and_not_a_failure() {
        val restarting = restarting()
        val dropped = PhoneBootstrap.probe(restarting, up = false, now = now + 3_000L)
        assertEquals("still restarting", PhoneBootstrap.Step.RESTARTING, dropped.step)
        assertNull("the drop is not a failure", dropped.failure)
        assertTrue("the expected drop is recorded", dropped.dropSeen)

        val back = PhoneBootstrap.probe(dropped, up = true, now = now + 4_000L)
        assertEquals("the return is the next step", PhoneBootstrap.Step.AWAITING_HANDSHAKE, back.step)
        assertEquals(now + 4_000L, back.restartedAt)
        assertNull(back.failure)
    }

    @Test fun a_gateway_that_answers_before_the_drop_is_not_a_restart() {
        val restarting = restarting()
        val stillUp = PhoneBootstrap.probe(restarting, up = true, now = now + 3_000L)
        assertEquals("a live gateway is not evidence it restarted", PhoneBootstrap.Step.RESTARTING, stillUp.step)
        assertFalse(stillUp.dropSeen)
        assertNull(stillUp.failure)
    }

    @Test fun a_gateway_that_never_drops_fails_with_the_step_it_reached() {
        val restarting = restarting()
        val never = PhoneBootstrap.probe(
            restarting,
            up = true,
            now = restarting.startedAt + PhoneBootstrap.RESTART_WINDOW_MS,
        )
        assertEquals(PhoneBootstrap.Step.FAILED, never.step)
        assertEquals(PhoneBootstrap.Failure.NOT_RESTARTED, never.failure)
        assertEquals(PhoneBootstrap.Step.RESTARTING, never.reached)
        assertTrue(PhoneBootstrap.offersFallback(never))
    }

    @Test fun a_gateway_that_never_comes_back_also_fails() {
        val restarting = restarting()
        val down = PhoneBootstrap.probe(restarting, up = false, now = now + 3_000L)
        val never = PhoneBootstrap.probe(
            down,
            up = false,
            now = restarting.startedAt + PhoneBootstrap.RESTART_WINDOW_MS,
        )
        assertEquals(PhoneBootstrap.Step.FAILED, never.step)
        assertEquals(PhoneBootstrap.Failure.NOT_RESTARTED, never.failure)
    }

    // ---- Connected is only the handshake's, and only this attempt's ----

    @Test fun a_handshake_from_before_the_press_is_not_evidence() {
        val restarting = restarting()
        assertEquals(restarting, PhoneBootstrap.handshake(restarting, now))
        assertEquals(restarting, PhoneBootstrap.handshake(restarting, now - 1L))
    }

    @Test fun a_new_handshake_after_the_restart_is_what_connects() {
        val awaiting = awaiting()
        val connected = PhoneBootstrap.handshake(awaiting, now + 5_000L)
        assertEquals(PhoneBootstrap.Step.CONNECTED, connected.step)
        assertNull(connected.failure)
        assertFalse(PhoneBootstrap.busy(connected))
    }

    @Test fun a_handshake_is_enough_even_if_the_probe_missed_the_return() {
        val restarting = restarting()
        val connected = PhoneBootstrap.settled(restarting, now + 5_000L, now + 6_000L)
        assertEquals(PhoneBootstrap.Step.CONNECTED, connected.step)
    }

    @Test fun a_missing_handshake_produces_the_fallback_not_connected() {
        val awaiting = awaiting()
        val late = awaiting.restartedAt + PhoneBootstrap.HANDSHAKE_WINDOW_MS
        val failed = PhoneBootstrap.settled(awaiting, handshakeAt = 0L, now = late)
        assertEquals(PhoneBootstrap.Step.FAILED, failed.step)
        assertEquals(PhoneBootstrap.Failure.NO_HANDSHAKE, failed.failure)
        assertEquals(PhoneBootstrap.Step.AWAITING_HANDSHAKE, failed.reached)
        assertTrue("the copyable prompt is the way forward", PhoneBootstrap.offersFallback(failed))
        assertFalse("a timeout is never the handshake", failed.step == PhoneBootstrap.Step.CONNECTED)
    }

    @Test fun a_late_handshake_does_not_resurrect_a_failed_attempt() {
        val failed = PhoneBootstrap.settled(
            awaiting(),
            handshakeAt = 0L,
            now = awaiting().restartedAt + PhoneBootstrap.HANDSHAKE_WINDOW_MS,
        )
        assertEquals(PhoneBootstrap.Step.FAILED, failed.step)
        assertEquals(failed, PhoneBootstrap.handshake(failed, now + PhoneBootstrap.HANDSHAKE_WINDOW_MS + 1L))
        assertEquals(failed, PhoneBootstrap.settled(failed, now + PhoneBootstrap.HANDSHAKE_WINDOW_MS + 2L, now + PhoneBootstrap.HANDSHAKE_WINDOW_MS + 3L))
    }

    @Test fun the_handshake_window_is_the_stated_three_minutes() {
        assertEquals(3 * 60_000L, PhoneBootstrap.HANDSHAKE_WINDOW_MS)
        assertEquals(PhoneSetup.TIMEOUT_MS, PhoneBootstrap.HANDSHAKE_WINDOW_MS)
    }

    // ---- the copy never says Connected except on the handshake ----

    @Test fun connected_is_the_only_step_or_failure_that_uses_the_word() {
        val steps = listOf(
            PhoneBootstrap.Step.IDLE,
            PhoneBootstrap.Step.ASKING,
            PhoneBootstrap.Step.TRIGGERING,
            PhoneBootstrap.Step.RESTARTING,
            PhoneBootstrap.Step.AWAITING_HANDSHAKE,
            PhoneBootstrap.Step.CONNECTED,
        )
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
            val copy = PhoneBootstrap.heading(PhoneBootstrap.Progress(step = PhoneBootstrap.Step.FAILED, failure = failure)) +
                " " + PhoneBootstrap.meaning(PhoneBootstrap.Progress(step = PhoneBootstrap.Step.FAILED, failure = failure))
            assertFalse("$failure must not use the word: $copy", copy.contains("connected", ignoreCase = true))
        }
    }

    @Test fun every_step_and_failure_has_its_own_words() {
        val headings = PhoneBootstrap.Step.values().flatMap { step ->
            if (step == PhoneBootstrap.Step.FAILED) {
                PhoneBootstrap.Failure.values().map {
                    PhoneBootstrap.heading(PhoneBootstrap.Progress(step = step, failure = it))
                }
            } else {
                listOf(PhoneBootstrap.heading(PhoneBootstrap.Progress(step = step)))
            }
        }
        assertEquals("each heading is its own line", headings.size, headings.toSet().size)
        for (failure in PhoneBootstrap.Failure.values()) {
            val p = PhoneBootstrap.Progress(step = PhoneBootstrap.Step.FAILED, failure = failure)
            assertTrue("$failure heading", PhoneBootstrap.heading(p).isNotBlank())
            assertTrue("$failure meaning", PhoneBootstrap.meaning(p).isNotBlank())
            assertTrue("$failure reached", PhoneBootstrap.reachedLine(p).isNotBlank())
        }
    }

    @Test fun the_restarting_line_is_the_one_the_owner_was_promised() {
        val p = PhoneBootstrap.Progress(step = PhoneBootstrap.Step.RESTARTING)
        assertEquals("Hermes is restarting — this takes a few seconds", PhoneBootstrap.heading(p))
        assertTrue(PhoneBootstrap.meaning(p).contains("expected"))
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
        assertTrue("the bootstrap logs its trigger", logLines.isNotEmpty())
        for (line in logLines) {
            assertFalse("a log line must not name the instruction: $line", line.contains("prompt", true))
            assertFalse("a log line must not name the token: $line", line.contains("token", true))
        }

        val setup = File("src/main/java/com/hermesvox/PhoneSetupStore.kt").readText()
        assertTrue("the sent prompt is memory only", setup.contains("@Volatile private var lastSent"))
        assertFalse("no prompt preference key exists", setup.contains("K_PROMPT"))
        val writes = setup.lines().filter { it.contains("putString") }
        assertTrue("the record is still written", writes.isNotEmpty())
        for (line in writes) {
            assertFalse("the persisted record never writes the instruction: $line", line.contains("prompt", true))
        }
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
        val shown = PhoneToolsSheet.drawn(status, record, tag, now, PhoneBootstrap.start(now))
        assertEquals(PhoneBootstrap.Step.ASKING, shown.bootstrap.step)
        val same = PhoneToolsSheet.drawn(status, record, tag, now + 1_000L, PhoneBootstrap.start(now))
        assertEquals("a second passing is not a change", shown, same)

        val ready = PhoneBootstrap.answered(PhoneBootstrap.start(now), "JOB_ID: $jobId", now + 1_000L)
        val next = PhoneToolsSheet.drawn(status, record, tag, now + 2_000L, ready)
        assertEquals(PhoneBootstrap.Step.TRIGGERING, next.bootstrap.step)
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
            now + 5_000L,
            tag,
        )
        val drawn = PhoneToolsSheet.drawn(status, record, tag, now + 6_000L, PhoneBootstrap.triggered(answered(), now + 2_000L))
        assertEquals("the phone's handshake wins without another poll step", PhoneBootstrap.Step.CONNECTED, drawn.bootstrap.step)
    }
}
