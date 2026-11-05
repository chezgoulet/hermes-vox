package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #429 read lane: `contact` looks a number up by name and returns **numbers only**.
 *
 * The properties this file pins, because they are the group's scope rather than its marketing:
 *
 *  * a partial name matches, case-insensitively, up to twenty people;
 *  * each person is a display name and numbers with their labels — no note, address, photo or
 *    email is in the result or in the audit;
 *  * no match is `ok:true, count:0`, not an error;
 *  * a missing `READ_CONTACTS` is `reason:"permission"` naming Android's exact string.
 */
class PhoneContactsTest {

    private class Fake : PhoneEngine.PhonePlatform {
        var permission: String? = null
        var matches: List<PhoneEngine.PhonePlatform.ContactMatch>? = emptyList()
        val asked = ArrayList<String>()
        /** Every reach for the address book, permission included — so "no read" is proven. */
        var permissionAsks = 0

        override fun contactsPermission(): String? {
            permissionAsks++
            return permission
        }

        override fun contactMatches(
            name: String,
            limit: Int,
        ): List<PhoneEngine.PhonePlatform.ContactMatch>? {
            asked.add(name)
            return matches?.take(limit)
        }

        /** The handoff path's single-number lookup: the same accessor, so a test can prove no read. */
        override fun contactNumber(name: String): String? {
            asked.add(name)
            return matches?.firstOrNull()?.numbers?.firstOrNull()?.number
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
        override fun clip(text: String): PhoneEngine.PhonePlatform.ClipWrite =
            PhoneEngine.PhonePlatform.ClipWrite.Done
    }

    private val fake = Fake()
    private val t0 = 1_000_000L
    private val e0 = 1_700_000_000_000L
    private val entries = ArrayList<PhoneAudit.Entry>()

    private fun onlyContacts(state: PhoneGrants.State = PhoneGrants.State.ALLOW) =
        PhoneGrants.Source { g ->
            if (g.id == "contacts") state else PhoneGrants.State.OFF
        }

    private fun engine(grants: PhoneGrants.Source = onlyContacts()) = PhoneEngine(
        fake,
        grants = grants,
        audit = { result, args, at -> entries.add(PhoneAudit.entry(at, args, result)) },
    )

    private fun call(arguments: Map<String, Any?>) = engine().dispatch(arguments, t0, e0)

    @Test fun a_partial_name_matches_and_returns_numbers_with_their_labels() {
        fake.matches = listOf(
            PhoneEngine.PhonePlatform.ContactMatch(
                "Mom",
                listOf(
                    PhoneEngine.PhonePlatform.ContactNumber("mobile", "+15551234567"),
                    PhoneEngine.PhonePlatform.ContactNumber("home", "555-0100"),
                ),
            ),
            PhoneEngine.PhonePlatform.ContactMatch(
                "Mom's work",
                listOf(PhoneEngine.PhonePlatform.ContactNumber("work", "555-0199")),
            ),
        )
        val r = call(mapOf("action" to "contact", "name" to "mo"))
        assertTrue(r.json(), r.ok)
        assertEquals("mo", fake.asked.single())
        assertEquals(2, r.facts["count"])
        val matches = r.facts["matches"] as List<*>
        val first = matches[0] as Map<*, *>
        assertEquals("Mom", first["display_name"])
        val numbers = first["numbers"] as List<*>
        assertEquals("mobile", (numbers[0] as Map<*, *>)["label"])
        assertEquals("+15551234567", (numbers[0] as Map<*, *>)["number"])
        assertEquals("home", (numbers[1] as Map<*, *>)["label"])
        assertTrue(r.json().contains("\"display_name\":\"Mom\""))
    }

    @Test fun the_result_carries_no_note_address_photo_or_email() {
        fake.matches = listOf(
            PhoneEngine.PhonePlatform.ContactMatch(
                "Ada",
                listOf(PhoneEngine.PhonePlatform.ContactNumber("mobile", "555-0100")),
            ),
        )
        val json = call(mapOf("action" to "contact", "name" to "ada")).json()
        for (forbidden in listOf("email", "address", "photo", "note", "structured")) {
            assertFalse("$forbidden must not be in a contact result: $json", json.contains(forbidden))
        }
    }

    @Test fun no_match_is_ok_with_count_zero_not_an_error() {
        fake.matches = emptyList()
        val r = call(mapOf("action" to "contact", "name" to "nobody"))
        assertTrue(r.json(), r.ok)
        assertEquals(0, r.facts["count"])
        assertEquals(emptyList<Any?>(), r.facts["matches"])
        assertNull(r.reason)
        assertFalse(r.json().contains("\"reason\""))
    }

    @Test fun a_missing_permission_refuses_permission_naming_the_exact_string() {
        fake.permission = ContactsAccess.PERMISSION
        val r = call(mapOf("action" to "contact", "name" to "Mom"))
        assertFalse(r.ok)
        assertEquals(PhoneReason.PERMISSION, r.reason)
        assertTrue(
            "the refusal names Android's own permission string",
            r.detail!!.contains("android.permission.READ_CONTACTS"),
        )
        assertTrue("nothing was read", fake.asked.isEmpty())
    }

    @Test fun a_provider_failure_is_unavailable_and_claims_no_count() {
        fake.matches = null
        val r = call(mapOf("action" to "contact", "name" to "Mom"))
        assertFalse(r.ok)
        assertEquals(PhoneReason.UNAVAILABLE, r.reason)
        assertFalse(r.toMap().containsKey("count"))
    }

    @Test fun the_audit_keeps_the_count_and_no_name_or_number() {
        fake.matches = listOf(
            PhoneEngine.PhonePlatform.ContactMatch(
                "Mom",
                listOf(PhoneEngine.PhonePlatform.ContactNumber("mobile", "+15551234567")),
            ),
        )
        call(mapOf("action" to "contact", "name" to "Mom"))
        val row = entries.single()
        assertEquals(mapOf("count" to "1"), row.arguments)
        assertFalse("the query name is not a contact fact to keep", row.arguments.containsKey("name"))
        assertFalse(row.arguments.values.any { it.contains("Mom") || it.contains("555") })
    }

    @Test fun the_contacts_read_uses_contactscontract_only() {
        val source = java.io.File("src/main/java/com/hermesvox/ContactsAccess.kt").readText()
        assertTrue("the lookup is ContactsContract", source.contains("ContactsContract"))
        for (other in listOf("CalendarContract", "Telephony", "SmsManager", "ContactsContract.Contacts.CONTENT_URI")) {
            assertFalse("$other is not a contact lookup", source.contains(other))
        }
        assertTrue(
            "the projection is a name and numbers, never a note/address/photo/email column",
            !source.contains("NOTE") && !source.contains("POSTAL") &&
                !source.contains("PHOTO") && !source.contains("EMAIL"),
        )
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue(
            "READ_CONTACTS is declared for the group",
            manifest.contains("android.permission.READ_CONTACTS"),
        )
    }

    @Test fun contacts_off_is_not_granted_and_reads_nothing() {
        val off = PhoneGrants.Source { PhoneGrants.State.OFF }
        val r = engine(off).dispatch(mapOf("action" to "contact", "name" to "Mom"), t0, e0)
        assertFalse(r.ok)
        assertEquals(PhoneReason.NOT_GRANTED, r.reason)
        assertTrue(r.detail!!.contains("Contacts"))
        assertTrue(fake.asked.isEmpty())
    }

    // ---- H3: a name is a Contacts read whatever action asked for it ----

    /** Contacts=Ask, Calls=Allow: the dial is refused and the address book is never opened. */
    @Test fun dialling_a_name_at_ask_reads_no_address_book() {
        val grants = PhoneGrants.Source { g ->
            when (g.id) {
                "contacts" -> PhoneGrants.State.ASK
                "calls" -> PhoneGrants.State.ALLOW
                else -> PhoneGrants.State.OFF
            }
        }
        val r = engine(grants).dispatch(mapOf("action" to "dial", "to" to "Mom"), t0, e0)
        assertFalse(r.ok)
        assertEquals("Ask is not Allow for a lookup", PhoneReason.APPROVAL, r.reason)
        assertTrue("the refusal names Contacts: ${r.detail}", r.detail!!.contains("Contacts"))
        assertEquals("the permission was never even asked for", 0, fake.permissionAsks)
        assertTrue("the address book was never queried", fake.asked.isEmpty())
    }

    /** Contacts=Off, Messages=Allow: same refusal, as `not_granted`, and no read. */
    @Test fun messaging_a_name_at_off_is_not_granted_and_reads_no_address_book() {
        val grants = PhoneGrants.Source { g ->
            if (g.id == "messages") PhoneGrants.State.ALLOW else PhoneGrants.State.OFF
        }
        val r = engine(grants)
            .dispatch(mapOf("action" to "message", "to" to "Mom", "text" to "hi"), t0, e0)
        assertFalse(r.ok)
        assertEquals(PhoneReason.NOT_GRANTED, r.reason)
        assertTrue("the refusal names Contacts: ${r.detail}", r.detail!!.contains("Contacts"))
        assertEquals(0, fake.permissionAsks)
        assertTrue(fake.asked.isEmpty())
    }

    /** The positive control for the two tests above: Allow does reach the address book. */
    @Test fun dialling_a_name_at_allow_resolves_it() {
        val grants = PhoneGrants.Source { g ->
            when (g.id) {
                "contacts" -> PhoneGrants.State.ALLOW
                "calls" -> PhoneGrants.State.ALLOW
                else -> PhoneGrants.State.OFF
            }
        }
        fake.matches = listOf(
            PhoneEngine.PhonePlatform.ContactMatch(
                "Mom",
                listOf(PhoneEngine.PhonePlatform.ContactNumber("mobile", "+15551234567")),
            ),
        )
        val r = engine(grants).dispatch(mapOf("action" to "dial", "to" to "Mom"), t0, e0)
        assertEquals(r.json(), "Mom", fake.asked.single())
        assertFalse("the refusal path was not taken: ${r.json()}", r.reason == PhoneReason.APPROVAL)
    }
}
