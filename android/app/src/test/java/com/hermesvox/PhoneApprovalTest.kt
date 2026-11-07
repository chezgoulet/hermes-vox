package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The uninstall approval protocol, without a phone.
 *
 * What is pinned here is the whole safety story: the request never removes anything, the execution
 * only runs for the same canonical action+arguments after a real yes, every refusal burns the id,
 * voice uses the one allowlist, and the audit carries request → decision → execution.
 */
class PhoneApprovalTest {

    private class Fake : PhoneEngine.PhonePlatform {
        var labels = mapOf(
            "org.mozilla.firefox" to "Firefox",
            "com.example.other" to "Other",
            "com.example.third" to "Third",
            "com.example.fourth" to "Fourth",
        )
        val uninstalls = ArrayList<String>()
        var uninstallOutcome: PhoneEngine.PhonePlatform.Uninstall? = null

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
            PhoneEngine.PhonePlatform.Playback(known = false, playing = null, packageName = null, title = null)
        override fun deviceState(): PhoneEngine.PhonePlatform.DeviceSnapshot =
            PhoneEngine.PhonePlatform.DeviceSnapshot(
                batteryPercent = null, chargingSource = null, chargingStatus = null, screenOn = null,
                network = null, tailnetPresent = null, freeStorageBytes = null, mediaVolume = null,
                dnd = null, timeZoneId = null, utcOffsetMinutes = null,
            )
        override fun clip(text: String): PhoneEngine.PhonePlatform.ClipWrite =
            PhoneEngine.PhonePlatform.ClipWrite.Done

        override fun appLabel(packageName: String): String? = labels[packageName]
        override fun uninstall(packageName: String): PhoneEngine.PhonePlatform.Uninstall {
            uninstalls.add(packageName)
            return uninstallOutcome ?: PhoneEngine.PhonePlatform.Uninstall.Confirming(packageName)
        }
    }

    private class FakeSurface(var canRaise: Boolean = true) : PhoneApproval.Surface {
        val raised = ArrayList<PhoneApproval.Card>()
        val withdrawn = ArrayList<String>()
        override fun raise(card: PhoneApproval.Card): Boolean {
            if (!canRaise) return false
            raised.add(card)
            return true
        }
        override fun withdraw(card: PhoneApproval.Card) {
            withdrawn.add(card.id)
        }
    }

    private class AppsAtAsk : PhoneGrants.Source {
        override fun state(group: PhoneGrants.Group): PhoneGrants.State =
            if (group.id == "apps") PhoneGrants.State.ASK else PhoneGrants.State.OFF
    }

    private val pkg = "org.mozilla.firefox"
    private val other = "com.example.other"
    private val t0 = 1_000_000L
    private val e0 = 1_700_000_000_000L
    private val fake = Fake()
    private val surface = FakeSurface()
    private val audit = ArrayList<PhoneAudit.Entry>()

    private fun engine(): PhoneEngine = PhoneEngine(
        fake,
        grants = AppsAtAsk(),
        audit = { result, args, at -> audit.add(PhoneAudit.entry(at, args, result)) },
        approvalSurface = surface,
        auditRow = { audit.add(it) },
    )

    private fun request(packageName: String = pkg, at: Long = e0): PhoneResult =
        engine().dispatch(
            linkedMapOf("action" to "uninstall", "package" to packageName),
            t0,
            at,
        )

    private fun execute(id: String, packageName: String = pkg, at: Long = e0 + 1_000): PhoneResult =
        engine().dispatch(
            linkedMapOf("action" to "uninstall", "package" to packageName, "approval" to id),
            t0,
            at,
        )

    @Test fun request_raises_a_card_and_removes_nothing() {
        val e = engine()
        val r = e.dispatch(linkedMapOf("action" to "uninstall", "package" to pkg), t0, e0)
        assertFalse(r.ok)
        assertEquals(PhoneReason.APPROVAL, r.reason)
        assertEquals("ap-1", r.facts["approval_id"])
        assertEquals("uninstall Firefox", r.facts["summary"])
        assertEquals(e0 + PhoneApproval.TIMEOUT_MS, r.facts["expires_at"])
        assertEquals("Your agent wants to remove Firefox. Approve?", surface.raised.single().line)
        assertTrue("nothing may be removed on the request step", fake.uninstalls.isEmpty())
        assertEquals("approval-requested", audit.first().outcome)
    }

    @Test fun approve_tap_then_identical_arguments_executes_and_audits_three_rows() {
        val e = engine()
        val asked = e.dispatch(linkedMapOf("action" to "uninstall", "package" to pkg), t0, e0)
        val id = asked.facts["approval_id"] as String
        assertTrue(e.answerApproval(id, "once", "tap", e0 + 1_000))

        val ran = e.dispatch(
            linkedMapOf("action" to "uninstall", "package" to pkg, "approval" to id),
            t0,
            e0 + 2_000,
        )
        assertTrue(ran.ok)
        assertEquals(listOf(pkg), fake.uninstalls)
        assertEquals(true, ran.facts["platform_confirmation"])
        assertEquals("Firefox", ran.facts["label"])

        assertEquals(
            listOf("approval-requested", "approved", "done"),
            audit.map { it.outcome },
        )
        assertEquals("tap", audit[1].arguments["via"])
        assertEquals(pkg, audit[0].arguments["package"])
        assertEquals("Firefox", audit[0].arguments["label"])
        assertEquals(pkg, audit[2].arguments["package"])
        assertEquals("Firefox", audit[2].arguments["label"])
    }

    @Test fun deny_refuses_with_approval_denied_and_performs_nothing() {
        val e = engine()
        val id = e.dispatch(linkedMapOf("action" to "uninstall", "package" to pkg), t0, e0)
            .facts["approval_id"] as String
        assertTrue(e.answerApproval(id, "deny", "tap", e0 + 1_000))

        val r = e.dispatch(
            linkedMapOf("action" to "uninstall", "package" to pkg, "approval" to id),
            t0,
            e0 + 2_000,
        )
        assertEquals(PhoneApproval.Reason.DENIED, r.reason)
        assertTrue(fake.uninstalls.isEmpty())
        val again = e.dispatch(
            linkedMapOf("action" to "uninstall", "package" to pkg, "approval" to id),
            t0,
            e0 + 3_000,
        )
        assertEquals("a denial burns the id", PhoneApproval.Reason.UNKNOWN, again.reason)
    }

    @Test fun expiry_refuses_and_a_late_approve_cannot_revive_it() {
        val e = engine()
        val id = e.dispatch(linkedMapOf("action" to "uninstall", "package" to pkg), t0, e0)
            .facts["approval_id"] as String
        val late = e0 + PhoneApproval.TIMEOUT_MS + 1

        assertFalse(e.answerApproval(id, "once", "tap", late))
        val r = e.dispatch(
            linkedMapOf("action" to "uninstall", "package" to pkg, "approval" to id),
            t0,
            late,
        )
        assertEquals(PhoneApproval.Reason.EXPIRED, r.reason)
        assertTrue(fake.uninstalls.isEmpty())
        val again = e.dispatch(
            linkedMapOf("action" to "uninstall", "package" to pkg, "approval" to id),
            t0,
            late + 1,
        )
        assertEquals("expiry burns the id", PhoneApproval.Reason.UNKNOWN, again.reason)
    }

    @Test fun a_different_package_is_approval_mismatch_and_nothing_is_removed() {
        val e = engine()
        val id = e.dispatch(linkedMapOf("action" to "uninstall", "package" to pkg), t0, e0)
            .facts["approval_id"] as String
        assertTrue(e.answerApproval(id, "once", "tap", e0 + 1_000))

        val r = e.dispatch(
            linkedMapOf("action" to "uninstall", "package" to other, "approval" to id),
            t0,
            e0 + 2_000,
        )
        assertEquals(PhoneApproval.Reason.MISMATCH, r.reason)
        assertTrue("the approved package must not be the one removed", fake.uninstalls.isEmpty())
        val again = e.dispatch(
            linkedMapOf("action" to "uninstall", "package" to other, "approval" to id),
            t0,
            e0 + 3_000,
        )
        assertEquals("a mismatch burns the id", PhoneApproval.Reason.UNKNOWN, again.reason)
    }

    @Test fun an_id_used_twice_is_approval_unknown() {
        val e = engine()
        val id = e.dispatch(linkedMapOf("action" to "uninstall", "package" to pkg), t0, e0)
            .facts["approval_id"] as String
        assertTrue(e.answerApproval(id, "once", "tap", e0 + 1_000))
        assertTrue(
            e.dispatch(
                linkedMapOf("action" to "uninstall", "package" to pkg, "approval" to id),
                t0,
                e0 + 2_000,
            ).ok
        )
        val again = e.dispatch(
            linkedMapOf("action" to "uninstall", "package" to pkg, "approval" to id),
            t0,
            e0 + 3_000,
        )
        assertEquals(PhoneApproval.Reason.UNKNOWN, again.reason)
        assertEquals(1, fake.uninstalls.size)
    }

    @Test fun voice_yes_approves_a_hedge_reasks_and_silence_denies() {
        val e = engine()
        val id = e.dispatch(linkedMapOf("action" to "uninstall", "package" to pkg), t0, e0)
            .facts["approval_id"] as String

        assertEquals(PhoneApproval.Voice.Reask, e.approvalVoice("I'm not sure", e0 + 1_000))
        assertEquals("a hedge is not an answer", id, e.currentApproval()?.id)
        assertEquals(PhoneApproval.Voice.Unheard, e.approvalVoice("maybe later", e0 + 2_000))
        assertEquals("still not approved", id, e.currentApproval()?.id)

        assertEquals(
            PhoneApproval.Voice.Answered("once", "voice"),
            e.approvalVoice("yes", e0 + 3_000),
        )
        val ran = e.dispatch(
            linkedMapOf("action" to "uninstall", "package" to pkg, "approval" to id),
            t0,
            e0 + 4_000,
        )
        assertTrue(ran.ok)
        assertEquals("voice", audit.first { it.outcome == "approved" }.arguments["via"])
    }

    @Test fun silence_at_expiry_is_a_denial() {
        val e = engine()
        val id = e.dispatch(linkedMapOf("action" to "uninstall", "package" to pkg), t0, e0)
            .facts["approval_id"] as String
        val r = e.dispatch(
            linkedMapOf("action" to "uninstall", "package" to pkg, "approval" to id),
            t0,
            e0 + PhoneApproval.TIMEOUT_MS,
        )
        assertEquals(PhoneApproval.Reason.EXPIRED, r.reason)
        assertTrue(audit.any { it.outcome == "expired" })
        assertTrue(fake.uninstalls.isEmpty())
    }

    @Test fun one_card_at_a_time_three_wait_and_the_fourth_is_busy() {
        val e = engine()
        val first = e.dispatch(linkedMapOf("action" to "uninstall", "package" to pkg), t0, e0)
            .facts["approval_id"] as String
        val second = e.dispatch(linkedMapOf("action" to "uninstall", "package" to other), t0, e0)
            .facts["approval_id"] as String
        val third = e.dispatch(
            linkedMapOf("action" to "uninstall", "package" to "com.example.third"),
            t0,
            e0,
        )
        assertEquals(PhoneReason.APPROVAL, third.reason)
        val fourth = e.dispatch(
            linkedMapOf("action" to "uninstall", "package" to "com.example.fourth"),
            t0,
            e0,
        )
        assertEquals(PhoneApproval.Reason.BUSY, fourth.reason)
        assertEquals("only the head card is raised", listOf("ap-1"), surface.raised.map { it.id })

        assertTrue(e.answerApproval(first, "deny", "tap", e0 + 1_000))
        assertEquals("the queued card is raised next", listOf("ap-1", "ap-2"), surface.raised.map { it.id })
        assertEquals(second, e.currentApproval()?.id)
    }

    @Test fun no_surface_is_unavailable_not_a_silent_timeout() {
        val e = PhoneEngine(
            fake,
            grants = AppsAtAsk(),
            approvalSurface = FakeSurface(canRaise = false),
        )
        val r = e.dispatch(linkedMapOf("action" to "uninstall", "package" to pkg), t0, e0)
        assertEquals(PhoneReason.UNAVAILABLE, r.reason)
        assertTrue(fake.uninstalls.isEmpty())
    }

    @Test fun canonicalisation_ignores_key_order_and_whitespace_but_not_the_package() {
        val a = PhoneApproval.canonical(
            "uninstall",
            linkedMapOf("action" to "uninstall", "package" to pkg),
        )
        val b = PhoneApproval.canonical(
            "uninstall",
            linkedMapOf("approval" to "ap-1", "package" to "  $pkg  ", "action" to "uninstall"),
        )
        assertEquals(a, b)
        assertEquals(PhoneApproval.digest(a), PhoneApproval.digest(b))

        val c = PhoneApproval.canonical(
            "uninstall",
            linkedMapOf("action" to "uninstall", "package" to other),
        )
        assertNotEquals(a, c)
        assertNotEquals(PhoneApproval.digest(a), PhoneApproval.digest(c))
    }

    @Test fun a_package_name_is_a_name_not_a_path() {
        assertEquals(pkg, PhoneApproval.packageName(" $pkg "))
        assertNull(PhoneApproval.packageName("../org.mozilla.firefox"))
        assertNull(PhoneApproval.packageName("org/mozilla/firefox"))
        assertNull(PhoneApproval.packageName("firefox"))
    }
}
