package com.hermesvox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * MediaListen — the notification-listener access `now_playing` needs to name the session, and the
 * one screen where the owner grants it.
 *
 * **The access is a grant, not a wall.** `MediaSessionManager.getActiveSessions` needs the app to
 * be the user's enabled notification listener (or hold `MEDIA_CONTENT_CONTROL`, which a sideloaded
 * app cannot). That is a special access only the owner can give, on Android's own screen — so the
 * phone's job is to show whether it is held and open that screen, not to design around its absence.
 * [MediaListenerService] is the (empty) listener the grant attaches to; it reads nothing.
 *
 * **The boundary is still the platform's.** Without the grant, [playback] returns `known:false`
 * with the cheap `isMusicActive` reading and no invented title. With the grant, the package and
 * title are the active session's own metadata; a phone whose players publish no session still gets
 * `known:false`, because there is nothing to name.
 */
class MediaListenerService : NotificationListenerService()

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
