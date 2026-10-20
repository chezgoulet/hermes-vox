package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Presence guard bounds — measured, not remembered (docs/PRESENCE-GUARD-BOUNDS.md).
 *
 * Runs a plausible set of renders through [ErSoulTurn.workingReject] — what the working
 * directive actually asks for, plus the ways a small model overshoots it — and records which
 * bound refuses FIRST. The counts below are pinned so a change to a bound cannot pass silently;
 * this test deliberately does NOT assert that any constant should change.
 */
class PresenceGuardBoundsTest {

    private data class Line(val what: String, val rendered: String?, val said: List<String> = emptyList())

    /** The corpus. Every line is something a 2B plausibly returns for the working directive. */
    private val corpus = listOf(
        // What the directive asks for: one short line, under seven words, no digits, no markup.
        Line("on it", "Checking on it now"),
        Line("on it", "Still digging through it"),
        Line("on it", "Almost there"),
        Line("on it", "Just a moment"),
        Line("on it", "Looking into that now"),
        Line("on it", "Right on it"),
        Line("on it", "Reading through the details"),
        Line("on it", "Working on it"),
        Line("on it", "One second"),
        Line("on it", "Hang tight a moment"),
        Line("on it", "Still working through this"),
        Line("on it", "I'm on it"),
        Line("on it", "Let me check that"),
        Line("on it", "Pulling that up now"),
        Line("on it", "Just checking the details"),
        Line("on it", "Nearly there"),
        Line("on it", "Sorting it out"),
        Line("on it", "Give me a moment"),
        Line("on it", "\"Just a second\""),
        Line("on it", "Checking..."),

        // The natural overshoot: a reassuring sentence one or two words past the margin.
        Line("7 words", "I'm just checking on that for you"),
        Line("7 words", "One moment while I pull that up"),
        Line("7 words", "Hang on, I'm still working through it"),
        Line("8 words", "I'm just checking on that for you now"),
        Line("8 words", "Just taking a look at that for you"),
        Line("8 words", "Still working through the details on my end"),
        Line("8 words", "Let me see what I can find here"),
        Line("8 words", "I'm looking into that right now for you"),
        Line("8 words", "Give me just a moment while I check"),
        Line("8 words", "I'm still checking on a few things here"),
        Line("9 words", "I am still reading through the logs for you"),
        Line("11 words", "I am still reading through the logs for you right now"),
        Line("10 words", "Just a quick update, I'm still checking everything for you"),

        // Long words: over the character bound with far fewer than seven words.
        Line("chars", "Extraordinarily complicated authentication verification ongoing"),
        Line("chars", "Comprehensively re-verifying the configuration details"),

        // A number is a fact, so a digit anywhere must be refused.
        Line("digits", "It'll take 2 minutes"),
        Line("digits", "Checking 3 things now"),
        Line("digits", "Reading 12 messages"),
        Line("digits", "One sec, 30 seconds"),

        // Markup in the shapes the guard knows.
        Line("markup", "[thinking] just a moment"),
        Line("markup", "<tool> checking now"),

        // Markdown emphasis is NOT in the guard's markup set — measured here on purpose.
        Line("markdown", "**Checking** on it"),
        Line("markdown", "_Still working_ on it"),

        // The same line already said this run.
        Line("repeat", "Still on it", said = listOf("still on it")),
        Line("repeat", "Checking the details", said = listOf("Checking the details")),

        // Silence in its forms.
        Line("silence", ErSoulTurn.ESCALATE),
        Line("silence", "<<ESCALATE>>"),
        Line("silence", "escalate"),
        Line("blank", ""),
        Line("blank", "   "),
        Line("blank", null),
    )

    @Test fun which_bound_refuses_first() {
        val rows = corpus.map { it to ErSoulTurn.workingReject(it.rendered, it.said) }
        val counts = rows.mapNotNull { it.second }.groupingBy { it }.eachCount()

        println("workingReject over ${corpus.size} plausible renders")
        println("  spoke        ${rows.count { it.second == null }}")
        for ((reason, n) in counts.toSortedMap()) println("  ${reason.padEnd(12)} $n")

        // Pinned measurements (2026-10-20). A bound change must move these numbers on purpose.
        assertEquals("spoke, i.e. passed every bound", 26, rows.count { it.second == null })
        assertEquals(10, counts["too-many-words"])
        assertEquals(1, counts["too-long"])
        assertEquals(6, counts["digits-or-markup"])
        assertEquals(2, counts["repeat"])
        assertEquals(3, counts["not-spoken"])
        assertEquals(3, counts["empty"])
        assertEquals(51, corpus.size)
        // The markdown lines are the finding, not a passing case: the guard does not carry * or _
        // inside a line, so both are spoken with their markers intact.
        assertEquals(null, rows.first { it.first.what == "markdown" }.second)
    }

    @Test fun the_order_of_the_bounds_is_length_then_content_then_count_then_repeat() {
        // A line that breaks several bounds at once reports the first one the guard applies.
        // >60 chars AND >7 words AND a digit: length wins.
        assertEquals("too-long",
            ErSoulTurn.workingReject("I am still checking 3 extraordinarily complicated authentication details right now"))
        // A digit AND >7 words: content wins over the word count.
        assertEquals("digits-or-markup", ErSoulTurn.workingReject("I am still checking 3 things for you right now"))
        // >7 words alone: the word count.
        assertEquals("too-many-words", ErSoulTurn.workingReject("I am still checking things for you right now"))
    }

    @Test fun the_directive_is_stricter_than_the_guard() {
        // workingDirective asks for "under MAX_OPENER_WORDS words" (so <= 6), while the guard
        // accepts MAX_OPENER_WORDS (so <= 7). A seven-word line is legal for the guard and
        // illegal for the instruction the model was given.
        val seven = "One moment while I pull that up"
        assertEquals(7, seven.split(Regex("\\s+")).size)
        assertEquals(null, ErSoulTurn.workingReject(seven))
        assert(ErSoulTurn.workingDirective("x").contains("under 7 words"))
    }
}
