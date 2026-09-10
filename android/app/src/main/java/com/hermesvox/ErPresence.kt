package com.hermesvox

import android.content.Context
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
    /** 0.6.7: the user's filler-density slider (Settings ER section).
     *  Read live per tick so a slider change lands on the next turn. */
    private val fillerCap: () -> Int = { ErFillers.MAX_FILLERS_PER_WINDOW },
) {

    private val main = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null
    /** 0.6.7 Tier 1: the presence-voice mode (Settings: silent / sounds / spoken).
     *  Read live per tick. silent = motion only; sounds = ErClips (the natural
     *  nonverbals, private track); spoken = Piper sentence fillers (the old path). */
    var clipContext: Context? = null
    @Volatile var voiceMode: String = "sounds"   // silent | sounds | spoken

    /** Latches for the current mind-work window. */
    @Volatile private var mindStartedAt = 0L
    private val fillerTimes = ArrayList<Long>()
    // 0.6.5: monotonic count of LAG lines said THIS window — the fail-soft line
    // must be once per window (the 3s trailing count let it repeat forever).
    private val lagSaidCount = object { var value = 0 }
    // 0.8/M2: monotonic count of preamble cues OFFERED this window (at most one —
    // a repeated "mm" is the chatty failure the field already rejected).
    private val preambleSaidCount = object { var value = 0 }
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

    /** 0.8/M1: did the SOUL say anything during the current mind-work window? The
     *  window closes when the mind's reply arrives, so this is exactly "did the soul
     *  speak before the reply" — the ER-delta numerator. Reset per window. */
    @Volatile private var spokeThisWindow = false

    /** The user finished an utterance — classify it and open the window.
     *  fromVoice=true only (typed sends never trigger presence). Returns the
     *  route for the caller's log line. */
    fun onUserUtterance(text: String, nowMs: Long): ErIntent.Route {
        spokeThisWindow = false   // 0.8/M1: a new window starts here
        val d = ErIntent.classify(text)
        lastRoute = d.route
        ErTelemetry.classify(d.route)   // Phase 8: the miss-rate denominator
        when (d.route) {
            ErIntent.Route.HOLD_ONLY -> {
                // The patient user. No escalation, no filler; a soft in-register
                // ack (P3, cuttable by a real barge) at most.
                speakGlue("okay — take the time you need")
                spokeThisWindow = true
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
        synchronized(preambleSaidCount) { preambleSaidCount.value = 0 }
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
                val o = ErFillers.tick(now, mindStartedAt, recent, userGoneMs = now - (mindStartedAt - 10_000), cap = fillerCap(), lagSaidCount = synchronized(lagSaidCount) { lagSaidCount.value }, preambleSaid = synchronized(preambleSaidCount) { preambleSaidCount.value })
                // 0.8/M2: the nonverbal preamble cue — the middle rung. Delivered as a
                // CLIP only, never as text (Piper must not read an interjection), so
                // 'spoken' mode stays silent here by design and a missing clip degrades
                // to silence rather than to words. This is the rung that makes ER
                // actually audible on a healthy gateway, where the 4s fail-soft line
                // never fires.
                if (o.state == ErFillers.State.PREAMBLE) {
                    synchronized(preambleSaidCount) { preambleSaidCount.value++ }
                    val ctx = clipContext
                    if (voiceMode == "sounds" && ctx != null &&
                        ErClips.play(ctx, ErClips.clipFor("neutral", 0))) {
                        spokeThisWindow = true
                        if (windowOpenedAt > 0 && synchronized(soulActions) { soulActions.isEmpty() }) {
                            ErTelemetry.soulFirstWord(now - windowOpenedAt)
                        }
                        VoxLog.er("er:preamble clip=neutral")
                    }
                }
                if (o.speak != null) {
                    // Phase 8: soul first-word = the first glue after the window opened.
                    if (windowOpenedAt > 0 && synchronized(soulActions) { soulActions.isEmpty() }) {
                        ErTelemetry.soulFirstWord(now - windowOpenedAt)
                    }
                    synchronized(fillerTimes) { fillerTimes.add(now) }
                    if (o.state == ErFillers.State.LAG_ACK) synchronized(lagSaidCount) { lagSaidCount.value++ }
                    spokeThisWindow = true
                    synchronized(soulActions) { soulActions.add(ErDrift.SoulAction(now, "filler", o.speak!!)) }
                    // 0.6.7 Tier 1: deliver per the presence-voice mode.
                    // sounds = ErClips (private track — never Piper, never the
                    // reply's track/fence); spoken = the old speakGlue path;
                    // silent = never happens here (Tier 0 already gated it).
                    val mode = voiceMode
                    val kind = if (o.state == ErFillers.State.LAG_ACK) "lag" else "neutral"
                    when {
                        mode == "sounds" && clipContext != null -> {
                            val ctx = clipContext!!   // single-threaded tick loop; no concurrent mutation
                            val clip = ErClips.clipFor(kind, synchronized(fillerTimes) { fillerTimes.size })
                            val played = ErClips.play(ctx, clip)
                            if (!played) main.post { speakGlue(o.speak!!) }   // clip missing → spoken fallback
                            else VoxLog.er("er:clip=$clip kind=$kind")
                        }
                        else -> main.post { speakGlue(o.speak!!) }
                    }
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
        ErTelemetry.window(spokeThisWindow)   // 0.8/M1: close the ER-delta window
        spokeThisWindow = false
        stop()
    }

    /** Stop the loop (call end / controller stop). Idempotent. */
    fun stop() {
        tick?.let { main.removeCallbacks(it) }
        tick = null
        active = false
        mindStartedAt = 0L
    }

    // ---- 0.6.8: the gateway-stall voice (the car-run silence fix) ----

    /** The mind's stream has gone quiet for [idleMs] — SAY it's still working
     *  (once per stall) instead of leaving the user to barge a dead turn into
     *  silence. Tier-respecting: a natural clip in sounds mode, the in-character
     *  lag line in spoken mode, nothing in silent mode. */
    fun onGatewayStall(idleMs: Long) {
        if (!active || voiceMode == "silent") return
        // Per-stall once: a second stall notice for the SAME window is noise.
        if (stallVoicedFor == mindStartedAt) return
        stallVoicedFor = mindStartedAt
        val ctx = clipContext
        when {
            voiceMode == "sounds" && ctx != null -> {
                if (!ErClips.play(ctx, ErClips.clipFor("lag", 1))) speakGlue("still working on it — the connection's a little slow")
                VoxLog.er("er:stall-voiced idleMs=$idleMs mode=sounds")
            }
            else -> {
                speakGlue("still working on it — the connection's a little slow")
                VoxLog.er("er:stall-voiced idleMs=$idleMs mode=spoken")
            }
        }
    }
    @Volatile private var stallVoicedFor = 0L
}
