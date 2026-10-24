package com.hermesvox

/** #40 per-stage latency capture. Not user-facing; logs a per-turn summary on
 *  every gate release plus P50/P95 every 8th turn so the realtime loop is
 *  quantifiable + regression-testable. Bounded ring per metric; the rings are a
 *  true rolling 8-turn window (cleared after every 8th emit). C4: firstText is
 *  the first TEXT delta of a turn; firstAudio is pushed ONLY when the first real
 *  audible audio write of a turn completes (so the field now means what it says —
 *  the two are NOT comparable across 0.4.0 versions). */
object LatencyStats {
    private val lock = Object()
    private val firstByte = ArrayList<Long>()
    private val firstText = ArrayList<Long>()
    private val firstAudio = ArrayList<Long>()
    private val fullReply = ArrayList<Long>()
    private val stt = ArrayList<Long>()
    // Phase-1 (#209 gap 1): the same turn, measured from the END OF SPEECH. The five rings
    // above keep their old origins (turn launch / segment open) for continuity with every
    // baseline already recorded; these four are the conversational ones — what D18's
    // 700 ms / 1.4 s target is actually about, because the caller starts waiting when
    // they stop talking, not when the client opens a buffer.
    private val speechToText = ArrayList<Long>()
    private val speechToAudio = ArrayList<Long>()
    private val speechToRender = ArrayList<Long>()
    private val sttDecode = ArrayList<Long>()
    private const val MAX = 128L
    private var turns = 0L
    // C3 session hygiene (measurement only — NO truncation/summarization behavior):
    // turns counted since the last resetConversation / new call, surfaced on every
    // event=turn so gateway context creep is visible in field logs. MainActivity
    // calls resetSessionTurns() beside every session.resetConversation() (the sites
    // where a conversation is born); summaryLines increments it per completed turn.
    private var sessionTurns = 0L
    // Per-turn last values: set by each push*, cleared after each summary emit.
    // Default -1L renders as "-" (no metric this turn).
    private var turnStt = -1L
    private var turnFirstByte = -1L
    private var turnFirstText = -1L
    private var turnFirstAudio = -1L
    private var turnFullReply = -1L
    // Phase 1: speech-end anchored + the recogniser's own time for this turn's segment.
    private var turnSpeechToText = -1L
    private var turnSpeechToAudio = -1L
    private var turnSpeechToRender = -1L
    private var turnSttDecode = -1L

    fun pushFirstByte(ms: Long) { synchronized(lock) { firstByte.add(ms); trim(firstByte); turnFirstByte = ms } }
    fun pushFirstText(ms: Long) { synchronized(lock) { firstText.add(ms); trim(firstText); turnFirstText = ms } }
    fun pushFirstAudio(ms: Long) { synchronized(lock) { firstAudio.add(ms); trim(firstAudio); turnFirstAudio = ms } }
    fun pushFullReply(ms: Long) { synchronized(lock) { fullReply.add(ms); trim(fullReply); turnFullReply = ms } }
    fun pushStt(ms: Long) { synchronized(lock) { stt.add(ms); trim(stt); turnStt = ms } }

    /** speech_end → the first text delta of the reply, in ms. */
    fun pushSpeechToText(ms: Long) { synchronized(lock) { speechToText.add(ms); trim(speechToText); turnSpeechToText = ms } }

    /** speech_end → the first AUDIBLE audio write (the same seam as [pushFirstAudio]). */
    fun pushSpeechToAudio(ms: Long) { synchronized(lock) { speechToAudio.add(ms); trim(speechToAudio); turnSpeechToAudio = ms } }

    /** speech_end → the reply's last audio chunk finished playing ("render done"). */
    fun pushSpeechToRender(ms: Long) { synchronized(lock) { speechToRender.add(ms); trim(speechToRender); turnSpeechToRender = ms } }

    /** The recogniser's own work for this turn's segment: the live feeds plus the tail
     *  decode, or the whole-clip decode when the live stream could not finish. It is NOT
     *  wall clock between utterances — that is the `stt=` field's old, misleading meaning. */
    fun pushSttDecode(ms: Long) { synchronized(lock) { sttDecode.add(ms); trim(sttDecode); turnSttDecode = ms } }

    private fun trim(l: ArrayList<Long>) { while (l.size > MAX) l.removeAt(0) }

    /** Zero the per-session turn count. Called by the host beside every
     *  session.resetConversation() (a new call / new conversation). */
    fun resetSessionTurns() { synchronized(lock) { sessionTurns = 0L } }

    /** #284: turns since the last reset — when a conversation is long enough to offer /compress. */
    fun sessionTurns(): Long = synchronized(lock) { sessionTurns }

    fun log(label: String, outcome: String, gen: Long) {
        summaryLines(label, outcome, gen).forEach { VoxLog.d(it) }
    }

    internal fun summaryLines(label: String, outcome: String, gen: Long): List<String> = synchronized(lock) {
        turns++
        sessionTurns++   // C3: cumulative turns since the last resetConversation/new call
        val out = ArrayList<String>()
        out += "event=turn label=$label gen=$gen outcome=$outcome stt=${fmt(turnStt)} " +
               "firstByte=${fmt(turnFirstByte)} firstText=${fmt(turnFirstText)} firstAudio=${fmt(turnFirstAudio)} fullReply=${fmt(turnFullReply)} " +
               "speechEndToFirstText=${fmt(turnSpeechToText)} speechEndToFirstAudio=${fmt(turnSpeechToAudio)} " +
               "speechEndToRenderDone=${fmt(turnSpeechToRender)} sttDecodeMs=${fmt(turnSttDecode)} " +
               "session_turns=$sessionTurns"
        turnStt = -1L; turnFirstByte = -1L; turnFirstText = -1L; turnFirstAudio = -1L; turnFullReply = -1L
        turnSpeechToText = -1L; turnSpeechToAudio = -1L; turnSpeechToRender = -1L; turnSttDecode = -1L
        if (turns % 8L == 0L) {
            out += "event=lat label=$label metric=first-byte p50=${pct(firstByte,0.50)} p95=${pct(firstByte,0.95)} n=${firstByte.size}"
            out += "event=lat label=$label metric=first-text p50=${pct(firstText,0.50)} p95=${pct(firstText,0.95)} n=${firstText.size}"
            out += "event=lat label=$label metric=first-audio p50=${pct(firstAudio,0.50)} p95=${pct(firstAudio,0.95)} n=${firstAudio.size}"
            out += "event=lat label=$label metric=full-reply p50=${pct(fullReply,0.50)} p95=${pct(fullReply,0.95)} n=${fullReply.size}"
            out += "event=lat label=$label metric=stt p50=${pct(stt,0.50)} p95=${pct(stt,0.95)} n=${stt.size}"
            out += "event=lat label=$label metric=speech-to-text p50=${pct(speechToText,0.50)} p95=${pct(speechToText,0.95)} n=${speechToText.size}"
            out += "event=lat label=$label metric=speech-to-audio p50=${pct(speechToAudio,0.50)} p95=${pct(speechToAudio,0.95)} n=${speechToAudio.size}"
            out += "event=lat label=$label metric=speech-to-render p50=${pct(speechToRender,0.50)} p95=${pct(speechToRender,0.95)} n=${speechToRender.size}"
            out += "event=lat label=$label metric=stt-decode p50=${pct(sttDecode,0.50)} p95=${pct(sttDecode,0.95)} n=${sttDecode.size}"
            // rolling window
            firstByte.clear(); firstText.clear(); firstAudio.clear(); fullReply.clear(); stt.clear()
            speechToText.clear(); speechToAudio.clear(); speechToRender.clear(); sttDecode.clear()
        }
        out
    }
    private fun fmt(v: Long) = if (v < 0L) "-" else v.toString()
    internal fun windowCounts(): IntArray = synchronized(lock) {
        intArrayOf(firstByte.size, firstText.size, firstAudio.size, fullReply.size, stt.size,
            speechToText.size, speechToAudio.size, speechToRender.size, sttDecode.size)
    }
    fun reset() {
        synchronized(lock) {
            firstByte.clear(); firstText.clear(); firstAudio.clear(); fullReply.clear(); stt.clear()
            speechToText.clear(); speechToAudio.clear(); speechToRender.clear(); sttDecode.clear()
            turns = 0; sessionTurns = 0
        }
    }

    private fun pct(l: List<Long>, p: Double): Long { if (l.isEmpty()) return 0L; val s = l.sorted(); return s[(s.size - 1).toDouble().times(p).toInt()] }
}
