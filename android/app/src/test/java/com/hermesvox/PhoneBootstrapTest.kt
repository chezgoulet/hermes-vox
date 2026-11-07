package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The one-button setup: advance only on evidence, never on prose, never claim Connected without the
 * phone's own handshake, and never put a clock on the agent.
 *
 * Each test is one way this could go wrong in the field:
 *
 *  * a polite "done" would be read as a handshake;
 *  * an agent that says the add failed would be treated as success;
 *  * a reply would connect the phone without any handshake;
 *  * a two-minute ceiling would stop a run that was still working, and the card would say nothing;
 *  * the work would be invisible — one frozen *waiting* line for the whole attempt;
 *  * an argument would be rendered, carrying this phone's token to the screen;
 *  * the flow would mention a restart, a dropped call or a job — none of which happen;
 *  * the token in the instruction would leak into a record, log or preference.
 */
class PhoneBootstrapTest {

    private val now = 1_700_000_000_000L
    private val host = "100.101.102.103"
    private val token = "0123456789abcdef0123456789abcdef"
    private val success = "Added vox with `hermes mcp add`; it verified and saved. mcp_servers now lists vox."

    private fun adding(): PhoneBootstrap.Progress = PhoneBootstrap.start(now)

    private fun waiting(): PhoneBootstrap.Progress =
        PhoneBootstrap.answered(adding(), success, now + 15_000L)

    /** A drained poll with tool openings, as `pollStreamJSON` would carry them. */
    private fun tools(vararg names: String): PhoneSetupRun.Poll =
        PhoneSetupRun.Poll(
            tools = names.mapIndexed { i, n -> PhoneSetupRun.Tool(n, "call_$i") },
            events = names.size + 1,
        )

    // ---- the press says what will happen before it happens ----

    @Test fun the_button_promises_the_token_and_the_listener_before_the_press() {
        assertEquals("SET THIS UP WITH MY AGENT", PhoneBootstrap.BUTTON)
        val line = PhoneBootstrap.BUTTON_WARNING
        assertTrue(line, line.contains("this phone's address and its token"))
        assertTrue("the listener is named", line.contains("listener"))
        assertTrue("its life follows the attempt, not a clock", line.contains("as long as this attempt runs"))
        assertFalse("no fixed window is promised", line.contains("three minutes"))
        assertTrue("the restart is denied, not promised", line.contains("nothing restarts"))
        assertTrue("who acts is named", line.contains("Nothing is written to your host by Vox itself"))
        assertTrue("the agent acts", line.contains("your agent does the configuring"))
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
        assertEquals("the reply's time starts the post-run grace", now + 15_000L, ready.answeredAt)
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

    @Test fun a_missing_handshake_after_the_run_produces_the_fallback_not_connected() {
        val failed = PhoneBootstrap.settled(
            waiting(),
            handshakeAt = 0L,
            now = now + 15_000L + PhoneBootstrap.HANDSHAKE_GRACE_MS,
        )
        assertEquals(PhoneBootstrap.Step.FAILED, failed.step)
        assertEquals(PhoneBootstrap.Failure.NO_HANDSHAKE, failed.failure)
        assertEquals(PhoneBootstrap.Step.WAITING, failed.reached)
        assertTrue("the copyable prompt is the way forward", PhoneBootstrap.offersFallback(failed))
        assertTrue("the record names the missing handshake", PhoneBootstrap.failureLine(failed).contains("without the MCP handshake"))
    }

    @Test fun a_late_handshake_does_not_resurrect_a_failed_attempt() {
        val failed = PhoneBootstrap.settled(
            waiting(), 0L, now + 15_000L + PhoneBootstrap.HANDSHAKE_GRACE_MS,
        )
        assertEquals(PhoneBootstrap.Step.FAILED, failed.step)
        assertEquals(failed, PhoneBootstrap.handshake(failed, now + 300_000L))
        assertEquals(failed, PhoneBootstrap.settled(failed, now + 301_000L, now + 302_000L))
    }

    /**
     * The progress is per-process; the handshake is in the phone's record. So a fresh process —
     * no attempt, [PhoneBootstrap.Step.IDLE] — folds the kept handshake in and is Connected, and
     * that is the restart case the Settings row and the sheet must both read.
     */
    @Test fun a_kept_handshake_connects_a_fresh_process_that_never_asked() {
        val fresh = PhoneBootstrap.Progress()
        assertEquals(PhoneBootstrap.Step.IDLE, fresh.step)
        val settled = PhoneBootstrap.settled(fresh, now - 60_000L, now)
        assertEquals(PhoneBootstrap.Step.CONNECTED, settled.step)
        assertNull(settled.failure)
        assertFalse(PhoneBootstrap.busy(settled))
        // Nothing recorded is still nothing recorded.
        assertEquals(PhoneBootstrap.Step.IDLE, PhoneBootstrap.settled(fresh, 0L, now).step)
    }

    /** A failure the owner just watched is not hidden behind evidence from before it. */
    @Test fun a_failed_attempt_outranks_a_handshake_from_before_it_failed() {
        val failed = PhoneBootstrap.Progress(
            step = PhoneBootstrap.Step.FAILED,
            failure = PhoneBootstrap.Failure.NO_HANDSHAKE,
            reached = PhoneBootstrap.Step.WAITING,
            startedAt = now,
            answeredAt = now + 15_000L,
        )
        // Recorded during the attempt, and long after it: both lose to the failure.
        for (handshakeAt in listOf(now + 5_000L, now + 900_000L)) {
            val settled = PhoneBootstrap.settled(failed, handshakeAt, now + 1_000_000L)
            assertEquals(PhoneBootstrap.Step.FAILED, settled.step)
            assertEquals(PhoneBootstrap.Failure.NO_HANDSHAKE, settled.failure)
        }
    }

    // ---- there is no deadline on the agent ----

    /**
     * The bug this lane exists for: at 120 seconds the app posted `/stop` at a run that was still
     * working. Nothing in this flow may end an attempt for elapsed time. Six hours is not a
     * deadline; the listener is still open and there is no post-run grace to expire.
     */
    @Test fun hours_pass_without_ending_the_run_or_closing_the_listener() {
        val adding = adding()
        for (elapsed in listOf(120_000L, 3 * 60_000L, 60 * 60_000L, 6 * 60 * 60_000L)) {
            val at = now + elapsed
            assertEquals("no deadline at ${elapsed}ms", PhoneBootstrap.Step.ADDING, PhoneBootstrap.expire(adding, at).step)
            assertEquals("no deadline at ${elapsed}ms", 0L, PhoneBootstrap.deadline(adding))
            assertTrue("the listener is still open at ${elapsed}ms", PhoneBootstrap.listenerOpen(adding))
            assertEquals(
                "and the card says so without a countdown",
                PhoneBootstrap.Step.ADDING,
                PhoneBootstrap.settled(adding, 0L, at).step,
            )
        }
    }

    @Test fun the_only_clock_is_the_short_grace_after_the_run_ends() {
        val answered = waiting()
        assertEquals(now + 15_000L + PhoneBootstrap.HANDSHAKE_GRACE_MS, PhoneBootstrap.deadline(answered))
        assertTrue("a short grace, not a minute and a half and not three minutes",
            PhoneBootstrap.HANDSHAKE_GRACE_MS in 1..60_000L)
        val still = PhoneBootstrap.settled(answered, 0L, PhoneBootstrap.deadline(answered) - 1L)
        assertEquals(PhoneBootstrap.Step.WAITING, still.step)
        val over = PhoneBootstrap.settled(answered, 0L, PhoneBootstrap.deadline(answered))
        assertEquals(PhoneBootstrap.Failure.NO_HANDSHAKE, over.failure)
    }

    @Test fun the_listener_follows_the_run_and_closes_after_it() {
        assertTrue("open while the agent works", PhoneBootstrap.listenerOpen(adding()))
        assertTrue("open through the post-run grace", PhoneBootstrap.listenerOpen(waiting()))
        assertFalse("closed the moment the handshake lands", PhoneBootstrap.listenerOpen(
            PhoneBootstrap.Progress(step = PhoneBootstrap.Step.CONNECTED),
        ))
        assertFalse("closed when the attempt fails", PhoneBootstrap.listenerOpen(
            PhoneBootstrap.settled(waiting(), 0L, now + 15_000L + PhoneBootstrap.HANDSHAKE_GRACE_MS),
        ))
        assertFalse("closed when the owner stops it", PhoneBootstrap.listenerOpen(
            PhoneBootstrap.failed(adding(), PhoneBootstrap.Failure.CANCELLED),
        ))
    }

    @Test fun the_listening_line_states_a_life_not_a_countdown() {
        val adding = adding()
        val line = PhoneBootstrap.listeningLine(adding, now)
        assertTrue(line, line.contains("listening for this attempt"))
        assertTrue("it says when it closes", line.contains("closes when the run ends"))
        assertFalse("no remaining-time number", line.contains("another"))
        assertFalse("no clock to count down", line.contains("minute"))
        assertEquals("", PhoneBootstrap.listeningLine(PhoneBootstrap.Progress(), now))
        assertEquals("", PhoneBootstrap.listeningLine(waiting().copy(step = PhoneBootstrap.Step.CONNECTED), now))
        assertTrue(
            "the post-run line is a moment longer, not a deadline",
            PhoneBootstrap.listeningLine(waiting(), now).contains("a moment longer"),
        )
    }

    // ---- the work is shown, and never the arguments ----

    @Test fun tool_events_append_lines_in_arrival_order() {
        var p = adding()
        p = PhoneBootstrap.observed(p, tools("terminal"), now + 1_000L)
        p = PhoneBootstrap.observed(p, tools("read_file"), now + 2_000L)
        p = PhoneBootstrap.observed(p, tools("terminal", "write_file"), now + 3_000L)
        assertEquals(
            listOf(
                "terminal · ran a command",
                "read_file · read a file",
                "terminal · ran a command",
                "write_file · wrote a file",
            ),
            PhoneBootstrap.activityLines(p),
        )
        assertEquals("the last event's time is the silence line's input", now + 3_000L, p.lastEventAt)
    }

    @Test fun an_unknown_tool_renders_its_own_name() {
        val p = PhoneBootstrap.observed(adding(), tools("quantum_flux"), now + 1_000L)
        assertEquals(listOf("quantum_flux"), PhoneBootstrap.activityLines(p))
    }

    /**
     * One call is one line, whichever route carried it: `/v1/runs` sends `tool.started`, while the
     * responses fallback sends `output_item.added` and later `output_item.done` for the same call.
     * The closing item is not a second action.
     */
    @Test fun one_tool_call_is_one_line_on_either_route() {
        val payload = """
            {"done":false,"text":"","events":[
              {"type":"response.output_item.added","item_type":"function_call","item_id":"c1","name":"terminal"},
              {"type":"response.output_item.done","item_type":"function_call","item_id":"c1","name":"terminal"}
            ]}
        """.trimIndent()
        assertEquals(1, PhoneSetupRun.parse(payload).tools.size)
    }

    @Test fun a_poll_with_no_events_changes_nothing_and_is_not_news() {
        val p = adding().copy(lastEventAt = now + 1_000L)
        val same = PhoneBootstrap.observed(p, PhoneSetupRun.Poll(events = 0), now + 120_000L)
        assertEquals(p, same)
        val delta = PhoneBootstrap.observed(p, PhoneSetupRun.Poll(events = 1), now + 120_000L)
        assertEquals("an answer delta moves the age even without a tool", now + 120_000L, delta.lastEventAt)
        assertTrue("and adds no invented action", delta.activity.isEmpty())
    }

    /**
     * The instruction carries this phone's token, and a tool call made to act on it can carry the
     * same bytes in its arguments. The parser reads only the app's normalized `item_type` and
     * `name`; the arguments are never decoded, so they cannot reach a line.
     */
    @Test fun an_argument_string_containing_the_token_never_reaches_a_line() {
        val payload = """
            {"done":false,"text":"","events":[
              {"type":"response.output_item.added","item_type":"function_call","item_id":"call_1",
               "name":"terminal",
               "arguments":"{\"command\":\"hermes mcp add vox --auth header Bearer $token\"}"},
              {"type":"response.output_item.added","item_type":"function_call_output","item_id":"call_1",
               "output":"Bearer $token"}
            ]}
        """.trimIndent()
        val poll = PhoneSetupRun.parse(payload)
        assertEquals("only the opening is a tool line", 1, poll.tools.size)
        assertEquals("terminal", poll.tools.single().name)
        val p = PhoneBootstrap.observed(adding(), poll, now + 1_000L)
        for (line in PhoneBootstrap.activityLines(p)) {
            assertFalse("the token must not be rendered: $line", line.contains(token))
        }
        assertEquals(listOf("terminal · ran a command"), PhoneBootstrap.activityLines(p))

        // The Android edge never names an argument or a preview either: there is no road from the
        // poll to the screen that could carry one.
        val store = File("src/main/java/com/hermesvox/PhoneBootstrapStore.kt").readText()
        val run = File("src/main/java/com/hermesvox/PhoneSetupRun.kt").readText()
        for ((name, text) in listOf("store" to store, "run" to run)) {
            assertFalse("$name must not read the raw arguments", text.contains("\"arguments\""))
            assertFalse("$name must not read the tool preview", text.contains("\"preview\""))
            assertFalse("$name must not log event text", text.contains("VoxLog.d(line"))
        }
    }

    @Test fun the_history_survives_the_outcome() {
        val p = PhoneBootstrap.observed(adding(), tools("terminal"), now + 1_000L)
        val failed = PhoneBootstrap.settled(
            PhoneBootstrap.answered(p, success, now + 15_000L),
            0L,
            now + 15_000L + PhoneBootstrap.HANDSHAKE_GRACE_MS,
        )
        assertEquals(PhoneBootstrap.Step.FAILED, failed.step)
        assertEquals("the list is a history, not the live line", listOf("terminal · ran a command"), PhoneBootstrap.activityLines(failed))

        val connected = PhoneBootstrap.handshake(p, now + 2_000L)
        assertEquals("and it stays with the proof too", listOf("terminal · ran a command"), PhoneBootstrap.activityLines(connected))
    }

    @Test fun silence_is_an_age_and_never_a_countdown() {
        assertEquals("under a minute is just thinking", "", PhoneBootstrap.silence(adding().copy(lastEventAt = now), now + 59_000L))
        val four = PhoneBootstrap.silence(adding().copy(lastEventAt = now), now + 4 * 60_000L)
        assertEquals("Nothing new for 4 minutes.", four)
        assertFalse("no predicted stop", four.contains("stop", ignoreCase = true))
        assertFalse("no countdown language", four.contains("left") || four.contains("closes"))
        assertEquals("Nothing new for 1 minute.", PhoneBootstrap.silence(adding().copy(lastEventAt = now), now + 60_000L))
        assertEquals("nothing to age before the first event", "", PhoneBootstrap.silence(adding(), now + 600_000L))
        assertEquals(
            "and no silence line once the attempt is over",
            "",
            PhoneBootstrap.silence(waiting().copy(step = PhoneBootstrap.Step.FAILED), now + 600_000L),
        )
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
            assertTrue("$failure record line", PhoneBootstrap.failureLine(p).isNotBlank())
        }
    }

    @Test fun the_expired_grace_says_the_add_may_not_have_taken_and_offers_the_prompt() {
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

    @Test fun stopping_says_so_instead_of_going_quiet() {
        for (failure in listOf(PhoneBootstrap.Failure.CANCELLED, PhoneBootstrap.Failure.LEFT)) {
            val p = PhoneBootstrap.failed(adding(), failure)
            val copy = PhoneBootstrap.heading(p) + " " + PhoneBootstrap.meaning(p) + " " +
                PhoneBootstrap.reachedLine(p)
            assertTrue(copy, copy.contains("stopped", ignoreCase = true))
            assertTrue("the record keeps the reason: $copy", PhoneBootstrap.failureLine(p).contains("stopped"))
            assertTrue("and there is a way forward", PhoneBootstrap.meaning(p).contains("Copy the setup prompt"))
        }
    }

    // ---- the token goes out once and stays out of everything else ----

    @Test fun the_instruction_carries_the_token_and_the_flow_does_not() {
        assertTrue("the outgoing instruction carries it", PhoneSetup.prompt(host, token).contains(token))
        val copies = PhoneBootstrap.Step.values().map { step ->
            PhoneBootstrap.heading(PhoneBootstrap.Progress(step = step)) + " " +
                PhoneBootstrap.meaning(PhoneBootstrap.Progress(step = step))
        } + PhoneBootstrap.Failure.values().map { failure ->
            val p = PhoneBootstrap.Progress(step = PhoneBootstrap.Step.FAILED, failure = failure)
            PhoneBootstrap.heading(p) + " " + PhoneBootstrap.meaning(p) + " " +
                PhoneBootstrap.reachedLine(p) + " " + PhoneBootstrap.failureLine(p)
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

    // ---- no deadline, no stop, and one attempt ----

    /**
     * The `/stop` this lane removes is not merely unreachable by masking it: the setup reads the
     * run's event stream until the run itself ends, and the only stop call is [PhoneBootstrapStore.cancel],
     * the owner's press or the sheet going away.
     */
    @Test fun the_flow_never_stops_a_run_on_a_clock() {
        val store = File("src/main/java/com/hermesvox/PhoneBootstrapStore.kt").readText()
        val session = File("src/main/java/com/hermesvox/core/HermesSession.kt").readText()
        val streams = File("src/main/java/com/hermesvox/core/Stream.kt").readText()
        assertTrue("the turn rides the run's own events", store.contains("session.startSetupStream(prompt)"))
        assertTrue("and drains it in a loop", store.contains("session.waitStream(sid, POLL_MS)"))
        assertFalse("no turnStored, which carries the ordinary turn ceiling",
            store.contains("turnStored"))
        assertTrue("the stop lives only in cancel()", store.contains("session.cancelStream(sid)"))
        val cancelAt = store.indexOf("fun cancel(")
        val stopAt = store.indexOf("session.cancelStream(sid)")
        assertTrue("and cancel() is the owner's, not a clock's", cancelAt in 1 until stopAt)
        assertFalse("the store holds no timeout constant", store.contains("STREAM_TURN_TIMEOUT_MS"))
        assertTrue("the run of a setup turn is never abandoned on a clock", streams.contains("keepRun"))
        assertTrue("a persistent stream is what the setup starts",
            session.contains("Streams.startPersistent("))
    }

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

    private fun status() = PhoneMcp.Status(
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

    @Test fun the_sheet_repaints_when_the_work_or_the_step_changes_and_not_on_a_tick() {
        val tag = PhoneMcpToken.fingerprint(token)
        val record = PhoneSetup.Record(requestedAt = now, tokenTag = tag)

        val shown = PhoneToolsSheet.drawn(status(), record, tag, now + 1_000L, waiting())
        assertEquals(PhoneBootstrap.Step.WAITING, shown.bootstrap.step)
        assertTrue(shown.listening.contains("a moment longer"))
        val same = PhoneToolsSheet.drawn(status(), record, tag, now + 2_000L, waiting())
        assertEquals("a second passing is not a change", shown, same)

        val worked = PhoneBootstrap.observed(waiting(), tools("terminal"), now + 5_000L)
        val next = PhoneToolsSheet.drawn(status(), record, tag, now + 5_000L, worked)
        assertEquals(listOf("terminal · ran a command"), next.activity)
        val work = PhoneToolsSheet.work(shown, next)
        assertTrue("a new line is a change, so the setup block is rewritten", work.setup)
        assertEquals("and the grant list is not", false, work.body)
        assertEquals("and the step did not move", PhoneBootstrap.Step.WAITING, next.bootstrap.step)
    }

    @Test fun the_sheet_shows_the_silence_age_after_a_quiet_stretch() {
        val tag = PhoneMcpToken.fingerprint(token)
        val record = PhoneSetup.Record(requestedAt = now, tokenTag = tag)
        val quiet = adding().copy(lastEventAt = now)
        assertTrue(PhoneToolsSheet.drawn(status(), record, tag, now + 30_000L, quiet).silence.isEmpty())
        assertEquals(
            "Nothing new for 4 minutes.",
            PhoneToolsSheet.drawn(status(), record, tag, now + 240_000L, quiet).silence,
        )
    }

    @Test fun the_sheet_shows_the_handshake_and_not_connected_from_a_stored_attempt() {
        val tag = PhoneMcpToken.fingerprint(token)
        val record = PhoneSetup.observedHandshake(
            PhoneSetup.Record(requestedAt = now, tokenTag = tag),
            now + 20_000L,
            tag,
        )
        val drawn = PhoneToolsSheet.drawn(status(), record, tag, now + 21_000L, adding())
        assertEquals("the phone's handshake wins without another poll step", PhoneBootstrap.Step.CONNECTED, drawn.bootstrap.step)
    }
}
