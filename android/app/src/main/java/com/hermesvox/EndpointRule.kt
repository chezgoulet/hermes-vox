package com.hermesvox

/**
 * EndpointRule — the pure, emulator-free endpointing decision for the offline
 * capture loop's utterance-length ceiling (B2, REVISED per the maintainer 2026-09-06:
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

    /**
     * 0.9.1: the clock [shouldStop]'s elapsedMs must run on — time since the SPEECH
     * began (0 before it has), not since the mic opened.
     *
     * It ran from the segment's open. Silence before the user speaks counts against
     * nothing else, so a mic left open past maxMs (15 s) — the caller thinking, or the
     * capture loop reopened under a long reply (TurnGateCeiling) — closed the user's
     * utterance at its FIRST >100 ms pause. The 0.9.0 field log's fragments are exactly
     * that: `endpoint-extended ms=31798` then `ms=1274 speechMs=896 text=and not`, and
     * `ms=33015` then `ms=4858 speechMs=4480 text=okay i just want to point out in
     * the` — each clip is the 250 ms pre-roll + the speech + ~128 ms (two reads) of
     * pause. The entity's reply: "that message cut off mid-sentence — 'in the'".
     */
    fun utteranceMs(nowMs: Long, speechStartAtMs: Long): Long =
        if (speechStartAtMs <= 0L) 0L else nowMs - speechStartAtMs
}
