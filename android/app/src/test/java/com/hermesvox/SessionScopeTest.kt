package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Multi-user contract for the entity scope (X-Hermes-Session-Key): several
 * people share ONE gateway behind ONE bearer key, so the scope is the only thing
 * that says who is talking. These are behavior contracts, not snapshots — each
 * one fails if the scope is declared when it shouldn't be, rewritten when it
 * shouldn't be, or silently dropped when it was usable.
 */
class SessionScopeTest {

    @Test fun blank_is_not_declared_so_the_gateway_default_stands() {
        // An install that declares nothing must stay on the gateway's
        // per-transcript default — the pre-existing behavior, unchanged.
        assertEquals("", SessionScope.normalize(""))
        assertEquals("", SessionScope.normalize("   "))
        assertFalse(SessionScope.isDeclared(""))
        assertNull(SessionScope.validationError(""))
    }

    @Test fun a_declared_scope_is_used_verbatim() {
        // No case folding, no punctuation mangling: the scope IS the identity,
        // and the gateway echoes it back.
        val scope = "agent:vox:tablet:member-42"
        assertEquals(scope, SessionScope.normalize(scope))
        assertTrue(SessionScope.isDeclared(scope))
        assertNull(SessionScope.validationError(scope))
    }

    @Test fun surrounding_whitespace_is_trimmed_not_kept() {
        // A pasted value with a trailing space must not become a DIFFERENT
        // scope from the same value without one (two scopes = two memories).
        assertEquals("agent:vox:tablet:cody", SessionScope.normalize("  agent:vox:tablet:cody  "))
        assertEquals("agent:vox:tablet:cody", SessionScope.normalize("agent:vox:tablet:cody\n"))
    }

    @Test fun the_gateway_documented_example_shape_is_accepted() {
        // The API server's own documented example — if this ever fails, the
        // rule has drifted from the gateway.
        val documented = "agent:main:webui:dm:user-42"
        assertEquals(documented, SessionScope.normalize(documented))
        assertTrue(SessionScope.isDeclared(documented))
    }

    @Test fun the_length_cap_matches_the_gateway_boundary() {
        // 256 is allowed (the gateway's cap is inclusive), 257 is not.
        val atCap = "s".repeat(SessionScope.MAX_LEN)
        assertEquals(atCap, SessionScope.normalize(atCap))
        assertNull(SessionScope.validationError(atCap))

        val overCap = "s".repeat(SessionScope.MAX_LEN + 1)
        assertEquals("", SessionScope.normalize(overCap))
        assertNotNull(SessionScope.validationError(overCap))
    }

    @Test fun over_length_is_rejected_not_truncated() {
        // Truncating would silently point the person at somebody else's memory
        // scope — reject, and tell them.
        val overCap = "s".repeat(SessionScope.MAX_LEN + 40)
        assertFalse(SessionScope.isDeclared(overCap))
        val err = SessionScope.validationError(overCap)
        assertNotNull(err)
        assertTrue(err!!.contains("${SessionScope.MAX_LEN}"))
    }

    @Test fun control_characters_are_rejected_not_stripped() {
        // CR/LF/NUL are header-injection material and the gateway rejects them;
        // stripping them would silently alter the declared identity.
        for (bad in listOf("a\rb", "a\nb", "a\u0000b")) {
            assertEquals("", SessionScope.normalize(bad))
            assertFalse(SessionScope.isDeclared(bad))
            assertTrue(SessionScope.validationError(bad)!!.contains("Control characters"))
        }
    }

    @Test fun the_validator_is_a_superset_of_the_gateway_rule() {
        // TAB and DEL are not in the gateway's reject list (CR/LF/NUL), but they
        // cannot travel safely through a header either. The app refuses them
        // too, and the message must describe what is actually wrong — it used to
        // claim "line breaks", which was untrue for a tab.
        for (tabish in listOf("a\tb", "a\u007Fb")) {
            assertEquals("", SessionScope.normalize(tabish))
            assertNotNull(SessionScope.validationError(tabish))
            assertTrue(SessionScope.validationError(tabish)!!.contains("Control characters"))
        }
    }

    @Test fun the_header_name_is_the_one_the_gateway_advertises() {
        // GET /v1/capabilities -> features.session_key_header
        assertEquals("X-Hermes-Session-Key", SessionScope.HEADER)
        assertEquals("session_scope", SessionScope.PREF)
    }
}
