package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadResumeTest {
    private val gb = 2_588_147_712L   // the Gemma artifact

    @Test fun a_fresh_download_writes_from_zero() {
        assertEquals(DownloadResume.Plan.Write(0, gb), DownloadResume.plan(0, 200, gb, null))
    }

    @Test fun a_ranged_206_appends_from_where_it_left_off() {
        // The field failure: a stream error at ~90% must not restart a 2.6 GB file.
        val have = 2_300_000_000L
        assertEquals(DownloadResume.Plan.Write(have, gb),
            DownloadResume.plan(have, 206, gb - have, "bytes $have-${gb - 1}/$gb"))
    }

    @Test fun a_server_that_ignores_range_restarts_cleanly() {
        assertEquals(DownloadResume.Plan.Write(0, gb), DownloadResume.plan(1_000, 200, gb, null))
    }

    @Test fun a_206_at_the_wrong_offset_is_refused() {
        assertTrue(DownloadResume.plan(1_000, 206, 10, "bytes 0-9/100") is DownloadResume.Plan.Fail)
    }

    @Test fun a_416_on_a_complete_partial_goes_to_verification() {
        assertEquals(DownloadResume.Plan.Complete(gb), DownloadResume.plan(gb, 416, -1, "bytes */$gb"))
        // A partial that does not match the real size starts over.
        assertEquals(DownloadResume.Plan.Write(0, -1), DownloadResume.plan(gb + 5, 416, -1, "bytes */$gb"))
    }

    @Test fun unknown_length_or_errors_fail() {
        assertTrue(DownloadResume.plan(0, 200, -1, null) is DownloadResume.Plan.Fail)
        assertTrue(DownloadResume.plan(0, 503, 10, null) is DownloadResume.Plan.Fail)
    }

    @Test fun content_range_parsing() {
        assertEquals(5L to 100L, DownloadResume.parseContentRange("bytes 5-99/100"))
        assertNull(DownloadResume.parseContentRange("bytes 5-99/*"))
        assertNull(DownloadResume.parseContentRange("bytes 50-10/100"))
        assertNull(DownloadResume.parseContentRange(null))
    }

    @Test fun backoff_grows_and_caps() {
        assertEquals(listOf(2000L, 4000L, 8000L, 16000L, 30000L, 30000L),
            (1..6).map { DownloadResume.backoffMs(it) })
    }

    @Test fun a_partial_resumes_only_for_the_same_artifact() {
        assertTrue(DownloadResume.sameArtifact("u", "ABC", "u", "abc"))
        assertFalse("a re-pinned model must not resume old bytes", DownloadResume.sameArtifact("u", "abc", "u", "def"))
        assertFalse(DownloadResume.sameArtifact(null, null, "u", "abc"))
    }

    @Test fun storage_need_counts_what_is_left_plus_unpack_room() {
        val mb = 1024L * 1024
        assertEquals(100 * mb + 200 * mb, DownloadResume.bytesNeeded(100 * mb, 0, isArchive = false))
        assertEquals(60 * mb + 200 * mb + 200 * mb, DownloadResume.bytesNeeded(100 * mb, 40 * mb, isArchive = true))
    }
}
