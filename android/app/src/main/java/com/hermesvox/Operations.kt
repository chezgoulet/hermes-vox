package com.hermesvox

import com.hermesvox.core.JArr
import com.hermesvox.core.JNum
import com.hermesvox.core.JObj
import com.hermesvox.core.JStr
import com.hermesvox.core.Json
import com.hermesvox.core.JsonException

/**
 * Operations — what the agent is doing, in one line of plain language (#281).
 *
 * The stream already says which tool ran and on what: a `function_call` item carries the
 * tool's `name` and `arguments`, and a `function_call_output` carries its result
 * (`docs/NOTES-api-events.md`). Before this, both reached the screen as developer text in
 * the console (`◆ tool: …`) and as a motif the being cannot name. The state audit
 * (`docs/STATE-AUDIT.md`, #280) concluded the being cannot carry *what*, so this turns the
 * same two facts into the words the state line shows: which tool, in plain language, on
 * what — running, done, or failed and why — and, when a step is long, that it is still
 * moving.
 *
 * It translates; it never invents (D25/D26). A step is "done" because its output arrived,
 * not because the world changed, and it is "failed" only when the output says so in the
 * shape Hermes uses for a failed tool: a top-level `error` (`tools/registry.py tool_error`)
 * or a non-zero `exit_code` (the terminal tool). Free text is never read for failure words.
 *
 * History is deliberately unchanged (STATE-AUDIT F12, #281): the transcript stays free of
 * operations. The line is the state while the step is current; a completed step is not
 * replayed, and the reply is the record. A log viewer is the thing #281 forbids.
 *
 * Pure JVM, so the vocabulary is proven off-device.
 */
object Operations {

    enum class Phase { RUNNING, DONE, FAILED }

    /** One tool step: the present-tense line, its past tense, and the thing it acts on. */
    data class Step(val tool: String, val doing: String, val done: String, val target: String)

    /** When the line starts moving. It must move BEFORE the being stalls
     *  ([MotionState.STALL_MS], 2.5 s): the words are proof, the aura is only a sign
     *  (STATE-AUDIT #281 item 4). The first move is "still working"; an elapsed count
     *  follows in [TICK_S] steps, so the line never claims more than time passed. */
    const val LONG_MS = 2_000L

    /** The elapsed count's step, in seconds. */
    const val TICK_S = 5L

    /** The target is a hint, not a transcript: one short line, on a screen and a notification. */
    const val TARGET_CHARS = 40

    /** Argument keys that name what a step acts on, most specific first. Content keys
     *  (an email's body, a file's contents) are deliberately absent: the line names the
     *  object, it does not read it out. */
    private val TARGET_KEYS = listOf(
        "query", "q", "url", "path", "file_path", "pattern", "command", "code_summary",
        "goal", "subject", "title", "name", "to", "calendar_id", "date", "time_min", "action",
    )

    private class Verb(val doing: String, val done: String)

    /** Hermes's built-in tools, by name. A tool not listed is humanised from its name. */
    private val BUILT_IN: Map<String, Verb> = mapOf(
        "terminal" to Verb("Running a command", "Ran a command"),
        "execute_code" to Verb("Running code", "Ran code"),
        "process_manage" to Verb("Managing a process", "Managed a process"),
        "web_search" to Verb("Searching the web", "Searched the web"),
        "x_search" to Verb("Searching X", "Searched X"),
        "web_extract" to Verb("Reading a web page", "Read a web page"),
        "browser_navigate" to Verb("Opening a page", "Opened a page"),
        "read_file" to Verb("Reading a file", "Read a file"),
        "write_file" to Verb("Writing a file", "Wrote a file"),
        "patch" to Verb("Editing a file", "Edited a file"),
        "search_files" to Verb("Searching files", "Searched files"),
        "memory" to Verb("Checking memory", "Checked memory"),
        "session_search" to Verb("Searching past conversations", "Searched past conversations"),
        "skills_list" to Verb("Looking up skills", "Looked up skills"),
        "skill_view" to Verb("Reading a skill", "Read a skill"),
        "skill_manage" to Verb("Updating a skill", "Updated a skill"),
        "delegate_task" to Verb("Handing off a task", "Handed off a task"),
        "vision_analyze" to Verb("Looking at an image", "Looked at an image"),
        "video_analyze" to Verb("Watching a video", "Watched a video"),
        "image_generate" to Verb("Making an image", "Made an image"),
        "video_generate" to Verb("Making a video", "Made a video"),
        "text_to_speech" to Verb("Preparing audio", "Prepared audio"),
        "cronjob_manage" to Verb("Scheduling a job", "Scheduled a job"),
        "todo_list" to Verb("Updating the plan", "Updated the plan"),
        "clarify" to Verb("Asking you something", "Asked you something"),
    )

    /** Describe a `function_call` from its name and raw JSON arguments. */
    fun step(name: String, arguments: String): Step {
        val tool = name.trim()
        val args = parseObj(arguments)
        val verb = memoryWrite(tool, args)
            ?: BUILT_IN[tool]
            ?: if (tool.startsWith("browser_")) Verb("Using the browser", "Used the browser") else null
            ?: domainVerb(tool, args)
            ?: humanised(tool)
        return Step(tool, verb.doing, verb.done, target(tool, args))
    }

    /**
     * The failure a `function_call_output` reports, or null when it reports none. Only the
     * two shapes Hermes uses for a failed tool count: `{"error": "<reason>"}` and a
     * non-zero `exit_code`. Anything else — prose, an empty result — is not a failure.
     */
    fun failure(output: String): String? {
        val o = parseObj(output) ?: return null
        when (val e = o["error"]) {
            is JStr -> if (e.value.isNotBlank()) return oneLine(e.value, 80)
            is JObj -> return oneLine(str(e, "message").ifBlank { "error" }, 80)
            else -> {}
        }
        val code = (o["exit_code"] as? JNum)?.raw?.toLongOrNull()
        if (code != null && code != 0L) return "exit code $code"
        return null
    }

    /** The running line: what, on what, and — past [LONG_MS] — that it is still moving. The
     *  first move is the indeterminate phrase "still working", not a number: at [LONG_MS]
     *  no honest count exists yet. After [TICK_S] seconds the count joins it. It never
     *  says how far through the step is, because that number is not known. The count
     *  moves in [TICK_S]-second steps: the line is a polite live region, and a screen
     *  reader must not be read a clock. */
    fun running(s: Step, elapsedMs: Long): String {
        val base = withTarget(s.doing, s.target)
        if (elapsedMs < LONG_MS) return base
        if (elapsedMs < TICK_S * 1000) return "$base · still working"
        val sec = elapsedMs / 1000 / TICK_S * TICK_S
        return "$base · still working, $sec s"
    }

    /** The line once the step's output arrived without an error. */
    fun finished(s: Step): String = withTarget(s.done, s.target)

    /** The line when the step reported a failure; held until the next turn. */
    fun failed(s: Step, reason: String): String = "✕ ${s.doing} failed — $reason"

    // --- internals -------------------------------------------------------------------

    /** `memory` reads and writes through one tool; a write is not a "check". */
    private fun memoryWrite(tool: String, args: JObj?): Verb? {
        if (tool != "memory") return null
        val act = str(args, "action")
        return if (act == "add" || act == "replace" || act == "remove") Verb("Updating memory", "Updated memory") else null
    }

    /** Integrations named by domain (MCP servers, connector tools): the noun matters more
     *  to a listener than the tool's own name, and "calendar"/"email" are what a person says. */
    private fun domainVerb(tool: String, args: JObj?): Verb? {
        val n = tool.lowercase()
        val act = str(args, "action").lowercase()
        fun has(vararg w: String) = w.any { n.contains(it) || act.contains(it) }
        val writes = has("create", "insert", "add", "update", "patch", "delete", "remove", "decline", "respond")
        return when {
            has("calendar", "event") ->
                if (writes) Verb("Changing your calendar", "Changed your calendar")
                else Verb("Checking your calendar", "Checked your calendar")
            has("gmail", "email", "mail") -> when {
                has("send") -> Verb("Sending an email", "Sent an email")
                has("draft") -> Verb("Drafting an email", "Drafted an email")
                else -> Verb("Checking your email", "Checked your email")
            }
            has("drive", "docs", "sheet") -> Verb("Working in your documents", "Worked in your documents")
            else -> null
        }
    }

    /** Any unknown identifier becomes words: `mcp_github_list_issues` → "Using github: list
     *  issues", `some.weird-tool` → "Using some weird tool". An identifier must never reach
     *  the line (STATE-AUDIT F4): if the table does not know the tool, the name is still
     *  read as a phrase. */
    private fun humanised(tool: String): Verb {
        val words = tool.removePrefix("mcp_")
            .replace(Regex("[^A-Za-z0-9]+"), " ")
            .trim()
            .lowercase()
        val (server, rest) = if (tool.startsWith("mcp_") && words.contains(' '))
            words.substringBefore(' ') to words.substringAfter(' ') else "" to words
        val what = if (server.isEmpty()) rest.ifBlank { "a tool" } else "$server: $rest"
        return Verb("Using $what", "Used $what")
    }

    private fun target(tool: String, args: JObj?): String {
        if (args == null) return ""
        for (k in TARGET_KEYS) {
            val n = args[k] ?: continue
            val v = when (n) {
                is JStr -> n.value
                is JArr -> (n.items.firstOrNull() as? JStr)?.value.orEmpty()
                else -> ""
            }
            if (v.isBlank()) continue
            // The action is already in the verb for a domain tool; repeating it says nothing.
            if (k == "action") return ""
            return oneLine(shorten(k, v), TARGET_CHARS)
        }
        return ""
    }

    /** A URL is named by its host and a path by its file: the part a person recognises. */
    private fun shorten(key: String, v: String): String = when (key) {
        "url" -> v.substringAfter("://").substringBefore('/').removePrefix("www.")
        "path", "file_path" -> v.trimEnd('/').substringAfterLast('/')
        else -> v
    }

    private fun withTarget(verb: String, target: String) = if (target.isEmpty()) verb else "$verb · $target"

    private fun oneLine(s: String, max: Int): String {
        val t = s.replace(Regex("\\s+"), " ").trim()
        return if (t.length <= max) t else t.take(max - 1).trimEnd() + "…"
    }

    private fun str(o: JObj?, key: String): String = (o?.get(key) as? JStr)?.value.orEmpty()

    private fun parseObj(text: String): JObj? {
        val t = text.trim()
        if (!t.startsWith("{")) return null
        return try { Json.parse(t) as? JObj } catch (_: JsonException) { null }
    }
}
