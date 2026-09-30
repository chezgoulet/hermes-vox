package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fix 4 — the dim "heard" line: readable, bounded, and scaled to its length. */
class HeardLineTest {

    @Test fun shows_the_words_behind_a_quiet_prompt() {
        assertEquals("› what's the weather tomorrow", HeardLine.displayText("  what's the   weather\ntomorrow "))
    }

    @Test fun long_text_is_bounded() {
        val s = HeardLine.displayText("word ".repeat(100))
        assertTrue(s.length <= HeardLine.MAX_CHARS + 2)
        assertTrue(s.endsWith("…"))
    }

    @Test fun hold_scales_with_length_within_bounds() {
        assertEquals(HeardLine.MIN_HOLD_MS + HeardLine.PER_WORD_MS, HeardLine.holdMs("stop"))
        assertTrue(HeardLine.holdMs("set a timer for ten minutes") > HeardLine.holdMs("stop"))
        assertEquals(HeardLine.MAX_HOLD_MS, HeardLine.holdMs("word ".repeat(200)))
    }
}
