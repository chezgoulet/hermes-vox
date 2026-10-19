package com.hermesvox

/**
 * CuePolicy — the pure, emulator-free decision for what a cue may DO right now (#156,
 * D7). No Android imports: every input the phone can offer is reduced to a scalar or a
 * flag here, so the whole matrix (ringer × DND × media volume × system haptics × a
 * speaking TTS) is proven off-device instead of by listening to a handset.
 *
 * THE ORDER IS THE POLICY. Each rule outranks everything below it, and the reason
 * token is logged with every cue so a field report says WHY it was quiet:
 *
 *   1. TTS is speaking        -> SILENT. Never overlap the voice. The reply IS the
 *                                signal; a chime under the first syllable is noise.
 *   2. User switched cues off -> SILENT. (A PREVIEW bypasses this one rule: the user
 *                                tapped the button to hear it on purpose.)
 *   3. Do Not Disturb         -> SILENT. Strictly: the user asked the phone to stop
 *                                interrupting. DND is not "quieter", it is "not now".
 *   4. Ringer SILENT          -> SILENT unless the user opted in. Then cues are heard
 *                                iff media volume and the cue level allow it.
 *   5. Ringer VIBRATE         -> HAPTIC (the phone has already said "sound off, feel
 *                                it"), or SILENT when system haptics are off.
 *   6. Normal ringer          -> AUDIO at the user's level, riding the MEDIA stream, so
 *                                the hardware media volume is the master. If the media
 *                                volume (or the level) is zero the cue would be
 *                                inaudible: fall back to HAPTIC when haptics are on,
 *                                so the being stays legible instead of silently absent.
 *
 * "Silent unless the user opts in" is [World.silentOptIn]; the shipped default is off,
 * matching D7. Sound cues themselves are ON by default for a new install.
 */
object CuePolicy {

    // ---- prefs: the one toggle + the level, and the silent opt-in --------------
    /** Sound cues on/off. Default ON for a new install (D7). */
    const val PREF_ENABLED = "sound_cues"
    /** The opt-in that lets cues be heard while the ringer is SILENT. Default off. */
    const val PREF_SILENT = "sound_cues_silent"
    /** Cue level, percent of the media stream. Default [DEFAULT_LEVEL]. */
    const val PREF_LEVEL = "sound_cue_level"

    const val DEFAULT_ENABLED = true
    const val DEFAULT_SILENT = false
    const val DEFAULT_LEVEL = 70

    // ---- AudioManager.RINGER_MODE_* mirrored so this file stays pure JVM ------
    const val RINGER_SILENT = 0
    const val RINGER_VIBRATE = 1
    const val RINGER_NORMAL = 2

    /**
     * The live world, as the phone reports it. Every field has a default so a test can
     * name only the axis it cares about.
     *
     * @param enabled        the Settings toggle ([PREF_ENABLED])
     * @param silentOptIn    "cues when the ringer is silent" ([PREF_SILENT])
     * @param levelPercent   the Settings level, 0..100 ([PREF_LEVEL])
     * @param mediaVolume    AudioManager.getStreamVolume(STREAM_MUSIC), 0 = muted
     * @param ringerMode     AudioManager.getRingerMode()
     * @param dnd            the interruption filter is not ALL
     * @param hapticsEnabled Settings.System.HAPTIC_FEEDBACK_ENABLED and a vibrator exists
     * @param ttsSpeaking    the being's own voice is playing right now
     * @param preview        a user-tapped preview, not a turn transition
     */
    data class World(
        val enabled: Boolean = DEFAULT_ENABLED,
        val silentOptIn: Boolean = DEFAULT_SILENT,
        val levelPercent: Int = DEFAULT_LEVEL,
        val mediaVolume: Int = 10,
        val ringerMode: Int = RINGER_NORMAL,
        val dnd: Boolean = false,
        val hapticsEnabled: Boolean = true,
        val ttsSpeaking: Boolean = false,
        val preview: Boolean = false,
    )

    /** What the cue becomes. [SILENT] is a real answer, not a failure. */
    enum class Action { AUDIO, HAPTIC, SILENT }

    /**
     * @param action AUDIO | HAPTIC | SILENT
     * @param volume 0..1 AudioTrack volume for AUDIO (the media stream applies on top)
     * @param reason the log token for why this action won
     */
    data class Decision(val action: Action, val volume: Float, val reason: String)

    fun decide(w: World): Decision {
        if (w.ttsSpeaking) return Decision(Action.SILENT, 0f, "tts")
        if (!w.enabled && !w.preview) return Decision(Action.SILENT, 0f, "off")
        if (w.dnd) return Decision(Action.SILENT, 0f, "dnd")
        if (w.ringerMode == RINGER_SILENT && !w.silentOptIn) return Decision(Action.SILENT, 0f, "silent")

        val level = w.levelPercent.coerceIn(0, 100) / 100f
        if (w.ringerMode == RINGER_VIBRATE) return hapticOrSilent(w, "vibrate")
        if (w.mediaVolume <= 0 || level <= 0f) return hapticOrSilent(w, "inaudible")
        return Decision(Action.AUDIO, level, if (w.preview) "preview" else "heard")
    }

    private fun hapticOrSilent(w: World, why: String): Decision =
        if (w.hapticsEnabled) Decision(Action.HAPTIC, 0f, "$why-haptic")
        else Decision(Action.SILENT, 0f, why)
}
