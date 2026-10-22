package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #224: every Settings row says what it changes, held against the layout itself. */
class SettingsHintsTest {

    private val layout = File("src/main/res/layout/activity_settings.xml").readText()
    private val rows = Regex("android:id=\"@\\+id/(row_[a-z0-9_]+)\"").findAll(layout)
        .map { it.groupValues[1] }.filterNot { it.startsWith("row_grp_") }.toSet()

    @Test fun everyRowHasAOneLineStatement() {
        val missing = rows - SettingsHints.HINTS.keys - SettingsHints.EXEMPT
        assertEquals("rows with no statement of what they change", emptySet<String>(), missing)
    }

    @Test fun noStatementNamesARowThatIsGone() {
        assertEquals(emptySet<String>(), SettingsHints.HINTS.keys - rows)
        assertEquals(emptySet<String>(), SettingsHints.EXEMPT - rows)
    }

    @Test fun eachStatementIsOneShortLine() {
        for ((row, hint) in SettingsHints.HINTS) {
            assertTrue("$row: ${hint.length} chars", hint.length <= SettingsHints.MAX_CHARS)
            assertTrue(row, !hint.contains('\n') && !hint.endsWith("."))
        }
    }

    @Test fun theSectionsAreNamedByWhatTheUserChanges() {
        for (t in listOf("Hearing", "Voice", "Presence", "Being")) assertTrue(t, layout.contains("android:text=\"$t\""))
        assertTrue(!layout.contains("android:text=\"Visuals\""))
        assertTrue(!layout.contains("android:text=\"Spoken replies\""))
    }

    @Test fun presenceRowsLiveInThePresenceSectionAndCuesInBeing() {
        val presence = layout.substringAfter("android:id=\"@+id/sec_presence\"").substringBefore("android:id=\"@+id/sec_models\"")
        for (r in listOf("row_er_presence", "row_er_density", "row_er_echo", "row_er_voice", "row_vox")) assertTrue(r, presence.contains("@+id/$r\""))
        val being = layout.substringAfter("android:id=\"@+id/sec_visuals\"").substringBefore("android:id=\"@+id/sec_about\"")
        for (r in listOf("row_sound_cues", "row_cue_scope", "row_cue_ping", "row_cue_idle")) assertTrue(r, being.contains("@+id/$r\""))
    }
}
