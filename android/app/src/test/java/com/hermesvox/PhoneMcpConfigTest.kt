package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #421: the config the phone copies and the config the document prints are one thing.
 *
 * A snippet that drifts from what the server speaks is a support ticket. The renderer is the single
 * source; this test reads the YAML block out of `docs/MCP-PHONE.md` and compares it, byte for byte,
 * with [PhoneMcpConfig.docSnippet]. It also pins the two shapes a careless renderer breaks: the
 * address/port/path and the token header.
 */
class PhoneMcpConfigTest {

    private fun repoFile(rel: String): File =
        listOf(File("../../$rel"), File("../$rel"), File(rel))
            .firstOrNull { it.isFile }
            ?: error("$rel not found from ${File(".").absolutePath}")

    private fun documentedBlock(): String {
        val text = repoFile("docs/MCP-PHONE.md").readText()
        val section = text.substringAfter("## The gateway config (the manual fallback)")
        assertTrue("the document must keep its gateway-config section", section.isNotBlank())
        val start = section.indexOf("```yaml")
        assertTrue("the section must show a yaml block", start >= 0)
        val after = section.substring(start + "```yaml".length).removePrefix("\n")
        return after.substringBefore("\n```").trimEnd()
    }

    @Test fun the_documented_block_is_the_one_the_phone_renders() {
        assertEquals(PhoneMcpConfig.docSnippet().trimEnd(), documentedBlock())
    }

    @Test fun the_block_names_the_address_port_path_token_and_shape() {
        val snippet = PhoneMcpConfig.snippet("100.101.102.103", "abcdef0123456789abcdef0123456789")
        assertTrue(snippet, snippet.contains("mcp_servers:"))
        assertTrue(snippet, snippet.contains("  vox:"))
        assertTrue(snippet, snippet.contains("url: \"http://100.101.102.103:8765/mcp\""))
        assertTrue(snippet, snippet.contains("Authorization: \"Bearer abcdef0123456789abcdef0123456789\""))
        assertTrue(snippet, snippet.contains("connect_timeout: 5"))
        assertTrue(snippet, snippet.contains("tool_timeout: 30"))
        assertTrue(snippet, snippet.contains("include: [phone]"))
        assertTrue("the default must be instant Tier 0", snippet.contains("trust: full"))
        assertTrue(snippet, snippet.contains("untrusted"))
    }

    @Test fun an_ipv6_tailnet_address_is_bracketed_once() {
        assertEquals("[fd7a:115c:a1e0::1]", PhoneMcpConfig.hostForUrl("fd7a:115c:a1e0::1"))
        assertEquals("[fd7a:115c:a1e0::1]", PhoneMcpConfig.hostForUrl("[fd7a:115c:a1e0::1]"))
        assertEquals("100.101.102.103", PhoneMcpConfig.hostForUrl("100.101.102.103"))
        assertTrue(PhoneMcpConfig.snippet("fd7a:115c:a1e0::1").contains("http://[fd7a:115c:a1e0::1]:8765/mcp"))
        assertFalse(PhoneMcpConfig.snippet("100.101.102.103").contains("http://["))
    }

    @Test fun the_document_and_the_renderer_share_the_placeholder() {
        val block = documentedBlock()
        assertTrue("the doc block must carry the generated placeholder", block.contains(PhoneMcpConfig.DOC_TOKEN))
        assertFalse("the doc must not grow a second token shape", block.contains("<your phone's token>"))
    }
}
