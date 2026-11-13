package com.hermesvox

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

/**
 * The Android edge of [PresenceDiagnostic]: reads the settings and the session's measured
 * numbers, and nothing else. Keeping this separate is what lets the exact block be tested
 * on the JVM (PresenceDiagnosticTest) while the real values still come from the running app.
 */
object PresenceDiagnosticCollect {

    /** The Presence model's catalog id — the only on-device model the block names. */
    const val MODEL_ID = "gemma-e2b"

    fun collect(
        context: Context,
        prefs: SharedPreferences,
        versionName: String,
        density: PresenceCadence.Density,
    ): String = PresenceDiagnostic.block(
        PresenceDiagnostic.Input(
            build = versionName,
            settings = settings(prefs, density),
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
    )

    /**
     * #417: **one builder, two entry points.** The Settings action and `/diagnostics` both call
     * this, so the copied text cannot drift: it is [collect]'s exact output for the same prefs,
     * and the aggregator parses one shape.
     *
     * True when the block reached the clipboard. The context reads nothing else: the build is
     * the installed package's `versionName`, the density is the stored register — the same two
     * values the Settings action always used.
     */
    fun copy(context: Context, prefs: SharedPreferences): Boolean {
        val block = collect(context, prefs, versionName(context), density(prefs))
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(PresenceDiagnostic.MARKER, block))
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** The installed `versionName` — the block's `build`, nightlies included. */
    fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (_: Throwable) { "" }

    /** The stored Presence register, exactly as Settings resolves it for the same block. */
    fun density(prefs: SharedPreferences): PresenceCadence.Density = PresenceCadence.resolve(
        prefs.getString(PresenceCadence.PREF, null),
        if (prefs.contains(PresenceCadence.LEGACY_PREF)) prefs.getInt(PresenceCadence.LEGACY_PREF, 2) else null)

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
