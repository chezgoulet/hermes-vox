package com.hermesvox.wake

/**
 * WakeWordEngine — the seam where a wake-word detector plugs in (#45, D6).
 *
 * The contract matches openWakeWord's streaming API so the real detector can sit
 * here without reshaping its caller: 16 kHz mono PCM16, fed in 80 ms frames
 * ([FRAME_SAMPLES] = 1280 samples), one score in 0..1 per frame. The caller owns
 * the microphone; the engine never opens audio itself, so whether anything listens
 * is decided by whoever feeds it — today, nobody does.
 *
 * Where openWakeWord will sit (see docs/WAKE-WORD.md §5):
 *   frame -> melspectrogram.onnx -> embedding_model.onnx (frozen, shared) ->
 *   hey_hermes.onnx (stage 1) -> per-user verifier (stage 2, from enrolment takes)
 * An implementation of this interface runs that chain and turns the per-frame score
 * into a [WakeDetection] with the threshold/refractory rules in the design doc.
 * Nothing in this build implements it: [InertWakeWordEngine] is the only engine.
 */
interface WakeWordEngine {
    /** Short stable id for logs and Settings ("inert", later "oww-hey-hermes"). */
    val id: String

    /** False until a detector and its model files are present on the device. */
    val available: Boolean

    /**
     * Feed one 80 ms frame (exactly [FRAME_SAMPLES] samples of 16 kHz PCM16).
     * Returns a detection when the phrase fired on this frame, else null.
     */
    fun process(frame: ShortArray): WakeDetection?

    /** Drop any streaming state (call when the feed is interrupted). */
    fun reset()

    /** Free native resources; the engine is unusable afterwards. */
    fun release()

    companion object {
        const val SAMPLE_RATE = 16_000
        /** 80 ms at 16 kHz — openWakeWord's native step. */
        const val FRAME_SAMPLES = 1_280
    }
}

/** A fired wake phrase. [score] is the final-stage score (verifier when present). */
data class WakeDetection(val phrase: String, val score: Float)

/**
 * The only engine in this build: never detects, holds nothing, opens no audio.
 * It exists so callers can be written against [WakeWordEngine] now and the Settings
 * row can report honestly that nothing is listening.
 */
object InertWakeWordEngine : WakeWordEngine {
    override val id = "inert"
    override val available = false
    override fun process(frame: ShortArray): WakeDetection? {
        require(frame.size == WakeWordEngine.FRAME_SAMPLES) {
            "wake frame must be ${WakeWordEngine.FRAME_SAMPLES} samples, got ${frame.size}"
        }
        return null
    }
    override fun reset() {}
    override fun release() {}
}
