package com.hermesvox

/**
 * MotionState — the pure, emulator-free rule for WHAT THE BEING IS DOING WITH ITS
 * BODY (0.5.0-previewB state-driven presence motion).
 *
 * Before this, the avatar's motion was chosen at the call site: MainActivity's
 * onState pushed a state string + a hardcoded level, onLog pushed a tool motif, and
 * everything else — provider latency, a barge, a natural retire — looked identical
 * to an idle screensaver. The being LOOPED; it did not report. A 15s provider stall
 * and a finished turn rendered the same dispersed aura, so dead air was
 * indistinguishable from rest.
 *
 * The fix is to make motion a function of the work, in one table:
 *
 *   listening   -> BREATHING            slow, low-amplitude, receptive
 *   thinking    -> GATHERING            particles draw inward, brighten, orbit
 *   speaking    -> voice-coupled pulse  amplitude locked to the REAL AudioTrack RMS
 *   stalled     -> WAITING constellation "still here, still working"
 *   barge/hush  -> RECOIL               fast, respectful — it heard you
 *   tool call   -> the tool's MOTIF     (existing AvatarView.onTool geometry)
 *   tool result -> satisfied SHIMMER    (existing AvatarView.pulseTool)
 *   retire      -> SETTLE back to breathing
 *   at rest     -> IDLE, then a gentle DRIFT (never frozen, never busy)
 *
 * Two precedence rules carry the whole design and are the reason this is a table
 * and not a chain of ifs at the call site:
 *
 *  - STALL OVERRIDES THINKING/SPEAKING. While the provider is silent, the ambient
 *    state signals keep repeating ("thinking", "thinking", ...). If they were
 *    allowed through, the being would flip back to gathering every poll and the
 *    stall would never be visible — the exact dead-air symptom. Only [Signal.RESUME]
 *    (real bytes moved again) or an EDGE event clears it.
 *  - EDGE EVENTS OUTRANK THE STALL. [Signal.TOOL_CALL], [Signal.TOOL_DONE],
 *    [Signal.LISTEN] and [Signal.BARGE] fire only when something actually happened,
 *    so they are proof the stall is over and they break it.
 *
 * RECOIL is a ONE-SHOT: this table sends any signal to [Motion.RECOIL] on a barge,
 * but the ~[RECOIL_MS] hold and the return to the interrupted motion are the
 * CALLER's job (MainActivity keeps a staysUntil + the prior motion). A pure table
 * has no clock, so it does not pretend to own one.
 *
 * Pure JVM — no Android deps — so the whole vocabulary is proven off-device, the
 * same way BargeGate / ReplySettleRule / StreamRetirementState are.
 */
object MotionState {

    /** The motion vocabulary. One entry = one thing the being can be seen doing. */
    enum class Motion {
        /** Receptive: a slow, low-amplitude breath. The mic is open. */
        LISTENING,
        /** Concentrating: particles draw inward, brighten, orbit. */
        THINKING,
        /** Voicing: the waveform, amplitude locked to the real playback RMS. */
        SPEAKING,
        /** Provider latency: a slow drifting constellation — still here, still working. */
        STALL,
        /** Interrupted: a fast outward flinch. One-shot (see [RECOIL_MS]). */
        RECOIL,
        /** A tool is running: the tool's own motif geometry. */
        TOOL,
        /** A tool result landed: a brief satisfied shimmer. */
        TOOL_RESULT,
        /** A turn retired naturally: settling back toward the breath. */
        SETTLE,
        /** At rest between turns. */
        IDLE,
        /** Rest, sustained: a wider, slower drift. Never frozen, never busy. */
        DRIFT,
    }

    /**
     * The live world, distilled to the smallest set of things that can change the
     * motion. AMBIENT signals ([THINK], [SPEAK]) repeat every poll and are what the
     * stall must be able to outrank; EDGE signals fire once, when something really
     * happened, and are what may break a stall.
     */
    enum class Signal {
        /** ambient: the mic is open (controller state "listening"). */
        LISTEN,
        /** ambient: the turn is working (controller state "thinking"/"streaming"). */
        THINK,
        /** ambient: audio is being voiced (controller state "speaking"). */
        SPEAK,
        /** edge: no stream activity for >= [STALL_MS], or a `stream-stall` log event. */
        STALL_ON,
        /** edge: stream activity resumed after a stall. */
        RESUME,
        /** edge: a tool was CALLED (`◆ tool:`). */
        TOOL_CALL,
        /** edge: a tool RESULT landed (`◆ tool ·`). */
        TOOL_DONE,
        /** edge: barge-in / hush / stop — the user cut in. */
        BARGE,
        /** edge: the turn ended on its own (tts-retire / final reply settled). */
        RETIRE,
        /** ambient: the controller is idle. */
        REST,
        /** ambient: at rest long enough that the being should widen into a drift. */
        QUIET,
    }

    /** How long a [Motion.RECOIL] is held before the caller restores the prior motion. */
    const val RECOIL_MS = 350L

    /** No stream activity for this long reads as a stall worth SHOWING. Above the
     *  normal inter-token cadence (tokens arrive continuously while a reply streams),
     *  well under the controller's own 5s `stream-stall` log threshold — the being
     *  should look like it is concentrating long before the log calls it a problem. */
    const val STALL_MS = 2500L

    /** Motions the stall may interrupt: only the ones that mean "work is in flight".
     *  A stall signal while LISTENING or at rest is meaningless — nothing is pending. */
    private val STALLABLE = setOf(Motion.THINKING, Motion.SPEAKING, Motion.TOOL, Motion.TOOL_RESULT)

    /** Motions a [Signal.QUIET] may widen into a drift: only the at-rest ones. */
    private val RESTFUL = setOf(Motion.IDLE, Motion.SETTLE, Motion.DRIFT)

    /**
     * The transition table. Total: every (motion, signal) pair has an answer, and an
     * answer that changes nothing returns [current] rather than an invalid state.
     */
    fun transition(current: Motion, signal: Signal): Motion = when (signal) {
        // The user cut in. Outranks everything, from any motion — being interrupted
        // is the one thing the being must always be seen to notice.
        Signal.BARGE -> Motion.RECOIL
        // Edge events: proof that work moved, so they also break a stall.
        Signal.TOOL_CALL -> Motion.TOOL
        Signal.TOOL_DONE -> Motion.TOOL_RESULT
        Signal.LISTEN -> Motion.LISTENING
        Signal.RETIRE -> Motion.SETTLE
        // Stall: only meaningful over work in flight.
        Signal.STALL_ON -> if (current in STALLABLE) Motion.STALL else current
        // Resume lands on THINKING: bytes moved, so the turn is working again, and the
        // very next ambient signal (SPEAK/LISTEN/REST) corrects it within a frame.
        Signal.RESUME -> if (current == Motion.STALL) Motion.THINKING else current
        // Ambient signals — these repeat, so the stall outranks them (the presence lever).
        Signal.THINK -> if (current == Motion.STALL) current else Motion.THINKING
        Signal.SPEAK -> if (current == Motion.STALL) current else Motion.SPEAKING
        Signal.REST -> if (current == Motion.STALL) current else Motion.IDLE
        Signal.QUIET -> if (current in RESTFUL) Motion.DRIFT else current
    }

    /**
     * The render params for a motion — the ONE place a motion is turned into drive
     * values, so no animation code hardcodes a shape per state and the whole feel can
     * be swept (rich/energetic here; scale [speed]/[bright] down for minimal) without
     * touching AvatarView's geometry.
     *
     * @param shape  the AvatarView shape key this motion renders as
     * @param radius sphere radius, as a multiple of the view's base R
     * @param speed  particle/orbit speed multiplier
     * @param bright base alpha 0..1
     * @param theme  color theme key (AvatarView maps it to a baked glow color)
     * @param orbit  radial bias: < 0 draws inward (gathering), > 0 pushes outward
     */
    data class Params(
        val shape: String,
        val radius: Float,
        val speed: Float,
        val bright: Float,
        val theme: String,
        val orbit: Float,
    )

    /**
     * @param workload 0..1 effort intensity (tool depth / turn activity)
     * @param amp      0..1 REAL voice amplitude (playback-head RMS), 0 when silent
     */
    fun renderParams(m: Motion, workload: Float, amp: Float): Params {
        val w = workload.coerceIn(0f, 1f)
        val a = amp.coerceIn(0f, 1f)
        return when (m) {
            // Breathing: wide, slow, dim — a receptive field, not a busy one.
            Motion.LISTENING -> Params("listening", 0.98f, 0.55f, 0.60f, "listen", 0f)
            // Gathering: inward and brightening, and the harder the work the faster.
            Motion.THINKING -> Params("gather", 0.78f, 1.15f + w * 1.9f, 0.74f + w * 0.22f, "think", -0.55f)
            // Voice-coupled: EVERY drive value moves with the real RMS, so the being
            // visibly dances with the syllable instead of running a fixed pulse.
            Motion.SPEAKING -> Params("speaking", 0.80f + a * 0.34f, 1.0f + a * 2.6f, 0.66f + a * 0.34f, "speak", a * 0.30f)
            // Waiting constellation: held pattern, slow drift, deliberately unhurried —
            // legible as "still here" rather than as either work or sleep.
            Motion.STALL -> Params("waiting", 1.06f, 0.30f, 0.52f, "wait", 0.08f)
            // Recoil: a fast outward flinch at full brightness. One-shot.
            Motion.RECOIL -> Params("recoil", 1.30f, 3.6f, 0.95f, "recoil", 0.85f)
            // Tool: the existing motif geometry, energised by the workload.
            Motion.TOOL -> Params("thinking", 0.92f, 1.4f + w * 2.2f, 0.78f + w * 0.20f, "think", -0.20f)
            // Satisfied shimmer: brightest thing the being does, and brief.
            Motion.TOOL_RESULT -> Params("thinking", 1.00f, 2.2f + w * 1.4f, 1.0f, "result", 0.35f)
            // Settling back toward the breath.
            Motion.SETTLE -> Params("settle", 0.95f, 0.85f, 0.68f, "presence", 0.25f)
            Motion.IDLE -> Params("idle", 0.90f, 0.45f, 0.62f, "presence", 0f)
            // Drift: wider and slower than idle. Alive, doing nothing.
            Motion.DRIFT -> Params("drift", 1.10f, 0.26f, 0.56f, "presence", 0.05f)
        }
    }
}
