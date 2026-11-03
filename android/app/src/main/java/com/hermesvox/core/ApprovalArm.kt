package com.hermesvox.core

/**
 * #376 — the approval arm: a run the phone places whose ONLY purpose is to make Hermes
 * ask for a sign-off, so the card (#282) has something real to appear for.
 *
 * WHY AN ARM IS NEEDED AT ALL
 *
 * Across the 0.9.1 acceptance run the device logged zero `event=approval` lines: not a
 * card that was missed, no request at all. Nothing in a normal turn reliably made Hermes
 * stop and ask. Three voice attempts failed for a recognisable reason — the recogniser
 * mangled the path, so the agent (rightly) refused to run an irreversible command against
 * a path it could not resolve, and the approval path was never reached.
 *
 * WHAT IS DETERMINISTIC HERE, AND WHAT IS NOT
 *
 * The POLICY half is deterministic and provable off-device: the gateway classifies a
 * command before the agent may run it, and `rm -rf /tmp/vox-approval-arm` matches the
 * dangerous-pattern table ("delete in root path"), so the run must stop for a human. On
 * a real gateway 0.22.6 that is checkable without executing anything:
 *
 *     hermes approvals test -- rm -rf /tmp/vox-approval-arm
 *     => verdict: ask-approval (exit 2), rule: delete in root path
 *
 * The MODEL half is not ours: whether the agent runs the command at all is its judgement.
 * The arm keeps that half as small as honesty allows — a TEXT trigger (no recogniser to
 * mangle it), one exact harmless command, and a path under the gateway host's own /tmp
 * that the command itself creates before deleting. It cannot remove that judgement, and
 * this file does not pretend otherwise.
 *
 * WHY THE ARM USES /v1/runs
 *
 * The app's ordinary turns ride `/v1/responses`. On gateway 0.22.6 that route does NOT
 * bridge the approval notifier: only `/v1/runs` and streaming `/v1/chat/completions`
 * register one (`_register_session_stream_approval` / `_register_stream_approval` in the
 * gateway's `api_server*.py`), while `/v1/responses` launches the agent with no approval
 * callback — so a flagged command there resolves straight from `approvals.unattended_mode`
 * and the phone never sees an event. `/v1/runs` also exposes the request twice, which is
 * what [requestFromRunStatus] reads:
 *
 *   GET /v1/runs/{id}  ->  {"status":"waiting_for_approval",
 *                          "pending_controls":[{"kind":"approval","prompt_id":"…",
 *                                               "execution_generation":N,
 *                                               "command":"…","description":"…",
 *                                               "choices":["once","deny"]}]}
 *
 * or, on a gateway without session authority, `status.approval` carrying the
 * `approval.request` event itself.
 *
 * This object is pure JVM so the wire reading is pinned by unit tests; the Android edges
 * (place the run, poll, show the card) live in [VoiceController.armApproval]. The whole
 * path is reachable ONLY from the FLAG_DEBUGGABLE intent harness in `MainActivity`, never
 * from a shipped surface.
 */
internal object ApprovalArm {

    /** The path the arm creates and then deletes: inside the gateway host's /tmp, empty,
     *  and re-creatable by the same command, so an accidental execution loses nothing. */
    const val PATH = "/tmp/vox-approval-arm"

    /**
     * The exact text the arm sends. One sentence of instruction, one command, and no
     * ambiguity about the path — the three things the failed voice attempts lacked.
     * `mkdir -p … && rm -rf …` is asked as ONE command so the delete always targets a
     * directory that exists (the agent's earlier refusal was an unresolvable path).
     */
    val PROMPT: String =
        "Use the terminal tool to run exactly this command and report its output: " +
            "mkdir -p $PATH && rm -rf $PATH"

    /** How often the arm re-reads the run's status while waiting for the request. */
    const val POLL_MS = 1_000L

    /** How long the arm waits for the request before giving up (the model has to answer
     *  first; the request itself then waits on the gateway's own `approvals.timeout`). */
    const val WATCH_MS = 180_000L

    /** Run states after which no request can ever arrive. */
    val TERMINAL_STATUSES: Set<String> = setOf("completed", "failed", "cancelled", "interrupted")

    /**
     * The run's status string, or "" when the body is not the run-status object. Used only
     * for the arm's log line, so an unreadable body is a fact to print, never a crash.
     */
    fun statusOf(json: String): String = root(json)?.let { Json.str(it, "status") } ?: ""

    /**
     * The approval the run is waiting on, or null while it is still working / after it has
     * ended. [ApprovalGate] still decides whether the request is answerable; this only
     * reads the wire. Both gateway shapes are accepted because the same code runs against
     * a session-authority gateway (`pending_controls`) and a plain one (`approval`).
     */
    fun requestFromRunStatus(json: String): ApprovalRequest? {
        val root = root(json) ?: return null
        val status = Json.str(root, "status")
        if (status.lowercase() in TERMINAL_STATUSES) return null
        val runId = Json.str(root, "run_id")

        // Session authority: the pending control carries the prompt id and the generation
        // fence the answer must echo. Choices are the gateway's, already narrowed.
        for (node in Json.arr(root, "pending_controls")) {
            val control = Json.elem(node) ?: continue
            if (!Json.str(control, "kind").equals("approval", ignoreCase = true)) continue
            val requestId = Json.str(control, "prompt_id")
            if (requestId.isEmpty() && runId.isEmpty()) continue
            return ApprovalRequest(
                runId = runId,
                requestId = requestId,
                command = Json.str(control, "command"),
                description = Json.str(control, "description"),
                reason = Json.str(control, "reason"),
                choices = choicesOf(control),
                executionGeneration = intOrNull(control, "execution_generation"),
            )
        }

        // Plain runs lane: the status carries the `approval.request` event itself. An older
        // gateway omits `request_id`; ApprovalRequest.key() then falls back to the run.
        val event = Json.obj(root, "approval") ?: return null
        val requestId = Json.str(event, "request_id")
        if (requestId.isEmpty() && runId.isEmpty()) return null
        return ApprovalRequest(
            runId = runId,
            requestId = requestId,
            command = Json.str(event, "command"),
            description = Json.str(event, "description"),
            reason = Json.str(event, "reason"),
            choices = choicesOf(event),
            executionGeneration = intOrNull(event, "execution_generation"),
        )
    }

    private fun choicesOf(o: JObj): List<String> = Json.arr(o, "choices").mapNotNull { node ->
        (node as? JStr)?.value?.takeIf { it.isNotBlank() }
    }

    private fun intOrNull(o: JObj, key: String): Int? = when (val node = o[key]) {
        is JNum -> node.raw.trim().toIntOrNull()
        is JStr -> node.value.trim().toIntOrNull()
        else -> null
    }

    private fun root(json: String): JObj? = try {
        Json.parse(json, allowTrailing = true) as? JObj
    } catch (_: JsonException) {
        null
    }
}
