package com.hermesvox

import com.hermesvox.core.JArr
import com.hermesvox.core.JBool
import com.hermesvox.core.JNum
import com.hermesvox.core.JObj
import com.hermesvox.core.JStr
import com.hermesvox.core.Json

/**
 * PhoneAudit — #429 step 1: the record the owner checks the agent's claims against.
 *
 * Every call the mind makes through the MCP `phone` tool lands here, **including the refusals**.
 * That is the point: a phone that only recorded successes could not answer "did my agent really
 * try to delete that?" — and an agent that misreports what it did would have nothing contradicting
 * it. A refusal is evidence too.
 *
 * ## What is stored, and what is not
 *
 * Time, action, the arguments that describe the call, the outcome, and the undo id when the action
 * carries one. **Arguments are summarised, not copied.** A note's text and an event's title are the
 * caller's words and do not belong in a list that gets shown on a screen or attached to a report,
 * so each is stored as a length ("42 chars"); every other argument today is a duration, a clock
 * time, a boolean, a level, a direction, a file name or an id. A `file_list` row is the folder, the
 * relative path and the count (`Downloads, 12 entries`) — never a name it saw. Values are clipped so
 * a hostile argument cannot make the list unreadable.
 *
 * The audit never stores a token, a message body or a file's contents. A `message` text **does**
 * become an argument now, so [summarise] stores it as its length exactly as it does a note's; a
 * handoff's recipient is stored because the owner needs to see who the draft was for. [summarise]
 * is the one function a future action must go through.
 *
 * **The two read groups are narrower than [summarise].** A notifications summary stores
 * `notification_count`; a single read stores the `app` and `notification_count=1`, never the
 * title, body or id it returned. A contact lookup stores `count`, never the query name, a display
 * name or a number. Those fields are the owner's data, not a fact this list may keep.
 *
 * Pure JVM. [PhoneAuditStore] is the Android side that persists it; this file is what
 * [PhoneAuditTest] pins.
 */
object PhoneAudit {

    /** How many calls the sheet remembers. Enough for a session's worth of work. */
    const val MAX = 50

    /** The longest a single displayed argument may be. */
    const val MAX_ARG_CHARS = 80

    /** One call, as the owner reads it. */
    data class Entry(
        val atEpochMs: Long,
        val action: String,
        /** Rendered `key=value` pieces; already summarised and safe to display. */
        val arguments: Map<String, String>,
        val ok: Boolean,
        /** "done", or the stable reason a refusal carried. */
        val outcome: String,
        /** Present only when the action really carried an undo. */
        val undoId: String? = null,
    )

    /**
     * The argument summary. `action` is its own column, a text body becomes its length, and every
     * value is clipped. Order is the call's own order, so the row reads like the call did.
     */
    fun summarise(args: Map<String, Any?>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((key, value) in args) {
            if (key == "action" || value == null) continue
            val rendered = when {
                key == "text" -> "${(value as? String)?.length ?: 0} chars"
                // An event's title is the owner's words, so the audit keeps its length, not it.
                key == "title" -> "${(value as? String)?.length ?: 0} chars"
                value is String -> value
                else -> value.toString()
            }
            out[key] = clip(rendered)
        }
        return out
    }

    /** The entry for one dispatch: the action, its safe arguments, the outcome, and its undo. */
    fun entry(atEpochMs: Long, args: Map<String, Any?>, result: PhoneResult): Entry {
        val undoable = result.ok && result.facts["undoable"] == true
        return Entry(
            atEpochMs = atEpochMs,
            action = result.action.ifBlank { (args["action"] as? String).orEmpty() },
            arguments = when {
                // A notifications summary is a count. A single read names the app and counts one.
                // Neither row ever carries a title, a body, or any id — the owner cannot see the
                // listener's ids, so they are not a fact this list may keep.
                result.action == "notifications" -> notificationCountRow(result)
                result.action == "notification_read" -> notificationReadRow(result)
                // A contact lookup is the owner's address book, so the row is its count alone:
                // never the name that was asked for and never a number or display name it found.
                result.action == "contact" -> contactRow(result)
                else -> summarise(args).let { summary ->
                    // A listing's row is `Downloads, 12 entries`: the folder it listed, the
                    // relative path inside it, and how many entries that page held. **Never a
                    // name** — the names in a folder are the owner's, and the count is all the
                    // record needs.
                    if (result.ok && result.action == "file_list") listingRow(summary, result) else summary
                }
            },
            ok = result.ok,
            outcome = if (result.ok) "done" else (result.reason ?: "refused"),
            undoId = if (undoable) result.id else null,
        )
    }

    /** The summary row: `notification_count`, never a title, a body or an id. */
    private fun notificationCountRow(result: PhoneResult): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (result.ok) {
            (result.facts["count"] as? Number)?.let {
                out["notification_count"] = "${it.toLong()}"
            }
        }
        return out
    }

    /**
     * The read row: the app and `notification_count` of one. **Never the id** — an id the owner
     * cannot see is not a fact to keep — and never the title or body it returned.
     */
    private fun notificationReadRow(result: PhoneResult): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (!result.ok) return out
        (result.facts["app"] as? String)?.takeIf { it.isNotBlank() }?.let { out["app"] = clip(it) }
        out["notification_count"] = "1"
        return out
    }

    /**
     * The contact row: how many people matched, and nothing else. The query name, the display
     * names and the numbers are the owner's address book — the audit is a list that gets shown
     * and attached to reports, so it keeps only the count. A refusal has no count to claim.
     */
    private fun contactRow(result: PhoneResult): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (result.ok) {
            (result.facts["count"] as? Number)?.let { out["count"] = "${it.toLong()}" }
        }
        return out
    }

    /** Adds the folder, path and count a listing actually returned; empty path reads `/` (the root). */
    private fun listingRow(
        summary: Map<String, String>,
        result: PhoneResult,
    ): Map<String, String> {
        val out = LinkedHashMap(summary)
        (result.facts["folder"] as? String)?.let { out["folder"] = clip(it) }
        (result.facts["path"] as? String)?.let { out["path"] = clip(it.ifEmpty { "/" }) }
        (result.facts["count"] as? Number)?.let { out["count"] = "${it.toLong()} entries" }
        return out
    }

    /** The stored form: a JSON array, small enough for one preference value. */
    fun encode(entries: List<Entry>): String =
        Json.encode(
            entries.map { e ->
                linkedMapOf<String, Any?>(
                    "at" to e.atEpochMs,
                    "action" to e.action,
                    "arguments" to e.arguments,
                    "ok" to e.ok,
                    "outcome" to e.outcome,
                    "undo" to e.undoId,
                )
            }
        )

    /** Reads what [encode] wrote. A malformed entry is dropped; the list is never fatal. */
    fun decode(stored: String?): List<Entry> {
        if (stored.isNullOrBlank()) return emptyList()
        val root = try {
            Json.parse(stored)
        } catch (_: Exception) {
            return emptyList()
        }
        val items = (root as? JArr)?.items ?: return emptyList()
        val out = ArrayList<Entry>(items.size)
        for (item in items) {
            val o = item as? JObj ?: continue
            val action = (o["action"] as? JStr)?.value?.trim().orEmpty()
            if (action.isEmpty()) continue
            val at = (o["at"] as? JNum)?.raw?.toLongOrNull() ?: 0L
            val ok = (o["ok"] as? JBool)?.value ?: false
            val outcome = (o["outcome"] as? JStr)?.value ?: if (ok) "done" else "refused"
            val undo = (o["undo"] as? JStr)?.value
            val arguments = LinkedHashMap<String, String>()
            (o["arguments"] as? JObj)?.fields?.forEach { (k, v) ->
                (v as? JStr)?.value?.let { arguments[k] = clip(it) }
            }
            out.add(Entry(at, action, arguments, ok, outcome, undo))
        }
        return out
    }

    /** Keep a value on one line and bounded. */
    fun clip(value: String): String {
        val oneLine = value.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= MAX_ARG_CHARS) oneLine else oneLine.take(MAX_ARG_CHARS - 1) + "…"
    }
}
