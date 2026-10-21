package com.hermesvox

import java.util.Locale

/**
 * ModelRowState — the one place a model's download facts become the words on its
 * row (#219). It is pure JVM: the Models screen feeds in the SAME
 * [ModelDownloads.State] the download path publishes and the SAME acceptance
 * [ModelLicences] enforces, and this object turns them into a label, an action,
 * progress and a tone. No second copy of the state machine, no Android types,
 * so every branch is unit-testable without a device.
 *
 * Tone is supplementary only: every state is spelled out in [Row.state] text, so
 * nothing is conveyed by colour alone.
 */
object ModelRowState {

    /** What the row is saying, in the order a person meets it. */
    enum class Stage {
        NOT_INSTALLED,
        NEEDS_LICENCE,
        QUEUED,
        DOWNLOADING,
        WAITING_NETWORK,
        VERIFYING,
        UNPACKING,
        PAUSED,
        CANNOT_START,
        INSTALLED
    }

    /** What the row's button does. [NONE] renders a disabled button. */
    enum class Action { DOWNLOAD, REVIEW_LICENCE, PAUSE, CANCEL, RESUME, NONE }

    /** Supplementary colour. The label always carries the meaning on its own. */
    enum class Tone { NEUTRAL, WARN, OK, DANGER }

    data class Row(
        val stage: Stage,
        val state: String,
        val action: Action,
        val actionLabel: String,
        /** 0..1 when the transfer reported a total; null when it did not. */
        val progress: Float? = null,
        /** True while the transfer is moving but cannot be measured yet. */
        val indeterminate: Boolean = false,
        val tone: Tone = Tone.NEUTRAL
    )

    /**
     * Map the download state (the exact object [ModelDownloads] hands the UI) and the
     * licence gate (the exact check [ModelDownloader] enforces) to display facts.
     *
     * Precedence: an installed model is simply installed — its licence was already
     * accepted on the way in. Otherwise a non-permissive licence that has not been
     * accepted IS the state, because no download can start until it is.
     */
    fun of(state: ModelDownloads.State, licence: ModelLicence?, licenceAccepted: Boolean): Row {
        if (state is ModelDownloads.State.Installed) {
            return Row(Stage.INSTALLED, "Installed and ready", Action.NONE, "Installed",
                tone = Tone.OK)
        }
        if (licence != null && !licenceAccepted) {
            return Row(
                Stage.NEEDS_LICENCE,
                "Needs a licence accepted — ${licence.name}",
                Action.REVIEW_LICENCE,
                "Review licence",
                tone = Tone.WARN
            )
        }
        return when (state) {
            // Unreachable (handled above); kept so this `when` stays exhaustive.
            ModelDownloads.State.Installed ->
                Row(Stage.INSTALLED, "Installed and ready", Action.NONE, "Installed",
                    tone = Tone.OK)

            ModelDownloads.State.Idle ->
                Row(Stage.NOT_INSTALLED, "Not installed", Action.DOWNLOAD, "Download")

            ModelDownloads.State.Queued ->
                Row(Stage.QUEUED, "Waiting — downloads run one at a time",
                    Action.CANCEL, "Cancel", indeterminate = true)

            is ModelDownloads.State.Running -> when (state.phase) {
                ModelDownloader.Phase.DOWNLOAD -> {
                    val fraction =
                        if (state.total > 0) (state.done.toDouble() / state.total).toFloat()
                            .coerceIn(0f, 1f) else null
                    val text =
                        if (state.total > 0)
                            "Downloading — ${ModelProgress.mb(state.done)} of " +
                                "${ModelProgress.mb(state.total)} MB"
                        else
                            "Downloading — no progress reported yet" +
                                (if (state.done > 0) " (${ModelProgress.mb(state.done)} MB so far)" else "")
                    Row(Stage.DOWNLOADING, text, Action.PAUSE, "Pause",
                        progress = fraction, indeterminate = fraction == null)
                }
                ModelDownloader.Phase.WAITING_NETWORK ->
                    Row(Stage.WAITING_NETWORK, ModelProgress.waiting(state.done),
                        Action.PAUSE, "Pause", indeterminate = true, tone = Tone.WARN)

                ModelDownloader.Phase.VERIFY ->
                    Row(Stage.VERIFYING, ModelProgress.verifying(),
                        Action.NONE, "Verifying…", indeterminate = true)

                ModelDownloader.Phase.UNPACK ->
                    Row(Stage.UNPACKING, ModelProgress.unpacking(),
                        Action.NONE, "Unpacking…", indeterminate = true)
            }

            is ModelDownloads.State.Paused -> {
                val fraction =
                    if (state.total > 0 && state.done > 0) (state.done.toDouble() / state.total)
                        .toFloat().coerceIn(0f, 1f) else null
                if (state.reason != null) {
                    val kept = if (state.done > 0) " (${ModelProgress.mb(state.done)} MB kept)" else ""
                    Row(Stage.CANNOT_START, "Cannot start — ${state.reason}$kept",
                        Action.RESUME, "Resume", progress = fraction, tone = Tone.DANGER)
                } else if (state.done <= 0) {
                    Row(Stage.PAUSED, "Paused — nothing downloaded yet", Action.RESUME, "Resume",
                        tone = Tone.WARN)
                } else {
                    Row(Stage.PAUSED, ModelProgress.paused(state.done, null), Action.RESUME,
                        "Resume", progress = fraction, tone = Tone.WARN)
                }
            }
        }
    }

    /** The licence chip's words, or null when the licence is permissive. Tapping it
     *  opens the existing acceptance dialog; the text says so. */
    fun licenceBadge(licence: ModelLicence?, accepted: Boolean): String? = when {
        licence == null -> null
        accepted -> "Licence: ${licence.name} — tap to review"
        else -> "Licence needed: ${licence.name} — tap to review and accept"
    }

    /** The catalogue's decimal MB, rendered exactly as catalogued (no re-rounding). */
    fun sizeLabel(sizeMB: Double): String = String.format(Locale.US, "%.1f MB", sizeMB)
}
