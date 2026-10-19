package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #120: a transfer says WHAT it is doing, not just how far along it is. These
 * strings are read by both the model card and the foreground notification.
 */
class ModelProgressTest {

    private fun mb(n: Long) = n * 1048576L

    @Test fun fetching_names_the_size_moved_and_total() {
        assertEquals("Fetching 120.0 of 290.0 MB", ModelProgress.fetching(mb(120), mb(290)))
    }

    @Test fun fetching_without_a_total_does_not_invent_one() {
        assertEquals("Fetching — 45.0 MB so far", ModelProgress.fetching(mb(45), -1))
    }

    @Test fun verify_and_unpack_say_what_is_happening() {
        assertEquals("Verifying the download…", ModelProgress.verifying())
        assertEquals("Unpacking the files…", ModelProgress.unpacking())
    }

    @Test fun paused_keeps_the_bytes_and_the_reason() {
        assertEquals("Paused — 120.0 MB kept, resumes where it stopped",
            ModelProgress.paused(mb(120), null))
        assertEquals("Stopped — sha256 mismatch (120.0 MB kept)",
            ModelProgress.paused(mb(120), "sha256 mismatch"))
    }
}
