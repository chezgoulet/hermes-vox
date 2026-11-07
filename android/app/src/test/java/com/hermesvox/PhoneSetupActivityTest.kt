package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The setup card's work lines: a history in arrival order, a phrase only for tools this app knows,
 * and an age in place of a deadline.
 *
 * The rule that matters is the one that is hard to see: a line is built from a tool's **name** and
 * this file's phrase, and [PhoneSetupActivity.Line] has no field an argument could travel in. A
 * token that rides the setup instruction can therefore not reach the screen through the work list.
 */
class PhoneSetupActivityTest {

    private val now = 1_700_000_000_000L

    @Test fun a_known_tool_gets_a_plain_phrase() {
        val line = PhoneSetupActivity.line("terminal", now)!!
        assertEquals("terminal", line.tool)
        assertEquals("ran a command", line.phrase)
        assertEquals("terminal · ran a command", PhoneSetupActivity.render(line))
    }

    @Test fun an_unknown_tool_is_shown_by_its_own_name() {
        val line = PhoneSetupActivity.line("quantum_flux", now)!!
        assertEquals("no phrase is invented", "", line.phrase)
        assertEquals("quantum_flux", PhoneSetupActivity.render(line))
    }

    @Test fun the_name_is_kept_exactly_as_the_gateway_sent_it() {
        val line = PhoneSetupActivity.line("Terminal", now)!!
        assertEquals("the phrase lookup is case-insensitive", "ran a command", line.phrase)
        assertEquals("the name is not rewritten", "Terminal", line.tool)
        assertEquals("Terminal · ran a command", PhoneSetupActivity.render(line))
    }

    @Test fun an_event_without_a_tool_makes_no_line() {
        assertNull(PhoneSetupActivity.line("", now))
        assertNull(PhoneSetupActivity.line("   ", now))
    }

    @Test fun the_history_is_rendered_in_arrival_order() {
        val lines = listOf(
            PhoneSetupActivity.line("terminal", now)!!,
            PhoneSetupActivity.line("mystery", now + 1)!!,
            PhoneSetupActivity.line("read_file", now + 2)!!,
        )
        assertEquals(
            listOf("terminal · ran a command", "mystery", "read_file · read a file"),
            PhoneSetupActivity.renderAll(lines),
        )
    }

    @Test fun silence_is_a_fact_about_the_last_event_not_a_countdown() {
        assertEquals("", PhoneSetupActivity.silence(0L, now))
        assertEquals("under a minute is just the agent thinking", "", PhoneSetupActivity.silence(now, now + 59_999L))
        assertEquals("Nothing new for 1 minute.", PhoneSetupActivity.silence(now, now + 60_000L))
        assertEquals("Nothing new for 4 minutes.", PhoneSetupActivity.silence(now, now + 240_000L))
        assertEquals("Nothing new for 60 minutes.", PhoneSetupActivity.silence(now, now + 3_600_000L))
    }

    @Test fun the_silence_line_never_promises_a_stop() {
        for (age in listOf(60_000L, 240_000L, 6 * 60 * 60_000L)) {
            val line = PhoneSetupActivity.silence(now, now + age).lowercase()
            assertFalse(line, line.contains("stop"))
            assertFalse(line, line.contains("close"))
            assertFalse(line, line.contains("will"))
            assertFalse(line, line.contains("left"))
        }
    }

    @Test fun the_work_list_has_no_room_for_an_argument() {
        // A compile-time fact, stated as a test: the only fields are the tool, the phrase and the
        // time. There is no arguments/preview/output field for a caller to fill.
        val fields = PhoneSetupActivity.Line::class.java.declaredFields
            .map { it.name }
            .filterNot { it.contains("$") }
            .toSet()
        assertEquals(setOf("tool", "phrase", "at"), fields)
        assertFalse(fields.any { it.contains("argument", true) || it.contains("preview", true) })
    }
}
