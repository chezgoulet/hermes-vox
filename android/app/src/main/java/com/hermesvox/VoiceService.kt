package com.hermesvox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hermesvox.mobile.HermesSession

/**
 * VoiceService — the background foreground service that owns the voice turn
 * loop (microphone type on Android 14+). The app keeps listening + responding
 * even when the Activity isn't in the foreground. Started/stopped by the UI;
 * START_STICKY + a persistent notification keeps it alive.
 */
class VoiceService : Service() {
    private var session: HermesSession? = null
    private var controller: VoiceController? = null
    private val CHANNEL = "hermes_vox_voice"

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val prefs = getSharedPreferences("hv", Context.MODE_PRIVATE)
        val u = prefs.getString("url", "").orEmpty()
        val k = prefs.getString("key", "").orEmpty()
        val m = prefs.getString("model", "hermes-agent").orEmpty()
        if (u.isNotBlank() && k.isNotBlank()) {
            session = HermesSession(u, k, m)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification())
        startVoice()
        return START_STICKY
    }

    private fun startVoice() {
        val s = session ?: return
        controller = VoiceController(this, s)
        controller?.start(listener, true)
    }

    override fun onDestroy() {
        controller?.stop(); controller = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Hermes Vox voice", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Hermes Vox")
            .setContentText("Listening for the entity…")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private val listener = object : VoiceController.Listener {
        override fun onState(s: String) {}
        override fun onDelta(t: String) {}
        override fun onLog(l: String) {}
        override fun onReply(r: String) {}
        override fun onError(e: String) {}
    }

    companion object {
        fun start(context: Context) {
            val i = Intent(context, VoiceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }
        fun stop(context: Context) { context.stopService(Intent(context, VoiceService::class.java)) }
    }
}
