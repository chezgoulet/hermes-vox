package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test


/**
 * #421 step 3: the bearer token that is the whole of the phone's authentication.
 *
 * Two properties, both load-bearing:
 *  * it is unguessable and stable (one per install, 128 bits);
 *  * comparison does not leak a prefix — a bearer check that returns on the first wrong byte tells
 *    an attacker on the tailnet how much of the secret they have.
 */
class PhoneMcpTokenTest {

    @Test fun a_token_is_thirty_two_lowercase_hex_characters() {
        val t = PhoneMcpToken.generate()
        assertEquals(PhoneMcpToken.LENGTH, t.length)
        assertTrue(t, t.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test fun two_tokens_are_not_the_same() {
        assertNotEquals(PhoneMcpToken.generate(), PhoneMcpToken.generate())
        val draws = (1..8).map { PhoneMcpToken.generate() }
        assertEquals("a token generator that repeats itself is broken", draws.size, draws.toSet().size)
    }

    @Test fun matches_only_the_exact_token() {
        val t = PhoneMcpToken.generate()
        assertTrue(PhoneMcpToken.matches(t, t))
        assertFalse(PhoneMcpToken.matches(t.uppercase(), t))
        assertFalse(PhoneMcpToken.matches(t + "0", t))
        assertFalse(PhoneMcpToken.matches(t.dropLast(1), t))
        assertFalse(PhoneMcpToken.matches("", t))
        assertFalse(PhoneMcpToken.matches(t, ""))
        assertFalse(PhoneMcpToken.matches(t.reversed(), t))
    }

    @Test fun a_near_miss_is_still_a_miss() {
        val t = "0123456789abcdef0123456789abcdef"
        val near = "0123456789abcdef0123456789abcdee"
        assertFalse(PhoneMcpToken.matches(near, t))
    }

    @Test fun the_config_snippet_names_the_address_the_token_and_one_tool() {
        val snippet = PhoneMcpConfig.snippet("100.101.102.103", "abcdef0123456789abcdef0123456789")
        assertTrue(snippet, snippet.contains("mcp_servers:"))
        assertTrue(snippet, snippet.contains("  vox:"))
        assertTrue(snippet, snippet.contains("url: \"http://100.101.102.103:8765/mcp\""))
        assertTrue(snippet, snippet.contains("Authorization: \"Bearer abcdef0123456789abcdef0123456789\""))
        assertTrue(snippet, snippet.contains("connect_timeout: 5"))
        assertTrue(snippet, snippet.contains("include: [phone]"))
        assertTrue("the default must be instant Tier 0", snippet.contains("trust: full"))
        assertTrue(snippet, snippet.contains("untrusted"))
    }

    @Test fun the_model_sees_the_tool_under_the_gateway_prefix() {
        assertEquals("mcp__vox__phone", PhoneMcpConfig.MODEL_TOOL_NAME)
    }

    @Test fun notes_round_trip_and_a_bad_entry_costs_only_itself() {
        val notes = listOf(
            PhoneEngine.Note(
                "note-1", "plumber Thursday 9am", 1_700_000_000_000L, PhoneEngine.Origin.MIND,
            ),
            PhoneEngine.Note(
                "note-2", "quotes \"inside\" and a\nnewline", 1_700_000_001_000L,
                PhoneEngine.Origin.OWNER,
            ),
        )
        assertEquals(notes, Notes.decode(Notes.encode(notes)))
        assertEquals(emptyList<PhoneEngine.Note>(), Notes.decode(null))
        assertEquals(emptyList<PhoneEngine.Note>(), Notes.decode(""))
        assertEquals(emptyList<PhoneEngine.Note>(), Notes.decode("not json"))
        assertEquals(emptyList<PhoneEngine.Note>(), Notes.decode("""{"not":"an array"}"""))
        val mixed = """[{"id":"note-1","text":"keep me","at":1},{"id":"","text":"no id","at":2},{"text":"no id"},"junk"]"""
        assertEquals(1, Notes.decode(mixed).size)
        assertEquals("keep me", Notes.decode(mixed)[0].text)
    }

    @Test fun a_note_line_is_bounded_and_quote_free() {
        val line = Notes.oneLine("  hello\n\"world\"  " + "x".repeat(200), max = 40)
        assertFalse(line.contains("\""))
        assertFalse(line.contains("\n"))
        assertEquals(40, line.length)
    }
}
