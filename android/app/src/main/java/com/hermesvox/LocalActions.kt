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

/**
 * LocalActions — B2 (#421 step 2): the four actions the phone takes by itself.
 *
 * The grammar ([LocalActionGrammar]) decides; this class executes, and keeps the small registry
 * [PresenceContext] reports to the mind so it can answer "how long is left on that timer?".
 *
 * **No network round trip.** That is the entire point of the path: by the time a streamed turn
 * would have finished opening its connection, the timer is already running and the caller has
 * heard about it.
 *
 * **What each action costs, precisely** — because the honest answer decides what ships:
 *
 * | action | API | permission |
 * |---|---|---|
 * | timer | `AlarmManager.setExact(…, OnAlarmListener, handler)` | **none.** The exact-alarm restriction (`SCHEDULE_EXACT_ALARM`) does not apply to the listener form (Android docs, "Declare the appropriate exact alarm permission"), so no manifest change and no user prompt. The listener lives in this process, so the timer rings while the app is alive — which it is during a call (the voice service is foreground). A timer that must outlive the process would need a broadcast receiver plus the "Alarms & reminders" special access, and is deliberately not this. |
 * | alarm | the same, at a wall-clock time (`RTC_WAKEUP`) | **none**, same reasoning. It rings through this app; it is not added to the system Clock app, which would need an outward-facing intent into another app. |
 * | do not disturb | `NotificationManager.setInterruptionFilter` | **the user's policy access**. `ACCESS_NOTIFICATION_POLICY` is already declared (#325 quiet calls), but the grant is a special access only the user can give in system settings. Without it the phone says so — it never pretends. |
 * | volume | `AudioManager.setStreamVolume` (media stream) | **none.** Some devices refuse a stream change while Do Not Disturb owns the stream; that is reported as a failure, not swallowed. |
 *
 * Tier 0 only: reversible, private, nothing outward-facing. No message, no call, no purchase, no
 * app launch.
 */
class LocalActions(context: Context) {

    /** What happened, and the one line to say about it — always true. */
    data class Outcome(val ok: Boolean, val line: String)

    private val app: Context = context.applicationContext ?: context
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    /** Running timers: label -> when it fires, on the [SystemClock.elapsedRealtime] clock. */
    private val timers = LinkedHashMap<String, Long>()
    /** Armed alarms: label -> when it fires, epoch millis. */
    private val alarms = LinkedHashMap<String, Long>()
    /** The last Do Not Disturb / volume state this class set (null = untouched). */
    private var dndOn: Boolean? = null
    private var volumePercent: Int? = null
    private var nextId = 0

    /** Take the action now. [nowElapsed]/[nowEpoch] are injectable so the registry maths is
     *  testable off-device. */
    fun run(
        intent: LocalActionGrammar.Intent,
        nowElapsed: Long = SystemClock.elapsedRealtime(),
        nowEpoch: Long = System.currentTimeMillis(),
    ): Outcome = try {
        when (intent) {
            is LocalActionGrammar.Intent.Timer -> timer(intent.durationMs, nowElapsed)
            is LocalActionGrammar.Intent.Alarm -> alarm(intent.hour, intent.minute, nowEpoch)
            is LocalActionGrammar.Intent.Dnd -> dnd(intent.on)
            is LocalActionGrammar.Intent.VolumeStep -> volume(step = intent.direction)
            is LocalActionGrammar.Intent.SetVolume -> volume(percent = intent.percent)
        }
    } catch (t: Throwable) {
        VoxLog.w("event=local-action failed kind=${intent.kind} reason=${t.javaClass.simpleName}")
        Outcome(false, LocalActionSpeech.failed(intent.kind))
    }

    /**
     * The active actions, newest first, compact — what B1's `local_actions:` line carries. Expired
     * entries are dropped here, so the registry cannot report a timer that already rang.
     */
    fun registry(
        nowElapsed: Long = SystemClock.elapsedRealtime(),
        nowEpoch: Long = System.currentTimeMillis(),
    ): List<String> = synchronized(lock) {
        timers.entries.removeAll { nowElapsed >= it.value }
        alarms.entries.removeAll { nowEpoch >= it.value }
        val out = ArrayList<String>()
        dndOn?.let { out.add("dnd=${if (it) "on" else "off"}") }
        volumePercent?.let { out.add("volume=$it%") }
        for (e in timers.entries) out.add("timer(remaining=${LocalActionSpeech.remaining(e.value - nowElapsed)})")
        for (e in alarms.entries) {
            val at = java.util.Calendar.getInstance().apply { timeInMillis = e.value }
            out.add("alarm(at=${LocalActionSpeech.clock24(at.get(java.util.Calendar.HOUR_OF_DAY), at.get(java.util.Calendar.MINUTE))})")
        }
        out.takeLast(MAX_REGISTRY)
    }

    // ---- timer and alarm: the same mechanism, two clocks ----

    private fun timer(durationMs: Long, nowElapsed: Long): Outcome {
        val label = "timer-${++nextId}"
        val at = nowElapsed + durationMs
        val scheduled = schedule(
            atMillis = at,
            usingElapsed = true,
            delayMs = durationMs,
            label = label,
            speech = "timer",
        )
        if (!scheduled) return Outcome(false, LocalActionSpeech.failed("timer"))
        synchronized(lock) { timers[label] = at }
        VoxLog.d("event=local-action kind=timer ms=$durationMs")
        return Outcome(true, LocalActionSpeech.timerSet(durationMs))
    }

    private fun alarm(hour: Int, minute: Int, nowEpoch: Long): Outcome {
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = nowEpoch
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= nowEpoch + 1_000L) cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
        val at = cal.timeInMillis
        val label = "alarm-${++nextId}"
        val scheduled = schedule(
            atMillis = at,
            usingElapsed = false,
            delayMs = at - nowEpoch,
            label = label,
            speech = "alarm",
        )
        if (!scheduled) return Outcome(false, LocalActionSpeech.failed("alarm"))
        synchronized(lock) { alarms[label] = at }
        VoxLog.d("event=local-action kind=alarm at=${LocalActionSpeech.clock24(hour, minute)}")
        return Outcome(true, LocalActionSpeech.alarmSet(hour, minute))
    }

    /**
     * Schedule [label] to fire at [atMillis] on the chosen clock. The listener form is used
     * deliberately: it needs no "Alarms & reminders" grant, and its one limitation — it lives in
     * this process — is exactly the lifetime a timer set during a call has.
     */
    private fun schedule(atMillis: Long, usingElapsed: Boolean, delayMs: Long, label: String, speech: String): Boolean {
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (am != null) {
            try {
                val type = if (usingElapsed) AlarmManager.ELAPSED_REALTIME_WAKEUP else AlarmManager.RTC_WAKEUP
                am.setExact(type, atMillis, label, { ring(label, speech) }, main)
                return true
            } catch (t: Throwable) {
                // Not a claim of success: fall back to this process's own clock below. If that fails
                // too the caller is told the timer could not be set.
                VoxLog.w("event=local-action alarm-api-fallback kind=$speech reason=${t.javaClass.simpleName}")
            }
        }
        return try {
            main.postDelayed({ ring(label, speech) }, delayMs.coerceAtLeast(0L))
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun ring(label: String, kind: String) {
        synchronized(lock) { timers.remove(label); alarms.remove(label) }
        VoxLog.d("event=local-action ring kind=$kind")
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

    // ---- do not disturb ----

    private fun dnd(on: Boolean): Outcome {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (nm == null || !nm.isNotificationPolicyAccessGranted) {
            VoxLog.d("event=local-action kind=dnd ok=false reason=no-policy-access")
            return Outcome(false, LocalActionSpeech.dndDenied())
        }
        nm.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_NONE
            else NotificationManager.INTERRUPTION_FILTER_ALL)
        synchronized(lock) { dndOn = on }
        VoxLog.d("event=local-action kind=dnd on=$on")
        return Outcome(true, LocalActionSpeech.dnd(on))
    }

    // ---- volume ----

    private fun volume(step: Int = 0, percent: Int? = null): Outcome {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return Outcome(false, LocalActionSpeech.failed("volume"))
        val stream = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        val target = if (percent != null) Math.round(percent / 100f * max)
        else (am.getStreamVolume(stream) + step).coerceIn(0, max)
        val next = target.coerceIn(0, max)
        return try {
            am.setStreamVolume(stream, next, 0)
            val pct = (next * 100) / max
            synchronized(lock) { volumePercent = pct }
            VoxLog.d("event=local-action kind=volume percent=$pct")
            Outcome(true, LocalActionSpeech.volume(pct))
        } catch (t: SecurityException) {
            // Do Not Disturb can own the stream on some devices.
            VoxLog.d("event=local-action kind=volume ok=false reason=dnd-owned")
            Outcome(false, LocalActionSpeech.volumeDenied())
        }
    }

    companion object {
        private const val CHANNEL = "vox_timers"
        private const val NOTIFICATION_ID = 2
        /** At most this many entries ride B1's `local_actions:` line. */
        const val MAX_REGISTRY = PresenceContext.MAX_ACTIONS
    }
}
