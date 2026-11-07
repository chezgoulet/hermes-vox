package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The owner's restructure of the Settings home (2026-11-06): the app as it is, then one
 * contiguous alpha block with the master switch immediately above it, then About.
 *
 * The order is a fact about the layout, so it is read from the layout itself rather than
 * trusted to a comment; the block's membership and its opening rule come from [AlphaBlock],
 * which is what the screen draws.
 */
class SettingsAlphaBlockTest {

    private val layoutFile = File("src/main/res/layout/activity_settings.xml")
    private val layout = layoutFile.readText()
    private val settings = File("src/main/java/com/hermesvox/SettingsActivity.kt").readText()

    private val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(layoutFile)

    private fun Element.id(): String? = when {
        hasAttribute("android:id") -> getAttribute("android:id")
        hasAttribute("id") -> getAttribute("id")
        else -> null
    }?.removePrefix("@+id/")?.removePrefix("@id/")

    private fun elements(root: Element = document.documentElement): Sequence<Element> = sequence {
        yield(root)
        val kids = root.childNodes
        for (i in 0 until kids.length) {
            val child = kids.item(i)
            if (child is Element) yieldAll(elements(child))
        }
    }

    /** The home list's rows, in the order the screen draws them. */
    private val homeRows: List<String> by lazy {
        val list = elements().first { it.id() == "grp_list" }
        elements(list).mapNotNull { it.id() }.filter { it.startsWith("row_grp_") }.toList()
    }

    private val ordinary = listOf(
        "row_grp_entity", "row_grp_speech", "row_grp_models", "row_grp_tts", "row_grp_visuals",
    )

    /** Every row between the master switch and About: the alpha block. */
    private val blockRows = listOf(
        "row_grp_presence", "row_grp_phone", "row_grp_wake", "row_grp_assistant",
    )

    @Test
    fun theHomeListIsOrdinaryThenTheMasterThenTheBlockThenAbout() {
        assertEquals(
            "ordinary sections, then the master switch, then the block, then About",
            ordinary + AlphaBlock.MASTER_ROW + blockRows + AlphaBlock.ABOUT_ROW,
            homeRows,
        )
        // The switch that governs the block is the row immediately before it, not the top.
        val blockStart = homeRows.indexOf(blockRows.first())
        assertEquals("the master switch sits immediately above the block",
            AlphaBlock.MASTER_ROW, homeRows[blockStart - 1])
        assertEquals("About is the last row", AlphaBlock.ABOUT_ROW, homeRows.last())
        assertTrue("the block is not contiguous",
            homeRows.subList(blockStart, homeRows.indexOf(AlphaBlock.ABOUT_ROW)) == blockRows)
        // Nothing gated is left up among the ordinary sections.
        for (row in blockRows) assertFalse("$row is outside the block", ordinary.contains(row))
    }

    /** Gated ⇒ in the block: the block holds every section the gate owns, and only those. */
    @Test
    fun everyGatedSectionIsInTheBlock() {
        // Every gated row the table knows about is drawn inside the block, in its order.
        val tableRows = AlphaBlock.ROWS.map { it.row }
        assertEquals(tableRows, homeRows.filter { tableRows.contains(it) })
        for (row in AlphaBlock.ROWS) {
            assertTrue("${row.row} is missing from the layout", blockRows.contains(row.row))
            assertEquals("the row opens its own section", row.section, row.section.trim())
        }
        // Presence, Phone tools and the wake word are the sections the gate owns in this step.
        assertEquals(listOf("row_grp_presence", "row_grp_phone", "row_grp_wake"), tableRows)
        assertEquals(setOf("presence", "phone", "wake"), AlphaBlock.ROWS.map { it.section }.toSet())
    }

    /** With the gate off every gated row is inert: no tap, and no section opens behind it. */
    @Test
    fun withTheGateOffEveryRowInTheBlockIsInert() {
        val off = AlphaGate.Reader { _, default -> default }   // nothing stored: a fresh install
        for (row in AlphaBlock.ROWS) {
            assertFalse(row.row, AlphaGate.allows(off, row.set))
            assertFalse("${row.row} can still open", AlphaBlock.canOpen(off, row.section))
        }
    }

    /** With the gate on and one set selected, only that set's section opens. */
    @Test
    fun withTheGateOnOnlyTheSelectedSetOpens() {
        for (only in AlphaBlock.ROWS) {
            val r = AlphaGate.Reader { key, _ ->
                key == AlphaGate.KEY_MASTER || key == only.set.key
            }
            for (row in AlphaBlock.ROWS) {
                assertEquals(row.row, row == only, AlphaBlock.canOpen(r, row.section))
            }
        }
    }

    /** An ungated section is untouched by the gate; it must not be dragged behind it. */
    @Test
    fun anOrdinarySectionAlwaysOpens() {
        val off = AlphaGate.Reader { _, default -> default }
        for (section in listOf("entity", "speech", "tts", "visuals", "about")) {
            assertTrue(section, AlphaBlock.canOpen(off, section))
        }
    }

    /** The screen asks [AlphaBlock] both questions — no second list to drift from. */
    @Test
    fun theScreenDrawsAndGuardsFromTheSameTable() {
        assertTrue("the rows are not drawn from the table",
            settings.contains("for (row in AlphaBlock.ROWS)"))
        assertTrue("a section does not refuse through the table",
            settings.contains("if (!AlphaBlock.canOpen(prefs, section)) return"))
        assertFalse("a hand-written gated row is left behind",
            settings.contains("gate(R.id.row_grp_presence,"))
        assertFalse(settings.contains("gate(R.id.row_grp_phone,"))
    }
}
