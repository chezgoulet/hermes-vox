package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1 (#421 step 1): the context block a voice turn puts in `instructions` so the mind knows what
 * the phone is doing — and the two properties that make it safe to send on every turn:
 *
 *  - it is SMALL (a hard character budget, trimmed deterministically), and
 *  - it never carries transcript text. The caller's words ride `input`, once.
 */
class PresenceContextTest {

    private fun block(
        soul: String = "beat",
        speaking: Boolean = false,
        lastLine: String = "On it.",
        actions: List<String> = listOf("timer(remaining=3m12s)"),
        clock: String = "18:42",
        battery: Int? = 62,
        online: Boolean = true,
        dnd: Boolean = false,
    ) = PresenceContext.block(soul, speaking, lastLine, actions, clock, battery, online, dnd)

    @Test fun the_shape_is_what_the_brief_asked_for() {
        val b = block()
        assertTrue(b, b.startsWith("presence: soul=beat | speaking=false | last_line=\"On it.\""))
        assertTrue(b, b.contains("\nlocal_actions: timer(remaining=3m12s)"))
        assertTrue(b, b.endsWith("\ndevice: 18:42, battery 62%, online, dnd=off"))
    }

    @Test fun it_stays_under_the_budget_even_with_a_long_line_and_many_actions() {
        val b = block(
            lastLine = "x".repeat(300),
            actions = listOf("timer(remaining=23h59m)", "alarm(at=07:30)", "volume=100%", "dnd=on"),
        )
        assertTrue("${b.length} chars:\n$b", b.length <= PresenceContext.MAX_CHARS)
        // The load-bearing parts survive the trim; the quote is what shrinks.
        assertTrue(b, b.contains("presence: soul=beat"))
        assertTrue(b, b.contains("device: 18:42, battery 62%, online, dnd=off"))
    }

    @Test fun an_empty_registry_says_none_rather_than_going_missing() {
        // "empty is fine for now" — but the mind must be able to tell "nothing is running" from
        // "the phone said nothing about it".
        assertTrue(block(actions = emptyList()).contains("\nlocal_actions: none"))
    }

    @Test fun no_transcript_text_ever_appears() {
        // The block reports the PHONE's state. A caller's sentence must never be reproduced here;
        // only the soul's last line is quoted, and it is capped.
        val longLine = "I searched every listing on the market for that exact card and compared them all"
        val b = block(lastLine = longLine)
        assertFalse(b, b.contains(longLine))
        assertTrue(b, b.contains("last_line=\""))
    }

    @Test fun quotes_and_newlines_cannot_break_the_block() {
        val b = block(lastLine = "he said \"hello\"\nthen left")
        assertFalse("no raw quote inside the quoted field", b.contains("last_line=\"he said \"hello"))
        assertTrue(b, b.contains("last_line=\"he said 'hello' then left\""))
    }

    @Test fun an_unknown_battery_or_network_still_produces_a_true_line() {
        // A platform that will not answer must not be reported as "0%" or as online.
        val b = block(battery = null, online = false, dnd = true)
        assertTrue(b, b.contains("device: 18:42, offline, dnd=on"))
        assertFalse(b, b.contains("battery null"))
    }

    @Test fun the_soul_token_follows_the_lane_that_spoke() {
        assertEquals("off", PresenceContext.soulToken("", narrationOn = false))
        assertEquals("quiet", PresenceContext.soulToken("", narrationOn = true))
        assertEquals("beat", PresenceContext.soulToken("soul-beat", narrationOn = true))
        assertEquals("working", PresenceContext.soulToken("soul-working", narrationOn = true))
        assertEquals("greeting", PresenceContext.soulToken("soul-greeting", narrationOn = true))
        assertEquals("filler", PresenceContext.soulToken("presence-filler", narrationOn = true))
        // Its own token is always a word, never free text: the mind reads this line.
        for (src in listOf("soul-beat", "soul-working", "presence-stall", "mind-commentary", "")) {
            assertTrue(src, Regex("^[a-z]{1,12}$").matches(PresenceContext.soulToken(src, true)))
        }
    }
}
