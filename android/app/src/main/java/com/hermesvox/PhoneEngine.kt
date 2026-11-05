package com.hermesvox

/**
 * PhoneEngine — the state and the rules behind [PhoneTool], with the platform behind an interface.
 *
 * Everything the phone does to itself passes through here: the **user-initiated** path
 * (`LocalActionGrammar` → [LocalActions.run]) and the **mind-initiated** path (MCP `tools/call`).
 * One registry, one set of ids, one undo table — because the presence context block and the
 * `phone` tool must never disagree about what is running. #421's duplicate-authority risk is a
 * divergence between two memories of the same fact; this class is the only memory.
 *
 * The two paths keep different guarantees, as D32 says:
 *
 *  * user-initiated: no round trip, and the confirmation is spoken from the result;
 *  * mind-initiated: the result is the reply. A tool call that fails returns `ok: false` with a
 *    reason, and the mind is expected to say so rather than to claim the action.
 *
 * Ids and undo lifetimes:
 *
 *  * `timer-N` / `alarm-N`: the undo is "cancel", and it lives exactly as long as the action.
 *  * `dnd-N` / `volume-N`: the undo restores the previous setting, so it lives until that setting is
 *    changed again (a second `dnd` supersedes the first undo — the older "previous" is no longer
 *    previous) or for [CHANGE_UNDO_MS].
 *  * `note-N`: the undo deletes the note, for [NOTE_UNDO_MS]. A note is Tier 1 and is reported to the
 *    mind on the next turn, so its undo has to outlive the turn that made it.
 *
 * ## Who initiated it (H4)
 *
 * Every entry in the registry carries its [Origin] — the owner's own voice on the phone, or the
 * mind over MCP. The two paths share one registry, so without the tag an owner's 6 am alarm and a
 * note the mind wrote would be the same kind of record, and `status`/`undo` on the MCP wire would
 * serve both. The mind's door only opens for a **mind** entry whose group is not Off; the owner's
 * door (the sheet's Undo tap, [LocalActions.run]) is not restricted, because those are the owner's
 * own actions.
 *
 * Pure JVM; [LocalActions] is the Android platform. Every clock is passed in, so the expiry rules
 * are proven in [PhoneEngineTest] without waiting.
 */
class PhoneEngine(
    private val platform: PhonePlatform,
    /** Where the one-line action log goes. Injected so the engine stays free of android.util.Log. */
    private val log: (String) -> Unit = {},
    /**
     * The owner's grant policy (#429). Read at call time, never cached: a group switched Off while a
     * call is live is Off for the next call. All Off is the default, because a phone that was never
     * asked has granted nothing.
     */
    private val grants: PhoneGrants.Source = PhoneGrants.ALL_OFF,
    /**
     * Every call the mind makes, refusal included: (result, arguments, epoch millis). The audit is
     * written *after* the result exists, so it records what happened rather than what was asked.
     */
    private val audit: (PhoneResult, Map<String, Any?>, Long) -> Unit = { _, _, _ -> },
    /**
     * Where the `uninstall` card is raised (the in-app card, else a heads-up notification). A
     * surface that cannot show anything makes the request refuse `unavailable` rather than
     * letting a two-minute silence look like a denial.
     */
    private val approvalSurface: PhoneApproval.Surface = PhoneApproval.Surface.NONE,
    /** The approval lifecycle rows: request, decision (approved/denied/expired, and by tap/voice). */
    private val auditRow: (PhoneAudit.Entry) -> Unit = {},
) {

    /** The one approval desk. Its state outlives a call; the platform only shows the card. */
    private val approvals = PhoneApprovalDesk(
        surface = approvalSurface,
        audit = { card, outcome, via, at ->
            try {
                auditRow(PhoneAudit.approvalEntry(at, card, outcome, via))
            } catch (_: Throwable) {
            }
        },
    )

    /** What the engine needs from the phone. One implementation on device; a fake in tests. */
    interface PhonePlatform : DialHandoff.Host {
        /** A media transport key. Resolved to a platform key code by the implementation. */
        enum class MediaKey { PLAY, PAUSE, NEXT, PREVIOUS }

        /**
         * What the platform will say about the active media session. [known] is false when it will
         * not name the session — which is the case without notification-listener access — and
         * [packageName]/[title] are null then. [playing] is the cheap reading
         * (`AudioManager.isMusicActive`) that needs no access, and is null when even that fails.
         */
        data class Playback(
            val known: Boolean,
            val playing: Boolean?,
            val packageName: String?,
            val title: String?,
            /**
             * True when the phone has granted this app the session list (notification-listener
             * access), whether or not a session is publishing right now. It separates "the phone
             * cannot see" from "the phone can see, and nothing is publishing".
             */
            val listenerGranted: Boolean = false,
        )

        /**
         * Send one media key to whichever session the platform considers active. False when the
         * audio service refused or is absent — never a claim that playback changed.
         */
        fun mediaKey(key: MediaKey): Boolean

        /** The active session, to the extent the platform will admit it. Never invent a title. */
        fun playback(): Playback

        /**
         * One read of the phone's own state. **Every field is nullable, and null means "the
         * platform would not say"** — it is never a plausible zero. `network` and `charging_source`
         * distinguish "read, and the answer is none" from "could not read".
         */
        data class DeviceSnapshot(
            val batteryPercent: Int?,
            val chargingSource: String?,
            val chargingStatus: String?,
            val screenOn: Boolean?,
            val network: String?,
            val tailnetPresent: Boolean?,
            val freeStorageBytes: Long?,
            val mediaVolume: Int?,
            val dnd: String?,
            val timeZoneId: String?,
            val utcOffsetMinutes: Int?,
        )

        /** The state now. Implementations catch their own failures and answer null for them. */
        fun deviceState(): DeviceSnapshot

        /**
         * Put [text] on the clipboard. The write is attempted wherever the call is — Android's
         * restriction is on clipboard **reads**, not writes, so background and PiP are not reasons
         * to refuse before asking. [ClipWrite.Refused] carries the platform's own reason when it
         * will not take the text.
         */
        fun clip(text: String): ClipWrite

        /** What happened to a clipboard write: accepted, or refused with the platform's reason. */
        sealed class ClipWrite {
            /** The platform took the text. */
            data object Done : ClipWrite()

            /** The platform would not take the text; [detail] is its reason, never a guess. */
            data class Refused(val detail: String) : ClipWrite()
        }

        /**
         * What the clipboard holds, **as far as the platform will let this app see it**. Android
         * does not answer for an app that is not focused, so [ClipRead.Unavailable] carries the
         * platform's own outcome rather than a stale or empty stand-in; when the answer is "no
         * clip" while Vox is not focused, the detail says focus is the **likely** cause.
         */
        sealed class ClipRead {
            /** The clipboard's text, exactly as the platform handed it over. */
            data class Text(val text: String) : ClipRead()

            /** There is a clipboard, and it holds nothing. */
            data object Empty : ClipRead()

            /** The platform would not let this app read it; [detail] is the platform's reason. */
            data class Unavailable(val detail: String) : ClipRead()
        }

        fun readClip(): ClipRead =
            ClipRead.Unavailable("the platform does not let this app read the clipboard")

        /** Arm a countdown. False means the phone refused. */
        fun startTimer(id: String, durationMs: Long, atEpochMs: Long): Boolean

        /** Arm a wall-clock alarm for the next occurrence of [hour]:[minute]. */
        fun startAlarm(id: String, hour: Int, minute: Int, atEpochMs: Long): Boolean

        /** Cancel a timer or alarm by the id it was armed under. */
        fun cancelTimer(id: String): Boolean

        /** Whether the app holds Do Not Disturb policy access (a special access, user-granted). */
        fun dndGranted(): Boolean

        /** The current interruption state, or null when it cannot be read. */
        fun currentDnd(): Boolean?

        fun setDnd(on: Boolean): Boolean

        /** The current media volume as a percentage, or null when it cannot be read. */
        fun currentVolume(): Int?

        /** Set the media volume and return the level actually in effect, or null if it did not take. */
        fun setVolume(percent: Int): Int?

        /**
         * One step up (+1) or down (-1) on the media stream, the way the volume keys move it. The
         * step is the platform's, not a percentage: the user path has always been one stream step
         * and D32 keeps it so. Returns the level in effect, or null if it did not take.
         */
        fun adjustVolume(direction: Int): Int?

        fun saveNote(id: String, text: String, atEpochMs: Long): Boolean

        fun deleteNote(id: String): Boolean

        // ---- files (#429 lane 2) ----

        /** One folder the owner granted with the system picker. [label] is the folder's own name. */
        data class GrantedFolder(val label: String)

        /** The outcome of looking one name up inside one granted folder. */
        sealed class FileText {
            /** The file's text, and the size the provider or the read reported. */
            data class Found(val name: String, val bytes: Long, val text: String) : FileText()

            /**
             * A window of a file: [text] begins at byte [offset] of a file whose whole size is
             * [totalBytes], and covers [windowBytes] bytes — exactly `[offset, offset + windowBytes)`
             * of the file. [offset] is never before the requested start and the window never ends
             * after the requested start plus length, so windows stitched by the offsets asked for
             * never repeat a character; the engine reports the offset it actually used rather than
             * the one that was asked for, because a cut inside a character cannot be honoured.
             */
            data class Window(
                val name: String,
                val totalBytes: Long,
                val offset: Long,
                val windowBytes: Long,
                val text: String,
            ) : FileText()

            /** No document with that name inside the granted folder. */
            data object Missing : FileText()

            /** The bytes are not text (a binary file, or bytes that are not UTF-8). */
            data object NotText : FileText()

            /** Larger than [PhoneFiles.MAX_READ_BYTES]; [bytes] is null when the provider hid it. */
            data class TooBig(val bytes: Long?) : FileText()

            /** The name resolved outside the granted tree. Never read. */
            data object Outside : FileText()

            /** The provider failed or would not answer. */
            data class Failed(val detail: String) : FileText()
        }

        /** The outcome of handing one file to the existing file bridge. */
        sealed class FileHandoff {
            data class Ready(
                val name: String,
                val mime: String,
                val bytes: Long,
                val sha256: String,
                val url: String,
                val expiresAtSec: Long,
            ) : FileHandoff()

            data object Missing : FileHandoff()
            data object Outside : FileHandoff()

            /** Above the bridge's [FileOffer.MAX_BYTES]. */
            data class TooBig(val bytes: Long) : FileHandoff()

            /** No tailnet address, no server, or the copy failed: nothing was offered. */
            data class Unavailable(val detail: String) : FileHandoff()
        }

        /**
         * True when the system's notification-listener access is held. The grant is global and
         * permanent for the install; `notifications` still refuses `permission` without it, and
         * its own use of the access is call-scoped regardless ([PhoneNotifications]).
         */
        fun notificationListenerGranted(): Boolean = true

        /** The folders the owner has granted, in grant order. Empty means neither action can run. */
        fun folders(): List<GrantedFolder> = emptyList()

        /** Read one named file inside [folder], capped and text-only. */
        fun fileText(folder: GrantedFolder, name: String): FileText = FileText.Missing

        /**
         * Read one window of one named file: [length] bytes from [offset], snapping to character
         * boundaries. This is how a file larger than [PhoneFiles.MAX_READ_BYTES] is read — it is
         * never refused for its size when the caller asked for a window.
         */
        fun fileWindow(
            folder: GrantedFolder,
            name: String,
            offset: Long,
            length: Long,
        ): FileText = FileText.Missing

        /**
         * List [path] inside [folder] to [depth], paged by [offset]/[limit]. The ordering, the
         * pattern and the page arithmetic are [PhoneFiles.list]; an implementation only answers
         * which children a directory has.
         */
        fun fileList(
            folder: GrantedFolder,
            path: String,
            depth: Int,
            pattern: String?,
            offset: Int,
            limit: Int,
        ): PhoneFiles.ListResult = PhoneFiles.ListResult.MissingPath

        /** Stage one named file inside [folder] and serve it through the file bridge. */
        fun fileOffer(folder: GrantedFolder, name: String): FileHandoff = FileHandoff.Missing

        // ---- calendar (#429 lane 2) ----

        /** One event, already rendered in the phone's own zone. Null fields are "not said". */
        data class AgendaEvent(
            val start: String,
            val end: String,
            val title: String?,
            val calendar: String?,
            /** The event's own description, when the provider offers it. */
            val description: String? = null,
            /** The event's location, when the provider offers it. */
            val location: String? = null,
            /**
             * Attendee **names**, when the provider offers them. Addresses are never read: the
             * group returns who, not how to reach them.
             */
            val attendees: List<String>? = null,
        )

        /** The event the platform inserted, with the times it actually stored. */
        data class CreatedEvent(
            val id: String,
            val start: String,
            val end: String,
            val startMs: Long,
            val endMs: Long,
            val calendar: String?,
        )

        /**
         * The exact runtime permission this phone lacks for its calendar, or null when it holds
         * both. The string is the platform's own, e.g. `android.permission.READ_CALENDAR`.
         */
        fun calendarPermission(): String? = null

        /**
         * The permission `agenda` needs: `READ_CALENDAR` alone. A read does not require the write
         * grant, and denying write must not take the owner's read away.
         */
        fun calendarReadPermission(): String? = calendarPermission()

        /**
         * The permissions `event_create` needs: read (to find a writable calendar) and write.
         */
        fun calendarWritePermission(): String? = calendarPermission()

        /** Today's events, in the phone's zone. Null when the platform would not answer. */
        fun agenda(): List<AgendaEvent>? = null

        /** Insert one event. Null when the phone refused or there is no writable calendar. */
        fun createEvent(
            title: String,
            startLocal: String,
            durationMin: Int,
            calendar: String?,
        ): CreatedEvent? = null

        /** Delete the event this phone created, for its undo. False when Android would not. */
        fun deleteEvent(id: String): Boolean = false

        // ---- handoffs (#429: messages, calls, apps) ----

        /** One activity that can actually be started: its package and its class. */
        data class AppTarget(val packageName: String, val className: String) {
            /** The stable `package/class` form a result reports, never a guessed one. */
            val component: String get() = "$packageName/$className"
        }

        /** The outcome of handing something to an app that answers an intent. */
        sealed class Handoff {
            /** An app took the intent; [packageName] is the one Android resolved. */
            data class Done(val packageName: String) : Handoff()

            /** Nothing on this phone answers that intent. Nothing was started. */
            data object NoApp : Handoff()

            /** Android refused to start it; [detail] is the platform's own explanation. */
            data class Failed(val detail: String) : Handoff()
        }

        // ---- contacts (#429 read lane) ----

        /** One number as the owner labelled it: `mobile`, `home`, `work`, or their own word. */
        data class ContactNumber(val label: String, val number: String)

        /** One person and their numbers. Nothing else is read from the address book. */
        data class ContactMatch(val displayName: String, val numbers: List<ContactNumber>)

        /**
         * The exact runtime permission the phone lacks for contacts, or null when it holds it.
         * Always `android.permission.READ_CONTACTS` today; the string is the platform's own.
         */
        fun contactsPermission(): String? = null

        /**
         * Up to [limit] people whose display name contains [name], case-insensitively, each with
         * the numbers the owner labelled. **No notes, addresses, photos or email**: the group's
         * purpose is looking up a number by name, and that is the whole projection. Null means the
         * provider failed; an empty list is a real answer.
         */
        fun contactMatches(name: String, limit: Int): List<ContactMatch>? = emptyList()

        /**
         * A recipient name resolved through the owner's address book: the first number of the
         * first match. Null means there was no match (or the read failed); the engine distinguishes
         * that from a missing permission through [contactsPermission].
         */
        fun contactNumber(name: String): String? = null

        /**
         * Compose [text] to [to] and open the messaging app through `ACTION_SENDTO` (`smsto:`).
         * **This never sends**: no `SEND_SMS`, no `SmsManager`, no default-SMS role. The owner
         * presses send in their own app.
         */
        fun sendMessage(to: String, text: String): Handoff = Handoff.NoApp

        /** Resolve an `ACTION_DIAL` target before anything is ended. False is `no_app`. */
        override fun canDial(): Boolean = true

        /** Open the dialer through `ACTION_DIAL` (`tel:`). Never `CALL_PHONE`. */
        override fun dial(number: String): Boolean = false

        /** Resolve [app] among **launchable** activities only: package name, then launcher label. */
        fun resolveLaunchable(name: String): AppTarget? = null

        /** Start the launchable activity [target] resolved to. */
        fun startApp(target: AppTarget): Boolean = false

        /** Resolve a URI among the apps that claim it, optionally restricted to [packageName]. */
        fun resolveUri(uri: String, packageName: String?): AppTarget? = null

        /** Start `ACTION_VIEW` for [uri] in the resolved [target]. */
        fun startUri(target: AppTarget, uri: String): Boolean = false

        // ---- apps: uninstall (the one card-gated action) ----

        /** The label the launcher shows for [packageName], or null when no such app is installed. */
        fun appLabel(packageName: String): String? = null

        /** The outcome of asking Android to remove one app. */
        sealed class Uninstall {
            /**
             * Android's own confirmation screen is open. **The app is not removed yet** and this
             * phone cannot see whether the owner confirms there; the result says exactly that.
             */
            data class Confirming(val packageName: String) : Uninstall()

            /** No installed app has that package any more. */
            data object Missing : Uninstall()

            /** The platform would not open its confirmation screen. */
            data class Unavailable(val detail: String) : Uninstall()
        }

        /**
         * Open Android's own uninstall confirmation for [packageName] (`ACTION_DELETE`). The owner
         * answers that screen; Vox never removes anything silently.
         */
        fun uninstall(packageName: String): Uninstall =
            Uninstall.Unavailable("this phone cannot open the platform's uninstall confirmation")

        /**
         * The PiP-first window ([CallWindow]), when an activity is attached. Null means there is
         * no window to shrink, so a launch starts immediately — PiP never fails a launch.
         */
        fun window(): CallWindow.Host? = null
    }

    private data class Armed(
        val id: String,
        val kind: String,
        /** [firesAtElapsed] for timers, [firesAtEpoch] for alarms; the other is 0. */
        val firesAtElapsed: Long,
        val firesAtEpoch: Long,
        val durationMs: Long,
        val hour: Int,
        val minute: Int,
        /** Who asked for it: the owner's voice, or the mind over MCP. */
        val origin: Origin,
    )

    private data class Undo(
        val id: String,
        val kind: String,
        val expiresAtElapsed: Long,
        /** Who may reverse it. The owner's door is unrestricted; the mind's is not. */
        val origin: Origin,
        /** True when the platform really reversed it. */
        val perform: () -> Boolean,
    )

    private val lock = Any()
    private val timers = LinkedHashMap<String, Armed>()
    private val alarms = LinkedHashMap<String, Armed>()
    private val notes = LinkedHashMap<String, Note>()
    private val undos = LinkedHashMap<String, Undo>()
    private var nextId = 0
    private var dndApplied: Boolean? = null
    private var volumeApplied: Int? = null

    /**
     * Who initiated an entry in the registry.
     *
     * The owner path ([LocalActions.run], and the sheet's Undo tap) and the mind path (MCP
     * `tools/call`) share one registry, so the tag is what keeps the mind out of the owner's own
     * actions — and what makes "the owner's note" and "the mind's note" different records.
     */
    enum class Origin(val id: String) {
        OWNER("owner"),
        MIND("mind"),
        ;

        companion object {
            /** The stored form; null when it is not an origin. */
            fun of(raw: String?): Origin? = values().firstOrNull { it.id == raw?.trim()?.lowercase() }
        }
    }

    data class Note(
        val id: String,
        val text: String,
        val atEpochMs: Long,
        /** The door the note came through; see [Origin]. */
        val origin: Origin,
    )

    // ---- the one entry point the MCP server uses ----

    /** Parse and execute one `phone` tool call. Never throws: a bad call is a described refusal. */
    fun dispatch(
        arguments: Map<String, Any?>,
        nowElapsed: Long,
        nowEpoch: Long,
    ): PhoneResult {
        approvals.tick(nowEpoch)
        val result = when (val parsed = PhoneTool.parse(arguments, grants)) {
            is PhoneTool.Parsed.Refuse -> parsed.result
            // Everything on this wire is the mind's, and every entry it registers says so.
            is PhoneTool.Parsed.Run -> execute(parsed.call, nowElapsed, nowEpoch, Origin.MIND)
        }
        // The audit records refusals as well as successes, because "my agent says it tried" is a
        // claim the owner has to be able to check. A failure to record must not fail the call.
        // The initial approval request is the one exception: the desk already wrote its request row,
        // and the "Recent actions" list must show request → decision → execution, not a duplicate.
        val requestRowAlreadyWritten = result.action == PhoneApproval.ACTION &&
            !result.ok &&
            result.reason == PhoneReason.APPROVAL &&
            result.facts["approval_id"] != null
        if (!requestRowAlreadyWritten) {
            try {
                audit(result, arguments, nowEpoch)
            } catch (_: Throwable) {
            }
        }
        return result
    }

    /**
     * Execute one validated call, tagging every registry entry with [origin].
     *
     * [origin] is required: the caller has to say whose action this is, because the tag is what
     * `status` and `undo` filter on. [dispatch] passes [Origin.MIND]; the owner's voice passes
     * [Origin.OWNER].
     */
    fun execute(
        call: PhoneTool.Call,
        nowElapsed: Long,
        nowEpoch: Long,
        origin: Origin,
    ): PhoneResult = synchronized(lock) {
        prune(nowElapsed, nowEpoch)
        when (call) {
            is PhoneTool.Call.Timer -> setTimer(call.durationMs, nowElapsed, nowEpoch, origin)
            is PhoneTool.Call.Alarm -> setAlarm(call.hour, call.minute, nowEpoch, origin)
            is PhoneTool.Call.Dnd -> setDnd(call.on, nowElapsed, origin)
            is PhoneTool.Call.Volume -> setVolume(call.level, call.direction, nowElapsed, origin)
            is PhoneTool.Call.Note -> saveNote(call.text, nowElapsed, nowEpoch, origin)
            is PhoneTool.Call.PlayPause -> pressMedia(call.pause)
            is PhoneTool.Call.Skip -> skip(call.previous)
            PhoneTool.Call.NowPlaying -> nowPlaying()
            PhoneTool.Call.State -> deviceState(nowEpoch)
            is PhoneTool.Call.Clip -> clip(call.text)
            PhoneTool.Call.ClipRead -> readClip()
            is PhoneTool.Call.FileList -> listFiles(call)
            is PhoneTool.Call.FileRead -> readFile(call)
            is PhoneTool.Call.FileOffer -> offerFile(call)
            PhoneTool.Call.Agenda -> agenda()
            is PhoneTool.Call.EventCreate -> createEvent(call, nowElapsed, nowEpoch, origin)
            PhoneTool.Call.Notifications -> notifications()
            is PhoneTool.Call.NotificationRead -> notificationRead(call.id)
            is PhoneTool.Call.Contact -> contacts(call)
            is PhoneTool.Call.Message -> message(call)
            is PhoneTool.Call.Dial -> dial(call)
            is PhoneTool.Call.Launch -> launch(call)
            is PhoneTool.Call.Open -> open(call)
            is PhoneTool.Call.Uninstall -> uninstall(call, nowEpoch)
            is PhoneTool.Call.Undo -> undo(call.id, nowElapsed, nowEpoch, origin)
            PhoneTool.Call.Status -> status(nowElapsed, nowEpoch)
        }
    }

    /** The platform's clock fired: the action has left the registry and its undo with it. */
    fun onFired(id: String) = synchronized(lock) {
        timers.remove(id)
        alarms.remove(id)
        undos.remove(id)
        Unit
    }

    /**
     * Notes outlive the call that made them, so they are re-read from disk when the app starts.
     * Ids are restored as they were (an undo in a previous process may still name one), and the id
     * counter is moved past them so a new note cannot collide with an old one.
     *
     * Each restored undo keeps the door the note came through. A note the mind wrote whose Notes
     * grant is **Off** now is re-registered for the owner only: the mind's door is shut, so the
     * undo table never offers the mind a note its grant does not cover (H4). The note itself is
     * untouched — the owner can still reverse it.
     */
    fun restoreNotes(saved: List<Note>, nowElapsed: Long) = synchronized(lock) {
        for (n in saved) {
            notes[n.id] = n
            val door = if (n.origin == Origin.MIND && !mindDoorOpen("note")) Origin.OWNER else n.origin
            register(n.id, "note", nowElapsed + NOTE_UNDO_MS, door) { platform.deleteNote(n.id) }
            val suffix = n.id.removePrefix("note-").toIntOrNull() ?: 0
            if (suffix > nextId) nextId = suffix
        }
    }

    // ---- tier 0 ----

    private fun setTimer(durationMs: Long, nowElapsed: Long, nowEpoch: Long, origin: Origin): PhoneResult {
        val id = "timer-${++nextId}"
        val atElapsed = nowElapsed + durationMs
        val atEpoch = nowEpoch + durationMs
        if (!platform.startTimer(id, durationMs, atEpoch)) {
            log("event=phone-mcp action=timer ok=false reason=unavailable")
            return PhoneResult.refused("timer", PhoneReason.UNAVAILABLE, "the phone refused to arm the countdown")
        }
        timers[id] = Armed(id, "timer", atElapsed, 0, durationMs, 0, 0, origin)
        register(id, "timer", atElapsed, origin) { platform.cancelTimer(id) }
        log("event=phone-mcp action=timer ok=true id=$id ms=$durationMs")
        return PhoneResult.done(
            "timer", id,
            linkedMapOf(
                "remaining_ms" to durationMs,
                "duration_ms" to durationMs,
                "undoable" to true,
            ),
        )
    }

    private fun setAlarm(hour: Int, minute: Int, nowEpoch: Long, origin: Origin): PhoneResult {
        val at = nextOccurrence(hour, minute, nowEpoch)
        val id = "alarm-${++nextId}"
        if (!platform.startAlarm(id, hour, minute, at)) {
            log("event=phone-mcp action=alarm ok=false reason=unavailable")
            return PhoneResult.refused("alarm", PhoneReason.UNAVAILABLE, "the phone refused to arm the alarm")
        }
        alarms[id] = Armed(id, "alarm", 0, at, 0, hour, minute, origin)
        // The undo has no elapsed clock to expire on: the registry drops it when the alarm is pruned.
        register(id, "alarm", Long.MAX_VALUE, origin) { platform.cancelTimer(id) }
        log("event=phone-mcp action=alarm ok=true id=$id at=${LocalActionSpeech.clock24(hour, minute)}")
        return PhoneResult.done(
            "alarm", id,
            linkedMapOf(
                "at" to LocalActionSpeech.clock24(hour, minute),
                "in_ms" to (at - nowEpoch).coerceAtLeast(0),
                "undoable" to true,
            ),
        )
    }

    private fun setDnd(on: Boolean, nowElapsed: Long, origin: Origin): PhoneResult {
        if (!platform.dndGranted()) {
            log("event=phone-mcp action=dnd ok=false reason=permission")
            return PhoneResult.refused(
                "dnd", PhoneReason.PERMISSION,
                "this app does not hold do-not-disturb access; only the owner can grant it in system settings",
            )
        }
        val previous = platform.currentDnd()
        if (!platform.setDnd(on)) {
            log("event=phone-mcp action=dnd ok=false reason=unavailable")
            return PhoneResult.refused("dnd", PhoneReason.UNAVAILABLE, "the phone refused the interruption filter change")
        }
        val id = "dnd-${++nextId}"
        // Report the state the phone is actually in, not the one we asked for: a device may clamp.
        val now = platform.currentDnd() ?: on
        dndApplied = now
        supersede("dnd")
        // Restore only what we actually read; with no previous state there is nothing honest to say.
        if (previous != null) register(id, "dnd", nowElapsed + CHANGE_UNDO_MS, origin) { platform.setDnd(previous) }
        log("event=phone-mcp action=dnd ok=true id=$id on=$now")
        return PhoneResult.done(
            "dnd", id,
            linkedMapOf<String, Any?>(
                "on" to now,
                "previous" to previous,
                "undoable" to (previous != null),
            ),
        )
    }

    private fun setVolume(level: Int?, direction: Int, nowElapsed: Long, origin: Origin): PhoneResult {
        val current = platform.currentVolume()
        val appliedLevel = if (level != null) {
            platform.setVolume(level)
        } else {
            platform.adjustVolume(direction)
        } ?: run {
            log("event=phone-mcp action=volume ok=false reason=unavailable")
            return PhoneResult.refused(
                "volume", PhoneReason.UNAVAILABLE,
                "the volume could not be changed; some devices refuse a stream change while do-not-disturb owns it",
            )
        }
        val applied = appliedLevel
        val id = "volume-${++nextId}"
        volumeApplied = applied
        supersede("volume")
        if (current != null && current != applied) {
            register(id, "volume", nowElapsed + CHANGE_UNDO_MS, origin) { platform.setVolume(current) != null }
        }
        log("event=phone-mcp action=volume ok=true id=$id level=$applied")
        return PhoneResult.done(
            "volume", id,
            linkedMapOf<String, Any?>(
                "level" to applied,
                "previous" to current,
                "undoable" to (current != null && current != applied),
            ),
        )
    }

    // ---- media ----

    /**
     * One transport key, dispatched to whatever session is active. `ok:true` means the platform
     * accepted the key event — **not** that a track started, stopped or changed, which the phone
     * cannot see without a listener it does not hold. No undo is registered on purpose: "play after
     * pause" is another key press into an unknown state, not a reversal.
     */
    private fun pressMedia(pause: Boolean): PhoneResult {
        val action = if (pause) "pause" else "play"
        val key = if (pause) PhonePlatform.MediaKey.PAUSE else PhonePlatform.MediaKey.PLAY
        if (!platform.mediaKey(key)) return mediaRefused(action)
        log("event=phone-mcp action=$action ok=true")
        return PhoneResult.info(
            action,
            linkedMapOf<String, Any?>(
                "dispatched" to true,
                "undoable" to false,
                "note" to mediaNote(action),
            ),
        )
    }

    private fun skip(previous: Boolean): PhoneResult {
        val key = if (previous) PhonePlatform.MediaKey.PREVIOUS else PhonePlatform.MediaKey.NEXT
        if (!platform.mediaKey(key)) return mediaRefused("skip")
        val direction = if (previous) "previous" else "next"
        log("event=phone-mcp action=skip ok=true direction=$direction")
        return PhoneResult.info(
            "skip",
            linkedMapOf<String, Any?>(
                "direction" to direction,
                "dispatched" to true,
                "undoable" to false,
                "note" to mediaNote("skip"),
            ),
        )
    }

    /**
     * What the phone will say about playback. The listener's grant separates the two honest
     * answers: without it the session is invisible and only `isMusicActive` is available; with it
     * the package and title are the platform's own, and an empty session list is said plainly —
     * never a guessed title.
     */
    private fun nowPlaying(): PhoneResult {
        val p = platform.playback()
        if (p.known) {
            log("event=phone-mcp action=now_playing ok=true known=true")
            return PhoneResult.info(
                "now_playing",
                linkedMapOf<String, Any?>(
                    "known" to true,
                    "playing" to p.playing,
                    "package" to p.packageName,
                    "title" to p.title,
                ),
            )
        }
        log("event=phone-mcp action=now_playing ok=true known=false listen=${p.listenerGranted}")
        return PhoneResult.info(
            "now_playing",
            linkedMapOf<String, Any?>(
                "known" to false,
                "playing" to p.playing,
                "package" to null,
                "title" to null,
                "note" to when {
                    // The access is held; no app is publishing a session this reading can name.
                    p.listenerGranted ->
                        if (p.playing == true) {
                            "audio is playing, but no app is publishing a media session the " +
                                "phone can name"
                        } else {
                            "the phone can see media sessions, and none is publishing one right now"
                        }
                    p.playing == true -> "audio is playing, but the phone cannot see which app it " +
                        "is or what it is: that needs notification-listener access, which this " +
                        "app does not hold"
                    p.playing == false -> "no audio is playing right now, and the phone cannot see " +
                        "what was last: that needs notification-listener access, which this app " +
                        "does not hold"
                    else -> "the phone cannot see what is playing: that needs notification-listener " +
                        "access, which this app does not hold"
                },
            ),
        )
    }

    private fun mediaRefused(action: String): PhoneResult {
        log("event=phone-mcp action=$action ok=false reason=unavailable")
        return PhoneResult.refused(
            action, PhoneReason.UNAVAILABLE,
            "the phone could not send the media key; there was no audio service to take it",
        )
    }

    private fun mediaNote(action: String): String =
        "sent the $action key to whichever media session is active; the phone cannot see what that " +
            "session did with it"

    // ---- device state ----

    /**
     * One read-only report. **Every field is present; an unanswered one is `null`**, so a caller
     * can tell "the phone says none" (`network:"none"`) from "the phone will not say" (`null`) and
     * never receives an invented value. The log line carries no values: battery, network and the
     * clock are the owner's business, and the result is where they belong.
     */
    private fun deviceState(nowEpoch: Long): PhoneResult {
        val d = platform.deviceState()
        log("event=phone-mcp action=state ok=true")
        return PhoneResult.info(
            "state",
            linkedMapOf<String, Any?>(
                "battery_percent" to d.batteryPercent,
                "charging_source" to d.chargingSource,
                "charging_status" to d.chargingStatus,
                "screen_on" to d.screenOn,
                "network" to d.network,
                "tailnet_present" to d.tailnetPresent,
                "free_storage_bytes" to d.freeStorageBytes,
                "media_volume" to d.mediaVolume,
                "dnd" to d.dnd,
                "timezone" to d.timeZoneId,
                "utc_offset_minutes" to d.utcOffsetMinutes,
                // Time needs the offset; without one there is no honest clock to report.
                "time" to d.utcOffsetMinutes?.let { localIso(nowEpoch, it) },
            ),
        )
    }

    // ---- clipboard ----

    /**
     * Put the text on the clipboard. The result carries **only the length**: the text is the
     * caller's, and neither the result, the log nor the audit row needs it. There is no undo —
     * reversing a clipboard write would mean having read what was there, which this action does
     * not do; [readClip] is a separate call with its own grant and its own result.
     *
     * The write is attempted, not predicted. Android restricts clipboard *reads*, not writes, so a
     * backgrounded or PiP call writes like any other; a platform refusal is reported with the
     * platform's own reason.
     */
    private fun clip(text: String): PhoneResult = when (val wrote = platform.clip(text)) {
        PhoneEngine.PhonePlatform.ClipWrite.Done -> {
            log("event=phone-mcp action=clip ok=true chars=${text.length}")
            PhoneResult.info(
                "clip",
                linkedMapOf<String, Any?>(
                    "chars" to text.length,
                    "undoable" to false,
                ),
            )
        }
        is PhoneEngine.PhonePlatform.ClipWrite.Refused -> {
            log("event=phone-mcp action=clip ok=false reason=unavailable")
            PhoneResult.refused("clip", PhoneReason.UNAVAILABLE, wrote.detail)
        }
    }

    /**
     * The clipboard's text, when the platform allows the read. The decision is Android's: an app
     * that is not focused gets no clipboard, and an empty clip is no answer either. Both are
     * `unavailable` with the platform's own outcome — never empty text dressed up as what the
     * clipboard holds, and never a pre-emptive refusal in place of asking.
     */
    private fun readClip(): PhoneResult = when (val found = platform.readClip()) {
        is PhonePlatform.ClipRead.Text -> {
            log("event=phone-mcp action=clip_read ok=true chars=${found.text.length}")
            PhoneResult.info(
                "clip_read",
                linkedMapOf<String, Any?>(
                    "text" to found.text,
                    "chars" to found.text.length,
                    "undoable" to false,
                ),
            )
        }
        PhonePlatform.ClipRead.Empty -> {
            log("event=phone-mcp action=clip_read ok=false reason=unavailable")
            PhoneResult.refused(
                "clip_read", PhoneReason.UNAVAILABLE,
                "the clipboard is empty, so there was nothing to read",
            )
        }
        is PhonePlatform.ClipRead.Unavailable -> {
            log("event=phone-mcp action=clip_read ok=false reason=unavailable")
            PhoneResult.refused("clip_read", PhoneReason.UNAVAILABLE, found.detail)
        }
    }

    // ---- tier 1 ----

    private fun saveNote(text: String, nowElapsed: Long, nowEpoch: Long, origin: Origin): PhoneResult {
        val id = "note-${++nextId}"
        if (!platform.saveNote(id, text, nowEpoch)) {
            log("event=phone-mcp action=note ok=false reason=unavailable")
            return PhoneResult.refused("note", PhoneReason.UNAVAILABLE, "the note could not be saved")
        }
        notes[id] = Note(id, text, nowEpoch, origin)
        register(id, "note", nowElapsed + NOTE_UNDO_MS, origin) { platform.deleteNote(id) }
        log("event=phone-mcp action=note ok=true id=$id chars=${text.length}")
        return PhoneResult.done(
            "note", id,
            linkedMapOf<String, Any?>(
                "text" to text,
                "reported_next_turn" to true,
                "undoable" to true,
            ),
        )
    }

    // ---- tier 1: files ----

    /**
     * The folder a call means: the one it named, or [PhoneFiles.DEFAULT_FOLDER] when it named
     * none. [PhoneFiles.normalizeFolder] makes `Downloads`/`Download` and case irrelevant, so the
     * folder the system picker named is found without asking the owner to spell it our way.
     *
     * There is no "pick the only folder that happens to be granted" fallback: the default is
     * Downloads, and a call that omits the folder means Downloads. Reading Documents because it
     * was the only grant would be a different file than the caller named.
     */
    private fun folderFor(requested: String?): PhonePlatform.GrantedFolder? {
        val granted = platform.folders()
        val wanted = PhoneFiles.normalizeFolder(requested)
        return granted.firstOrNull { PhoneFiles.normalizeFolder(it.label) == wanted }
    }

    /** Both file actions answer this when the folder they mean has not been granted. */
    private fun noFolder(action: String, requested: String?): PhoneResult {
        val granted = platform.folders()
        val detail = if (granted.isEmpty()) {
            PhoneFiles.NO_FOLDER
        } else {
            val names = granted.joinToString(", ") { it.label }
            "the ${requested ?: PhoneFiles.DEFAULT_FOLDER} folder is not granted; the folder granted " +
                "to your agent is $names. Name that folder, or the owner can grant another one in " +
                "Phone tools → Files"
        }
        log("event=phone-mcp action=$action ok=false reason=no_folder")
        return PhoneResult.refused(action, PhoneReason.NO_FOLDER, detail)
    }

    /**
     * One named file's text. Without `offset`/`length` the whole file is read, capped at
     * [PhoneFiles.MAX_READ_BYTES]; with them a **window** is read and `truncated` tells the truth
     * about the total, so a large file is readable in pieces rather than refused. A name that
     * resolves outside the granted tree is `bad_path`; bytes that are not text are `not_text`.
     * The result carries the text and its length, and no undo — a read changes nothing.
     */
    private fun readFile(call: PhoneTool.Call.FileRead): PhoneResult {
        val folder = folderFor(call.folder) ?: return noFolder("file_read", call.folder)
        val found = if (call.offset != null || call.length != null) {
            platform.fileWindow(
                folder,
                call.name,
                call.offset ?: 0L,
                call.length ?: PhoneFiles.MAX_READ_BYTES,
            )
        } else {
            platform.fileText(folder, call.name)
        }
        return when (found) {
            PhonePlatform.FileText.Missing -> {
                log("event=phone-mcp action=file_read ok=false reason=unavailable")
                PhoneResult.refused(
                    "file_read", PhoneReason.UNAVAILABLE,
                    "no file named \"${call.name}\" is in ${folder.label}; nothing was read",
                )
            }
            PhonePlatform.FileText.NotText -> {
                log("event=phone-mcp action=file_read ok=false reason=not_text")
                PhoneResult.refused(
                    "file_read", PhoneReason.NOT_TEXT,
                    "that file is not text (it is not UTF-8, or it contains binary bytes); " +
                        "file_read returns text only, and nothing was returned",
                )
            }
            is PhonePlatform.FileText.TooBig -> {
                log("event=phone-mcp action=file_read ok=false reason=too_big")
                PhoneResult.refused("file_read", PhoneReason.TOO_BIG, PhoneFiles.tooBig(found.bytes))
            }
            PhonePlatform.FileText.Outside -> {
                log("event=phone-mcp action=file_read ok=false reason=bad_path")
                PhoneResult.refused(
                    "file_read", PhoneReason.BAD_PATH,
                    "that name resolves outside the folder you granted; nothing was read",
                )
            }
            is PhonePlatform.FileText.Failed -> {
                log("event=phone-mcp action=file_read ok=false reason=unavailable")
                PhoneResult.refused("file_read", PhoneReason.UNAVAILABLE, found.detail)
            }
            is PhonePlatform.FileText.Found -> {
                log("event=phone-mcp action=file_read ok=true chars=${found.text.length}")
                PhoneResult.info(
                    "file_read",
                    linkedMapOf<String, Any?>(
                        "name" to found.name,
                        "folder" to folder.label,
                        "bytes" to found.bytes,
                        "chars" to found.text.length,
                        "text" to found.text,
                        "undoable" to false,
                    ),
                )
            }
            is PhonePlatform.FileText.Window -> {
                // `truncated` is the whole truth of a window: false only when this text is the
                // entire file, byte for byte.
                val whole = found.offset == 0L && found.offset + found.windowBytes >= found.totalBytes
                log(
                    "event=phone-mcp action=file_read ok=true window=true offset=${found.offset} " +
                        "length=${found.windowBytes} chars=${found.text.length}"
                )
                PhoneResult.info(
                    "file_read",
                    linkedMapOf<String, Any?>(
                        "name" to found.name,
                        "folder" to folder.label,
                        "bytes" to found.totalBytes,
                        "chars" to found.text.length,
                        "offset" to found.offset,
                        "length" to found.windowBytes,
                        "truncated" to !whole,
                        "text" to found.text,
                        "undoable" to false,
                    ),
                )
            }
        }
    }

    /**
     * What is inside one granted folder. The walk, the ordering, the pattern and the page are
     * [PhoneFiles.list]; this maps its outcome onto the tool's result shape and never invents a
     * count, a page or an entry the platform did not give.
     *
     * The log line and the audit row carry the folder, the relative path and the count — `Downloads,
     * 12 entries` — and **never a name**, because the names in a listing are the owner's.
     */
    private fun listFiles(call: PhoneTool.Call.FileList): PhoneResult {
        val folder = folderFor(call.folder) ?: return noFolder("file_list", call.folder)
        val listed = platform.fileList(
            folder, call.path, call.depth, call.pattern, call.offset, call.limit,
        )
        return when (listed) {
            is PhoneFiles.ListResult.Ok -> {
                val l = listed.listing
                log(
                    "event=phone-mcp action=file_list ok=true folder=${folder.label} " +
                        "path=${l.path.ifEmpty { "/" }} count=${l.count} has_more=${l.hasMore}"
                )
                PhoneResult.info(
                    "file_list",
                    linkedMapOf<String, Any?>(
                        "folder" to l.folder,
                        "path" to l.path,
                        "depth" to l.depth,
                        "count" to l.count,
                        "has_more" to l.hasMore,
                        "next_offset" to l.nextOffset,
                        "entries" to l.entries.map { e ->
                            linkedMapOf<String, Any?>(
                                "name" to e.name,
                                "path" to e.path,
                                "kind" to e.kind,
                                "bytes" to e.bytes,
                                "modified" to e.modified,
                                "mime" to e.mime,
                            )
                        },
                        "undoable" to false,
                    ),
                )
            }
            PhoneFiles.ListResult.MissingPath -> {
                log("event=phone-mcp action=file_list ok=false reason=unavailable")
                PhoneResult.refused(
                    "file_list", PhoneReason.UNAVAILABLE,
                    "there is no directory at \"${call.path}\" inside ${folder.label}; " +
                        "nothing was listed",
                )
            }
            PhoneFiles.ListResult.Outside -> {
                log("event=phone-mcp action=file_list ok=false reason=bad_path")
                PhoneResult.refused(
                    "file_list", PhoneReason.BAD_PATH,
                    "that path, or an entry below it, resolves outside the folder you granted; " +
                        "nothing was listed",
                )
            }
            is PhoneFiles.ListResult.Failed -> {
                log("event=phone-mcp action=file_list ok=false reason=unavailable")
                PhoneResult.refused("file_list", PhoneReason.UNAVAILABLE, listed.detail)
            }
        }
    }

    /**
     * Hand one file to the existing file bridge. The result is the capability the bridge minted —
     * URL, size, hash, expiry — so the agent fetches an artifact instead of receiving bytes in a
     * tool result. Nothing about the file's contents is returned here, and there is no undo.
     */
    private fun offerFile(call: PhoneTool.Call.FileOffer): PhoneResult {
        val folder = folderFor(call.folder) ?: return noFolder("file_offer", call.folder)
        return when (val handoff = platform.fileOffer(folder, call.name)) {
            PhonePlatform.FileHandoff.Missing -> {
                log("event=phone-mcp action=file_offer ok=false reason=unavailable")
                PhoneResult.refused(
                    "file_offer", PhoneReason.UNAVAILABLE,
                    "no file named \"${call.name}\" is in ${folder.label}; nothing was offered",
                )
            }
            PhonePlatform.FileHandoff.Outside -> {
                log("event=phone-mcp action=file_offer ok=false reason=bad_path")
                PhoneResult.refused(
                    "file_offer", PhoneReason.BAD_PATH,
                    "that name resolves outside the folder you granted; nothing was offered",
                )
            }
            is PhonePlatform.FileHandoff.TooBig -> {
                log("event=phone-mcp action=file_offer ok=false reason=too_big")
                PhoneResult.refused(
                    "file_offer", PhoneReason.TOO_BIG,
                    "that file is above the 100 MB the file bridge will serve; nothing was offered",
                )
            }
            is PhonePlatform.FileHandoff.Unavailable -> {
                log("event=phone-mcp action=file_offer ok=false reason=unavailable")
                PhoneResult.refused("file_offer", PhoneReason.UNAVAILABLE, handoff.detail)
            }
            is PhonePlatform.FileHandoff.Ready -> {
                log("event=phone-mcp action=file_offer ok=true bytes=${handoff.bytes}")
                PhoneResult.info(
                    "file_offer",
                    linkedMapOf<String, Any?>(
                        "name" to handoff.name,
                        "folder" to folder.label,
                        "mime" to handoff.mime,
                        "bytes" to handoff.bytes,
                        "sha256" to handoff.sha256,
                        "url" to handoff.url,
                        "expires_at" to handoff.expiresAtSec,
                        "undoable" to false,
                    ),
                )
            }
        }
    }

    // ---- tier 1: calendar ----

    /**
     * The runtime permission this phone lacks for [action], as a refusal that names it exactly.
     * Null when the action's halves are held. `agenda` asks only for READ_CALENDAR; `event_create`
     * asks for both, because it must find a writable calendar before it inserts. No
     * provider-specific path exists: this is `CalendarContract`, the platform's own provider, and
     * the permission strings are Android's.
     */
    private fun calendarRefusal(action: String, write: Boolean): PhoneResult? {
        val missing = (
            if (write) platform.calendarWritePermission() else platform.calendarReadPermission()
            ) ?: return null
        log("event=phone-mcp action=$action ok=false reason=permission")
        val what = if (write) "write the calendar" else "read the calendar"
        return PhoneResult.refused(
            action, PhoneReason.PERMISSION,
            "the phone does not hold $missing, so it cannot $what; only the owner can grant it in " +
                "system settings (Phone tools → Calendar shows whether it is granted)",
        )
    }

    /**
     * Today's events, with what the calendar holds: start, end, title, the calendar's name, and —
     * when the provider offers them — the description, the location and the attendee names. Names
     * only: an attendee's address is not read. A field the provider will not answer is null.
     */
    private fun agenda(): PhoneResult {
        calendarRefusal("agenda", write = false)?.let { return it }
        val events = platform.agenda() ?: run {
            log("event=phone-mcp action=agenda ok=false reason=unavailable")
            return PhoneResult.refused(
                "agenda", PhoneReason.UNAVAILABLE,
                "the phone would not answer for today's calendar; nothing was read",
            )
        }
        val rows = events.map { e ->
            linkedMapOf<String, Any?>(
                "start" to e.start,
                "end" to e.end,
                "title" to e.title,
                "calendar" to e.calendar,
                "description" to e.description,
                "location" to e.location,
                "attendees" to e.attendees,
            )
        }
        log("event=phone-mcp action=agenda ok=true events=${rows.size}")
        return PhoneResult.info(
            "agenda",
            linkedMapOf<String, Any?>(
                "count" to rows.size,
                "events" to rows,
                "undoable" to false,
            ),
        )
    }

    /**
     * One event, created and reported with the id its undo needs. The undo deletes it and lives
     * until the event starts — or ten minutes, whichever is longer, so an event created for the
     * next minute is still reversible on the next turn.
     */
    private fun createEvent(
        call: PhoneTool.Call.EventCreate,
        nowElapsed: Long,
        nowEpoch: Long,
        origin: Origin,
    ): PhoneResult {
        calendarRefusal("event_create", write = true)?.let { return it }
        val created = platform.createEvent(
            call.title, call.start, call.durationMin, call.calendar,
        ) ?: run {
            log("event=phone-mcp action=event_create ok=false reason=unavailable")
            return PhoneResult.refused(
                "event_create", PhoneReason.UNAVAILABLE,
                "the phone did not create the event: there is no writable calendar for it, or " +
                    "Android refused the insert; nothing was created",
            )
        }
        val id = created.id
        val lifetime = (created.startMs - nowEpoch).coerceAtLeast(CHANGE_UNDO_MS)
        register(id, "event", nowElapsed + lifetime, origin) { platform.deleteEvent(id) }
        log("event=phone-mcp action=event_create ok=true id=$id minutes=${call.durationMin}")
        return PhoneResult.done(
            "event_create", id,
            linkedMapOf<String, Any?>(
                "title" to call.title,
                "start" to created.start,
                "end" to created.end,
                "duration_min" to call.durationMin,
                "start_ms" to created.startMs,
                "end_ms" to created.endMs,
                "calendar" to created.calendar,
                "undoable" to true,
            ),
        )
    }

    // ---- notifications (#429 read lane) ----

    /**
     * A summary of the notifications on the phone **while a call is live**: a total and one row
     * per app with `count`, `latest_at` and opaque `ids`. No title and no text — the question
     * "what's going on?" gets counts, and content is a separate, deliberate step.
     *
     * `sensitive:true` is on the result because what this describes is private even when the
     * summary itself is not. The tool description carries the rule about reading it aloud.
     */
    private fun notifications(): PhoneResult {
        if (!PhoneNotifications.attached()) return listenerClosed("notifications")
        listenerPermission("notifications")?.let { return it }
        val summary = PhoneNotifications.summary() ?: return listenerClosed("notifications")
        val apps = summary.apps.map { row ->
            linkedMapOf<String, Any?>(
                "app" to row.app,
                "count" to row.count,
                "latest_at" to row.latestAt,
                "ids" to row.ids,
            )
        }
        log("event=phone-mcp action=notifications ok=true count=${summary.count}")
        return PhoneResult.info(
            "notifications",
            linkedMapOf<String, Any?>(
                "sensitive" to true,
                "count" to summary.count,
                "apps" to apps,
            ),
        )
    }

    /**
     * One notification's content, by an id a summary of this call has shown. A guessed id, or a
     * notification that has left the phone since the summary, is `unknown-id`; no live call is
     * `unavailable`. The content is returned, not logged and not stored.
     */
    private fun notificationRead(id: String): PhoneResult {
        if (!PhoneNotifications.attached()) return listenerClosed("notification_read")
        listenerPermission("notification_read")?.let { return it }
        val record = PhoneNotifications.read(id) ?: run {
            log("event=phone-mcp action=notification_read ok=false reason=unknown-id")
            return PhoneResult.refused(
                "notification_read",
                PhoneReason.UNKNOWN_ID,
                "no notification with that id is in a summary from this call; the id must come " +
                    "from a `notifications` summary while the call is open",
            )
        }
        log("event=phone-mcp action=notification_read ok=true")
        return PhoneResult.info(
            "notification_read",
            linkedMapOf<String, Any?>(
                "sensitive" to true,
                "app" to record.app,
                "posted_at" to record.postedAt,
                "title" to record.title,
                "text" to record.text,
            ),
        )
    }

    /**
     * Both notification reads say the same thing when no call is live: the system grant may be
     * held, but this app's use of it is not. It names the listener's lifetime rather than a
     * generic failure.
     */
    /** The system grant is missing (the group is on, but Android has not been asked or said no). */
    private fun listenerPermission(action: String): PhoneResult? {
        if (platform.notificationListenerGranted()) return null
        log("event=phone-mcp action=$action ok=false reason=permission")
        return PhoneResult.refused(
            action,
            PhoneReason.PERMISSION,
            "this app does not hold notification-listener access; only the owner can grant it in " +
                "Phone tools, on Android's notification-access screen",
        )
    }

    private fun listenerClosed(action: String): PhoneResult {
        log("event=phone-mcp action=$action ok=false reason=unavailable live=false")
        return PhoneResult.refused(
            action,
            PhoneReason.UNAVAILABLE,
            "the notification listener is only open during a call; no call is live, so nothing " +
                "was read",
        )
    }

    // ---- contacts (#429 read lane) ----

    /**
     * Look a name up in the owner's address book and return **numbers only**. The result has a
     * display name and each number with its label; there is no note, address, photo or email in
     * the projection, because the group's stated purpose is "look up a contact's number by name"
     * and a wider read would be a different capability with different consent.
     *
     * A missing `READ_CONTACTS` is `permission` naming Android's exact string. No match is not an
     * error: `ok:true, count:0` is the true answer to "there is nobody by that name".
     */
    private fun contacts(call: PhoneTool.Call.Contact): PhoneResult {
        platform.contactsPermission()?.let { missing ->
            log("event=phone-mcp action=contact ok=false reason=permission")
            return PhoneResult.refused(
                "contact",
                PhoneReason.PERMISSION,
                "this app does not hold $missing, so the address book cannot be read; only the " +
                    "owner can grant it in Phone tools",
            )
        }
        val found = platform.contactMatches(call.name, PhoneTool.MAX_CONTACT_MATCHES)
            ?: run {
                log("event=phone-mcp action=contact ok=false reason=unavailable")
                return PhoneResult.refused(
                    "contact",
                    PhoneReason.UNAVAILABLE,
                    "the phone's address book did not answer; nothing was read",
                )
            }
        val matches = found.take(PhoneTool.MAX_CONTACT_MATCHES).map { person ->
            linkedMapOf<String, Any?>(
                "display_name" to person.displayName,
                "numbers" to person.numbers.map { n ->
                    linkedMapOf<String, Any?>("label" to n.label, "number" to n.number)
                },
            )
        }
        log("event=phone-mcp action=contact ok=true count=${matches.size}")
        return PhoneResult.info(
            "contact",
            linkedMapOf<String, Any?>(
                "count" to matches.size,
                "matches" to matches,
            ),
        )
    }

    // ---- handoffs: messages, calls, apps ----

    /**
     * Compose the text and open the owner's messaging app. **Nothing is sent here.** The result
     * says `handed_off:true` and carries the recipient and the text's length; the owner's tap
     * in their own app is what sends it. `ACTION_SENDTO` with an `smsto:` URI is the only path,
     * so no `SEND_SMS`, no `SmsManager` and no default-SMS role is involved.
     */
    private fun message(call: PhoneTool.Call.Message): PhoneResult {
        val to = when (val r = resolveRecipient("message", call.to, call.contact)) {
            is Recipient.Number -> r.number
            is Recipient.Refused -> return r.result
        }
        return when (val handed = platform.sendMessage(to, call.text)) {
            PhonePlatform.Handoff.NoApp -> noApp(
                "message",
                "no app on this phone answers ACTION_SENDTO for a text; the draft was not opened",
            )
            is PhonePlatform.Handoff.Failed -> {
                log("event=phone-mcp action=message ok=false reason=unavailable")
                PhoneResult.refused("message", PhoneReason.UNAVAILABLE, handed.detail)
            }
            is PhonePlatform.Handoff.Done -> {
                log("event=phone-mcp action=message ok=true chars=${call.text.length}")
                PhoneResult.info(
                    "message",
                    linkedMapOf<String, Any?>(
                        "to" to to,
                        "chars" to call.text.length,
                        "handed_off" to true,
                    ),
                )
            }
        }
    }

    /**
     * Open the dialer with the number ready. `ACTION_DIAL` only: the owner presses call, so
     * there is no `CALL_PHONE`, no emergency-number case, and no call this tool can place.
     *
     * With a live Vox call, [DialHandoff] announces one line through the being, ends the call
     * through the same teardown the hang-up runs, and then opens the dialer. Ending the call
     * does not end the conversation: the thread lives on the gateway.
     */
    private fun dial(call: PhoneTool.Call.Dial): PhoneResult {
        val to = when (val r = resolveRecipient("dial", call.to, call.contact)) {
            is Recipient.Number -> r.number
            is Recipient.Refused -> return r.result
        }
        return when (DialHandoff.run(platform, to)) {
            DialHandoff.Outcome.NO_DIALER -> noApp(
                "dial",
                "no app on this phone answers ACTION_DIAL; nothing was opened and no call was ended",
            )
            DialHandoff.Outcome.DIAL_FAILED -> noApp(
                "dial",
                "the dialer could not be opened; nothing was dialled and no call was placed",
            )
            DialHandoff.Outcome.DIALED -> {
                log("event=phone-mcp action=dial ok=true")
                PhoneResult.info("dial", linkedMapOf<String, Any?>("to" to to))
            }
            DialHandoff.Outcome.ANNOUNCED_END_THEN_DIAL -> {
                log("event=phone-mcp action=dial ok=true ended_call=true")
                PhoneResult.info("dial", linkedMapOf<String, Any?>("to" to to))
            }
        }
    }

    /** Open one launchable app, entering PiP first when a call is live ([CallWindow]). */
    private fun launch(call: PhoneTool.Call.Launch): PhoneResult {
        val target = platform.resolveLaunchable(call.app) ?: return noApp(
            "launch",
            "no launchable app matches \"${call.app}\"; only an activity with a launcher entry " +
                "can be started, and nothing was opened",
        )
        return handOff("launch", target) { platform.startApp(target) }
    }

    /** Open one URI, optionally in a named package, through the same PiP-first window. */
    private fun open(call: PhoneTool.Call.Open): PhoneResult {
        val target = platform.resolveUri(call.uri, call.packageName) ?: return noApp(
            "open",
            "no app on this phone opens that URI" +
                (call.packageName?.let { " in $it" } ?: "") + "; nothing was opened",
        )
        return handOff("open", target) { platform.startUri(target, call.uri) }
    }

    // ---- apps: the one card-gated action ----

    /**
     * `uninstall`, in two steps on the same tool.
     *
     * Without [PhoneTool.Call.Uninstall.approvalId] this is the **request**: the platform's label
     * is read, the card is raised, and the refusal carries the id and expiry the agent needs for
     * the second call. Nothing is removed. With an id this is the **execution** check: the desk
     * decides whether it is the same action, the same canonical package, unexpired and unburnt.
     * Only then does Android's own confirmation open — after our card, not instead of it.
     */
    private fun uninstall(call: PhoneTool.Call.Uninstall, nowEpoch: Long): PhoneResult {
        val canonical = PhoneApproval.canonical(PhoneApproval.ACTION, approvalArgs(call.packageName))
        val digest = PhoneApproval.digest(canonical)

        if (call.approvalId == null) {
            val label = platform.appLabel(call.packageName) ?: run {
                log("event=phone-mcp action=uninstall ok=false reason=unavailable")
                return PhoneResult.refused(
                    "uninstall",
                    PhoneReason.UNAVAILABLE,
                    "no installed app has the package \"${call.packageName}\"; nothing was removed",
                )
            }
            return when (
                val request = approvals.request(call.packageName, label, canonical, digest, nowEpoch)
            ) {
                is PhoneApproval.Request.Raised -> {
                    val card = request.card
                    log("event=phone-mcp action=uninstall ok=false reason=approval-required id=${card.id}")
                    PhoneResult.refused(
                        "uninstall",
                        PhoneReason.APPROVAL,
                        "the owner must approve removing ${card.label}; the card is up for " +
                            "${PhoneApproval.TIMEOUT_MS / 1000} seconds. Call uninstall again with " +
                            "the same package and approval=\"${card.id}\" after they answer.",
                        tier = PhoneTiers.APPROVAL,
                        facts = linkedMapOf<String, Any?>(
                            "approval_id" to card.id,
                            "expires_at" to card.expiresAtMs,
                            "summary" to card.summary,
                        ),
                    )
                }
                PhoneApproval.Request.Busy -> {
                    log("event=phone-mcp action=uninstall ok=false reason=approval_busy")
                    PhoneResult.refused(
                        "uninstall",
                        PhoneApproval.Reason.BUSY,
                        "three approval cards are already waiting on the phone; nothing was raised " +
                            "and nothing was removed",
                        tier = PhoneTiers.APPROVAL,
                    )
                }
                is PhoneApproval.Request.Unavailable -> {
                    log("event=phone-mcp action=uninstall ok=false reason=unavailable")
                    PhoneResult.refused(
                        "uninstall",
                        PhoneReason.UNAVAILABLE,
                        request.detail,
                        tier = PhoneTiers.APPROVAL,
                    )
                }
            }
        }

        return when (
            val grant = approvals.authorize(
                call.approvalId,
                PhoneApproval.ACTION,
                approvalArgs(call.packageName),
                nowEpoch,
            )
        ) {
            is PhoneApproval.Grant.Pending -> {
                val card = grant.card
                PhoneResult.refused(
                    "uninstall",
                    PhoneReason.APPROVAL,
                    "the owner has not answered the card for ${card.label} yet; nothing was removed",
                    tier = PhoneTiers.APPROVAL,
                    facts = linkedMapOf<String, Any?>(
                        "approval_id" to card.id,
                        "expires_at" to card.expiresAtMs,
                        "summary" to card.summary,
                    ),
                )
            }
            is PhoneApproval.Grant.Refused -> {
                log("event=phone-mcp action=uninstall ok=false reason=${grant.reason}")
                PhoneResult.refused(
                    "uninstall",
                    grant.reason,
                    grant.detail,
                    tier = PhoneTiers.APPROVAL,
                )
            }
            is PhoneApproval.Grant.Allowed -> executeUninstall(grant.card)
        }
    }

    /** The canonical argument set the card is bound to: the action and the package, nothing else. */
    private fun approvalArgs(packageName: String): Map<String, Any?> = linkedMapOf(
        "action" to PhoneApproval.ACTION,
        "package" to packageName,
    )

    /**
     * The card said yes. Android's own confirmation still runs: it asks "may this app be removed?",
     * which is a different question from our card's "did you mean this app?". Opening it is the
     * limit of what this phone can honestly claim — it cannot see the owner's answer there.
     */
    private fun executeUninstall(card: PhoneApproval.Card): PhoneResult =
        when (val outcome = platform.uninstall(card.packageName)) {
            is PhonePlatform.Uninstall.Confirming -> {
                log("event=phone-mcp action=uninstall ok=true package=${card.packageName} confirming=true")
                PhoneResult.done(
                    "uninstall",
                    null,
                    linkedMapOf<String, Any?>(
                        "package" to card.packageName,
                        "label" to card.label,
                        "platform_confirmation" to true,
                        "undoable" to false,
                        "note" to
                            "Android is asking you to confirm removing ${card.label}; the phone " +
                            "cannot see whether you do",
                    ),
                )
            }
            PhonePlatform.Uninstall.Missing -> {
                log("event=phone-mcp action=uninstall ok=false reason=unavailable")
                PhoneResult.refused(
                    "uninstall",
                    PhoneReason.UNAVAILABLE,
                    "${card.label} is not installed any more; nothing was removed",
                    tier = PhoneTiers.APPROVAL,
                )
            }
            is PhonePlatform.Uninstall.Unavailable -> {
                log("event=phone-mcp action=uninstall ok=false reason=unavailable")
                PhoneResult.refused(
                    "uninstall",
                    PhoneReason.UNAVAILABLE,
                    outcome.detail,
                    tier = PhoneTiers.APPROVAL,
                )
            }
        }

    /** Expire approval cards. The surface's timer calls this; the engine also ticks on each call. */
    fun tickApprovals(nowEpoch: Long) = approvals.tick(nowEpoch)

    /** The card on screen right now, for the Android surface and the voice path. */
    fun currentApproval(): PhoneApproval.Card? = approvals.current()

    /** Answer the current card by tap or voice. True when the id was still waiting. */
    fun answerApproval(id: String, choice: String, via: String, nowEpoch: Long): Boolean =
        approvals.decide(id, choice, via, nowEpoch) != null

    /** One spoken utterance against the current card, using [PhoneApproval]'s allowlist. */
    fun approvalVoice(text: String, nowEpoch: Long): PhoneApproval.Voice =
        approvals.voice(text, nowEpoch)

    /**
     * Start an app through [CallWindow]: PiP first when a call is live, otherwise at once.
     * **A launch is never failed because PiP was unavailable** — the fallback starts it anyway.
     * When the start is immediate its answer is reported; when it is posted behind the PiP
     * transition the handoff has been arranged, and the window's own failure is logged rather
     * than turned into a claim that the app is on screen.
     */
    private fun handOff(
        action: String,
        target: PhonePlatform.AppTarget,
        start: () -> Boolean,
    ): PhoneResult {
        val host = platform.window()
        var started = false
        val begin = { started = start() }
        val order = if (host == null) {
            begin()
            CallWindow.Order.LAUNCH_NOW
        } else {
            CallWindow.launch(host, begin)
        }
        if (order == CallWindow.Order.LAUNCH_NOW && !started) {
            log("event=phone-mcp action=$action ok=false reason=unavailable")
            return PhoneResult.refused(
                action,
                PhoneReason.UNAVAILABLE,
                "Android refused to start ${target.packageName}; nothing was opened",
            )
        }
        log(
            "event=phone-mcp action=$action ok=true package=${target.packageName} " +
                "pip=${order == CallWindow.Order.PIP_THEN_LAUNCH}"
        )
        return PhoneResult.info(
            action,
            linkedMapOf<String, Any?>(
                "package" to target.packageName,
                "component" to target.component,
            ),
        )
    }

    /** A recipient: a number ready to hand off, or the described refusal to return instead. */
    private sealed class Recipient {
        data class Number(val number: String) : Recipient()
        data class Refused(val result: PhoneResult) : Recipient()
    }

    /**
     * A number as given, or a name resolved through [PhonePlatform.contactMatches]. The Contacts
     * grant is enforced before this runs ([PhoneTool.parse]): only Allow reaches here with a name,
     * because Ask and Off are both refused with a governance reason. What remains here is the
     * platform's answer: a missing `READ_CONTACTS`, no such person, or a provider failure.
     */
    private fun resolveRecipient(action: String, raw: String, contact: Boolean): Recipient {
        if (!contact) return Recipient.Number(raw)
        platform.contactsPermission()?.let { missing ->
            log("event=phone-mcp action=$action ok=false reason=permission")
            return Recipient.Refused(
                PhoneResult.refused(
                    action,
                    PhoneReason.PERMISSION,
                    "looking up \"$raw\" reads your address book, and this app does not hold " +
                        "$missing; only the owner can grant it in Phone tools",
                )
            )
        }
        val number = platform.contactNumber(raw)
        if (number.isNullOrBlank()) {
            log("event=phone-mcp action=$action ok=false reason=no_match")
            return Recipient.Refused(
                PhoneResult.refused(
                    action,
                    PhoneReason.UNAVAILABLE,
                    "no contact named \"$raw\" has a number in this phone's address book; give " +
                        "the number instead. Nothing was opened.",
                )
            )
        }
        return Recipient.Number(number)
    }

    /** A handoff with nothing to receive it: no SENDTO app, no dialer, no launchable activity. */
    private fun noApp(action: String, detail: String): PhoneResult {
        log("event=phone-mcp action=$action ok=false reason=no_app")
        return PhoneResult.refused(action, PhoneReason.NO_APP, detail)
    }

    // ---- undo ----

    /**
     * Reverse one registered action.
     *
     * The owner's door ([Origin.OWNER], the sheet's Undo tap) may reverse anything still
     * registered: those are the owner's own actions, and the phone's own behaviour is unchanged.
     * The mind's door ([Origin.MIND]) may reverse only a **mind** entry whose group is not Off —
     * an owner's 6 am alarm is not the agent's to cancel, and a grant that has since been turned
     * Off is not a grant. A refusal leaves the entry in place, so the owner can still reverse it.
     */
    fun undo(
        id: String,
        nowElapsed: Long,
        nowEpoch: Long,
        origin: Origin = Origin.OWNER,
    ): PhoneResult = synchronized(lock) {
        prune(nowElapsed, nowEpoch)
        val entry = undos[id]
            ?: return PhoneResult.refused(
                "undo", PhoneReason.EXPIRED,
                "no undo is registered for that id any more; it fired, was superseded, or was never this phone's",
            )
        if (origin == Origin.MIND) {
            if (entry.origin != Origin.MIND) {
                log("event=phone-mcp action=undo ok=false id=$id kind=${entry.kind} reason=owner-entry")
                return PhoneResult.refused(
                    "undo", PhoneReason.NOT_GRANTED,
                    "that ${entry.kind} was set on the phone by the owner, not by the agent; only " +
                        "the owner can reverse it. Nothing was undone.",
                )
            }
            val action = actionForKind(entry.kind)
            if (action == null || !mindDoorOpen(action)) {
                val group = action?.let { PhoneGrants.groupOf(it)?.label }
                log("event=phone-mcp action=undo ok=false id=$id kind=${entry.kind} reason=not_granted")
                return PhoneResult.refused(
                    "undo", PhoneReason.NOT_GRANTED,
                    if (group == null) {
                        "that ${entry.kind} is outside every capability group, so the agent may " +
                            "not reverse it; nothing was undone"
                    } else {
                        "the \"$group\" group is off on this phone, so the agent may not reverse " +
                            "that ${entry.kind}; nothing was undone. Only the owner can turn it " +
                            "on in Phone tools."
                    },
                )
            }
        }
        undos.remove(id)
        if (!entry.perform()) {
            log("event=phone-mcp action=undo ok=false id=${entry.id} kind=${entry.kind} reason=unavailable")
            return PhoneResult.refused("undo", PhoneReason.UNAVAILABLE, "the phone could not reverse that ${entry.kind}")
        }
        timers.remove(id)
        alarms.remove(id)
        notes.remove(id)
        if (entry.kind == "dnd") dndApplied = null
        if (entry.kind == "volume") volumeApplied = null
        log("event=phone-mcp action=undo ok=true id=$id kind=${entry.kind}")
        PhoneResult.done(
            "undo", id,
            linkedMapOf<String, Any?>(
                "undone" to entry.kind,
                "state" to if (
                    entry.kind == "timer" || entry.kind == "alarm" || entry.kind == "event"
                ) "cancelled" else "restored",
            ),
        )
    }

    // ---- discovery ----

    /**
     * What the phone is doing, filtered to what the mind may see.
     *
     * `running`, `notes` and the registry carry only **mind** entries whose group is not Off: an
     * owner's voice-set timer, alarm or note is the owner's, and a group that is Off is not a
     * grant to read it either (H4). The live DND and volume readings belong to the `dnd-volume`
     * group, so they are omitted when it is Off as well. The owner's own screens do not read this
     * view; nothing here changes what the phone does for the owner.
     */
    fun status(nowElapsed: Long, nowEpoch: Long): PhoneResult = synchronized(lock) {
        prune(nowElapsed, nowEpoch)
        val offered = PhoneTool.offeredActions(grants)
        val running = ArrayList<Map<String, Any?>>(timers.size + alarms.size)
        for (a in timers.values) {
            if (!mindSees("timer", a.origin)) continue
            running.add(
                linkedMapOf(
                    "id" to a.id,
                    "kind" to "timer",
                    "remaining_ms" to (a.firesAtElapsed - nowElapsed).coerceAtLeast(0),
                )
            )
        }
        for (a in alarms.values) {
            if (!mindSees("alarm", a.origin)) continue
            running.add(
                linkedMapOf(
                    "id" to a.id,
                    "kind" to "alarm",
                    "at" to LocalActionSpeech.clock24(a.hour, a.minute),
                    "in_ms" to (a.firesAtEpoch - nowEpoch).coerceAtLeast(0),
                )
            )
        }
        val saved = ArrayList<Map<String, Any?>>(notes.size)
        for (n in notes.values) {
            if (!mindSees("note", n.origin)) continue
            saved.add(linkedMapOf("id" to n.id, "text" to n.text))
        }
        val facts = linkedMapOf<String, Any?>(
            "offered" to offered,
            "tiers" to linkedMapOf(
                "0" to offered.filter { PhoneTiers.of(it) == PhoneTiers.INSTANT },
                "1" to offered.filter { PhoneTiers.of(it) == PhoneTiers.REPORTED },
                // Named from the one tier table, so a name cannot linger here after it moved.
                "2" to PhoneTiers.names(PhoneTiers.APPROVAL),
            ),
            "grants" to PhoneGrants.summary(grants),
            "running" to running,
            "notes" to saved,
            "registry" to registry(nowElapsed, nowEpoch),
        )
        if (mindDoorOpen("dnd")) {
            facts["dnd"] = linkedMapOf("on" to dndApplied, "granted" to platform.dndGranted())
            facts["volume"] = volumeApplied ?: platform.currentVolume()
        }
        PhoneResult.info("status", facts)
    }

    /**
     * The compact registry the presence context reports — the same strings, from the same state, that
     * `status` explains in full. Expired entries are dropped here, so the registry cannot report a
     * timer that already rang. It is the **mind's** view: owner-initiated entries are not in it
     * (H4), because this line is read into the agent's context.
     */
    fun registry(nowElapsed: Long, nowEpoch: Long): List<String> = synchronized(lock) {
        prune(nowElapsed, nowEpoch)
        val out = ArrayList<String>()
        if (mindDoorOpen("dnd")) {
            dndApplied?.let { out.add("dnd=${if (it) "on" else "off"}") }
            volumeApplied?.let { out.add("volume=$it%") }
        }
        for (a in timers.values) {
            if (!mindSees("timer", a.origin)) continue
            out.add("timer(remaining=${LocalActionSpeech.remaining(a.firesAtElapsed - nowElapsed)})")
        }
        for (a in alarms.values) {
            if (!mindSees("alarm", a.origin)) continue
            out.add("alarm(at=${LocalActionSpeech.clock24(a.hour, a.minute)})")
        }
        for (n in notes.values) {
            if (!mindSees("note", n.origin)) continue
            out.add("note(id=${n.id})")
        }
        out.takeLast(PresenceContext.MAX_ACTIONS)
    }

    /** The notes this phone is holding, newest last — for a caller that wants the text, not the id. */
    fun noteList(): List<Note> = synchronized(lock) { notes.values.toList() }

    // ---- internals ----

    /** The action whose group governs an entry of [kind], or null when no group does. */
    private fun actionForKind(kind: String): String? = when (kind) {
        "timer", "alarm", "dnd", "volume", "note" -> kind
        // The registry's kind is the noun; the action that made it is `event_create`.
        "event" -> "event_create"
        else -> null
    }

    /** True when the mind's door is open for [action]'s group: the group exists and is not Off. */
    private fun mindDoorOpen(action: String): Boolean =
        PhoneGrants.stateOf(grants, action)?.let { it != PhoneGrants.State.OFF } == true

    /**
     * True when an entry of [kind] that was made by [origin] may be shown to — and reversed by —
     * the mind. Ask is enough (it is not Off, and a Tier 1 group at Ask is on), but the entry must
     * be the mind's own: an owner's action is never cleared by a group being on.
     */
    private fun mindSees(kind: String, origin: Origin): Boolean {
        if (origin != Origin.MIND) return false
        val action = actionForKind(kind) ?: return false
        return mindDoorOpen(action)
    }

    private fun register(
        id: String,
        kind: String,
        expiresAtElapsed: Long,
        origin: Origin,
        perform: () -> Boolean,
    ) {
        undos[id] = Undo(id, kind, expiresAtElapsed, origin, perform)
    }

    /** A second change of the same setting makes the older change's "previous" meaningless. */
    private fun supersede(kind: String) {
        undos.entries.removeAll { it.value.kind == kind }
    }

    private fun prune(nowElapsed: Long, nowEpoch: Long) {
        val firedTimers = timers.values.filter { nowElapsed >= it.firesAtElapsed }.map { it.id }
        val firedAlarms = alarms.values.filter { nowEpoch >= it.firesAtEpoch }.map { it.id }
        for (id in firedTimers + firedAlarms) undos.remove(id)
        if (firedTimers.isNotEmpty()) timers.keys.removeAll(firedTimers.toSet())
        if (firedAlarms.isNotEmpty()) alarms.keys.removeAll(firedAlarms.toSet())
        undos.entries.removeAll { nowElapsed >= it.value.expiresAtElapsed }
    }

    companion object {
        /** How long a dnd/volume undo stays meaningful. Long enough for the next turn to correct it. */
        const val CHANGE_UNDO_MS = 10 * 60_000L

        /** A note's undo outlives the turn that made it, because the note itself does. */
        const val NOTE_UNDO_MS = 24 * 60 * 60_000L

        /**
         * [epochMs] in a fixed [offsetMinutes] east of UTC, as local `YYYY-MM-DDTHH:MM:SS`.
         * `SimpleTimeZone` rather than `java.time`: the app's minSdk is 24, and a fixed offset needs
         * no zone database. Pure, so the clock shape is pinned without a phone.
         */
        fun localIso(epochMs: Long, offsetMinutes: Int): String {
            val zone = java.util.SimpleTimeZone(offsetMinutes * 60_000, "local")
            val cal = java.util.Calendar.getInstance(zone).apply { timeInMillis = epochMs }
            return String.format(
                java.util.Locale.US,
                "%04d-%02d-%02dT%02d:%02d:%02d",
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH),
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE),
                cal.get(java.util.Calendar.SECOND),
            )
        }

        /** The next occurrence of [hour]:[minute] strictly after [nowEpoch] (1 s of slack). */
        fun nextOccurrence(hour: Int, minute: Int, nowEpoch: Long): Long {            val cal = java.util.Calendar.getInstance().apply {
                timeInMillis = nowEpoch
                set(java.util.Calendar.HOUR_OF_DAY, hour)
                set(java.util.Calendar.MINUTE, minute)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            if (cal.timeInMillis <= nowEpoch + 1_000L) cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            return cal.timeInMillis
        }
    }
}
