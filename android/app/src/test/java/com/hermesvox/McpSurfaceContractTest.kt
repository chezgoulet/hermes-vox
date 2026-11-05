package com.hermesvox

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The approval lane's two requirements, pinned in the contract document that will govern it.
 *
 * `docs/MCP-SURFACE.md` is where a reader learns what the MCP surface does. The approval card is
 * not built, so these are **requirements**, not descriptions — and a requirement that quietly
 * disappears from the document is a requirement the next lane never sees. This test is the
 * source-level half of the doc-truth check: it fails if the section is removed, if either rule is
 * softened, or if the document starts implying the card exists today.
 *
 * It is deliberately prose-level rather than a `DOC-TRUTH.yml` entry: that ledger matches a
 * document claim against code, and there is no code for these yet — the absence is the point.
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
    fun the_approval_lane_requirements_are_stated_and_marked_as_required() {
        val required = section("### Required of the approval lane")
        assertTrue(
            "the section must say these are requirements, not what exists today",
            required.contains("requirements the approval card must satisfy") &&
                required.contains("not descriptions of") &&
                required.contains("anything built today"),
        )

        assertTrue(
            "file_offer must be required to join the approval tier",
            required.contains("`file_offer` joins the approval tier"),
        )
        assertTrue(
            "the file card must show name, type and size",
            required.contains("one card per file") &&
                required.contains("name, type and size"),
        )
        assertTrue(
            "handing a file to the agent is an export the folder grant only makes possible",
            required.contains("exports it") && required.contains("possible, not intended"),
        )

        assertTrue(
            "the card must not be actionable on a locked phone",
            required.contains("not actionable on a locked phone"),
        )
        assertTrue(
            "its notification must be private",
            required.contains("`VISIBILITY_PRIVATE`"),
        )
        assertTrue(
            "approving must require an unlocked device",
            required.contains("requires the device to be unlocked") ||
                required.contains("approving requires the device to be unlocked"),
        )
    }

    @Test
    fun the_document_does_not_claim_the_card_exists() {
        val required = section("### Required of the approval lane")
        assertTrue(
            "the section must not read as though the card exists",
            required.contains("when it lands") && !required.contains("**Built**"),
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
