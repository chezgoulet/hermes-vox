package com.hermesvox.core

/**
 * A strict JSON reader/writer for the entity wire format.
 *
 * Why not org.json: the stream consumer needs three things org.json does not give.
 *  1. Strict validity. The SSE framer asks "is the buffered data already one complete
 *     JSON value?" (a producer that omits the blank separator). org.json is lenient —
 *     it accepts `{a:1}` and single quotes — so it would split events the gateway
 *     never meant to split.
 *  2. The raw source text of a value. A function_call's `arguments` is passed through
 *     as its raw JSON text, quotes trimmed, exactly as the Go core did.
 *  3. A JVM unit test can run it. android.jar's org.json is a stub off-device.
 *
 * Decoding rules follow Go's encoding/json, which the parity fixtures were recorded
 * against: object keys match case-insensitively with the last occurrence winning,
 * a lone UTF-16 surrogate in a `\u` escape decodes to U+FFFD, and `null` reads as
 * the field's zero value.
 */
internal sealed class JNode(val raw: String)

internal class JObj(raw: String, val fields: List<Pair<String, JNode>>) : JNode(raw) {
    /** Go's field matching: the last key that equals [key] ignoring case. */
    operator fun get(key: String): JNode? = fields.lastOrNull { it.first.equals(key, ignoreCase = true) }?.second
}

internal class JArr(raw: String, val items: List<JNode>) : JNode(raw)
internal class JStr(raw: String, val value: String) : JNode(raw)
internal class JNum(raw: String) : JNode(raw)
internal class JBool(raw: String, val value: Boolean) : JNode(raw)
internal class JNull(raw: String) : JNode(raw)

/** The input is not JSON, or a value has the wrong type for the field reading it. */
internal class JsonException(message: String) : Exception(message)

internal object Json {

    /** Parses exactly one JSON value; anything but whitespace after it is an error
     *  unless [allowTrailing] (Go's Decoder.Decode reads the first value only). */
    fun parse(text: String, allowTrailing: Boolean = false): JNode {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (!allowTrailing && p.i != text.length) throw JsonException("trailing data at offset ${p.i}")
        return v
    }

    /** Go's json.Valid: one complete, strictly well-formed value. */
    fun valid(text: String): Boolean = try { parse(text); true } catch (_: JsonException) { false }

    // ---- typed field reads (a wrong type fails the whole decode, as in Go) ----

    fun str(o: JObj?, key: String): String = when (val n = o?.get(key)) {
        null, is JNull -> ""
        is JStr -> n.value
        else -> throw JsonException("field $key: want string")
    }

    fun obj(o: JObj?, key: String): JObj? = when (val n = o?.get(key)) {
        null, is JNull -> null
        is JObj -> n
        else -> throw JsonException("field $key: want object")
    }

    fun arr(o: JObj?, key: String): List<JNode> = when (val n = o?.get(key)) {
        null, is JNull -> emptyList()
        is JArr -> n.items
        else -> throw JsonException("field $key: want array")
    }

    /** An array element read as a struct: null is the zero struct. */
    fun elem(n: JNode): JObj? = when (n) {
        is JNull -> null
        is JObj -> n
        else -> throw JsonException("array element: want object")
    }

    // ---- writing ----

    /** Encodes maps (key order kept), lists, strings, booleans, numbers and null. */
    fun encode(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean, is Int, is Long -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString()); sb.append(':'); write(sb, value)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, value -> if (i > 0) sb.append(','); write(sb, value) }
                sb.append(']')
            }
            else -> throw IllegalArgumentException("cannot encode ${v.javaClass}")
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                // U+2028/2029 are legal JSON but end a line in JavaScript; Go escapes them too.
                ' ', ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() { while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++ }

        fun fail(what: String): Nothing = throw JsonException("$what at offset $i")

        fun value(): JNode {
            if (i >= s.length) fail("unexpected end")
            val start = i
            return when (s[i]) {
                '{' -> obj(start)
                '[' -> arr(start)
                '"' -> { val v = string(); JStr(s.substring(start, i), v) }
                't' -> { lit("true"); JBool("true", true) }
                'f' -> { lit("false"); JBool("false", false) }
                'n' -> { lit("null"); JNull("null") }
                else -> { number(); JNum(s.substring(start, i)) }
            }
        }

        private fun lit(word: String) {
            if (!s.startsWith(word, i)) fail("invalid literal")
            i += word.length
        }

        private fun obj(start: Int): JObj {
            i++ // {
            val fields = ArrayList<Pair<String, JNode>>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return JObj(s.substring(start, i), fields) }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail("expected object key")
                val k = string()
                ws()
                if (i >= s.length || s[i] != ':') fail("expected ':'")
                i++
                ws()
                fields.add(k to value())
                ws()
                if (i >= s.length) fail("unterminated object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return JObj(s.substring(start, i), fields) }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun arr(start: Int): JArr {
            i++ // [
            val items = ArrayList<JNode>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return JArr(s.substring(start, i), items) }
            while (true) {
                ws()
                items.add(value())
                ws()
                if (i >= s.length) fail("unterminated array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return JArr(s.substring(start, i), items) }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun string(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail("unterminated escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> unicode(sb)
                            else -> fail("invalid escape '\\$e'")
                        }
                    }
                    c < ' ' -> fail("control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun hex4(): Int {
            if (i + 4 > s.length) fail("short \\u escape")
            var v = 0
            repeat(4) {
                val d = Character.digit(s[i++], 16)
                if (d < 0) fail("invalid \\u escape")
                v = v * 16 + d
            }
            return v
        }

        private fun unicode(sb: StringBuilder) {
            val u = hex4()
            if (u in 0xD800..0xDBFF && s.startsWith("\\u", i)) {
                val save = i
                i += 2
                val lo = hex4()
                if (lo in 0xDC00..0xDFFF) { sb.append(u.toChar()).append(lo.toChar()); return }
                i = save // not a pair: the high half is lone, the next escape is read on its own
            }
            sb.append(if (u in 0xD800..0xDFFF) '�' else u.toChar())
        }

        private fun number() {
            if (i < s.length && s[i] == '-') i++
            if (i >= s.length) fail("invalid number")
            if (s[i] == '0') i++
            else if (s[i] in '1'..'9') { while (i < s.length && s[i].isAsciiDigit()) i++ }
            else fail("invalid character")
            if (i < s.length && s[i] == '.') {
                i++
                if (i >= s.length || !s[i].isAsciiDigit()) fail("invalid number")
                while (i < s.length && s[i].isAsciiDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i >= s.length || !s[i].isAsciiDigit()) fail("invalid number")
                while (i < s.length && s[i].isAsciiDigit()) i++
            }
        }

        private fun Char.isAsciiDigit() = this in '0'..'9'
    }
}
