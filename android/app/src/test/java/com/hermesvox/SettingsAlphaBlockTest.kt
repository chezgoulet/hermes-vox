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

    /** Gated ⇒ in the block: the block holds every gated section, in its own order, and only
     *  those — the list between the master switch and About is exactly [AlphaBlock]. */
    @Test
    fun everyGatedSectionIsInTheBlock() {
        // Every row between the master switch and About is the table, in the table's order.
        assertEquals(blockRows, AlphaBlock.ROWS.map { it.row })
        for (row in AlphaBlock.ROWS) {
            assertTrue("${row.row} is missing from the layout", blockRows.contains(row.row))
            assertEquals("the row opens its own section", row.section, row.section.trim())
        }
        assertEquals(
            listOf("presence", "phone", "wake", "assistant"),
            AlphaBlock.ROWS.map { it.section },
        )
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

    /**
     * The owner's words are about where the block sits, not only about set membership: the
     * block is the last thing before About in the view hierarchy. The home list's own children
     * are the sections, so this reads them directly — a gated row that drifted into another
     * wrapper or into a section's container would still flatten into the right order and pass a
     * list-level check.
     */
    @Test
    fun theBlockIsTheLastThingBeforeAboutInTheHierarchy() {
        val list = elements().first { it.id() == "grp_list" }
        val sections = (0 until list.childNodes.length)
            .mapNotNull { list.childNodes.item(it) as? Element }
            .mapNotNull { it.id() }
            .filter { it.startsWith("row_grp_") }

        assertEquals(
            "the block, then About, ends the home list",
            AlphaBlock.ROWS.map { it.row } + AlphaBlock.ABOUT_ROW,
            sections.takeLast(AlphaBlock.ROWS.size + 1),
        )
        // The switch that governs the block is the direct sibling immediately above it.
        assertEquals(
            "the master switch is the row immediately above the block",
            AlphaBlock.MASTER_ROW,
            sections[sections.size - AlphaBlock.ROWS.size - 2],
        )

        // A gated row lives in the block and nowhere else — drawn once, and parented by the home
        // list rather than by another section that would still show it.
        val occurrences = elements().mapNotNull { it.id() }.groupingBy { it }.eachCount()
        val parents = mutableMapOf<String, String?>()
        fun walk(e: Element, parent: String?) {
            e.id()?.let { parents[it] = parent }
            val kids = e.childNodes
            for (i in 0 until kids.length) (kids.item(i) as? Element)?.let { walk(it, e.id() ?: parent) }
        }
        walk(document.documentElement, null)
        for (row in AlphaBlock.ROWS) {
            assertEquals("${row.row} is drawn once", 1, occurrences[row.row])
            assertEquals("${row.row} escaped the home list", "grp_list", parents[row.row])
        }
    }

    /**
     * Moving the switch is only half the change: a surface that still sends the owner to the top
     * of Settings for it is a lie. Two Phone tools' off states were user-facing strings saying
     * exactly that, so the shot is pinned, not just corrected.
     */
    @Test
    fun noSurfaceSendsTheOwnerToTheTopOfSettings() {
        val surfaces = File("src/main").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
        for (f in surfaces) {
            assertFalse(
                "the gate's old position is still described in ${f.name}",
                f.readText().contains("top of Settings"),
            )
        }
    }

    /**
     * #329, the owner's rename: the row is an instruction, not a state — a tap asks Android for
     * the role — so it reads "Set Digital Assistant to Hermes Vox". The old name is gone from
     * everything the app puts in front of a person.
     */
    @Test
    fun theAssistantRowIsAnInstructionAndItsOldNameIsGone() {
        val row = elements().first { it.id() == "row_grp_assistant" }
        val title = row.getElementsByTagName("TextView").item(0) as Element
        assertEquals("Set Digital Assistant to Hermes Vox", title.getAttribute("android:text"))

        val old = "Be the phone's assistant"
        val surfaces = File("src/main").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") } +
            sequenceOf(File("../../skills/hermes-vox-app/SKILL.md"))
        for (f in surfaces) {
            assertFalse("the old row name is still in ${f.name}", f.readText().contains(old))
        }
    }
}
