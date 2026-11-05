package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The setting itself: Settings → Appearance has one **Command icon** row, it sits with the
 * screen settings, it says what it changes, and both the row and its chooser draw the
 * chosen glyph live ([CommandMark]) instead of naming it only in words.
 *
 * The chooser's five options and their mapping to five distinct drawings are
 * [CommandGlyphTest]'s contract; this file holds the row to the screen.
 */
class CommandIconSettingTest {

    private val layoutFile = File("src/main/res/layout/activity_settings.xml")
    private val layout = layoutFile.readText()
    private val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(layoutFile)
    private val activity = File("src/main/java/com/hermesvox/SettingsActivity.kt").readText()

    private fun Element.id(): String? {
        val raw = if (hasAttribute("android:id")) getAttribute("android:id") else null
        return raw?.removePrefix("@+id/")?.removePrefix("@id/")
    }

    private fun elements(root: Element = document.documentElement): Sequence<Element> = sequence {
        yield(root)
        val kids = root.childNodes
        for (i in 0 until kids.length) {
            val child = kids.item(i)
            if (child is Element) yieldAll(elements(child))
        }
    }

    private fun byId(id: String): Element =
        elements().firstOrNull { it.id() == id } ?: error("$id is missing from the layout")

    private fun sectionOf(e: Element): String? {
        var p: Element? = e.parentNode as? Element
        while (p != null) {
            val id = p.id()
            if (id != null && id.startsWith("sec_")) return id
            p = p.parentNode as? Element
        }
        return null
    }

    private fun rows(section: String): Set<String> = elements()
        .filter { sectionOf(it) == section }
        .mapNotNull { it.id() }
        .filter { it.startsWith("row_") }
        .toSet()

    @Test fun theRowIsInAppearanceWithTheScreenSettings() {
        val row = byId("row_command_icon")
        assertEquals("sec_visuals", sectionOf(row))
        assertTrue("the row is in the screen block, not the being's",
            listOf("row_theme", "row_layout", "row_keep_screen_on").all { it in rows("sec_visuals") })
        // The row's own words.
        val title = elements(row).mapNotNull { e ->
            e.getAttribute("android:text").takeIf { it.isNotBlank() }
        }.toList()
        assertTrue("the row is called what it is: $title", "Command icon" in title)
    }

    @Test fun theRowShowsTheChosenGlyphDrawnLiveWithItsName() {
        val row = byId("row_command_icon")
        // A live drawing, not an ImageView of a bitmap: the id is a CommandMark.
        val mark = elements(row).firstOrNull { it.id() == "set_command_icon_mark" }
            ?: error("set_command_icon_mark is missing")
        assertEquals("com.hermesvox.CommandMark", mark.tagName)
        assertEquals("24dp", mark.getAttribute("android:layout_width"))
        // And its name beside it, as the value.
        val value = elements(row).firstOrNull { it.id() == "set_command_icon_val" }
            ?: error("set_command_icon_val is missing")
        assertTrue(value.getAttribute("android:layout_width").isNotBlank())
        // Shipped default in the layout agrees with the code's default.
        assertEquals(CommandGlyph.DEFAULT.label, value.getAttribute("android:text"))
    }

    @Test fun theRowSaysWhatItChanges() {
        val hint = SettingsHints.HINTS["row_command_icon"]
            ?: error("row_command_icon has no statement of what it changes")
        assertTrue("one short line", hint.length in 1..SettingsHints.MAX_CHARS)
        assertFalse("no full stop", hint.endsWith("."))
        assertFalse("not exempt as well", "row_command_icon" in SettingsHints.EXEMPT)
        // Device-wide, like the rest of the screen block — not a profile's look.
        assertFalse("row_command_icon is the phone's, not a profile's",
            "row_command_icon" in SettingsHints.SCOPED)
    }

    @Test fun theChooserDrawsEachOptionAndWritesTheOnePref() {
        // The picker walks the five icons and shows each with a live mark + its label.
        assertTrue(activity.contains("CommandGlyph.Icon.entries"))
        assertTrue(activity.contains("CommandMark(this@SettingsActivity)"))
        assertTrue(activity.contains("text = icon.label"))
        // The row reads the one pref; the chooser writes it; restore clears it.
        assertTrue(activity.contains("CommandGlyph.current(this)"))
        assertTrue(activity.contains("putString(CommandGlyph.PREF, icon.token)"))
        assertTrue(activity.contains(".remove(CommandGlyph.PREF)"))
        // No second copy of the glyph set in the activity: the names come from the enum.
        assertFalse("the settings screen invents its own glyph names",
            activity.contains("\"Steve\", \"Bill\""))
    }
}
