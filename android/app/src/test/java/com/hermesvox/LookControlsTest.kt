package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

/**
 * #225: the pref key ↔ control mapping, held so a slider cannot lie.
 *
 * The failure this guards is quiet: a slider that looks right, moves right, and writes a
 * pref nobody reads (or one value while displaying another). The Being section binds its
 * sliders from [LookControls.ALL], so the mapping is structural — these tests hold the table
 * against the four things that could still drift apart:
 *
 *   the layout   every entry's row, SeekBar and value label exist, nested correctly, and the
 *                layout's own text/max agree with the table;
 *   the prefs    every key is a [VisualStyle] constant with the shipped default;
 *   the renderer every key the table writes is a key AvatarView.applyLookPrefs reads;
 *   the screen   Settings binds the sliders from this table (not hand-rolled), and each has
 *                the control's own reset.
 */
class LookControlsTest {

    private val layoutFile = File("src/main/res/layout/activity_settings.xml")
    private val layoutText = layoutFile.readText()
    private val settingsSource = File("src/main/java/com/hermesvox/SettingsActivity.kt").readText()
    private val avatarSource = File("src/main/java/com/hermesvox/AvatarView.kt").readText()
    private val styleSource = File("src/main/java/com/hermesvox/VisualStyle.kt").readText()

    private val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(layoutFile)

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

    private val ids: Map<String, Element> by lazy {
        elements().mapNotNull { e -> e.id()?.let { it to e } }.toMap()
    }

    private fun inside(e: Element, ancestorId: String): Boolean {
        var p: Node? = e.parentNode
        while (p is Element) {
            if (p.id() == ancestorId) return true
            p = p.parentNode
        }
        return false
    }

    /** `const val KEY_DENSITY = "visual_density"` -> VALUE -> constant name. */
    private val constantsByValue: Map<String, String> by lazy {
        Regex("""const\s+val\s+([A-Z0-9_]+)\s*=\s*"([^"]*)"""")
            .findAll(styleSource)
            .associate { it.groupValues[2] to it.groupValues[1] }
    }

    private fun constantOf(key: String): String =
        constantsByValue[key] ?: error("$key is not a VisualStyle constant")

    @Test fun theFiveLookPrefsAreEachOwnedByExactlyOneControl() {
        // The issue's five, verbatim: these are the stores that already existed.
        assertEquals(
            listOf("visual_density", "visual_speed", "visual_trail", "visual_hue", "visual_energy"),
            LookControls.LOOK_KEYS
        )
        for (k in LookControls.LOOK_KEYS) {
            assertEquals("$k is not owned by exactly one control",
                1, LookControls.ALL.count { it.key == k })
        }
        // Glow predates #225 and rides the same table; it is not one of the five.
        assertEquals(1, LookControls.ALL.count { it.key == VisualStyle.KEY_GLOW })
        assertFalse(VisualStyle.KEY_GLOW in LookControls.LOOK_KEYS)
        assertEquals("the table must not invent a key", LookControls.ALL.size, LookControls.ALL.map { it.key }.toSet().size)
    }

    /** The renderer half: a control may only claim a pref that [AvatarView] actually applies. */
    @Test fun everyControlWritesAPrefTheRendererReads() {
        val apply = avatarSource.substringAfter("fun applyLookPrefs").substringBefore("// ----")
        for (c in LookControls.ALL) {
            val name = constantOf(c.key)
            assertTrue("${c.title} writes ${c.key}, which applyLookPrefs never reads",
                apply.contains("getFloat(VisualStyle.$name,"))
        }
    }

    @Test fun everyControlOwnsItsRowSeekBarValueAndResetInTheLayout() {
        for (c in LookControls.ALL) {
            val row = ids[c.row] ?: error("${c.title}: no row ${c.row} in the layout")
            val seek = ids[c.seek] ?: error("${c.title}: no SeekBar ${c.seek}")
            val value = ids[c.value] ?: error("${c.title}: no value label ${c.value}")
            assertTrue("${c.seek} is not inside ${c.row}", inside(seek, c.row))
            assertTrue("${c.value} is not inside ${c.row}", inside(value, c.row))
            assertEquals("${c.seek} draws the wrong number of steps",
                c.steps.toString(), seek.getAttribute("android:max"))
            // The static layout text is the default reading, so an unopened screen cannot
            // advertise a value or a range the control does not have.
            assertEquals("${c.value} disagrees with the table", c.labelAt(c.progressOf(c.default)),
                value.getAttribute("android:text"))
            // One reset action per control, beside its title.
            val resets = elements(row).mapNotNull { it.id() }.filter { it == LookControls.resetName(c.seek) }.toList()
            assertEquals("${c.title} has no single reset action", 1, resets.size)
            assertNotNull("the reset action for ${c.title} is not in the layout", ids[LookControls.resetName(c.seek)])
            // The row's title is what the hint table is keyed on, so it must be the table's.
            assertNotNull("${c.title}'s row title drifted from the table",
                elements(row).firstOrNull { it.tagName == "TextView" && it.getAttribute("android:text") == c.title })
            assertTrue("${c.title} has no one-line statement", c.hint.isNotBlank())
        }
    }

    @Test fun everyControlShowsItsCurrentValueAndItsRange() {
        for (c in LookControls.ALL) {
            val atDefault = c.labelAt(c.progressOf(c.default))
            assertTrue("${c.title}: '$atDefault' does not show the default read value",
                atDefault.contains(c.fmt(c.default)))
            assertTrue("${c.title}: '$atDefault' does not show its range", atDefault.contains(c.range))
            assertTrue("${c.title} has an empty range", c.range.isNotBlank())
            assertTrue("${c.title}: min must be below max", c.min < c.max)
            assertTrue("${c.title}: step must move the control", c.step > 0f && c.steps > 1)
        }
    }

    /** No defaults moved: each control's default is the shipped identity, on a step, in range. */
    @Test fun theDefaultsAreTheShippedIdentityAndOnAStep() {
        for (c in LookControls.ALL) {
            assertTrue("${c.title}: default is outside its range", c.default in c.min..c.max)
            val back = c.at(c.progressOf(c.default))
            assertTrue("${c.title}: default ${c.default} is not a whole step ($back)",
                abs(back - c.default) < 1e-4f)
        }
        assertEquals(1f, LookControls.ALL.first { it.key == VisualStyle.KEY_DENSITY }.default)
        assertEquals(1f, LookControls.ALL.first { it.key == VisualStyle.KEY_SPEED }.default)
        assertEquals(0f, LookControls.ALL.first { it.key == VisualStyle.KEY_TRAIL }.default)
        assertEquals(0f, LookControls.ALL.first { it.key == VisualStyle.KEY_HUE }.default)
        assertEquals(1f, LookControls.ALL.first { it.key == VisualStyle.KEY_ENERGY }.default)
    }

    /** The screen is wired from the table, and each control resets its own pref in one tap. */
    @Test fun theScreenBindsTheSlidersFromTheTableAndResetsEachControl() {
        assertTrue("SettingsActivity does not bind from LookControls.ALL",
            settingsSource.contains("for (c in LookControls.ALL) bindLookControl(c)"))
        // The old hand-wiring would mean a slider whose key is restated at the call site.
        for (c in LookControls.ALL) {
            assertFalse("${c.seek} is still wired by hand",
                settingsSource.contains("bindFloatSeekBar(R.id.${c.seek}"))
        }
        assertTrue("the per-control reset is not wired", settingsSource.contains("LookControls.resetName(c.seek)"))
        assertTrue("the reset does not write the control's own pref",
            settingsSource.contains("prefs.edit().putFloat(key, c.default)"))
        // ...and the whole section keeps its own reset (GROUP_VISUALS), so both scopes exist.
        assertTrue("the Being section has no reset of its own",
            settingsSource.contains("bindRestoreRow(R.id.row_restore_visuals, GROUP_VISUALS"))
    }

    /**
     * The lifetime promise: the preview stops at onPause, not at onDestroy, and stopping it
     * means joining the render thread (which destroys the GL context and its targets).
     */
    @Test fun thePreviewStopsAndTheRendererIsReleasedAtOnPause() {
        val pause = settingsSource.substringAfter("override fun onPause()").substringBefore("override fun")
        assertTrue("onPause does not stop the preview ticker", pause.contains("previewHandler.removeCallbacks"))
        assertTrue("onPause does not pause the being", pause.contains("setPaused(true)"))
        assertTrue("the preview is not resumed with the screen", settingsSource.contains("resumed = true"))
        // The preview only runs on its own section and only while resumed.
        assertTrue("the preview is not gated on the Being section",
            settingsSource.contains("resumed && currentSection == SECTION_VISUALS"))
        val avatarPause = avatarSource.substringAfter("fun setPaused(")
        assertTrue("setPaused does not release the render thread",
            avatarPause.substringBefore("override fun").contains("releaseRenderThread()"))
        assertTrue("releasing does not join the render thread",
            avatarSource.contains("it.finish(); try { it.join(500) }"))
    }

    /** The preview is the call screen's renderer, not a second visual system. */
    @Test fun thePreviewUsesTheSameRendererAndPrefsReaderAsTheCallScreen() {
        assertTrue("the preview is not an AvatarView", layoutText.contains("<com.hermesvox.AvatarView"))
        assertTrue("the preview is not fed by applyLookPrefs",
            settingsSource.contains("applyLookPrefs(prefs)"))
        // The light ground is the #275 path, not a dark panel in light mode.
        assertTrue("the preview does not set the light surface",
            settingsSource.contains("setLightSurface(night != android.content.res.Configuration.UI_MODE_NIGHT_YES"))
        assertTrue("the light ground is not the app's own page",
            settingsSource.contains("ContextCompat.getColor(this, R.color.hv_bg)"))
    }
}
