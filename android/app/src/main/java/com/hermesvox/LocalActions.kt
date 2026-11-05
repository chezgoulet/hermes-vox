package com.hermesvox

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.view.KeyEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * LocalActions — the Android side of the phone's own actions, for both initiators (#421 steps 2–4).
 *
 * There are two ways an action starts, and they keep different guarantees (D32, #421):
 *
 *  * **user-initiated** — [LocalActionGrammar] matches the caller's words and [run] executes here,
 *    with **no network round trip**. The caller hears one short line ([LocalActionSpeech]) and the
 *    turn never reaches the mind.
 *  * **mind-initiated** — the agent calls the MCP `phone` tool ([PhoneMcpServer]); [phone] executes
 *    the same call through the same engine, and **returns a result** the mind reasons about.
 *
 * Both go through [PhoneEngine], which owns the ids, the registry and the undo table. That is the
 * single-source-of-truth rule from #421: the presence context the mind reads and the `status` the
 * mind asks for are the same memory, so the phone can never contradict itself.
 *
 * **What each action costs, precisely** — because the honest answer decides what ships:
 *
 * | action | API | permission |
 * |---|---|---|
 * | timer | `AlarmManager.setExact(…, OnAlarmListener, handler)` | **none.** The exact-alarm restriction (`SCHEDULE_EXACT_ALARM`) does not apply to the listener form (Android docs, "Declare the appropriate exact alarm permission"), so no manifest change and no user prompt. The listener lives in this process, so the timer rings while the app is alive — which it is during a call (the voice service is foreground). A timer that must outlive the process would need a broadcast receiver plus the "Alarms & reminders" special access, and is deliberately not this. |
 * | alarm | the same, at a wall-clock time (`RTC_WAKEUP`) | **none**, same reasoning. The Clock app's own intents were considered and rejected for this lane: `ACTION_SET_TIMER` starts a timer we cannot read back or cancel, and `ACTION_SET_ALARM` opens another app's UI and reports no outcome. The tool's contract is a verified result plus an undo, so the in-app listener form is required. It does not need `SCHEDULE_EXACT_ALARM`. |
 * | do not disturb | `NotificationManager.setInterruptionFilter` | **the user's policy access**. `ACCESS_NOTIFICATION_POLICY` is declared, but the grant is a special access only the user can give in system settings. Without it the phone says so — it never pretends. The sheet shows the grant and opens the system screen. |
 * | volume | `AudioManager.setStreamVolume` / `adjustStreamVolume` (media stream) | **`MODIFY_AUDIO_SETTINGS`**, a normal permission declared in the manifest and granted at install; it is what the stream calls rely on. Some devices refuse a stream change while Do Not Disturb owns the stream; that is reported as a failure, not swallowed. The level is read back, so the result is the level in effect. |
 * | note | SharedPreferences, JSON list | **none.** A note is Tier 1: it is kept, reported to the mind on the next turn, and undoable. |
 * | media transport | `AudioManager.dispatchMediaKeyEvent` | **none.** No permission and no notification-listener access: the key goes to whatever session the platform considers active, exactly as the phone's own media buttons do. The result says the key was dispatched, never that playback changed — the phone cannot see that. |
 * | now playing | `AudioManager.isMusicActive`; `MediaSessionManager.getActiveSessions` with the listener | **none** for "is audio playing". A package and a title come from the active media session, which needs **notification-listener special access** — a grant the owner gives on Android's own screen, offered in the sheet. With it the result is `known:true` and the session's own package/title; without it `known:false` and a sentence saying so. It never invents a title. |
 * | device state | the sticky `ACTION_BATTERY_CHANGED` broadcast, `PowerManager.isInteractive`, `ConnectivityManager`, `StatFs`, the media stream, the device zone | **none.** No permission is added: battery, screen, transport, storage, volume and time are all readable with what the manifest already declares. Do Not Disturb is the one field that can be absent (its special access), and it reports `null` rather than "off". |
 * | clipboard | `ClipboardManager.setPrimaryClip` / `getPrimaryClip` | **none**, but Android decides what is readable: on 10+ an app that is not in front gets no clipboard at all, and a write from a backgrounded app is dropped silently. `clip` asks [AppFocus] before writing and answers `unavailable` rather than claiming a write that did not happen; `clip_read` asks for the clip and reports the platform's own refusal — backgrounded, or an empty clip — rather than returning stale or empty text as though it were the clipboard. |
 *
 * Tier 0 only for the grammar path: reversible, private, nothing outward-facing. No message, no call,
 * no purchase, no app launch.
 */
class LocalActions(context: Context) {

    /** What happened, and the one line to say about it — always true. */
    data class Outcome(val ok: Boolean, val line: String)

    private val app: Context = context.applicationContext ?: context
    private val main = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The one registry, shared by the user path, the MCP path and the presence context.
     *
     * The grants are read through [PhoneGrantStore.source] on every call (#429), so revoking a
     * group takes effect on the next call — not at the next connection. Every mind-initiated call
     * lands in the Recent actions audit, refusals included.
     */
    private val engine = PhoneEngine(
        Platform(),
        { line -> VoxLog.d(line) },
        PhoneGrantStore.source(app),
        { result, args, at -> PhoneAuditStore.record(app, PhoneAudit.entry(at, args, result)) },
    )

    /** How a scheduled action may be cancelled, per id. */
    private class Armed(
        val am: AlarmManager?,
        val listener: AlarmManager.OnAlarmListener?,
        val runnable: Runnable?,
    )

    private val armed = HashMap<String, Armed>()

    /**
     * Ids whose ring must be a no-op. Cancelling through the API is not enough by itself: the
     * process-clock fallback and a listener already handed to the OS can both fire late, and a
     * cancelled timer that still rings is a claim the caller can hear.
     */
    private val cancelled = LinkedHashSet<String>()

    init {
        // Notes outlive the call that made them; the undo for each is re-registered on load.
        engine.restoreNotes(Notes.decode(prefs.getString(KEY_NOTES, null)), SystemClock.elapsedRealtime())
        // The owner's Undo tap in the Phone tools sheet reaches this engine while the call lives.
        PhoneUndo.attach { id ->
            engine.undo(id, SystemClock.elapsedRealtime(), System.currentTimeMillis())
        }
    }

    /**
     * Take the action now (user-initiated path). [nowElapsed]/[nowEpoch] are injectable so the
     * registry maths is testable off-device.
     */
    fun run(
        intent: LocalActionGrammar.Intent,
        nowElapsed: Long = SystemClock.elapsedRealtime(),
        nowEpoch: Long = System.currentTimeMillis(),
    ): Outcome = try {
        val call = call(intent)
        val result = engine.execute(call, nowElapsed, nowEpoch)
        Outcome(result.ok, speech(intent, result))
    } catch (t: Throwable) {
        VoxLog.w("event=local-action failed kind=${intent.kind} reason=${t.javaClass.simpleName}")
        Outcome(false, LocalActionSpeech.failed(intent.kind))
    }

    /**
     * Take the action for the mind (MCP path), and **return what happened as JSON**. A result with
     * `ok:false` names the reason; nothing here ever claims an action the phone did not take.
     *
     * Called from the MCP server's thread, so the work is posted to the main thread (the alarm,
     * audio and notification APIs are happiest there) and the caller waits with a bound. A phone
     * that cannot answer in time says exactly that rather than inventing a result.
     */
    fun phone(arguments: Map<String, Any?>): String {
        if (Looper.myLooper() == Looper.getMainLooper()) return phoneHere(arguments)
        val latch = CountDownLatch(1)
        var out: String? = null
        main.post {
            out = try {
                phoneHere(arguments)
            } catch (t: Throwable) {
                VoxLog.w("event=phone-mcp failed reason=${t.javaClass.simpleName}")
                PhoneResult.refused(actionOf(arguments), PhoneReason.UNAVAILABLE, "the phone failed to run that action").json()
            }
            latch.countDown()
        }
        if (!latch.await(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            VoxLog.w("event=phone-mcp timeout action=${actionOf(arguments)}")
            return refusal(arguments, "the phone did not answer in time")
        }
        return out ?: refusal(arguments, "the phone did not answer")
    }

    /** A refusal the phone minted itself (a timeout, a crashed handler), recorded like any other. */
    private fun refusal(arguments: Map<String, Any?>, detail: String): String {
        val result = PhoneResult.refused(actionOf(arguments), PhoneReason.UNAVAILABLE, detail)
        PhoneAuditStore.record(app, PhoneAudit.entry(System.currentTimeMillis(), arguments, result))
        return result.json()
    }

    private fun phoneHere(arguments: Map<String, Any?>): String {
        val result = engine.dispatch(arguments, SystemClock.elapsedRealtime(), System.currentTimeMillis())
        persistNotes()
        return result.json()
    }

    private fun persistNotes() {
        try {
            prefs.edit().putString(KEY_NOTES, Notes.encode(engine.noteList())).apply()
        } catch (t: Throwable) {
            VoxLog.w("event=phone-mcp notes-persist-failed reason=${t.javaClass.simpleName}")
        }
    }

    /**
     * The active actions, newest first, compact — what B1's `local_actions:` line carries. Expired
     * entries are dropped here, so the registry cannot report a timer that already rang. It is the
     * same registry the `phone` tool's `status` explains in full.
     */
    fun registry(
        nowElapsed: Long = SystemClock.elapsedRealtime(),
        nowEpoch: Long = System.currentTimeMillis(),
    ): List<String> = engine.registry(nowElapsed, nowEpoch)

    // ---- the user path's words ----

    private fun call(intent: LocalActionGrammar.Intent): PhoneTool.Call = when (intent) {
        is LocalActionGrammar.Intent.Timer -> PhoneTool.Call.Timer(intent.durationMs)
        is LocalActionGrammar.Intent.Alarm -> PhoneTool.Call.Alarm(intent.hour, intent.minute)
        is LocalActionGrammar.Intent.Dnd -> PhoneTool.Call.Dnd(intent.on)
        is LocalActionGrammar.Intent.VolumeStep -> PhoneTool.Call.Volume(null, intent.direction)
        is LocalActionGrammar.Intent.SetVolume -> PhoneTool.Call.Volume(intent.percent, 0)
    }

    /**
     * The one line the caller hears. It is derived from the result, so a refusal can never be spoken
     * as a success: the ok branch is only reachable when [PhoneEngine] actually performed the action.
     */
    private fun speech(intent: LocalActionGrammar.Intent, result: PhoneResult): String {
        if (!result.ok) {
            return when {
                intent is LocalActionGrammar.Intent.Dnd && result.reason == PhoneReason.PERMISSION ->
                    LocalActionSpeech.dndDenied()
                intent is LocalActionGrammar.Intent.VolumeStep || intent is LocalActionGrammar.Intent.SetVolume ->
                    LocalActionSpeech.volumeDenied()
                else -> LocalActionSpeech.failed(intent.kind)
            }
        }
        return when (intent) {
            is LocalActionGrammar.Intent.Timer -> LocalActionSpeech.timerSet(intent.durationMs)
            is LocalActionGrammar.Intent.Alarm -> LocalActionSpeech.alarmSet(intent.hour, intent.minute)
            is LocalActionGrammar.Intent.Dnd -> LocalActionSpeech.dnd(intent.on)
            is LocalActionGrammar.Intent.VolumeStep, is LocalActionGrammar.Intent.SetVolume ->
                LocalActionSpeech.volume((result.facts["level"] as? Int) ?: 0)
        }
    }

    // ---- the platform ----

    private inner class Platform : PhoneEngine.PhonePlatform {

        override fun startTimer(id: String, durationMs: Long, atEpochMs: Long): Boolean =
            schedule(
                id = id,
                atMillis = SystemClock.elapsedRealtime() + durationMs,
                delayMs = durationMs,
                usingElapsed = true,
                kind = "timer",
            )

        override fun startAlarm(id: String, hour: Int, minute: Int, atEpochMs: Long): Boolean =
            schedule(
                id = id,
                atMillis = atEpochMs,
                delayMs = atEpochMs - System.currentTimeMillis(),
                usingElapsed = false,
                kind = "alarm",
            )

        /**
         * Schedule [id] to fire at [atMillis] on the chosen clock. The listener form is used
         * deliberately: it needs no "Alarms & reminders" grant, and its one limitation — it lives in
         * this process — is exactly the lifetime a timer set during a call has.
         */
        private fun schedule(id: String, atMillis: Long, delayMs: Long, usingElapsed: Boolean, kind: String): Boolean {
            val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (am != null) {
                try {
                    val listener = AlarmManager.OnAlarmListener { fire(id, kind) }
                    val type = if (usingElapsed) AlarmManager.ELAPSED_REALTIME_WAKEUP else AlarmManager.RTC_WAKEUP
                    am.setExact(type, atMillis, id, listener, main)
                    synchronized(armed) { armed[id] = Armed(am, listener, null) }
                    return true
                } catch (t: Throwable) {
                    // Not a claim of success: fall back to this process's own clock below. If that
                    // fails too the caller is told the timer could not be set.
                    VoxLog.w("event=local-action alarm-api-fallback kind=$kind reason=${t.javaClass.simpleName}")
                }
            }
            return try {
                val r = Runnable { fire(id, kind) }
                main.postDelayed(r, delayMs.coerceAtLeast(0L))
                synchronized(armed) { armed[id] = Armed(null, null, r) }
                true
            } catch (_: Throwable) {
                false
            }
        }

        override fun cancelTimer(id: String): Boolean {
            val a = synchronized(armed) { armed.remove(id) }
            synchronized(cancelled) {
                cancelled.add(id)
                while (cancelled.size > CANCELLED_MEMORY) cancelled.remove(cancelled.first())
            }
            if (a == null) return false
            a.listener?.let { l -> try { a.am?.cancel(l) } catch (_: Throwable) { } }
            a.runnable?.let { r -> try { main.removeCallbacks(r) } catch (_: Throwable) { } }
            return true
        }

        override fun dndGranted(): Boolean = try {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.isNotificationPolicyAccessGranted == true
        } catch (_: Throwable) {
            false
        }

        override fun currentDnd(): Boolean? = try {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.let { it.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL }
        } catch (_: Throwable) {
            null
        }

        override fun setDnd(on: Boolean): Boolean = try {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
            nm.setInterruptionFilter(
                if (on) NotificationManager.INTERRUPTION_FILTER_NONE
                else NotificationManager.INTERRUPTION_FILTER_ALL
            )
            true
        } catch (_: Throwable) {
            false
        }

        override fun currentVolume(): Int? = try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            (am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100) / max
        } catch (_: Throwable) {
            null
        }

        override fun setVolume(percent: Int): Int? = try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
            val stream = AudioManager.STREAM_MUSIC
            val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
            val target = Math.round(percent / 100f * max).coerceIn(0, max)
            am.setStreamVolume(stream, target, 0)
            // Read back: the level in effect is what we report, not the one we asked for.
            (am.getStreamVolume(stream) * 100) / max
        } catch (t: Throwable) {
            // Do Not Disturb can own the stream on some devices.
            VoxLog.d("event=local-action volume-denied reason=${t.javaClass.simpleName}")
            null
        }

        /**
         * One step of the media stream, exactly as the volume keys move it — the behaviour the
         * caller's "volume up" has always had (D32 keeps it), not a one-percent nudge.
         */
        override fun adjustVolume(direction: Int): Int? = try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
            val stream = AudioManager.STREAM_MUSIC
            val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
            am.adjustStreamVolume(
                stream,
                if (direction >= 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                0,
            )
            (am.getStreamVolume(stream) * 100) / max
        } catch (t: Throwable) {
            VoxLog.d("event=local-action volume-denied reason=${t.javaClass.simpleName}")
            null
        }

        override fun saveNote(id: String, text: String, atEpochMs: Long): Boolean = true

        override fun deleteNote(id: String): Boolean = true

        /**
         * The folders the owner granted with the system picker, by their own display names. The
         * tree URI stays in [PhoneFileAccess]; the engine only needs the label to choose one, so
         * the path never travels through a tool result or a log line.
         */
        override fun folders(): List<PhoneEngine.PhonePlatform.GrantedFolder> =
            PhoneFileAccess.trees(app).map { PhoneEngine.PhonePlatform.GrantedFolder(it.label) }

        override fun fileText(
            folder: PhoneEngine.PhonePlatform.GrantedFolder,
            name: String,
        ): PhoneEngine.PhonePlatform.FileText {
            val tree = treeFor(folder) ?: return PhoneEngine.PhonePlatform.FileText.Failed(
                "the folder granted to your agent is no longer readable · grant it again in Phone tools",
            )
            return PhoneFileAccess.read(app, tree, name)
        }

        override fun fileOffer(
            folder: PhoneEngine.PhonePlatform.GrantedFolder,
            name: String,
        ): PhoneEngine.PhonePlatform.FileHandoff {
            val tree = treeFor(folder) ?: return PhoneEngine.PhonePlatform.FileHandoff.Unavailable(
                "the folder granted to your agent is no longer readable · grant it again in Phone tools",
            )
            return PhoneFileAccess.offer(app, tree, name)
        }

        /** The one folder whose label matches, by the same normalisation the engine used. */
        private fun treeFor(folder: PhoneEngine.PhonePlatform.GrantedFolder): PhoneFileAccess.Tree? =
            PhoneFileAccess.trees(app).firstOrNull {
                PhoneFiles.normalizeFolder(it.label) == PhoneFiles.normalizeFolder(folder.label)
            }

        /** Android's own missing-permission string, or null when both calendar halves are held. */
        override fun calendarPermission(): String? = PhoneCalendarAccess.permission(app)

        override fun agenda(): List<PhoneEngine.PhonePlatform.AgendaEvent>? =
            PhoneCalendarAccess.agenda(app)

        override fun createEvent(
            title: String,
            startLocal: String,
            durationMin: Int,
            calendar: String?,
        ): PhoneEngine.PhonePlatform.CreatedEvent? =
            PhoneCalendarAccess.createEvent(app, title, startLocal, durationMin, calendar)

        override fun deleteEvent(id: String): Boolean = PhoneCalendarAccess.deleteEvent(app, id)

        /**
         * Dispatch one media key at whatever session is active — exactly what the phone's own
         * media buttons do. No permission and no notification-listener access: the key goes to the
         * platform, which routes it to the session that owns the media button receiver. False means
         * no audio service would take it, never "playback changed".
         */
        override fun mediaKey(key: PhoneEngine.PhonePlatform.MediaKey): Boolean = try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            val code = when (key) {
                PhoneEngine.PhonePlatform.MediaKey.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
                PhoneEngine.PhonePlatform.MediaKey.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
                PhoneEngine.PhonePlatform.MediaKey.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
                PhoneEngine.PhonePlatform.MediaKey.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            }
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            true
        } catch (t: Throwable) {
            VoxLog.d("event=local-action media-key-denied reason=${t.javaClass.simpleName}")
            false
        }

        /**
         * What the phone will say about playback. `isMusicActive` needs no access; the package
         * and title are the active session's own metadata, read only while the owner's
         * notification-listener grant is held ([MediaListen]). Without the grant the result is
         * `known:false` — the platform's boundary, reported rather than worked around.
         */
        override fun playback(): PhoneEngine.PhonePlatform.Playback = MediaListen.playback(app)

        /**
         * One read of the phone's state, each field answered or `null`. Nothing here needs a
         * permission the manifest does not already declare: the battery comes from the sticky
         * `ACTION_BATTERY_CHANGED` broadcast, the screen from `PowerManager.isInteractive`, the
         * transport from `ACCESS_NETWORK_STATE`, storage from `StatFs`, volume from the media
         * stream, and the clock from the device's own zone. Do Not Disturb is the one field that
         * can be absent because a special access is missing, and it is `null` rather than "off".
         */
        override fun deviceState(): PhoneEngine.PhonePlatform.DeviceSnapshot {
            val battery = attempt("battery") { batteryReadings() }
            val zone = attempt("zone") { java.util.TimeZone.getDefault() }
            return PhoneEngine.PhonePlatform.DeviceSnapshot(
                batteryPercent = battery?.percent,
                chargingSource = battery?.source,
                chargingStatus = battery?.status,
                screenOn = attempt("screen") {
                    (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive
                },
                network = attempt("network") { networkTransport() },
                tailnetPresent = attempt("tailnet") { Tailnet.address() != null },
                freeStorageBytes = attempt("storage") {
                    StatFs(app.filesDir.absolutePath).availableBytes
                },
                mediaVolume = attempt("volume") { currentVolume() },
                dnd = attempt("dnd") { if (dndGranted()) currentDnd()?.let { if (it) "on" else "off" } else null },
                timeZoneId = zone?.id,
                utcOffsetMinutes = zone?.let { attempt("offset") { it.getOffset(System.currentTimeMillis()) / 60_000 } },
            )
        }

        /** The battery broadcast's three answers. Any field it does not carry stays null. */
        private fun batteryReadings(): BatteryReadings? {
            val i = app.registerReceiver(
                null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),
            ) ?: return null
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            return BatteryReadings(
                percent = if (level >= 0 && scale > 0) level * 100 / scale else null,
                source = when (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
                    0 -> "none"
                    1 -> "ac"        // BATTERY_PLUGGED_AC
                    2 -> "usb"       // BATTERY_PLUGGED_USB
                    4 -> "wireless"  // BATTERY_PLUGGED_WIRELESS
                    8 -> "dock"      // BATTERY_PLUGGED_DOCK (API 33; the value is stable)
                    else -> null
                },
                status = when (i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                    2 -> "charging"      // BATTERY_STATUS_CHARGING
                    3 -> "discharging"   // BATTERY_STATUS_DISCHARGING
                    4 -> "not-charging"  // BATTERY_STATUS_NOT_CHARGING
                    5 -> "full"          // BATTERY_STATUS_FULL
                    else -> null         // unknown is not a state; say nothing
                },
            )
        }

        /** `wifi` / `cellular` / `ethernet` / `vpn` / `other`, or `none` when nothing is connected. */
        private fun networkTransport(): String? {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
            val active = cm.activeNetwork ?: return "none"
            val caps = cm.getNetworkCapabilities(active) ?: return null
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            }
        }

        /** One field's read: a failure is that field's null, never the whole report's failure. */
        private fun <T> attempt(field: String, read: () -> T?): T? = try {
            read()
        } catch (t: Throwable) {
            VoxLog.d("event=local-action device-state-unreadable field=$field reason=${t.javaClass.simpleName}")
            null
        }

        /**
         * Write the text, and nothing else. The in-front check is done first because Android 10+
         * drops a background write without saying so; reporting `ok` after a silent drop would be
         * the one thing this tool must never do. The label is the app's name, never the caller's
         * text at the OS layer either.
         */
        override fun clip(text: String): Boolean {
            if (!AppFocus.inFront) return false
            return try {
                val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return false
                cm.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
                true
            } catch (t: Throwable) {
                VoxLog.d("event=local-action clip-denied reason=${t.javaClass.simpleName}")
                false
            }
        }

        /**
         * Read the clipboard **when Android allows it**. The platform, not this app, decides: an
         * app it does not consider in front gets no clipboard (Android 10+), so the read is
         * refused with that reason rather than returning an empty clip as if it were the answer.
         * An empty clipboard and a non-text item are refused too: "nothing" is not clipboard text.
         *
         * Nothing else in the app reads the clipboard; this is the one read path, behind the
         * owner's `clipboard` grant.
         */
        override fun readClip(): PhoneEngine.PhonePlatform.ClipRead {
            if (!AppFocus.inFront) {
                return PhoneEngine.PhonePlatform.ClipRead.Unavailable(
                    "Android does not let an app that is not in front read the clipboard; the " +
                        "phone was backgrounded, so nothing was read",
                )
            }
            return try {
                val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return PhoneEngine.PhonePlatform.ClipRead.Unavailable(
                        "this phone has no clipboard service to read",
                    )
                val clip = cm.primaryClip
                    ?: return PhoneEngine.PhonePlatform.ClipRead.Empty
                if (clip.itemCount == 0) return PhoneEngine.PhonePlatform.ClipRead.Empty
                val text = clip.getItemAt(0).text?.toString()
                if (text.isNullOrEmpty()) {
                    PhoneEngine.PhonePlatform.ClipRead.Unavailable(
                        "the clipboard holds no text (it may be an image or another app's " +
                            "private content), so there was nothing to return",
                    )
                } else {
                    PhoneEngine.PhonePlatform.ClipRead.Text(text)
                }
            } catch (t: Throwable) {
                VoxLog.d("event=local-action clip-read-denied reason=${t.javaClass.simpleName}")
                PhoneEngine.PhonePlatform.ClipRead.Unavailable(
                    "Android refused the clipboard read (${t.javaClass.simpleName}); nothing " +
                        "was read",
                )
            }
        }
    }

    /** The battery broadcast, decoded. */
    private data class BatteryReadings(val percent: Int?, val source: String?, val status: String?)

    // ---- ring ----

    private fun fire(id: String, kind: String) {
        val wasCancelled = synchronized(cancelled) { cancelled.remove(id) }
        if (wasCancelled) return
        synchronized(armed) { armed.remove(id) }
        engine.onFired(id)
        VoxLog.d("event=local-action ring kind=$kind id=$id")
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            RingtoneManager.getRingtone(app, uri)?.play()
        } catch (t: Throwable) {
            VoxLog.w("event=local-action ring-audio-failed reason=${t.javaClass.simpleName}")
        }
        try {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm != null) {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Timers and alarms",
                        NotificationManager.IMPORTANCE_HIGH))
                }
                val n = if (android.os.Build.VERSION.SDK_INT >= 26)
                    android.app.Notification.Builder(app, CHANNEL)
                else
                    @Suppress("DEPRECATION") android.app.Notification.Builder(app)
                nm.notify(NOTIFICATION_ID, n
                    .setContentTitle(if (kind == "alarm") "Alarm" else "Timer")
                    .setContentText("The ${if (kind == "alarm") "alarm" else "timer"} you set is going off.")
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setAutoCancel(true)
                    .build())
            }
        } catch (t: Throwable) {
            // POST_NOTIFICATIONS can be denied on 13+. The ringtone is the signal that matters.
            VoxLog.w("event=local-action notify-failed reason=${t.javaClass.simpleName}")
        }
    }

    private fun actionOf(arguments: Map<String, Any?>): String =
        (arguments["action"] as? String)?.trim()?.lowercase().orEmpty()

    companion object {
        private const val CHANNEL = "vox_timers"
        private const val NOTIFICATION_ID = 2
        private const val PREFS = "hv"
        private const val KEY_NOTES = "phone_notes"
        private const val CANCELLED_MEMORY = 64

        /** The clipboard entry's label. The app's name, never the caller's text. */
        private const val CLIP_LABEL = "Hermes Vox"

        /** How long the MCP caller waits for the main thread. Well under any tool timeout. */
        const val CALL_TIMEOUT_MS = 4_000L

        /** At most this many entries ride B1's `local_actions:` line. */
        const val MAX_REGISTRY = PresenceContext.MAX_ACTIONS
    }
}
