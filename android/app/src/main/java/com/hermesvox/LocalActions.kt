package com.hermesvox

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
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
 * | clipboard | `ClipboardManager.setPrimaryClip` / `getPrimaryClip` | **none.** Android restricts clipboard *reads*: on 10+ an app that is not focused gets no clipboard at all, while a write needs no focus and is permitted from the background or PiP. So `clip` always attempts the write and answers `unavailable` only when the platform refuses it, with the platform's reason; `clip_read` asks for the clip and reports the platform's own answer — no clip without focus (said as the likely cause, not a claim), no text, or an empty clipboard — rather than returning stale or empty text as though it were the clipboard. |
 * | message | `ACTION_SENDTO` with `smsto:` and `sms_body` | **none.** Vox composes a draft and the owner's messaging app sends it: no `SEND_SMS`, no SMS manager API, and no default-SMS role. The result says `handed_off:true`, never "sent". |
 * | dial | `ACTION_DIAL` with `tel:` | **none.** The dialer opens with the number and the owner presses call: no `CALL_PHONE` and no emergency special case, because Vox never dials. With a live Vox call, the being says one line, the call ends through the same teardown the hang-up uses, and then the dialer opens — the conversation itself lives on. |
 * | launch / open | `PackageManager` launchable resolution; `ACTION_VIEW` for a URI | **none.** Only activities with a launcher entry are launchable, and the launch goes through [CallWindow] so a live call enters PiP first. If the window cannot shrink, the app still opens. |
 *
 * The **grammar path** is still Tier 0 only: timers, alarms, do-not-disturb and volume, matched
 * locally with no round trip. `message`, `dial` and the app handoffs are **mind-initiated** only —
 * they exist on the MCP wire, where the result is the reply, and never run from the caller's words.
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
        approvalSurface = PhoneApprovals,
        auditRow = { entry -> PhoneAuditStore.record(app, entry) },
        unlocked = PhoneApproval.Unlocked { PhoneApprovals.deviceUnlocked() },
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
        // OWNER is explicit: the owner may reverse anything the phone still holds, including an
        // entry the mind made. That door is deliberately unrestricted (H4).
        PhoneUndo.attach { id ->
            engine.undo(
                id,
                SystemClock.elapsedRealtime(),
                System.currentTimeMillis(),
                PhoneEngine.Origin.OWNER,
            )
        }
        // The uninstall card: the surface presents, this engine decides. One answerer, one desk.
        PhoneApprovals.install(app)
        PhoneApprovals.bind(object : PhoneApprovals.Answerer {
            override fun answer(id: String, choice: String, via: String): Boolean =
                engine.answerApproval(id, choice, via, System.currentTimeMillis())

            override fun tick(now: Long) = engine.tickApprovals(now)
        })
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
        // The owner's own words: every entry this registers is tagged OWNER, and the mind's
        // `status`/`undo` will not serve it (H4).
        val result = engine.execute(call, nowElapsed, nowEpoch, PhoneEngine.Origin.OWNER)
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

    // ---- the uninstall card ----

    /** The card waiting for an answer right now, or null. */
    fun currentApproval(): PhoneApproval.Card? = engine.currentApproval()

    /** One spoken utterance against that card; the allowlist is [PhoneApproval]'s, not a new one. */
    fun approvalVoice(
        text: String,
        nowEpoch: Long = System.currentTimeMillis(),
    ): PhoneApproval.Voice = engine.approvalVoice(text, nowEpoch)

    /** Expire cards whose two minutes are up. The surface calls this on its own timer. */
    fun tickApprovals(nowEpoch: Long = System.currentTimeMillis()) = engine.tickApprovals(nowEpoch)

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
         * The folders the owner granted with the system picker, by their own display names and
         * **tree ids**. The tree URI stays in [PhoneFileAccess]; the engine needs the id to name
         * the grant and the label to let a call choose one, so the path never travels through a
         * tool result or a log line.
         */
        override fun folders(): List<PhoneEngine.PhonePlatform.GrantedFolder> =
            PhoneFileAccess.trees(app).map {
                PhoneEngine.PhonePlatform.GrantedFolder(id = it.id, label = it.label)
            }

        override fun fileText(
            folder: PhoneEngine.PhonePlatform.GrantedFolder,
            name: String,
        ): PhoneEngine.PhonePlatform.FileText {
            val tree = treeFor(folder) ?: return PhoneEngine.PhonePlatform.FileText.Failed(
                "the folder granted to your agent is no longer readable · grant it again in Phone tools",
            )
            return PhoneFileAccess.read(app, tree, name)
        }

        override fun fileWindow(
            folder: PhoneEngine.PhonePlatform.GrantedFolder,
            name: String,
            offset: Long,
            length: Long,
        ): PhoneEngine.PhonePlatform.FileText {
            val tree = treeFor(folder) ?: return PhoneEngine.PhonePlatform.FileText.Failed(
                "the folder granted to your agent is no longer readable · grant it again in Phone tools",
            )
            return PhoneFileAccess.readWindow(app, tree, name, offset, length)
        }

        override fun fileList(
            folder: PhoneEngine.PhonePlatform.GrantedFolder,
            path: String,
            depth: Int,
            pattern: String?,
            offset: Int,
            limit: Int,
        ): PhoneFiles.ListResult {
            val tree = treeFor(folder) ?: return PhoneFiles.ListResult.Failed(
                "the folder granted to your agent is no longer readable · grant it again in Phone tools",
            )
            return PhoneFileAccess.list(app, tree, path, depth, pattern, offset, limit)
        }

        override fun fileInfo(
            folder: PhoneEngine.PhonePlatform.GrantedFolder,
            name: String,
        ): PhoneEngine.PhonePlatform.FileInfo {
            val tree = treeFor(folder) ?: return PhoneEngine.PhonePlatform.FileInfo.Unavailable(
                "the folder granted to your agent is no longer readable · grant it again in Phone tools",
            )
            return PhoneFileAccess.info(app, tree, name)
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

        /** The one tree whose **id** is the grant: a label may repeat, a tree id cannot. */
        private fun treeFor(folder: PhoneEngine.PhonePlatform.GrantedFolder): PhoneFileAccess.Tree? =
            PhoneFileAccess.trees(app).firstOrNull { it.id == folder.id }

        /** Android's own missing-permission string, or null when both calendar halves are held. */
        override fun calendarPermission(): String? = PhoneCalendarAccess.permission(app)

        /** `agenda` needs read alone; denying write must not take the owner's read away. */
        override fun calendarReadPermission(): String? = PhoneCalendarAccess.readPermission(app)

        /** `event_create` needs read and write, in that order. */
        override fun calendarWritePermission(): String? = PhoneCalendarAccess.writePermission(app)

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

        /** The system's notification-listener access, shared with Media but used call-scoped. */
        override fun notificationListenerGranted(): Boolean = MediaListen.granted(app)

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
         * Write the text, and nothing else. The write is attempted from wherever the call is:
         * Android's restriction is on clipboard reads, not writes, so background and PiP are not
         * reasons to refuse before asking. The label is the app's name, never the caller's text at
         * the OS layer either.
         */
        override fun clip(text: String): PhoneEngine.PhonePlatform.ClipWrite = ClipboardRules.write {
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: throw IllegalStateException("this phone has no clipboard service")
            cm.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
        }.also { result ->
            if (result is PhoneEngine.PhonePlatform.ClipWrite.Refused) {
                VoxLog.d("event=local-action clip-denied detail=${result.detail}")
            }
        }

        // ---- handoffs: messages, calls, apps ----

        /** The exact runtime permission `contact` needs, or null when the owner has granted it. */
        override fun contactsPermission(): String? = ContactsAccess.permission(app)

        /** Up to 20 people by full or partial name, each with numbers and labels only. */
        override fun contactMatches(
            name: String,
            limit: Int,
        ): List<PhoneEngine.PhonePlatform.ContactMatch>? =
            ContactsAccess.matches(app, name, limit)

        /** The first number of the first match, for `message`/`dial`. Null is "no such contact". */
        override fun contactNumber(name: String): String? = ContactsAccess.firstNumber(app, name)

        /**
         * `ACTION_SENDTO` with an `smsto:` URI and the draft in `sms_body`. **No send call of any
         * kind** — no SMS permission, no SMS manager API, no default-SMS role. The owner's
         * messaging app opens on the draft and its own send button is the only thing that sends.
         */
        override fun sendMessage(to: String, text: String): PhoneEngine.PhonePlatform.Handoff {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", to, null))
                .putExtra("sms_body", text)
            return startHandoff(intent, "message")
        }

        /** A dialer that can answer `ACTION_DIAL`, resolved before a live call is ended. */
        override fun canDial(): Boolean = try {
            app.packageManager.resolveActivity(
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:")),
                PackageManager.MATCH_DEFAULT_ONLY,
            ) != null
        } catch (t: Throwable) {
            VoxLog.d("event=local-action dial-resolve-failed reason=${t.javaClass.simpleName}")
            false
        }

        /** `ACTION_DIAL` with the number filled in. The owner presses call; this never dials. */
        override fun dial(number: String): Boolean = try {
            app.startActivity(
                Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (t: Throwable) {
            VoxLog.w("event=local-action dial-failed reason=${t.javaClass.simpleName}")
            false
        }

        /** Is Vox's own call live in the attached window right now? */
        override fun callLive(): Boolean = PhoneHandoff.callLive()

        /** Speak the handoff line through the being; the call ends when it is audible. */
        override fun announce(line: String, after: () -> Unit) =
            PhoneHandoff.announceDial(line, after)

        /** The same teardown the on-screen hang-up runs, never a second one. */
        override fun endCall() = PhoneHandoff.endCall()

        /**
         * Resolve [app] among **launchable** activities only: an exact package match first, then
         * the label the launcher shows, case-insensitively. A package with no launcher entry is
         * not an app the owner can open, so it is not a target.
         */
        override fun resolveLaunchable(name: String): PhoneEngine.PhonePlatform.AppTarget? = try {
            val pm = app.packageManager
            val launchables = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0,
            )
            val match = launchables.firstOrNull { it.activityInfo.packageName == name }
                ?: launchables.firstOrNull {
                    it.loadLabel(pm).toString().equals(name, ignoreCase = true)
                }
            match?.let {
                PhoneEngine.PhonePlatform.AppTarget(
                    it.activityInfo.packageName,
                    it.activityInfo.name,
                )
            }
        } catch (t: Throwable) {
            VoxLog.d("event=local-action launch-resolve-failed reason=${t.javaClass.simpleName}")
            null
        }

        override fun startApp(target: PhoneEngine.PhonePlatform.AppTarget): Boolean = try {
            app.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(target.packageName, target.className)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (t: Throwable) {
            VoxLog.w("event=local-action launch-failed reason=${t.javaClass.simpleName}")
            false
        }

        override fun resolveUri(
            uri: String,
            packageName: String?,
        ): PhoneEngine.PhonePlatform.AppTarget? = try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            packageName?.let { intent.setPackage(it) }
            app.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.let {
                PhoneEngine.PhonePlatform.AppTarget(
                    it.activityInfo.packageName,
                    it.activityInfo.name,
                )
            }
        } catch (t: Throwable) {
            VoxLog.d("event=local-action open-resolve-failed reason=${t.javaClass.simpleName}")
            null
        }

        override fun startUri(
            target: PhoneEngine.PhonePlatform.AppTarget,
            uri: String,
        ): Boolean = try {
            app.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                    .setClassName(target.packageName, target.className)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (t: Throwable) {
            VoxLog.w("event=local-action open-failed reason=${t.javaClass.simpleName}")
            false
        }

        /**
         * The app's own label, which is the only name the uninstall card may show. Null when no
         * such package is installed, so the request refuses `unavailable` instead of raising a card
         * for a name nobody can recognise.
         */
        override fun appLabel(packageName: String): String? = try {
            val pm = app.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString().takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            VoxLog.d("event=local-action uninstall-label-missing reason=${t.javaClass.simpleName}")
            null
        }

        /**
         * Open Android's own confirmation (`ACTION_DELETE`). The owner answers *that* screen; this
         * phone cannot see the answer, so the result says "confirming", not "removed". A background
         * activity launch the platform refuses becomes `unavailable`, never a silent success.
         */
        override fun uninstall(packageName: String): PhoneEngine.PhonePlatform.Uninstall = try {
            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.packageManager.getApplicationInfo(packageName, 0)
            app.startActivity(intent)
            PhoneEngine.PhonePlatform.Uninstall.Confirming(packageName)
        } catch (t: Throwable) {
            val missing = t is PackageManager.NameNotFoundException
            VoxLog.w(
                "event=local-action uninstall-failed reason=${t.javaClass.simpleName} " +
                    "missing=$missing"
            )
            if (missing) PhoneEngine.PhonePlatform.Uninstall.Missing
            else PhoneEngine.PhonePlatform.Uninstall.Unavailable(
                "Android would not open its uninstall confirmation; nothing was removed",
            )
        }

        override fun window(): CallWindow.Host? = PhoneHandoff.window()

        /**
         * Resolve an intent, then start it. The result names the package Android resolved, so the
         * mind never has to infer which app it opened; no resolver is [Handoff.NoApp], and a
         * platform refusal is described rather than claimed as success.
         */
        private fun startHandoff(
            intent: Intent,
            action: String,
        ): PhoneEngine.PhonePlatform.Handoff = try {
            val resolved = app.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?: return PhoneEngine.PhonePlatform.Handoff.NoApp
            app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            VoxLog.d("event=local-action handoff action=$action package=${resolved.activityInfo.packageName}")
            PhoneEngine.PhonePlatform.Handoff.Done(resolved.activityInfo.packageName)
        } catch (e: ActivityNotFoundException) {
            PhoneEngine.PhonePlatform.Handoff.NoApp
        } catch (t: Throwable) {
            VoxLog.w("event=local-action handoff-failed action=$action reason=${t.javaClass.simpleName}")
            PhoneEngine.PhonePlatform.Handoff.Failed(
                "Android refused to hand the ${action} to another app; nothing was opened",
            )
        }

        /**
         * Read the clipboard **when Android allows it**. The platform, not this app, decides: an
         * app it does not consider focused gets no clipboard (Android 10+), so the read is asked
         * for and refused with the platform's outcome rather than pre-empted. When the platform
         * returns nothing while Vox is not focused, the sentence names focus as the likely cause
         * without claiming to know it. An empty clipboard and a non-text item are refused too:
         * "nothing" is not clipboard text.
         *
         * Nothing else in the app reads the clipboard; this is the one read path, behind the
         * owner's `clipboard` grant.
         */
        override fun readClip(): PhoneEngine.PhonePlatform.ClipRead {
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return PhoneEngine.PhonePlatform.ClipRead.Unavailable(
                    "this phone has no clipboard service to read",
                )
            return ClipboardRules.read(AppFocus.inFront) {
                val clip = cm.primaryClip ?: return@read ClipboardRules.Reading.NoClip
                if (clip.itemCount == 0) return@read ClipboardRules.Reading.NoClip
                val text = clip.getItemAt(0).text?.toString()
                if (text.isNullOrEmpty()) ClipboardRules.Reading.NoText
                else ClipboardRules.Reading.Text(text)
            }.also { result ->
                if (result is PhoneEngine.PhonePlatform.ClipRead.Unavailable) {
                    VoxLog.d("event=local-action clip-read-denied detail=${result.detail}")
                }
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
