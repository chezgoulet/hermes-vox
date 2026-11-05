package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #421 step 5: the setup turn and the state machine behind it.
 *
 * The two load-bearing properties:
 *  * the prompt carries the real address and token, names the server and tool, says what the phone
 *    can and cannot do, and is idempotent **by content** — running setup twice describes the same
 *    single entry;
 *  * **Connected** is reachable only from an observed MCP handshake, never from the agent's reply.
 *    The failure states each remain distinct so the sheet can name a next step.
 */
class PhoneSetupTest {

    private val host = "100.101.102.103"
    private val token = "0123456789abcdef0123456789abcdef"
    private val now = 1_700_000_000_000L

    private fun record() = PhoneSetup.Record()

    private fun requested(): PhoneSetup.Record =
        PhoneSetup.begin(record(), now, PhoneMcpToken.fingerprint(token))

    @Test fun the_prompt_carries_the_real_address_the_token_and_the_tool_name() {
        val p = PhoneSetup.prompt(host, token)
        assertTrue(p, p.contains("http://100.101.102.103:8765/mcp"))
        assertTrue(p, p.contains("Authorization: Bearer $token"))
        assertTrue(p, p.contains("Server name: vox"))
        assertTrue(p, p.contains("mcp__vox__phone"))
        assertTrue("the agent must know to include only phone", p.contains("tools.include"))
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
        assertNotEquals("a different token is a different turn", p, PhoneSetup.prompt(host, "f".repeat(32)))
    }

    @Test fun the_prompt_tells_the_agent_to_verify_and_reload() {
        val p = PhoneSetup.prompt(host, token)
        assertTrue(p, p.contains("verify that a server named `vox` appears"))
        assertTrue("a new server may need a restart", p.contains("reload or restart your gateway"))
        assertTrue("the agent reports what it changed", p.contains("Then reply with"))
    }

    @Test fun an_ipv6_tailnet_address_is_bracketed_in_the_url() {
        val p = PhoneSetup.prompt("fd7a:115c:a1e0::42", token)
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
        assertTrue(PhoneToolsView.setupHeadline(PhoneSetup.State.CONNECTED).contains("Connected"))
        assertTrue(PhoneToolsView.setupHeadline(PhoneSetup.State.WAITING).contains("Waiting"))
        assertTrue(PhoneToolsView.setupNext(PhoneSetup.State.TIMED_OUT).contains("rotate"))
    }

    @Test fun the_token_fingerprint_is_stable_and_not_the_token() {
        val tag = PhoneMcpToken.fingerprint(token)
        assertEquals(tag, PhoneMcpToken.fingerprint(token))
        assertNotEquals(token, tag)
        assertFalse(tag, tag.contains(token.take(8)))
        assertEquals("", PhoneMcpToken.fingerprint(""))
    }
}
