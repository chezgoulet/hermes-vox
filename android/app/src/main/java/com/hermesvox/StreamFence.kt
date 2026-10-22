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
    @Volatile private var stops = 0L

    /** A chunk may play iff the stream started and has not been stopped. */
    val allowed: Boolean get() = started && !stopped

    /** 0.4.0.4 — the cancel token for a one-shot speak(). [allowed] is LEVEL
     *  triggered, so it cannot answer "was there a stop while I was busy?": the
     *  one-shot path synthesizes for ~1.4s and then OPENS the fence itself to
     *  play, which would re-open the very fence a hush just closed (the ghost
     *  voice). A caller captures [stopEpoch] before it starts synthesizing and
     *  re-checks it under the track lock before building a track — a changed
     *  value means a stop landed in between, so the utterance is dropped
     *  unplayed. Monotonic, so a repeat stop still invalidates old tokens. */
    val stopEpoch: Long get() = stops

    /** #240: may a chunk that began synthesizing at [epoch] still play? Only if no stop has
     *  landed since — even when the next turn has re-opened the fence meanwhile. [allowed]
     *  alone is level-triggered: a stop and a start inside one chunk's synthesis left it
     *  true, and that chunk played into the next turn (the 1.77 s field carry-over). */
    fun allows(epoch: Long): Boolean = allowed && stops == epoch

    /** startStreaming(): open the fence — chunks may play until the next stop. */
    fun start() { started = true; stopped = false }

    /** stopStreaming(): close the fence. Call BEFORE nulling/tearing down the
     *  track so no in-flight worker can (re)build one after the stop. */
    fun stop() { stopped = true; stops++ }
}
