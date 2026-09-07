package com.hermesvox

/**
 * ReplySettleRule — the pure, emulator-free decision for what a COMPLETED reply
 * (done=true with text) may do, i.e. the body of VoiceController.settleReply.
 *
 * 0.4.0.4 — the ghost re-speak (field log docs/evidence-0404-failure.log):
 *
 *   22:17:31.460  event=tts-stop reason=hush     (user silences the reply)
 *   22:17:31.461  event=gate-release gen=1 reason=hush
 *   22:17:31.462  turn done: gen=1 len=199 err=  (done lands ONE ms later)
 *   22:17:32.862  piper generated 225146 samples (199 chars)   <-- the whole
 *   22:17:43.209  piper played 225146 samples                       reply again
 *
 * The old settle branched on `streamed && supportsStreaming` alone. But the hush
 * had ALREADY run stopStreaming(), which clears `streamed` — so a reply that was
 * half-spoken and then cancelled fell through to the text-only branch
 * (`else if (shouldSpeak()) speak(finalText, gen)`) and re-synthesized the ENTIRE
 * reply as one monolithic utterance, minutes of ghost voice outliving the hush,
 * the call, and the foreground service. `streamed` is not a witness of "this turn
 * was cancelled"; it is only a witness of "a streaming turn is currently open".
 *
 * The two real witnesses, both per-turn and both already maintained:
 *  - [gateReleased]: this gen's turn gate is already released (or the gen is
 *    stale). Every cancel path — bargeIn / hush / endCall-stop / focus-loss —
 *    goes through silenceAll, which releases the gate. A settle for a released
 *    gate is a settle for a turn the user already ended: it may render TEXT, but
 *    it must never start speech, and it must not re-release the gate.
 *  - [genSpokeAudio]: the turn's firstAudioPushed latch — true once the streaming
 *    worker completed a real write to the playback track. A reply that was
 *    already (even partly) audible must never be spoken a SECOND time.
 *
 * Anything that was never streamed and never audible is an ordinary text-only
 * turn and still speaks normally ([SPEAK]) — that is the path this rule must not
 * regress. A streamed turn still retires through the worker ([RETIRE_STREAM],
 * R1/#71 single-owner retirement): the settle only marks the queue final.
 */
enum class ReplySettle {
    /** Streaming turn open: mark the queue final; the worker owns the close (#71). */
    RETIRE_STREAM,
    /** Nothing was streamed or spoken for this gen and the voice channel is open. */
    SPEAK,
    /** Live turn, but speech is off (channel closed / speak toggle): settle quietly. */
    SILENT_SETTLE,
    /** Cancelled or already voiced: render the text, start NO speech, release nothing. */
    DROP,
}

internal object ReplySettleRule {

    /**
     * @param streamed          a streaming turn is open (VoiceController.streamed)
     * @param streamingEngine   the TTS engine supports chunk streaming
     * @param genSpokeAudio     this gen already pushed real audio (firstAudioPushed)
     * @param gateReleased      this gen's gate is already released, or the gen is stale
     * @param speakAllowed      shouldSpeak(): voice channel open AND speak toggle on
     * @param hasText           the settled reply is non-blank
     */
    fun decide(
        streamed: Boolean,
        streamingEngine: Boolean,
        genSpokeAudio: Boolean,
        gateReleased: Boolean,
        speakAllowed: Boolean,
        hasText: Boolean,
    ): ReplySettle {
        // #71 first and unconditionally: an OPEN streamed turn retires through the
        // worker. genSpokeAudio is true for every healthy streamed reply, so the
        // cancel checks below must never see this case (they would starve the tail
        // and wedge the gate until the 60s backstop).
        if (streamed && streamingEngine) return ReplySettle.RETIRE_STREAM
        // 0.4.0.4: the turn is over (cancelled) or was already audible -> no speech.
        if (gateReleased || genSpokeAudio) return ReplySettle.DROP
        if (!speakAllowed || !hasText) return ReplySettle.SILENT_SETTLE
        return ReplySettle.SPEAK
    }
}
