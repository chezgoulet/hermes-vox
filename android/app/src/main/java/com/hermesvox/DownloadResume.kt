package com.hermesvox

/**
 * DownloadResume — the decisions behind a resumable model download, pure JVM.
 *
 * Model files are large (Gemma's is 2.6 GB) and phones lose networks. The old downloader
 * deleted its partial file on ANY failure and never sent a Range header, so a stream error at
 * 90% restarted the whole file from zero. The rules here keep what was already fetched:
 *  - a partial file is resumed with `Range: bytes=<have>-` when it belongs to the same
 *    artifact (same URL, same pinned sha256 — recorded next to it);
 *  - the server's answer decides what happens: 206 with a matching Content-Range appends,
 *    200 means the server ignored the range and the transfer restarts cleanly, 416 means the
 *    partial is already complete (or stale) and is judged by the sha256;
 *  - a transfer error retries automatically with backoff, resuming each time;
 *  - the pinned sha256 still judges the complete file, so a bad resume can never install.
 */
object DownloadResume {

    /** What to do with the response to a (possibly ranged) request. */
    sealed class Plan {
        /** Write from byte [offset] (0 = a fresh file) until [total] bytes. */
        data class Write(val offset: Long, val total: Long) : Plan()
        /** The partial already holds every byte — go straight to verification. */
        data class Complete(val total: Long) : Plan()
        /** Unusable response. */
        data class Fail(val reason: String) : Plan()
    }

    /**
     * Decide from the response. [have] is the partial's size when the request was made
     * (0 = no Range sent). [contentLength] is the response body length (-1 unknown).
     */
    fun plan(have: Long, code: Int, contentLength: Long, contentRange: String?): Plan = when (code) {
        200 -> if (contentLength > 0) Plan.Write(0L, contentLength) else Plan.Fail("unknown length")
        206 -> {
            val r = parseContentRange(contentRange)
            when {
                r == null -> Plan.Fail("bad Content-Range: $contentRange")
                r.first != have -> Plan.Fail("server resumed at ${r.first}, expected $have")
                else -> Plan.Write(have, r.second)
            }
        }
        // Range Not Satisfiable: asked for bytes past the end — the partial is (at least) the
        // whole file. `bytes */<total>` says how big the file really is.
        416 -> {
            val total = contentRange?.substringAfter("*/", "")?.trim()?.toLongOrNull()
            if (total != null && total == have) Plan.Complete(total) else Plan.Write(0L, -1L)
        }
        else -> Plan.Fail("HTTP $code")
    }

    /** `bytes <first>-<last>/<total>` -> (first, total); null when malformed or total unknown. */
    fun parseContentRange(h: String?): Pair<Long, Long>? {
        val m = Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+)\s*$""").find(h ?: return null) ?: return null
        val first = m.groupValues[1].toLong()
        val last = m.groupValues[2].toLong()
        val total = m.groupValues[3].toLong()
        if (last < first || last >= total) return null
        return first to total
    }

    /** Automatic retries of a failed transfer before it is reported (each resumes). */
    const val MAX_RETRIES = 6

    /** Backoff before retry [attempt] (1-based): 2, 4, 8, 16, 30, 30 seconds. */
    fun backoffMs(attempt: Int): Long = (1000L shl attempt.coerceIn(1, 5)).coerceAtMost(30_000L)

    /** The partial belongs to this artifact only when its recorded identity matches. */
    fun sameArtifact(recordedUrl: String?, recordedSha: String?, url: String, sha: String): Boolean =
        recordedUrl == url && recordedSha.equals(sha, ignoreCase = true)

    /**
     * Free space needed to finish: the bytes still to fetch, plus room to unpack an archive
     * (an archive is expanded beside it before the swap; ~2x the archive is a safe bound), plus
     * a margin so the phone is never left with a full disk.
     */
    fun bytesNeeded(total: Long, have: Long, isArchive: Boolean): Long {
        val remaining = (total - have).coerceAtLeast(0L)
        val unpack = if (isArchive) total * 2 else 0L
        return remaining + unpack + 200L * 1024 * 1024
    }
}
