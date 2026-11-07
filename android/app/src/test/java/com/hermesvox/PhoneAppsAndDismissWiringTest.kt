package com.hermesvox

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #apps-and-dismiss: the two new actions through the shared engine, where the wiring lives.
 *
 * The lane's own tests already prove [PhoneNotifications.dismiss] and [AppsAccess.result] in
 * isolation. This file proves the seam: **each action honours Off, Ask and Allow exactly like
 * everything else** — Ask raises one card and runs on the second call with its id, Allow runs with
 * no card, Off is `not_granted` — and that the audit keeps only an app count for `apps` and only
 * the app plus a count for `notification_dismiss`.
 *
 * A read that asks is still a read: the `apps` card says "list the apps on your phone", which is
 * what the owner is agreeing to, and nothing is queried before they answer.
 */
class PhoneAppsAndDismissWiringTest {

    private class Fake : PhoneEngine.PhonePlatform {
        var appsAsked = 0
        var usage = false
        val installed = listOf(
            AppsAccess.App("com.messages", "Messages"),
            AppsAccess.App("com.mail", "Mail"),
            AppsAccess.App("com.slack", "Slack"),
        )

        override fun launchableApps(): List<AppsAccess.App>? {
            appsAsked++
            return installed
        }

        override fun usageAccess(): Boolean = usage

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
            PhoneEngine.PhonePlatform.Playback(
                known = false,
                playing = null,
                packageName = null,
                title = null,
            )
        override fun deviceState(): PhoneEngine.PhonePlatform.DeviceSnapshot =
            PhoneEngine.PhonePlatform.DeviceSnapshot(
                batteryPercent = null,
                chargingSource = null,
                chargingStatus = null,
                screenOn = null,
                network = null,
                tailnetPresent = null,
                freeStorageBytes = null,
                mediaVolume = null,
                dnd = null,
                timeZoneId = null,
                utcOffsetMinutes = null,
            )
        override fun clip(text: String): PhoneEngine.PhonePlatform.ClipWrite =
            PhoneEngine.PhonePlatform.ClipWrite.Done
    }

    private class Surface : PhoneApproval.Surface {
        val raised = ArrayList<PhoneApproval.Card>()
        override fun raise(card: PhoneApproval.Card): Boolean {
            raised.add(card)
            return true
        }
    }

    private class Policy(private val states: Map<String, PhoneGrants.State>) : PhoneGrants.Source {
        override fun state(group: PhoneGrants.Group): PhoneGrants.State =
            states[group.id] ?: PhoneGrants.State.OFF
    }

    private data class Audited(val result: PhoneResult, val args: Map<String, Any?>)

    private val fake = Fake()
    private val surface = Surface()
    private val audits = ArrayList<Audited>()
    private val dismissed = ArrayList<String>()
    private val t0 = 1_000_000L
    private val e0 = 1_700_000_000_000L

    private fun only(id: String, state: PhoneGrants.State): PhoneGrants.Source =
        Policy(mapOf(id to state))

    private fun engine(grants: PhoneGrants.Source): PhoneEngine =
        PhoneEngine(
            fake,
            grants = grants,
            approvalSurface = surface,
            audit = { result, args, _ -> audits.add(Audited(result, args)) },
        )

    @Before fun aCallIsLive() {
        PhoneNotifications.attach()
        PhoneNotifications.setDismisser { key ->
            dismissed.add(key)
            true
        }
    }

    @After fun theCallEnds() {
        PhoneNotifications.setDismisser(null)
        PhoneNotifications.detach()
    }

    /** Two Messages notifications and one Mail, and a summary so their ids are summarisable. */
    private fun seed(): String {
        PhoneNotifications.record(
            "com.messages", "Messages", "m1", "Code 123", "Your code is 123", 1_000L,
        )
        PhoneNotifications.record(
            "com.messages", "Messages", "m2", "Dinner?", "Are you coming?", 2_000L,
        )
        PhoneNotifications.record(
            "com.mail", "Mail", "e1", "Invoice", "Attached is the invoice", 3_000L,
        )
        return PhoneNotifications.summary()!!
            .apps.first { it.app == "Mail" }
            .ids.first()
    }

    // ---- notification_dismiss ----

    @Test fun notification_dismiss_at_ask_raises_a_card_then_dismisses_by_id() {
        val id = seed()
        val e = engine(only("notifications", PhoneGrants.State.ASK))
        val request = mapOf("action" to "notification_dismiss", "id" to id)

        val asked = e.dispatch(request, t0, e0)

        assertEquals(PhoneReason.APPROVAL, asked.reason)
        assertEquals("notification_dismiss", asked.action)
        assertEquals(1, surface.raised.size)
        assertEquals("dismiss one notification on this phone", surface.raised.single().phrase)
        assertEquals(
            "Your agent wants to dismiss one notification on this phone. Approve?",
            surface.raised.single().line,
        )
        assertTrue("the request dismisses nothing", dismissed.isEmpty())

        val approval = asked.facts["approval_id"] as String
        assertNotNull(approval)
        assertTrue(e.answerApproval(approval, "once", "tap", e0 + 1_000))
        assertTrue("the yes alone dismisses nothing", dismissed.isEmpty())

        val ran = e.dispatch(request + ("approval" to approval), t0, e0 + 2_000)

        assertTrue(ran.json(), ran.ok)
        assertEquals("Mail", ran.facts["app"])
        assertEquals(1, ran.facts["count"])
        assertEquals(PhoneTiers.REPORTED, ran.tier)
        assertEquals(listOf("e1"), dismissed)
    }

    @Test fun notification_dismiss_by_app_at_ask_names_the_app_on_the_card() {
        seed()
        val e = engine(only("notifications", PhoneGrants.State.ASK))
        val request = mapOf("action" to "notification_dismiss", "app" to "Messages")

        val asked = e.dispatch(request, t0, e0)

        assertEquals(PhoneReason.APPROVAL, asked.reason)
        assertEquals(1, surface.raised.size)
        assertEquals(
            "dismiss all notifications from Messages on this phone",
            surface.raised.single().phrase,
        )
        assertEquals(
            "Your agent wants to dismiss all notifications from Messages on this phone. Approve?",
            surface.raised.single().line,
        )
        assertTrue("the request dismisses nothing", dismissed.isEmpty())

        val approval = asked.facts["approval_id"] as String
        assertTrue(e.answerApproval(approval, "once", "tap", e0 + 1_000))
        val ran = e.dispatch(request + ("approval" to approval), t0, e0 + 2_000)

        assertTrue(ran.json(), ran.ok)
        assertEquals("Messages", ran.facts["app"])
        assertEquals(2, ran.facts["count"])
        assertEquals(listOf("m1", "m2"), dismissed)
    }

    @Test fun notification_dismiss_at_allow_runs_with_no_card() {
        val id = seed()
        val e = engine(only("notifications", PhoneGrants.State.ALLOW))

        val ran = e.dispatch(
            mapOf("action" to "notification_dismiss", "id" to id),
            t0,
            e0,
        )

        assertTrue(ran.json(), ran.ok)
        assertEquals(1, ran.facts["count"])
        assertEquals(listOf("e1"), dismissed)
        assertTrue("Allow must not raise a card", surface.raised.isEmpty())
    }

    @Test fun notification_dismiss_at_off_is_not_granted_and_dismisses_nothing() {
        val id = seed()
        val e = engine(only("notifications", PhoneGrants.State.OFF))

        val refused = e.dispatch(
            mapOf("action" to "notification_dismiss", "id" to id),
            t0,
            e0,
        )

        assertFalse(refused.ok)
        assertEquals(PhoneReason.NOT_GRANTED, refused.reason)
        assertTrue("Off raises no card", surface.raised.isEmpty())
        assertTrue("Off dismisses nothing", dismissed.isEmpty())
    }

    // ---- apps ----

    @Test fun apps_at_ask_raises_a_card_then_returns_the_list() {
        val e = engine(only("apps", PhoneGrants.State.ASK))

        val asked = e.dispatch(mapOf("action" to "apps"), t0, e0)

        assertEquals(PhoneReason.APPROVAL, asked.reason)
        assertEquals("apps", asked.action)
        assertEquals(1, surface.raised.size)
        assertEquals("list the apps on your phone", surface.raised.single().phrase)
        assertEquals(
            "Your agent wants to list the apps on your phone. Approve?",
            surface.raised.single().line,
        )
        assertEquals("the request reads nothing", 0, fake.appsAsked)

        val approval = asked.facts["approval_id"] as String
        assertTrue(e.answerApproval(approval, "once", "tap", e0 + 1_000))
        val ran = e.dispatch(mapOf("action" to "apps", "approval" to approval), t0, e0 + 2_000)

        assertTrue(ran.json(), ran.ok)
        assertEquals("apps", ran.action)
        assertEquals(3, ran.facts["total"])
        assertEquals(3, (ran.facts["apps"] as List<*>).size)
        assertEquals(1, fake.appsAsked)
    }

    @Test fun apps_at_allow_returns_the_list_with_no_card() {
        val e = engine(only("apps", PhoneGrants.State.ALLOW))

        val ran = e.dispatch(mapOf("action" to "apps"), t0, e0)

        assertTrue(ran.json(), ran.ok)
        assertEquals(3, ran.facts["total"])
        assertEquals(false, ran.facts["truncated"])
        assertEquals(listOf("Mail", "Messages", "Slack"), (ran.facts["apps"] as List<*>).map {
            (it as Map<*, *>)["label"]
        })
        assertEquals(1, fake.appsAsked)
        assertTrue("Allow must not raise a card", surface.raised.isEmpty())
    }

    // ---- the audit's allowlist ----

    @Test fun the_audit_records_apps_as_a_count_and_dismissal_as_app_and_count() {
        val apps = engine(only("apps", PhoneGrants.State.ALLOW))
        val appsResult = apps.dispatch(mapOf("action" to "apps"), t0, e0)
        assertTrue(appsResult.ok)
        val appsRow = PhoneAudit.entry(e0, audits.last().args, appsResult)
        assertEquals("apps", appsRow.action)
        assertEquals(mapOf("app_count" to "3"), appsRow.arguments)
        val appsText = appsRow.arguments.values.joinToString(" ")
        for (secret in listOf("Messages", "Mail", "Slack", "com.messages")) {
            assertFalse("the apps audit leaked '$secret'", appsText.contains(secret))
        }

        val id = seed()
        val dismiss = engine(only("notifications", PhoneGrants.State.ALLOW))
        val dismissResult = dismiss.dispatch(
            mapOf("action" to "notification_dismiss", "id" to id),
            t0,
            e0,
        )
        assertTrue(dismissResult.ok)
        assertEquals(
            "the dispatch hands the audit the allowlisted arguments, never the id",
            mapOf("app" to "Mail", "notification_count" to "1"),
            audits.last().args,
        )
        val dismissRow = PhoneAudit.entry(e0, audits.last().args, dismissResult)
        assertEquals("notification_dismiss", dismissRow.action)
        assertEquals("done", dismissRow.outcome)
        assertEquals(mapOf("app" to "Mail", "notification_count" to "1"), dismissRow.arguments)
        val dismissText = dismissRow.arguments.values.joinToString(" ")
        for (secret in listOf("Invoice", "Attached is the invoice", id)) {
            assertFalse("the dismissal audit leaked '$secret'", dismissText.contains(secret))
        }
    }
}
