package com.hermesvox

import com.hermesvox.HostEntity.Answer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T7: the entity's name comes from the host, not from what the user typed.
 *
 * Everything here is the parse-and-adopt decision against a *fake* body — the exact shape
 * this gateway answers (`GET /v1/models` → `data[0].id`). What can be proven off-device is
 * the contract: when the host wins, when the typed fallback stands, and when nothing is
 * saved at all. The request itself is the probe the connection flow already makes; it is
 * not exercised here.
 */
class HostEntityTest {

    /** The real gateway's answer (mikoa gateway, 2026-11-02). */
    private val served =
        """{"object": "list", "data": [{"id": "hermes-agent", "object": "model", "created": 1793657100, "owned_by": "hermes", "permission": [], "root": "hermes-agent", "parent": null}]}"""

    private fun named(body: String = served): Answer.Named = HostEntity.answer(200, body) as Answer.Named

    // ---- the decision: 200 names, 200 with no id falls back, non-200 saves nothing ----

    @Test fun a200WithDataZeroIdNamesTheEntity() {
        val a = named()
        assertEquals("hermes-agent", a.name)
        assertEquals("hermes-agent", HostEntity.name(a, "typed"))
        assertNull("a named host needs no fallback line", HostEntity.line(a))
    }

    @Test fun theIdIsTrimmedAndAnUnknownFieldIsIgnored() {
        val a = named("""{"object":"list","data":[{"id":"  hermes-agent  ","future":{"deep":[1,2,3]}}]}""")
        assertEquals("hermes-agent", a.name)
    }

    /**
     * A 200 means the connection is real. If the body names nothing, that is said in one
     * line and the typed value stays — the client does not invent a name from a top-level
     * `id`, a `name`, or any other shape.
     */
    @Test fun a200WithAnUnexpectedShapeFallsBackAndInventsNothing() {
        val bodies = listOf(
            "", "not json", "{}", "[]", "null",
            """{"data":[]}""",
            """{"data":[{}]}""",
            """{"data":[{"id":""}]}""",
            """{"data":[{"id":"   "}]}""",
            """{"data":[{"id":42}]}""",
            """{"data":[{"id":null}]}""",
            """{"data":[null]}""",
            """{"data":"hermes-agent"}""",
            """{"data":{"id":"hermes-agent"}}""",
            """{"id":"hermes-agent"}""",
            """{"models":[{"id":"hermes-agent"}]}""",
            """{"data":[{"name":"hermes-agent"}]}""",
            """{"data":[{"id":"${"x".repeat(HostEntity.NAME_MAX + 1)}"}]}""",
        )
        for (body in bodies) {
            val a = HostEntity.answer(200, body)
            assertTrue(body, a is Answer.Unnamed)
            assertEquals(body, HostEntity.FALLBACK_LINE, HostEntity.line(a))
            assertEquals(
                "connected, but the host did not name itself",
                HostEntity.line(a),
            )
            assertEquals("the typed value is the fallback, not an invented id", "typed", HostEntity.name(a, "typed"))
        }
    }

    @Test fun aNon200SavesNothing() {
        for (code in listOf(400, 401, 403, 404, 500, 502, -1)) {
            val a = HostEntity.answer(code, served)
            assertTrue("$code", a is Answer.Refused)
            assertNull("$code: no name may be saved", HostEntity.name(a, "typed"))
            assertNull("$code: no connected line", HostEntity.line(a))
        }
    }

    /** The complaint, in one assertion: the host's answer overrides what was typed. */
    @Test fun anIdThatDiffersFromWhatTheUserTypedWins() {
        val a = named("""{"data":[{"id":"athena"}]}""")
        assertEquals("athena", HostEntity.name(a, "hermes-agent"))
        assertEquals("athena", a.name)
    }

    /** A host that answers 200 but is silent keeps the old value; it does not rewrite it. */
    @Test fun anUnnamedHostKeepsTheSavedNameAsTheFallback() {
        val a = HostEntity.answer(200, "{}")
        assertEquals("hermes-agent", HostEntity.name(a, "hermes-agent"))
        assertNull("an empty fallback is not a name", HostEntity.name(a, ""))
    }
}
