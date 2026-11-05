package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three groups this lane made real — `media`, `device-state`, `clipboard` — asserted against a
 * fake platform, so each action's success, each of its named refusals, and the honesty rules that
 * are specific to it can be proven without a phone.
 *
 * The rules this file exists to keep:
 *
 *  * a media action reports that a key was **dispatched**, never that playback changed — the phone
 *    cannot see playback without a listener it does not hold;
 *  * `now_playing` says `known:false` and explains itself rather than inventing a title, and names
 *    the app and track only when the platform's session list is granted;
 *  * `state` reports `null` for anything the platform will not answer, never a plausible zero;
 *  * `clip` reports only the text's length, and `clip_read` returns the clipboard only when the
 *    platform allows the read — a backgrounded or empty clip is a refusal, not empty content.
 *
 * The fake is deliberately dumb: it records what it was asked and answers what it is told to
 * answer, so a test that passes proves the engine, not the fake.
 */
class PhoneSurfaceTest {

    private class Fake : PhoneEngine.PhonePlatform {
        val mediaKeys = ArrayList<PhoneEngine.PhonePlatform.MediaKey>()
        var mediaRefuse = false
        var playback = PhoneEngine.PhonePlatform.Playback(
            known = false, playing = null, packageName = null, title = null,
        )
        var snapshot = PhoneEngine.PhonePlatform.DeviceSnapshot(
            batteryPercent = null, chargingSource = null, chargingStatus = null, screenOn = null,
            network = null, tailnetPresent = null, freeStorageBytes = null, mediaVolume = null,
            dnd = null, timeZoneId = null, utcOffsetMinutes = null,
        )

        override fun startTimer(id: String, durationMs: Long, atEpochMs: Long): Boolean = true
        override fun startAlarm(id: String, hour: Int, minute: Int, atEpochMs: Long): Boolean = true
        override fun cancelTimer(id: String): Boolean = true
        override fun dndGranted(): Boolean = true
        override fun currentDnd(): Boolean? = false
        override fun setDnd(on: Boolean): Boolean = true
        override fun currentVolume(): Int? = 30
        override fun setVolume(percent: Int): Int? = percent
        override fun adjustVolume(direction: Int): Int? = 30 + direction
        override fun saveNote(id: String, text: String, atEpochMs: Long): Boolean = true
        override fun deleteNote(id: String): Boolean = true

        override fun mediaKey(key: PhoneEngine.PhonePlatform.MediaKey): Boolean {
            mediaKeys.add(key)
            return !mediaRefuse
        }

        override fun playback(): PhoneEngine.PhonePlatform.Playback = playback

        override fun deviceState(): PhoneEngine.PhonePlatform.DeviceSnapshot = snapshot

        val clips = ArrayList<String>()
        var clipRefuse = false

        override fun clip(text: String): Boolean {
            clips.add(text)
            return !clipRefuse
        }

        var clipRead: PhoneEngine.PhonePlatform.ClipRead =
            PhoneEngine.PhonePlatform.ClipRead.Empty

        override fun readClip(): PhoneEngine.PhonePlatform.ClipRead = clipRead
    }

    private val fake = Fake()
    private val t0 = 1_000_000L
    private val e0 = 1_700_000_000_000L

    private val entries = ArrayList<PhoneAudit.Entry>()

    /** Only the named group granted, everything else Off — the screen's own shape. */
    private fun only(id: String) = PhoneGrants.Source { g ->
        if (g.id == id) PhoneGrants.State.ALLOW else PhoneGrants.State.OFF
    }

    private fun engine(grants: PhoneGrants.Source) = PhoneEngine(
        fake,
        grants = grants,
        audit = { result, args, at -> entries.add(PhoneAudit.entry(at, args, result)) },
    )

    private fun call(arguments: Map<String, Any?>, grants: PhoneGrants.Source = only("media")) =
        engine(grants).dispatch(arguments, t0, e0)

    // ---- media ----

    @Test fun play_dispatches_the_play_key_and_claims_nothing_beyond_it() {
        val r = call(mapOf("action" to "play"))
        assertTrue(r.json(), r.ok)
        assertEquals(listOf(PhoneEngine.PhonePlatform.MediaKey.PLAY), fake.mediaKeys)
        assertEquals(true, r.facts["dispatched"])
        assertEquals("a media key has no honest reversal", false, r.facts["undoable"])
        assertTrue("the media note says what the phone cannot see", (r.facts["note"] as String).contains("cannot see"))
        assertFalse("no undo object is invented", r.json().contains("\"undo\""))
    }

    @Test fun pause_dispatches_the_pause_key_not_the_combined_one() {
        val r = call(mapOf("action" to "pause"))
        assertTrue(r.json(), r.ok)
        assertEquals(listOf(PhoneEngine.PhonePlatform.MediaKey.PAUSE), fake.mediaKeys)
        assertEquals(PhoneTiers.INSTANT, r.tier)
    }

    @Test fun skip_defaults_to_next_and_says_which_way_it_went() {
        val r = call(mapOf("action" to "skip"))
        assertTrue(r.json(), r.ok)
        assertEquals(listOf(PhoneEngine.PhonePlatform.MediaKey.NEXT), fake.mediaKeys)
        assertEquals("next", r.facts["direction"])
        assertEquals(true, r.facts["dispatched"])
    }

    @Test fun skip_previous_dispatches_the_previous_key() {
        val r = call(mapOf("action" to "skip", "direction" to "previous"))
        assertTrue(r.json(), r.ok)
        assertEquals(listOf(PhoneEngine.PhonePlatform.MediaKey.PREVIOUS), fake.mediaKeys)
        assertEquals("previous", r.facts["direction"])
    }

    @Test fun a_key_the_phone_would_not_take_is_unavailable_and_not_a_claim() {
        fake.mediaRefuse = true
        val r = call(mapOf("action" to "play"))
        assertFalse(r.ok)
        assertEquals(PhoneReason.UNAVAILABLE, r.reason)
        assertNull(r.id)
        assertFalse("a refusal never says it dispatched anything", r.toMap().containsKey("dispatched"))
        assertFalse("and never carries an undo", r.toMap().containsKey("undo"))
    }

    @Test fun now_playing_never_invents_a_title_the_platform_will_not_name() {
        fake.playback = PhoneEngine.PhonePlatform.Playback(
            known = false, playing = true, packageName = null, title = null,
        )
        val r = call(mapOf("action" to "now_playing"))
        assertTrue("the phone answers the question even when it cannot see", r.ok)
        assertEquals(false, r.facts["known"])
        assertEquals(true, r.facts["playing"])
        assertNull("no guessed package", r.facts["package"])
        assertNull("no guessed title", r.facts["title"])
        val note = r.facts["note"] as String
        assertTrue("it says the phone cannot see what is playing", note.contains("cannot see"))
        assertTrue("and names the access it would need", note.contains("notification-listener"))
    }

    @Test fun now_playing_is_still_an_answer_when_even_is_music_active_fails() {
        fake.playback = PhoneEngine.PhonePlatform.Playback(
            known = false, playing = null, packageName = null, title = null,
        )
        val r = call(mapOf("action" to "now_playing"))
        assertTrue(r.json(), r.ok)
        assertEquals(false, r.facts["known"])
        assertNull(r.facts["playing"])
        assertTrue(r.toMap().containsKey("playing"))
    }

    @Test fun now_playing_reports_a_session_the_platform_does_name() {
        fake.playback = PhoneEngine.PhonePlatform.Playback(
            known = true, playing = true, packageName = "com.example.player", title = "A Track",
        )
        val r = call(mapOf("action" to "now_playing"))
        assertTrue(r.json(), r.ok)
        assertEquals(true, r.facts["known"])
        assertEquals("com.example.player", r.facts["package"])
        assertEquals("A Track", r.facts["title"])
        assertEquals("no caveat is needed when the phone can see", false, r.toMap().containsKey("note"))
    }

    @Test fun now_playing_with_the_listener_granted_does_not_claim_the_access_is_missing() {
        fake.playback = PhoneEngine.PhonePlatform.Playback(
            known = false, playing = false, packageName = null, title = null,
            listenerGranted = true,
        )
        val r = call(mapOf("action" to "now_playing"))
        assertTrue(r.json(), r.ok)
        assertEquals(false, r.facts["known"])
        val note = r.facts["note"] as String
        assertTrue("it says the phone can see sessions", note.contains("can see media sessions"))
        assertFalse("and does not pretend the grant is absent", note.contains("does not hold"))
    }

    @Test fun the_media_group_is_refused_when_off_and_asked_when_ask() {
        val off = call(mapOf("action" to "play"), PhoneGrants.ALL_OFF)
        assertFalse(off.ok)
        assertEquals(PhoneReason.NOT_GRANTED, off.reason)
        assertTrue("the refusal names the group", off.detail!!.contains("Media"))

        val ask = call(mapOf("action" to "play"), PhoneGrants.Source { PhoneGrants.State.ASK })
        assertFalse(ask.ok)
        assertEquals(PhoneReason.APPROVAL, ask.reason)

        assertEquals("nothing reached the audio service", emptyList<PhoneEngine.PhonePlatform.MediaKey>(), fake.mediaKeys)
    }

    @Test fun bad_media_arguments_are_named_before_any_key_is_sent() {
        val r = call(mapOf("action" to "skip", "direction" to "sideways"))
        assertFalse(r.ok)
        assertEquals(PhoneReason.INVALID, r.reason)
        assertEquals(emptyList<PhoneEngine.PhonePlatform.MediaKey>(), fake.mediaKeys)
    }

    @Test fun a_media_call_is_audited_with_its_direction_and_no_payload() {
        call(mapOf("action" to "skip", "direction" to "previous"))
        val entry = entries.single()
        assertEquals("skip", entry.action)
        assertEquals("direction", entry.arguments.keys.single())
        assertEquals("previous", entry.arguments["direction"])
        assertEquals("done", entry.outcome)
        assertNull("a media key is not undoable, so there is no undo id", entry.undoId)
    }

    // ---- device-state ----

    /** The fields `state` reports, in the order the result reads them. */
    private val stateFields = listOf(
        "battery_percent", "charging_source", "charging_status", "screen_on", "network",
        "tailnet_present", "free_storage_bytes", "media_volume", "dnd", "timezone",
        "utc_offset_minutes", "time",
    )

    @Test fun state_reports_every_field_the_phone_answered() {
        fake.snapshot = fullSnapshot()
        val r = call(mapOf("action" to "state"), only("device-state"))
        assertTrue(r.json(), r.ok)
        assertEquals(82, r.facts["battery_percent"])
        assertEquals("usb", r.facts["charging_source"])
        assertEquals("charging", r.facts["charging_status"])
        assertEquals(true, r.facts["screen_on"])
        assertEquals("wifi", r.facts["network"])
        assertEquals(true, r.facts["tailnet_present"])
        assertEquals(9_000_000_000L, r.facts["free_storage_bytes"])
        assertEquals(42, r.facts["media_volume"])
        assertEquals("off", r.facts["dnd"])
        assertEquals("Europe/Berlin", r.facts["timezone"])
        assertEquals(60, r.facts["utc_offset_minutes"])
        // 2023-11-14T22:13:20Z + 60 minutes.
        assertEquals("2023-11-14T23:13:20", r.facts["time"])
    }

    @Test fun state_reports_null_for_everything_the_platform_will_not_say() {
        fake.snapshot = PhoneEngine.PhonePlatform.DeviceSnapshot(
            batteryPercent = null, chargingSource = null, chargingStatus = null, screenOn = null,
            network = null, tailnetPresent = null, freeStorageBytes = null, mediaVolume = null,
            dnd = null, timeZoneId = null, utcOffsetMinutes = null,
        )
        val r = call(mapOf("action" to "state"), only("device-state"))
        assertTrue("an unreadable phone is still an answer", r.ok)
        val wire = r.toMap()
        for (field in stateFields) {
            assertTrue("$field must be present, not omitted", wire.containsKey(field))
            assertNull("$field must be null, never a plausible zero", wire[field])
        }
    }

    @Test fun state_keeps_the_difference_between_none_and_cannot_read() {
        fake.snapshot = fullSnapshot().copy(
            network = "none",
            chargingSource = "none",
            dnd = null,
            timeZoneId = null,
            utcOffsetMinutes = null,
        )
        val r = call(mapOf("action" to "state"), only("device-state"))
        assertEquals("read, and nothing is connected", "none", r.facts["network"])
        assertEquals("read, and the phone is not plugged in", "none", r.facts["charging_source"])
        assertNull("Do Not Disturb access is not held", r.facts["dnd"])
        assertNull("with no offset there is no honest clock", r.facts["time"])
    }

    @Test fun state_needs_its_group_like_every_other_action() {
        val off = call(mapOf("action" to "state"), PhoneGrants.ALL_OFF)
        assertFalse(off.ok)
        assertEquals(PhoneReason.NOT_GRANTED, off.reason)
        assertTrue(off.detail!!.contains("Device state"))

        val ask = call(mapOf("action" to "state"), PhoneGrants.Source { PhoneGrants.State.ASK })
        assertFalse(ask.ok)
        assertEquals(PhoneReason.APPROVAL, ask.reason)
    }

    @Test fun state_is_read_only_and_writes_one_audit_row() {
        fake.snapshot = fullSnapshot()
        val r = call(mapOf("action" to "state"), only("device-state"))
        assertEquals("state", r.action)
        assertEquals(PhoneTiers.INSTANT, r.tier)
        assertNull("a report carries no id", r.id)
        assertNull("and no undo", r.facts["undoable"])
        val entry = entries.single()
        assertEquals("state", entry.action)
        assertTrue("state takes no arguments to summarise", entry.arguments.isEmpty())
        assertEquals("done", entry.outcome)
        assertNull(entry.undoId)
    }

    @Test fun local_time_is_the_injected_clock_in_the_reported_offset() {
        // 2023-11-14T22:13:20Z.
        assertEquals("2023-11-14T22:13:20", PhoneEngine.localIso(e0, 0))
        assertEquals("2023-11-14T23:13:20", PhoneEngine.localIso(e0, 60))
        assertEquals("2023-11-14T17:13:20", PhoneEngine.localIso(e0, -300))
        assertEquals("2023-11-14T22:28:20", PhoneEngine.localIso(e0, 15))
        assertEquals("2023-11-15T08:13:20", PhoneEngine.localIso(e0, 600))
    }

    private fun fullSnapshot() = PhoneEngine.PhonePlatform.DeviceSnapshot(
        batteryPercent = 82,
        chargingSource = "usb",
        chargingStatus = "charging",
        screenOn = true,
        network = "wifi",
        tailnetPresent = true,
        freeStorageBytes = 9_000_000_000L,
        mediaVolume = 42,
        dnd = "off",
        timeZoneId = "Europe/Berlin",
        utcOffsetMinutes = 60,
    )

    // ---- clipboard ----

    @Test fun clip_writes_the_text_and_reports_only_its_length() {
        val text = "the plumber's number is 555-0100"
        val r = call(mapOf("action" to "clip", "text" to text), only("clipboard"))
        assertTrue(r.json(), r.ok)
        assertEquals(listOf(text), fake.clips)
        assertEquals(text.length, r.facts["chars"])
        assertEquals("a clipboard write cannot be reversed", false, r.facts["undoable"])
        assertFalse("the text is never echoed back", r.json().contains("555-0100"))
        assertFalse("and no undo object is invented", r.json().contains("\"undo\""))
    }

    @Test fun clip_is_audited_as_a_length_never_the_payload() {
        call(mapOf("action" to "clip", "text" to "correct horse battery staple"), only("clipboard"))
        val entry = entries.single()
        assertEquals("clip", entry.action)
        assertEquals("${"correct horse battery staple".length} chars", entry.arguments["text"])
        assertFalse(
            "the payload must never reach the audit",
            entry.arguments.values.any { it.contains("battery") },
        )
        assertEquals("done", entry.outcome)
        assertNull(entry.undoId)
    }

    @Test fun a_clipboard_that_would_not_take_the_text_is_unavailable() {
        fake.clipRefuse = true
        val r = call(mapOf("action" to "clip", "text" to "hello"), only("clipboard"))
        assertFalse(r.ok)
        assertEquals(PhoneReason.UNAVAILABLE, r.reason)
        assertNull("a refusal never carries a success fact", r.facts["chars"])
        assertTrue("the sentence says nothing was written", r.detail!!.contains("nothing was written"))
    }

    @Test fun clip_needs_its_group_and_its_argument() {
        val off = call(mapOf("action" to "clip", "text" to "hello"), PhoneGrants.ALL_OFF)
        assertFalse(off.ok)
        assertEquals(PhoneReason.NOT_GRANTED, off.reason)
        assertTrue(off.detail!!.contains("Clipboard"))

        val empty = call(mapOf("action" to "clip", "text" to "  "), only("clipboard"))
        assertFalse(empty.ok)
        assertEquals(PhoneReason.INVALID, empty.reason)

        val long = call(mapOf("action" to "clip", "text" to "x".repeat(PhoneTool.MAX_CLIP_CHARS + 1)), only("clipboard"))
        assertFalse(long.ok)
        assertEquals(PhoneReason.INVALID, long.reason)

        assertEquals("nothing was ever written", emptyList<String>(), fake.clips)
    }

    /**
     * The read the owner granted, when the platform allows it. Android decides this: a foreground
     * app gets the clipboard, a backgrounded one gets nothing. The engine returns what the platform
     * handed over, and only then.
     */
    @Test fun clip_read_returns_the_text_the_platform_hands_over() {
        val text = "the plumber's number is 555-0100"
        fake.clipRead = PhoneEngine.PhonePlatform.ClipRead.Text(text)
        val r = call(mapOf("action" to "clip_read"), only("clipboard"))
        assertTrue(r.json(), r.ok)
        assertEquals(text, r.facts["text"])
        assertEquals(text.length, r.facts["chars"])
        assertEquals("a clipboard read cannot be reversed", false, r.facts["undoable"])
        assertFalse("and no undo object is invented", r.json().contains("\"undo\""))
    }

    /**
     * The rule this lane replaced the old "never reads" assertion with: when the platform will not
     * answer — the app is backgrounded, which is Android 10+'s own boundary — the result is a
     * refusal carrying the platform's reason, **not** the empty text a read would have produced.
     */
    @Test fun a_backgrounded_clip_read_refuses_honestly_instead_of_returning_empty_content() {
        fake.clipRead = PhoneEngine.PhonePlatform.ClipRead.Unavailable(
            "Android does not let an app that is not in front read the clipboard; the phone was " +
                "backgrounded, so nothing was read",
        )
        val r = call(mapOf("action" to "clip_read"), only("clipboard"))
        assertFalse("a backgrounded read is not a success", r.ok)
        assertEquals(PhoneReason.UNAVAILABLE, r.reason)
        assertTrue(
            "the platform's own explanation is what the caller reads",
            r.detail!!.contains("not in front"),
        )
        assertFalse("no stale or empty text is passed off as the clipboard", r.toMap().containsKey("text"))
        assertFalse("and no length either", r.toMap().containsKey("chars"))
    }

    @Test fun an_empty_clipboard_is_a_refusal_not_an_empty_string() {
        fake.clipRead = PhoneEngine.PhonePlatform.ClipRead.Empty
        val r = call(mapOf("action" to "clip_read"), only("clipboard"))
        assertFalse(r.ok)
        assertEquals(PhoneReason.UNAVAILABLE, r.reason)
        assertTrue(r.detail!!.contains("clipboard is empty"))
        assertFalse(r.toMap().containsKey("text"))
    }

    @Test fun clip_read_needs_the_clipboard_group_like_the_write() {
        val off = call(mapOf("action" to "clip_read"), PhoneGrants.ALL_OFF)
        assertFalse(off.ok)
        assertEquals(PhoneReason.NOT_GRANTED, off.reason)
        assertTrue(off.detail!!.contains("Clipboard"))

        val on = call(mapOf("action" to "clip_read"), only("clipboard"))
        assertTrue("offered once the group is on", on.ok || on.reason != PhoneReason.NOT_GRANTED)
    }

    @Test fun app_focus_counts_resumed_activities_and_never_goes_below_zero() {
        AppFocus.reset()
        assertFalse("a fresh process has nothing in front", AppFocus.inFront)
        AppFocus.onActivityResumed()
        assertTrue(AppFocus.inFront)
        AppFocus.onActivityResumed()
        AppFocus.onActivityPaused()
        assertTrue("a second activity keeps the app in front", AppFocus.inFront)
        AppFocus.onActivityPaused()
        AppFocus.onActivityPaused()
        assertFalse("an unbalanced callback does not go negative", AppFocus.inFront)
        AppFocus.reset()
    }
}
