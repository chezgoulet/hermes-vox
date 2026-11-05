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
 * [PhoneGrants] is the single registry: thirteen capability groups, each Off, Ask or Allow, all Off
 * by default. [schema] builds the advertised `enum` from [PhoneGrants.advertised], so a group the
 * owner has not granted is **absent** — not a temptation the model has to resist, but a name it
 * cannot call. The source is read at call time, never cached, so revocation lands on the next call
 * (including mid-session).
 *
 * A call that still names a hidden action gets a described refusal rather than a transport error:
 * `reason:"not_granted"` when the group is Off, `reason:"approval-required"` when it is Ask and the
 * tier waits for a card. A **reported** (Tier 1) group at Ask runs: the owner turned it on, and the
 * result is reported back to them.
 *
 * ## The tier table (D32's bounds, extended by #421 steps 3–4 and #429)
 *
 * | tier | actions | what happens |
 * |---|---|---|
 * | 0 — instant | `timer`, `alarm`, `dnd`, `volume`, `play`, `pause`, `skip`, `now_playing`, `state`, `clip`, `clip_read`, `notifications`, `notification_read`, `contact`, `message`, `dial`, `launch`, `open`, `status`, `undo` | executed now, reversible, private; the only tier that may be Allow. The handoff actions compose a draft or open an app the owner already trusts, so nothing leaves the phone except through that app. The two read groups are instant because they change nothing; their privacy comes from scope (a call-scoped listener, one notification at a time, numbers and labels only) |
 * | 1 — reported | `note`, `file_list`, `file_read`, `file_offer`, `agenda`, `event_create` | executed now (when granted), reported to the mind on the next turn, undoable where the row says; Ask or Off only |
 * | 2 — approval | `install`, `uninstall`, `delete`, `purchase`, `pay`, `transfer` | `uninstall` is the one that has a card on this phone: [PhoneApproval]'s two-step handshake. Every other Tier 2 name is still refused with `approval-required` |
 * | not a phone action | anything else | refused as unknown, with the offered list |
 *
 * Tier 2 is deliberately refused rather than queued: an MCP call carries no run id, so the phone
 * cannot mint the gateway's approval card for it. `uninstall` is the exception that proves the
 * shape: it is irreversible and cannot be handed to another app, so [PhoneApproval] is a **phone**
 * card on this wire — a two-step handshake on the same tool, not a second gateway surface. The
 * owner may still put this whole tool behind the gateway's approval surface with `trust: untrusted`
 * in the gateway config (`docs/MCP-PHONE.md`).
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

    /** A file name, never a path. [PhoneFiles] owns the rule; the bound is named here too. */
    const val MAX_FILE_NAME_CHARS = PhoneFiles.MAX_NAME_CHARS

    /** An event's title is a label, not a description. */
    const val MAX_EVENT_TITLE_CHARS = PhoneCalendar.MAX_TITLE_CHARS

    /** A text handed to the owner's messaging app is a message, not a document. */
    const val MAX_MESSAGE_CHARS = 1_000

    /** A recipient is a phone number or a contact name; neither is a paragraph. */
    const val MAX_TO_CHARS = 200

    /** An app name or package, and a URI, are both one line, not a payload. */
    const val MAX_APP_CHARS = 200
    const val MAX_URI_CHARS = 2_000

    /** An approval id is `ap-N`; the bound is generous, the shape is not. */
    const val MAX_APPROVAL_ID_CHARS = 64

    /**
     * A contact lookup answers at most this many people. More would be an address-book dump, not
     * "look up a contact's number by name"; a name that is too broad is narrowed by asking again.
     */
    const val MAX_CONTACT_MATCHES = 20

    /** A name, full or partial, is not a paragraph. */
    const val MAX_CONTACT_NAME_CHARS = 200

    /** An opaque id from a notifications summary is short; it is not a payload either. */
    const val MAX_NOTIFICATION_ID_CHARS = 80

    /**
     * `duration_min` is whole minutes, at least one. There is no upper bound of ours: the
     * calendar provider's own limits apply, and a duration it refuses comes back as `unavailable`.
     */
    const val MIN_EVENT_MINUTES = 1

    /**
     * The actions a schema may offer right now — a pure function of the live grants. [PhoneToolTest]
     * pins this against the registry, so the enum and the registry cannot drift.
     */
    fun offeredActions(grants: PhoneGrants.Source = PhoneGrants.ALL_OFF): List<String> =
        PhoneGrants.advertised(grants)

    /**
     * The rule that makes `sensitive:true` on a notification result mean something. It lives in
     * the **tool description**, not only in the docs, because the description is what the agent
     * reads on every turn and the docs are not.
     */
    const val NOTIFICATION_PRIVACY =
        "Notifications and their contents may include verification codes, private messages and " +
            "financial alerts. Do not read them aloud unless the owner asked you to, do not repeat " +
            "them back, and do not store them in a note."

    /** The part of the tool description that is true whatever is granted. */
    const val DESCRIPTION =
        "Act on the phone this conversation is happening on. Only the actions in `action` are " +
            "granted on this phone right now; the rest are absent because the owner has not given " +
            "them. Every result says what actually happened — check ok before telling the caller an " +
            "action is done, and do not claim one this tool did not confirm. A refusal carries a " +
            "reason (`not_granted` when the owner has not granted it, `approval-required` when it " +
            "needs the owner's approval on the phone); a refusal means nothing was performed. " +
            NOTIFICATION_PRIVACY + " " +
            "`uninstall` is two steps: call it without `approval`, then call it again with the " +
            "returned approval id and identical arguments once the owner says yes."

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
        "clip" to "put text on the clipboard",
        "clip_read" to "the clipboard's text, when the phone allows the read",
        "file_list" to "list a folder you granted (depth-limited, paged, names and sizes only)",
        "file_read" to "read a named file's text inside the folder you granted, in whole or a window",
        "file_offer" to "offer a named file to your agent as an artifact",
        "agenda" to "today's events (start, end, title, calendar)",
        "event_create" to "create one event, undoable",
        "notifications" to "a count of notifications per app, with no titles or text",
        "notification_read" to "one notification's app, time, title and text by id from a summary",
        "contact" to "a contact's numbers by full or partial name",
        "message" to "compose a text and open the messaging app; you send it",
        "dial" to "open the dialer with a number ready; you make the call",
        "launch" to "open a launchable app by package or name",
        "open" to "open a URI in the app that claims it",
        "uninstall" to "remove an app by package; the phone asks you first",
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
                        "note, clip or message only: the text to keep or send — at most " +
                        "$MAX_NOTE_CHARS characters for a note, $MAX_CLIP_CHARS for a clip, " +
                        "$MAX_MESSAGE_CHARS for a message. A message is composed here and " +
                        "handed to your messaging app; this tool never sends it.",
                ),
                "to" to linkedMapOf(
                    "type" to "string",
                    "description" to
                        "message or dial only: the recipient — a phone number, or a contact name " +
                        "when the Contacts group is granted. At most $MAX_TO_CHARS characters. " +
                        "The dialer opens with the number filled in, and the messaging app opens " +
                        "with the draft; the owner presses send or call.",
                ),
                "app" to linkedMapOf(
                    "type" to "string",
                    "description" to
                        "launch only: the package name, or the name the launcher shows. At most " +
                        "$MAX_APP_CHARS characters. Only an activity with a launcher entry can " +
                        "be started; a package with no launchable activity is no_app.",
                ),
                "uri" to linkedMapOf(
                    "type" to "string",
                    "description" to
                        "open only: the URI to open, such as https://…, mailto:… or a deep link. " +
                        "It must carry a scheme and contain no whitespace, at most " +
                        "$MAX_URI_CHARS characters; an unusable URI is invalid-arguments. " +
                        "intent:, file: and a string with no scheme are bad_uri and are never " +
                        "opened.",
                ),
                "package" to linkedMapOf(
                    "type" to "string",
                    "description" to
                        "open only: the package that must receive the URI. uninstall only: the " +
                        "package to remove. Omit it for open to let the phone choose among the " +
                        "apps that claim the link; uninstall requires it.",
                ),
                "approval" to linkedMapOf(
                    "type" to "string",
                    "description" to
                        "uninstall only: the `approval_id` a previous call returned. Omit it to " +
                        "ask the owner; the phone raises a card and answers " +
                        "approval-required with that id and its expiry. Call again with **the " +
                        "same action and arguments** plus this id to remove the app after the " +
                        "owner approves. An id is used once.",
                ),
                "id" to linkedMapOf(
                    "type" to "string",
                    "description" to
                        "undo: the id an earlier result returned. notification_read: one of the " +
                        "ids a notifications summary returned — never guess one.",
                ),
                "name" to linkedMapOf(
                    "type" to "string",
                    "description" to
                        "contact: a full or partial contact name, case-insensitive, at most " +
                        "$MAX_CONTACT_NAME_CHARS characters; the lookup returns up to " +
                        "$MAX_CONTACT_MATCHES matching people and their numbers with labels. " +
                        "file_read or file_offer: the file's name inside the folder the owner " +
                        "granted, at most $MAX_FILE_NAME_CHARS characters. A name, never a path: " +
                        "\"..\", an absolute path and a separator are refused.",
                ),
                "folder" to linkedMapOf(
                    "type" to "string",
                    "enum" to PhoneFiles.FOLDERS,
                    "description" to
                        "file_list, file_read or file_offer only: which granted folder to look in — " +
                        "${PhoneFiles.FOLDERS.joinToString(" or ")} (the default is " +
                        "${PhoneFiles.DEFAULT_FOLDER}). Everything is inside that folder only; " +
                        "there is no way to name another one.",
                ),
                "path" to linkedMapOf(
                    "type" to "string",
                    "description" to
                        "file_list only: a subdirectory relative to the folder you granted " +
                        "(for example \"reports/2026\"); leave it out, or \"\", for the folder " +
                        "itself. An absolute path, \"..\" and a backslash are refused.",
                ),
                "depth" to linkedMapOf(
                    "type" to "integer",
                    "minimum" to 1,
                    "maximum" to PhoneFiles.MAX_DEPTH,
                    "description" to
                        "file_list only: 1 (the default) lists that directory's entries; 2 also " +
                        "lists each subdirectory's, and so on, up to ${PhoneFiles.MAX_DEPTH}.",
                ),
                "pattern" to linkedMapOf(
                    "type" to "string",
                    "description" to
                        "file_list only: a case-insensitive glob on the file name, such as " +
                        "\"*.pdf\" or \"invoice*\" (\"*\" is any run of characters, \"?\" one). " +
                        "Applied to files, never to directories.",
                ),
                "offset" to linkedMapOf(
                    "type" to "integer",
                    "minimum" to 0,
                    "description" to
                        "file_list: the page to start at, from 0 (the default). file_read: the byte " +
                        "to start a window at (a file larger than the whole-file cap is read this " +
                        "way, never refused).",
                ),
                "limit" to linkedMapOf(
                    "type" to "integer",
                    "minimum" to 1,
                    "maximum" to PhoneFiles.MAX_LIST_LIMIT,
                    "description" to
                        "file_list only: entries per page, ${PhoneFiles.DEFAULT_LIST_LIMIT} by " +
                        "default, at most ${PhoneFiles.MAX_LIST_LIMIT}. More entries are not lost: " +
                        "the result says has_more and gives next_offset.",
                ),
                "length" to linkedMapOf(
                    "type" to "integer",
                    "minimum" to 1,
                    "description" to
                        "file_read only: the window's size in bytes, ${PhoneFiles.MAX_READ_BYTES} " +
                        "by default. The result says truncated and the file's total bytes.",
                ),
                "start" to linkedMapOf(
                    "type" to "string",
                    "description" to
                        "event_create only: the start as local ISO-8601, YYYY-MM-DDTHH:MM (seconds " +
                        "optional), in the phone's own time zone. A trailing Z or an offset is " +
                        "refused: this is the phone's local clock.",
                ),
                "duration_min" to linkedMapOf(
                    "type" to "integer",
                    "minimum" to MIN_EVENT_MINUTES,
                    "description" to
                        "event_create only: how long the event lasts, in minutes — at least " +
                        "$MIN_EVENT_MINUTES. Zero and negative durations are refused; there is no " +
                        "upper bound here, so the calendar provider's own limits apply.",
                ),
                "calendar" to linkedMapOf(
                    "type" to "string",
                    "description" to
                        "event_create only: the name of the calendar to create the event in. Omit " +
                        "it for the phone's default calendar.",
                ),
                "title" to linkedMapOf(
                    "type" to "string",
                    "description" to
                        "event_create only: the event's title, at most $MAX_EVENT_TITLE_CHARS " +
                        "characters. The audit stores it as a length, never as the text.",
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

        object ClipRead : Call() {
            override val action = "clip_read"
        }

        /** One named file inside one granted folder. [folder] is the tool's word, not a path. */
        data class FileRead(
            val name: String,
            val folder: String?,
            /** Byte offset of a window, or null for the whole-file path. */
            val offset: Long? = null,
            /** Bytes in the window; null means the whole-file path. */
            val length: Long? = null,
        ) : Call() {
            override val action = "file_read"
        }

        /**
         * One page of one directory inside one granted folder. [path] is relative to the granted
         * root (`""` = the root), [depth] is already bounded, and [offset]/[limit] are a page.
         */
        data class FileList(
            val folder: String?,
            val path: String,
            val depth: Int,
            val pattern: String?,
            val offset: Int,
            val limit: Int,
        ) : Call() {
            override val action = "file_list"
        }

        data class FileOffer(val name: String, val folder: String?) : Call() {
            override val action = "file_offer"
        }

        object Agenda : Call() {
            override val action = "agenda"
        }

        /** [start] is normalised local ISO-8601; [durationMin] is minutes, already bounded. */
        data class EventCreate(
            val title: String,
            val start: String,
            val durationMin: Int,
            val calendar: String?,
        ) : Call() {
            override val action = "event_create"
        }

        /** A notifications summary: counts per app, never a title or a body. */
        object Notifications : Call() {
            override val action = "notifications"
        }

        /** One notification's content, by an id a summary has already shown. */
        data class NotificationRead(val id: String) : Call() {
            override val action = "notification_read"
        }

        /** A full or partial contact name, answered with numbers and their labels only. */
        data class Contact(val name: String) : Call() {
            override val action = "contact"
        }

        /**
         * A text composed here and handed to the owner's messaging app. [to] is already a
         * number or, when [contact] is true, a name the Contacts group permits reading.
         */
        data class Message(val to: String, val text: String, val contact: Boolean = false) : Call() {
            override val action = "message"
        }

        /** The dialer, opened with [to] ready. Same number-or-contact shape as [Message]. */
        data class Dial(val to: String, val contact: Boolean = false) : Call() {
            override val action = "dial"
        }

        /** One launchable app, named by package or by the label the launcher shows. */
        data class Launch(val app: String) : Call() {
            override val action = "launch"
        }

        /** One URI, optionally restricted to [package]. */
        data class Open(val uri: String, val packageName: String? = null) : Call() {
            override val action = "open"
        }

        /**
         * Remove one app. [approvalId] is null on the request call (the phone raises the card) and
         * the id the card returned on the execute call. The package is the canonical argument the
         * id is bound to.
         */
        data class Uninstall(val packageName: String, val approvalId: String? = null) : Call() {
            override val action = "uninstall"
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
                PhoneGrants.State.ASK -> {
                    // Ask means two different things by tier, and the difference is the whole
                    // point of the tier: a Tier 0 group waits for the owner's card, while a
                    // **Tier 1 (reported)** group acts when the owner turns it on and is reported
                    // back to them — there is no per-use card to wait for, because the owner has
                    // already said yes to the group and can see what it did. `uninstall` is the
                    // one Tier 2 action with a card on this wire, so it goes to the engine's
                    // two-step handshake rather than being refused outright.
                    val tier = PhoneGrants.tierOf(action) ?: group.tier
                    if (tier == PhoneTiers.REPORTED || action == PhoneApproval.ACTION) {
                        parseArguments(action, args, grants)
                    } else {
                        Parsed.Refuse(
                            PhoneResult.refused(
                                action,
                                PhoneReason.APPROVAL,
                                "the \"${group.label}\" group is set to Ask on this phone; this use waits " +
                                    "for the owner's approval on the phone and was not performed",
                                tier = tier,
                            )
                        )
                    }
                }
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
        "clip_read" -> Parsed.Run(Call.ClipRead)
        "file_read", "file_offer" -> {
            val name = (args["name"] as? String)?.trim().orEmpty()
            PhoneFiles.badPath(name)?.let { return refused(action, PhoneReason.BAD_PATH, it) }
            val (folder, folderError) = folderArg(args, action)
            folderError?.let { return it }
            if (action == "file_offer") {
                // An offer hands the whole file to the bridge; a window is a read, not an offer.
                if (args["offset"] != null || args["length"] != null) {
                    return bad(action, "file_offer takes no offset or length: it offers the whole file")
                }
                return Parsed.Run(Call.FileOffer(name, folder))
            }
            val offset = when (val raw = args["offset"]) {
                null -> null
                else -> {
                    val v = integer(raw)
                        ?: return bad(action, "offset must be a whole number of bytes")
                    if (v < 0) return bad(action, "offset must be 0 or more")
                    v
                }
            }
            val length = when (val raw = args["length"]) {
                null -> null
                else -> {
                    val v = integer(raw)
                        ?: return bad(action, "length must be a whole number of bytes")
                    if (v < 1) return bad(action, "length must be at least one byte")
                    v
                }
            }
            when {
                // A length without an offset starts at the beginning; an offset without a length
                // takes one cap-sized window from there. Neither is a refusal.
                offset == null && length == null -> Parsed.Run(Call.FileRead(name, folder))
                else -> Parsed.Run(
                    Call.FileRead(name, folder, offset ?: 0L, length ?: PhoneFiles.MAX_READ_BYTES),
                )
            }
        }
        "file_list" -> {
            val (folder, folderError) = folderArg(args, action)
            folderError?.let { return it }
            val path = (args["path"] as? String)?.trim().orEmpty()
            PhoneFiles.badListPath(path)?.let { return refused(action, PhoneReason.BAD_PATH, it) }
            val depth = when (val raw = args["depth"]) {
                null -> PhoneFiles.DEFAULT_DEPTH
                else -> {
                    val v = integer(raw) ?: return bad(action, "depth must be a whole number")
                    if (v < 1 || v > PhoneFiles.MAX_DEPTH) {
                        return bad(action, "depth must be between 1 and ${PhoneFiles.MAX_DEPTH}")
                    }
                    v.toInt()
                }
            }
            val pattern = (args["pattern"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            if (pattern != null) {
                PhoneFiles.badPattern(pattern)?.let { return bad(action, it) }
            }
            val offset = when (val raw = args["offset"]) {
                null -> 0
                else -> {
                    val v = integer(raw) ?: return bad(action, "offset must be a whole number")
                    if (v < 0 || v > Int.MAX_VALUE) {
                        return bad(action, "offset must be between 0 and ${Int.MAX_VALUE}")
                    }
                    v.toInt()
                }
            }
            val limit = when (val raw = args["limit"]) {
                null -> PhoneFiles.DEFAULT_LIST_LIMIT
                else -> {
                    val v = integer(raw) ?: return bad(action, "limit must be a whole number")
                    if (v < 1 || v > PhoneFiles.MAX_LIST_LIMIT) {
                        return bad(
                            action,
                            "limit must be between 1 and ${PhoneFiles.MAX_LIST_LIMIT}; a folder " +
                                "with more entries is paged, never refused",
                        )
                    }
                    v.toInt()
                }
            }
            Parsed.Run(Call.FileList(folder, path, depth, pattern, offset, limit))
        }
        "agenda" -> Parsed.Run(Call.Agenda)
        "event_create" -> {
            val title = (args["title"] as? String)?.trim().orEmpty()
            when {
                title.isEmpty() -> return bad(action, "title is required")
                title.length > MAX_EVENT_TITLE_CHARS ->
                    return bad(action, "title is longer than $MAX_EVENT_TITLE_CHARS characters")
            }
            val start = PhoneCalendar.parseLocal(args["start"] as? String)
                ?: return bad(
                    action,
                    "start is required and must be local ISO-8601, YYYY-MM-DDTHH:MM with optional " +
                        "seconds; a Z or an offset is not the phone's local clock",
                )
            val minutes = integer(args["duration_min"])
                ?: return bad(action, "duration_min is required and must be whole minutes")
            if (minutes < MIN_EVENT_MINUTES) {
                return bad(action, "duration_min must be at least $MIN_EVENT_MINUTES minute")
            }
            // No upper bound of ours: the calendar provider decides what it will store, and a
            // duration it refuses is reported as unavailable rather than pre-empted here.
            if (minutes > Int.MAX_VALUE) {
                return bad(action, "duration_min is beyond what a calendar can hold")
            }
            val calendar = (args["calendar"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            Parsed.Run(Call.EventCreate(title, start.iso, minutes.toInt(), calendar))
        }
        "notifications" -> Parsed.Run(Call.Notifications)
        "notification_read" -> {
            val id = (args["id"] as? String)?.trim().orEmpty()
            when {
                id.isEmpty() -> bad(action, "id is required: one of the ids a notifications summary returned")
                id.length > MAX_NOTIFICATION_ID_CHARS ->
                    bad(action, "id is longer than $MAX_NOTIFICATION_ID_CHARS characters")
                else -> Parsed.Run(Call.NotificationRead(id))
            }
        }
        "contact" -> {
            val name = (args["name"] as? String)?.trim().orEmpty()
            when {
                name.isEmpty() -> bad(action, "name is required: a full or partial contact name")
                name.length > MAX_CONTACT_NAME_CHARS ->
                    bad(action, "name is longer than $MAX_CONTACT_NAME_CHARS characters")
                else -> Parsed.Run(Call.Contact(name))
            }
        }
        "message" -> {
            val text = args["text"] as? String
            when {
                text.isNullOrBlank() -> bad(action, "text is required and must not be empty")
                text.length > MAX_MESSAGE_CHARS ->
                    bad(action, "text is longer than $MAX_MESSAGE_CHARS characters")
                else -> recipient(action, args, grants) { to, contact ->
                    Call.Message(to, text, contact)
                }
            }
        }
        "dial" -> recipient(action, args, grants) { to, contact -> Call.Dial(to, contact) }
        "launch" -> {
            val name = (args["app"] as? String)?.trim().orEmpty()
            when {
                name.isEmpty() -> bad(action, "app is required")
                name.length > MAX_APP_CHARS ->
                    bad(action, "app is longer than $MAX_APP_CHARS characters")
                else -> Parsed.Run(Call.Launch(name))
            }
        }
        "open" -> {
            val url = when (val verdict = uri(action, args["uri"] as? String)) {
                is UriArg.Use -> verdict.uri
                is UriArg.Refuse -> return Parsed.Refuse(verdict.result)
            }
            val pkg = (args["package"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            if (pkg != null && pkg.length > MAX_APP_CHARS) {
                return bad(action, "package is longer than $MAX_APP_CHARS characters")
            }
            Parsed.Run(Call.Open(url, pkg))
        }
        // The apps group's safe half is launch/open. `uninstall` is the one destructive action
        // with a card: it parses into a call and the engine runs its two-step handshake. `install`
        // is still named and refused, because its own installer confirmation is a different lane.
        "uninstall" -> {
            val unexpected = args.keys - setOf("action", "package", "approval")
            if (unexpected.isNotEmpty()) {
                return bad(
                    action,
                    "uninstall takes only package and approval; unexpected: " +
                        unexpected.sorted().joinToString(", "),
                )
            }
            val pkg = PhoneApproval.packageName(args["package"] as? String)
                ?: return bad(
                    action,
                    "package is required and must be a dotted package name, such as " +
                        "org.mozilla.firefox",
                )
            val approval = when (val raw = args["approval"]) {
                null -> null
                is String -> raw.trim().takeIf { it.isNotEmpty() }
                else -> return bad(action, "approval must be the id a previous result returned")
            }
            if (approval != null && approval.length > MAX_APPROVAL_ID_CHARS) {
                return bad(action, "approval is longer than $MAX_APPROVAL_ID_CHARS characters")
            }
            Parsed.Run(Call.Uninstall(pkg, approval))
        }
        "install" -> Parsed.Refuse(
            PhoneResult.refused(
                action,
                PhoneReason.APPROVAL,
                "installing an app needs its own approval card, which is not built; nothing was " +
                    "performed",
                tier = PhoneTiers.APPROVAL,
            )
        )
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

    /**
     * The `to` of a message or a dial: a number, or a contact **name** only when the Contacts
     * group is not Off. Resolving a name is reading the address book, so a phone whose owner
     * has not granted Contacts refuses with `not_granted` naming it — never with a generic
     * complaint about the arguments. [build] receives the recipient and whether it is a name.
     */
    private fun recipient(
        action: String,
        args: Map<String, Any?>,
        grants: PhoneGrants.Source,
        build: (String, Boolean) -> Call,
    ): Parsed {
        val raw = (args["to"] as? String)?.trim().orEmpty()
        if (raw.isEmpty()) {
            return bad(action, "to is required: a phone number, or a contact name when Contacts is on")
        }
        if (raw.length > MAX_TO_CHARS) {
            return bad(action, "to is longer than $MAX_TO_CHARS characters")
        }
        number(raw)?.let { return Parsed.Run(build(it, false)) }
        val contacts = PhoneGrants.GROUPS.first { it.id == "contacts" }
        if (PhoneGrants.stateOf(grants, contacts) == PhoneGrants.State.OFF) {
            return Parsed.Refuse(
                PhoneResult.refused(
                    action,
                    PhoneReason.NOT_GRANTED,
                    "\"$raw\" is a contact name, and looking one up reads your address book; " +
                        "the \"${contacts.label}\" group is off on this phone. Only the owner can " +
                        "grant it in Phone tools, or give a number instead.",
                    tier = PhoneTiers.of(action),
                )
            )
        }
        return Parsed.Run(build(raw, true))
    }

    /**
     * A phone number, normalised to an optional `+` and its digits; null means "this is a
     * name, not a number". Spaces, dashes, dots and parentheses are formatting and are
     * removed; **letters make it a name**, which is what routes the Contacts check above.
     */
    fun number(text: String?): String? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (!raw.all { it.isDigit() || it in "+-() ." }) return null
        val digits = raw.filter { it.isDigit() }
        if (digits.length < 3) return null
        return (if (raw.startsWith("+")) "+" else "") + digits
    }

    /** The verdict on `open`'s `uri`: the URI to use, or the refusal it has already earned. */
    sealed class UriArg {
        data class Use(val uri: String) : UriArg()
        data class Refuse(val result: PhoneResult) : UriArg()
    }

    /**
     * `open`'s `uri`: it must carry a scheme (`https:`, `mailto:`, `geo:`, a deep link) and no
     * whitespace. Three shapes are refused with `bad_uri` **before any intent exists**:
     *
     *  * `intent:` — Android starts an explicit component from it, with arbitrary extras and
     *    flags. That reaches an activity the owner never chose, and is the only reason a URI would
     *    need to be a URI, so it is refused and the detail names the scheme.
     *  * `file:` — a path on disk is not something the owner asked Vox to hand to a viewer.
     *    `file_offer`/`file_read` exist for files, inside the folder grant.
     *  * **no scheme at all** — refused, never guessed into `https://`.
     *
     * `content:` and `https:`/`http:` pass through and are resolved by the platform as before.
     * A missing, over-long or whitespace-bearing string is `invalid-arguments` instead.
     */
    fun uri(action: String, text: String?): UriArg {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty() || raw.length > MAX_URI_CHARS) {
            return UriArg.Refuse(
                PhoneResult.refused(
                    action,
                    PhoneReason.INVALID,
                    "uri is required and at most $MAX_URI_CHARS characters",
                ),
            )
        }
        if (raw.any { it.isWhitespace() || it.code < 0x20 }) {
            return UriArg.Refuse(
                PhoneResult.refused(
                    action,
                    PhoneReason.INVALID,
                    "uri must contain no whitespace",
                ),
            )
        }
        val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*):(.+)$").find(raw)
            ?.groupValues?.get(1)?.lowercase()
            ?: return UriArg.Refuse(
                PhoneResult.refused(
                    action,
                    PhoneReason.BAD_URI,
                    "uri has no scheme; write it in full (https://…), because nothing is " +
                        "guessed into one",
                ),
            )
        if (scheme == "intent" || scheme == "file") {
            return UriArg.Refuse(
                PhoneResult.refused(
                    action,
                    PhoneReason.BAD_URI,
                    "the $scheme: scheme is refused; nothing was opened. A file belongs to " +
                        "file_offer or file_read inside a granted folder, and a component is " +
                        "never started from a URI",
                ),
            )
        }
        return UriArg.Use(raw)
    }

    /**
     * The `folder` argument, as the tool's word or null for the default, together with a refusal
     * when it is not one of the two words. Shared by the three file actions so the words and their
     * case can only be read one way.
     */
    private fun folderArg(args: Map<String, Any?>, action: String): Pair<String?, Parsed?> =
        when (val raw = args["folder"]) {
            null -> null to null
            is String -> if (raw.isBlank()) {
                null to null
            } else {
                PhoneFiles.FOLDERS.firstOrNull { it.equals(raw.trim(), ignoreCase = true) }?.let {
                    it to null
                } ?: (null to bad(action, "folder must be ${PhoneFiles.FOLDERS.joinToString(" or ")}"))
            }
            else -> null to bad(action, "folder must be a string")
        }

    /** A refusal with a reason of its own — `bad_path`, `no_folder`, `not_text`, `too_big`. */
    private fun refused(action: String, reason: String, detail: String): Parsed =
        Parsed.Refuse(PhoneResult.refused(action, reason, detail))

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
        // Clipboard (#429 lane): a write, and a read only when the platform allows it.
        "clip", "clip_read",
        // Notifications and contacts (#429 read lane): both are reads and both are instant. The
        // notification listener is call-scoped, and a summary carries counts only; a contact
        // lookup returns names and numbers, never notes, addresses, photos or email.
        "notifications", "notification_read", "contact",
        // Messages/calls (#429 handoff lane): a draft handed to the owner's messaging app, and
        // the dialer opened with a number ready. No SEND_SMS and no CALL_PHONE exist on this path.
        "message", "dial",
        // Apps (#429 handoff lane): the safe half of the group. Only launchable activities are
        // resolved, and a launch enters PiP first while a call is live.
        "launch", "open",
    )
    private val TIER_1 = setOf(
        "note",
        // Files (#429 lane 2, listing lane): what is in the folder the owner granted, one named
        // file inside it, and one file handed to the bridge. Nothing here walks outside the grant.
        "file_list", "file_read", "file_offer",
        // Calendar (#429 lane 2): today's events, and one event that can be undone.
        "agenda", "event_create",
    )

    /**
     * The consequential capabilities the phone deliberately does **not** execute for the mind.
     * They are named so a direct call gets "this needs the owner" rather than "unknown action".
     */
    private val TIER_2 = setOf(
        "purchase", "pay", "install", "uninstall", "transfer",
        // `call`, `send` and `email` were names under the old messages-calls group. They are gone
        // with it: calls are `dial` (the dialer, never CALL_PHONE) and messages are `message`
        // (SENDTO, never SEND_SMS). A call naming one of them is unknown, not a capability the
        // phone is pretending not to have.
        //
        // A file delete is a Tier 2 capability that this lane deliberately does not build: there
        // is no undo, so it is named here and never advertised.
        "delete",
    )

    /** The names in one tier, sorted — the shape `status` reports without a second list. */
    fun names(tier: Int): List<String> = when (tier) {
        INSTANT -> TIER_0.sorted()
        REPORTED -> TIER_1.sorted()
        else -> TIER_2.sorted()
    }

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
    /** The args name a path, a URI or a name that resolves outside the folder the owner granted. */
    const val BAD_PATH = "bad_path"

    /**
     * The URI is not one `open` will hand to the platform: `intent:`, `file:`, or no scheme at
     * all. Nothing was resolved and no intent was built.
     */
    const val BAD_URI = "bad_uri"

    /** No folder has been granted to the app yet (or not the one the call named). */
    const val NO_FOLDER = "no_folder"

    /** The file's bytes are not text; `file_read` returns text only. */
    const val NOT_TEXT = "not_text"

    /** The file is above `file_read`'s cap. It is refused, never truncated. */
    const val TOO_BIG = "too_big"

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

    /** The owner denied the card. Nothing was performed, and the id is burnt. */
    const val APPROVAL_DENIED = "approval_denied"

    /** The card expired unanswered (silence is no) before this id was used. */
    const val APPROVAL_EXPIRED = "approval_expired"

    /** The id was approved for one app and the call names another. Nothing was removed. */
    const val APPROVAL_MISMATCH = "approval_mismatch"

    /** No such approval id, or it has already been used. Nothing was performed. */
    const val APPROVAL_UNKNOWN = "approval_unknown"

    /** Three cards are already waiting; nothing was raised and nothing was performed. */
    const val APPROVAL_BUSY = "approval_busy"

    /** The owner has not granted this capability group. Nothing was done, and it is not offered. */
    const val NOT_GRANTED = "not_granted"

    /**
     * A handoff found nothing to receive it: no app answers `ACTION_SENDTO`, there is no
     * dialer, or no launchable activity matches the package or name. Nothing was started.
     */
    const val NO_APP = "no_app"

    /**
     * The id was not in a notifications summary of this call (or the notification is gone). The
     * content is only reachable by naming an id a summary already showed, so a guessed id is
     * refused rather than becoming a way to vacuum notifications.
     */
    const val UNKNOWN_ID = "unknown-id"
}

/**
 * One result, and its wire form.
 *
 * `ok` is set only by the code path that actually performed the action, so the shape itself is the
 * honesty rule: a refusal has a `reason` and never an `id`, `state` or other success fact. The one
 * exception is the approval handshake: a request refusal carries `approval_id`, `expires_at` and
 * `summary` as its **call to action**, not as a claim that anything ran.
 */
data class PhoneResult(
    val ok: Boolean,
    val action: String,
    val tier: Int? = null,
    val id: String? = null,
    val reason: String? = null,
    val detail: String? = null,
    /** Success facts, or the approval id a request refusal hands back. */
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
            // An approval-required result is the request half of the handshake. These fields are
            // what the agent needs to make the second call; they are not a success fact.
            out.putAll(facts)
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

        /** Nothing happened, and why. [facts] carries an approval request's id and expiry. */
        fun refused(
            action: String,
            reason: String,
            detail: String,
            tier: Int? = PhoneTiers.of(action),
            facts: Map<String, Any?> = emptyMap(),
        ): PhoneResult = PhoneResult(false, action, tier, null, reason, detail, facts)
    }
}
