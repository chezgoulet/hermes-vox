package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        override fun startTimer(id: String, durationMs: Long, atEpochMs: Long): Boolean {
            timerArms++
            return true
        }
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
        assertTrue(
            "the first timer is still running and cancellable",
            e.registry(t0 + 1_000, e0 + 1_000).any { it.startsWith("timer(") },
        )
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
}
