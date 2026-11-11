package com.hermesvox

/**
 * #538: why one `SherpaTts.streamChunk()` call wrote nothing — or that it wrote.
 *
 * A bare `false` used to cover all of these, and the streaming worker read every one of
 * them as engine death. Only [SILENT] and [ERROR] are the engine's fault; the rest are the
 * fence, the track, or an engine that was swapped out under the reply.
 */
internal enum class ChunkResult {
    /** The chunk was synthesized and written to the playback track. */
    WROTE,
    /** This engine's fence was never opened: it was installed (re-warm / degradation)
     *  after the reply's stream began. Nothing is wrong with it; the stream must adopt it. */
    NOT_OPEN,
    /** A stop closed the fence, or landed while the chunk synthesized. Blames nobody. */
    CUT,
    /** The engine had been shut down (replaced) when the chunk reached it. */
    NO_ENGINE,
    /** The engine produced no audible audio for a speakable chunk. */
    SILENT,
    /** No playback track could be built (#549's defect, on the streaming path). */
    TRACK_UNAVAILABLE,
    /** Synthesis or the write threw. */
    ERROR,
}

/**
 * The streaming worker's reading of one `SherpaTts.streamChunk()` result.
 *
 * A silent or throwing chunk is an engine failure on a live reply: the phrase is dropped,
 * the host applies its bounded recovery (one re-warm, then one degradation), and the turn
 * keeps draining. It must NOT be read as the end of the reply. A chunk cut by a stop is
 * expected and blames nobody — and neither does a chunk that reached an engine installed
 * after the stream began (#538: that read spent the whole voice budget in one reply).
 */
internal object StreamSynthResult {
    /** The pre-#538 boolean reading, kept for the stall tests' table. */
    fun failed(wrote: Boolean, cancelled: Boolean, closed: Boolean): Boolean =
        !wrote && !cancelled && !closed

    /** The code to hand [TtsEngineHost.noteFailure], or null when nobody is to blame. */
    fun failureCode(result: ChunkResult, cancelled: Boolean, closed: Boolean): String? = when {
        cancelled || closed -> null
        result == ChunkResult.SILENT || result == ChunkResult.ERROR -> "stream-synth"
        // Not the engine's fault: the host logs it and keeps the budget (NOT_THE_ENGINE).
        result == ChunkResult.TRACK_UNAVAILABLE -> "track-unavailable"
        else -> null
    }

    /** Open this engine's fence and retry the chunk once: a replacement engine mid-reply. */
    fun adopt(result: ChunkResult, cancelled: Boolean, closed: Boolean): Boolean =
        result == ChunkResult.NOT_OPEN && !cancelled && !closed
}
