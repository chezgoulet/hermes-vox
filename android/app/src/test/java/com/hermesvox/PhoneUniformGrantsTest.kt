package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The uniform grant model, proved from the outside: **Off, Ask and Allow mean one thing for every
 * group, whatever its tier.**
 *
 * The defect this replaces was a word with three behaviours. Allow existed for Tier 0 only; a
 * Tier 1 group at Ask ran without asking and reported afterwards; a Tier 0 group at Ask was
 * withheld because it had no card. Now:
 *
 *  * **Off** — `not_granted`, nothing happens, nothing is armed or written.
 *  * **Ask** — a card naming the action and its arguments, answered by tap or voice; the same call
 *    with the returned id and **identical arguments** executes; different arguments are
 *    `approval_mismatch`; an expired or answered id is refused.
 *  * **Allow** — the action runs and **no card is raised**, for a Tier 0, Tier 1 or Tier 2 action.
 *
 * The surface is asserted, not only the reply: "it asked first" is a fact about a card being
 * raised, and "Allow does not ask" is a fact about no card being raised.
 */
class PhoneUniformGrantsTest {

    private class Fake : PhoneEngine.PhonePlatform {
        var timerArms = 0
        val notes = LinkedHashMap<String, String>()
        val uninstalls = ArrayList<String>()

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
        override fun saveNote(id: String, text: String, atEpochMs: Long): Boolean {
            notes[id] = text
            return true
        }
        override fun deleteNote(id: String): Boolean = notes.remove(id) != null
        override fun mediaKey(key: PhoneEngine.PhonePlatform.MediaKey): Boolean = true
        override fun playback(): PhoneEngine.PhonePlatform.Playback =
            PhoneEngine.PhonePlatform.Playback(known = false, playing = null, packageName = null, title = null)
        override fun deviceState(): PhoneEngine.PhonePlatform.DeviceSnapshot =
            PhoneEngine.PhonePlatform.DeviceSnapshot(
                batteryPercent = null, chargingSource = null, chargingStatus = null, screenOn = null,
                network = null, tailnetPresent = null, freeStorageBytes = null, mediaVolume = null,
                dnd = null, timeZoneId = null, utcOffsetMinutes = null,
            )
        override fun clip(text: String): PhoneEngine.PhonePlatform.ClipWrite =
            PhoneEngine.PhonePlatform.ClipWrite.Done

        override fun appLabel(packageName: String): String? =
            if (packageName == "org.mozilla.firefox") "Firefox" else null

        override fun uninstall(packageName: String): PhoneEngine.PhonePlatform.Uninstall {
            uninstalls.add(packageName)
            return PhoneEngine.PhonePlatform.Uninstall.Confirming(packageName)
        }
    }

    private class Surface : PhoneApproval.Surface {
        val raised = ArrayList<PhoneApproval.Card>()
        val withdrawn = ArrayList<String>()
        override fun raise(card: PhoneApproval.Card): Boolean {
            raised.add(card)
            return true
        }
        override fun withdraw(card: PhoneApproval.Card) {
            withdrawn.add(card.id)
        }
    }

    /** A policy the test can change between calls, exactly as the sheet writes it. */
    private class Policy(var all: PhoneGrants.State) : PhoneGrants.Source {
        override fun state(group: PhoneGrants.Group): PhoneGrants.State = all
    }

    private val fake = Fake()
    private val surface = Surface()
    private val t0 = 1_000_000L
    private val e0 = 1_700_000_000_000L

    private fun engine(grants: PhoneGrants.Source): PhoneEngine =
        PhoneEngine(fake, grants = grants, approvalSurface = surface)

    private fun askThenRun(
        e: PhoneEngine,
        request: Map<String, Any?>,
        execute: (String) -> Map<String, Any?>,
        at: Long = e0 + 1_000,
    ): PhoneResult {
        val asked = e.dispatch(request, t0, e0)
        assertEquals("the first call must ask", PhoneReason.APPROVAL, asked.reason)
        val id = asked.facts["approval_id"] as? String
        assertNotNull("the request must hand back an id: ${asked.json()}", id)
        assertTrue("the owner answers on the phone", e.answerApproval(id!!, "once", "tap", at))
        return e.dispatch(execute(id), t0, at + 1_000)
    }

    // ---- the three states, as the owner sets them ----

    /**
     * Every group accepts every state, and the state it reads back is the state that was set. This
     * is the whole defect in one assertion: there is no tier whose Allow is silently downgraded.
     */
    @Test fun every_group_can_be_set_to_each_state_and_reads_back_what_was_set() {
        for (group in PhoneGrants.GROUPS) {
            for (wanted in listOf(
                PhoneGrants.State.OFF,
                PhoneGrants.State.ASK,
                PhoneGrants.State.ALLOW,
            )) {
                val policy = Policy(PhoneGrants.State.OFF)
                policy.all = wanted
                assertEquals(
                    "${group.id} must read back ${wanted.id}",
                    wanted,
                    PhoneGrants.stateOf(policy, group),
                )
                assertTrue(
                    "${group.id} must offer ${wanted.id}",
                    wanted in PhoneGrants.statesFor(group.tier),
                )
            }
        }
    }

    // ---- Ask: a card, for every tier ----

    /** Tier 0 (`timer`): the card names the action and its duration; nothing is armed until yes. */
    @Test fun a_tier_zero_action_at_ask_raises_a_card_then_runs_on_the_second_call() {
        val e = engine(Policy(PhoneGrants.State.ASK))
        val asked = e.dispatch(mapOf("action" to "timer", "duration_ms" to 300_000L), t0, e0)

        assertEquals(PhoneReason.APPROVAL, asked.reason)
        assertEquals("timer", asked.action)
        assertEquals("ap-1", asked.facts["approval_id"])
        assertEquals("nothing is armed by the request", 0, fake.timerArms)
        assertEquals(1, surface.raised.size)
        assertEquals("set a timer for 5 minutes", surface.raised.single().phrase)
        assertEquals(
            "Your agent wants to set a timer for 5 minutes. Approve?",
            surface.raised.single().line,
        )

        val id = asked.facts["approval_id"] as String
        assertTrue(e.answerApproval(id, "once", "tap", e0 + 1_000))
        assertEquals("the yes alone arms nothing", 0, fake.timerArms)

        val ran = e.dispatch(mapOf("action" to "timer", "duration_ms" to 300_000L, "approval" to id), t0, e0 + 2_000)
        assertTrue(ran.json(), ran.ok)
        assertEquals("timer-1", ran.id)
        assertEquals(1, fake.timerArms)
    }

    /** Tier 1 (`note`): Ask used to run and report afterwards. It now waits for the card. */
    @Test fun a_tier_one_action_at_ask_raises_a_card_then_runs_on_the_second_call() {
        val e = engine(Policy(PhoneGrants.State.ASK))
        val asked = e.dispatch(mapOf("action" to "note", "text" to "milk"), t0, e0)

        assertEquals(PhoneReason.APPROVAL, asked.reason)
        assertEquals("nothing is written by the request", 0, fake.notes.size)
        assertEquals(1, surface.raised.size)
        assertEquals("keep a note (4 characters)", surface.raised.single().phrase)

        val id = asked.facts["approval_id"] as String
        assertTrue(e.answerApproval(id, "once", "voice", e0 + 1_000))
        assertEquals("the yes alone writes nothing", 0, fake.notes.size)

        val ran = e.dispatch(mapOf("action" to "note", "text" to "milk", "approval" to id), t0, e0 + 2_000)
        assertTrue(ran.json(), ran.ok)
        assertEquals("milk", fake.notes[ran.id])
    }

    /** Tier 2 (`uninstall`): the consequential action uses the same card, not a special path. */
    @Test fun a_tier_two_action_at_ask_raises_a_card_then_runs_on_the_second_call() {
        val e = engine(Policy(PhoneGrants.State.ASK))
        val asked = e.dispatch(mapOf("action" to "uninstall", "package" to "org.mozilla.firefox"), t0, e0)

        assertEquals(PhoneReason.APPROVAL, asked.reason)
        assertEquals("nothing is removed by the request", emptyList<String>(), fake.uninstalls)
        assertEquals(1, surface.raised.size)
        assertEquals("Your agent wants to remove Firefox. Approve?", surface.raised.single().line)

        val id = asked.facts["approval_id"] as String
        assertTrue(e.answerApproval(id, "once", "tap", e0 + 1_000))
        assertEquals("the yes alone removes nothing", emptyList<String>(), fake.uninstalls)

        val ran = e.dispatch(
            mapOf("action" to "uninstall", "package" to "org.mozilla.firefox", "approval" to id),
            t0,
            e0 + 2_000,
        )
        assertTrue(ran.json(), ran.ok)
        assertEquals(listOf("org.mozilla.firefox"), fake.uninstalls)
        assertEquals("Firefox", ran.facts["label"])
    }

    /** There is no allowlist of card-able actions: every group's first action raises one. */
    @Test fun every_group_at_ask_raises_a_card_for_its_first_action() {
        val calls = mapOf(
            "timers-alarms" to mapOf("action" to "timer", "duration_ms" to 60_000L),
            "dnd-volume" to mapOf("action" to "dnd", "on" to true),
            "notes" to mapOf("action" to "note", "text" to "milk"),
            "media" to mapOf("action" to "play"),
            "device-state" to mapOf("action" to "state"),
            "clipboard" to mapOf("action" to "clip_read"),
            "files" to mapOf("action" to "file_list"),
            "calendar" to mapOf("action" to "agenda"),
            "notifications" to mapOf("action" to "notifications"),
            "messages" to mapOf("action" to "message", "to" to "+15551234567", "text" to "hi"),
            "calls" to mapOf("action" to "dial", "to" to "+15551234567"),
            "apps" to mapOf("action" to "launch", "app" to "Firefox"),
            "contacts" to mapOf("action" to "contact", "name" to "Mom"),
        )
        assertEquals("every group must be covered", PhoneGrants.GROUPS.map { it.id }.toSet(), calls.keys)
        for ((id, call) in calls) {
            surface.raised.clear()
            val e = engine(PhoneGrants.Source { g ->
                if (g.id == id) PhoneGrants.State.ASK else PhoneGrants.State.OFF
            })
            val r = e.dispatch(call, t0, e0)
            assertEquals("$id at Ask must ask: ${r.json()}", PhoneReason.APPROVAL, r.reason)
            assertEquals("$id at Ask must raise exactly one card", 1, surface.raised.size)
            assertTrue(
                "$id's card must name the action: ${surface.raised.single().line}",
                surface.raised.single().line.startsWith("Your agent wants to "),
            )
        }
    }

    // ---- Allow: no card, for every tier ----

    @Test fun at_allow_a_tier_zero_action_runs_and_no_card_is_raised() {
        val e = engine(Policy(PhoneGrants.State.ALLOW))
        val r = e.dispatch(mapOf("action" to "timer", "duration_ms" to 60_000L), t0, e0)

        assertTrue(r.json(), r.ok)
        assertEquals("timer-1", r.id)
        assertEquals(1, fake.timerArms)
        assertTrue("Allow must not raise a card", surface.raised.isEmpty())
    }

    @Test fun at_allow_a_tier_one_action_runs_and_no_card_is_raised() {
        val e = engine(Policy(PhoneGrants.State.ALLOW))
        val r = e.dispatch(mapOf("action" to "note", "text" to "milk"), t0, e0)

        assertTrue(r.json(), r.ok)
        assertEquals("milk", fake.notes[r.id])
        assertTrue("Allow must not raise a card", surface.raised.isEmpty())
    }

    @Test fun at_allow_a_tier_two_action_runs_and_no_card_is_raised() {
        val e = engine(Policy(PhoneGrants.State.ALLOW))
        val r = e.dispatch(mapOf("action" to "uninstall", "package" to "org.mozilla.firefox"), t0, e0)

        assertTrue(r.json(), r.ok)
        assertEquals(listOf("org.mozilla.firefox"), fake.uninstalls)
        assertTrue("Allow on a consequential action is a real permission, not a card", surface.raised.isEmpty())
    }

    // ---- Off: nothing happens ----

    @Test fun at_off_it_is_not_granted_and_nothing_happens() {
        for (call in listOf<Map<String, Any?>>(
            mapOf("action" to "timer", "duration_ms" to 60_000L),
            mapOf("action" to "note", "text" to "milk"),
            mapOf("action" to "uninstall", "package" to "org.mozilla.firefox"),
        )) {
            val e = engine(Policy(PhoneGrants.State.OFF))
            val r = e.dispatch(call, t0, e0)
            assertFalse(r.ok)
            assertEquals("${r.action} at Off must be not_granted", PhoneReason.NOT_GRANTED, r.reason)
            assertNull(r.facts["approval_id"])
        }
        assertEquals("nothing armed", 0, fake.timerArms)
        assertEquals("nothing written", 0, fake.notes.size)
        assertEquals("nothing removed", emptyList<String>(), fake.uninstalls)
        assertTrue("Off raises no card", surface.raised.isEmpty())
    }

    // ---- the id is bound to its arguments, for every action ----

    @Test fun different_arguments_are_approval_mismatch_for_a_tier_zero_action() {
        val e = engine(Policy(PhoneGrants.State.ASK))
        val asked = e.dispatch(mapOf("action" to "timer", "duration_ms" to 300_000L), t0, e0)
        val id = asked.facts["approval_id"] as String
        assertTrue(e.answerApproval(id, "once", "tap", e0 + 1_000))

        val r = e.dispatch(mapOf("action" to "timer", "duration_ms" to 600_000L, "approval" to id), t0, e0 + 2_000)
        assertEquals(PhoneApproval.Reason.MISMATCH, r.reason)
        assertEquals("the approved duration is the only one that may run", 0, fake.timerArms)
        assertEquals(
            "a mismatch burns the id",
            PhoneApproval.Reason.UNKNOWN,
            e.dispatch(mapOf("action" to "timer", "duration_ms" to 300_000L, "approval" to id), t0, e0 + 3_000).reason,
        )
        assertEquals(0, fake.timerArms)
    }

    @Test fun different_arguments_are_approval_mismatch_for_a_tier_two_action() {
        val e = engine(Policy(PhoneGrants.State.ASK))
        val asked = e.dispatch(mapOf("action" to "uninstall", "package" to "org.mozilla.firefox"), t0, e0)
        val id = asked.facts["approval_id"] as String
        assertTrue(e.answerApproval(id, "once", "tap", e0 + 1_000))

        val r = e.dispatch(mapOf("action" to "uninstall", "package" to "com.example.other", "approval" to id), t0, e0 + 2_000)
        assertEquals(PhoneApproval.Reason.MISMATCH, r.reason)
        assertEquals(emptyList<String>(), fake.uninstalls)
    }

    @Test fun an_expired_id_is_refused_and_an_answered_id_cannot_be_reused() {
        val late = e0 + PhoneApproval.TIMEOUT_MS + 1
        val e = engine(Policy(PhoneGrants.State.ASK))
        val asked = e.dispatch(mapOf("action" to "timer", "duration_ms" to 60_000L), t0, e0)
        val id = asked.facts["approval_id"] as String

        val expired = e.dispatch(
            mapOf("action" to "timer", "duration_ms" to 60_000L, "approval" to id),
            t0,
            late,
        )
        assertEquals(PhoneApproval.Reason.EXPIRED, expired.reason)
        assertEquals(0, fake.timerArms)
        assertTrue("the expired card left the surface", id in surface.withdrawn)

        val second = engine(Policy(PhoneGrants.State.ASK))
        val again = second.dispatch(mapOf("action" to "timer", "duration_ms" to 60_000L), t0, e0)
            .facts["approval_id"] as String
        assertTrue(second.answerApproval(again, "once", "tap", e0 + 1_000))
        assertTrue(
            second.dispatch(
                mapOf("action" to "timer", "duration_ms" to 60_000L, "approval" to again),
                t0,
                e0 + 2_000,
            ).ok,
        )
        assertEquals(
            "an answered id is used once",
            PhoneApproval.Reason.UNKNOWN,
            second.dispatch(
                mapOf("action" to "timer", "duration_ms" to 60_000L, "approval" to again),
                t0,
                e0 + 3_000,
            ).reason,
        )
        assertEquals(1, fake.timerArms)
    }

    @Test fun a_denied_card_performs_nothing_for_any_tier() {
        for (call in listOf<Map<String, Any?>>(
            mapOf("action" to "timer", "duration_ms" to 60_000L),
            mapOf("action" to "note", "text" to "milk"),
        )) {
            val e = engine(Policy(PhoneGrants.State.ASK))
            val asked = e.dispatch(call, t0, e0)
            val id = asked.facts["approval_id"] as String
            assertTrue(e.answerApproval(id, "deny", "tap", e0 + 1_000))
            val r = e.dispatch(call + ("approval" to id), t0, e0 + 2_000)
            assertEquals(PhoneApproval.Reason.DENIED, r.reason)
        }
        assertEquals("nothing armed", 0, fake.timerArms)
        assertEquals("nothing written", 0, fake.notes.size)
    }

    /** A hedge is not a yes and silence is never one: the voice rule is unchanged for every card. */
    @Test fun a_hedge_is_not_an_answer_and_silence_is_never_yes() {
        val e = engine(Policy(PhoneGrants.State.ASK))
        val asked = e.dispatch(mapOf("action" to "timer", "duration_ms" to 60_000L), t0, e0)
        val id = asked.facts["approval_id"] as String

        assertEquals(PhoneApproval.Voice.Reask, e.approvalVoice("I'm not sure", e0 + 1_000))
        assertEquals("a hedge does not approve", id, e.currentApproval()?.id)
        assertEquals(PhoneApproval.Voice.Unheard, e.approvalVoice("maybe later", e0 + 2_000))
        assertEquals("still not approved", id, e.currentApproval()?.id)

        val silent = e.dispatch(
            mapOf("action" to "timer", "duration_ms" to 60_000L, "approval" to id),
            t0,
            e0 + PhoneApproval.TIMEOUT_MS,
        )
        assertEquals("silence expires, it never approves", PhoneApproval.Reason.EXPIRED, silent.reason)
        assertEquals(0, fake.timerArms)
    }

    /** A card the surface cannot raise is `unavailable`, not a two-minute silence that reads as no. */
    @Test fun an_action_that_cannot_raise_a_card_is_refused_rather_than_pretended() {
        val e = PhoneEngine(fake, grants = Policy(PhoneGrants.State.ASK))
        val r = e.dispatch(mapOf("action" to "timer", "duration_ms" to 60_000L), t0, e0)
        assertEquals(PhoneReason.UNAVAILABLE, r.reason)
        assertEquals(0, fake.timerArms)
        assertNull(r.facts["approval_id"])
    }
}
