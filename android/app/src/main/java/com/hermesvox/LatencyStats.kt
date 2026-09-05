package com.hermesvox

/** #40 per-stage latency capture. Not user-facing; logs a per-turn summary on
 *  every gate release plus P50/P95 every 8th turn so the realtime loop is
 *  quantifiable + regression-testable. Bounded ring per metric; the rings are a
 *  true rolling 8-turn window (cleared after every 8th emit). */
object LatencyStats {
    private val lock = Object()
    private val firstByte = ArrayList<Long>()
    private val firstAudio = ArrayList<Long>()
    private val fullReply = ArrayList<Long>()
    private val stt = ArrayList<Long>()
    private const val MAX = 128L
    private var turns = 0L
    // Per-turn last values: set by each push*, cleared after each summary emit.
    // Default -1L renders as "-" (no metric this turn).
    private var turnStt = -1L
    private var turnFirstByte = -1L
    private var turnFirstAudio = -1L
    private var turnFullReply = -1L

    fun pushFirstByte(ms: Long) { synchronized(lock) { firstByte.add(ms); trim(firstByte); turnFirstByte = ms } }
    fun pushFirstAudio(ms: Long) { synchronized(lock) { firstAudio.add(ms); trim(firstAudio); turnFirstAudio = ms } }
    fun pushFullReply(ms: Long) { synchronized(lock) { fullReply.add(ms); trim(fullReply); turnFullReply = ms } }
    fun pushStt(ms: Long) { synchronized(lock) { stt.add(ms); trim(stt); turnStt = ms } }

    private fun trim(l: ArrayList<Long>) { while (l.size > MAX) l.removeAt(0) }

    fun log(label: String, outcome: String, gen: Long) {
        summaryLines(label, outcome, gen).forEach { VoxLog.d(it) }
    }

    internal fun summaryLines(label: String, outcome: String, gen: Long): List<String> = synchronized(lock) {
        turns++
        val out = ArrayList<String>()
        out += "event=turn label=$label gen=$gen outcome=$outcome stt=${fmt(turnStt)} " +
               "firstByte=${fmt(turnFirstByte)} firstAudio=${fmt(turnFirstAudio)} fullReply=${fmt(turnFullReply)}"
        turnStt = -1L; turnFirstByte = -1L; turnFirstAudio = -1L; turnFullReply = -1L
        if (turns % 8L == 0L) {
            out += "event=lat label=$label metric=first-byte p50=${pct(firstByte,0.50)} p95=${pct(firstByte,0.95)} n=${firstByte.size}"
            out += "event=lat label=$label metric=first-audio p50=${pct(firstAudio,0.50)} p95=${pct(firstAudio,0.95)} n=${firstAudio.size}"
            out += "event=lat label=$label metric=full-reply p50=${pct(fullReply,0.50)} p95=${pct(fullReply,0.95)} n=${fullReply.size}"
            out += "event=lat label=$label metric=stt p50=${pct(stt,0.50)} p95=${pct(stt,0.95)} n=${stt.size}"
            firstByte.clear(); firstAudio.clear(); fullReply.clear(); stt.clear()   // rolling window
        }
        out
    }
    private fun fmt(v: Long) = if (v < 0L) "-" else v.toString()
    internal fun windowCounts(): IntArray = synchronized(lock) { intArrayOf(firstByte.size, firstAudio.size, fullReply.size, stt.size) }
    fun reset() { synchronized(lock) { firstByte.clear(); firstAudio.clear(); fullReply.clear(); stt.clear(); turns = 0 } }
    private fun pct(l: List<Long>, p: Double): Long { if (l.isEmpty()) return 0L; val s = l.sorted(); return s[(s.size - 1).toDouble().times(p).toInt()] }
}
