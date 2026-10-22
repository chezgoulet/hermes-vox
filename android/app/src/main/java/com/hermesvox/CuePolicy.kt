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

    /** #251: when cues may sound at all — [SCOPE_ALWAYS], or [SCOPE_PRESENCE_OFF] (the
     *  default): while Presence is on its asides already occupy the ambient channel, and a
     *  cue would double it. The owner's read is that a caller wants one or the other. */
    const val PREF_SCOPE = "sound_cue_scope"
    const val SCOPE_ALWAYS = "always"
    const val SCOPE_PRESENCE_OFF = "presence_off"
    const val DEFAULT_SCOPE = SCOPE_PRESENCE_OFF
    /** #251: a soft ping, repeated while the agent works, so a pocketed call says it is alive. */
    const val PREF_PING = "sound_cue_ping"
    const val DEFAULT_PING = true
    /** #251: seconds without any input or output before a call checks in; 0 = never. */
    const val PREF_IDLE_S = "sound_cue_idle_s"
    const val DEFAULT_IDLE_S = 120

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
        val scope: String = DEFAULT_SCOPE,
        val presenceOn: Boolean = false,
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
        // #251: one ambient channel at a time — Presence's asides, or the cues.
        if (w.scope == SCOPE_PRESENCE_OFF && w.presenceOn && !w.preview) return Decision(Action.SILENT, 0f, "presence")
        if (w.dnd) return Decision(Action.SILENT, 0f, "dnd")
        if (w.ringerMode == RINGER_SILENT && !w.silentOptIn) return Decision(Action.SILENT, 0f, "silent")

        val level = w.levelPercent.coerceIn(0, 100) / 100f
        if (w.ringerMode == RINGER_VIBRATE) return hapticOrSilent(w, "vibrate")
        if (w.mediaVolume <= 0 || level <= 0f) return hapticOrSilent(w, "inaudible")
        return Decision(Action.AUDIO, level, if (w.preview) "preview" else "heard")
    }

    /**
     * #251: the two ambient cues' timing, pure so the cadence is proven off-device.
     *
     * The ping repeats every [PING_EVERY_MS] while the agent works and stops the moment it
     * does not; it is played at [PING_GAIN] of the cue level, because it is a pulse, not an
     * event. The idle check-in fires once per silent stretch: after [idleS] seconds with no
     * input and no output in a live call, then not again until something happens.
     */
    object Ambient {
        const val PING_EVERY_MS = 6_000L
        const val PING_GAIN = 0.35f

        fun pingDue(working: Boolean, lastPingAt: Long, now: Long): Boolean =
            working && now - lastPingAt >= PING_EVERY_MS

        fun idleDue(callLive: Boolean, idleS: Int, lastActivityAt: Long, now: Long, firedThisStretch: Boolean): Boolean =
            callLive && idleS > 0 && !firedThisStretch && now - lastActivityAt >= idleS * 1000L
    }

    private fun hapticOrSilent(w: World, why: String): Decision =
        if (w.hapticsEnabled) Decision(Action.HAPTIC, 0f, "$why-haptic")
        else Decision(Action.SILENT, 0f, why)
}
