package com.hermesvox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #421 steps 3–4's wiring, pinned at source level the way [GlueClaimGuardTest] pins its:
 * the parts that matter are Android-bound (a foreground service's
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
            "the presence context must report the same instance, read-only",
            vc.contains("private val presenceReadout: PhoneReadout by lazy { localActions }") &&
                vc.contains("actions = presenceReadout.registry("),
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

    @Test fun the_phone_tools_set_gates_the_server_the_token_and_the_sheet() {
        val settings = File("src/main/java/com/hermesvox/SettingsActivity.kt").readText()
        val offer = File("src/main/java/com/hermesvox/PhoneToolsOffer.kt").readText()
        val view = File("src/main/java/com/hermesvox/PhoneToolsView.kt").readText()
        assertTrue("start refuses while the set is off",
            mcp.contains("AlphaGate.allows(context, AlphaGate.FeatureSet.PHONE_TOOLS)"))
        assertTrue("the token is not generated while the set is off",
            mcp.contains("event=phone-mcp token=off reason=alpha"))
        assertTrue("rotation is refused too", mcp.contains("action=rotate"))
        assertTrue("the status carries the gate", mcp.contains("allowed = AlphaGate.allows(app, AlphaGate.FeatureSet.PHONE_TOOLS)"))
        assertTrue("the settings row is dimmed and takes no tap",
            settings.contains("gate(R.id.row_phone_mcp, phoneTools)"))
        assertTrue("turning the set off stops a live listener", settings.contains("if (!phoneTools) PhoneMcp.stop()"))
        assertTrue("the first-run offer is gated too",
            offer.contains("AlphaGate.allows(prefs, AlphaGate.FeatureSet.PHONE_TOOLS)"))
        assertTrue("status rows say why they are off", view.contains("const val ALPHA_OFF"))
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
        assertTrue("the app composes the turn", setup.contains("fun prompt(") && setup.contains("host: String"))
        // The one-button flow carries the phone's token, because `hermes mcp add` needs it to
        // write the server entry; the agent is told not to echo it.
        assertTrue("the turn carries the phone's token", setup.contains("Token: \$token"))
        assertTrue("it uses the documented CLI", setup.contains("hermes mcp add"))
        assertTrue("it names the exact restart command", setup.contains("systemctl --user restart hermes-gateway"))
        assertTrue("it asks for a parseable job id", setup.contains("JOB_ID_PREFIX"))
        assertTrue("it names the tool the model sees", setup.contains("MODEL_TOOL_NAME"))
        assertTrue("it is idempotent by content", setup.contains("Do not add a second"))
        assertTrue("it is sent as an ordinary stored turn", store.contains("session.turnStored(prompt)"))
        assertTrue(
            "through the live session only when it is the connection in force",
            store.contains("PhoneSetupSend.plan(liveFor, live != null, active)"),
        )
        assertTrue(
            "which MainActivity hands over with the connection it was built from",
            File("src/main/java/com/hermesvox/MainActivity.kt").readText()
                .contains("PhoneSetupStore.attach(session, PhoneSetupSend.Connection("),
        )
        val promptAt = sheet.indexOf("configView(col, text)")
        val copyAt = sheet.indexOf("COPY SETUP PROMPT")
        val restartAt = sheet.indexOf("restart?.let { body(col, it) }")
        assertTrue("the prompt is on the sheet itself, not behind a disclosure", promptAt > 0)
        assertTrue("its copy action sits with the turn", copyAt > promptAt)
        assertTrue("and the restart sentence follows them", restartAt > copyAt)
        assertTrue("manual config is a disclosed fallback", sheet.contains("Manual fallback"))
    }

    /**
     * #452, the server side. The phone's server takes a second provider — the active connection's
     * API key — read from the same store the turn path resolves it from and read per request, so a
     * key re-saved in Settings is in force immediately. Nothing new is stored: the key the phone
     * already holds for its turns is the key it now accepts.
     */
    @Test fun the_server_accepts_the_key_the_phone_already_sends() {
        val wiring = File("src/main/java/com/hermesvox/PhoneMcp.kt").readText()
        val serverFile = File("src/main/java/com/hermesvox/PhoneMcpServer.kt").readText()
        assertTrue("the second credential is a provider", wiring.contains("keyProvider = { connectionKey(app) }"))
        assertTrue(
            "resolved exactly as the turn path resolves it",
            wiring.contains("GatewayKey.resolve(") && wiring.contains("SecureStore::decrypt"),
        )
        assertTrue("from the profile's existing key slot", wiring.contains("Profiles.K_KEY"))
        assertFalse("no new secret is written for it", wiring.contains("putString(KEY_CONNECTION"))
        assertTrue("read per request, not captured at start", serverFile.contains("private val keyProvider: () -> String"))
    }

    /**
     * #452, the comparison. Both credentials go through [PhoneMcpToken.matches] (constant-time),
     * and both results are computed before the answer: a short-circuit would tell a caller on the
     * tailnet which credential it presented, and how close it was.
     */
    @Test fun both_credentials_are_compared_without_leaking_which_one_was_tried() {
        val serverFile = File("src/main/java/com/hermesvox/PhoneMcpServer.kt").readText()
        assertTrue(serverFile.contains("PhoneMcpToken.matches(presented, tokenProvider())"))
        assertTrue(serverFile.contains("PhoneMcpToken.matches(presented, keyProvider())"))
        assertTrue("the second comparison runs even when the first matched",
            serverFile.contains("val asPhoneToken") && serverFile.contains("val asConnectionKey"))
        assertTrue("and only then is the answer formed", serverFile.contains("return asPhoneToken or asConnectionKey"))
        assertFalse("never == on a secret", serverFile.contains("presented == "))
        assertFalse("never == on a secret", serverFile.contains("== presented"))
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

    @Test fun the_sheet_offers_the_listener_access_now_playing_needs() {
        val sheet = File("src/main/java/com/hermesvox/PhoneToolsSheet.kt").readText()
        val listen = File("src/main/java/com/hermesvox/MediaListen.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue("the sheet shows the access", sheet.contains("Now playing access"))
        assertTrue(
            "the row names what the grant changes",
            sheet.contains("PhoneToolsView.nowPlaying("),
        )
        assertTrue(
            "the owner's own words offer it",
            sheet.contains("LET VOX SEE WHAT IS PLAYING"),
        )
        assertTrue(
            "the button opens the system screen",
            sheet.contains("MediaListen.settingsIntent()"),
        )
        assertTrue(
            "it is offered only while the media group is on",
            sheet.contains("states[\"media\"]"),
        )
        assertTrue("the session list is read through the grant", listen.contains("getActiveSessions"))
        assertTrue(
            "the listener service is what the grant attaches to",
            manifest.contains("android:name=\".MediaListenerService\""),
        )
        assertTrue(
            "and it is not a second capability",
            manifest.contains("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"),
        )
    }

    // ---- #429 step 1: one registry, read live ----

    @Test fun the_schema_the_screen_and_the_engine_read_the_same_registry() {
        val tool = File("src/main/java/com/hermesvox/PhoneTool.kt").readText()
        val grants = File("src/main/java/com/hermesvox/PhoneGrants.kt").readText()
        val sheet = File("src/main/java/com/hermesvox/PhoneToolsSheet.kt").readText()
        val local = File("src/main/java/com/hermesvox/LocalActions.kt").readText()
        val engine = File("src/main/java/com/hermesvox/PhoneEngine.kt").readText()

        assertTrue("the enum is built from the registry", tool.contains("PhoneGrants.advertised(grants)"))
        assertTrue("the tool description names what is on", tool.contains("PhoneGrants.stateOf(grants, it) != PhoneGrants.State.OFF"))
        assertTrue("the engine reads grants at dispatch", engine.contains("PhoneTool.parse(arguments, grants)"))
        assertTrue("the engine is handed the live store", local.contains("PhoneGrantStore.source(app)"))
        assertTrue("the screen writes through the same store", sheet.contains("PhoneGrantStore.set(act, group, state)"))
        assertTrue("the screen groups rows by tier", sheet.contains("PhoneGrants.byTier()"))
        assertTrue("the registry is the only group list", grants.contains("val GROUPS: List<Group> = listOf("))
    }

    @Test fun the_server_reads_the_grants_per_request_not_once_at_start() {
        assertTrue("the server is handed a source", server.contains("private val grants: PhoneGrants.Source"))
        assertTrue(
            "tools/list builds the schema now, with the grants and the granted folders read per call",
            server.contains("PhoneTool.schema(grants, folderLabels())"),
        )
        assertTrue(
            "the app passes the live store, not a snapshot",
            mcp.contains("grants = PhoneGrantStore.source(app)"),
        )
    }

    @Test fun the_recent_actions_list_records_refusals_and_can_undo() {
        val sheet = File("src/main/java/com/hermesvox/PhoneToolsSheet.kt").readText()
        val local = File("src/main/java/com/hermesvox/LocalActions.kt").readText()
        val auditStore = File("src/main/java/com/hermesvox/PhoneAuditStore.kt").readText()
        assertTrue("the sheet has the list", sheet.contains("Recent actions"))
        assertTrue("a row can be undone", sheet.contains("PhoneAuditStore.undo(act, id)"))
        assertTrue("the engine audits every dispatch", local.contains("PhoneAuditStore.record"))
        assertTrue("refusals are entries too", local.contains("PhoneAudit.entry(at, args, result)"))
        assertTrue("the store persists the list", auditStore.contains("PhoneAudit.encode(kept)"))
        assertTrue("an undo with no live engine says expired", auditStore.contains("PhoneReason.EXPIRED"))
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
