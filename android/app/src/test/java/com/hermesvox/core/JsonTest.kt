package com.hermesvox.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The strict reader matches Go's encoding/json on the cases the stream depends on. */
class JsonTest {

    @Test
    fun `valid is strict, like json Valid`() {
        for (ok in listOf("{}", "[]", "\"x\"", "0", "-1.5e+3", "true", "null", """ {"a":[1,{"b":null}]} """)) {
            assertTrue(ok, Json.valid(ok))
        }
        for (bad in listOf("", "{", "{a:1}", "{'a':1}", "[1,]", "01", "1.", "\"\t\"", "{\"a\":1} x", "nul", "\"\\x\"")) {
            assertFalse(bad, Json.valid(bad))
        }
        // The SSE framer's question: is the first data line already a whole payload?
        assertFalse(Json.valid("""{"type":"response.output_text.delta","""))
    }

    @Test
    fun `values keep their raw text`() {
        val o = Json.parse("""{"arguments": {"q" : 1}, "s":"a\"b"}""") as JObj
        assertEquals("""{"q" : 1}""", o["arguments"]!!.raw)
        assertEquals("\"a\\\"b\"", o["s"]!!.raw)
        assertEquals("a\"b", Json.str(o, "s"))
    }

    @Test
    fun `keys match case-insensitively and the last one wins`() {
        val o = Json.parse("""{"type":"a","Type":"b"}""") as JObj
        assertEquals("b", Json.str(o, "type"))
    }

    @Test
    fun `escapes and surrogates decode like Go`() {
        assertEquals("😀", (Json.parse("\"\\ud83d\\ude00\"") as JStr).value)
        assertEquals("\uFFFDx", (Json.parse("\"\\ud83dx\"") as JStr).value)
        assertEquals("\uFFFD", (Json.parse("\"\\ude00\"") as JStr).value)
        assertEquals("a/b\n", (Json.parse("\"a\\/b\\n\"") as JStr).value)
    }

    @Test
    fun `a wrong field type fails the read`() {
        val o = Json.parse("""{"type":5}""") as JObj
        try { Json.str(o, "type"); fail() } catch (_: JsonException) {}
        assertEquals("", Json.str(Json.parse("""{"type":null}""") as JObj, "type"))
    }

    @Test
    fun `encode round-trips`() {
        val v = linkedMapOf("s" to "q\"\\\n\u0001\u2028", "b" to true, "n" to null, "l" to listOf(1, "x"))
        val back = Json.parse(Json.encode(v)) as JObj
        assertEquals("q\"\\\n\u0001\u2028", Json.str(back, "s"))
        assertTrue(back["n"] is JNull)
        assertEquals(2, (back["l"] as JArr).items.size)
    }
}
