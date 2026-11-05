package com.hermesvox

import androidx.core.app.NotificationCompat

/**
 * PhoneApprovalNotice — the content and, above all, the **privacy** of the approval notification.
 *
 * The card is posted as a heads-up when Vox has no screen. By default a notification is visible
 * on the lock screen, so its title would show the file's name or the app being removed to anyone
 * holding the phone. [VISIBILITY_PRIVATE] is the rule: the lock screen does not disclose what is
 * being approved. It is a floor, not a default the caller can forget — every builder starts there.
 *
 * Actionability is enforced separately, by the keyguard check in [PhoneApprovals]: a private
 * notification hides its content, but its buttons can still be tapped, so the tap must refuse
 * unless the device is unlocked.
 *
 * Pure JVM (only NotificationCompat's int constants), so [PhoneApprovalTest] can assert the
 * builder without rendering a notification or owning a phone.
 */
class PhoneApprovalNotice private constructor(
    val title: String,
    val text: String,
    val visibility: Int,
    val priority: Int,
    val category: String,
    val approveLabel: String,
    val denyLabel: String,
    val actions: Boolean,
) {

    /** The builder for one approval. Every field starts at its safe value. */
    class Builder(private val title: String) {
        var text: String = TWO_MINUTES
        var visibility: Int = NotificationCompat.VISIBILITY_PRIVATE
        var priority: Int = NotificationCompat.PRIORITY_HIGH
        var category: String = NotificationCompat.CATEGORY_REMINDER
        var approveLabel: String = "APPROVE"
        var denyLabel: String = "DENY"
        var actions: Boolean = true

        fun build(): PhoneApprovalNotice = PhoneApprovalNotice(
            title = title,
            text = text,
            visibility = visibility,
            priority = priority,
            category = category,
            approveLabel = approveLabel,
            denyLabel = denyLabel,
            actions = actions,
        )
    }

    companion object {
        const val TWO_MINUTES = "Two minutes. Dismissing or silence is a no."
        const val LOCKED_TITLE = "Approval not given"
        const val LOCKED_TEXT = "Locked — nothing was approved. Unlock your phone, then ask again."

        /** The card's notification: private, high priority, with the same two answers as the card. */
        fun of(card: PhoneApproval.Card): PhoneApprovalNotice = Builder(card.line).build()

        /**
         * What the owner sees after answering from a locked phone. It names no app, no file and no
         * action, so the lock screen still learns nothing about what was being approved.
         */
        fun locked(): PhoneApprovalNotice = Builder(LOCKED_TITLE).apply {
            text = LOCKED_TEXT
            actions = false
            priority = NotificationCompat.PRIORITY_DEFAULT
        }.build()

        /** The privacy rule, named once so a test and the builder read the same value. */
        val PRIVATE: Int get() = NotificationCompat.VISIBILITY_PRIVATE
    }
}
