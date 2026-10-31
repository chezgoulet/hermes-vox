package com.hermesvox

/**
 * What changed in this version, and what the user has to do about it.
 *
 * The notes ship inside the APK rather than being fetched: an update banner that
 * needs the network to explain itself is useless on the launch where the network
 * is exactly what broke. Entries live here, next to the version that ships them,
 * and are covered by unit tests.
 *
 * Alerts are for things a user must act on — a breaking change, a new permission,
 * a migration — not for marketing. An alert carries a short body and one action
 * the app can route itself.
 */
internal enum class AlertAction { ENTITY, MODELS, URL, NONE }

internal data class ReleaseAlert(
    val id: String,
    val title: String,
    val body: String,
    val action: AlertAction,
    val label: String,
    val url: String = "",
)

internal data class ReleaseNote(
    val versionCode: Int,
    val versionName: String,
    val title: String,
    val bullets: List<String>,
    val alerts: List<ReleaseAlert> = emptyList(),
)

internal object ReleaseNotes {

    /** 0 means "this install has never recorded a version" — a fresh install. */
    const val PREF_LAST_SEEN = "last_seen_version_code"

    /**
     * Show on an upgrade only. A fresh install meets onboarding, not a changelog,
     * and a downgrade (a sideload of an older APK) must not resurrect old notes.
     */
    fun shouldShow(seenVersion: Int, currentVersion: Int, hasEntry: Boolean): Boolean =
        hasEntry && seenVersion > 0 && currentVersion > seenVersion

    fun forVersion(versionCode: Int): ReleaseNote? = ENTRIES.firstOrNull { it.versionCode == versionCode }

    val ENTRIES: List<ReleaseNote> = listOf(
        ReleaseNote(
            versionCode = 142,
            versionName = "0.8.1",
            title = "The headset release",
            bullets = listOf(
                "A paired Bluetooth, wired or USB headset becomes the call's route — at line open, and again if one is attached mid-call.",
                "Nightlies are tagged at the commit they were built from and install over the release.",
                "The microphone, speech recognition and voice models behave exactly as before.",
            ),
        ),

        ReleaseNote(
            versionCode = 143,
            versionName = "0.9.0",
            title = "The HTTPS release",
            bullets = listOf(
                "HTTPS to Hermes Endpoints is now enforced. A plain-http gateway — including a bare 192.168.x.x address — is refused, with an error that says how to fix it. A tailnet MagicDNS name still works over plain HTTP.",
                "Barge-in improvements: talking over the reply stops it in a fraction of a second, and the threshold is measured from your phone's own speaker instead of assumed.",
                "The entity no longer mistakes its own greeting for you, and the spoken text keeps pace with the voice instead of jumping to the end.",
                "Sound cues: the being's states now have a voice of their own, generated from its motion. Settings → Sound cues.",
                "The connection test stops calling a working gateway failed.",
                "Wake word is here as a preview — you can record your takes; nothing listens yet.",
            ),
            alerts = listOf(
                ReleaseAlert(
                    id = "https-only",
                    title = "Your gateway must use HTTPS",
                    body = "Android now blocks plain HTTP for anything but a *.ts.net name. On the gateway host run `tailscale serve --bg 8080`, then enter https://<machine>.<tailnet>.ts.net as the entity endpoint.",
                    action = AlertAction.ENTITY,
                    label = "Open connection settings",
                ),
            ),
        ),

        ReleaseNote(
            versionCode = 144,
            versionName = "0.9.0.1",
            title = "The /new fix",
            bullets = listOf(
                "Starting a new session with /new during a call no longer leaves the call deaf. The call closes and re-opens its microphone itself, in about two seconds — no hang-up needed.",
            ),
        ),

        ReleaseNote(
            versionCode = 145,
            versionName = "0.9.0.2",
            title = "The fallback release",
            bullets = listOf(
                "A phone with no working system text-to-speech engine can now start a call. Replies arrive as text and the status line names the engine failure, instead of the call stopping with \"Voice models failed to load\".",
                "Cold start: the listening indicator now waits until the microphone is actually capturing, and the first attempt retries once if the audio route is still coming up.",
            ),
        ),

        ReleaseNote(
            versionCode = 146,
            versionName = "0.9.0.3",
            title = "The hearing fix",
            bullets = listOf(
                "A phone whose text-to-speech engine could not be reached can now speak. Android 11 and later hide other apps' services unless an app declares that it needs them, and Vox never declared it — so the engine was invisible to the app while every other app on the phone spoke normally.",
                "If no speech engine is available at all, the call still opens as text and the status line names the reason.",
            ),
        ),
    )
}
