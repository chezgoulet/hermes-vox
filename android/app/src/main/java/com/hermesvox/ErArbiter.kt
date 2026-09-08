package com.hermesvox

/**
 * ErArbiter — the priority audio arbiter (ER Phase 6, Miles rule #4), pure JVM.
 *
 * The double-talk foot-gun: two outputs (soul P3 fillers + the mind's reply)
 * and one listener. ONE arbiter, strict priority:
 *   P0 system stop/cancel · P1 mind content · P2 critical soul (safety/
 *   clarify) · P3 non-critical fillers.
 * Any P0-2 request interrupts all P3 immediately. P3 never preempts anything.
 *
 * This is the DECISION table the VoiceController's existing primitives
 * (silenceAll / stopTts / the speak() precedence) compose with — the arbiter
 * only ever says what may play and what must be cut; the cuts themselves stay
 * the proven single paths. Nothing here bypasses a fence or a gate.
 */
object ErArbiter {

    enum class Priority { P0_STOP, P1_MIND, P2_SOUL_CRITICAL, P3_FILLER }

    /** The decision for a speaker asking to play at a priority while something
     *  else is on the output. */
    sealed class Decision {
        /** Play over/after whatever is current (nothing above P3 is playing). */
        object Play : Decision()
        /** Cut the current P3 and play. (P0-2 vs P3.) */
        data class Preempt(val cut: Priority) : Decision()
        /** Something at or above this priority is playing — do not start. */
        data class Reject(val by: Priority) : Decision()
    }

    /** What is currently on the output (null = silence). */
    private fun currentPriority(current: Priority?): Priority? = current

    /** The arbitration. `current` = what is playing now (null = silence).
     *  `request` = the incoming speaker's priority. */
    fun arbitrate(current: Priority?, request: Priority): Decision {
        // Silence: anything plays.
        if (current == null) return Decision.Play
        // A P0 (system stop) never "plays" — the caller executes the stop path;
        // the arbiter's answer to a stop request is always Preempt-everything.
        if (request == Priority.P0_STOP) return Decision.Preempt(current)
        // Equal or higher-ranked current audio rejects the request — EXCEPT
        // P3: a filler never plays over anything (idempotent reject below).
        val currentRank = current.ordinal
        val requestRank = request.ordinal
        return when {
            requestRank < currentRank -> {
                // The request outranks the current audio: preempt if the current
                // is cuttable. P1 mind content is cuttable only by P0 (a real
                // barge/stop path handles that OUTSIDE the arbiter — the
                // semantic gate decides; the arbiter never cuts the mind for
                // a soul utterance). So: P2 vs P3 preempts; P1 vs P3 does NOT
                // (the mind is playing — a filler must not interrupt it).
                if (current == Priority.P3_FILLER) Decision.Preempt(current)
                else Decision.Reject(current)
            }
            request == Priority.P3_FILLER -> Decision.Reject(current)  // P3 never talks over anything
            else -> Decision.Reject(current)   // same or lower rank: never overlap
        }
    }

    /** Interrupt/overlap telemetry record (Phase 8's honest numbers): every
     *  preemption + rejection is logged with both priorities. */
    data class Event(val atMs: Long, val current: Priority?, val request: Priority, val outcome: String)

    /** Pure decision-log helper for the controller to call on each arbitration. */
    fun outcomeOf(d: Decision): String = when (d) {
        is Decision.Play -> "play"
        is Decision.Preempt -> "preempt:${d.cut}"
        is Decision.Reject -> "reject:${d.by}"
    }
}
