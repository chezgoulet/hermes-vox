package com.hermesvox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #421 steps 3–4's wiring, pinned at source level the way [LocalActionWiringTest] and
 * [GlueClaimGuardTest] pin theirs: the parts that matter are Android-bound (a foreground service's
 * lifetime, a keystore, a tailnet interface), so what can be checked off-device is the ORDER, the
 * OWNERSHIP, and the one address.
 *
 * Four properties, each a real way to get this wrong:
 *  1. the MCP server runs on the **same** [LocalActions] the presence context reports from — two
 *     registries would let the phone contradict itself (#421's duplicate-authority risk);
 *  2. it is up exactly while the call is (started by `start`, closed by `stop`);
 *  3. it binds only the address [Tailnet] picked;
 *  4. the token is stored encrypted, never in plain text and never in a log.
 */
class PhoneMcpWiringTest {

    private val vc = File("src/main/java/com/hermesvox/VoiceController.kt").readText()
    private val mcp = File("src/main/java/com/hermesvox/PhoneMcp.kt").readText()
    private val server = File("src/main/java/com/hermesvox/PhoneMcpServer.kt").readText()
    private val presence = File("src/main/java/com/hermesvox/PresenceContext.kt").readText()

    @Test fun the_tool_and_the_context_share_one_registry() {
        assertTrue(
            "the server must be handed the controller's own LocalActions",
            vc.contains("PhoneMcp.start(context) { args -> localActions.phone(args) }"),
        )
        assertTrue(
            "the presence context must report the same instance",
            vc.contains("actions = localActions.registry()"),
        )
        assertTrue("one lazy instance, not two", vc.contains("private val localActions: LocalActions by lazy"))
    }

    @Test fun the_server_lives_exactly_as_long_as_the_call() {
        val startAt = vc.indexOf("fun start(l: Listener, enabled: Boolean)")
        val stopAt = vc.indexOf("fun stop()")
        assertTrue(startAt > 0 && stopAt > startAt)
        val startBody = vc.substring(startAt, minOf(vc.length, startAt + 1_200))
        assertTrue("the server starts with the call", startBody.contains("PhoneMcp.start("))
        val stopBody = vc.substring(stopAt, minOf(vc.length, stopAt + 600))
        assertTrue("and is closed by the hangup", stopBody.contains("PhoneMcp.stop()"))
    }

    @Test fun it_binds_the_tailnet_address_and_nothing_else() {
        assertTrue("the bind address comes from Tailnet.pick", mcp.contains("Tailnet.pick(Tailnet.snapshot())"))
        assertTrue("a phone with no tailnet does not listen", mcp.contains("event=phone-mcp unavailable reason=no-tailnet"))
        assertTrue("an any-local bind is refused in the server itself", server.contains("bind.isAnyLocalAddress"))
    }

    @Test fun the_token_is_encrypted_and_never_logged() {
        assertTrue("stored through the keystore", mcp.contains("SecureStore.encrypt(fresh, ALIAS)"))
        assertTrue("read back through the keystore", mcp.contains("SecureStore.decrypt(stored, ALIAS)"))
        assertTrue("no plaintext fallback on disk", mcp.contains("token-store=failed session-only=true"))
        // Every log line about the server names the URL or a reason; the token is never formatted in.
        assertFalse("the token must not appear in a log line", mcp.contains("token=\${"))
        assertFalse("no token in the request log either", server.contains("token=\${"))
    }

    @Test fun the_presence_block_still_carries_what_the_phone_is_doing() {
        assertTrue(presence.contains("local_actions: "))
        assertTrue(
            "the registry the server uses is the one the block formats",
            File("src/main/java/com/hermesvox/PhoneEngine.kt").readText().contains("fun registry("),
        )
    }

    @Test fun the_user_initiated_path_is_untouched_and_still_local() {
        val parse = vc.indexOf("LocalActionGrammar.parse(text)")
        val submit = vc.indexOf("session.voiceTurn(")
        assertTrue("the grammar still runs before the mind is asked", parse > 0 && parse < submit)
        assertTrue("and it still ends the turn locally", vc.contains("if (local != null) { takeLocalAction(text, local, gen); return }"))
    }

    @Test fun the_settings_row_can_show_the_owner_the_config() {
        val settings = File("src/main/java/com/hermesvox/SettingsActivity.kt").readText()
        assertTrue(settings.contains("R.id.row_phone_mcp"))
        assertTrue("the snippet comes from one place", settings.contains("PhoneMcpConfig.snippet("))
    }
}
