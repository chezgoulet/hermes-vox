package com.hermesvox

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * PhoneApprovals — where an `uninstall` or `file_offer` card is raised on Android.
 *
 * Two surfaces, because a full-screen dialog cannot be started from the background:
 *
 *  * **Vox in front** — the existing approval card in [MainActivity], rendered from the same
 *    [com.hermesvox.core.ApprovalPrompt] model and answered by tap or by voice.
 *  * **Vox not in front** — a heads-up notification with **Approve** and **Deny** actions. Tapping
 *    the body opens Vox. **Approve opens Vox and is answered from the activity**, because the
 *    uninstall it allows is a platform activity start and Android 10+ blocks one from the
 *    background without throwing (B2). Deny answers in place; it starts nothing.
 *
 * **The notification is private and its yes needs an unlocked phone.** [PhoneApprovalNotice]
 * builds it `VISIBILITY_PRIVATE`, so the lock screen does not show the file's name or the app
 * being removed. The buttons can still be tapped while locked, so [answer] checks the keyguard
 * first: a locked yes is refused with [PhoneApproval.Reason.LOCKED], the id is burnt, and the agent
 * is told why. The owner sees a private "unlock and ask again" notice. A pending card may outlive a
 * screen-off, but it can never be approved from the lock screen; its two-minute expiry still runs.
 *
 * If neither surface can be shown the surface returns false, and the request refuses `unavailable`.
 * A notification the owner has blocked is not a card, so it is never allowed to look like one and
 * then expire into a silent denial.
 *
 * The desk lives in [PhoneEngine]; this object only presents and routes answers. That keeps the
 * protocol pure and testable, and makes this file the one place Android presentation differs.
 */
object PhoneApprovals : PhoneApproval.Surface {

    private const val CHANNEL = "phone_approval"
    private const val CHANNEL_NAME = "Approve an action on your phone"
    private const val NOTIFICATION_ID = 0x7E11
    private const val LOCKED_NOTIFICATION_ID = 0x7E12

    /** What an answer attempt did, so the caller can say "locked" rather than "expired". */
    enum class Outcome { ANSWERED, DENIED, LOCKED, NOT_PENDING }

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

    /**
     * Whether the phone is unlocked enough to give a yes. The keyguard being up, or the device
     * being locked, is enough to refuse: an approval on the lock screen would be whoever holds the
     * phone's decision, not the owner's.
     */
    fun deviceUnlocked(): Boolean {
        val context = appContext ?: return false
        return unlocked(context)
    }

    fun unlocked(context: Context): Boolean {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            ?: return true
        return !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked
    }

    /** An answer from a tap (in-app or notification). */
    fun answer(id: String, approve: Boolean, via: String): Outcome {
        if (approve && !deviceUnlocked()) {
            // The desk records the refusal (it reads the same keyguard), then the owner is told why
            // in a private notice that names neither the file nor the app.
            answerer?.answer(id, "once", via)
            notifyLocked()
            VoxLog.d("event=phone-approval source=$via answered=false reason=locked")
            return Outcome.LOCKED
        }
        val answered = answerer?.answer(id, if (approve) "once" else "deny", via) ?: false
        return when {
            !answered -> Outcome.NOT_PENDING
            approve -> Outcome.ANSWERED
            else -> Outcome.DENIED
        }
    }

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
            val notice = PhoneApprovalNotice.of(card)
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
                .setContentTitle(notice.title)
                .setContentText(notice.text)
                .setVisibility(notice.visibility)
                .setPriority(notice.priority)
                .setCategory(notice.category)
                .setAutoCancel(true)
                .setContentIntent(open)
                .apply {
                    if (notice.actions) {
                        addAction(0, notice.approveLabel, action(context, card.id, approve = true))
                        addAction(0, notice.denyLabel, action(context, card.id, approve = false))
                    }
                }
                .build()
            nm.notify(NOTIFICATION_ID, n)
            true
        } catch (t: Throwable) {
            VoxLog.w("event=phone-approval unavailable reason=${t.javaClass.simpleName}")
            false
        }
    }

    /** The private "unlock and ask again" notice; it never names what was being approved. */
    private fun notifyLocked() {
        val context = appContext ?: return
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val notice = PhoneApprovalNotice.locked()
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle(notice.title)
                .setContentText(notice.text)
                .setVisibility(notice.visibility)
                .setPriority(notice.priority)
                .setCategory(notice.category)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            nm.notify(LOCKED_NOTIFICATION_ID, n)
        } catch (t: Throwable) {
            VoxLog.w("event=phone-approval locked-notice-failed reason=${t.javaClass.simpleName}")
        }
    }

    private fun action(context: Context, id: String, approve: Boolean): PendingIntent =
        if (approve) {
            // Approve starts Vox: the action it authorises opens another activity (Android's
            // uninstall confirmation), which Android refuses from the background (B2). The
            // answer is delivered by MainActivity once it is resumed, so the confirmation
            // appears in front of the owner.
            PendingIntent.getActivity(
                context,
                1,
                Intent(context, MainActivity::class.java)
                    .setAction(ACTION_APPROVE)
                    .putExtra(EXTRA_ID, id)
                    .putExtra(EXTRA_APPROVE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            PendingIntent.getBroadcast(
                context,
                2,
                Intent(context, PhoneApprovalReceiver::class.java)
                    .setAction(ACTION_DENY)
                    .putExtra(EXTRA_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

    const val ACTION_APPROVE = "com.hermesvox.action.PHONE_APPROVAL_APPROVE"
    const val ACTION_DENY = "com.hermesvox.action.PHONE_APPROVAL_DENY"
    const val EXTRA_ID = "com.hermesvox.extra.PHONE_APPROVAL_ID"
    /** Set by the notification's Approve action, so the activity knows to answer on resume. */
    const val EXTRA_APPROVE = "com.hermesvox.extra.PHONE_APPROVAL_APPROVE"
}

/** The notification's two buttons. Explicit intents; the receiver is not exported. */
class PhoneApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(PhoneApprovals.EXTRA_ID).orEmpty()
        if (id.isEmpty()) return
        val approve = intent.action == PhoneApprovals.ACTION_APPROVE
        val outcome = PhoneApprovals.answer(id, approve, "tap")
        VoxLog.d("event=phone-approval source=notification outcome=${outcome.name.lowercase()}")
    }
}
