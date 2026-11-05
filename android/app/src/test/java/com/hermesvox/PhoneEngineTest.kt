package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #421 steps 3–4: the phone's actions as the mind sees them — a result per action, an undo per
 * reversible action, the tier table, and the refusal path.
 *
 * The property that matters most is the last section: **a result with `ok:true` is only ever
 * produced after the platform said it did the thing**, and a refusal never carries an `id`, a
 * `state` or any other success fact. That is the false-action-claim guard's premise applied to the
 * phone's own claims, and it is asserted structurally rather than by reading the text.
 */
class PhoneEngineTest {

    /** A phone that records what it was asked to do, and can be told to refuse. */
    private class Fake : PhoneEngine.PhonePlatform {
        var timerArms = 0
        var timerFail = false
        var cancelled = ArrayList<String>()
        var cancelResult = true
        var dndGranted = true
        var dnd: Boolean? = false
        var dndSets = ArrayList<Boolean>()
        var dndRefuse = false
        var volume: Int? = 30
        var volumeSets = ArrayList<Int>()
        var volumeRefuse = false
        val notes = LinkedHashMap<String, String>()
        val mediaKeys = ArrayList<PhoneEngine.PhonePlatform.MediaKey>()
        var mediaRefuse = false
        var playback = PhoneEngine.PhonePlatform.Playback(
            known = false, playing = null, packageName = null, title = null,
        )

        override fun startTimer(id: String, durationMs: Long, atEpochMs: Long): Boolean {
            timerArms++
            return !timerFail
        }

        override fun startAlarm(id: String, hour: Int, minute: Int, atEpochMs: Long): Boolean {
            timerArms++
            return !timerFail
        }

        override fun cancelTimer(id: String): Boolean {
            cancelled.add(id)
            return cancelResult
        }

        override fun dndGranted(): Boolean = dndGranted

        override fun currentDnd(): Boolean? = dnd

        override fun setDnd(on: Boolean): Boolean {
            if (dndRefuse) return false
            dndSets.add(on)
            dnd = on
            return true
        }

        override fun currentVolume(): Int? = volume

        override fun setVolume(percent: Int): Int? {
            if (volumeRefuse) return null
            volumeSets.add(percent)
            volume = percent
            return percent
        }

        override fun adjustVolume(direction: Int): Int? {
            if (volumeRefuse) return null
            val from = volume ?: return null
            val to = (from + direction * 7).coerceIn(0, 100)   // one stream step is ~7% on most phones
            volumeSets.add(to)
            volume = to
            return to
        }

        override fun saveNote(id: String, text: String, atEpochMs: Long): Boolean {
            notes[id] = text
            return true
        }

        override fun deleteNote(id: String): Boolean = notes.remove(id) != null

        override fun mediaKey(key: PhoneEngine.PhonePlatform.MediaKey): Boolean {
            mediaKeys.add(key)
            return !mediaRefuse
        }

        override fun playback(): PhoneEngine.PhonePlatform.Playback = playback
    }

    private val fake = Fake()

    /**
     * The engine under test is handed a policy; the *store* is what keeps Tier 1/2 at Ask. A raw
     * source that says Allow for a Tier 1 group is normalised to Ask by the gate, so the tier-1
     * tests below drive [PhoneEngine.execute] — the path an approved card will use — rather than
     * pretending the MCP wire can grant Allow where the design does not.
     */
    private val grants = PhoneGrants.Source { PhoneGrants.State.ALLOW }
    private val engine = PhoneEngine(fake, grants = grants)
    private val t0 = 1_000_000L        // elapsed millis
    private val e0 = 1_700_000_000_000L // epoch millis

    private fun call(arguments: Map<String, Any?>, nowElapsed: Long = t0, nowEpoch: Long = e0) =
        engine.dispatch(arguments, nowElapsed, nowEpoch)

    private fun timer(ms: Long = 300_000) = call(mapOf("action" to "timer", "duration_ms" to ms))

    // ---- timer and alarm ----

    @Test fun a_timer_returns_the_id_and_the_time_it_has_left() {
        val r = timer(300_000)
        assertTrue(r.json(), r.ok)
        assertEquals("timer-1", r.id)
        assertEquals(300_000L, r.facts["remaining_ms"])
        assertEquals(1, fake.timerArms)
        assertTrue("the result names its own undo", r.json().contains("\"undo\":{\"action\":\"undo\",\"id\":\"timer-1\"}"))
    }

    @Test fun a_timer_that_the_phone_refused_is_not_reported_as_set() {
        fake.timerFail = true
        val r = timer()
        assertFalse(r.ok)
        assertEquals(PhoneReason.UNAVAILABLE, r.reason)
        assertNull("a refusal never carries an id", r.id)
        assertFalse(r.toMap().containsKey("remaining_ms"))
        assertTrue("the registry must not list a timer that was never armed", engine.registry(t0, e0).isEmpty())
    }

    @Test fun an_alarm_resolves_to_the_next_occurrence_and_says_when() {
        // 2023-11-14 22:13:20 UTC — "07:30" is tomorrow morning's.
        val now = 1_700_000_000_000L
        val r = call(mapOf("action" to "alarm", "at" to "07:30"), nowEpoch = now)
        assertTrue(r.json(), r.ok)
        assertEquals("07:30", r.facts["at"])
        val inMs = r.facts["in_ms"] as Long
        assertTrue("must be less than a day away, got $inMs", inMs in 1..86_400_000L)
        assertTrue(engine.registry(t0, now).contains("alarm(at=07:30)"))
    }

    @Test fun a_fired_timer_leaves_the_registry_and_its_undo_expires() {
        val r = timer(60_000)
        assertTrue(r.ok)
        engine.onFired("timer-1")
        assertTrue(engine.registry(t0 + 61_000, e0 + 61_000).isEmpty())
        val undo = call(mapOf("action" to "undo", "id" to "timer-1"), nowElapsed = t0 + 61_000, nowEpoch = e0 + 61_000)
        assertFalse(undo.ok)
        assertEquals(PhoneReason.EXPIRED, undo.reason)
    }

    // ---- dnd ----

    @Test fun dnd_without_the_policy_grant_is_named_as_a_permission_and_nothing_is_set() {
        fake.dndGranted = false
        val r = call(mapOf("action" to "dnd", "on" to true))
        assertFalse(r.ok)
        assertEquals(PhoneReason.PERMISSION, r.reason)
        assertNull(r.id)
        assertTrue("the phone must not touch the filter it cannot change", fake.dndSets.isEmpty())
        assertFalse("a refusal never claims the new state", r.toMap().containsKey("on"))
        assertTrue("the refusal names the special access", r.detail!!.contains("do-not-disturb access"))
    }

    @Test fun dnd_reports_the_state_in_effect_and_undo_restores_the_previous_one() {
        fake.dnd = false
        val r = call(mapOf("action" to "dnd", "on" to true))
        assertTrue(r.json(), r.ok)
        assertEquals(true, r.facts["on"])
        assertEquals(false, r.facts["previous"])
        assertEquals(listOf(true), fake.dndSets)

        val undo = call(mapOf("action" to "undo", "id" to r.id!!))
        assertTrue(undo.json(), undo.ok)
        assertEquals("dnd", undo.facts["undone"])
        assertEquals(listOf(true, false), fake.dndSets)
    }

    @Test fun a_second_dnd_supersedes_the_first_undo() {
        val first = call(mapOf("action" to "dnd", "on" to true))
        val second = call(mapOf("action" to "dnd", "on" to false))
        assertTrue(first.ok && second.ok)
        val undo = call(mapOf("action" to "undo", "id" to first.id!!))
        assertFalse(undo.ok)
        assertEquals(PhoneReason.EXPIRED, undo.reason)
    }

    @Test fun a_dnd_change_that_did_not_take_is_a_failure() {
        fake.dndRefuse = true
        val r = call(mapOf("action" to "dnd", "on" to false))
        assertFalse(r.ok)
        assertEquals(PhoneReason.UNAVAILABLE, r.reason)
    }

    // ---- volume ----

    @Test fun volume_sets_and_reports_the_level_actually_in_effect() {
        fake.volume = 30
        val r = call(mapOf("action" to "volume", "level" to 55))
        assertTrue(r.json(), r.ok)
        assertEquals(55, r.facts["level"])
        assertEquals(30, r.facts["previous"])
        assertEquals(listOf(55), fake.volumeSets)
        assertTrue(engine.registry(t0, e0).contains("volume=55%"))
    }

    @Test fun a_volume_step_is_one_stream_step_and_needs_a_readable_level() {
        fake.volume = null
        val r = call(mapOf("action" to "volume", "direction" to "up"))
        assertFalse(r.ok)
        assertEquals(PhoneReason.UNAVAILABLE, r.reason)
        assertTrue(fake.volumeSets.isEmpty())
    }

    @Test fun a_volume_refusal_says_so_and_changes_nothing() {
        fake.volumeRefuse = true
        val r = call(mapOf("action" to "volume", "level" to 10))
        assertFalse(r.ok)
        assertNull(r.id)
    }

    @Test fun volume_undo_restores_the_previous_level() {
        fake.volume = 20
        val r = call(mapOf("action" to "volume", "level" to 80))
        assertTrue(r.ok)
        val undo = call(mapOf("action" to "undo", "id" to r.id!!))
        assertTrue(undo.json(), undo.ok)
        assertEquals(listOf(80, 20), fake.volumeSets)
        assertEquals(20, fake.volume)
    }

    // ---- note (tier 1) ----

    @Test fun a_note_is_saved_reported_and_undoable() {
        val r = engine.execute(PhoneTool.Call.Note("plumber Thursday 9am"), t0, e0)
        assertTrue(r.json(), r.ok)
        assertEquals(PhoneTiers.REPORTED, r.tier)
        assertEquals("plumber Thursday 9am", r.facts["text"])
        assertEquals(true, r.facts["reported_next_turn"])
        assertEquals("plumber Thursday 9am", fake.notes[r.id])
        assertTrue("the context block reports the note by id", engine.registry(t0, e0).contains("note(id=${r.id})"))

        val undo = call(mapOf("action" to "undo", "id" to r.id!!))
        assertTrue(undo.ok)
        assertEquals("note", undo.facts["undone"])
        assertTrue(fake.notes.isEmpty())
    }

    @Test fun a_note_outlives_the_process_it_was_made_in() {
        val r = engine.execute(PhoneTool.Call.Note("milk"), t0, e0)
        val restored = PhoneEngine(Fake())
        restored.restoreNotes(listOf(PhoneEngine.Note(r.id!!, "milk", e0)), t0)
        assertTrue(restored.registry(t0, e0).contains("note(id=note-1)"))
        // A new note must not collide with the restored id.
        val next = restored.execute(PhoneTool.Call.Note("eggs"), t0, e0)
        assertEquals("note-2", next.id)
    }

    // ---- undo ----

    @Test fun undo_of_a_timer_cancels_it_through_the_platform() {
        val r = timer(120_000)
        assertTrue(r.ok)
        val undo = call(mapOf("action" to "undo", "id" to r.id!!))
        assertTrue(undo.json(), undo.ok)
        assertEquals(listOf("timer-1"), fake.cancelled)
        assertEquals("cancelled", undo.facts["state"])
        assertTrue(engine.registry(t0, e0).isEmpty())
    }

    @Test fun undo_of_an_unknown_id_is_a_refusal_not_a_claim() {
        val r = call(mapOf("action" to "undo", "id" to "timer-99"))
        assertFalse(r.ok)
        assertEquals(PhoneReason.EXPIRED, r.reason)
        assertNull(r.id)
    }

    @Test fun an_undo_the_platform_refused_does_not_claim_to_have_undone_it() {
        val r = timer(120_000)
        fake.cancelResult = false
        val undo = call(mapOf("action" to "undo", "id" to r.id!!))
        assertFalse(undo.ok)
        assertEquals(PhoneReason.UNAVAILABLE, undo.reason)
        assertFalse(undo.toMap().containsKey("undone"))
    }

    // ---- tiers and refusals ----

    @Test fun the_tier_table_is_the_decided_one() {
        for (a in listOf("timer", "alarm", "dnd", "volume", "status", "undo")) {
            assertEquals("$a must be tier 0", PhoneTiers.INSTANT, PhoneTiers.of(a))
        }
        assertEquals(PhoneTiers.REPORTED, PhoneTiers.of("note"))
        for (a in listOf("share", "message", "call", "email", "delete", "purchase", "launch", "send")) {
            assertEquals("$a must need approval", PhoneTiers.APPROVAL, PhoneTiers.of(a))
            assertFalse("$a must never be executed by the phone", PhoneTiers.executable(a))
        }
        assertNull(PhoneTiers.of("fly"))
    }

    @Test fun a_tier_two_action_is_refused_and_names_the_approval_path() {
        val r = call(mapOf("action" to "message", "text" to "hi"))
        assertFalse(r.ok)
        assertEquals(PhoneTiers.APPROVAL, r.tier)
        assertEquals(PhoneReason.APPROVAL, r.reason)
        assertNull(r.id)
        assertTrue("it must say approval, not a generic failure", r.detail!!.contains("approval"))
    }

    @Test fun an_unknown_action_lists_what_the_phone_can_do() {
        val r = call(mapOf("action" to "fly"))
        assertFalse(r.ok)
        assertEquals(PhoneReason.UNKNOWN, r.reason)
        assertTrue(r.detail!!.contains("timer"))
        assertTrue(r.detail!!.contains("status"))
    }

    @Test fun bad_arguments_are_described_not_executed() {
        val cases = listOf(
            mapOf<String, Any?>("action" to "timer"),
            mapOf<String, Any?>("action" to "timer", "duration_ms" to 10),
            mapOf<String, Any?>("action" to "timer", "duration_ms" to 86_400_001L),
            mapOf<String, Any?>("action" to "alarm", "at" to "25:00"),
            mapOf<String, Any?>("action" to "alarm", "at" to "7.30"),
            mapOf<String, Any?>("action" to "dnd"),
            mapOf<String, Any?>("action" to "volume"),
            mapOf<String, Any?>("action" to "volume", "level" to 101),
            mapOf<String, Any?>("action" to "undo"),
            mapOf<String, Any?>(),
        )
        for (c in cases) {
            val r = call(c)
            assertFalse("$c must be refused", r.ok)
            assertEquals("$c", PhoneReason.INVALID, r.reason)
        }
        assertEquals("nothing may be armed by a bad call", 0, fake.timerArms)
    }

    // ---- discovery ----

    @Test fun status_reports_the_action_list_the_tiers_and_what_is_running() {
        timer(300_000)
        engine.execute(PhoneTool.Call.Note("call the vet"), t0, e0)
        val s = engine.status(t0 + 60_000, e0 + 60_000)
        assertTrue(s.json(), s.ok)
        assertEquals(PhoneTool.offeredActions(grants), s.facts["offered"])
        val running = s.facts["running"] as List<*>
        val entry = running.first() as Map<*, *>
        assertEquals("timer-1", entry["id"])
        assertEquals("timer", entry["kind"])
        assertEquals(240_000L, entry["remaining_ms"])
        assertTrue((s.facts["registry"] as List<*>).contains("timer(remaining=4m00s)"))
        assertTrue((s.facts["notes"] as List<*>).isNotEmpty())
    }

    @Test fun status_is_the_same_registry_the_presence_context_reports() {
        timer(60_000)
        val s = engine.status(t0, e0)
        assertEquals(engine.registry(t0, e0), s.facts["registry"])
    }

    // ---- the honesty invariant, asserted structurally ----

    @Test fun no_refusal_ever_carries_a_success_fact() {
        fake.dndGranted = false
        fake.timerFail = true
        fake.volumeRefuse = true
        val refusals = listOf(
            call(mapOf("action" to "timer", "duration_ms" to 1_000)),
            call(mapOf("action" to "dnd", "on" to true)),
            call(mapOf("action" to "volume", "level" to 30)),
            call(mapOf("action" to "undo", "id" to "nope")),
            call(mapOf("action" to "message", "text" to "hi")),
            call(mapOf("action" to "fly")),
            call(mapOf("action" to "timer")),
        )
        for (r in refusals) {
            assertFalse(r.ok)
            assertNull("no id on a refusal: ${r.json()}", r.id)
            assertNotNull("a refusal must say why: ${r.json()}", r.reason)
            val map = r.toMap()
            assertFalse(map.containsKey("undo"))
            assertFalse("no success facts on a refusal: ${r.json()}", map.containsKey("remaining_ms"))
            assertTrue("a refusal must not carry success facts: ${r.json()}", r.facts.isEmpty())
            assertTrue(map.keys.all { it in setOf("ok", "action", "tier", "reason", "detail") })
        }
    }

    @Test fun a_result_never_uses_the_word_error_as_a_key() {
        // Hermes classifies a tool result whose text parses to a JSON object containing `error` as a
        // server application error. Refusals must be readable reasons, not breaker strikes.
        fake.dndGranted = false
        val texts = listOf(
            timer(1_000).json(),
            call(mapOf("action" to "dnd", "on" to true)).json(),
            call(mapOf("action" to "message")).json(),
            call(mapOf("action" to "fly")).json(),
            call(mapOf("action" to "status")).json(),
        )
        for (t in texts) assertFalse(t, Regex("\"error\"").containsMatchIn(t))
    }
}
