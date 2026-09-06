package com.hermesvox

/**
 * EndpointRule — the pure, emulator-free endpointing decision for the offline
 * capture loop's utterance-length ceiling (B2).
 *
 * The old rule cut the mic unconditionally once the segment passed `vad_max_ms`
 * (15s), which chopped a user's long continuous utterance mid-sentence (the
 * field log's "…The app is." + a 1216ms "That" fragment). The VAD-extended rule
 * only stops at maxMs once the speaker has actually paused ([EXTEND_PAUSE_MS]
 * of VAD silence) and otherwise lets the segment run on toward the hard ceiling
 * [DEFAULT_HARD_MS] (`vad_max_hard_ms`, 60s) so a 15-25s ramble transcribes
 * whole. The normal pause-break (silentMs > silenceMs, the caller's turn-ending
 * pause) is UNCHANGED and owns everything below maxMs — [shouldStop] returns
 * false there and is never the voice that ends a quiet/early segment. The
 * partial early-start worker keeps running inside an extended segment (its
 * snapshot is already bounded to a 6s tail).
 */
object EndpointRule {

    /** `vad_max_hard_ms` pref default: no utterance ever exceeds this ceiling. */
    const val DEFAULT_HARD_MS = 60000

    /** VAD silence past maxMs that closes an extended utterance (ms). A >100ms
     *  real pause means the speaker stopped; a shorter blip is intra-speech. */
    const val EXTEND_PAUSE_MS = 100L

    /** Stop when the segment ran past the hard ceiling, OR it is past maxMs and
     *  the speaker has paused ([EXTEND_PAUSE_MS] of VAD silence). Below maxMs the
     *  existing silence rule owns endpointing — never consulted here. */
    fun shouldStop(elapsedMs: Long, silentMs: Long, maxMs: Long, hardMs: Long): Boolean =
        elapsedMs >= hardMs || (elapsedMs >= maxMs && silentMs > EXTEND_PAUSE_MS)
}
