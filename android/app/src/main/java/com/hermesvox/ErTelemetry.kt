package com.hermesvox

/**
 * ErTelemetry — the ER decision counters (Phase 8), pure JVM. The honest
 * numbers that tell us whether ER feels alive vs misfires:
 *  - classifier decisions by route (the miss-rate denominator);
 *  - semantic barge verdicts (cancel vs hold — the false-cancel rate);
 *  - arbiter preemptions/rejections (the double-talk watch);
 *  - soul first-word latency (the presence's own responsiveness).
 * Bounded, lock-guarded, dumped as one log line per emit — measurement only,
 * zero behavior.
 */
object ErTelemetry {

    private val lock = Object()

    // Classifier decisions by route (0.8/M3c: two outcomes only — the routing counters are
    // gone with the routing).
    private var clsHold = 0L
    private var clsYield = 0L
    // Semantic barge verdicts.
    private var bargeCancel = 0L
    private var bargeHold = 0L
    // Arbiter outcomes.
    private var arbPlay = 0L
    private var arbPreempt = 0L
    private var arbReject = 0L
    // Soul first-word (ms) ring — bounded like LatencyStats.
    private val soulFirstWord = ArrayList<Long>()
    private val soulFirstWordCap = 64
    // 0.8/M1: the ER-delta counters — the headline "is ER audible at all" numbers.
    private var turns = 0L
    private var turnsSoulSpoke = 0L
    private var emitTurns = 0L
    // The soul's OWN render latency (the GemmaExpress.express round-trip).
    private val gemmaRender = ArrayList<Long>()
    private val gemmaRenderCap = 64

    fun classify(route: ErIntent.Route) = synchronized(lock) {
        when (route) {
            ErIntent.Route.HOLD_ONLY -> clsHold++
            ErIntent.Route.ACK_AND_YIELD -> clsYield++
        }
    }

    fun barge(v: ErBargeGate.Verdict) = synchronized(lock) {
        when (v) {
            ErBargeGate.Verdict.CANCEL_MIND -> bargeCancel++
            ErBargeGate.Verdict.HOLD_MIND -> bargeHold++
        }
    }

    fun arbiter(d: ErArbiter.Decision) = synchronized(lock) {
        when (d) {
            is ErArbiter.Decision.Play -> arbPlay++
            is ErArbiter.Decision.Preempt -> arbPreempt++
            is ErArbiter.Decision.Reject -> arbReject++
        }
    }

    fun soulFirstWord(ms: Long) = synchronized(lock) {
        if (ms > 0) { soulFirstWord.add(ms); if (soulFirstWord.size > soulFirstWordCap) soulFirstWord.removeAt(0) }
    }

    /** One mind-work window closed. [soulSpoke] = the soul produced at least one
     *  utterance while the mind was working. The window IS the mind's work and it ends
     *  when the reply arrives, so any soul utterance necessarily preceded the reply —
     *  this is the ER delta in its simplest honest form. */
    fun window(soulSpoke: Boolean) = synchronized(lock) {
        turns++
        if (soulSpoke) turnsSoulSpoke++
    }

    /** The soul's own render latency (ms) for one express() call. */
    fun gemmaRender(ms: Long) = synchronized(lock) {
        if (ms > 0) {
            gemmaRender.add(ms)
            if (gemmaRender.size > gemmaRenderCap) gemmaRender.removeAt(0)
        }
    }

    /** True every [EVERY_N_TURNS] windows — the periodic emit cadence, so a LIVE
     *  session shows its numbers instead of waiting for a hangup (and a process
     *  killed mid-call doesn't take the whole measurement with it). */
    fun shouldEmit(): Boolean = synchronized(lock) {
        emitTurns++
        emitTurns % EVERY_N_TURNS == 0L
    }

    const val EVERY_N_TURNS = 10L

    /** The counters as one honest log line; zeroes preserved (a hold-only
     *  session IS the finding). Never resets the counters. */
    fun line(): String = synchronized(lock) {
        val sw = if (soulFirstWord.isEmpty()) "-" else
            "p50=${pct(soulFirstWord, 50)} p95=${pct(soulFirstWord, 95)}ms"
        val gr = if (gemmaRender.isEmpty()) "-" else
            "p50=${pct(gemmaRender, 50)} p95=${pct(gemmaRender, 95)}ms"
        val soulPct = if (turns == 0L) 0L else turnsSoulSpoke * 100 / turns
        "er: cls(hold=$clsHold yield=$clsYield) " +
            "barge(cancel=$bargeCancel hold=$bargeHold) " +
            "arb(play=$arbPlay preempt=$arbPreempt reject=$arbReject) soul-first-word[$sw] " +
            "turns=$turns soul-spoke=$turnsSoulSpoke (${soulPct}%) gemma-render[$gr]"
    }

    private fun pct(sortedByInsertion: List<Long>, p: Int): Long {
        if (sortedByInsertion.isEmpty()) return 0
        val s = sortedByInsertion.sorted()
        val idx = ((s.size - 1) * p + 99) / 100
        return s[idx.coerceIn(0, s.size - 1)]
    }
}
