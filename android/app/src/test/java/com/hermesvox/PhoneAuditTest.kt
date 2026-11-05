package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #429 step 1: the Recent actions record.
 *
 * The audit is the owner's check on the agent's claims, so two things are load-bearing: a refusal
 * is recorded exactly like a success, and an argument that is a body (a note's text) is summarised
 * rather than copied. The stored form round-trips, because a list that only exists until the screen
 * closes is not the record the design asks for.
 */
class PhoneAuditTest {

    private val e0 = 1_700_000_000_000L

    @Test fun a_success_records_its_action_arguments_and_undo() {
        val result = PhoneResult.done(
            "timer",
            "timer-3",
            linkedMapOf("remaining_ms" to 300_000L, "duration_ms" to 300_000L, "undoable" to true),
        )
        val entry = PhoneAudit.entry(e0, mapOf("action" to "timer", "duration_ms" to 300_000L), result)
        assertTrue(entry.ok)
        assertEquals("done", entry.outcome)
        assertEquals("timer", entry.action)
        assertEquals("300000", entry.arguments["duration_ms"])
        assertFalse("action is its own column", entry.arguments.containsKey("action"))
        assertEquals("timer-3", entry.undoId)
    }

    @Test fun a_refusal_is_recorded_with_its_reason_and_no_undo() {
        val result = PhoneResult.refused("timer", PhoneReason.NOT_GRANTED, "the group is off")
        val entry = PhoneAudit.entry(e0, mapOf("action" to "timer", "duration_ms" to 60_000L), result)
        assertFalse(entry.ok)
        assertEquals("not_granted", entry.outcome)
        assertNull(entry.undoId)
    }

    @Test fun an_action_without_an_undo_records_none() {
        val result = PhoneResult.done("dnd", "dnd-1", linkedMapOf("on" to true, "undoable" to false))
        assertNull(PhoneAudit.entry(e0, mapOf("action" to "dnd", "on" to true), result).undoId)
    }

    @Test fun a_text_body_is_stored_as_its_length_not_its_words() {
        val body = "the plumber is coming Thursday at 9"
        val summary = PhoneAudit.summarise(mapOf("action" to "note", "text" to body))
        assertEquals("${body.length} chars", summary["text"])
        assertFalse(summary.values.any { it.contains("plumber") })
    }

    @Test fun values_are_clipped_and_kept_on_one_line() {
        val long = "x".repeat(PhoneAudit.MAX_ARG_CHARS * 2) + "\nsecond line"
        val summary = PhoneAudit.summarise(mapOf("action" to "note", "id" to long))
        assertEquals(PhoneAudit.MAX_ARG_CHARS, summary["id"]!!.length)
        assertFalse(summary["id"]!!.contains("\n"))
    }

    @Test fun the_stored_form_round_trips_in_order() {
        val entries = listOf(
            PhoneAudit.entry(e0, mapOf("action" to "timer", "duration_ms" to 60_000L),
                PhoneResult.done("timer", "timer-1", linkedMapOf("undoable" to true))),
            PhoneAudit.entry(e0 + 1_000, mapOf("action" to "dnd"),
                PhoneResult.refused("dnd", PhoneReason.NOT_GRANTED, "off")),
        )
        val decoded = PhoneAudit.decode(PhoneAudit.encode(entries))
        assertEquals(entries, decoded)
    }

    @Test fun a_corrupt_store_reads_as_an_empty_list() {
        assertTrue(PhoneAudit.decode(null).isEmpty())
        assertTrue(PhoneAudit.decode("not json").isEmpty())
        assertTrue(PhoneAudit.decode("""[{"action":""}]""").isEmpty())
    }
}
