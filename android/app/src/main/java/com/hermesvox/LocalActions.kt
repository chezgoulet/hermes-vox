package com.hermesvox

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

    /** The one registry, shared by the user path, the MCP path and the presence context. */
    private val engine = PhoneEngine(Platform(), { line -> VoxLog.d(line) })

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
            return PhoneResult.refused(actionOf(arguments), PhoneReason.UNAVAILABLE, "the phone did not answer in time").json()
        }
        return out ?: PhoneResult.refused(actionOf(arguments), PhoneReason.UNAVAILABLE, "the phone did not answer").json()
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
    }

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

        /** How long the MCP caller waits for the main thread. Well under any tool timeout. */
        const val CALL_TIMEOUT_MS = 4_000L

        /** At most this many entries ride B1's `local_actions:` line. */
        const val MAX_REGISTRY = PresenceContext.MAX_ACTIONS
    }
}
