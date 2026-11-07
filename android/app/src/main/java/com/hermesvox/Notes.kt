package com.hermesvox

import com.hermesvox.core.JArr
import com.hermesvox.core.JNum
import com.hermesvox.core.JObj
import com.hermesvox.core.JStr
import com.hermesvox.core.Json

/**
 * Notes — the on-disk form of the notes the phone keeps (Tier 1, #421).
 *
 * A note is the one local action with a life after the call: it is written to SharedPreferences as
 * a small JSON list and read back on the next start, so "note that the plumber is coming Thursday"
 * survives the call it was said in, is reported to the mind on the next turn through the presence
 * context's `local_actions` line, and can be undone by the `phone` tool's `undo`.
 *
 * Each entry carries **who wrote it** (`owner`/`mind`, see [PhoneEngine.Origin]) so that a restart
 * restores the note behind the same door. An entry with no tag predates the tag: notes were then
 * only ever written by the mind, so it decodes as [PhoneEngine.Origin.MIND]. The encoding always
 * writes the tag.
 *
 * Decoding is deliberately forgiving in one direction only: a malformed stored entry is skipped, so
 * a corrupt preference costs the note and never the app. Encoding never fails on content — the
 * writer escapes it.
 *
 * Pure JVM, unit-tested in [NotesTest].
 */
object Notes {

    /** What is stored: id, text, when it was made (epoch millis), and who wrote it. */
    fun encode(notes: List<PhoneEngine.Note>): String =
        Json.encode(
            notes.map { n ->
                linkedMapOf<String, Any?>(
                    "id" to n.id,
                    "text" to n.text,
                    "at" to n.atEpochMs,
                    "origin" to n.origin.id,
                )
            }
        )

    /** Reads what [encode] wrote. Null or blank is an empty list; a bad entry is dropped. */
    fun decode(stored: String?): List<PhoneEngine.Note> {
        if (stored.isNullOrBlank()) return emptyList()
        val root = try {
            Json.parse(stored)
        } catch (_: Exception) {
            return emptyList()
        }
        val items = (root as? JArr)?.items ?: return emptyList()
        val out = ArrayList<PhoneEngine.Note>(items.size)
        for (item in items) {
            val o = item as? JObj ?: continue
            val id = (o["id"] as? JStr)?.value?.trim().orEmpty()
            val text = (o["text"] as? JStr)?.value.orEmpty()
            val at = (o["at"] as? JNum)?.raw?.toLongOrNull() ?: 0L
            if (id.isEmpty() || text.isEmpty()) continue
            // No tag: written before the tag existed, when only the mind could write a note.
            val origin = PhoneEngine.Origin.of((o["origin"] as? JStr)?.value) ?: PhoneEngine.Origin.MIND
            out.add(PhoneEngine.Note(id, text, at, origin))
        }
        return out
    }

    /** The bounded, quote-free form a context line can carry. */
    fun oneLine(text: String, max: Int = 60): String =
        text.replace(Regex("\\s+"), " ").replace("\"", "'").trim().take(max)
}
