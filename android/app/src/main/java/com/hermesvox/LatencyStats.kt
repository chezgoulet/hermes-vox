package com.hermesvox

/** #40 per-stage latency capture. Not user-facing; logs P50/P95 so the realtime
 *  loop is quantifiable + regression-testable. Bounded ring per metric. */
object LatencyStats {
    private val lock = Object()
    private val firstByte = ArrayList<Long>()
    private val firstAudio = ArrayList<Long>()
    private val fullReply = ArrayList<Long>()
    private val stt = ArrayList<Long>()
    private const val MAX = 128L
    private var turns = 0L

    fun pushFirstByte(ms: Long) { synchronized(lock) { firstByte.add(ms); trim(firstByte) } }
    fun pushFirstAudio(ms: Long) { synchronized(lock) { firstAudio.add(ms); trim(firstAudio) } }
    fun pushFullReply(ms: Long) { synchronized(lock) { fullReply.add(ms); trim(fullReply) } }
    fun pushStt(ms: Long) { synchronized(lock) { stt.add(ms); trim(stt) } }

    private fun trim(l: ArrayList<Long>) { while (l.size > MAX) l.removeAt(0) }

    fun log(label: String) {
        synchronized(lock) {
            turns++
            if (turns % 8L != 0L) return
            VoxLog.d("lat[$label] ttf-first-byte p50=${pct(firstByte,0.50)} p95=${pct(firstByte,0.95)} n=${firstByte.size}")
            VoxLog.d("lat[$label] ttf-first-audio p50=${pct(firstAudio,0.50)} p95=${pct(firstAudio,0.95)} n=${firstAudio.size}")
            VoxLog.d("lat[$label] ttf-full-reply p50=${pct(fullReply,0.50)} p95=${pct(fullReply,0.95)} n=${fullReply.size}")
            VoxLog.d("lat[$label] stt(speech->text) p50=${pct(stt,0.50)} p95=${pct(stt,0.95)} n=${stt.size}")
        }
    }
    fun reset() { synchronized(lock) { firstByte.clear(); firstAudio.clear(); fullReply.clear(); stt.clear(); turns = 0 } }
    private fun pct(l: List<Long>, p: Double): Long { if (l.isEmpty()) return 0L; val s = l.sorted(); return s[(s.size - 1).toDouble().times(p).toInt()] }
}
