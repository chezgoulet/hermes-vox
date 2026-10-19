package com.hermesvox

import java.util.Locale

/**
 * User-facing words for a model transfer (#120). A bare percentage says how far
 * along a download is but not WHAT the phone is doing — fetching bytes, checking
 * them, or unpacking a 2.6 GB archive. Both the model card and the background
 * notification read these, so the two surfaces can never disagree.
 *
 * Pure JVM so the copy is testable off-device.
 */
object ModelProgress {

    /** One decimal of a byte count, in MB. */
    fun mb(bytes: Long): String = String.format(Locale.US, "%.1f", bytes / 1048576.0)

    /** Downloading: the size still moving across the network. */
    fun fetching(done: Long, total: Long): String =
        if (total > 0) "Fetching ${mb(done)} of ${mb(total)} MB"
        else "Fetching — ${mb(done)} MB so far"

    /** Offline: the partial is kept and the transfer resumes by itself. */
    fun waiting(done: Long): String =
        "Waiting for network — ${mb(done)} MB kept, resumes automatically"

    /** All bytes are here; the sha256 check is running. */
    fun verifying(): String = "Verifying the download…"

    /** The verified archive is being unpacked into the model directory. */
    fun unpacking(): String = "Unpacking the files…"

    /** Stopped by a cancel or a transfer error. [reason] is the honest failure text. */
    fun paused(done: Long, reason: String?): String =
        if (reason.isNullOrBlank()) "Paused — ${mb(done)} MB kept, resumes where it stopped"
        else "Stopped — $reason (${mb(done)} MB kept)"
}
