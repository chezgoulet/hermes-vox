package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #429 step 1: the grant is enforced at the call, and revoking it does not wait for a reconnect.
 *
 * The engine reads [PhoneGrants.Source] through [PhoneEngine.dispatch], so the same engine instance
 * that ran an allowed call must refuse the next one after the owner turns the group Off. That is the
 * property the sheet's control depends on: flipping a switch is the revocation.
 *
 * Every dispatch also lands in the audit — success *and* refusal — because the list is the record a
 * claim can be checked against.
 */
class PhoneGovernanceTest {

    private class Fake : PhoneEngine.PhonePlatform {
        var timerArms = 0
        val cancelled = ArrayList<String>()
        override fun startTimer(id: String, durationMs: Long, atEpochMs: Long): Boolean {
            timerArms++
            return true
        }
        override fun startAlarm(id: String, hour: Int, minute: Int, atEpochMs: Long): Boolean = true
        override fun cancelTimer(id: String): Boolean {
            cancelled.add(id)
            return true
        }
        override fun dndGranted(): Boolean = true
        override fun currentDnd(): Boolean? = false
        override fun setDnd(on: Boolean): Boolean = true
        override fun currentVolume(): Int? = 30
        override fun setVolume(percent: Int): Int? = percent
        override fun adjustVolume(direction: Int): Int? = 30 + direction
        override fun saveNote(id: String, text: String, atEpochMs: Long): Boolean = true
        override fun deleteNote(id: String): Boolean = true
        override fun mediaKey(key: PhoneEngine.PhonePlatform.MediaKey): Boolean = true
        override fun playback(): PhoneEngine.PhonePlatform.Playback =
            PhoneEngine.PhonePlatform.Playback(known = false, playing = null, packageName = null, title = null)
        override fun deviceState(): PhoneEngine.PhonePlatform.DeviceSnapshot =
            PhoneEngine.PhonePlatform.DeviceSnapshot(
                batteryPercent = null, chargingSource = null, chargingStatus = null, screenOn = null,
                network = null, tailnetPresent = null, freeStorageBytes = null, mediaVolume = null,
                dnd = null, timeZoneId = null, utcOffsetMinutes = null,
            )
        override fun clip(text: String): Boolean = true
    }

    private val fake = Fake()
    private val t0 = 1_000_000L
    private val e0 = 1_700_000_000_000L

    /** A mutable policy, the way the settings screen is: the engine holds the source, not a copy. */
    private class MutableGrants(var state: PhoneGrants.State) : PhoneGrants.Source {
        override fun state(group: PhoneGrants.Group): PhoneGrants.State = state
    }

    private val entries = ArrayList<PhoneAudit.Entry>()

    private fun engine(grants: PhoneGrants.Source) = PhoneEngine(
        fake,
        grants = grants,
        audit = { result, args, at -> entries.add(PhoneAudit.entry(at, args, result)) },
    )

    private fun timerCall() = mapOf<String, Any?>("action" to "timer", "duration_ms" to 300_000L)

    @Test fun an_off_group_refuses_and_nothing_is_armed() {
        val r = engine(PhoneGrants.ALL_OFF).dispatch(timerCall(), t0, e0)
        assertFalse(r.ok)
        assertEquals(PhoneReason.NOT_GRANTED, r.reason)
        assertNull(r.id)
        assertEquals("a refused call must not reach the platform", 0, fake.timerArms)
    }

    @Test fun an_ask_group_refuses_and_nothing_is_armed() {
        val r = engine(MutableGrants(PhoneGrants.State.ASK)).dispatch(timerCall(), t0, e0)
        assertFalse(r.ok)
        assertEquals(PhoneReason.APPROVAL, r.reason)
        assertNull(r.id)
        assertEquals(0, fake.timerArms)
    }

    @Test fun a_reported_group_at_ask_runs_and_lands_in_the_audit() {
        // Tier 1 has no Allow: Ask is its on state, and the report is the audit row plus the
        // result. Nothing waits for a card that this build cannot mint.
        val r = engine(MutableGrants(PhoneGrants.State.ASK))
            .dispatch(mapOf("action" to "note", "text" to "milk"), t0, e0)
        assertTrue(r.json(), r.ok)
        assertEquals("note", r.action)
        assertNotNull(r.id)
        val entry = entries.single()
        assertEquals("done", entry.outcome)
        assertTrue("a note is reported as a length, never its body", entry.arguments["text"] == "4 chars")
    }

    @Test fun revocation_takes_effect_on_the_very_next_call() {
        val policy = MutableGrants(PhoneGrants.State.ALLOW)
        val e = engine(policy)

        val first = e.dispatch(timerCall(), t0, e0)
        assertTrue(first.json(), first.ok)
        assertEquals(1, fake.timerArms)

        // The owner turns the group Off while the session is live. Same engine, same source.
        policy.state = PhoneGrants.State.OFF

        val second = e.dispatch(timerCall(), t0 + 1_000, e0 + 1_000)
        assertFalse(second.ok)
        assertEquals(PhoneReason.NOT_GRANTED, second.reason)
        assertEquals("the second call must not arm anything", 1, fake.timerArms)
        assertFalse(
            "the first timer is not advertised to the mind once the group is Off (H4)",
            e.registry(t0 + 1_000, e0 + 1_000).any { it.startsWith("timer(") },
        )
        assertTrue(
            "the first timer is still running and cancellable",
            e.undo(first.id!!, t0 + 1_000, e0 + 1_000, PhoneEngine.Origin.OWNER).ok,
        )
        assertEquals(listOf(first.id!!), fake.cancelled)
    }

    @Test fun a_refusal_is_written_to_the_audit_like_any_other_call() {
        val e = engine(PhoneGrants.ALL_OFF)
        e.dispatch(timerCall(), t0, e0)

        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("timer", entry.action)
        assertEquals("not_granted", entry.outcome)
        assertFalse(entry.ok)
        assertNull("a refusal carries no undo", entry.undoId)
        assertEquals("300000", entry.arguments["duration_ms"])
    }

    @Test fun a_success_is_written_with_the_undo_it_returned() {
        val e = engine(MutableGrants(PhoneGrants.State.ALLOW))
        val r = e.dispatch(timerCall(), t0, e0)
        assertTrue(r.ok)

        val entry = entries.single()
        assertTrue(entry.ok)
        assertEquals("done", entry.outcome)
        assertEquals(r.id, entry.undoId)
        assertEquals("timer", entry.action)
    }

    @Test fun the_audit_never_keeps_a_note_body() {
        val text = "the plumber's number is 555-0100"
        val entry = PhoneAudit.entry(
            e0,
            mapOf("action" to "note", "text" to text),
            PhoneResult.done("note", "note-1", mapOf("undoable" to true)),
        )
        assertEquals("${text.length} chars", entry.arguments["text"])
        assertFalse("the body must never be stored", entry.arguments.values.any { it.contains("555-0100") })
        assertEquals("note-1", entry.undoId)
    }

    @Test fun status_reports_only_what_is_offered_now() {
        val e = engine(MutableGrants(PhoneGrants.State.OFF))
        val s = e.status(t0, e0)
        assertEquals(listOf("undo", "status"), s.facts["offered"])
        assertTrue((s.facts["grants"] as String).contains("timers-alarms=off"))
    }

    // ---- H4: the grants govern the registry, not only the actions ----

    /**
     * The owner's own voice, then every group Off: the mind's `status` must show none of it —
     * not the timer, not the alarm, not the note, and not the note's text.
     */
    @Test fun with_every_group_off_status_shows_no_owner_timer_alarm_or_note() {
        val policy = MutableGrants(PhoneGrants.State.ALLOW)
        val e = engine(policy)
        assertTrue(e.execute(PhoneTool.Call.Timer(300_000), t0, e0, PhoneEngine.Origin.OWNER).ok)
        assertTrue(e.execute(PhoneTool.Call.Alarm(6, 0), t0, e0, PhoneEngine.Origin.OWNER).ok)
        assertTrue(
            e.execute(
                PhoneTool.Call.Note("the safe code is 4417"),
                t0,
                e0,
                PhoneEngine.Origin.OWNER,
            ).ok
        )
        assertEquals("the owner's entries were never the mind's to see", emptyList<String>(), e.registry(t0, e0))

        policy.state = PhoneGrants.State.OFF
        val s = e.status(t0, e0)
        assertEquals(emptyList<Any?>(), s.facts["running"])
        assertEquals(emptyList<Any?>(), s.facts["notes"])
        assertEquals(emptyList<Any?>(), s.facts["registry"])
        assertFalse("an owner's note text is not in the result", s.json().contains("4417"))
        assertFalse("the DND and volume readings belong to a group that is off", s.facts.containsKey("volume"))
        assertFalse(s.facts.containsKey("dnd"))
    }

    /** `undo` on an owner entry is refused, says whose it is, and reverses nothing. */
    @Test fun undo_of_an_owner_entry_is_refused_and_leaves_it_armed() {
        val e = engine(MutableGrants(PhoneGrants.State.ALLOW))
        val timer = e.execute(PhoneTool.Call.Timer(300_000), t0, e0, PhoneEngine.Origin.OWNER)
        assertTrue(timer.ok)

        val refused = e.dispatch(mapOf("action" to "undo", "id" to timer.id!!), t0, e0)
        assertFalse(refused.ok)
        assertEquals(PhoneReason.NOT_GRANTED, refused.reason)
        assertTrue("the reason names the owner: ${refused.detail}", refused.detail!!.contains("owner"))
        assertTrue("nothing was cancelled", fake.cancelled.isEmpty())

        // The owner's own door is unrestricted, as before: their undo tap still works.
        val byOwner = e.undo(timer.id!!, t0, e0, PhoneEngine.Origin.OWNER)
        assertTrue(byOwner.json(), byOwner.ok)
        assertEquals(listOf(timer.id!!), fake.cancelled)
    }

    /**
     * Notes on: the mind's own note is listed and reversible, and the owner's note is not listed
     * even though the group is on — Ask is not Allow, and an owner action is the owner's.
     */
    @Test fun with_notes_on_the_minds_note_appears_and_the_owners_does_not() {
        val e = engine(MutableGrants(PhoneGrants.State.ASK))
        val owner = e.execute(
            PhoneTool.Call.Note("my own private note"),
            t0,
            e0,
            PhoneEngine.Origin.OWNER,
        )
        assertTrue(owner.ok)
        val mind = e.dispatch(mapOf("action" to "note", "text" to "milk"), t0, e0)
        assertTrue(mind.json(), mind.ok)

        val s = e.status(t0, e0)
        val notes = s.facts["notes"] as List<*>
        assertEquals(1, notes.size)
        assertEquals(mind.id, (notes.single() as Map<*, *>)["id"])
        assertFalse("the owner's note is not in the mind's answer", s.json().contains("private note"))

        val undo = e.dispatch(mapOf("action" to "undo", "id" to mind.id!!), t0, e0)
        assertTrue(undo.json(), undo.ok)
    }

    /** A grant turned Off after the fact shuts the mind's door on its own entry, without consuming it. */
    @Test fun a_minds_entry_cannot_be_undone_while_its_group_is_off() {
        val policy = MutableGrants(PhoneGrants.State.ALLOW)
        val e = engine(policy)
        val note = e.dispatch(mapOf("action" to "note", "text" to "milk"), t0, e0)
        assertTrue(note.ok)

        policy.state = PhoneGrants.State.OFF
        val refused = e.dispatch(mapOf("action" to "undo", "id" to note.id!!), t0, e0)
        assertFalse(refused.ok)
        assertEquals(PhoneReason.NOT_GRANTED, refused.reason)
        assertTrue(
            "the refusal names the group: ${refused.detail}",
            refused.detail!!.contains("Reminders and notes"),
        )
        assertEquals("the note is still there", 1, e.noteList().size)

        policy.state = PhoneGrants.State.ASK
        assertTrue("the refusal did not burn the undo", e.dispatch(
            mapOf("action" to "undo", "id" to note.id!!), t0, e0,
        ).ok)
    }

    /**
     * A restart restores each note behind the door it came through. A mind note whose group is Off
     * at that moment is not re-registered for the mind (H4) — but it is still the owner's to
     * reverse, so the owner's own view does not change.
     */
    @Test fun a_restored_mind_note_is_not_the_minds_to_undo_while_its_group_is_off() {
        val e = engine(MutableGrants(PhoneGrants.State.OFF))
        e.restoreNotes(
            listOf(PhoneEngine.Note("note-7", "milk", e0, PhoneEngine.Origin.MIND)),
            t0,
        )
        val refused = e.dispatch(mapOf("action" to "undo", "id" to "note-7"), t0, e0)
        assertFalse(refused.ok)
        assertEquals(PhoneReason.NOT_GRANTED, refused.reason)
        assertEquals("the note is still held", 1, e.noteList().size)

        val byOwner = e.undo("note-7", t0, e0, PhoneEngine.Origin.OWNER)
        assertTrue("the owner's undo still works after a restart", byOwner.ok)
        assertTrue(e.noteList().isEmpty())
    }
}
