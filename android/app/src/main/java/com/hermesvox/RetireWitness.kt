package com.hermesvox

/**
 * RetireWitness — regression path 5's "the full chunk count", made checkable.
 *
 * The streaming worker's retire line said only how many chunks it had handed to the engine
 * (`chunks=`), which on a natural retirement is every chunk it dequeued — so it could not show a
 * reply that lost a phrase. Path 5 asked for "the full chunk count" with nothing to compare it
 * to (the 0.9.x review, §3.0(d)). The worker now also counts what the text side queued for this
 * stream and how many chunks actually wrote audio, and the line says whether they agree:
 *
 *     … chunks=<handed to the engine> ms=<worker lifetime> queued=<chunked from the reply> wrote=<produced audio> full=<true|false>
 *
 * The new fields come after `ms=` so every existing reader of `chunks=` and `ms=` is unchanged.
 * `full=true` means every queued chunk was handed over and every one wrote audio. `full=false`
 * on a natural retirement is a reply that lost a phrase — a dropped chunk (`tts-drop`) or an
 * engine failure — and is what path 5 exists to catch. Pure JVM.
 */
internal object RetireWitness {

    fun full(chunks: Int, queued: Int, wrote: Int): Boolean =
        queued > 0 && chunks == queued && wrote == chunks

    /** The fields appended to the retire line. */
    fun fields(chunks: Int, queued: Int, wrote: Int): String =
        "queued=$queued wrote=$wrote full=${full(chunks, queued, wrote)}"
}
