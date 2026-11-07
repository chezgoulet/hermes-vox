package com.hermesvox

/**
 * The streaming worker's reading of one `SherpaTts.streamChunk()` result.
 *
 * `streamChunk()` returns false for two different things, and they must not be
 * confused: a chunk that synthesized to no audible audio (the engine went quiet),
 * and a chunk cut by a barge/hush/stop that landed while it was synthesizing.
 *
 * A silent chunk is an engine failure on a live reply: the phrase is dropped, the
 * host applies its bounded recovery (one re-warm, then one degradation), and the
 * turn keeps draining. It must NOT be read as the end of the reply — treating the
 * gap as "done" is how a reply goes quiet at a midpoint and still reports success.
 * A chunk cut by a stop is expected and blames nobody.
 */
internal object StreamSynthResult {
    fun failed(wrote: Boolean, cancelled: Boolean, closed: Boolean): Boolean =
        !wrote && !cancelled && !closed
}
