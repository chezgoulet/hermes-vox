package com.hermesvox

import android.os.Handler
import android.os.Looper

/**
 * ErPresence — the Enhanced Realtime presence loop (ER Phase 4), owned by the
 * VoiceController. Composes the two pure pieces — [ErIntent] (the classifier)
 * and [ErFillers] (the filler state machine) — into the live presence:
 *
 *  - user speech → classify → BACKCHANNEL holds (no escalation, maybe a soft
 *    ack), EMOTION/SMALLTALK the soul converses directly (Gemma), INFORMATION/
 *    ACTION the soul acknowledges ("let me think…") and the MIND is engaged;
 *  - while the mind works → bounded fillers on the ~1s tick, density-capped,
 *    fail-soft after 4s, silent after the one lag line;
 *  - the mind's reply preempts everything (the existing speakGlue/speak
 *    precedence in VoiceController is untouched — this feeds it, never races it).
 *
 * KEEP-LIST NOTE: this loop ADDS glue speech only through speakGlue (the
 * existing P3 path) — it never touches speak(), the gate, or the streaming
 * worker. When ER is off, presenceAt/erActive stay false and the loop is a
 * no-op (Realtime behavior byte-identical).
 */
class ErPresence(
    private val speakGlue: (String) -> Unit,
    /** 0.6.2: the user's filler-density slider (Settings ER section).
     *  Read live per tick so a slider change lands on the next turn. */
    private val fillerCap: () -> Int = { ErFillers.MAX_FILLERS_PER_WINDOW },
) {

    private val main = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null

    /** Latches for the current mind-work window. */
    @Volatile private var mindStartedAt = 0L
    private val fillerTimes = ArrayList<Long>()
    // 0.6.5: monotonic count of LAG lines said THIS window — the fail-soft line
    // must be once per window (the 3s trailing count let it repeat forever).
    private val lagSaidCount = object { var value = 0 }
    private var lastRoute: ErIntent.Route = ErIntent.Route.ACK_AND_YIELD
    // ER Phase 7: what the soul already voiced this turn (the drift-sync log
    // the mind sees so it doesn't re-state confirmations).
    private val soulActions = ArrayList<ErDrift.SoulAction>()

    /** The turn's soul-action log + vibe (consumed at submit, then cleared). */
    fun drainSoulActions(): List<ErDrift.SoulAction> = synchronized(soulActions) {
        val out = ArrayList(soulActions); soulActions.clear(); out
    }

    /** 0.6.2: presence OFF — the classifier/telemetry/log still run (the mind's
     *  drift-sync stays honest) but every speakGlue is swallowed. A live mute,
     *  not a teardown: the window lifecycle is unchanged. */
    val silentProxy: ErPresence by lazy { ErPresence({ /* presence muted */ }) }

    /** True while the presence loop is running (diagnostics/ER label). */
    @Volatile var active = false
        private set

    /** The user finished an utterance — classify it and open the window.
     *  fromVoice=true only (typed sends never trigger presence). Returns the
     *  route for the caller's log line. */
    fun onUserUtterance(text: String, nowMs: Long): ErIntent.Route {
        val d = ErIntent.classify(text)
        lastRoute = d.route
        ErTelemetry.classify(d.route)   // Phase 8: the miss-rate denominator
        when (d.route) {
            ErIntent.Route.HOLD_ONLY -> {
                // The patient user. No escalation, no filler; a soft in-register
                // ack (P3, cuttable by a real barge) at most.
                speakGlue("okay — take the time you need")
                VoxLog.er("er:intent=backchannel route=hold")
            }
            ErIntent.Route.SOUL_DIRECT -> {
                // The soul's own lane (emotion/smalltalk): Gemma converses directly.
                // The expression itself is rendered by the GemmaExpress path in the
                // host; presence only opens a quiet window (no fillers needed —
                // the soul is speaking).
                mindStartedAt = nowMs
                VoxLog.er("er:intent=${d.cls.name.lowercase()} route=soul-direct")
            }
            ErIntent.Route.ACK_AND_YIELD -> {
                // The mind's lane: ack + yield (Miles rule #1). Open the filler window.
                // 0.6.7 Tier 0: the SPOKEN ack is gone for short waits — silence +
                // motion IS the ack (ErFillers.SILENCE_FIRST_MS gates the tick).
                // Opening the window is all this route does now; the fail-soft
                // line at 4s+ is the first voice.
                startWindow(nowMs)
                VoxLog.er("er:intent=${d.cls.name.lowercase()} route=ack-yield mode=silence-first")
            }
        }
        return d.route
    }

    /** The mind (gateway) has started working — arm the filler tick. */
    fun startWindow(nowMs: Long) {
        mindStartedAt = nowMs
        synchronized(fillerTimes) { fillerTimes.clear() }
        synchronized(lagSaidCount) { lagSaidCount.value = 0 }
        active = true
        arm()
    }

    private fun arm() {
        if (tick != null) return
        var windowOpenedAt = 0L
        val t = object : Runnable {
            override fun run() {
                val now = android.os.SystemClock.uptimeMillis()
                val recent = synchronized(fillerTimes) { ErFillers.countRecent(fillerTimes, now) }
                if (mindStartedAt > 0 && windowOpenedAt != mindStartedAt) windowOpenedAt = mindStartedAt
                val o = ErFillers.tick(now, mindStartedAt, recent, warm = false, userGoneMs = now - (mindStartedAt - 10_000), cap = fillerCap(), lagSaidCount = synchronized(lagSaidCount) { lagSaidCount.value })
                if (o.speak != null) {
                    // Phase 8: soul first-word = the first glue after the window opened.
                    if (windowOpenedAt > 0 && synchronized(soulActions) { soulActions.isEmpty() }) {
                        ErTelemetry.soulFirstWord(now - windowOpenedAt)
                    }
                    synchronized(fillerTimes) { fillerTimes.add(now) }
                    if (o.state == ErFillers.State.LAG_ACK) synchronized(lagSaidCount) { lagSaidCount.value++ }
                    synchronized(soulActions) { soulActions.add(ErDrift.SoulAction(now, "filler", o.speak!!)) }
                    main.post { speakGlue(o.speak!!) }
                }
                if (o.state == ErFillers.State.SILENT && now - mindStartedAt > ErFillers.LAG_AFTER_MS + 8_000) {
                    stop()   // long stall: the waiting-constellation motion carries it from here
                    return
                }
                main.postDelayed(this, 1_000L)
            }
        }
        tick = t
        main.postDelayed(t, 1_000L)
    }

    /** The mind's reply arrived (or the turn was cut) — everything stops; the
     *  reply's own speech has precedence via the existing speak() path. */
    fun onMindReply() {
        stop()
    }

    /** Stop the loop (call end / controller stop). Idempotent. */
    fun stop() {
        tick?.let { main.removeCallbacks(it) }
        tick = null
        active = false
        mindStartedAt = 0L
    }
}
