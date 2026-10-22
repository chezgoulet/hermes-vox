package com.hermesvox

/**
 * ContinuousCapture — the mic and the streaming recogniser stay live for the whole call (#228).
 *
 * The loop's shape before this was per turn: the VAD gathered one utterance, the recogniser was
 * handed that utterance's *new* audio on a 900 ms cadence, and the turn's text came from ending
 * the stream. The AudioRecord itself has not closed between turns since #12 (the barge drain
 * keeps reading it), but the recogniser had nothing to say between partials: its hypothesis was
 * read at most four times a second, and no hypothesis existed before the VAD opened a segment.
 *
 * Continuous capture changes two things, both behind [KEY] — off by default, so a call behaves
 * exactly as it did and a user can run continuous capture with the existing 800 ms endpointing:
 *
 *  1. **The cadence.** The live stream is fed and its partial read every [PARTIAL_MS], and that
 *     starts at the segment's first sample rather than 900 ms in. This is the signal #229's
 *     semantic endpointer needs (it judges from a partial that is never more than one tick old)
 *     and the reason #235's "each audio-second is decoded once" is now measurable per turn in
 *     `event=stt-rtf`.
 *  2. **The recorder.** It is opened once per call and left recording across turns, so the first
 *     word after a reply is never lost to a stop/start seam, and the mic is not re-negotiated
 *     (with its AEC/NS session) on every turn.
 *
 * It is deliberately **not** one recogniser stream for the whole call. sherpa's online stream
 * grows with the audio it has seen, and its own endpointing stays off; one stream per utterance
 * (a cheap [StreamingTranscriber.reset]) is what keeps the decoding state bounded and a turn's
 * text its own. "Continuous" is about the mic and the cadence, not about one endless hypothesis.
 *
 * Cost is the reason this ships off: a recogniser reading continuously is a different power
 * profile from one reading between turns, and #228's own precondition is a measured budget. The
 * ten-minute co-residency run (#179) records CPU, RSS and thermal with it on, and the battery
 * figure stays pending because USB power cannot show it (docs/BASELINE.md).
 */
object ContinuousCapture {

    /** The switch: `continuous_capture`. Off by default — #228 ships it beside, not instead of,
     *  the existing endpointing. */
    const val KEY = "continuous_capture"

    /** How often the live stream is fed and its partial read. #229 needs fresh partials about
     *  every 100 ms; the bench that chose its thresholds also replays at 100 ms frames. */
    const val PARTIAL_MS = 100L

    /** The cadence continuous capture replaces: one partial per 900 ms of an open utterance. */
    const val LEGACY_PARTIAL_MS = 900L

    /** Continuous capture can only run on a recogniser that keeps a live stream (#207's
     *  streaming arm, [LiveStt]); on the whole-clip legs there is nothing to keep open, and the
     *  switch must not pretend otherwise. */
    fun active(switchOn: Boolean, hasLiveStream: Boolean): Boolean = switchOn && hasLiveStream

    /** #229's switch only means something once capture produces fresh partials: its thresholds
     *  are fitted to 100 ms frames, and on the legacy 900 ms cadence a "stable" partial is a
     *  quarter-second-old fact. Semantic endpointing without continuous capture therefore does
     *  not engage, and says so once per call. */
    fun semanticReady(semanticSwitchOn: Boolean, continuous: Boolean): Boolean =
        semanticSwitchOn && continuous

    /** Which cadence the capture loop should run, in ms. */
    fun cadenceMs(continuous: Boolean): Long = if (continuous) PARTIAL_MS else LEGACY_PARTIAL_MS
}

/**
 * When the next partial is due, on a fixed cadence that does not drift with how long a decode
 * took. [intervalMs] comes from the switches ([ContinuousCapture.cadenceMs]); [startMs] is the
 * segment's own clock, so the first tick is one interval after the utterance opened rather than
 * "as soon as anyone asks".
 */
class PartialCadence(
    private val intervalMs: Long = ContinuousCapture.PARTIAL_MS,
    startMs: Long = 0L,
) {
    private var lastAt = startMs

    /** True at most once per [intervalMs], measured from the previous *granted* tick. */
    fun due(nowMs: Long): Boolean {
        if (nowMs - lastAt < intervalMs) return false
        lastAt = nowMs
        return true
    }

    fun reset(nowMs: Long) { lastAt = nowMs }
}

/**
 * #229's wiring: [SemanticEndpoint] judged against the partials continuous capture produces.
 *
 * The endpointer is pure and already benched ([SemanticEndpoint], EndpointBenchTest); what it
 * cannot know by itself is *when the last partial changed*, which is the feature that carries
 * the decision ("stableMs" — the corpus showed the hypothesis still revising half a second into
 * a word gap). This keeps that clock: one partial per tick, the time it last changed, and the
 * silence the caller has accumulated, and asks the endpointer for a verdict.
 *
 * Only [SemanticEndpoint.Verdict.FINAL] is acted on and it is irreversible (the turn is
 * committed); PROVISIONAL is the latency hint the frame protocol reserves and this loop does not
 * yet use. Called from the capture thread and from the partial worker, hence synchronized.
 */
class EndpointWatch(val eagerness: SemanticEndpoint.Eagerness = SemanticEndpoint.Eagerness.BALANCED) {

    private var partial = ""
    private var changedAt = 0L
    private var heard = false

    /** The caller is speaking (the VAD said so at least once in this segment). */
    @Synchronized fun onSpeech(nowMs: Long) {
        if (!heard) { heard = true; changedAt = nowMs; partial = "" }
    }

    /** A fresh hypothesis from the live stream. */
    @Synchronized fun onPartial(text: String, nowMs: Long) {
        if (text != partial) { partial = text; changedAt = nowMs }
    }

    /** How long the hypothesis has held still; 0 before the first partial. */
    @Synchronized fun stableMs(nowMs: Long): Long =
        if (!heard || changedAt <= 0L) 0L else (nowMs - changedAt).coerceAtLeast(0L)

    @Synchronized fun verdict(silenceMs: Long, nowMs: Long): SemanticEndpoint.Verdict =
        SemanticEndpoint.decide(silenceMs, partial, stableMs(nowMs), eagerness)

    @Synchronized fun text(): String = partial

    @Synchronized fun reset() { partial = ""; changedAt = 0L; heard = false }
}
