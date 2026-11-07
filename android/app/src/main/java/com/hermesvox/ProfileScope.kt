package com.hermesvox

/**
 * ProfileScope — which settings belong to a profile and which belong to the phone.
 *
 * The owner's rule (0.9.1 follow-up): a profile is not only *where* the agent lives, it is
 * *how it presents* — **its voice, its model and its look**. Before this file those three were
 * device-wide, so switching from `home` to `work` kept the being, the register and the engine
 * of the profile you just left, which made the switcher a lie about "the agent you are
 * talking to".
 *
 * What a profile now carries:
 *
 *  * **model** — the record's own `model` (and `provider`), which have been per profile since
 *    #174: a switch already writes them through [Profiles.toPrefs]. They are named here as a
 *    [Group] so the doc, the switcher line and the Settings rows all say the same three
 *    things, but their storage stays the record's fields — moving them would break every
 *    saved record for no gain.
 *  * **voice** — the TTS engine ([tts]), the delivery register ([voice]) and each engine's
 *    chosen speaker id ([VoiceSpeakers] keys). The speaker id is per engine *inside* a
 *    profile, so Supertonic's id 7 and Piper's id 7 never mix.
 *  * **look** — the being: the visual category and its five scalars, the cycle-all switch,
 *    the resting shape and whether it cycles, and the three state shapes
 *    ([AvatarView.KEY_SHAPE_SPEAKING] and friends).
 *
 * What stays device-wide, deliberately, and is written down in `docs/PRESENCE.md`:
 * the connection itself (that *is* the profile), the Alpha gate, Presence's own switches
 * (its cadence, its asides, its voice, its reasoning effort), the screen (theme, layout,
 * keep-screen-on), the microphone and hearing, the sound cues, the wake word, and everything
 * about the phone. A profile is an agent, not a second phone.
 *
 * ## Storage and the fallback
 *
 * [Profiles.Profile.own] holds this profile's values as canonical strings; the live `hv` prefs
 * remain the store the rest of the app reads, and they are also the **device-wide default**: a
 * key a profile has no value for is simply not written on a switch, so the device-wide value
 * stays in force. That is the whole fallback rule, and it is why a profile written by an older
 * build (no [Profiles.Profile.own] at all) keeps working untouched.
 *
 * [migrate] is the one-time inheritance: on first load every profile with no values of its own
 * takes the device-wide ones, and from then on they are its own. [capture] is the other half —
 * a change made in Settings belongs to the profile that was active when it was made, because a
 * switch records the live values into the profile it is leaving.
 *
 * Pure JVM: the registry rules, the migration and the fallback are proven off-device
 * ([ProfileScopeTest]); the Android edge is three typed reads and one typed write
 * ([ScopedPrefs]).
 */
object ProfileScope {

    /** How a scoped value is stored in the live prefs, and therefore how a switch writes it. */
    enum class Kind { TEXT, FLAG, NUMBER, WHOLE }

    /** The three things a profile carries, in the words the UI uses. */
    enum class Group(val label: String) {
        VOICE("voice"),
        LOOK("look"),
    }

    /** One setting a profile can own. [key] is the live pref name *and* the key in `own`. */
    data class Scoped(val key: String, val kind: Kind, val group: Group)

    /**
     * Every key a profile owns, in [Group] order. The model is not here: it is the record's
     * `model`/`provider` fields (see the doc above).
     */
    val KEYS: List<Scoped> = listOf(
        // ---- voice -------------------------------------------------------------------
        Scoped("tts", Kind.TEXT, Group.VOICE),
        Scoped("voice", Kind.TEXT, Group.VOICE),
        Scoped(VoiceSpeakers.key(SherpaVoice.PIPER), Kind.WHOLE, Group.VOICE),
        Scoped(VoiceSpeakers.key(SherpaVoice.SUPERTONIC), Kind.WHOLE, Group.VOICE),
        // The fr-FR voices (#477): their speaker selection is a profile's for the same
        // reason as the en-US one — UPMC really has two, and a switch must not silently
        // hand a profile another profile's speaker.
        Scoped(VoiceSpeakers.key(SherpaVoice.PIPER_SIWIS), Kind.WHOLE, Group.VOICE),
        Scoped(VoiceSpeakers.key(SherpaVoice.PIPER_TOM), Kind.WHOLE, Group.VOICE),
        Scoped(VoiceSpeakers.key(SherpaVoice.PIPER_UPMC), Kind.WHOLE, Group.VOICE),
        // ---- look --------------------------------------------------------------------
        Scoped(VisualStyle.KEY_CATEGORY, Kind.TEXT, Group.LOOK),
        Scoped(VisualStyle.KEY_ENERGY, Kind.NUMBER, Group.LOOK),
        Scoped(VisualStyle.KEY_GLOW, Kind.NUMBER, Group.LOOK),
        Scoped(VisualStyle.KEY_DENSITY, Kind.NUMBER, Group.LOOK),
        Scoped(VisualStyle.KEY_SPEED, Kind.NUMBER, Group.LOOK),
        Scoped(VisualStyle.KEY_TRAIL, Kind.NUMBER, Group.LOOK),
        Scoped(VisualStyle.KEY_HUE, Kind.NUMBER, Group.LOOK),
        Scoped(VisualStyle.KEY_CYCLE_ALL, Kind.FLAG, Group.LOOK),
        // The idle shape and its rotation, and the three state shapes. AvatarView's keys are
        // `const val`s, so they are inlined here: this file never loads an Android class.
        Scoped(AvatarView.KEY_SHAPE_SPEAKING, Kind.TEXT, Group.LOOK),
        Scoped(AvatarView.KEY_SHAPE_LISTENING, Kind.TEXT, Group.LOOK),
        Scoped(AvatarView.KEY_SHAPE_THINKING, Kind.TEXT, Group.LOOK),
        Scoped("particles_theme", Kind.TEXT, Group.LOOK),
        Scoped("particles_cycle", Kind.FLAG, Group.LOOK),
    )

    private val BY_KEY: Map<String, Scoped> = KEYS.associateBy { it.key }

    /** The scoped meaning of [key], or null when the key is device-wide (or not a setting). */
    fun scoped(key: String): Scoped? = BY_KEY[key]

    /** The keys of one [Group], in [KEYS] order. */
    fun keys(group: Group): List<String> = KEYS.filter { it.group == group }.map { it.key }

    /**
     * Every key this profile owns a value for, read from the live prefs by [read]. A blank or
     * absent value is not recorded at all: "no value of its own" is exactly what makes the
     * device-wide default apply, so it must stay absent rather than become "".
     */
    fun capture(read: (Scoped) -> String?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (k in KEYS) {
            val v = read(k)?.trim().orEmpty()
            if (v.isNotEmpty()) out[k.key] = v
        }
        return out
    }

    /**
     * [p] with every scoped key it has no value for filled from [deviceWide]. Once a profile has
     * been through this, its values are its own: a later change to the device-wide value does not
     * move it. Values it already owns are never overwritten, and an unknown entry in `own` (one a
     * future build wrote) is carried through untouched.
     */
    fun adopt(p: Profiles.Profile, deviceWide: (Scoped) -> String?): Profiles.Profile {
        val own = LinkedHashMap(p.own)
        for (k in KEYS) {
            if (!own[k.key].isNullOrBlank()) continue
            val v = deviceWide(k)?.trim().orEmpty()
            if (v.isNotEmpty()) own[k.key] = v
        }
        return p.copy(own = own)
    }

    /**
     * The first load: every profile with no values of its own inherits the device-wide ones, once
     * ([adopt] is what makes them its own). A profile that already owns something is left exactly
     * as it is — inheriting again would undo an edit, which is the failure this rule exists to
     * prevent.
     */
    fun migrate(list: List<Profiles.Profile>, deviceWide: (Scoped) -> String?): List<Profiles.Profile> =
        list.map { if (it.own.isEmpty()) adopt(it, deviceWide) else it }

    /**
     * Everything a switch writes into the live prefs: the whole connection ([Profiles.toPrefs] —
     * url, key, model, provider, agent name, scope) and the profile's voice and look. Call it on
     * the *adopted* profile ([adopt]), so a profile with no value of its own writes nothing and
     * the device-wide value stays in force.
     */
    fun switchWrites(p: Profiles.Profile, deviceWide: (Scoped) -> String?): Map<String, String> {
        val adopted = adopt(p, deviceWide)
        return LinkedHashMap(adopted.own).apply { putAll(Profiles.toPrefs(adopted)) }
    }

    /** The voice in one line for the switcher: the engine, then the register. */
    fun voiceLine(own: Map<String, String>): String {
        val token = own["tts"]?.trim()?.lowercase().orEmpty()
        val engine = when {
            token == "system" -> "System voice"
            else -> SherpaVoice.fromEngineToken(token)?.let {
                if (it == SherpaVoice.PIPER) "Piper" else it.displayName
            } ?: ""
        }
        // "system" is the *register's* token for the unmarked register; the Settings row calls
        // it "Natural" and so does this line, so the two cannot read as different settings.
        val register = when (own["voice"]?.trim()?.lowercase()) {
            "bright" -> "bright"
            "deep" -> "deep"
            "system" -> "natural"
            else -> ""
        }
        return listOf(engine, register).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /**
     * The line under a profile's name in the switcher: who it is, what it thinks with, how it
     * speaks. A choice has to be informed without opening Settings, and these three are the
     * three things a switch changes.
     */
    fun detail(p: Profiles.Profile): String {
        val model = ModelChoices.displayName(p.model)
        val agent = p.agentName.trim().ifBlank { model }
        return listOf(agent, model, voiceLine(p.own))
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" · ")
    }
}
