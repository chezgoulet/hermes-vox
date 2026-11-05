package com.hermesvox

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * PhoneApprovals — where an `uninstall` card is raised on Android.
 *
 * Two surfaces, because a full-screen dialog cannot be started from the background:
 *
 *  * **Vox in front** — the existing approval card in [MainActivity], rendered from the same
 *    [com.hermesvox.core.ApprovalPrompt] model and answered by tap or by voice.
 *  * **Vox not in front** — a heads-up notification with **Approve** and **Deny** actions. Tapping
 *    the body opens Vox; the actions answer without opening it.
 *
 * If neither can be shown the surface returns false, and the request refuses `unavailable`. A
 * notification the owner has blocked is not a card, so it is never allowed to look like one and
 * then expire into a silent denial.
 *
 * The desk lives in [PhoneEngine]; this object only presents and routes answers. That keeps the
 * protocol pure and testable, and makes this file the one place Android presentation differs.
 */
object PhoneApprovals : PhoneApproval.Surface {

    private const val CHANNEL = "phone_approval"
    private const val CHANNEL_NAME = "Approve removing an app"
    private const val NOTIFICATION_ID = 0x7E11

    /** The in-app surface, attached while an activity is in front. */
    interface Ui {
        /** Show [card]. False when this surface cannot render it right now. */
        fun show(card: PhoneApproval.Card): Boolean

        /** The card is gone: answered, expired, or replaced. */
        fun withdraw(id: String)
    }

    /** The desk side: the one place an answer is delivered. Bound by [LocalActions]. */
    interface Answerer {
        /** True when [id] was still waiting. [choice] is `once` or `deny`. */
        fun answer(id: String, choice: String, via: String): Boolean

        /** Expire cards whose two minutes are up. */
        fun tick(now: Long)
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val expiry = Runnable {
        try { answerer?.tick(System.currentTimeMillis()) } catch (_: Throwable) {}
    }

    @Volatile private var appContext: Context? = null
    @Volatile private var answerer: Answerer? = null
    @Volatile var ui: Ui? = null

    fun install(context: Context) {
        appContext = context.applicationContext ?: context
    }

    fun bind(answerer: Answerer) {
        this.answerer = answerer
    }

    /** An answer from a tap (in-app or notification). */
    fun answer(id: String, approve: Boolean, via: String): Boolean =
        answerer?.answer(id, if (approve) "once" else "deny", via) ?: false

    override fun raise(card: PhoneApproval.Card): Boolean {
        // A resumed activity is the only time a dialog can be started; everything else is the
        // notification. AppFocus is maintained by the application's lifecycle callbacks.
        val shown = if (AppFocus.inFront) {
            ui?.let { it.show(card) } ?: false
        } else {
            notify(card)
        }
        if (shown) {
            handler.removeCallbacks(expiry)
            handler.postDelayed(
                expiry,
                (card.expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0) + 50,
            )
        }
        return shown
    }

    override fun withdraw(card: PhoneApproval.Card) {
        handler.removeCallbacks(expiry)
        ui?.withdraw(card.id)
        val context = appContext ?: return
        try {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.cancel(NOTIFICATION_ID)
        } catch (_: Throwable) {
        }
    }

    private fun notify(card: PhoneApproval.Card): Boolean {
        val context = appContext ?: return false
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return false
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val prompt = card.prompt()
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .putExtra(EXTRA_ID, card.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(prompt.line)
                .setContentText("Two minutes. Dismissing or silence is a no.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(open)
                .addAction(0, "APPROVE", action(context, card.id, approve = true))
                .addAction(0, "DENY", action(context, card.id, approve = false))
                .build()
            nm.notify(NOTIFICATION_ID, n)
            true
        } catch (t: Throwable) {
            VoxLog.w("event=phone-approval unavailable reason=${t.javaClass.simpleName}")
            false
        }
    }

    private fun action(context: Context, id: String, approve: Boolean): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            if (approve) 1 else 2,
            Intent(context, PhoneApprovalReceiver::class.java)
                .setAction(if (approve) ACTION_APPROVE else ACTION_DENY)
                .putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    const val ACTION_APPROVE = "com.hermesvox.action.PHONE_APPROVAL_APPROVE"
    const val ACTION_DENY = "com.hermesvox.action.PHONE_APPROVAL_DENY"
    const val EXTRA_ID = "com.hermesvox.extra.PHONE_APPROVAL_ID"
}

/** The notification's two buttons. Explicit intents; the receiver is not exported. */
class PhoneApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(PhoneApprovals.EXTRA_ID).orEmpty()
        if (id.isEmpty()) return
        val approve = intent.action == PhoneApprovals.ACTION_APPROVE
        val answered = PhoneApprovals.answer(id, approve, "tap")
        VoxLog.d("event=phone-approval source=notification answered=$answered")
    }
}
