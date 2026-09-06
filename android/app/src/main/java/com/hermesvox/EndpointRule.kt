package com.hermesvox

/**
 * EndpointRule — the pure, emulator-free endpointing decision for the offline
 * capture loop's utterance-length ceiling (B2, REVISED per Christopher 2026-09-06:
 * no hard ceiling by default — you talk until you're done; natural pauses end a turn).
 *
 * The old rule cut the mic unconditionally once the segment passed `vad_max_ms`
 * (15s), which chopped a user's long continuous utterance mid-sentence (the
 * field log's "…The app is." + a 1216ms "That" fragment). The revised rule:
 *  - past maxMs, the segment is only closed when the speaker has actually paused
 *    (>100ms VAD silence) — a continuous speaker is never chopped;
 *  - the absolute hard ceiling is DISABLED by default (`vad_max_hard_ms` = 0):
 *    the turn ends on the user's natural pause, period. A positive value remains
 *    available as an opt-in crash-guard for noisy environments where the VAD
 *    never releases;
 *  - the normal pause-break (silentMs > silenceMs, default 800ms) is UNCHANGED,
 *    lives in the caller, and owns everything below maxMs — [shouldStop] returns
 *    false there and is never the voice that ends a quiet/early segment.
 * The partial early-start worker keeps running inside an extended segment (its
 * snapshot is already bounded to a 6s tail).
 *
 * Practical note (not enforced here): on-device whisper transcription time scales
 * with utterance length; the remote STT backend (GPU, RTF ~0.02) removes that
 * ceiling for dictation-style use.
 */
object EndpointRule {

    /** `vad_max_hard_ms` pref default: 0 = DISABLED (no absolute ceiling).
     *  Set positive only as a crash-guard where the VAD may never release. */
    const val DEFAULT_HARD_MS = 0

    /** VAD silence past maxMs that closes an extended utterance (ms). A >100ms
     *  real pause means the speaker stopped; a shorter blip is intra-speech. */
    const val EXTEND_PAUSE_MS = 100L

    /** Stop when (past maxMs AND the speaker paused [EXTEND_PAUSE_MS]) or, only
     *  when the opt-in ceiling is enabled (hardMs > 0), when it is reached.
     *  Below maxMs the existing silence rule owns endpointing. */
    fun shouldStop(elapsedMs: Long, silentMs: Long, maxMs: Long, hardMs: Long): Boolean =
        (hardMs > 0L && elapsedMs >= hardMs) || (elapsedMs >= maxMs && silentMs > EXTEND_PAUSE_MS)
}
