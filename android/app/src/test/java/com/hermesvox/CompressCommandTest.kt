package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.2 K2 — the user-facing /compress command: its names, and its copy. */
class CompressCommandTest {

    @Test fun compress_and_compact_are_the_same_command() {
        for (s in listOf("/compress", "/compact", "compress", "COMPACT", "  /Compress  "))
            assertTrue("'$s' should be the compress command", CompressCommand.matches(s))
    }

    @Test fun nothing_else_matches() {
        for (s in listOf("", "   ", "/clear", "/new", "compressor", "please compress this",
                         "/compresses", "/health"))
            assertFalse("'$s' is not the compress command", CompressCommand.matches(s))
    }

    @Test fun the_directive_goes_to_the_gateway_not_the_app() {
        // The whole point (K2): compaction is the GATEWAY's, on the live agent
        // session. If this ever became app-side text munging the command would be
        // a lie — the entity's context would be untouched.
        assertEquals("/compress", CompressCommand.DIRECTIVE)
    }

    @Test fun a_blank_reply_is_reported_as_a_failure_that_changed_nothing() {
        for (r in listOf(null, "", "   ")) {
            val card = CompressCommand.card(r)
            assertTrue(card.contains("didn't compact"))
            assertTrue(card.contains("Nothing was changed"))
        }
    }

    @Test fun a_real_reply_is_shown_and_bounded() {
        val card = CompressCommand.card("Summarized 42 turns into 3 paragraphs.")
        assertTrue(card.contains("compacted this conversation"))
        assertTrue(card.contains("Summarized 42 turns"))
        assertTrue(CompressCommand.card("x".repeat(5000)).length < 900)
    }

    @Test fun help_names_both_spellings() {
        assertTrue(CompressCommand.HELP_LINE.contains(CompressCommand.NAME))
        assertTrue(CompressCommand.HELP_LINE.contains(CompressCommand.ALIAS))
    }
}
