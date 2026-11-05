package com.hermesvox

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #apps-and-dismiss: `notification_dismiss` is call-scoped, not undoable, and reports an app and a
 * count — never a title, a body or an id.
 *
 * The rules this file exists to keep:
 *
 *  * by `id` from a summary of this call, or by `app` to dismiss all of that app's;
 *  * with no live call it refuses `unavailable`, naming the listener's lifetime exactly as
 *    `notifications` does;
 *  * Vox's own notifications are never dismissed;
 *  * the result and the audit row carry the app and the count, and nothing from the notification's
 *    content or its opaque listener id;
 *  * `undoable:false` and a sentence saying it cannot be restored.
 */
class PhoneNotificationsDismissTest {

    private val dismissed = ArrayList<String>()
    private var accept = true

    private fun seed() {
        PhoneNotifications.record("com.messages", "Messages", "m1", "Code 123", "Your code is 123", 1_000L)
        PhoneNotifications.record("com.messages", "Messages", "m2", "Dinner?", "Are you coming?", 2_000L)
        PhoneNotifications.record("com.mail", "Mail", "e1", "Invoice", "Attached is the invoice", 3_000L)
        PhoneNotifications.record("com.slack", "Slack", "s1", "Standup", "Standup in five", 4_000L)
    }

    private fun summarise(): PhoneNotifications.Summary =
        PhoneNotifications.summary() ?: error("a call is live in this test")

    private fun idOf(app: String): String =
        summarise().apps.first { it.app == app }.ids.first()

    @Before fun aCallIsLive() {
        PhoneNotifications.attach()
        PhoneNotifications.setDismisser { key ->
            dismissed.add(key)
            accept
        }
    }

    @After fun theCallEnds() {
        PhoneNotifications.setDismisser(null)
        PhoneNotifications.detach()
    }

    // ---- by id ----

    @Test fun by_id_dismisses_one_notification_and_reports_its_app_and_count() {
        seed()
        val id = idOf("Mail")

        val r = PhoneNotifications.dismiss(id = id)

        assertTrue(r.json(), r.ok)
        assertEquals("Mail", r.facts["app"])
        assertEquals(1, r.facts["count"])
        assertEquals(PhoneTiers.REPORTED, r.tier)
        assertEquals(false, r.facts["undoable"])
        assertTrue(
            "the reply says it cannot be undone: ${r.json()}",
            (r.facts["note"] as String).contains("cannot be restored"),
        )
        assertEquals(listOf("e1"), dismissed)
        assertEquals(3, summarise().count)
        assertTrue("Mail is gone from the next summary", summarise().apps.none { it.app == "Mail" })
    }

    @Test fun by_id_refuses_an_id_no_summary_of_this_call_has_shown() {
        seed()
        // The record exists, but no summary has shown its id yet.
        val neverShown = PhoneNotifications.dismiss(id = "n1")
        assertFalse(neverShown.ok)
        assertEquals(PhoneReason.UNKNOWN_ID, neverShown.reason)
        assertTrue(dismissed.isEmpty())

        summarise()
        val nonsense = PhoneNotifications.dismiss(id = "n999")
        assertEquals(PhoneReason.UNKNOWN_ID, nonsense.reason)
        assertTrue(dismissed.isEmpty())
    }

    // ---- by app ----

    @Test fun by_app_dismisses_all_of_that_app_and_counts_them() {
        seed()

        val r = PhoneNotifications.dismiss(app = "Messages")

        assertTrue(r.json(), r.ok)
        assertEquals("Messages", r.facts["app"])
        assertEquals(2, r.facts["count"])
        assertEquals(listOf("m1", "m2"), dismissed)
        assertEquals(2, summarise().count)
        assertTrue(summarise().apps.none { it.app == "Messages" })
    }

    @Test fun the_app_match_is_case_insensitive_and_accepts_the_package() {
        seed()
        val byLabel = PhoneNotifications.dismiss(app = "mAiL")
        assertTrue(byLabel.ok)
        assertEquals("Mail", byLabel.facts["app"])

        seed()
        val byPackage = PhoneNotifications.dismiss(app = "com.slack")
        assertTrue(byPackage.ok)
        assertEquals("Slack", byPackage.facts["app"])
    }

    @Test fun an_app_with_no_notification_is_unknown_and_dismisses_nothing() {
        seed()
        summarise()
        val r = PhoneNotifications.dismiss(app = "News")
        assertFalse(r.ok)
        assertEquals(PhoneReason.UNKNOWN_ID, r.reason)
        assertTrue(r.detail!!.contains("News"))
        assertTrue(dismissed.isEmpty())
    }

    // ---- scope ----

    @Test fun vox_s_own_notification_is_never_dismissed() {
        PhoneNotifications.record(
            packageName = "com.hermesvox",
            app = "Hermes Vox",
            key = "vox-1",
            title = "Voice call",
            text = "Listening",
            postedAt = 9_000L,
            own = true,
        )

        val r = PhoneNotifications.dismiss(app = "Hermes Vox")

        assertFalse(r.ok)
        assertEquals(PhoneReason.UNKNOWN_ID, r.reason)
        assertTrue("the listener was never asked to cancel Vox itself", dismissed.isEmpty())
        assertEquals(0, summarise().count)
    }

    // ---- lifetime ----

    @Test fun with_no_live_call_it_refuses_unavailable_naming_the_listener() {
        PhoneNotifications.detach()

        val r = PhoneNotifications.dismiss(id = "n1")

        assertFalse(r.ok)
        assertEquals(PhoneReason.UNAVAILABLE, r.reason)
        assertEquals(PhoneNotifications.CLOSED_DETAIL, r.detail)
        assertTrue(r.detail!!.contains("only open during a call"))
        assertTrue("nothing is cancelled without a call", dismissed.isEmpty())
    }

    @Test fun a_live_call_with_no_bound_listener_refuses_unavailable_too() {
        seed()
        PhoneNotifications.setDismisser(null)

        val r = PhoneNotifications.dismiss(app = "Mail")

        assertFalse(r.ok)
        assertEquals(PhoneReason.UNAVAILABLE, r.reason)
        assertEquals(PhoneNotifications.NOT_BOUND_DETAIL, r.detail)
    }

    @Test fun a_platform_refusal_is_unavailable_and_keeps_the_record() {
        seed()
        accept = false

        val r = PhoneNotifications.dismiss(app = "Mail")

        assertFalse(r.ok)
        assertEquals(PhoneReason.UNAVAILABLE, r.reason)
        assertTrue(r.detail!!.contains("did not dismiss"))
        assertEquals(4, summarise().count)
    }

    @Test fun one_of_each_or_the_call_is_invalid() {
        seed()
        summarise()

        val both = PhoneNotifications.dismiss(id = "n1", app = "Messages")
        assertEquals(PhoneReason.INVALID, both.reason)
        val neither = PhoneNotifications.dismiss()
        assertEquals(PhoneReason.INVALID, neither.reason)
        assertTrue(dismissed.isEmpty())
    }

    // ---- the result and the audit carry nothing else ----

    @Test fun the_result_never_carries_a_title_a_body_or_an_id() {
        seed()
        val id = idOf("Mail")
        val r = PhoneNotifications.dismiss(id = id)
        assertTrue(r.ok)

        val json = r.json()
        for (secret in listOf("Invoice", "Attached is the invoice", "title", "text", id)) {
            assertFalse("the result leaked '$secret': $json", json.contains(secret))
        }
        assertNull(r.facts["title"])
        assertNull(r.facts["text"])
        assertFalse("a dismissal offers no undo handle: $json", json.contains("\"undo\""))
        assertTrue("it says not undoable: $json", json.contains("\"undoable\":false"))
    }

    @Test fun the_audit_row_is_the_app_and_the_count_only() {
        seed()
        val r = PhoneNotifications.dismiss(app = "Messages")

        val args = PhoneNotifications.auditArguments(r)
        assertEquals(mapOf("app" to "Messages", "notification_count" to "2"), args)

        val row = PhoneAudit.entry(1_700_000_000_000L, args, r)
        assertEquals("notification_dismiss", row.action)
        assertEquals("done", row.outcome)
        assertEquals(args, row.arguments)

        val all = row.arguments.values.joinToString(" ")
        for (secret in listOf("Dinner", "Are you coming", "m1", "m2", "title", "text")) {
            assertFalse("the audit leaked '$secret': $all", all.contains(secret))
        }
    }

    @Test fun a_refused_audit_row_never_carries_the_id_it_was_given() {
        seed()
        val r = PhoneNotifications.dismiss(id = "n1") // never summarised
        assertEquals(PhoneReason.UNKNOWN_ID, r.reason)
        assertEquals(emptyMap<String, Any?>(), PhoneNotifications.auditArguments(r))

        val unknownApp = PhoneNotifications.dismiss(app = "News")
        assertEquals(
            mapOf("app" to "News", "notification_count" to "0"),
            PhoneNotifications.auditArguments(unknownApp),
        )
    }

    @Test fun detaching_drops_the_board_so_the_next_call_dismisses_nothing() {
        seed()
        val id = idOf("Mail")
        PhoneNotifications.detach()
        PhoneNotifications.attach()

        val r = PhoneNotifications.dismiss(id = id)
        assertFalse(r.ok)
        assertEquals(PhoneReason.UNKNOWN_ID, r.reason)
        assertTrue(dismissed.isEmpty())
    }
}
