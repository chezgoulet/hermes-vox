package com.hermesvox

/**
 * StreamFence — the pure, JVM-testable core of the immediate-silence fix (#D1).
 * Streaming-TTS playback may proceed ONLY between startStreaming() and
 * stopStreaming(): a chunk is allowed to play iff the stream was STARTED and not
 * yet STOPPED. stopStreaming() closes the fence so a synth worker that is still
 * iterating its queued-sentence loop after a barge/hush/hangup sees
 * allowed=false and returns instead of REBUILDING the nulled AudioTrack (the old
 * resurrection that kept the reply playing seconds after the cut). No Android
 * deps — the decision + ordering live here and are unit-proven.
 */
internal class StreamFence {
    @Volatile private var started = false
    @Volatile private var stopped = true

    /** A chunk may play iff the stream started and has not been stopped. */
    val allowed: Boolean get() = started && !stopped

    /** startStreaming(): open the fence — chunks may play until the next stop. */
    fun start() { started = true; stopped = false }

    /** stopStreaming(): close the fence. Call BEFORE nulling/tearing down the
     *  track so no in-flight worker can (re)build one after the stop. */
    fun stop() { stopped = true }
}
