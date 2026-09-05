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

/**
 * VoiceService — the foreground keepalive for the voice line (microphone type on
 * Android 14+). It keeps the process + the mic-type notification alive so the
 * Activity's single VoiceController can access the mic and survive backgrounding.
 * It does NOT own a VoiceController (single-owner). Started/stopped by the UI.
 */
class VoiceService : Service() {
    // SINGLE-OWNER (best-practice, 2026-08-26): this foreground service ONLY keeps
    // the process + the microphone-type notification alive so the Activity's ONE
    // VoiceController can run (it reuses the entity session). It does NOT create
    // its own VoiceController — a second VoiceController/second AudioRecord was
    // the realtime double-fire root.
    private val CHANNEL = "hermes_vox_voice"

    override fun onCreate() {
        super.onCreate()
        createChannel()
        VoxLog.d("event=fg-service onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        VoxLog.d("event=fg-service start id=$startId")
        return try { startForeground(1, buildNotification()); START_STICKY }
        catch (e: Exception) { VoxLog.e("event=fg-service startForeground-failed err=${e.message}"); START_NOT_STICKY }
    }

    override fun onDestroy() {
        VoxLog.d("event=fg-service destroy")
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

    companion object {
        fun start(context: Context) {
            VoxLog.d("event=fg-service-start requested sdk=${Build.VERSION.SDK_INT}")
            try {
                val i = Intent(context, VoiceService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
                else context.startService(i)
                VoxLog.d("event=fg-service-start ok")
            } catch (e: Exception) { VoxLog.e("event=fg-service-start-failed err=${e.message}") }
        }
        fun stop(context: Context) { context.stopService(Intent(context, VoiceService::class.java)) }
    }
}
