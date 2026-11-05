package com.hermesvox

import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `file_offer` is an export, not a read. The file card shows the name, type and size; the second
 * call must be the same file; expiry burns the id; and an approval answered on a locked phone is
 * refused, because the lock screen must never be a place where the owner's content is handed over.
 */
class PhoneFileApprovalTest {

    private class Fake : PhoneEngine.PhonePlatform {
        val folder = PhoneEngine.PhonePlatform.GrantedFolder("Download")
        var info: PhoneEngine.PhonePlatform.FileInfo =
            PhoneEngine.PhonePlatform.FileInfo.Ready("report.pdf", "application/pdf", 240_000)
        var handoff: PhoneEngine.PhonePlatform.FileHandoff =
            PhoneEngine.PhonePlatform.FileHandoff.Ready(
                name = "report.pdf",
                mime = "application/pdf",
                bytes = 240_000,
                sha256 = "abc123",
                url = "http://100.64.0.1:9/token",
                expiresAtSec = 1_700_000_300L,
            )
        val offers = ArrayList<String>()

        override fun folders(): List<PhoneEngine.PhonePlatform.GrantedFolder> = listOf(folder)

        override fun fileInfo(
            folder: PhoneEngine.PhonePlatform.GrantedFolder,
            name: String,
        ): PhoneEngine.PhonePlatform.FileInfo = info

        override fun fileOffer(
            folder: PhoneEngine.PhonePlatform.GrantedFolder,
            name: String,
        ): PhoneEngine.PhonePlatform.FileHandoff {
            offers.add(name)
            return handoff
        }

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
        override fun clip(text: String): Boolean = true
    }

    private class Surface(var canRaise: Boolean = true) : PhoneApproval.Surface {
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

    private val filesAtAsk = PhoneGrants.Source { group ->
        if (group.id == "files") PhoneGrants.State.ASK else PhoneGrants.State.OFF
    }
    private val t0 = 1_000_000L
    private val e0 = 1_700_000_000_000L
    private val fake = Fake()
    private val surface = Surface()

    private fun engine(unlocked: PhoneApproval.Unlocked = PhoneApproval.Unlocked.ALWAYS) =
        PhoneEngine(
            fake,
            grants = filesAtAsk,
            approvalSurface = surface,
            unlocked = unlocked,
        )

    private fun ask(e: PhoneEngine = engine(), name: String = "report.pdf"): PhoneResult =
        e.dispatch(mapOf("action" to "file_offer", "name" to name), t0, e0)

    private fun run(
        e: PhoneEngine,
        id: String,
        name: String = "report.pdf",
        at: Long = e0 + 2_000,
    ): PhoneResult = e.dispatch(
        mapOf("action" to "file_offer", "name" to name, "approval" to id),
        t0,
        at,
    )

    @Test fun offering_a_file_asks_first_and_names_the_file_type_and_size() {
        val e = engine()
        val r = ask(e)

        assertFalse("the request may not export anything", r.ok)
        assertEquals(PhoneReason.APPROVAL, r.reason)
        assertEquals(PhoneTiers.APPROVAL, r.tier)
        assertEquals("ap-1", r.facts["approval_id"])
        assertEquals(e0 + PhoneApproval.TIMEOUT_MS, r.facts["expires_at"])
        assertEquals("offer report.pdf (PDF, 240 KB)", r.facts["summary"])
        assertEquals(
            "Your agent wants to read report.pdf (PDF, 240 KB). Approve?",
            surface.raised.single().line,
        )
        assertTrue("nothing was staged or served before the answer", fake.offers.isEmpty())
    }

    @Test fun approving_the_same_file_executes_only_on_the_second_call() {
        val e = engine()
        val id = ask(e).facts["approval_id"] as String

        assertTrue("the card is answered by the owner", e.answerApproval(id, "once", "tap", e0 + 1_000))
        assertTrue("the answer alone exports nothing", fake.offers.isEmpty())

        val r = run(e, id)
        assertTrue(r.json(), r.ok)
        assertEquals(listOf("report.pdf"), fake.offers)
        assertEquals("http://100.64.0.1:9/token", r.facts["url"])
        assertEquals("abc123", r.facts["sha256"])
        assertFalse("the bytes are fetched, not embedded", r.facts.containsKey("text"))
    }

    @Test fun a_different_file_is_approval_mismatch_and_nothing_is_exported() {
        val e = engine()
        val id = ask(e).facts["approval_id"] as String
        assertTrue(e.answerApproval(id, "once", "tap", e0 + 1_000))

        val r = run(e, id, name = "notes.txt")
        assertEquals(PhoneApproval.Reason.MISMATCH, r.reason)
        assertTrue("the approved file is the only one that may be served", fake.offers.isEmpty())
        assertTrue("a mismatch burns the id", run(e, id).reason == PhoneApproval.Reason.UNKNOWN)
        assertTrue(fake.offers.isEmpty())
    }

    @Test fun expiry_burns_the_id_and_a_late_approve_is_refused() {
        val e = engine()
        val id = ask(e).facts["approval_id"] as String
        val late = e0 + PhoneApproval.TIMEOUT_MS + 1

        assertFalse("an expired card cannot be approved", e.answerApproval(id, "once", "tap", late))
        val r = run(e, id, at = late)
        assertEquals(PhoneApproval.Reason.EXPIRED, r.reason)
        assertTrue(fake.offers.isEmpty())
        assertEquals("expiry burns the id", PhoneApproval.Reason.UNKNOWN, run(e, id, at = late + 1).reason)
        assertTrue(fake.offers.isEmpty())
    }

    @Test fun a_locked_phone_cannot_approve_and_says_why() {
        val e = engine(unlocked = PhoneApproval.Unlocked { false })
        val id = ask(e).facts["approval_id"] as String

        assertFalse("the locked yes is not recorded", e.answerApproval(id, "once", "tap", e0 + 1_000))
        val r = run(e, id)
        assertEquals(PhoneApproval.Reason.LOCKED, r.reason)
        assertTrue("the agent is told why", r.detail!!.contains("locked"))
        assertTrue("nothing was exported", fake.offers.isEmpty())
        assertTrue("the card left the lock screen", id in surface.withdrawn)
    }

    @Test fun a_missing_or_oversized_file_is_refused_before_a_card_is_raised() {
        fake.info = PhoneEngine.PhonePlatform.FileInfo.Missing
        val missing = ask()
        assertEquals(PhoneReason.UNAVAILABLE, missing.reason)
        assertNull("no card for a file that is not there", missing.facts["approval_id"])
        assertTrue(surface.raised.isEmpty())

        fake.info = PhoneEngine.PhonePlatform.FileInfo.TooBig(120_000_000)
        val tooBig = ask()
        assertEquals(PhoneReason.TOO_BIG, tooBig.reason)
        assertNull(tooBig.facts["approval_id"])
        assertTrue(surface.raised.isEmpty())
        assertTrue(fake.offers.isEmpty())
    }

    @Test fun the_notification_builder_is_private_not_just_the_rendered_notification() {
        val card = PhoneApproval.Card(
            id = "ap-1",
            action = PhoneApproval.FILE_ACTION,
            subject = "report.pdf",
            label = "report.pdf",
            detail = "PDF, 240 KB",
            canonical = "file_offer\nname=report.pdf",
            digest = "x",
            raisedAtMs = e0,
            expiresAtMs = e0 + PhoneApproval.TIMEOUT_MS,
        )
        assertEquals(
            NotificationCompat.VISIBILITY_PRIVATE,
            PhoneApprovalNotice.Builder(card.line).visibility,
        )
        assertEquals(
            "the card's own notice is private too",
            NotificationCompat.VISIBILITY_PRIVATE,
            PhoneApprovalNotice.of(card).visibility,
        )
        assertEquals(
            "the locked notice names nothing that was being approved",
            NotificationCompat.VISIBILITY_PRIVATE,
            PhoneApprovalNotice.locked().visibility,
        )
    }
}
