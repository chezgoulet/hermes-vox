package com.hermesvox

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The approval card's two safety rules, pinned in the contract document.
 *
 * `docs/MCP-SURFACE.md` is where a reader learns what the MCP surface does. These rules used to be
 * written as requirements for a later lane; the card has landed, so the section must now describe
 * the shipped behaviour. A behaviour that quietly disappears from the document is one the next
 * change never sees, so this test fails if either rule is removed or softened.
 */
class McpSurfaceContractTest {

    private fun doc(): String = repoFile("docs/MCP-SURFACE.md").readText()

    private fun section(heading: String): String {
        val text = doc()
        val start = text.indexOf(heading)
        assertTrue("$heading must exist in docs/MCP-SURFACE.md", start >= 0)
        val next = text.indexOf("\n## ", start + heading.length)
        val end = if (next < 0) text.length else next
        // Prose wraps; the assertions are about what the sentence says, not where the line breaks.
        return text.substring(start, end).replace(Regex("\\s+"), " ")
    }

    @Test
    fun the_approval_card_behaviour_is_stated_as_enforced() {
        val enforced = section("### What the approval card enforces")
        assertTrue(
            "the section must describe shipped behaviour, not a future lane",
            enforced.contains("behaviours the shipped card implements") &&
                enforced.contains("not requirements for a later lane"),
        )

        assertTrue(
            "file_offer must be stated as part of the approval tier",
            enforced.contains("`file_offer` is in the approval tier"),
        )
        assertTrue(
            "the file card must show name, type and size",
            enforced.contains("one card per file") &&
                enforced.contains("name, type and size"),
        )
        assertTrue(
            "handing a file to the agent is an export the folder grant only makes possible",
            enforced.contains("exports it") && enforced.contains("possible, not intended"),
        )

        assertTrue(
            "the card must be stated as not actionable on a locked phone",
            enforced.contains("not actionable on a locked phone"),
        )
        assertTrue(
            "its notification must be private",
            enforced.contains("`VISIBILITY_PRIVATE`"),
        )
        assertTrue(
            "approving must be stated as requiring an unlocked device",
            enforced.contains("checks the keyguard") &&
                enforced.contains("approval_locked"),
        )
    }

    @Test
    fun the_document_also_pins_the_tier_and_the_card_wording() {
        val text = doc().replace(Regex("\\s+"), " ")
        assertTrue(
            "file_offer must be described as an export in the Tier 2 section",
            text.contains("`file_offer` is here because offering a file **exports** it"),
        )
        assertTrue(
            "the file card's wording is in the document",
            text.contains("Your agent wants to read `report.pdf` (PDF, 240 KB). Approve?"),
        )
        assertTrue(
            "the reason vocabulary includes the locked refusal",
            text.contains("| `approval_locked` |"),
        )
    }

    private fun repoFile(path: String): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            val f = File(d, path)
            if (f.isFile) return f
            d = d.parentFile
        }
        error("$path not found above ${System.getProperty("user.dir")}")
    }
}
