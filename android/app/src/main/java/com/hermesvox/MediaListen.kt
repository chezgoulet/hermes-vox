package com.hermesvox

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * MediaListen — the notification-listener access `now_playing` and `notifications` share, and the
 * one screen where the owner grants it.
 *
 * **The access is a grant, not a wall.** `MediaSessionManager.getActiveSessions` needs the app to
 * be the user's enabled notification listener (or hold `MEDIA_CONTENT_CONTROL`, which a sideloaded
 * app cannot). That is a special access only the owner can give, on Android's own screen — so the
 * phone's job is to show whether it is held and open that screen, not to design around its absence.
 *
 * **One listener, two uses, one lifetime rule.** [MediaListenerService] is the app's single entry
 * in Android's notification-access list, so the owner is asked once for one access rather than
 * twice. Media uses the session list. Notifications use posted notifications — but only while a
 * call is live, only while the notifications group is on, and never when the posting package is
 * Vox itself. Outside a call the service records nothing at all; [PhoneNotifications] drops every
 * record and id when the call ends.
 *
 * **The boundary is still the platform's.** Without the grant, [playback] returns `known:false`
 * with the cheap `isMusicActive` reading and no invented title. With the grant, the package and
 * title are the active session's own metadata; a phone whose players publish no session still gets
 * `known:false`, because there is nothing to name.
 */
class MediaListenerService : NotificationListenerService() {

    /**
     * The board is call-scoped, and the group is the owner's consent. Both must hold before a
     * posted notification is read; either one alone is not enough.
     */
    private fun collecting(): Boolean =
        PhoneNotifications.attached() &&
            PhoneGrantStore.state(this, notificationsGroup()) != PhoneGrants.State.OFF

    override fun onListenerConnected() {
        // The service can stay bound across calls. Registering the seed lets a call start ask for
        // the notifications already on the phone, not only the ones posted after it began.
        PhoneNotifications.setResync { seed() }
        // The listener is the only thing that can cancel: the board stays pure JVM and asks here.
        PhoneNotifications.setDismisser { key ->
            try {
                cancelNotification(key)
                true
            } catch (_: Throwable) {
                false
            }
        }
        seed()
    }

    override fun onListenerDisconnected() {
        PhoneNotifications.setResync(null)
        PhoneNotifications.setDismisser(null)
    }

    override fun onDestroy() {
        PhoneNotifications.setResync(null)
        PhoneNotifications.setDismisser(null)
        super.onDestroy()
    }

    private fun seed() {
        if (!collecting()) return
        val active = try {
            activeNotifications
        } catch (_: Throwable) {
            null
        }
        active?.forEach { post(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || !collecting()) return
        post(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        // Removing Vox's own notification must not touch a record, because none was made for it.
        if (sbn.packageName == packageName) return
        PhoneNotifications.remove(sbn.key)
    }

    private fun post(sbn: StatusBarNotification) {
        val extras = sbn.notification?.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (
            extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras?.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras?.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)
                ?: extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString(" · ")
            )?.toString()
        PhoneNotifications.record(
            packageName = sbn.packageName,
            app = appLabel(sbn.packageName),
            key = sbn.key,
            title = title,
            text = text,
            postedAt = sbn.postTime,
            // An app reading its own notification through the listener is a loop and a confusion.
            own = sbn.packageName == packageName,
        )
    }

    /** The launcher-visible name, falling back to the package when the platform will not say. */
    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) {
        pkg
    }

    private fun notificationsGroup(): PhoneGrants.Group =
        PhoneGrants.GROUPS.first { it.id == "notifications" }
}

object MediaListen {

    /** The listener component the owner enables in system settings. */
    fun component(context: Context): ComponentName =
        ComponentName(context.applicationContext ?: context, MediaListenerService::class.java)

    /**
     * True when this app is the enabled notification listener. API 27 has the platform predicate;
     * below it the enabled-list setting is the same fact in its stored form.
     */
    fun granted(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 27) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as? android.app.NotificationManager ?: return false
            nm.isNotificationListenerAccessGranted(component(context))
        } else {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ).orEmpty()
            val mine = component(context)
            enabled.split(':').any { ComponentName.unflattenFromString(it) == mine }
        }
    } catch (_: Throwable) {
        false
    }

    /** The system screen where the owner grants or revokes notification access. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    /**
     * What the platform will say about playback. [isMusicActive] is the one reading that needs no
     * access; the session list (a package and a title) is only asked for when the grant is held.
     *
     * A session is chosen by the platform's own order: the first one that says it is playing, or
     * else the first one there is. `null` fields are "the session did not publish it", never
     * invented.
     */
    fun playback(
        context: Context,
        listenerGranted: Boolean = granted(context),
        musicActive: Boolean? = null,
    ): PhoneEngine.PhonePlatform.Playback {
        val active = musicActive ?: try {
            (context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager)
                ?.isMusicActive
        } catch (_: Throwable) {
            null
        }
        if (!listenerGranted) {
            return PhoneEngine.PhonePlatform.Playback(
                known = false,
                playing = active,
                packageName = null,
                title = null,
                listenerGranted = false,
            )
        }
        return try {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
                as? MediaSessionManager
            val sessions = manager?.getActiveSessions(component(context)).orEmpty()
            val controller = sessions.firstOrNull {
                it.playbackState?.state == PlaybackState.STATE_PLAYING
            } ?: sessions.firstOrNull()
                ?: return PhoneEngine.PhonePlatform.Playback(
                    known = false,
                    playing = active,
                    packageName = null,
                    title = null,
                    listenerGranted = true,
                )
            val metadata: MediaMetadata? = controller.metadata
            val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            val state = controller.playbackState?.state
            val playing = when (state) {
                null -> active
                PlaybackState.STATE_PLAYING -> true
                PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING ->
                    active ?: true
                PlaybackState.STATE_NONE, PlaybackState.STATE_STOPPED,
                PlaybackState.STATE_PAUSED, PlaybackState.STATE_ERROR -> false
                else -> active
            }
            PhoneEngine.PhonePlatform.Playback(
                known = true,
                playing = playing,
                packageName = controller.packageName,
                title = title,
                listenerGranted = true,
            )
        } catch (t: Throwable) {
            VoxLog.d("event=media-listen unreadable reason=${t.javaClass.simpleName}")
            PhoneEngine.PhonePlatform.Playback(
                known = false,
                playing = active,
                packageName = null,
                title = null,
                listenerGranted = true,
            )
        }
    }
}
