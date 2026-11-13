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
 *    own configuration, says the listener is open for the setup, and asks for no restart;
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

    /**
     * The command that works from a tool call is `hermes config set`. The turn names the exact
     * nested key paths, in order, with this phone's URL and bearer, so nothing has to be improvised.
     */
    @Test fun the_prompt_writes_the_entry_with_config_set_in_order() {
        val p = PhoneSetup.prompt(host, token)
        val url = "hermes config set mcp_servers.vox.url http://100.101.102.103:8765/mcp"
        val auth = "hermes config set mcp_servers.vox.headers.Authorization \"Bearer $token\""
        val enabled = "hermes config set mcp_servers.vox.enabled true"
        assertTrue("the real URL, at the documented key path", p.contains(url))
        assertTrue("the bearer, at the documented header key path", p.contains(auth))
        assertTrue("the entry is enabled", p.contains(enabled))
        assertTrue(
            "the order is URL, header, enabled",
            p.indexOf(url) < p.indexOf(auth) && p.indexOf(auth) < p.indexOf(enabled),
        )
        assertTrue("why these commands", p.contains("These are the commands that work from a tool call"))
        assertTrue(
            "the listener is open when the commands run",
            p.contains("listener is open for as long as this attempt runs"),
        )
    }

    /**
     * `hermes mcp add` stays, but only as an alternative: it prompts, and a tool call has no stdin.
     * The turn may not present it as the route that works, because that is what forced the agent to
     * improvise on the device.
     */
    @Test fun the_prompt_keeps_hermes_mcp_add_only_as_an_alternative_with_its_limitation() {
        val p = PhoneSetup.prompt(host, token)
        assertTrue(p, p.contains("`hermes mcp add` is an alternative only"))
        assertTrue("it asks questions", p.contains("asks two questions interactively"))
        assertTrue("a tool call has no stdin", p.contains("a tool call has no stdin"))
        assertTrue("so it may fail to save", p.contains("may fail to save"))
        assertFalse("not presented as the primary command", p.contains("hermes mcp add vox --url"))
    }

    /**
     * The equivalent config entry is still named, with the phone's token — no invented key, no
     * undocumented per-server key. `mcp_servers`, `Authorization` and `lazy` are the facts the agent
     * can check, and the token is the credential this server actually accepts.
     */
    @Test fun the_prompt_names_the_equivalent_entry_without_inventing_a_key() {
        val p = PhoneSetup.prompt(host, token)
        assertTrue("the block the agent may edit is named", p.contains("mcp_servers"))
        assertTrue("the bearer rides the header", p.contains("Authorization: \"Bearer $token\""))
        assertTrue("the documented lazy key is in the block", p.contains("lazy: true"))
        assertFalse("no invented per-server tool filter", p.contains("tools.include"))
        assertFalse("`tools:` is not a per-server key", p.contains("    tools:"))
        assertFalse("`trust:` is implemented but not documented; do not teach it here", p.contains("trust:"))
        assertFalse("this is a remote HTTP server: no stdio command", p.contains("command:"))
        assertFalse("an undeclared key is ignored, so never tell the agent to write it", p.contains("tool_timeout"))
    }

    /**
     * The tool has to stay in the agent's hands while the phone is idle. `lazy` is the documented
     * per-server key that does it: the schema cache registers the tool at startup and the connection
     * is made on first use. The prompt names the key, the exact command, and the reason the agent
     * can repeat to the owner.
     */
    @Test fun the_prompt_sets_the_documented_lazy_key_and_says_why() {
        val p = PhoneSetup.prompt(host, token)
        assertTrue(
            "the documented command, with this server's key path",
            p.contains("hermes config set mcp_servers.vox.lazy true"),
        )
        assertTrue(
            "and the reason, in the owner's words",
            p.contains(
                "The tool is registered from its schema, so it is always available to you; " +
                    "the connection is made when it is used",
            ),
        )
        assertTrue(
            "availability must not be tied to a recent connection",
            p.contains("does not need to have been reached recently"),
        )
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
        assertEquals("the same facts must produce the same turn", p, PhoneSetup.prompt(host, token))
        assertNotEquals("a different address is a different turn", p, PhoneSetup.prompt("100.82.156.1", token))
    }

    /**
     * The phone's own token is still the escape hatch for a host that is not the one this phone
     * talks to, and the manual block is where it lives. The setup turn carries it now for the
     * agent's `hermes config set`; the fallback remains the same credential.
     */
    @Test fun the_manual_fallback_still_carries_the_real_token() {
        val block = PhoneMcpConfig.snippet(host, token)
        assertTrue(block, block.contains("Authorization: \"Bearer $token\""))
        assertTrue("and the documented timeout key", block.contains("timeout: 30"))
        assertTrue("and the documented lazy key", block.contains("lazy: true"))
        assertFalse("not the invented one", block.contains("tool_timeout"))
    }

    /**
     * There is no restart. A running gateway reconciles its MCP configuration on its own, so the
     * instruction asks only for the write and explicitly forbids the restart the old flow invented.
     * The one thing it states is the listener window the press opened — that is what makes the
     * commands reach this phone from an idle start.
     */
    @Test fun the_prompt_asks_for_no_restart_and_states_the_listener_window() {
        val p = PhoneSetup.prompt(host, token)
        assertTrue("the listener's life is stated, without a clock", p.contains("open for as long as this attempt runs"))
        assertTrue("the gateway picks the entry up by itself", p.contains("picks the new server up"))
        assertTrue("no restart is wanted", p.contains("No restart is needed and none is wanted"))
        assertTrue("the agent is told not to create a job", p.contains("do not create or run a job"))
        assertTrue("the agent verifies the saved entry", p.contains("verify it with `hermes mcp list`"))
        assertTrue("and names the URL it saved", p.contains("report the URL you saved"))
        assertFalse("no job id is asked for", p.contains("JOB_ID"))
        assertFalse("no host command is asked for", p.contains("systemctl"))
        assertFalse("no cron job is asked for", p.contains("hermes cron create"))
        assertFalse("the refused route is not even mentioned as a route to try", p.contains("POST /api/jobs"))
    }

    /**
     * The instruction has to leave a named result, not just a saved file. `hermes mcp list` (or
     * `hermes mcp test`) is the verification, and the reply must name the URL it saved.
     */
    @Test fun the_prompt_requires_verification_and_a_reply_naming_the_saved_url() {
        val p = PhoneSetup.prompt(host, token)
        assertTrue("the list command", p.contains("`hermes mcp list`"))
        assertTrue("or the per-server test", p.contains("`hermes mcp test vox`"))
        assertTrue("the reply names the URL", p.contains("report the URL you saved"))
        assertTrue(
            "a reply without the URL is not a completed setup",
            p.contains("A reply that does not name the saved URL is not a completed setup"),
        )
    }

    /**
     * A handshake proves the phone was reached, not that the entry was saved. The prompt says both,
     * so the agent cannot declare the setup complete on the strength of the connection alone.
     */
    @Test fun the_prompt_keeps_the_limit_of_a_handshake() {
        val p = PhoneSetup.prompt(host, token)
        assertTrue(
            p.contains("A handshake proves this phone was reached; it does not prove the entry was saved"),
        )
        assertTrue("only the phone decides connected", p.contains("Do not claim the phone is connected"))
        assertFalse(p.contains("save the entry anyway"))
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

    /**
     * There is no elapsed-time verdict on the record. A setup that has been asked and has no
     * handshake is *waiting* an hour later as much as a second later; only a named failure or the
     * phone's own `initialize` changes the state. A clock here is what cut a working attempt short.
     */
    @Test fun waiting_has_no_deadline_and_a_late_handshake_still_connects() {
        val tag = PhoneMcpToken.fingerprint(token)
        val asked = PhoneSetup.replied(requested(), "Done — I added vox.")
        val hours = now + 6 * 60 * 60_000L
        assertEquals(PhoneSetup.State.WAITING, PhoneSetup.state(asked, hours, tag))
        val late = PhoneSetup.observedHandshake(asked, hours + 1L, tag)
        assertEquals(PhoneSetup.State.CONNECTED, PhoneSetup.state(late, hours + 2L, tag))
    }

    @Test fun the_view_names_every_state_and_every_next_step() {
        for (state in PhoneSetup.State.values()) {
            assertTrue("headline for $state", PhoneToolsView.setupHeadline(state).isNotBlank())
            assertTrue("next step for $state", PhoneToolsView.setupNext(state).isNotBlank())
        }
        assertEquals(PhoneToolsView.CONNECTED_HEADING, PhoneToolsView.setupHeadline(PhoneSetup.State.CONNECTED))
        assertEquals(PhoneToolsView.WAITING_HEADING, PhoneToolsView.setupHeadline(PhoneSetup.State.WAITING))
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
     * The prompt is in that state, with its copy action, and the note follows it — not a footnote
     * below the fold. The prompt is passed through untouched: it is a contract with the agent, not
     * copy to improve.
     */
    @Test fun the_prompt_is_offered_with_the_note_after_it() {
        val prompt = PhoneSetup.prompt(host)
        val block = PhoneToolsView.setupBlock(PhoneSetup.State.IDLE, prompt)
        assertEquals("the setup turn itself, byte for byte", prompt, block.prompt)
        assertEquals(PhoneToolsView.SETUP_NOTE, block.note)
        assertTrue("who writes the entry", block.note.orEmpty().contains("writes the entry itself"))
        assertTrue("that the listener is open for it", block.note.orEmpty().contains("listener is open"))
        assertTrue("and that nothing restarts", block.note.orEmpty().contains("Nothing on your host restarts"))
    }

    /** Asked, waiting for the agent: the prompt is with the agent, no handshake yet, no restart. */
    @Test fun the_waiting_state_names_no_restart_and_never_says_connected() {
        val block = PhoneToolsView.setupBlock(PhoneSetup.State.WAITING, PhoneSetup.prompt(host))
        assertEquals(PhoneToolsView.WAITING_HEADING, block.heading)
        assertEquals(PhoneToolsView.WAITING_MEANING, block.meaning)
        assertTrue("it says a handshake has not arrived", block.meaning.contains("no handshake has arrived"))
        assertTrue(
            "and that there is nothing to restart",
            block.meaning.contains("nothing to restart"),
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
                assertNull("and nothing to hand over", block.note)
            } else {
                assertFalse("$state must not use the word: $copy", copy.contains("connected", ignoreCase = true))
            }
        }
    }

    /**
     * Connected states both the proof and its limit: the handshake proves the agent reached this
     * phone, and the agent's own reply is where the saved entry is named. The same sentence is on
     * both Connected screens, so they cannot drift.
     */
    @Test fun the_connected_copy_states_the_proof_and_its_limit() {
        val meaning = PhoneToolsView.setupNext(PhoneSetup.State.CONNECTED)
        assertTrue(meaning, meaning.contains("reached this phone"))
        assertTrue("the handshake is the proof", meaning.contains("completed the MCP handshake"))
        assertTrue("and its limit", meaning.contains("does not prove the entry was saved"))
        assertTrue(
            "the reply is where the saved entry is named",
            meaning.contains("reply") && meaning.contains("saved URL"),
        )
        assertEquals(
            "both Connected screens say the same thing",
            meaning,
            PhoneBootstrap.CONNECTED_MEANING,
        )
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

    /** A recorded handshake renders connected, and the note is gone with the wait. */
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
        assertNull(PhoneToolsView.setupBlock(drawn.state, PhoneSetup.prompt(host)).note)
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
        assertEquals("working", PhoneToolsView.rowValue(PhoneSetup.State.WAITING))
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

    /**
     * The lazy key is not only in the prompt. Both documents that describe the entry name it, and
     * no surface may promise a connection that is kept warm or comes back within seconds: the tool
     * is registered; the phone is reached on use, when a call is live.
     */
    @Test fun the_documents_name_the_lazy_entry_and_promise_nothing_warmer() {
        val paths = listOf(
            "docs/MCP-PHONE.md",
            "docs/MCP-SURFACE.md",
            "skills/hermes-vox-app/SKILL.md",
        )
        for (path in paths) {
            val text = repoFile(path).readText()
            assertFalse("$path must not promise a kept-warm connection", text.contains("kept warm"))
            assertFalse("$path must not promise a reconnect in seconds", text.contains("reconnects within seconds"))
            assertFalse("$path must not promise a reconnect in seconds", text.contains("reconnect within seconds"))
        }
        for (path in paths.take(2)) {
            assertTrue("$path must name the lazy key", repoFile(path).readText().contains("lazy"))
        }
    }

    private fun repoFile(path: String): java.io.File {
        var d: java.io.File? = java.io.File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            val f = java.io.File(d, path)
            if (f.isFile) return f
            d = d.parentFile
        }
        error("$path not found above ${System.getProperty("user.dir")}")
    }

    @Test fun the_token_fingerprint_is_stable_and_not_the_token() {
        val tag = PhoneMcpToken.fingerprint(token)
        assertEquals(tag, PhoneMcpToken.fingerprint(token))
        assertNotEquals(token, tag)
        assertFalse(tag, tag.contains(token.take(8)))
        assertEquals("", PhoneMcpToken.fingerprint(""))
    }
}
