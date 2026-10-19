package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The what's-new banner's rules, which are easy to get subtly wrong. */
class ReleaseNotesTest {

    @Test
    fun `a fresh install is not an upgrade`() {
        assertFalse(ReleaseNotes.shouldShow(seenVersion = 0, currentVersion = 142, hasEntry = true))
    }

    @Test
    fun `an upgrade shows the notes once`() {
        assertTrue(ReleaseNotes.shouldShow(seenVersion = 141, currentVersion = 142, hasEntry = true))
    }

    @Test
    fun `the same version does not show again`() {
        assertFalse(ReleaseNotes.shouldShow(seenVersion = 142, currentVersion = 142, hasEntry = true))
    }

    @Test
    fun `a downgrade does not resurrect older notes`() {
        assertFalse(ReleaseNotes.shouldShow(seenVersion = 150, currentVersion = 142, hasEntry = true))
    }

    @Test
    fun `no entry means no banner`() {
        assertFalse(ReleaseNotes.shouldShow(seenVersion = 141, currentVersion = 142, hasEntry = false))
    }

    @Test
    fun `every entry is presentable`() {
        assertTrue("notes must exist", ReleaseNotes.ENTRIES.isNotEmpty())
        for (note in ReleaseNotes.ENTRIES) {
            assertTrue("${note.versionName}: empty title", note.title.isNotBlank())
            assertTrue("${note.versionName}: no bullets", note.bullets.isNotEmpty())
            assertTrue("${note.versionName}: blank bullet", note.bullets.none { it.isBlank() })
        }
    }

    @Test
    fun `version codes are unique`() {
        val codes = ReleaseNotes.ENTRIES.map { it.versionCode }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun `an alert always has an id, a body and an action`() {
        for (note in ReleaseNotes.ENTRIES) {
            for (alert in note.alerts) {
                assertTrue("${note.versionName}: alert without id", alert.id.isNotBlank())
                assertTrue("${note.versionName}: alert without body", alert.body.isNotBlank())
                assertTrue("${note.versionName}: alert without a label", alert.label.isNotBlank())
                if (alert.action == AlertAction.URL) {
                    assertTrue("${note.versionName}: url alert without url", alert.url.startsWith("https://"))
                }
            }
        }
    }

    @Test
    fun `the shipped 0_8_1 notes are retrievable`() {
        assertNotNull(ReleaseNotes.forVersion(142))
    }
}
