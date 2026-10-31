package com.hermesvox

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * ErPresence — the presence loop (ER Phase 4), owned by the VoiceController.
 * Composes the two pure pieces — [ErIntent] (the classifier)
 * and [ErFillers] (the filler state machine) — into the live presence:
 *
 *  - user speech → classify → BACKCHANNEL holds (no escalation, maybe a soft
 *    ack), EMOTION/SMALLTALK the soul converses directly (Gemma), INFORMATION/
 *    ACTION the soul acknowledges ("let me think…") and the MIND is engaged;
 *  - while the mind works → bounded fillers on the ~1s tick, density-capped,
 *    fail-soft after 4s; then, on the same tick, the mid-run asides [PresenceCadence]
 *    paces (one soul line per interval, capped per run) so a long tool call is not
 *    71 seconds of silence;
 *  - the mind's reply preempts everything (the existing speakGlue/speak
 *    precedence in VoiceController is untouched — this feeds it, never races it).
 *
 * KEEP-LIST NOTE: this loop ADDS glue speech only through speakGlue (the
 * existing P3 path) — it never touches speak(), the gate, or the streaming
 * worker. When ER is off, presenceAt/erActive stay false and the loop is a
 * no-op (normal operation byte-identical).
 */
class ErPresence(
    /** utterance + a SOURCE tag, so the field log can name who spoke (0.8/M3). */
    private val speakGlue: (String, String) -> Unit,
    /** The filler cap per window — set by the "Presence while working" register (Settings ER
     *  section). Read live per tick so a change lands on the next turn. */
    private val fillerCap: () -> Int = { ErFillers.MAX_FILLERS_PER_WINDOW },
    /** The "Presence while working" register — the mid-run aside interval and cap. Read live. */
    private val density: () -> PresenceCadence.Density = { PresenceCadence.DEFAULT },
) {

    /** A mid-run aside slot: the line's number in the run, the gap since the previous line, the
     *  register that paced it, and the run it belongs to (its start) so a late line can be told. */
    data class Slot(val line: Int, val intervalMs: Long, val density: PresenceCadence.Density, val runId: Long)

    /**
     * Asked on the tick when a mid-run aside is due (line 2 onward — line 1 is the lag line). The
     * owner renders the soul's line and reports back with [noteWorkingLine] / [noteWorkingSkip].
     * Null = no mid-run asides: the loop behaves exactly as before the cadence existed.
     */
    var onWorkingSlot: ((Slot) -> Unit)? = null

    /** The cadence's run; [PresenceCadence.Run.IDLE] when no mind is at work. Guarded by [cadenceLock]. */
    private var run = PresenceCadence.Run.IDLE
    private val cadenceLock = Any()
    /** The run's end has been logged (the cap logs it while the last line may still land). */
    private var cadenceEndLogged = true

    private val main = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null
    /** 0.6.7 Tier 1: the narration register (Settings: silent / sounds / spoken).
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
    val silentProxy: ErPresence by lazy { ErPresence({ _, _ -> /* presence muted */ }) }

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
        firstWordSeen = false     // 0.8/M2.1: and so does the first-word metric
        val d = ErIntent.classify(text)
        lastRoute = d.route
        ErTelemetry.classify(d.route)   // Phase 8: the miss-rate denominator
        when (d.route) {
            ErIntent.Route.HOLD_ONLY -> {
                // The patient user. No escalation, no filler; a soft in-register
                // ack (P3, cuttable by a real barge) at most.
                speakGlue("okay — take the time you need", "presence-hold")
                spokeThisWindow = true
                VoxLog.er("er:intent=backchannel route=hold")
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

    /**
     * The soul spoke a line of its own this window — a beat or a full answer. Recorded for the
     * mind's soul-sync (synchronously, so it is in the epilogue the mind's turn carries), and it
     * IS the window's presence: the nonverbal preamble must not also play, and the first-word
     * metric starts here.
     */
    fun noteSoulSpoke(kind: String, text: String, nowMs: Long) {
        synchronized(preambleSaidCount) { preambleSaidCount.value++ }
        spokeThisWindow = true
        markFirstWord(nowMs, mindStartedAt)
        synchronized(soulActions) { soulActions.add(ErDrift.SoulAction(nowMs, kind, text)) }
    }

    /** The mind (gateway) has started working — arm the filler tick and the cadence. */
    fun startWindow(nowMs: Long) {
        mindStartedAt = nowMs
        synchronized(cadenceLock) {
            run = if (onWorkingSlot != null) PresenceCadence.Run.start(nowMs) else PresenceCadence.Run.IDLE
            cadenceEndLogged = onWorkingSlot == null
        }
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
                        markFirstWord(now, windowOpenedAt)
                        VoxLog.er("er:preamble clip=neutral")
                    }
                }
                if (o.speak != null) {
                    // Phase 8: soul first-word = the first cue after the window opened.
                    markFirstWord(now, windowOpenedAt)
                    synchronized(fillerTimes) { fillerTimes.add(now) }
                    if (o.state == ErFillers.State.LAG_ACK) synchronized(lagSaidCount) { lagSaidCount.value++ }
                    spokeThisWindow = true
                    synchronized(soulActions) { soulActions.add(ErDrift.SoulAction(now, "filler", o.speak!!)) }
                    // 0.6.7 Tier 1: deliver per the narration register.
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
                            if (!played) main.post { speakGlue(o.speak!!, "presence-filler-clip-fallback") }   // clip missing → spoken fallback
                            else VoxLog.er("er:clip=$clip kind=$kind")
                        }
                        else -> main.post { speakGlue(o.speak!!, "presence-filler") }
                    }
                }
                // The cadence: line 1 is the lag line the ladder above just said (the same 4 s
                // rule, the same tick); every later line is a soul render, one interval apart.
                val d = density()
                var slot: Slot? = null
                synchronized(cadenceLock) {
                    val r = run
                    if (PresenceCadence.shouldSpeak(now, r, d)) {
                        run = r.said(now)
                        slot = Slot(r.linesSaid + 1, r.gap(now), d, r.startedAt)
                    }
                }
                slot?.let { s ->
                    if (s.line > 1) onWorkingSlot?.invoke(s)
                    else if (o.state == ErFillers.State.LAG_ACK) noteWorkingLine(s, "lag")
                    else noteWorkingSkip(s, "lag-not-said")
                }
                if (o.state == ErFillers.State.SILENT && now - mindStartedAt > ErFillers.LAG_AFTER_MS + 8_000) {
                    val cadenceLeft = synchronized(cadenceLock) {
                        run.startedAt != 0L && !PresenceCadence.capped(run, d)
                    }
                    if (!cadenceLeft) {
                        // The cap ends the cadence, but the run stays live so the last line —
                        // still rendering — may land; the reply/barge/call end closes it.
                        synchronized(cadenceLock) {
                            if (run.startedAt != 0L && !cadenceEndLogged) {
                                cadenceEndLogged = true
                                VoxLog.er("event=er-presence stop reason=cap lines=${run.linesSaid}")
                            }
                        }
                        halt()   // long stall: the waiting-constellation motion carries it from here
                        return
                    }
                }
                main.postDelayed(this, 1_000L)
            }
        }
        tick = t
        main.postDelayed(t, 1_000L)
    }

    /** 0.8/M2.1: the FIRST soul output of a window owns the first-word metric — once.
     *  The old marker was `soulActions.isEmpty()`, and the preamble deliberately does
     *  NOT add a drift-sync entry (a nonverbal is not information worth syncing to the
     *  mind) — so the later lag cue re-recorded and inflated p95 ~4x in the 09-10 field
     *  log: p50=1007ms, p95=4050ms, from three cues that all measured ~1.0s. */
    private fun markFirstWord(now: Long, windowOpenedAt: Long) {
        if (firstWordSeen) return
        firstWordSeen = true
        if (windowOpenedAt > 0) ErTelemetry.soulFirstWord(now - windowOpenedAt)
    }

    @Volatile private var firstWordSeen = false

    /** The mind's reply arrived (or the turn was cut) — everything stops; the
     *  reply's own speech has precedence via the existing speak() path. */
    fun onMindReply() {
        ErTelemetry.window(spokeThisWindow)   // 0.8/M1: close the ER-delta window
        spokeThisWindow = false
        stop("reply")
    }

    /** Stop the loop — [reason] names why the cadence ended (reply, barge, hush, call-end). Idempotent. */
    fun stop(reason: String = "call-end") {
        endCadence(reason)
        halt()
    }

    /**
     * End the mid-run cadence NOW: no further slots, and a line still rendering is refused at
     * delivery ([cadenceLive]). The filler tick is left alone. Idempotent; logs the end once.
     */
    fun endCadence(reason: String) = synchronized(cadenceLock) {
        if (run.startedAt == 0L) return@synchronized
        if (!cadenceEndLogged) VoxLog.er("event=er-presence stop reason=$reason lines=${run.linesSaid}")
        cadenceEndLogged = true
        run = PresenceCadence.Run.IDLE
    }

    /** Is the run [runId] still accepting lines (no reply, barge or call end since)? */
    fun cadenceLive(runId: Long): Boolean = synchronized(cadenceLock) { runId != 0L && run.startedAt == runId }

    /** A line of the run was voiced — the field log's proof that the gap was held. */
    fun noteWorkingLine(slot: Slot, kind: String) =
        VoxLog.er("event=er-presence line=${slot.line} interval=${slot.intervalMs} density=${slot.density.key} kind=$kind")

    /** A slot passed in silence, and why (the soul chose silence, the reply landed first, …). */
    fun noteWorkingSkip(slot: Slot, reason: String) =
        VoxLog.er("event=er-presence skip line=${slot.line} reason=$reason density=${slot.density.key}")

    /**
     * D20: the line was SHORTENED, not silenced. Shape repair (a clause cut, markup, a fence, a
     * title line, a trailing courtesy) changed the render and the result was still spoken. Logged
     * separately from `skip` so the field can tell the two apart at a glance.
     */
    fun noteWorkingRepair(slot: Slot, reason: String) =
        VoxLog.er("event=er-presence repair line=${slot.line} reason=$reason density=${slot.density.key}")

    /** Stop the tick. */
    private fun halt() {
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
                if (!ErClips.play(ctx, ErClips.clipFor("lag", 1))) speakGlue("still working on it — the connection's a little slow", "presence-stall-clip-fallback")
                VoxLog.er("er:stall-voiced idleMs=$idleMs mode=sounds")
            }
            else -> {
                speakGlue("still working on it — the connection's a little slow", "presence-stall")
                VoxLog.er("er:stall-voiced idleMs=$idleMs mode=spoken")
            }
        }
    }
    @Volatile private var stallVoicedFor = 0L
}
