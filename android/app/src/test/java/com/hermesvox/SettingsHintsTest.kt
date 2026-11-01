package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** #224: every Settings row says what it changes, and lives in exactly one section. */
class SettingsHintsTest {

    private val layoutFile = File("src/main/res/layout/activity_settings.xml")
    private val layout = layoutFile.readText()
    private val activityFile = File("src/main/java/com/hermesvox/SettingsActivity.kt")

    private val document = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder().parse(layoutFile)

    /** The four #224 sections, plus the home list and the sections the issue left alone. */
    private val sections = linkedMapOf(
        "grp_list" to "Settings home",
        "sec_speech" to "Hearing",
        "sec_tts" to "Voice",
        "sec_presence" to "Presence",
        "sec_visuals" to "Being",
        "sec_entity" to "Entity & Connection",
        "sec_stt" to "Transcription (STT)",
        "sec_models" to "Voice models",
        "sec_appearance" to "Appearance & Layout",
        "sec_about" to "About & Diagnostics",
        "sec_assistant" to "Be the phone's assistant",   // #329
    )

    private fun Element.id(): String? {
        val raw = when {
            hasAttribute("android:id") -> getAttribute("android:id")
            hasAttribute("id") -> getAttribute("id")
            else -> null
        } ?: return null
        return raw.removePrefix("@+id/").removePrefix("@id/")
    }

    private fun elements(root: Element = document.documentElement): Sequence<Element> = sequence {
        yield(root)
        val kids = root.childNodes
        for (i in 0 until kids.length) {
            val child = kids.item(i)
            if (child is Element) yieldAll(elements(child))
        }
    }

    private val allElements: List<Element> by lazy { elements().toList() }
    private val ids: Map<String, Element> by lazy {
        allElements.mapNotNull { e -> e.id()?.let { it to e } }.toMap()
    }

    /** Every row in the layout — home rows and in-section rows alike. */
    private val rows: Set<String> by lazy {
        allElements.mapNotNull { it.id() }
            .filter { it.startsWith("row_") && !it.startsWith("row_grp_") }
            .toSet()
    }

    /** The nearest ancestor that is one of the screen's sections. */
    private fun sectionOf(e: Element): String? {
        var p: Node? = e.parentNode
        while (p is Element) {
            val id = p.id()
            if (id != null && id in sections) return id
            p = p.parentNode
        }
        return null
    }

    private fun rowsIn(section: String): Set<String> =
        allElements.filter { sectionOf(it) == section }
            .mapNotNull { it.id() }
            .filter { it.startsWith("row_") }
            .toSet()

    @Test fun everyRowHasAOneLineStatement() {
        val missing = rows - SettingsHints.HINTS.keys - SettingsHints.EXEMPT
        assertEquals("rows with no statement of what they change", emptySet<String>(), missing)

        for ((row, hint) in SettingsHints.HINTS) {
            assertTrue("$row has an empty hint", hint.isNotBlank())
            assertFalse("$row is both hinted and exempt", row in SettingsHints.EXEMPT)
        }
        for (row in SettingsHints.EXEMPT) {
            assertFalse("$row has a hint as well as an exemption", row in SettingsHints.HINTS)
        }
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

    /** #224: a moved row must not be orphaned or duplicated between two sub-views. */
    @Test fun everyRowLivesInExactlyOneSection() {
        val placed = allElements.mapNotNull { e ->
            val id = e.id() ?: return@mapNotNull null
            if (!id.startsWith("row_")) return@mapNotNull null
            id to sectionOf(e)
        }
        val orphans = placed.filter { it.second == null }.map { it.first }
        assertEquals("rows outside any section", emptyList<String>(), orphans)

        val duplicated = placed.groupBy({ it.first }, { it.second })
            .filterValues { it.size != 1 }
            .keys
        assertEquals("rows in more than one section", emptySet<String>(), duplicated)

        // Every section the screen knows about exists in the layout.
        for (id in sections.keys) assertTrue("missing section $id", id in ids)
    }

    @Test fun theSectionsAreNamedByWhatTheUserChanges() {
        for (t in listOf("Hearing", "Voice", "Presence", "Being")) assertTrue(t, layout.contains("android:text=\"$t\""))
        assertTrue(!layout.contains("android:text=\"Visuals\""))
        assertTrue(!layout.contains("android:text=\"Spoken replies\""))
    }

    @Test fun hearingOwnsTheMicrophoneRowsAndPresenceKeepsOurToneReading() {
        val hearing = rowsIn("sec_speech")
        for (r in listOf(
            "row_mic_aec", "row_mic_ns", "row_mic_partial", "row_mic_continuous",
            "row_mic_semantic", "row_mic_voicecall", "row_mic_vad", "row_mic_silence",
            "row_mic_early", "row_mic_min_speech", "row_mic_max", "row_mic_voicecall",
            "row_er_hears",
        )) assertTrue("$r should be in Hearing", r in hearing)

        val presence = rowsIn("sec_presence")
        for (r in listOf(
            "row_presence_enabled", "row_er_presence", "row_er_density", "row_er_echo",
            "row_er_voice", "row_vox",
        )) assertTrue("$r should be in Presence", r in presence)
        assertFalse("row_er_hears is a hearing setting now", "row_er_hears" in presence)
    }

    @Test fun beingKeepsTheParticleControlsAndVoiceKeepsTheTtsRows() {
        val being = rowsIn("sec_visuals")
        for (r in listOf(
            "row_visual_category", "row_visual_energy", "row_visual_glow", "row_visual_density",
            "row_visual_speed", "row_visual_trail", "row_visual_hue", "row_particle_theme",
            "row_particle_cycle", "row_sound_cues", "row_cue_scope", "row_cue_ping", "row_cue_idle",
        )) assertTrue("$r should be in Being", r in being)
        for (r in listOf("row_mic_aec", "row_er_hears", "row_tts", "row_er_density"))
            assertFalse("$r does not belong in Being", r in being)

        val voice = rowsIn("sec_tts")
        for (r in listOf("row_tts", "row_tts_speaker", "row_voice", "row_speak"))
            assertTrue("$r should be in Voice", r in voice)
        for (r in listOf("row_er_density", "row_er_echo", "row_mic_vad"))
            assertFalse("$r does not belong in Voice", r in voice)
    }

    /**
     * #224: the hint is a second line inside the row's title, so the row must top-align
     * its children. Baseline-aligning a two- or three-line title against the row's help
     * chip or switch pushes it past the padding box, where clipToPadding cuts the last
     * line in half — the device capture showed "the mic hears" sliced through the
     * middle. Top alignment keeps every line inside the card.
     */
    @Test fun hintLinesAreNotClippedByBaselineAlignment() {
        val styles = File("src/main/res/values/settings_styles.xml").readText()
        val hvRow = Regex("""<style name="HvRow">(.*?)</style>""", RegexOption.DOT_MATCHES_ALL)
            .find(styles)?.groupValues?.get(1) ?: error("HvRow style is missing")
        assertTrue("HvRow must top-align so a hinted title stays inside the padding box",
            hvRow.contains("""<item name="android:baselineAligned">false</item>"""))
    }

    /** #224: each section has at most one advanced disclosure, and it starts closed. */
    @Test fun advancedRowsSitBehindOneCollapsedDisclosurePerSection() {
        val disclosures = mapOf(
            "sec_speech" to ("row_mic_advanced" to "adv_mic_panel"),
            "sec_visuals" to ("row_being_advanced" to "adv_being_panel"),
            "sec_presence" to ("row_presence_advanced" to "adv_presence_panel"),
        )
        for ((section, pair) in disclosures) {
            val (row, panel) = pair
            val found = rowsIn(section).filter { it.endsWith("_advanced") }
            assertEquals("${sections[section]} disclosures: $found", setOf(row), found.toSet())
            assertTrue("$panel is missing", panel in ids)
            assertEquals("$panel should start closed", "gone",
                ids.getValue(panel).getAttribute("android:visibility"))
            assertEquals("$panel belongs to the wrong section", section, sectionOf(ids.getValue(panel)))
        }
        assertEquals("Voice has no advanced rows", 0, rowsIn("sec_tts").count { it.endsWith("_advanced") })
    }

    /**
     * #224's regression that matters: a moved row must not silently stop writing its
     * pref. This is the exact set of prefs the screen touched BEFORE the split; the
     * split moved views only, so every key must still be read or written here.
     */
    @Test fun theScreenReadsAndWritesTheSamePrefKeysAsBeforeTheSplit() {
        val keys = prefKeysIn(activityFile.readText())
        assertEquals("pref keys the Settings screen touches changed — a moved row lost or " +
            "renamed its binding", BEFORE_AND_AFTER_SPLIT_PREF_KEYS, keys)
    }

    private fun prefKeysIn(source: String): Set<String> {
        val patterns = listOf(
            Regex("""prefs\.(?:get|contains|remove)[A-Za-z]*\(\s*("([^"]*)"|[A-Za-z_][A-Za-z0-9_.]*)"""),
            Regex("""\.put(?:String|Boolean|Float|Int|Long|StringSet)\(\s*("([^"]*)"|[A-Za-z_][A-Za-z0-9_.]*)"""),
            Regex("""\.remove\(\s*("([^"]*)"|[A-Za-z_][A-Za-z0-9_.]*)"""),
        )
        return patterns.flatMap { p ->
            p.findAll(source).map { m ->
                val raw = m.groupValues[1]
                if (raw.startsWith("\"")) raw.substring(1, raw.length - 1) else raw
            }.toList()
        }.toSet() - setOf("key", "prefKey", "it", "default")   // helper parameters, not keys
    }

    private companion object {
        /** Captured from the screen before the four-way split (and identical after it). */
        val BEFORE_AND_AFTER_SPLIT_PREF_KEYS: Set<String> = setOf(
            "AvatarView.KEY_SHAPE_LISTENING",
            "AvatarView.KEY_SHAPE_SPEAKING",
            "AvatarView.KEY_SHAPE_THINKING",
            "ContinuousCapture.KEY",
            "CuePolicy.PREF_ENABLED",
            "CuePolicy.PREF_IDLE_S",
            "CuePolicy.PREF_LEVEL",
            "CuePolicy.PREF_PING",
            "CuePolicy.PREF_SCOPE",
            "CuePolicy.PREF_SILENT",
            "KEY_KEEP_SCREEN_ON",
            "KEY_STT_REMOTE_KEY",
            "KEY_STT_REMOTE_MODEL",
            "KEY_STT_REMOTE_URL",
            "MemoryBudget.PREF_KV_TOKENS",
            "MemoryBudget.PREF_UNLOAD",
            "MemoryBudget.PREF_UNLOAD_DELAY",
            "ModelCatalog.KEY_SOURCE",
            "ModelCatalog.KEY_STT_BACKEND",
            "ModelCatalog.KEY_STT_MODEL",
            "ModelCatalog.KEY_STT_STREAM_MODEL",
            "PresenceGate.KEY_PRESENCE_ENABLED",
            "PresenceCadence.LEGACY_PREF",
            "PresenceCadence.PREF",
            "SemanticEndpoint.KEY",
            "SemanticEndpoint.KEY_EAGERNESS",
            "SessionScope.PREF",
            "SoulAudio.PREF",
            "VisualStyle.KEY_CATEGORY",
            "VisualStyle.KEY_CYCLE_ALL",
            "VisualStyle.KEY_DENSITY",
            "VisualStyle.KEY_ENERGY",
            "VisualStyle.KEY_GLOW",
            "VisualStyle.KEY_HUE",
            "VisualStyle.KEY_SPEED",
            "VisualStyle.KEY_TRAIL",
            "VoxThreads.PREF",
            "barge_grace_ms",
            "barge_level_only_ms",
            "barge_rms_min",
            "debug_log",
            "dev_console",
            "duplex",
            "er_echo_skip_ms",
            "er_presence",
            "er_presence_voice",
            "er_semantic_barge",
            "layout_mode",
            "log_transcripts",
            "mic_aec",
            "model",
            "ns_extra",
            "partial_stt",
            "particles_cycle",
            "particles_theme",
            "provider",
            "speak_responses",
            "theme",
            "tts",
            "tts_voice_usage",
            "url",
            "vad_early_silence_ms",
            "vad_max_ms",
            "vad_min_speech_ms",
            "vad_silence_ms",
            "vad_threshold",
            "voice",
        )
    }
}
