package com.hermesvox

import java.io.File
import java.nio.file.Files
import com.hermesvox.core.JArr
import com.hermesvox.core.JObj
import com.hermesvox.core.JStr
import com.hermesvox.core.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #429 lane 2: the `files` and `calendar` groups.
 *
 * What this file is here to keep:
 *
 *  * **path safety is the point** — `..`, an absolute path, a name that resolves outside the tree
 *    and a symlink that leaves it are each refused before anything is read;
 *  * a file above the cap is **refused, never truncated**, and a binary is `not_text`;
 *  * without a granted folder both file actions refuse `no_folder` and name the way to give one;
 *  * without the calendar's runtime permission both actions refuse `permission` naming Android's
 *    exact string, and with it `agenda` and `event_create` work, with a real undo;
 *  * the action is advertised only while its group is on;
 *  * the audit keeps an event's title as its length, never the text.
 */
class PhoneFilesCalendarTest {

    /** A phone that answers what it is told, and records what it was asked. */
    private class Fake : PhoneEngine.PhonePlatform {
        var folders = emptyList<PhoneEngine.PhonePlatform.GrantedFolder>()
        var text: PhoneEngine.PhonePlatform.FileText =
            PhoneEngine.PhonePlatform.FileText.Missing
        var handoff: PhoneEngine.PhonePlatform.FileHandoff =
            PhoneEngine.PhonePlatform.FileHandoff.Missing
        var info: PhoneEngine.PhonePlatform.FileInfo =
            PhoneEngine.PhonePlatform.FileInfo.Missing
        var seenFile: Pair<String, String>? = null

        var calendarMissing: String? = null
        var calendarReadMissing: String? = null
        var calendarWriteMissing: String? = null
        var agendaRows: List<PhoneEngine.PhonePlatform.AgendaEvent>? = emptyList()
        var created: PhoneEngine.PhonePlatform.CreatedEvent? = null
        var createdArgs: List<Any?>? = null
        val deleted = ArrayList<String>()

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

        override fun folders(): List<PhoneEngine.PhonePlatform.GrantedFolder> = folders

        override fun fileText(
            folder: PhoneEngine.PhonePlatform.GrantedFolder,
            name: String,
        ): PhoneEngine.PhonePlatform.FileText {
            seenFile = folder.label to name
            return text
        }

        override fun fileInfo(
            folder: PhoneEngine.PhonePlatform.GrantedFolder,
            name: String,
        ): PhoneEngine.PhonePlatform.FileInfo = info

        override fun fileOffer(
            folder: PhoneEngine.PhonePlatform.GrantedFolder,
            name: String,
        ): PhoneEngine.PhonePlatform.FileHandoff {
            seenFile = folder.label to name
            return handoff
        }

        override fun calendarPermission(): String? = calendarMissing
        override fun calendarReadPermission(): String? = calendarReadMissing ?: calendarMissing
        override fun calendarWritePermission(): String? = calendarWriteMissing ?: calendarMissing
        override fun agenda(): List<PhoneEngine.PhonePlatform.AgendaEvent>? = agendaRows

        override fun createEvent(
            title: String,
            startLocal: String,
            durationMin: Int,
            calendar: String?,
        ): PhoneEngine.PhonePlatform.CreatedEvent? {
            createdArgs = listOf(title, startLocal, durationMin, calendar)
            return created
        }

        override fun deleteEvent(id: String): Boolean {
            deleted.add(id)
            return true
        }
    }

    private val fake = Fake()
    private val t0 = 1_000_000L
    private val e0 = 1_700_000_000_000L

    private fun only(id: String) = PhoneGrants.Source { g ->
        if (g.id == id) PhoneGrants.State.ASK else PhoneGrants.State.OFF
    }

    private fun engine(grants: PhoneGrants.Source) = PhoneEngine(fake, grants = grants)

    private fun call(
        arguments: Map<String, Any?>,
        grants: PhoneGrants.Source = only("files"),
        at: PhoneEngine = engine(grants),
    ) = at.dispatch(arguments, t0, e0)

    private val folder = PhoneEngine.PhonePlatform.GrantedFolder("primary:Download", "Download")

    /** The action enum the mind is actually handed, not a second reading of the grants. */
    private fun schemaEnum(grants: PhoneGrants.Source): List<String> {
        val schema = Json.parse(Json.encode(PhoneTool.schema(grants))) as JObj
        val input = schema["inputSchema"] as JObj
        val action = (input["properties"] as JObj)["action"] as JObj
        return (action["enum"] as JArr).items.map { (it as JStr).value }
    }

    // ---- path safety, without a phone ----

    @Test fun a_name_containing_dot_dot_is_refused_as_a_path() {
        for (name in listOf("..", "../secret.txt", "notes/../../secret", "a..b")) {
            assertNotNull("$name must be refused", PhoneFiles.badPath(name))
        }
    }

    @Test fun an_absolute_path_is_refused_as_a_path() {
        for (name in listOf("/sdcard/secret.txt", "\\Windows\\secret.txt", "C:\\secret.txt")) {
            assertNotNull("$name must be refused", PhoneFiles.badPath(name))
        }
    }

    @Test fun a_separator_uri_or_blank_name_is_refused() {
        for (name in listOf("", "   ", "folder/file.txt", "content://x/y")) {
            assertNotNull("$name must be refused", PhoneFiles.badPath(name))
        }
        assertNull("an ordinary file name is fine", PhoneFiles.badPath("notes.txt"))
    }

    @Test fun a_document_id_outside_the_granted_tree_is_not_contained() {
        assertTrue(PhoneFiles.contained("primary:Download", "primary:Download/notes.txt"))
        assertFalse(
            "a sibling tree is not the granted one",
            PhoneFiles.contained("primary:Download", "primary:Documents/notes.txt"),
        )
        assertFalse(
            "a nested document is not one child of the tree",
            PhoneFiles.contained("primary:Download", "primary:Download/sub/notes.txt"),
        )
        assertFalse(
            "dot-dot never passes a document id",
            PhoneFiles.contained("primary:Download", "primary:Download/../Documents/notes.txt"),
        )
    }

    @Test fun a_symlink_that_leaves_the_granted_folder_is_outside_it() {
        val root = Files.createTempDirectory("hv-root").toFile()
        val outside = Files.createTempDirectory("hv-outside").toFile()
        try {
            val inside = File(root, "notes.txt").apply { writeText("inside") }
            val secret = File(outside, "secret.txt").apply { writeText("outside") }
            val link = File(root, "link.txt")
            Files.createSymbolicLink(link.toPath(), secret.toPath())

            assertTrue("a real file in the tree is contained", PhoneFiles.canonicalWithin(root, inside))
            assertFalse("a link out of the tree is not", PhoneFiles.canonicalWithin(root, link))
            assertFalse(
                "and a path outside it is not",
                PhoneFiles.canonicalWithin(root, secret),
            )
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun text_is_strict_utf8_and_a_binary_is_not_text() {
        assertEquals("hello", PhoneFiles.asText("hello".toByteArray()))
        assertNull("a NUL byte is binary", PhoneFiles.asText(byteArrayOf(0, 1, 2)))
        assertNull("malformed UTF-8 is binary", PhoneFiles.asText(byteArrayOf(0xFF.toByte())))
    }

    // ---- files through the tool ----

    @Test fun no_granted_folder_refuses_no_folder_and_says_how_to_give_one() {
        fake.folders = emptyList()
        val r = call(mapOf("action" to "file_read", "name" to "notes.txt"))
        assertFalse(r.ok)
        assertEquals(PhoneReason.NO_FOLDER, r.reason)
        assertNull("nothing was read", fake.seenFile)
        assertTrue("the refusal says how to grant one", r.detail!!.contains("Phone tools"))

        val offer = call(mapOf("action" to "file_offer", "name" to "notes.txt"))
        assertEquals(PhoneReason.NO_FOLDER, offer.reason)
    }

    @Test fun file_read_returns_the_text_its_length_and_no_undo() {
        fake.folders = listOf(folder)
        fake.text = PhoneEngine.PhonePlatform.FileText.Found("notes.txt", 5, "hello")
        val r = call(mapOf("action" to "file_read", "name" to "notes.txt"))
        assertTrue(r.json(), r.ok)
        assertEquals("notes.txt", r.facts["name"])
        assertEquals("hello", r.facts["text"])
        assertEquals(5, r.facts["chars"])
        assertEquals(false, r.facts["undoable"])
        assertNull("a read has no undo", r.id)
        assertFalse("and no undo object", r.toMap().containsKey("undo"))
        assertEquals("a defaulted call means Downloads", folder.label to "notes.txt", fake.seenFile)
    }

    @Test fun an_over_cap_whole_file_says_to_read_it_in_windows() {
        fake.folders = listOf(folder)
        fake.text = PhoneEngine.PhonePlatform.FileText.TooBig(70_000)
        val r = call(mapOf("action" to "file_read", "name" to "big.txt"))
        assertFalse(r.ok)
        assertEquals(PhoneReason.TOO_BIG, r.reason)
        assertFalse("no text was returned", r.facts.containsKey("text"))
        assertFalse("and no length", r.facts.containsKey("chars"))
        assertTrue("the refusal states the cap", r.detail!!.contains("65536"))
        assertTrue("and says how to read it anyway", r.detail!!.contains("offset and length"))
    }

    @Test fun a_binary_file_is_refused_not_text() {
        fake.folders = listOf(folder)
        fake.text = PhoneEngine.PhonePlatform.FileText.NotText
        val r = call(mapOf("action" to "file_read", "name" to "photo.png"))
        assertFalse(r.ok)
        assertEquals(PhoneReason.NOT_TEXT, r.reason)
        assertFalse(r.facts.containsKey("text"))
    }

    @Test fun a_file_that_resolves_outside_the_tree_is_bad_path() {
        fake.folders = listOf(folder)
        fake.text = PhoneEngine.PhonePlatform.FileText.Outside
        val r = call(mapOf("action" to "file_read", "name" to "notes.txt"))
        assertFalse(r.ok)
        assertEquals(PhoneReason.BAD_PATH, r.reason)
        assertFalse(r.facts.containsKey("text"))
    }

    @Test fun a_name_that_is_a_path_never_reaches_the_platform() {
        fake.folders = listOf(folder)
        val r = call(mapOf("action" to "file_read", "name" to "../secret.txt"))
        assertFalse(r.ok)
        assertEquals(PhoneReason.BAD_PATH, r.reason)
        assertNull("the platform was never asked to look it up", fake.seenFile)
    }

    @Test fun file_offer_hands_back_the_bridge_capability_not_the_bytes() {
        fake.folders = listOf(folder)
        fake.info = PhoneEngine.PhonePlatform.FileInfo.Ready("notes.txt", "text/plain", 12)
        fake.handoff = PhoneEngine.PhonePlatform.FileHandoff.Ready(
            name = "notes.txt",
            mime = "text/plain",
            bytes = 12,
            sha256 = "abc123",
            url = "http://100.64.0.1:9/token",
            expiresAtSec = 1_700_000_300L,
        )
        val surface = Surface()
        val e = PhoneEngine(fake, grants = only("files"), approvalSurface = surface)
        val asked = e.dispatch(mapOf("action" to "file_offer", "name" to "notes.txt"), t0, e0)
        assertEquals(PhoneReason.APPROVAL, asked.reason)
        val id = asked.facts["approval_id"] as String
        assertTrue(e.answerApproval(id, "once", "tap", e0 + 1_000))

        val r = e.dispatch(
            mapOf("action" to "file_offer", "name" to "notes.txt", "approval" to id),
            t0,
            e0 + 2_000,
        )
        assertTrue(r.json(), r.ok)
        assertEquals("http://100.64.0.1:9/token", r.facts["url"])
        assertEquals("abc123", r.facts["sha256"])
        assertFalse("the artifact is fetched, not embedded", r.facts.containsKey("text"))
        assertNull("offering changes nothing", r.id)
    }

    private class Surface : PhoneApproval.Surface {
        val raised = ArrayList<PhoneApproval.Card>()
        override fun raise(card: PhoneApproval.Card): Boolean {
            raised.add(card)
            return true
        }
    }

    @Test fun the_file_actions_are_absent_until_files_is_on() {
        assertFalse("file_read is absent while Files is off", "file_read" in schemaEnum(PhoneGrants.ALL_OFF))
        assertFalse("file_offer is absent while Files is off", "file_offer" in schemaEnum(PhoneGrants.ALL_OFF))
        val on = schemaEnum(only("files"))
        assertTrue("file_read appears when Files is on", "file_read" in on)
        assertTrue("file_offer appears when Files is on", "file_offer" in on)
    }

    // ---- calendar through the tool ----

    @Test fun calendar_without_permission_refuses_permission_naming_the_exact_string() {
        fake.calendarMissing = android.Manifest.permission.READ_CALENDAR
        val r = call(mapOf("action" to "agenda"), grants = only("calendar"))
        assertFalse(r.ok)
        assertEquals(PhoneReason.PERMISSION, r.reason)
        assertTrue(
            "the refusal names Android's own permission",
            r.detail!!.contains("android.permission.READ_CALENDAR"),
        )
        assertNull("the platform's calendar was not written", fake.created)
        assertTrue("nothing was deleted", fake.deleted.isEmpty())
    }

    /**
     * The read is the owner's grant on its own. Denying WRITE_CALENDAR is a choice about creating
     * events; it must not take `agenda` away, because reading needs only READ_CALENDAR.
     */
    @Test fun agenda_needs_read_calendar_alone_not_the_write_grant() {
        fake.calendarReadMissing = null
        fake.calendarWriteMissing = android.Manifest.permission.WRITE_CALENDAR
        fake.agendaRows = listOf(
            PhoneEngine.PhonePlatform.AgendaEvent(
                start = "2026-11-05T09:30:00",
                end = "2026-11-05T10:30:00",
                title = "Dentist",
                calendar = "Home",
            ),
        )
        val r = call(mapOf("action" to "agenda"), grants = only("calendar"))
        assertTrue("a read does not need the write grant: ${r.json()}", r.ok)
        assertEquals(1, r.facts["count"])

        val write = call(
            mapOf(
                "action" to "event_create",
                "title" to "Dentist",
                "start" to "2026-11-05T09:30",
                "duration_min" to 30,
            ),
            grants = only("calendar"),
        )
        assertFalse("creating still needs write", write.ok)
        assertEquals(PhoneReason.PERMISSION, write.reason)
        assertTrue(write.detail!!.contains("android.permission.WRITE_CALENDAR"))
    }

    @Test fun event_create_without_permission_creates_nothing_and_names_write() {
        fake.calendarMissing = android.Manifest.permission.WRITE_CALENDAR
        val r = call(
            mapOf(
                "action" to "event_create",
                "title" to "Dentist",
                "start" to "2026-11-05T09:30",
                "duration_min" to 30,
            ),
            grants = only("calendar"),
        )
        assertFalse(r.ok)
        assertEquals(PhoneReason.PERMISSION, r.reason)
        assertTrue(r.detail!!.contains("android.permission.WRITE_CALENDAR"))
        assertNull("no insert was attempted", fake.createdArgs)
    }

    @Test fun agenda_returns_what_the_calendar_holds_including_names_but_no_addresses() {
        fake.agendaRows = listOf(
            PhoneEngine.PhonePlatform.AgendaEvent(
                start = "2026-11-05T09:30:00",
                end = "2026-11-05T10:30:00",
                title = "Dentist",
                calendar = "Home",
                description = "Bring the referral letter",
                location = "Hauptstrasse 4",
                attendees = listOf("Sam", "Alex"),
            ),
            PhoneEngine.PhonePlatform.AgendaEvent(
                start = "2026-11-05T15:00:00",
                end = "2026-11-05T15:30:00",
                title = null,
                calendar = "Work",
            ),
        )
        val r = call(mapOf("action" to "agenda"), grants = only("calendar"))
        assertTrue(r.json(), r.ok)
        assertEquals(2, r.facts["count"])
        val rows = r.facts["events"] as List<*>
        val first = rows.first() as Map<*, *>
        assertEquals(
            setOf("start", "end", "title", "calendar", "description", "location", "attendees"),
            first.keys,
        )
        assertEquals("Dentist", first["title"])
        assertEquals("Bring the referral letter", first["description"])
        assertEquals("Hauptstrasse 4", first["location"])
        assertEquals(listOf("Sam", "Alex"), first["attendees"])
        val second = rows[1] as Map<*, *>
        assertNull("a field the provider will not say is null", second["title"])
        assertNull("no invented description", second["description"])
        assertNull("no invented location", second["location"])
        assertNull("no invented attendees", second["attendees"])
        assertFalse("reading has no undo", r.toMap().containsKey("undo"))
    }

    @Test fun event_create_returns_the_id_and_the_undo_deletes_the_event() {
        fake.calendarMissing = null
        fake.created = PhoneEngine.PhonePlatform.CreatedEvent(
            id = "42",
            start = "2026-11-05T09:30:00",
            end = "2026-11-05T10:00:00",
            startMs = e0 + 3_600_000L,
            endMs = e0 + 5_400_000L,
            calendar = "Home",
        )
        val at = engine(only("calendar"))
        val r = at.dispatch(
            mapOf(
                "action" to "event_create",
                "title" to "Dentist",
                "start" to "2026-11-05T09:30",
                "duration_min" to 30,
            ),
            t0, e0,
        )
        assertTrue(r.json(), r.ok)
        assertEquals("42", r.id)
        assertEquals(true, r.facts["undoable"])
        assertEquals("2026-11-05T09:30:00", r.facts["start"])
        assertEquals(
            listOf<Any?>("Dentist", "2026-11-05T09:30:00", 30, null),
            fake.createdArgs,
        )

        val undo = at.dispatch(mapOf("action" to "undo", "id" to "42"), t0, e0)
        assertTrue(undo.json(), undo.ok)
        assertEquals("event", undo.facts["undone"])
        assertEquals("cancelled", undo.facts["state"])
        assertEquals(listOf("42"), fake.deleted)
    }

    @Test fun a_phone_with_no_writable_calendar_does_not_claim_the_event() {
        fake.created = null
        val r = call(
            mapOf(
                "action" to "event_create",
                "title" to "Dentist",
                "start" to "2026-11-05T09:30",
                "duration_min" to 30,
            ),
            grants = only("calendar"),
        )
        assertFalse(r.ok)
        assertEquals(PhoneReason.UNAVAILABLE, r.reason)
        assertNull(r.id)
    }

    @Test fun duration_is_minutes_and_only_zero_or_negative_is_refused() {
        val base = mapOf(
            "action" to "event_create",
            "title" to "Dentist",
            "start" to "2026-11-05T09:30",
        )
        for (minutes in listOf(0, -5)) {
            val parsed = PhoneTool.parse(base + ("duration_min" to minutes), only("calendar"))
            assertEquals(
                "duration_min=$minutes must be invalid",
                PhoneReason.INVALID,
                (parsed as PhoneTool.Parsed.Refuse).result.reason,
            )
        }
        // There is no bound of ours above a day: the calendar provider's own limits apply, so a
        // week-long event parses and is offered to the platform.
        for (minutes in listOf(1_440, 1_441, 7 * 24 * 60)) {
            assertTrue(
                "duration_min=$minutes must be accepted by the tool",
                PhoneTool.parse(base + ("duration_min" to minutes), only("calendar"))
                    is PhoneTool.Parsed.Run,
            )
        }
    }

    @Test fun a_start_that_is_not_local_iso_or_not_a_real_day_is_refused() {
        for (start in listOf(
            "2026-11-05T09:30Z",
            "2026-11-05T09:30+02:00",
            "2026-02-30T09:30",
            "2026-11-05 09:30",
            "09:30",
        )) {
            assertNull("$start must not parse as a local instant", PhoneCalendar.parseLocal(start))
        }
        assertEquals(
            "2026-11-05T09:30:00",
            PhoneCalendar.parseLocal("2026-11-05T09:30")!!.iso,
        )
    }

    @Test fun the_calendar_actions_are_absent_until_calendar_is_on() {
        assertFalse("agenda is absent while Calendar is off", "agenda" in schemaEnum(PhoneGrants.ALL_OFF))
        assertFalse("event_create is absent while Calendar is off", "event_create" in schemaEnum(PhoneGrants.ALL_OFF))
        val on = schemaEnum(only("calendar"))
        assertTrue("agenda appears when Calendar is on", "agenda" in on)
        assertTrue("event_create appears when Calendar is on", "event_create" in on)
    }

    @Test fun the_audit_keeps_an_event_title_as_a_length_not_its_words() {
        val summary = PhoneAudit.summarise(
            mapOf(
                "action" to "event_create",
                "title" to "Dentist appointment",
                "start" to "2026-11-05T09:30",
                "duration_min" to 30,
            ),
        )
        assertEquals("${"Dentist appointment".length} chars", summary["title"])
        assertFalse(summary.values.any { it.contains("Dentist") })
        assertEquals("2026-11-05T09:30", summary["start"])
        assertEquals("30", summary["duration_min"])
    }
}
