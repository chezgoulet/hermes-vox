package com.hermesvox

import java.net.URI

/**
 * PresenceDiagnostic — the block a report pastes into its issue (#417).
 *
 * The block is the only part of a report that gets parsed, so it has to be both useful and
 * safe to publish. Nothing here is free text from the user: every value is a setting read
 * from a closed vocabulary, a device fact Android supplies, a count, or a classification.
 * There is deliberately no transcript, key, URL, hostname or path — a report can be pasted
 * without reading it first.
 *
 * The app has no preset selector today. [Preset.COMPANION] is the shipped default bundle;
 * anything else is [Preset.CUSTOM] and names only the settings that moved off that bundle.
 * The other names in the issue template's vocabulary are carried for older/newer builds and
 * for the aggregator's grouping, not guessed at here.
 *
 * Pure JVM so the exact block is unit-tested off-device. The Android edge that fills it in
 * is [PresenceDiagnosticCollect].
 */
object PresenceDiagnostic {

    /** The block's first line. The aggregator looks for exactly this. */
    const val MARKER = "vox-diag"

    enum class Preset(val key: String) {
        COMPANION("companion"),
        BEAT_ONLY("beat-only"),
        VOICE_SMALLTALK("voice-smalltalk"),
        VOICE_MAX("voice-max"),
        CUSTOM("custom"),
    }

    /**
     * The Presence settings the block reports. Defaults are the app's shipped defaults, which
     * is what makes [Preset.COMPANION] checkable rather than a mood.
     */
    data class Settings(
        val presenceEnabled: Boolean = true,
        val soulNarration: Boolean = true,
        val voice: String = "sounds",
        val density: String = "conversational",
        val hears: Boolean = true,
        val semanticBarge: Boolean = true,
        val reasoning: String = "low",
        val echoMs: Int = 700,
    )

    /** What the app actually measured for this call. Null means "not measured", never zero. */
    data class Session(
        val e2P50Ms: Long? = null,
        val turns: Long = 0,
        val lost: Long? = null,
        val answeredByVoice: Long = 0,
        val escalated: Long = 0,
    )

    data class Device(val manufacturer: String, val model: String, val android: String)

    data class Input(
        val build: String,
        val settings: Settings = Settings(),
        val device: Device,
        /** local | lan | remote | cloud, or null when no gateway is configured. */
        val mind: String? = null,
        val session: Session = Session(),
        /** The on-device Presence model id, or null when it is not installed. */
        val model: String? = null,
    )

    /** The exact text the copy action puts on the clipboard. */
    fun block(input: Input): String = buildString {
        appendLine(MARKER)
        appendLine("build: ${input.build.ifBlank { "unknown" }}")
        val preset = preset(input.settings)
        appendLine("preset: ${preset.key}")
        detail(input.settings)?.let { appendLine("preset_detail: $it") }
        appendLine("device: ${deviceLine(input.device)}")
        input.mind?.let { appendLine("mind: $it") }
        input.session.e2P50Ms?.takeIf { it > 0L }?.let { appendLine("e2_p50_ms: $it") }
        appendLine(countersLine(input.session))
        appendLine("model: ${input.model?.takeIf { it.isNotBlank() } ?: "none"}")
    }.trimEnd()

    /** The shipped default bundle is the only named preset the app can claim by inspection. */
    fun preset(s: Settings): Preset = if (s == Settings()) Preset.COMPANION else Preset.CUSTOM

    /**
     * The non-default settings, in a stable order. Values are mapped through closed
     * vocabularies: a pref that somehow holds free text is reported as `other`, never echoed.
     * Null when the settings are exactly the default bundle.
     */
    fun detail(s: Settings): String? {
        val d = Settings()
        val parts = ArrayList<String>()
        if (s.presenceEnabled != d.presenceEnabled) parts += "presence=${if (s.presenceEnabled) "on" else "off"}"
        if (s.soulNarration != d.soulNarration) parts += "narration=${if (s.soulNarration) "on" else "off"}"
        if (s.voice != d.voice) parts += "voice=${oneOf(s.voice, "silent", "sounds", "spoken")}"
        if (s.density != d.density) parts += "density=${oneOf(s.density, "quiet", "conversational", "chatty")}"
        if (s.hears != d.hears) parts += "hears=${if (s.hears) "on" else "off"}"
        if (s.semanticBarge != d.semanticBarge) parts += "barge=${if (s.semanticBarge) "on" else "off"}"
        if (s.reasoning != d.reasoning) parts += "reasoning=${oneOf(s.reasoning, "off", "low", "medium", "high")}"
        if (s.echoMs != d.echoMs) parts += "echo=${s.echoMs}ms"
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /**
     * Which side of the caller/mind boundary the gateway is on, using only the URL's host.
     * The host itself is never returned: `local` (loopback), `lan` (a private address or a
     * single-label/.local name), `remote` (anything else, including a tailnet address — it is
     * not on the same LAN). `cloud` is reserved for a hosted gateway the app cannot identify
     * without a configuration it does not have, so it is not guessed here.
     */
    fun mindFor(url: String): String? {
        val raw = url.trim()
        if (raw.isEmpty()) return null
        val withScheme = if (raw.contains("://")) raw else "http://$raw"
        val host = try { URI(withScheme).host?.lowercase() } catch (_: Throwable) { null }
            ?: return "remote"
        if (host.isBlank()) return "remote"
        if (host == "localhost" || host.endsWith(".localhost")) return "local"
        if (host == "10.0.2.2") return "local"          // the emulator's alias for the host
        val v6 = host.removePrefix("[").removeSuffix("]")
        if (v6.contains(':')) return when {
            v6 == "::1" -> "local"
            v6.startsWith("fe80") -> "lan"               // link-local
            v6.startsWith("fc") || v6.startsWith("fd") -> "lan"   // unique-local
            else -> "remote"
        }
        ipv4(host)?.let { (a, b) ->
            return when {
                a == 127 -> "local"
                a == 10 -> "lan"
                a == 172 && b in 16..31 -> "lan"
                a == 192 && b == 168 -> "lan"
                a == 169 && b == 254 -> "lan"             // link-local
                // 100.64.0.0/10 is Tailscale's CGNAT range. It is private, but it is not
                // the same network the phone is on, so it reads `remote` (never a hostname).
                a == 100 && b in 64..127 -> "remote"
                else -> "remote"
            }
        }
        if (host.endsWith(".local") || host.endsWith(".lan") ||
            host.endsWith(".home") || host.endsWith(".internal")) return "lan"
        // A bare hostname such as `thelio` is resolved on the local network.
        if (!host.contains('.')) return "lan"
        return "remote"
    }

    /** `Google Pixel 7, Android 14` — system facts, no identifiers. */
    fun deviceLine(d: Device): String {
        val maker = d.manufacturer.trim().replace(Regex("\\s+"), " ")
        val model = d.model.trim().replace(Regex("\\s+"), " ")
        val name = listOf(maker, model).filter { it.isNotEmpty() }.joinToString(" ")
        val android = d.android.trim().ifBlank { "unknown" }
        return "$name, Android $android"
    }

    private fun countersLine(s: Session): String = buildString {
        append("turns: ${s.turns}")
        // An absent field is omitted, not printed empty: `lost: 0` would claim a measurement
        // this build does not make.
        s.lost?.let { append("  lost: $it") }
        append("  answered_by_voice: ${s.answeredByVoice}")
        append("  escalated: ${s.escalated}")
    }

    private fun oneOf(value: String, vararg allowed: String): String =
        if (value in allowed) value else "other"

    /** The first four dotted-octet groups, or null when [host] is not an IPv4 literal. */
    private fun ipv4(host: String): Pair<Int, Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val nums = parts.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return null }
        return nums[0] to nums[1]
    }
}
