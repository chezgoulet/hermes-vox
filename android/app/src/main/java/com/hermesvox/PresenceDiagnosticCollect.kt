package com.hermesvox

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

/**
 * The Android edge of [PresenceDiagnostic]: reads the settings and the session's measured
 * numbers, and nothing else. Keeping this separate is what lets the exact block be tested
 * on the JVM (PresenceDiagnosticTest) while the real values still come from the running app.
 *
 * There is one door to the block — the Presence row in Settings (#418's command and switch
 * are gone). The phone's facts are read into [Facts] once, the settings into
 * [PresenceDiagnostic.Settings], and [block] joins them; the door assembles nothing of its own.
 */
object PresenceDiagnosticCollect {

    /** The Presence model's catalog id — the only on-device model the block names. */
    const val MODEL_ID = "gemma-e2b"

    /**
     * Everything the phone supplies to the block, in one value: the build, the device, which
     * side of the caller/mind boundary the gateway is on, this session's numbers, and whether
     * the on-device model is installed. No Android types, so a test can stand in for a phone.
     */
    data class Facts(
        val versionName: String,
        val device: PresenceDiagnostic.Device,
        val mind: String?,
        val session: PresenceDiagnostic.Session,
        val model: String?,
    )

    /** The block, from parts already read: the one assembly both doors go through. */
    fun block(settings: PresenceDiagnostic.Settings, facts: Facts): String =
        PresenceDiagnostic.block(
            PresenceDiagnostic.Input(
                build = facts.versionName,
                settings = settings,
                device = facts.device,
                mind = facts.mind,
                session = facts.session,
                model = facts.model,
            )
        )

    /** The block as this app copies it. The build, the Presence density and the device are
     *  read here, so a door makes one call and cannot vary the inputs. */
    fun collect(context: Context, prefs: SharedPreferences): String =
        collect(context, prefs, versionName(context), density(prefs))

    fun collect(
        context: Context,
        prefs: SharedPreferences,
        versionName: String,
        density: PresenceCadence.Density,
    ): String = block(settings(prefs, density), facts(context, prefs, versionName))

    /** The app's version name. PackageManager rather than BuildConfig — the C0 release guard
     *  rejects key injection, and BuildConfig is not reliably generated. */
    fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    } catch (_: Throwable) { "" }

    /** The Presence register, with the legacy filler-cap slider migrated until a register is
     *  chosen — the same reading the Presence section's own row shows. */
    fun density(prefs: SharedPreferences): PresenceCadence.Density = PresenceCadence.resolve(
        prefs.getString(PresenceCadence.PREF, null),
        if (prefs.contains(PresenceCadence.LEGACY_PREF)) prefs.getInt(PresenceCadence.LEGACY_PREF, 2) else null)

    fun facts(context: Context, prefs: SharedPreferences, versionName: String): Facts = Facts(
        versionName = versionName,
        device = PresenceDiagnostic.Device(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            android = Build.VERSION.RELEASE.orEmpty(),
        ),
        mind = PresenceDiagnostic.mindFor(prefs.getString("url", "").orEmpty()),
        session = PresenceDiagnostic.Session(
            e2P50Ms = LatencyStats.sessionSpeechToAudioP50(),
            turns = LatencyStats.sessionTurns(),
            // E5 has no counter today: no turn is labelled "the caller heard neither an
            // answer nor a repair". Omitted rather than reported as zero.
            lost = null,
            answeredByVoice = ErTelemetry.sessionAnswered(),
            escalated = ErTelemetry.sessionEscalated(),
        ),
        model = MODEL_ID.takeIf { ModelCatalog.isInstalled(context, it) },
    )

    fun settings(prefs: SharedPreferences, density: PresenceCadence.Density): PresenceDiagnostic.Settings =
        PresenceDiagnostic.Settings(
            // The layer's effective state (choice AND the alpha gate), because that is what
            // produced the call the report is about.
            presenceEnabled = PresenceGate.enabled(prefs),
            soulNarration = prefs.getBoolean("er_presence", true),
            voice = prefs.getString("er_presence_voice", "sounds").orEmpty().ifBlank { "sounds" },
            density = density.key,
            hears = prefs.getBoolean(SoulAudio.PREF, true),
            semanticBarge = prefs.getBoolean("er_semantic_barge", true),
            reasoning = prefs.getString(VoiceTurn.PREF_REASONING, "low").orEmpty().ifBlank { "low" },
            echoMs = prefs.getFloat("er_echo_skip_ms", 700f).toInt(),
        )
}
