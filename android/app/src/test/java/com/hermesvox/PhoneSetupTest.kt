package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #421 step 5: the setup turn and the state machine behind it.
 *
 * The three load-bearing properties:
 *  * the prompt carries the real address, names the server and tool, says what the phone can and
 *    cannot do, and is idempotent **by content** — running setup twice describes the same single
 *    entry;
 *  * the prompt carries **this phone's token**, because `hermes mcp add` writes it into the agent's
 *    own configuration, and asks for the restart job's id in one parseable line;
 *  * **Connected** is reachable only from an observed MCP handshake, never from the agent's reply.
 *    The failure states each remain distinct so the sheet can name a next step.
 */
class PhoneSetupTest {

    private val host = "100.101.102.103"
    private val token = "0123456789abcdef0123456789abcdef"
    /** The key this phone sends on its own turns — the credential the agent already holds. */
    private val connectionKey = "fedcba9876543210fedcba9876543210"
    private val now = 1_700_000_000_000L

    private fun record() = PhoneSetup.Record()

    private fun requested(): PhoneSetup.Record =
        PhoneSetup.begin(record(), now, PhoneMcpToken.fingerprint(token))

    @Test fun the_prompt_carries_the_real_address_and_the_tool_name() {
        val p = PhoneSetup.prompt(host, token)
        assertTrue(p, p.contains("http://100.101.102.103:8765/mcp"))
        assertTrue(p, p.contains("Server name: vox"))
        assertTrue(p, p.contains("mcp__vox__phone"))
        assertTrue(
            "the prompt says, in prose, that the phone offers one tool and needs no filter",
            p.contains("exactly one tool"),
        )
    }

    /**
     * The token is the point of the one-button flow: `hermes mcp add` needs it to write the server
     * entry. It appears in the instruction and nowhere else in this turn's contract; the agent is
     * told not to echo it.
     */
    @Test fun the_prompt_carries_the_token_the_agent_needs() {
        val p = PhoneSetup.prompt(host, token)
        assertTrue("the marked token line", p.contains("Token: $token"))
        assertFalse("no redaction placeholder: the agent would try to use it", p.contains("***"))
        assertFalse("not the connection's key", p.contains(connectionKey))
        assertFalse("and no bearer value of that key", p.contains("Bearer $connectionKey"))
        assertTrue("the agent is told to keep it out of its reply", p.contains("Never paste the token into your reply"))
        assertFalse(
            "a prompt built without a token shows the documented placeholder, never a made-up secret",
            PhoneSetup.prompt(host).contains("Token: $token"),
        )
    }

    /** The documented CLI is the route, and the token is what its auth prompt gets. */
    @Test fun the_prompt_uses_hermes_mcp_add_with_the_phones_token() {
        val p = PhoneSetup.prompt(host, token)
        assertTrue("the documented CLI, with the real URL", p.contains("hermes mcp add vox --url \"http://100.101.102.103:8765/mcp\""))
        assertTrue("header auth is named", p.contains("--auth header"))
        assertTrue("the short connect timeout is named", p.contains("--connect-timeout 5"))
        assertTrue("the token is pasted at the prompt", p.contains("paste exactly the token above"))
        assertTrue("a failed probe is expected, not a reason to skip the entry", p.contains("keep it enabled"))
    }

    /**
     * The equivalent config entry is still named, with the phone's token — no invented key, no
     * undocumented per-server key. `mcp_servers` and `Authorization` are the facts the agent can
     * check, and the token is the credential this server actually accepts.
     */
    @Test fun the_prompt_names_the_equivalent_entry_without_inventing_a_key() {
        val p = PhoneSetup.prompt(host, token)
        assertTrue("the block the agent may edit is named", p.contains("mcp_servers"))
        assertTrue("the bearer rides the header", p.contains("Authorization: \"Bearer $token\""))
        assertFalse("no invented per-server tool filter", p.contains("tools.include"))
        assertFalse("`tools:` is not a per-server key", p.contains("    tools:"))
        assertFalse("`trust:` is implemented but not documented; do not teach it here", p.contains("trust:"))
        assertFalse("this is a remote HTTP server: no stdio command", p.contains("command:"))
        assertFalse("an undeclared key is ignored, so never tell the agent to write it", p.contains("tool_timeout"))
    }

    @Test fun the_prompt_says_what_the_phone_can_and_cannot_do() {
        val p = PhoneSetup.prompt(host, token)
        for (can in listOf("timers", "alarms", "Do Not Disturb", "media volume", "note", "undo")) {
            assertTrue("the prompt must name $can", p.contains(can))
        }
        for (cannot in listOf("read messages", "place calls", "send email", "delete")) {
            assertTrue("the prompt must name the limit: $cannot", p.contains(cannot))
        }
    }

    @Test fun the_prompt_is_idempotent_by_content() {
        val p = PhoneSetup.prompt(host, token)
        assertTrue("an existing entry is updated in place", p.contains("update that entry in place"))
        assertTrue("and never duplicated", p.contains("Do not add a second `vox` entry"))
        assertTrue("the duplicate rule is explicit", p.contains("do not duplicate the block"))
        assertTrue("the job is reused rather than duplicated", p.contains("reuse it instead of creating a second one"))
        assertEquals("the same facts must produce the same turn", p, PhoneSetup.prompt(host, token))
        assertNotEquals("a different address is a different turn", p, PhoneSetup.prompt("100.82.156.1", token))
    }

    /**
     * The phone's own token is still the escape hatch for a host that is not the one this phone
     * talks to, and the manual block is where it lives. The setup turn carries it now for the
     * agent's `hermes mcp add`; the fallback remains the same credential.
     */
    @Test fun the_manual_fallback_still_carries_the_real_token() {
        val block = PhoneMcpConfig.snippet(host, token)
        assertTrue(block, block.contains("Authorization: \"Bearer $token\""))
        assertTrue("and the documented timeout key", block.contains("timeout: 30"))
        assertFalse("not the invented one", block.contains("tool_timeout"))
    }

    /**
     * The restart is arranged by the agent and triggered by the app. The prompt names the exact
     * command, a schedule that never fires, the paused state, and the one marked line the app can
     * parse; it explicitly forbids the agent from restarting the gateway itself.
     */
    @Test fun the_prompt_asks_for_a_one_shot_restart_job_and_a_parseable_job_id() {
        val p = PhoneSetup.prompt(host, token)
        assertTrue("the exact host command", p.contains("systemctl --user restart hermes-gateway"))
        assertTrue("a schedule that never fires", p.contains("hermes cron create \"2099-01-01T00:00:00\""))
        assertTrue("no agent runs for the restart", p.contains("--no-agent"))
        assertTrue("disabled from firing", p.contains("--paused"))
        assertTrue("one shot", p.contains("--repeat 1"))
        assertTrue("runnable on demand", p.contains("runnable on demand"))
        assertTrue("the app triggers it, the agent does not", p.contains("Do not run it now"))
        assertTrue("the refused route is named and forbidden", p.contains("do not call `hermes gateway restart`"))
        assertTrue("the app's trigger is described", p.contains("POST /api/jobs/{job_id}/run"))
        assertTrue("the marked id line", p.contains("${PhoneBootstrap.JOB_ID_PREFIX} <job id>"))
        assertTrue("the agent reports what it changed", p.contains("verify that a server named `vox` appears"))
        assertTrue("and replies with the change", p.contains("Then reply with"))
    }

    @Test fun an_ipv6_tailnet_address_is_bracketed_in_the_url() {
        val p = PhoneSetup.prompt("fd7a:115c:a1e0::42")
        assertTrue(p, p.contains("http://[fd7a:115c:a1e0::42]:8765/mcp"))
    }

    @Test fun a_sent_turn_waits_and_does_not_claim_connected() {
        val r = requested()
        assertEquals(PhoneSetup.State.WAITING, PhoneSetup.state(r, now + 1_000L, PhoneMcpToken.fingerprint(token)))
    }

    @Test fun the_handshake_the_phone_saw_is_what_connects_it() {
        val tag = PhoneMcpToken.fingerprint(token)
        val asked = requested()
        val connected = PhoneSetup.observedHandshake(asked, now + 2_000L, tag)
        assertEquals(PhoneSetup.State.CONNECTED, PhoneSetup.state(connected, now + 3_000L, tag))
        assertTrue(connected.reply == null || !connected.reply.contains("connected"))
    }

    @Test fun an_agent_reply_alone_never_connects_the_phone() {
        val tag = PhoneMcpToken.fingerprint(token)
        val answered = PhoneSetup.replied(requested(), "Done — I added vox and it is present in my MCP config.")
        assertEquals(
            "only the phone's own handshake may say Connected",
            PhoneSetup.State.WAITING,
            PhoneSetup.state(answered, now + 2_000L, tag),
        )
    }

    @Test fun a_handshake_made_with_an_old_token_stops_counting_after_rotation() {
        val oldTag = PhoneMcpToken.fingerprint(token)
        val newTag = PhoneMcpToken.fingerprint("f".repeat(32))
        val connected = PhoneSetup.observedHandshake(requested(), now + 2_000L, oldTag)
        assertEquals(PhoneSetup.State.CONNECTED, PhoneSetup.state(connected, now + 3_000L, oldTag))
        assertEquals(
            "the same stored handshake is not evidence for a rotated token",
            PhoneSetup.State.IDLE,
            PhoneSetup.state(PhoneSetup.rotated(connected), now + 3_000L, newTag),
        )
    }

    @Test fun rotation_clears_the_reply_and_the_proof() {
        val tag = PhoneMcpToken.fingerprint(token)
        val r = PhoneSetup.rotated(
            PhoneSetup.observedHandshake(
                PhoneSetup.replied(requested(), "done"),
                now + 2_000L,
                tag,
            )
        )
        assertTrue(r.reply == null)
        assertEquals(0L, r.requestedAt)
        assertEquals(0L, r.handshakeAt)
        assertEquals(PhoneSetup.State.IDLE, PhoneSetup.state(r, now + 3_000L, "another"))
    }

    @Test fun an_agent_that_cannot_edit_its_config_is_a_named_failure() {
        val tag = PhoneMcpToken.fingerprint(token)
        val refused = PhoneSetup.replied(
            requested(),
            "I can't edit my own config: ~/.hermes/config.yaml is read-only for this process.",
        )
        assertEquals(PhoneSetup.State.REFUSED, PhoneSetup.state(refused, now + 2_000L, tag))
        assertTrue(refused.refused)
        assertTrue(PhoneSetup.looksRefused("I do not have permission to modify that file."))
        assertTrue(PhoneSetup.looksRefused("The file is read-only."))
        assertFalse(PhoneSetup.looksRefused("Done — added vox, no restart needed."))
    }

    @Test fun no_answer_is_an_unreachable_agent_not_a_success() {
        val tag = PhoneMcpToken.fingerprint(token)
        val empty = PhoneSetup.replied(requested(), "   ")
        assertEquals(PhoneSetup.State.UNREACHABLE, PhoneSetup.state(empty, now + 2_000L, tag))
        assertTrue(empty.failure.orEmpty().contains("did not answer"))
        val thrown = PhoneSetup.unreachable(requested())
        assertEquals(PhoneSetup.State.UNREACHABLE, PhoneSetup.state(thrown, now + 2_000L, tag))
        assertTrue(thrown.failure.orEmpty().isNotBlank())
    }

    @Test fun nothing_connected_within_the_window_is_a_timeout_with_a_retry() {
        val tag = PhoneMcpToken.fingerprint(token)
        val asked = PhoneSetup.replied(requested(), "Done — I added vox.")
        assertEquals(PhoneSetup.State.WAITING, PhoneSetup.state(asked, now + PhoneSetup.TIMEOUT_MS - 1L, tag))
        assertEquals(PhoneSetup.State.TIMED_OUT, PhoneSetup.state(asked, now + PhoneSetup.TIMEOUT_MS, tag))
    }

    @Test fun a_later_handshake_wins_over_an_earlier_timeout() {
        val tag = PhoneMcpToken.fingerprint(token)
        val timedOut = PhoneSetup.replied(requested(), "Done.")
        assertEquals(PhoneSetup.State.TIMED_OUT, PhoneSetup.state(timedOut, now + PhoneSetup.TIMEOUT_MS + 1L, tag))
        val late = PhoneSetup.observedHandshake(timedOut, now + PhoneSetup.TIMEOUT_MS + 2L, tag)
        assertEquals(PhoneSetup.State.CONNECTED, PhoneSetup.state(late, now + PhoneSetup.TIMEOUT_MS + 3L, tag))
    }

    @Test fun the_view_names_every_state_and_every_next_step() {
        for (state in PhoneSetup.State.values()) {
            assertTrue("headline for $state", PhoneToolsView.setupHeadline(state).isNotBlank())
            assertTrue("next step for $state", PhoneToolsView.setupNext(state).isNotBlank())
        }
        assertEquals(PhoneToolsView.CONNECTED_HEADING, PhoneToolsView.setupHeadline(PhoneSetup.State.CONNECTED))
        assertEquals(PhoneToolsView.WAITING_HEADING, PhoneToolsView.setupHeadline(PhoneSetup.State.WAITING))
        assertTrue(PhoneToolsView.setupNext(PhoneSetup.State.TIMED_OUT).contains("rotate"))
    }

    // ---- the three states at the top of the sheet: not set up, asked, connected ----

    /**
     * The bug this lane exists for: the set was on, the agent was asked about the phone, and it
     * truthfully said it had no such ability. Nothing was broken — the phone's listener was up and
     * its credential minted — but the entry in the *agent's* configuration is a separate, one-time
     * step, and the sheet was too quiet about it. So the not-set-up state is the loud one, and it
     * says what it means.
     */
    @Test fun the_not_set_up_state_is_loud_and_says_what_it_means() {
        val block = PhoneToolsView.setupBlock(PhoneSetup.State.IDLE, PhoneSetup.prompt(host))
        assertEquals(PhoneToolsView.NOT_SET_UP_HEADING, block.heading)
        assertEquals(
            "Your agent does not know about this phone yet",
            block.heading,
        )
        assertEquals(PhoneToolsView.NOT_SET_UP_MEANING, block.meaning)
        assertTrue("the agent cannot see the phone at all", block.meaning.contains("cannot see this phone at all"))
        assertTrue(
            "and an answer about the phone is honest but unhelpful",
            block.meaning.contains("honest but unhelpful"),
        )
    }

    /**
     * The prompt is in that state, with its copy action, and the restart follows it — not a
     * footnote below the fold. The prompt is passed through untouched: it is a contract with the
     * agent, not copy to improve.
     */
    @Test fun the_prompt_is_offered_with_the_restart_sentence_after_it() {
        val prompt = PhoneSetup.prompt(host)
        val block = PhoneToolsView.setupBlock(PhoneSetup.State.IDLE, prompt)
        assertEquals("the setup turn itself, byte for byte", prompt, block.prompt)
        assertEquals(PhoneToolsView.SETUP_RESTART, block.restart)
        assertTrue("who writes the entry", block.restart.orEmpty().contains("writes the entry itself"))
        assertTrue(
            "and when it takes effect",
            block.restart.orEmpty().contains("takes effect when your gateway restarts"),
        )
    }

    /** Asked, waiting for the agent: the prompt is with the agent, no handshake yet, restart named. */
    @Test fun the_waiting_state_names_the_restart_and_never_says_connected() {
        val block = PhoneToolsView.setupBlock(PhoneSetup.State.WAITING, PhoneSetup.prompt(host))
        assertEquals(PhoneToolsView.WAITING_HEADING, block.heading)
        assertEquals(PhoneToolsView.WAITING_MEANING, block.meaning)
        assertTrue("it says a handshake has not arrived", block.meaning.contains("no handshake has arrived"))
        assertTrue(
            "and that the gateway may need restarting",
            block.meaning.contains("your gateway may need restarting"),
        )
        assertFalse(
            "waiting is not connected",
            (block.heading + " " + block.meaning).contains("connected", ignoreCase = true),
        )
    }

    /**
     * Only the handshake's state may use the word. The other five states say their own thing —
     * and "not connected" is not a way of saying connected.
     */
    @Test fun connected_is_the_only_state_that_says_connected() {
        for (state in PhoneSetup.State.values()) {
            val block = PhoneToolsView.setupBlock(state, PhoneSetup.prompt(host), "a named cause")
            val copy = "${PhoneToolsView.setupHeadline(state, "a named cause")} ${block.meaning}"
            if (state == PhoneSetup.State.CONNECTED) {
                assertEquals(PhoneToolsView.CONNECTED_HEADING, block.heading)
                assertNull("nothing to hand over once the handshake is real", block.prompt)
                assertNull("and no restart to name", block.restart)
            } else {
                assertFalse("$state must not use the word: $copy", copy.contains("connected", ignoreCase = true))
            }
        }
    }

    /** Three states, six lines, no two the same: the owner can tell where he is at a glance. */
    @Test fun the_three_setup_states_never_share_wording() {
        val lines = listOf(
            PhoneSetup.State.IDLE,
            PhoneSetup.State.WAITING,
            PhoneSetup.State.CONNECTED,
        ).flatMap { state ->
            val block = PhoneToolsView.setupBlock(state, PhoneSetup.prompt(host))
            listOf("${state.name} heading" to block.heading, "${state.name} meaning" to block.meaning)
        }
        val texts = lines.map { it.second }
        assertEquals("each of the six lines is its own line: $lines", texts.toSet().size, texts.size)
        for ((label, line) in lines) assertFalse("$label must not be blank", line.isBlank())
    }

    /**
     * The sheet's state is the record's state: a set that is on with no handshake draws the loud
     * not-set-up block, and nothing it draws says connected.
     */
    @Test fun a_sheet_with_the_set_on_and_no_handshake_shows_the_not_set_up_state() {
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
        val drawn = PhoneToolsSheet.drawn(status, PhoneSetup.Record(), PhoneMcpToken.fingerprint(token), now)
        assertEquals("the set is on and nothing has connected", PhoneSetup.State.IDLE, drawn.state)
        val block = PhoneToolsView.setupBlock(drawn.state, PhoneSetup.prompt(host), drawn.record.failure)
        assertEquals(PhoneToolsView.NOT_SET_UP_HEADING, block.heading)
        assertNotNull("the prompt is right there", block.prompt)
        assertFalse(
            "a sheet that has not connected never says connected",
            (block.heading + " " + block.meaning).contains("connected", ignoreCase = true),
        )
    }

    /** A recorded handshake renders connected, and the restart sentence is gone with the wait. */
    @Test fun a_recorded_handshake_renders_connected() {
        val tag = PhoneMcpToken.fingerprint(token)
        val record = PhoneSetup.observedHandshake(PhoneSetup.Record(), now, tag)
        val drawn = PhoneToolsSheet.drawn(
            PhoneMcp.Status(
                tailnet = host,
                listening = true,
                url = "http://$host:8765/mcp",
                served = 1,
                lastRequest = "resources/list",
                lastRequestAt = now,
                token = PhoneMcp.TokenState.STORED,
                dndGranted = true,
                volumeGranted = true,
            ),
            record,
            tag,
            now + 1_000L,
        )
        assertEquals(PhoneSetup.State.CONNECTED, drawn.state)
        assertEquals(PhoneToolsView.CONNECTED_HEADING, PhoneToolsView.setupHeadline(drawn.state, drawn.record.failure))
        assertNull(PhoneToolsView.setupBlock(drawn.state, PhoneSetup.prompt(host)).restart)
    }

    /**
     * The setup turn is a contract with the agent (#452), so it is pinned byte for byte in
     * `src/test/resources/phonesetup/setup-prompt.golden.txt`. The sheet shows, and the copy
     * action copies, exactly [PhoneSetup.prompt] — improving its wording is a change to what the
     * agent is told, and has to be made on purpose.
     */
    @Test fun the_setup_prompt_is_byte_for_byte_the_agent_contract() {
        val golden = java.io.File("src/test/resources/phonesetup/setup-prompt.golden.txt")
        assertTrue("the golden prompt must be checked in at ${golden.path}", golden.isFile)
        val expected = golden.readText()
        assertEquals(
            "the setup turn changed: the agent is told something different",
            expected,
            PhoneSetup.prompt(host, token) + "\n",
        )
        assertEquals(
            "what the sheet offers is the prompt the app produces, unchanged",
            expected.removeSuffix("\n"),
            PhoneToolsView.setupBlock(PhoneSetup.State.IDLE, PhoneSetup.prompt(host, token)).prompt,
        )
    }

    @Test fun the_three_settings_row_words_are_distinct_too() {
        assertEquals("not set up", PhoneToolsView.rowValue(PhoneSetup.State.IDLE))
        assertEquals("waiting for your agent", PhoneToolsView.rowValue(PhoneSetup.State.WAITING))
        assertEquals("connected", PhoneToolsView.rowValue(PhoneSetup.State.CONNECTED))
        assertEquals(
            "a failure offers the retry it has",
            "retry",
            PhoneToolsView.rowValue(PhoneSetup.State.UNREACHABLE),
        )
    }

    @Test fun every_status_row_says_why_it_is_off_when_phone_tools_is_off() {
        for (line in listOf(
            PhoneToolsView.reachability(host, allowed = false),
            PhoneToolsView.server(host, listening = true, url = "http://$host/mcp", allowed = false),
            PhoneToolsView.token(PhoneMcp.TokenState.STORED, allowed = false),
            PhoneToolsView.setupHeadline(PhoneSetup.State.IDLE, allowed = false),
            PhoneToolsView.setupNext(PhoneSetup.State.IDLE, allowed = false),
        )) {
            assertTrue(line, line.contains("Off") || line.contains("Select Phone tools"))
            assertFalse(line, line.contains("Not set up"))
            assertFalse(line, line.contains("Connected"))
        }
    }

    @Test fun the_token_fingerprint_is_stable_and_not_the_token() {
        val tag = PhoneMcpToken.fingerprint(token)
        assertEquals(tag, PhoneMcpToken.fingerprint(token))
        assertNotEquals(token, tag)
        assertFalse(tag, tag.contains(token.take(8)))
        assertEquals("", PhoneMcpToken.fingerprint(""))
    }
}
