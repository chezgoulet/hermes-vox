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
) {

    /** What the engine needs from the phone. One implementation on device; a fake in tests. */
    interface PhonePlatform {
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
    )

    private data class Undo(
        val id: String,
        val kind: String,
        val expiresAtElapsed: Long,
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

    data class Note(val id: String, val text: String, val atEpochMs: Long)

    // ---- the one entry point the MCP server uses ----

    /** Parse and execute one `phone` tool call. Never throws: a bad call is a described refusal. */
    fun dispatch(
        arguments: Map<String, Any?>,
        nowElapsed: Long,
        nowEpoch: Long,
    ): PhoneResult {
        val result = when (val parsed = PhoneTool.parse(arguments, grants)) {
            is PhoneTool.Parsed.Refuse -> parsed.result
            is PhoneTool.Parsed.Run -> execute(parsed.call, nowElapsed, nowEpoch)
        }
        // The audit records refusals as well as successes, because "my agent says it tried" is a
        // claim the owner has to be able to check. A failure to record must not fail the call.
        try {
            audit(result, arguments, nowEpoch)
        } catch (_: Throwable) {
        }
        return result
    }

    /** Execute one validated call. */
    fun execute(call: PhoneTool.Call, nowElapsed: Long, nowEpoch: Long): PhoneResult = synchronized(lock) {
        prune(nowElapsed, nowEpoch)
        when (call) {
            is PhoneTool.Call.Timer -> setTimer(call.durationMs, nowElapsed, nowEpoch)
            is PhoneTool.Call.Alarm -> setAlarm(call.hour, call.minute, nowEpoch)
            is PhoneTool.Call.Dnd -> setDnd(call.on, nowElapsed)
            is PhoneTool.Call.Volume -> setVolume(call.level, call.direction, nowElapsed)
            is PhoneTool.Call.Note -> saveNote(call.text, nowElapsed, nowEpoch)
            is PhoneTool.Call.PlayPause -> pressMedia(call.pause)
            is PhoneTool.Call.Skip -> skip(call.previous)
            PhoneTool.Call.NowPlaying -> nowPlaying()
            PhoneTool.Call.State -> deviceState(nowEpoch)
            is PhoneTool.Call.Undo -> undo(call.id, nowElapsed, nowEpoch)
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
     */
    fun restoreNotes(saved: List<Note>, nowElapsed: Long) = synchronized(lock) {
        for (n in saved) {
            notes[n.id] = n
            register(n.id, "note", nowElapsed + NOTE_UNDO_MS) { platform.deleteNote(n.id) }
            val suffix = n.id.removePrefix("note-").toIntOrNull() ?: 0
            if (suffix > nextId) nextId = suffix
        }
    }

    // ---- tier 0 ----

    private fun setTimer(durationMs: Long, nowElapsed: Long, nowEpoch: Long): PhoneResult {
        val id = "timer-${++nextId}"
        val atElapsed = nowElapsed + durationMs
        val atEpoch = nowEpoch + durationMs
        if (!platform.startTimer(id, durationMs, atEpoch)) {
            log("event=phone-mcp action=timer ok=false reason=unavailable")
            return PhoneResult.refused("timer", PhoneReason.UNAVAILABLE, "the phone refused to arm the countdown")
        }
        timers[id] = Armed(id, "timer", atElapsed, 0, durationMs, 0, 0)
        register(id, "timer", atElapsed) { platform.cancelTimer(id) }
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

    private fun setAlarm(hour: Int, minute: Int, nowEpoch: Long): PhoneResult {
        val at = nextOccurrence(hour, minute, nowEpoch)
        val id = "alarm-${++nextId}"
        if (!platform.startAlarm(id, hour, minute, at)) {
            log("event=phone-mcp action=alarm ok=false reason=unavailable")
            return PhoneResult.refused("alarm", PhoneReason.UNAVAILABLE, "the phone refused to arm the alarm")
        }
        alarms[id] = Armed(id, "alarm", 0, at, 0, hour, minute)
        // The undo has no elapsed clock to expire on: the registry drops it when the alarm is pruned.
        register(id, "alarm", Long.MAX_VALUE) { platform.cancelTimer(id) }
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

    private fun setDnd(on: Boolean, nowElapsed: Long): PhoneResult {
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
        if (previous != null) register(id, "dnd", nowElapsed + CHANGE_UNDO_MS) { platform.setDnd(previous) }
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

    private fun setVolume(level: Int?, direction: Int, nowElapsed: Long): PhoneResult {
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
            register(id, "volume", nowElapsed + CHANGE_UNDO_MS) { platform.setVolume(current) != null }
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
     * What the phone will say about playback. Without notification-listener access it will not name
     * the session, so this returns `known:false` with the cheap `playing` reading and a sentence
     * saying so — never a guessed title.
     */
    private fun nowPlaying(): PhoneResult {
        val p = platform.playback()
        if (p.known) {
            // Reachable only when a platform (a later lane holding session access) really can name
            // it; the shape is here so that lane does not have to invent a second one.
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
        log("event=phone-mcp action=now_playing ok=true known=false")
        return PhoneResult.info(
            "now_playing",
            linkedMapOf<String, Any?>(
                "known" to false,
                "playing" to p.playing,
                "package" to null,
                "title" to null,
                "note" to when (p.playing) {
                    true -> "audio is playing, but the phone cannot see which app it is or what it " +
                        "is: that needs notification-listener access, which this app does not hold"
                    false -> "no audio is playing right now, and the phone cannot see what was " +
                        "last: that needs notification-listener access, which this app does not hold"
                    null -> "the phone cannot see what is playing: that needs notification-listener " +
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

    // ---- tier 1 ----

    private fun saveNote(text: String, nowElapsed: Long, nowEpoch: Long): PhoneResult {
        val id = "note-${++nextId}"
        if (!platform.saveNote(id, text, nowEpoch)) {
            log("event=phone-mcp action=note ok=false reason=unavailable")
            return PhoneResult.refused("note", PhoneReason.UNAVAILABLE, "the note could not be saved")
        }
        notes[id] = Note(id, text, nowEpoch)
        register(id, "note", nowElapsed + NOTE_UNDO_MS) { platform.deleteNote(id) }
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

    // ---- undo ----

    fun undo(id: String, nowElapsed: Long, nowEpoch: Long): PhoneResult = synchronized(lock) {
        prune(nowElapsed, nowEpoch)
        val entry = undos.remove(id)
            ?: return PhoneResult.refused(
                "undo", PhoneReason.EXPIRED,
                "no undo is registered for that id any more; it fired, was superseded, or was never this phone's",
            )
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
                "state" to if (entry.kind == "timer" || entry.kind == "alarm") "cancelled" else "restored",
            ),
        )
    }

    // ---- discovery ----

    fun status(nowElapsed: Long, nowEpoch: Long): PhoneResult = synchronized(lock) {
        prune(nowElapsed, nowEpoch)
        val offered = PhoneTool.offeredActions(grants)
        val running = ArrayList<Map<String, Any?>>(timers.size + alarms.size)
        for (a in timers.values) {
            running.add(
                linkedMapOf(
                    "id" to a.id,
                    "kind" to "timer",
                    "remaining_ms" to (a.firesAtElapsed - nowElapsed).coerceAtLeast(0),
                )
            )
        }
        for (a in alarms.values) {
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
        for (n in notes.values) saved.add(linkedMapOf("id" to n.id, "text" to n.text))
        PhoneResult.info(
            "status",
            linkedMapOf<String, Any?>(
                "offered" to offered,
                "tiers" to linkedMapOf(
                    "0" to offered.filter { PhoneTiers.of(it) == PhoneTiers.INSTANT },
                    "1" to offered.filter { PhoneTiers.of(it) == PhoneTiers.REPORTED },
                    "2" to listOf(
                        "share", "message", "call", "email", "delete",
                    ),
                ),
                "grants" to PhoneGrants.summary(grants),
                "running" to running,
                "notes" to saved,
                "registry" to registry(nowElapsed, nowEpoch),
                "dnd" to linkedMapOf("on" to dndApplied, "granted" to platform.dndGranted()),
                "volume" to (volumeApplied ?: platform.currentVolume()),
            ),
        )
    }

    /**
     * The compact registry the presence context reports — the same strings, from the same state, that
     * `status` explains in full. Expired entries are dropped here, so the registry cannot report a
     * timer that already rang.
     */
    fun registry(nowElapsed: Long, nowEpoch: Long): List<String> = synchronized(lock) {
        prune(nowElapsed, nowEpoch)
        val out = ArrayList<String>()
        dndApplied?.let { out.add("dnd=${if (it) "on" else "off"}") }
        volumeApplied?.let { out.add("volume=$it%") }
        for (a in timers.values) out.add("timer(remaining=${LocalActionSpeech.remaining(a.firesAtElapsed - nowElapsed)})")
        for (a in alarms.values) out.add("alarm(at=${LocalActionSpeech.clock24(a.hour, a.minute)})")
        for (n in notes.values) out.add("note(id=${n.id})")
        out.takeLast(PresenceContext.MAX_ACTIONS)
    }

    /** The notes this phone is holding, newest last — for a caller that wants the text, not the id. */
    fun noteList(): List<Note> = synchronized(lock) { notes.values.toList() }

    // ---- internals ----

    private fun register(id: String, kind: String, expiresAtElapsed: Long, perform: () -> Boolean) {
        undos[id] = Undo(id, kind, expiresAtElapsed, perform)
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
