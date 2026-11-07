package com.hermesvox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The owner removed the two caution sentences on 2026-11-06.
 *
 * `/clear` and `/new` each carried a second line ending "…cannot be brought back" — the
 * screen's copy for one, the thread for the other. Both are gone entirely: the row keeps its
 * name and its one-line description, and nothing else. The distinction between the two stays
 * in their wording and their marks (see `CommandCatalogTest`), not in a sentence underneath.
 *
 * Asserted against the source and the resources rather than only against the catalogue, so
 * the sentence cannot come back as a layout literal or a `strings.xml` entry either. "brought
 * back" is the distinctive half of the removed form: if any file in the app still says
 * something is brought back, the caution is back in some shape.
 */
class CommandCautionRemovedTest {

    /** Everything the app ships as text: Kotlin sources, the layouts, the string resources. */
    private fun shippedText(): List<Pair<String, String>> =
        File("src/main").walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "xml") }
            .map { it.path to it.readText() }
            .toList()

    /** The exact sentences the owner removed. */
    private val removed = listOf(
        "The screen's copy cannot be brought back",
        "This thread cannot be brought back",
    )

    @Test fun theTwoSentencesAreGoneFromEverySourceAndResource() {
        val files = shippedText()
        // A scan that finds nothing because it read nothing would pass for the wrong reason.
        assertTrue("the scan read no source or resource file", files.size > 50)
        for ((path, text) in files) {
            for (sentence in removed)
                assertFalse("$path still says \"$sentence\"", text.contains(sentence))
            // The caution cannot come back in another casing or with a contraction.
            assertFalse("$path still says something is brought back",
                text.contains("brought back", ignoreCase = true))
        }
        // The string resources are where a second copy would most plausibly hide.
        val strings = File("src/main/res/values/strings.xml")
        assertTrue("the string resources are missing", strings.isFile)
        assertFalse("a string resource keeps the caution",
            strings.readText().contains("brought back", ignoreCase = true))
    }

    @Test fun theRowsThemselvesCarryNothingElse() {
        // Name and effect only: no cost, no risk, nothing drawn under the effect line.
        for (name in listOf("/clear", "/new")) {
            val c = CommandCatalog.find(name)!!
            assertNull("$name still states what it costs", c.cost)
            assertTrue("$name lost its effect line", c.effect.isNotBlank())
            // What the sheet and /help print: the name, its aliases and the effect, nothing more.
            assertFalse("$name's help line carries the caution",
                CommandCatalog.helpText().contains("brought back", ignoreCase = true))
        }
    }
}
