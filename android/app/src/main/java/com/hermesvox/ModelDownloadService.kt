package com.hermesvox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * ModelDownloadService — keeps model downloads alive when the Models screen is closed or the
 * phone is locked (a `dataSync` foreground service, the platform's sanctioned home for a
 * user-started transfer). It runs [ModelDownloads]' queue, shows the current model's progress in
 * a notification with a Pause action, and stops itself when the queue is empty.
 */
class ModelDownloadService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var lastNotify = 0L

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            ModelDownloads.running()?.let { (spec, _) -> ModelDownloads.cancel(this, spec.id) }
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                startForeground(NOTIF_ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIF_ID, build())
        } catch (e: Exception) {
            // Without foreground status the download still runs while the process lives; it just
            // is not protected from being killed in the background.
            VoxLog.e("event=model-dl-service startForeground-failed err=${e.message}")
        }
        if (wake == null) {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hermesvox:model-dl")
                .also { it.acquire(3 * 60 * 60 * 1000L) }   // bounded: a 2.6 GB model on a slow link
        }
        ModelDownloads.drain(this, onEmpty = { stopSelf() }, onProgress = { refresh() })
        return START_NOT_STICKY
    }

    private fun refresh() {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 1000) return   // the platform rate-limits notification updates
        lastNotify = now
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, build()) } catch (_: Throwable) {}
    }

    private fun build(): Notification {
        val r = ModelDownloads.running()
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, ModelsActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        if (r == null) return b.setContentTitle("Hermes Vox").setContentText("Preparing downloads…").build()
        val (spec, st) = r
        b.setContentTitle("Downloading ${spec.name}")
            .addAction(0, "Pause", PendingIntent.getService(this, 1,
                Intent(this, ModelDownloadService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE))
        when (st) {
            is ModelDownloads.State.Running -> when (st.phase) {
                ModelDownloader.Phase.DOWNLOAD -> {
                    val pct = if (st.total > 0) (st.done * 100 / st.total).toInt() else 0
                    b.setProgress(100, pct, st.total <= 0)
                        .setContentText("$pct% · ${st.done / 1048576} / ${st.total / 1048576} MB")
                }
                ModelDownloader.Phase.WAITING_NETWORK -> b.setProgress(0, 0, true)
                    .setContentText("Waiting for network — ${st.done / 1048576} MB kept")
                ModelDownloader.Phase.VERIFY -> b.setProgress(0, 0, true).setContentText("Verifying…")
                ModelDownloader.Phase.UNPACK -> b.setProgress(0, 0, true).setContentText("Installing…")
            }
            else -> b.setProgress(0, 0, true).setContentText("Starting…")
        }
        return b.build()
    }

    override fun onDestroy() {
        try { wake?.release() } catch (_: Throwable) {}
        wake = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "hermes_vox_downloads"
        private const val NOTIF_ID = 42
        private const val ACTION_PAUSE = "com.hermesvox.action.PAUSE_DOWNLOAD"

        fun start(context: Context) {
            val i = Intent(context, ModelDownloadService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
                else context.startService(i)
            } catch (e: Exception) {
                VoxLog.e("event=model-dl-service start-failed err=${e.message}")
            }
        }
    }
}
