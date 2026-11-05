package com.hermesvox

import com.hermesvox.core.Json

/**
 * PhoneTool — #421 steps 3 and 4: the phone as an MCP tool, its tiers, and its result shape.
 *
 * The mind acts on the phone by calling **one** tool with an action enum. One tool rather than eight
 * because the schema rides on every voice turn the gateway offers it on, and because a single tool
 * with a required `action` is easier for a model to use correctly than eight near-synonyms.
 *
 * **This file is pure JVM.** It decides what a call *means* (tier, grant, arguments, validation) and
 * how a result is rendered; [PhoneEngine] executes against a [PhonePlatform] and [PhoneMcpServer]
 * speaks the wire. That split is what lets the governance, the tier routing, the refusal path and
 * the result shape be proven off-device, with no Android and no socket.
 *
 * ## The schema follows the grants (#429 step 1)
 *
 * [PhoneGrants] is the single registry: twelve capability groups, each Off, Ask or Allow, all Off
 * by default. [schema] builds the advertised `enum` from [PhoneGrants.advertised], so a group the
 * owner has not granted is **absent** — not a temptation the model has to resist, but a name it
 * cannot call. The source is read at call time, never cached, so revocation lands on the next call
 * (including mid-session).
 *
 * A call that still names a hidden action gets a described refusal rather than a transport error:
 * `reason:"not_granted"` when the group is Off, `reason:"approval-required"` when it is Ask.
 *
 * ## The tier table (D32's bounds, extended by #421 steps 3–4 and #429)
 *
 * | tier | actions | what happens |
 * |---|---|---|
 * | 0 — instant | `timer`, `alarm`, `dnd`, `volume`, `play`, `pause`, `skip`, `now_playing`, `state`, `clip`, `status`, `undo` | executed now, reversible, private; the only tier that may be Allow |
 * | 1 — reported | `note` | executed now (when granted), reported to the mind on the next turn, undoable; Ask or Off only |
 * | 2 — approval | `share`, `message`, `call`, `email`, `delete`, `purchase`, `launch`, `send`, `pay` | **never executed from here.** The result says an approval is required and names the surface that owns it |
 * | not a phone action | anything else | refused as unknown, with the offered list |
 *
 * Tier 2 is deliberately refused rather than queued: an MCP call carries no run id, so the phone
 * cannot mint an approval card for it, and inventing a second approval surface inside the MCP server
 * is exactly what #421 rules out. The consequential path is the *gateway's* approval surface
 * (the same cards `core/Approval.kt` answers), and the owner can put this whole tool behind it with
 * `trust: untrusted` in the gateway config (`docs/MCP-PHONE.md`).
 *
 * ## The invariant
 *
 * A result with `ok: true` is only ever rendered *after* the platform said it performed the action.
 * A refusal carries a `reason` and never an `id` or a `state`. The phone therefore cannot claim an
 * action that was vetoed, failed or never ran — and a claim in the mind's reply is traceable to a
 * result it was given.
 */
object PhoneTool {

    /** The one tool name. The gateway prefixes it with the server: `mcp__vox__phone`. */
    const val NAME = "phone"

    /**
     * The actions that are not a capability: `undo` reverses something the owner already allowed,
     * and `status` only reports. Both are always offered — a phone whose grants are all Off must be
     * able to say so.
     */
    val ALWAYS: List<String> = PhoneGrants.ALWAYS

    /** Timers are bounded exactly as the grammar bounds them: 1 s to 24 h. */
    const val MIN_DURATION_MS = LocalActionGrammar.MIN_DURATION_MS
    const val MAX_DURATION_MS = LocalActionGrammar.MAX_DURATION_MS

    /** A note is a sentence the caller wants kept, not a document. */
    const val MAX_NOTE_CHARS = 500

    /** A clip is text meant to be pasted somewhere, not a document either. */
    const val MAX_CLIP_CHARS = 2_000

    /**
     * The actions a schema may offer right now — a pure function of the live grants. [PhoneToolTest]
     * pins this against the registry, so the enum and the registry cannot drift.
     */
    fun offeredActions(grants: PhoneGrants.Source = PhoneGrants.ALL_OFF): List<String> =
        PhoneGrants.advertised(grants)

    /** The part of the tool description that is true whatever is granted. */
    const val DESCRIPTION =
        "Act on the phone this conversation is happening on. Only the actions in `action` are " +
            "granted on this phone right now; the rest are absent because the owner has not given " +
            "them. Every result says what actually happened — check ok before telling the caller an " +
            "action is done, and do not claim one this tool did not confirm. A refusal carries a " +
            "reason (`not_granted` when the owner has not granted it, `approval-required` when it " +
            "needs the owner's approval on the phone); a refusal means nothing was performed."

    /**
     * The description the model reads, with the current grants named. Built per request, because a
     * grant can change between two turns and the model should not be reading yesterday's list.
     */
    fun description(grants: PhoneGrants.Source = PhoneGrants.ALL_OFF): String {
        val on = PhoneGrants.GROUPS.filter {
            PhoneGrants.stateOf(grants, it) != PhoneGrants.State.OFF && it.actions.isNotEmpty()
        }
        val grantLine = if (on.isEmpty()) {
            "No capability group is on: only `status` and `undo` are available."
        } else {
            "On now: " + on.joinToString("; ") { g ->
                "${g.label} (${PhoneGrants.stateOf(grants, g).label.lowercase()}): " +
                    g.actions.joinToString(", ")
            } + "."
        }
        return "$DESCRIPTION $grantLine"
    }

    /** One phrase per action, for the `action` property's description. */
    private val ACTION_HINTS = linkedMapOf(
        "timer" to "countdown",
        "alarm" to "wall-clock alarm",
        "dnd" to "Do not disturb on/off",
        "volume" to "media volume",
        "note" to "keep a short note",
        "play" to "send the play key to the active player",
        "pause" to "send the pause key to the active player",
        "skip" to "next or previous track",
        "now_playing" to "what the phone will admit to playing",
        "state" to "battery, charging, screen, network, storage, volume, time",
        "clip" to "put text on the clipboard (never reads it)",
    )

    /** The `tools/list` entry, verbatim. Pure, so [PhoneToolTest] can pin the wire shape. */
    fun schema(grants: PhoneGrants.Source = PhoneGrants.ALL_OFF): Map<String, Any?> {
        val offered = offeredActions(grants)
        val actionDesc = buildString {
            append(
                offered.joinToString("; ") { a ->
                    when (a) {
                        "undo" -> "undo: reverse an action by id"
                        "status" -> "status: what the phone can do and what is running"
                        else -> "$a: ${ACTION_HINTS[a] ?: a}"
                    }
                }
            )
            append(
                ". Actions in a group that is Off are absent on purpose; naming one is refused " +
                    "with reason=not_granted.",
            )
        }
        return linkedMapOf(
        "name" to NAME,
        "description" to description(grants),
        "inputSchema" to linkedMapOf(
            "type" to "object",
            "properties" to linkedMapOf(
                "action" to linkedMapOf(
                    "type" to "string",
                    "enum" to offered,
                    "description" to actionDesc,
                ),
                "duration_ms" to linkedMapOf(
                    "type" to "integer",
                    "minimum" to MIN_DURATION_MS.toInt(),
                    "maximum" to MAX_DURATION_MS.toInt(),
                    "description" to "timer only: how long, in milliseconds.",
                ),
                "at" to linkedMapOf(
                    "type" to "string",
                    "description" to "alarm only: 24-hour clock time, HH:MM (for example 07:30).",
                ),
                "on" to linkedMapOf(
                    "type" to "boolean",
                    "description" to "dnd only: true turns do not disturb on, false turns it off.",
                ),
                "level" to linkedMapOf(
                    "type" to "integer",
                    "minimum" to 0,
                    "maximum" to 100,
                    "description" to "volume only: the media volume to set, in percent.",
                ),
                "direction" to linkedMapOf(
                    "type" to "string",
                    "enum" to listOf("up", "down", "next", "previous"),
                    "description" to
                        "volume only: up or down for one stream step. skip only: next (the " +
                        "default) or previous. Each action accepts only its own pair.",
                ),
                "text" to linkedMapOf(
                    "type" to "string",
                    "description" to
                        "note or clip only: the text to keep — at most $MAX_NOTE_CHARS characters " +
                        "for a note, $MAX_CLIP_CHARS for a clip. Never a message or a secret.",
                ),
                "id" to linkedMapOf(
                    "type" to "string",
                    "description" to "undo only: the id an earlier result returned.",
                ),
            ),
            "required" to listOf("action"),
            "additionalProperties" to false,
        ),
        // The tool changes the phone: it is write-capable, and the gateway's `trust: untrusted`
        // mode is meant to gate it. Claiming readOnlyHint here would be a lie with consequences.
        "annotations" to linkedMapOf(
            "title" to "Phone",
            "readOnlyHint" to false,
            "idempotentHint" to false,
            "openWorldHint" to false,
        ),
        )
    }


    // ---- the calls the dispatcher understands ----

    /** One validated request. Every field here is already bounded; nothing parses after this. */
    sealed class Call {
        abstract val action: String

        data class Timer(val durationMs: Long) : Call() {
            override val action = "timer"
        }

        data class Alarm(val hour: Int, val minute: Int) : Call() {
            override val action = "alarm"
        }

        data class Dnd(val on: Boolean) : Call() {
            override val action = "dnd"
        }

        /** [level] absolute percent, or null for a one-step change in [direction] (+1 / -1). */
        data class Volume(val level: Int?, val direction: Int) : Call() {
            override val action = "volume"
        }

        data class Note(val text: String) : Call() {
            override val action = "note"
        }

        /** `play` and `pause` are the same call with a different key; [pause] names which. */
        data class PlayPause(val pause: Boolean) : Call() {
            override val action: String = if (pause) "pause" else "play"
        }

        /** `skip` forward (the default) or back, as one transport key. */
        data class Skip(val previous: Boolean) : Call() {
            override val action = "skip"
        }

        object NowPlaying : Call() {
            override val action = "now_playing"
        }

        object State : Call() {
            override val action = "state"
        }

        data class Clip(val text: String) : Call() {
            override val action = "clip"
        }

        data class Undo(val id: String) : Call() {
            override val action = "undo"
        }

        object Status : Call() {
            override val action = "status"
        }
    }

    /** Either a call to run, or the refusal to return without running anything. */
    sealed class Parsed {
        data class Run(val call: Call) : Parsed()
        data class Refuse(val result: PhoneResult) : Parsed()
    }

    // ---- argument reading ----

    /**
     * Reads the tool's `arguments` object against the **current** grants. Accepts the plain JSON
     * shapes the MCP SDK produces (Long/Double for integers, String, Boolean) and refuses everything
     * else by name, so a bad call is a described refusal rather than an exception in a socket thread.
     *
     * The grant check comes before argument parsing on purpose: "that group is off" is the honest
     * answer to a call the model should not have made, and it does not depend on the argument shape.
     * [grants] is read now, so a revocation that happened while this request was in flight is
     * honoured here, not at the next connection.
     */
    fun parse(args: Map<String, Any?>, grants: PhoneGrants.Source = PhoneGrants.ALL_OFF): Parsed {
        val action = (args["action"] as? String)?.trim()?.lowercase().orEmpty()
        if (action.isEmpty()) return Parsed.Refuse(PhoneResult.refused("", PhoneReason.INVALID, "action is required"))
        val group = PhoneGrants.groupOf(action)
        if (group != null) {
            return when (PhoneGrants.stateOf(grants, group)) {
                PhoneGrants.State.OFF -> Parsed.Refuse(
                    PhoneResult.refused(
                        action,
                        PhoneReason.NOT_GRANTED,
                        "the \"${group.label}\" group is off on this phone; nothing was performed. " +
                            "Only the owner can grant it in Phone tools.",
                        tier = group.tier,
                    )
                )
                PhoneGrants.State.ASK -> Parsed.Refuse(
                    PhoneResult.refused(
                        action,
                        PhoneReason.APPROVAL,
                        "the \"${group.label}\" group is set to Ask on this phone; this use waits " +
                            "for the owner's approval on the phone and was not performed",
                        tier = group.tier,
                    )
                )
                PhoneGrants.State.ALLOW -> parseArguments(action, args, grants)
            }
        }
        return when (PhoneTiers.of(action)) {
            PhoneTiers.APPROVAL -> Parsed.Refuse(
                PhoneResult.refused(
                    action,
                    PhoneReason.APPROVAL,
                    "this action leaves the phone or cannot be undone; it needs the owner's approval " +
                        "on the phone and was not performed",
                    tier = PhoneTiers.APPROVAL,
                )
            )
            null -> Parsed.Refuse(
                PhoneResult.refused(
                    action,
                    PhoneReason.UNKNOWN,
                    "not a phone action; offered: ${offeredActions(grants).joinToString(", ")}",
                )
            )
            else -> parseArguments(action, args, grants)
        }
    }

    /** Argument bounds for one known action, without the grant decision. Internal for tests. */
    internal fun parseArguments(
        action: String,
        args: Map<String, Any?>,
        grants: PhoneGrants.Source,
    ): Parsed {
        return when (action) {
        "timer" -> {
            val ms = integer(args["duration_ms"])
            when {
                ms == null -> bad(action, "duration_ms is required and must be an integer")
                ms < MIN_DURATION_MS || ms > MAX_DURATION_MS ->
                    bad(action, "duration_ms must be between $MIN_DURATION_MS and $MAX_DURATION_MS")
                else -> Parsed.Run(Call.Timer(ms))
            }
        }
        "alarm" -> {
            val (h, m) = clock(args["at"] as? String) ?: return bad(action, "at must be a 24-hour HH:MM time")
            Parsed.Run(Call.Alarm(h, m))
        }
        "dnd" -> {
            val on = args["on"] as? Boolean
            if (on == null) bad(action, "on is required and must be true or false") else Parsed.Run(Call.Dnd(on))
        }
        "volume" -> {
            val raw = args["level"]?.let { integer(it) ?: return bad(action, "level must be an integer 0-100") }
            if (raw != null && raw !in 0L..100L) return bad(action, "level must be between 0 and 100")
            val level = raw?.toInt()
            if (level != null) return Parsed.Run(Call.Volume(level, 0))
            val direction = when ((args["direction"] as? String)?.trim()?.lowercase()) {
                "up" -> 1
                "down" -> -1
                else -> return bad(action, "give level 0-100, or direction up or down")
            }
            Parsed.Run(Call.Volume(null, direction))
        }
        "note" -> {
            val text = (args["text"] as? String)?.trim().orEmpty()
            when {
                text.isEmpty() -> bad(action, "text is required")
                text.length > MAX_NOTE_CHARS -> bad(action, "text is longer than $MAX_NOTE_CHARS characters")
                else -> Parsed.Run(Call.Note(text))
            }
        }
        "play" -> Parsed.Run(Call.PlayPause(pause = false))
        "pause" -> Parsed.Run(Call.PlayPause(pause = true))
        "skip" -> {
            val dir = (args["direction"] as? String)?.trim()?.lowercase()
            when (dir) {
                null, "", "next" -> Parsed.Run(Call.Skip(previous = false))
                "previous", "prev" -> Parsed.Run(Call.Skip(previous = true))
                else -> bad(action, "direction must be next (the default) or previous")
            }
        }
        "now_playing" -> Parsed.Run(Call.NowPlaying)
        "state" -> Parsed.Run(Call.State)
        "clip" -> {
            val text = args["text"] as? String
            when {
                text.isNullOrBlank() -> bad(action, "text is required and must not be empty")
                text.length > MAX_CLIP_CHARS ->
                    bad(action, "text is longer than $MAX_CLIP_CHARS characters")
                // The text is written exactly as given: a clip is something to paste, not prose.
                else -> Parsed.Run(Call.Clip(text))
            }
        }
        "undo" -> {
            val id = (args["id"] as? String)?.trim().orEmpty()
            if (id.isEmpty()) bad(action, "id is required") else Parsed.Run(Call.Undo(id))
        }
        "status" -> Parsed.Run(Call.Status)
        else -> Parsed.Refuse(
            PhoneResult.refused(
                action,
                PhoneReason.UNKNOWN,
                "not a phone action; offered: ${offeredActions(grants).joinToString(", ")}",
            )
        )
        }
    }

    private fun bad(action: String, detail: String): Parsed =
        Parsed.Refuse(PhoneResult.refused(action, PhoneReason.INVALID, detail))

    /** JSON numbers arrive as Long or Double depending on the reader; both are accepted as whole. */
    private fun integer(v: Any?): Long? = when (v) {
        is Int -> v.toLong()
        is Long -> v
        is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong() else null
        is String -> v.trim().toLongOrNull()
        else -> null
    }

    /** "07:30" — 24-hour, both digits required by the pattern. Null when it is not a clock time. */
    fun clock(text: String?): Pair<Int, Int>? {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(text?.trim().orEmpty()) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        if (h !in 0..23 || min !in 0..59) return null
        return h to min
    }
}

/** The tier table, in one place. Unknown actions have no tier — they are not phone actions. */
object PhoneTiers {

    /** Instant, reversible, private. */
    const val INSTANT = 0

    /** Acts now, is reported to the mind next turn, and can be undone. */
    const val REPORTED = 1

    /** Leaves the phone or cannot be undone: the owner's approval surface owns it. */
    const val APPROVAL = 2

    private val TIER_0 = setOf(
        "timer", "alarm", "dnd", "volume", "status", "undo",
        // Media (#429 lane): transport keys, and a read of what the session will admit to.
        "play", "pause", "skip", "now_playing",
        // Device state (#429 lane): one read-only report, every field nullable.
        "state",
        // Clipboard (#429 lane): a write. Never a read.
        "clip",
    )
    private val TIER_1 = setOf("note")

    /**
     * The consequential capabilities the phone deliberately does **not** execute for the mind.
     * They are named so a direct call gets "this needs the owner" rather than "unknown action".
     */
    private val TIER_2 = setOf(
        "share", "message", "call", "email", "delete", "purchase", "pay", "launch", "send", "open",
        "install", "uninstall", "transfer",
    )

    /** The tier of [action], or null when it is not a phone action at all. */
    fun of(action: String): Int? {
        val a = action.trim().lowercase()
        return when {
            a in TIER_0 -> INSTANT
            a in TIER_1 -> REPORTED
            a in TIER_2 -> APPROVAL
            else -> null
        }
    }

    /** True when the phone itself may perform [action]. */
    fun executable(action: String): Boolean = of(action)?.let { it <= REPORTED } == true
}

/** The machine-readable reasons a result can carry. Stable strings; the model reads them. */
object PhoneReason {
    /** The phone does not hold a permission the action needs. */
    const val PERMISSION = "permission"

    /** The action was recognised and the platform refused or failed. */
    const val UNAVAILABLE = "unavailable"

    /** No undo is registered for that id any more (it fired, was superseded, or was never ours). */
    const val EXPIRED = "expired"

    /** The arguments do not describe a runnable action. */
    const val INVALID = "invalid-arguments"

    /** Not a phone action; `detail` carries the offered list. */
    const val UNKNOWN = "unknown-action"

    /** Tier 2: needs the owner's approval, and nothing was done. */
    const val APPROVAL = "approval-required"

    /** The owner has not granted this capability group. Nothing was done, and it is not offered. */
    const val NOT_GRANTED = "not_granted"
}

/**
 * One result, and its wire form.
 *
 * `ok` is set only by the code path that actually performed the action, so the shape itself is the
 * honesty rule: a refusal has a `reason` and never an `id`, `state` or other success fact.
 */
data class PhoneResult(
    val ok: Boolean,
    val action: String,
    val tier: Int? = null,
    val id: String? = null,
    val reason: String? = null,
    val detail: String? = null,
    /** Success facts, in the order the result should read them. Never present on a refusal. */
    val facts: Map<String, Any?> = emptyMap(),
) {
    /** The JSON object the tool returns. Key order is deliberate: `ok` first, then identity. */
    fun toMap(): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        out["ok"] = ok
        out["action"] = action
        tier?.let { out["tier"] = it }
        id?.let { out["id"] = it }
        if (ok) {
            out.putAll(facts)
            // Every reversible action names its own undo, so the mind does not have to remember a
            // shape: `{"action":"undo","id":"timer-3"}` is a complete second tool call.
            facts["undoable"]?.let { undoable ->
                if (undoable == true) out["undo"] = linkedMapOf("action" to "undo", "id" to (id ?: ""))
            }
        } else {
            reason?.let { out["reason"] = it }
            detail?.let { out["detail"] = it }
        }
        return out
    }

    fun json(): String = Json.encode(toMap())

    companion object {
        /** A performed action. [facts] carries what the caller needs to know it happened. */
        fun done(action: String, id: String?, facts: Map<String, Any?> = emptyMap()): PhoneResult =
            PhoneResult(true, action, PhoneTiers.of(action), id, facts = facts)

        /** A performed action that cannot be undone (status), or carries no id. */
        fun info(action: String, facts: Map<String, Any?>): PhoneResult =
            PhoneResult(true, action, PhoneTiers.of(action), null, facts = facts)

        /** Nothing happened, and why. */
        fun refused(action: String, reason: String, detail: String, tier: Int? = PhoneTiers.of(action)): PhoneResult =
            PhoneResult(false, action, tier, null, reason, detail)
    }
}
