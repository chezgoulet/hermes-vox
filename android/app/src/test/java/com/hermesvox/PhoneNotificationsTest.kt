package com.hermesvox

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #429 read lane: the notification board is call-scoped, a summary carries no content, and a
 * single notification is readable only through an id a summary already showed.
 *
 * The rules this file exists to keep:
 *
 *  * a summary is `count` and per-app `{app, count, latest_at}` plus opaque ids — **no title and
 *    no text** anywhere in the JSON;
 *  * `notification_read` is the deliberate second step, and refuses `unknown-id` for an id that
 *    was never summarised (including one that arrived after the summary);
 *  * Vox's own notifications are never listed;
 *  * with no live call both actions refuse `unavailable`, naming the listener's lifetime;
 *  * the audit keeps a count and an app, never a title, a body or an id.
 */
class PhoneNotificationsTest {

    private class Fake : PhoneEngine.PhonePlatform {
        var listenerGranted = true
        override fun notificationListenerGranted(): Boolean = listenerGranted
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
        override fun mediaKey(key: PhoneEngine.PhonePlatform.MediaKey): Boolean = true
        override fun playback(): PhoneEngine.PhonePlatform.Playback =
            PhoneEngine.PhonePlatform.Playback(false, null, null, null)
        override fun deviceState(): PhoneEngine.PhonePlatform.DeviceSnapshot =
            PhoneEngine.PhonePlatform.DeviceSnapshot(
                null, null, null, null, null, null, null, null, null, null, null,
            )
        override fun clip(text: String): PhoneEngine.PhonePlatform.ClipWrite =
            PhoneEngine.PhonePlatform.ClipWrite.Done
    }

    private val fake = Fake()
    private val t0 = 1_000_000L
    private val e0 = 1_700_000_000_000L
    private val entries = ArrayList<PhoneAudit.Entry>()

    private fun onlyNotifications() = PhoneGrants.Source { g ->
        if (g.id == "notifications") PhoneGrants.State.ALLOW else PhoneGrants.State.OFF
    }

    private fun engine(grants: PhoneGrants.Source = onlyNotifications()) = PhoneEngine(
        fake,
        grants = grants,
        audit = { result, args, at -> entries.add(PhoneAudit.entry(at, args, result)) },
    )

    private fun call(arguments: Map<String, Any?>) = engine().dispatch(arguments, t0, e0)

    private fun seed() {
        PhoneNotifications.record("com.messages", "Messages", "m1", "Code 123", "Your code is 123", 1_000L)
        PhoneNotifications.record("com.messages", "Messages", "m2", "Dinner?", "Are you coming?", 2_000L)
        PhoneNotifications.record("com.mail", "Mail", "e1", "Invoice", "Attached is the invoice", 3_000L)
        PhoneNotifications.record("com.slack", "Slack", "s1", "Standup", "Standup in five", 4_000L)
    }

    @Before fun aCallIsLive() {
        PhoneNotifications.attach()
    }

    @After fun theCallEnds() {
        PhoneNotifications.detach()
    }

    // ---- the summary ----

    @Test fun a_summary_counts_apps_and_carries_no_title_or_text() {
        seed()
        val r = call(mapOf("action" to "notifications"))
        assertTrue(r.json(), r.ok)
        assertEquals(4, r.facts["count"])
        assertEquals(true, r.facts["sensitive"])
        assertEquals(PhoneTiers.INSTANT, r.tier)

        val json = r.json()
        assertFalse("a summary has no title field: $json", json.contains("\"title\""))
        assertFalse("a summary has no text field: $json", json.contains("\"text\""))
        for (secret in listOf("Code 123", "Your code is", "Dinner?", "Invoice", "Standup")) {
            assertFalse("summary leaked '$secret': $json", json.contains(secret))
        }

        val apps = (r.facts["apps"] as List<*>).associate {
            val m = it as Map<*, *>
            (m["app"] as String) to m
        }
        assertEquals(2, (apps["Messages"] as Map<*, *>)["count"])
        assertEquals(1, (apps["Mail"] as Map<*, *>)["count"])
        assertEquals(1, (apps["Slack"] as Map<*, *>)["count"])
        assertEquals(2_000L, (apps["Messages"] as Map<*, *>)["latest_at"])
        assertEquals(2, ((apps["Messages"] as Map<*, *>)["ids"] as List<*>).size)
    }

    @Test fun a_summary_names_the_apps_in_count_order_and_keeps_the_time() {
        seed()
        val apps = (call(mapOf("action" to "notifications")).facts["apps"] as List<*>)
            .map { it as Map<*, *> }
        assertEquals(listOf("Messages", "Mail", "Slack"), apps.map { it["app"] })
        assertEquals(4_000L, apps.first { it["app"] == "Slack" }["latest_at"])
    }

    // ---- the second step ----

    @Test fun notification_read_returns_one_notification_by_a_summarised_id() {
        seed()
        call(mapOf("action" to "notifications"))
        val read = call(mapOf("action" to "notification_read", "id" to "n1"))
        assertTrue(read.json(), read.ok)
        assertEquals(true, read.facts["sensitive"])
        assertEquals("Messages", read.facts["app"])
        assertEquals(1_000L, read.facts["posted_at"])
        assertEquals("Code 123", read.facts["title"])
        assertEquals("Your code is 123", read.facts["text"])
    }

    @Test fun notification_read_refuses_an_id_no_summary_has_shown() {
        seed()
        // The id exists on the board, but no summary has shown it yet.
        val neverSummarised = call(mapOf("action" to "notification_read", "id" to "n1"))
        assertFalse(neverSummarised.ok)
        assertEquals(PhoneReason.UNKNOWN_ID, neverSummarised.reason)
        assertNull(neverSummarised.facts["title"])

        val nonsense = call(mapOf("action" to "notification_read", "id" to "n999"))
        assertEquals(PhoneReason.UNKNOWN_ID, nonsense.reason)
    }

    @Test fun a_notification_that_arrives_after_the_summary_needs_a_fresh_summary() {
        seed()
        call(mapOf("action" to "notifications"))
        PhoneNotifications.record("com.news", "News", "n1", "Headline", "Body", 5_000L)
        val guessed = call(mapOf("action" to "notification_read", "id" to "n5"))
        assertEquals(PhoneReason.UNKNOWN_ID, guessed.reason)
        call(mapOf("action" to "notifications"))
        val shown = call(mapOf("action" to "notification_read", "id" to "n5"))
        assertTrue(shown.json(), shown.ok)
        assertEquals("News", shown.facts["app"])
    }

    // ---- scope ----

    @Test fun vox_s_own_notifications_are_excluded() {
        PhoneNotifications.record(
            packageName = "com.hermesvox",
            app = "Hermes Vox",
            key = "vox-1",
            title = "Voice call",
            text = "Listening",
            postedAt = 9_000L,
            own = true,
        )
        val r = call(mapOf("action" to "notifications"))
        assertTrue(r.ok)
        assertEquals(0, r.facts["count"])
        assertEquals(emptyList<Any?>(), r.facts["apps"])
        assertEquals(PhoneReason.UNKNOWN_ID, call(mapOf("action" to "notification_read", "id" to "n1")).reason)
    }

    @Test fun removing_a_notification_removes_it_from_the_next_summary() {
        seed()
        assertEquals(4, call(mapOf("action" to "notifications")).facts["count"])
        PhoneNotifications.remove("m1")
        assertEquals(3, call(mapOf("action" to "notifications")).facts["count"])
    }

    // ---- lifetime ----

    @Test fun both_actions_refuse_when_no_call_is_live_naming_the_listener() {
        PhoneNotifications.detach()
        val summary = call(mapOf("action" to "notifications"))
        assertFalse(summary.ok)
        assertEquals(PhoneReason.UNAVAILABLE, summary.reason)
        assertTrue(summary.detail!!.contains("only open during a call"))

        val read = call(mapOf("action" to "notification_read", "id" to "n1"))
        assertFalse(read.ok)
        assertEquals(PhoneReason.UNAVAILABLE, read.reason)
        assertTrue(read.detail!!.contains("only open during a call"))
    }

    @Test fun without_the_system_listener_grant_both_actions_refuse_permission() {
        seed()
        fake.listenerGranted = false
        val summary = call(mapOf("action" to "notifications"))
        assertFalse(summary.ok)
        assertEquals(PhoneReason.PERMISSION, summary.reason)
        assertTrue(summary.detail!!.contains("notification-listener access"))

        val read = call(mapOf("action" to "notification_read", "id" to "n1"))
        assertFalse(read.ok)
        assertEquals(PhoneReason.PERMISSION, read.reason)
    }

    @Test fun the_call_lifetime_wins_over_a_missing_grant_so_the_reason_is_unavailable() {
        PhoneNotifications.detach()
        fake.listenerGranted = false
        assertEquals(PhoneReason.UNAVAILABLE, call(mapOf("action" to "notifications")).reason)
        assertEquals(
            PhoneReason.UNAVAILABLE,
            call(mapOf("action" to "notification_read", "id" to "n1")).reason,
        )
    }

    @Test fun detaching_drops_every_record_and_id_and_the_next_call_starts_empty() {
        seed()
        val ids = ((call(mapOf("action" to "notifications")).facts["apps"] as List<*>)
            .first() as Map<*, *>)["ids"] as List<*>
        val first = ids.first() as String

        PhoneNotifications.detach()
        assertEquals(PhoneReason.UNAVAILABLE, call(mapOf("action" to "notifications")).reason)

        PhoneNotifications.attach()
        assertEquals(0, call(mapOf("action" to "notifications")).facts["count"])
        assertEquals(PhoneReason.UNKNOWN_ID, call(mapOf("action" to "notification_read", "id" to first)).reason)
    }

    @Test fun attach_is_idempotent_so_a_warming_call_does_not_clear_the_board() {
        seed()
        PhoneNotifications.attach()
        assertEquals(4, call(mapOf("action" to "notifications")).facts["count"])
    }

    // ---- the audit ----

    @Test fun the_audit_keeps_a_count_and_an_app_never_a_title_text_or_id() {
        seed()
        call(mapOf("action" to "notifications"))
        call(mapOf("action" to "notification_read", "id" to "n1"))

        val summary = entries[0]
        assertEquals(mapOf("notification_count" to "4"), summary.arguments)
        val read = entries[1]
        assertEquals(mapOf("app" to "Messages", "notification_count" to "1"), read.arguments)

        val all = entries.flatMap { it.arguments.values }.joinToString(" ")
        for (secret in listOf("Code 123", "Your code", "n1", "title", "text")) {
            assertFalse("the audit leaked '$secret': $all", all.contains(secret))
        }
    }
}
