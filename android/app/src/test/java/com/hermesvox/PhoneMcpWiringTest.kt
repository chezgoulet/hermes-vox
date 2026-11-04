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

    @Test fun the_settings_row_opens_the_setup_sheet_not_a_paste_block() {
        val settings = File("src/main/java/com/hermesvox/SettingsActivity.kt").readText()
        assertTrue(settings.contains("R.id.row_phone_mcp"))
        assertTrue("one sheet owns the flow", settings.contains("PhoneToolsSheet(this"))
        assertFalse("the setup path is not a config dialog", settings.contains("setTitle(\"Phone MCP\")"))
    }

    @Test fun setup_is_an_ordinary_turn_composed_by_the_app() {
        val setup = File("src/main/java/com/hermesvox/PhoneSetup.kt").readText()
        val store = File("src/main/java/com/hermesvox/PhoneSetupStore.kt").readText()
        val sheet = File("src/main/java/com/hermesvox/PhoneToolsSheet.kt").readText()
        assertTrue("the app composes the turn", setup.contains("fun prompt(host: String, token: String"))
        assertTrue("it carries the real address and token", setup.contains("Authorization: Bearer \$token"))
        assertTrue("it names the tool the model sees", setup.contains("MODEL_TOOL_NAME"))
        assertTrue("it is idempotent by content", setup.contains("Do not add a second"))
        assertTrue("it is sent as an ordinary stored turn", store.contains("session.turnStored(prompt)"))
        assertTrue("through the live session when there is one", store.contains("live?.let"))
        assertTrue("which MainActivity hands over", File("src/main/java/com/hermesvox/MainActivity.kt").readText().contains("PhoneSetupStore.attach(session)"))
        assertTrue("the prompt is visible in the sheet", sheet.contains("SHOW THE SETUP TURN"))
        assertTrue("manual config is a disclosed fallback", sheet.contains("Manual fallback"))
    }

    @Test fun connected_requires_the_phones_own_handshake() {
        val server = File("src/main/java/com/hermesvox/PhoneMcpServer.kt").readText()
        val mcp = File("src/main/java/com/hermesvox/PhoneMcp.kt").readText()
        val setup = File("src/main/java/com/hermesvox/PhoneSetup.kt").readText()
        assertTrue("the server observes initialize", server.contains("method == \"initialize\""))
        assertTrue("after authentication", server.indexOf("authorized(headers[\"authorization\"])") < server.indexOf("method == \"initialize\""))
        assertTrue("and records it as the only proof", mcp.contains("recordHandshake"))
        assertTrue("the reply is never the proof", setup.contains("handshakeTag == tag"))
    }

    @Test fun permissions_are_shown_in_the_sheet_and_refused_in_the_engine() {
        val sheet = File("src/main/java/com/hermesvox/PhoneToolsSheet.kt").readText()
        val engine = File("src/main/java/com/hermesvox/PhoneEngine.kt").readText()
        assertTrue("the sheet shows DND access", sheet.contains("Do Not Disturb access"))
        assertTrue("and opens the right system screen", sheet.contains("ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS"))
        assertTrue("the engine refuses without the grant", engine.contains("if (!platform.dndGranted())"))
        assertTrue("as a permission reason, not a success", engine.contains("PhoneReason.PERMISSION"))
        assertTrue("the sheet states the listener's lifetime", sheet.contains("only while a call is"))
    }

    @Test fun no_exact_alarm_permission_is_declared_or_needed() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("uses-permission android:name=\"android.permission.SCHEDULE_EXACT_ALARM\""))
        assertFalse(manifest.contains("uses-permission android:name=\"android.permission.USE_EXACT_ALARM\""))
        val actions = File("src/main/java/com/hermesvox/LocalActions.kt").readText()
        assertTrue("the listener form is the reason", actions.contains("OnAlarmListener"))
        assertTrue("and it is explained", actions.contains("does not apply to the listener form"))
    }
}
