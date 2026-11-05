package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The three handoffs that need no permission and no approval card (#429 handoff lane):
 * `message` composes, `dial` opens the dialer, and `launch`/`open` start an app.
 *
 * Each of them hands off to an app the owner already trusts, which is why they are Tier 0.
 * The properties that matter are asserted, not described:
 *
 *  * a text is an `ACTION_SENDTO` draft and **never a send call** (source-level, because
 *    this is the test that matters);
 *  * a dial is `ACTION_DIAL`, and with a live Vox call the order is announce, end, launch;
 *  * a launch resolves launchable activities only, and PiP is asked for before the start;
 *  * a contact name without the Contacts group is `not_granted`, naming Contacts.
 */
class PhoneHandoffTest {

    /** A phone that records what it was asked to do, and can be told what resolves. */
    private class FakePhone : PhoneEngine.PhonePlatform {
        val events = mutableListOf<String>()
        var live = false
        var canStart = true
        var dialer = true
        var dialStarts = true
        var contact: String? = null
        var send: PhoneEngine.PhonePlatform.Handoff =
            PhoneEngine.PhonePlatform.Handoff.Done("com.example.sms")
        var launchTarget: PhoneEngine.PhonePlatform.AppTarget? =
            PhoneEngine.PhonePlatform.AppTarget("com.example.app", "com.example.app.Main")
        var uriTarget: PhoneEngine.PhonePlatform.AppTarget? =
            PhoneEngine.PhonePlatform.AppTarget("com.example.browser", "com.example.browser.View")
        var startResult = true
        var windowHost: CallWindow.Host? = null
        /** When true, [announce] holds the continuation until the test runs [after]. */
        var deferAnnounce = false
        var after: (() -> Unit)? = null
        var label: String? = "Example"
        var uninstallResult: PhoneEngine.PhonePlatform.Uninstall =
            PhoneEngine.PhonePlatform.Uninstall.Confirming("com.example.app")

        override fun callLive(): Boolean = live
        override fun canStartActivity(): Boolean = canStart
        override fun canDial(): Boolean = dialer

        override fun announce(line: String, after: () -> Unit) {
            events += "announce"
            assertEquals(DialHandoff.LINE, line)
            after()
        }

        override fun endCall() {
            events += "end"
        }

        override fun dial(number: String): Boolean {
            events += "dial:$number"
            return dialStarts
        }

        override fun contactNumber(name: String): String? = contact

        override fun sendMessage(to: String, text: String): PhoneEngine.PhonePlatform.Handoff {
            events += "send:$to:${text.length}"
            return send
        }

        override fun resolveLaunchable(name: String): PhoneEngine.PhonePlatform.AppTarget? =
            launchTarget

        override fun startApp(target: PhoneEngine.PhonePlatform.AppTarget): Boolean {
            events += "launch:${target.packageName}"
            return startResult
        }

        override fun resolveUri(
            uri: String,
            packageName: String?,
        ): PhoneEngine.PhonePlatform.AppTarget? = uriTarget

        override fun startUri(
            target: PhoneEngine.PhonePlatform.AppTarget,
            uri: String,
        ): Boolean {
            events += "open:$uri"
            return startResult
        }

        override fun appLabel(packageName: String): String? = label

        override fun uninstall(packageName: String): PhoneEngine.PhonePlatform.Uninstall {
            events += "uninstall:$packageName"
            return uninstallResult
        }

        override fun window(): CallWindow.Host? = windowHost

        // ---- the rest of the platform, inert for these tests ----

        override fun startTimer(id: String, durationMs: Long, atEpochMs: Long) = true
        override fun startAlarm(id: String, hour: Int, minute: Int, atEpochMs: Long) = true
        override fun cancelTimer(id: String) = true
        override fun dndGranted() = true
        override fun currentDnd(): Boolean? = false
        override fun setDnd(on: Boolean) = true
        override fun currentVolume(): Int? = 50
        override fun setVolume(percent: Int): Int? = percent
        override fun adjustVolume(direction: Int): Int? = 50
        override fun saveNote(id: String, text: String, atEpochMs: Long) = true
        override fun deleteNote(id: String) = true
        override fun mediaKey(key: PhoneEngine.PhonePlatform.MediaKey) = true
        override fun playback() = PhoneEngine.PhonePlatform.Playback(
            known = false, playing = false, packageName = null, title = null,
        )
        override fun deviceState() = PhoneEngine.PhonePlatform.DeviceSnapshot(
            batteryPercent = null, chargingSource = null, chargingStatus = null, screenOn = null,
            network = null, tailnetPresent = null, freeStorageBytes = null, mediaVolume = null,
            dnd = null, timeZoneId = null, utcOffsetMinutes = null,
        )
        override fun clip(text: String): PhoneEngine.PhonePlatform.ClipWrite =
            PhoneEngine.PhonePlatform.ClipWrite.Done
    }

    /** A PiP host that records `pip` and holds the posted start until the test runs it. */
    private class FakeWindow(private val events: MutableList<String>) : CallWindow.Host {
        var posted: (() -> Unit)? = null

        override fun callLive(): Boolean = true

        override fun enterPip(): Boolean {
            events += "pip"
            return true
        }

        override fun post(delayMs: Long, action: () -> Unit) {
            events += "post"
            posted = action
        }
    }

    private val fake = FakePhone()
    private val engine = PhoneEngine(fake, grants = PhoneGrants.Source { PhoneGrants.State.ALLOW })
    private val t0 = 1_000_000L
    private val e0 = 1_700_000_000_000L

    private fun run(map: Map<String, Any?>): PhoneResult =
        engine.dispatch(map, t0, e0)

    // ---- messages ----

    @Test
    fun message_composes_a_draft_and_hands_it_off_without_sending() {
        val r = run(mapOf("action" to "message", "to" to "+1 (555) 123-4567", "text" to "on my way"))
        assertTrue(r.json(), r.ok)
        assertEquals("message", r.action)
        assertEquals("+15551234567", r.facts["to"])
        assertEquals(9, r.facts["chars"])
        assertEquals(true, r.facts["handed_off"])
        assertEquals("the platform got the number and the body", listOf("send:+15551234567:9"), fake.events)
        assertNull("a handoff carries no undo", r.id)
    }

    @Test
    fun message_never_calls_a_send_api() {
        val source = File("src/main/java/com/hermesvox/LocalActions.kt").readText()
        assertTrue("the draft is ACTION_SENDTO", source.contains("Intent.ACTION_SENDTO"))
        assertTrue("with an smsto: URI", source.contains("\"smsto\""))
        assertTrue("and the body is sms_body", source.contains("\"sms_body\""))
        for (forbidden in listOf(
            "sendTextMessage",
            "SmsManager",
            "Intent.ACTION_SEND_SMS",
            "Manifest.permission.SEND_SMS",
        )) {
            assertFalse("messages are never sent by Vox: $forbidden", source.contains(forbidden))
        }
    }

    @Test
    fun message_with_no_messaging_app_is_no_app() {
        fake.send = PhoneEngine.PhonePlatform.Handoff.NoApp
        val r = run(mapOf("action" to "message", "to" to "12345", "text" to "hi"))
        assertFalse(r.ok)
        assertEquals(PhoneReason.NO_APP, r.reason)
        assertTrue(r.facts.isEmpty())
    }

    @Test
    fun message_requires_a_recipient_and_a_non_empty_body() {
        assertEquals(
            PhoneReason.INVALID,
            run(mapOf("action" to "message", "text" to "hi")).reason,
        )
        assertEquals(
            PhoneReason.INVALID,
            run(mapOf("action" to "message", "to" to "12345", "text" to "  ")).reason,
        )
    }

    // ---- calls ----

    @Test
    fun dial_builds_the_dialer_intent_and_never_places_a_call() {
        val r = run(mapOf("action" to "dial", "to" to "+1 (555) 123-4567"))
        assertTrue(r.json(), r.ok)
        assertEquals("+15551234567", r.facts["to"])
        assertEquals(listOf("dial:+15551234567"), fake.events)

        val source = File("src/main/java/com/hermesvox/LocalActions.kt").readText()
        assertTrue("the dialer is opened with ACTION_DIAL", source.contains("Intent.ACTION_DIAL"))
        assertFalse("Vox never places a call itself", source.contains("Intent.ACTION_CALL"))
        assertFalse(
            "and never asks for the permission that would let it",
            source.contains("Manifest.permission.CALL_PHONE"),
        )
        assertFalse("not even through the telecom manager", source.contains("placeCall"))
    }

    @Test
    fun dial_ends_a_live_call_in_the_order_announce_end_launch() {
        fake.live = true
        val r = run(mapOf("action" to "dial", "to" to "123456"))
        assertTrue(r.json(), r.ok)
        assertEquals("123456", r.facts["to"])
        assertEquals(listOf("announce", "end", "dial:123456"), fake.events)
    }

    @Test
    fun dial_with_no_dialer_ends_nothing_and_says_no_app() {
        fake.live = true
        fake.dialer = false
        val r = run(mapOf("action" to "dial", "to" to "123456"))
        assertFalse(r.ok)
        assertEquals(PhoneReason.NO_APP, r.reason)
        assertEquals("no line, no teardown, no launch", emptyList<String>(), fake.events)
    }

    @Test
    fun dial_does_not_end_a_call_that_is_not_live() {
        val r = run(mapOf("action" to "dial", "to" to "123456"))
        assertTrue(r.ok)
        assertEquals(listOf("dial:123456"), fake.events)
    }

    // ---- apps ----

    @Test
    fun launch_resolves_a_launchable_app_and_reports_package_and_component() {
        val r = run(mapOf("action" to "launch", "app" to "com.example.app"))
        assertTrue(r.json(), r.ok)
        assertEquals("com.example.app", r.facts["package"])
        assertEquals("com.example.app/com.example.app.Main", r.facts["component"])
        assertEquals(listOf("launch:com.example.app"), fake.events)
    }

    @Test
    fun launch_refuses_no_app_when_nothing_launchable_matches() {
        fake.launchTarget = null
        val r = run(mapOf("action" to "launch", "app" to "com.example.not-installed"))
        assertFalse(r.ok)
        assertEquals(PhoneReason.NO_APP, r.reason)
        assertTrue(r.detail!!.contains("no launchable app"))
        assertTrue(fake.events.isEmpty())
    }

    @Test
    fun launch_asks_pip_before_the_start_when_a_call_is_live() {
        fake.live = true
        val window = FakeWindow(fake.events)
        fake.windowHost = window
        val r = run(mapOf("action" to "launch", "app" to "com.example.app"))
        assertTrue(r.json(), r.ok)
        assertEquals("PiP first, the start posted behind it", listOf("pip", "post"), fake.events)

        window.posted?.invoke()
        assertEquals(listOf("pip", "post", "launch:com.example.app"), fake.events)
    }

    @Test
    fun launch_resolution_is_limited_to_launchable_activities() {
        val source = File("src/main/java/com/hermesvox/LocalActions.kt").readText()
        assertTrue("launch asks for launcher entries", source.contains("CATEGORY_LAUNCHER"))
        assertTrue("through the launcher intent", source.contains("queryIntentActivities"))
        assertFalse(
            "and never enumerates every installed package",
            source.contains("getInstalledPackages") || source.contains("getInstalledApplications"),
        )

        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(
            "package visibility is declared for launchers",
            manifest.contains("android.intent.category.LAUNCHER"),
        )
        assertTrue("and for the SENDTO handoff", manifest.contains("android.intent.action.SENDTO"))
        assertTrue("and for the dialer", manifest.contains("android.intent.action.DIAL"))
    }

    @Test
    fun a_launch_goes_through_the_pip_funnel() {
        val source = File("src/main/java/com/hermesvox/PhoneEngine.kt").readText()
        assertTrue("the engine calls the PiP-first entry point", source.contains("CallWindow.launch(host, begin)"))
    }

    @Test
    fun a_launch_still_happens_when_pip_is_unavailable() {
        fake.live = true
        fake.windowHost = null   // no activity / no PiP: the launch starts immediately
        val r = run(mapOf("action" to "launch", "app" to "com.example.app"))
        assertTrue(r.json(), r.ok)
        assertEquals(listOf("launch:com.example.app"), fake.events)
    }

    @Test
    fun open_resolves_the_uri_and_starts_it() {
        val r = run(mapOf("action" to "open", "uri" to "https://example.com/a"))
        assertTrue(r.json(), r.ok)
        assertEquals("com.example.browser", r.facts["package"])
        assertEquals("com.example.browser/com.example.browser.View", r.facts["component"])
        assertEquals(listOf("open:https://example.com/a"), fake.events)
    }

    @Test
    fun open_refuses_an_unusable_uri_and_a_missing_receiver() {
        assertEquals(
            PhoneReason.INVALID,
            run(mapOf("action" to "open", "uri" to "not a uri")).reason,
        )
        assertEquals(
            PhoneReason.BAD_URI,
            run(mapOf("action" to "open", "uri" to "example.com")).reason,
        )
        fake.uriTarget = null
        val r = run(mapOf("action" to "open", "uri" to "https://example.com"))
        assertFalse(r.ok)
        assertEquals(PhoneReason.NO_APP, r.reason)
    }

    /**
     * `open` hands a URI to a viewer. Both of these would make Android do something the owner did
     * not choose: `intent:` starts an explicit component with arbitrary extras and flags, and
     * `file:` names a path on disk. Neither reaches the platform — the refusal happens at parse
     * time, so no intent is built and [FakePhone] records nothing.
     */
    @Test
    fun open_refuses_intent_and_file_uris_before_any_intent_is_built() {
        for (uri in listOf(
            "intent:#Intent;component=com.example/.Secret;end",
            "intent:https://example.com#Intent;end",
            "file:///sdcard/Download/private.txt",
            "file:/data/local/tmp/x",
        )) {
            val r = run(mapOf("action" to "open", "uri" to uri))
            assertFalse("$uri must be refused", r.ok)
            assertEquals("$uri must be bad_uri", PhoneReason.BAD_URI, r.reason)
            val scheme = uri.substringBefore(':')
            assertTrue(
                "$uri must name the scheme it refused in the detail: ${r.detail}",
                r.detail!!.contains(scheme),
            )
        }
        assertEquals("nothing may be resolved or started", emptyList<String>(), fake.events)
    }

    @Test
    fun open_refuses_a_uri_with_no_scheme_instead_of_guessing_https() {
        val r = run(mapOf("action" to "open", "uri" to "example.com/a"))
        assertFalse(r.ok)
        assertEquals(PhoneReason.BAD_URI, r.reason)
        assertTrue("the detail must not imply a scheme was guessed", r.detail!!.contains("scheme"))
        assertEquals("nothing may be resolved or started", emptyList<String>(), fake.events)
    }

    @Test
    fun open_allows_content_and_web_uris() {
        for (uri in listOf(
            "https://example.com/a",
            "http://example.com/b",
            "content://com.example.provider/document/1",
        )) {
            val r = run(mapOf("action" to "open", "uri" to uri))
            assertTrue("$uri must be handed to the platform", r.ok)
            assertEquals("com.example.browser", r.facts["package"])
            assertEquals(listOf("open:$uri"), fake.events)
            fake.events.clear()
        }
    }

    // ---- contacts ----

    @Test
    fun a_contact_name_without_the_contacts_group_is_not_granted_naming_contacts() {
        val messagesOnly = PhoneGrants.Source { g ->
            if (g.id == "messages") PhoneGrants.State.ALLOW else PhoneGrants.State.OFF
        }
        val parsed = PhoneTool.parse(
            mapOf("action" to "message", "to" to "Mom", "text" to "hi"),
            messagesOnly,
        )
        val result = (parsed as PhoneTool.Parsed.Refuse).result
        assertEquals(PhoneReason.NOT_GRANTED, result.reason)
        assertTrue(result.detail!!.contains("Contacts"))
        assertFalse("nothing was handed off", result.ok)
    }

    // ---- the background-launch gate (B2) ----

    /**
     * Android 10+ refuses a background activity start **without throwing**. Each handoff must
     * therefore ask before it resolves a target, ends a call or announces anything, and the
     * owner-facing refusal must say why rather than reporting a launch that never happened.
     */
    @Test
    fun every_handoff_refuses_before_it_resolves_when_vox_is_not_on_screen() {
        fake.canStart = false
        fake.live = true   // even a live call must not be torn down for a start that cannot happen
        for (call in listOf(
            mapOf("action" to "message", "to" to "12345", "text" to "hi"),
            mapOf("action" to "dial", "to" to "12345"),
            mapOf("action" to "launch", "app" to "com.example.app"),
            mapOf("action" to "open", "uri" to "https://example.com/a"),
            mapOf("action" to "uninstall", "package" to "com.example.app"),
        )) {
            fake.events.clear()
            val r = run(call)
            assertFalse("${call["action"]} must be refused: ${r.json()}", r.ok)
            assertEquals(PhoneReason.UNAVAILABLE, r.reason)
            assertTrue(
                "${call["action"]} must say why: ${r.detail}",
                r.detail!!.contains("on screen") && r.detail.contains("background"),
            )
            assertEquals(
                "${call["action"]} must resolve, end and start nothing",
                emptyList<String>(),
                fake.events,
            )
        }
    }

    @Test
    fun the_background_refusal_is_visible_and_the_gate_sits_on_the_platform() {
        val engineSource = File("src/main/java/com/hermesvox/PhoneEngine.kt").readText()
        assertTrue(
            "one gate is checked before each handoff",
            engineSource.contains("private fun backgroundRefusal(action: String)") &&
                engineSource.countMatches("backgroundRefusal(\"") == 5,
        )
        assertTrue(
            "the answer is resumed or PiP-visible",
            File("src/main/java/com/hermesvox/MainActivity.kt").readText()
                .contains("AppFocus.inFront || isInPictureInPictureMode"),
        )
    }

    private fun String.countMatches(needle: String): Int =
        windowed(needle.length).count { it == needle }
}
