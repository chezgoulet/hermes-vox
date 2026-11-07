package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * #418's `/diagnostics` command is gone, and its switch with it: the owner asked for the
 * command to leave the catalogue entirely and for About to keep only the debug log and the
 * developer console. The block itself stays where it always was — the Presence row's own copy
 * action — because the owner did not ask for that to be removed.
 *
 * Asserted rather than described, so none of it can come back by accident.
 */
class DiagnosticsRemovedTest {

    private val src = File("src/main/java/com/hermesvox")
    private val layoutFile = File("src/main/res/layout/activity_settings.xml")
    private val layout = layoutFile.readText()

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

    private val aboutRows: List<Element> by lazy {
        val about = elements().first { it.id() == "sec_about" }
        elements(about).filter { it.id()?.startsWith("row_") == true }.toList()
    }

    @Test
    fun theCommandIsNotInTheCatalogue() {
        assertNull(CommandCatalog.find("/diagnostics"))
        assertNull(CommandCatalog.find("diagnostics"))
        assertFalse(CommandCatalog.ALL.any { it.name.contains("diagnostic") })
        assertFalse(CommandCatalog.helpText().contains("/diagnostics"))
        assertFalse(CommandCatalog.grouped(all = true).flatMap { it.second }
            .any { it.name.contains("diagnostic") })
        // Nothing answers the typed or dictated word either.
        assertNull(CommandCatalog.find(" /Diagnostics "))
    }

    /** The switch is gone from About, and so is the code that used to own it. */
    @Test
    fun theToggleIsGoneFromAbout() {
        assertFalse("the switch is still in the layout", "set_diag_summary" in layout)
        assertFalse("a diagnostics row is back", aboutRows.any { it.id()!!.contains("diag") })
        // About keeps the log and the console, and nothing diagnostic to flip.
        assertEquals(
            setOf("row_devconsole", "row_logtranscripts", "row_debuglog", "row_debug",
                "row_restore_about"),
            aboutRows.mapNotNull { it.id() }.toSet(),
        )
        for (row in aboutRows) {
            for (e in elements(row)) {
                val text = when {
                    e.hasAttribute("android:text") -> e.getAttribute("android:text")
                    e.hasAttribute("android:contentDescription") -> e.getAttribute("android:contentDescription")
                    else -> continue
                }
                assertFalse("About still offers \"$text\"",
                    text.contains("diagnostic summary", ignoreCase = true))
            }
        }
    }

    /** The orphan objects went with it — no dead switch, no one-command gate. */
    @Test
    fun theOwnersOfTheCommandAreGone() {
        assertFalse(File(src, "DiagnosticSummary.kt").exists())
        assertFalse(File(src, "CommandGate.kt").exists())
        val main = src.walkTopDown().filter { it.isFile && it.extension == "kt" }
        for (f in main) {
            val text = f.readText()
            assertFalse("${f.name} still names the command", text.contains("/diagnostics"))
            assertFalse("${f.name} still names its switch", text.contains("DiagnosticSummary"))
            assertFalse("${f.name} still names the gate", text.contains("CommandGate"))
        }
        // The report block keeps its one door: the Presence row's copy action.
        assertTrue(File(src, "DiagnosticClipboard.kt").exists())
        assertTrue(File(src, "SettingsActivity.kt").readText()
            .contains("R.id.row_presence_diag).setOnClickListener"))
    }
}
