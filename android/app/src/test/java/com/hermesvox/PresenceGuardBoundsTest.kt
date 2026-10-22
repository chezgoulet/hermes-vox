package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        // A number is a fact, so a digit anywhere must be refused — and here nothing is vouched
        // for (#218), so these are refused exactly as they always were, under the number's own
        // reason. The reason is separate from markup's so a log can tell them apart.
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

        // Pinned measurements. 2026-10-20 for the corpus; D20 (2026-10-21) moved one line from
        // `too-many-words` to spoken: "Just a quick update, I'm still checking everything for you"
        // now cuts at its comma and speaks. Nothing else in the corpus moved — the other over-long
        // lines have no clause end inside the budget, so D20 drops them exactly as before.
        assertEquals("spoke, i.e. passed every bound", 27, rows.count { it.second == null })
        assertEquals(9, counts["too-many-words"])
        assertEquals(1, counts["too-long"])
        // #218 split the old `digits-or-markup` in two: the four digit lines are refused as
        // unvouched numbers (nothing is vouched for in this corpus), the two bracket lines keep the
        // old reason. The total refusal count is unchanged.
        assertEquals(4, counts["digits-unvouched"])
        assertEquals(2, counts["digits-or-markup"])
        assertEquals(2, counts["repeat"])
        assertEquals(3, counts["not-spoken"])
        assertEquals(3, counts["empty"])
        assertEquals(51, corpus.size)
        // D20's finding: markdown was spoken WITH its markers before; the repair now strips them,
        // so the model's formatting no longer reaches the voice and no longer costs the line.
        assertEquals("Checking on it...", ErSoulTurn.workingLine("**Checking** on it"))
        assertEquals("Still working on it...", ErSoulTurn.workingLine("_Still working_ on it"))
    }

    @Test fun content_is_refused_before_shape_is_fitted() {
        // Content first: a line that invents a number is refused for the number even when it is
        // also over the shape budget — the fabrication is the thing the log must show.
        assertEquals("digits-unvouched", ErSoulTurn.workingReject("I am still checking 3 things for you right now"))
        assertEquals(
            "result-unbacked",
            ErSoulTurn.workingReject("I've booked it for you, and I am still checking the details"),
        )
        // Shape alone: over the word budget with no clause end is `too-many-words`; long words are
        // the character backstop's `too-long`; a clause end inside the budget is spoken shorter.
        assertEquals("too-many-words", ErSoulTurn.workingReject("I am still checking things for you right now"))
        assertEquals("too-long", ErSoulTurn.workingReject("Extraordinarily complicated authentication verification ongoing"))
        assertEquals(null, ErSoulTurn.workingReject("I am still checking things, for you right now"))
    }

    @Test fun the_directive_states_no_cap_and_the_guard_still_bounds_the_shape() {
        // D20: the directive asks for "one line ... no longer than a breath", not "under N words".
        // The bound lives in code as a CUT, not as a threat the model has to satisfy: a line over
        // it with a clause end is spoken shorter, and one without is dropped.
        val d = ErSoulTurn.workingDirective("x")
        assertFalse("no word cap as a prohibition", d.contains("under 7 words"))
        assertFalse("no character cap", d.contains("characters"))
        assertEquals("One moment while I pull that up...", ErSoulTurn.workingLine("One moment while I pull that up"))
        assertEquals(
            "I'm just checking on that...",
            ErSoulTurn.workingLine("I'm just checking on that, for you right now"),
        )
    }
}
