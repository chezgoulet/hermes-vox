package com.hermesvox

/**
 * The call readiness gate — **the call does not go green until everything is ready**,
 * Presence included (owner's decision).
 *
 * Four legs, each *observed*, never simulated. The activity reports what the app already
 * knows and already logs; if a report never comes, the leg never advances:
 *
 *  - [Leg.MIND]     — the gateway answered: `conn-test: verdict=ok` (a fresh probe at call start);
 *  - [Leg.HEARING]  — the recogniser is up: `sttReady=true` (VoiceController.onSttReady), and
 *                     the VAD when one is installed;
 *  - [Leg.VOICE]    — an engine is warm: `event=tts-ready engine=…` (TtsEngineHost's state
 *                     `ready`), not merely selected;
 *  - [Leg.PRESENCE] — `event=presence-load phase=end ok=true` and the profile's VOX.md, only
 *                     when Presence is on. With Presence off the leg is **absent**, not green.
 *
 * The rules, all here so they are tested rather than buried in the activity:
 *
 *  - GREEN only when every present leg is ready;
 *  - a failed leg is said in the app's existing words (the caller passes the line it already
 *    shows — TtsPolicy's refusal, ConnectionPhase's pill). A failure of the mind, the ears or the
 *    voice is fatal: the overlay offers the honest refusal (Close), nothing else. Presence
 *    failing is not: the call can hold a conversation without it, so **Talk anyway** is offered;
 *  - after [TALK_ANYWAY_MS] still waiting, the overlay names the leg still coming and offers
 *    **Talk anyway** — when everything still missing is something a call can start without
 *    (the mind, which the first turn then waits on; Presence, whose asides are then off). The
 *    ears and the voice are never skipped: a call that cannot hear or speak is not a call.
 *
 * Pure JVM: no Android, no clock (the caller passes the elapsed time).
 */
object CallReadiness {

    /** How long the gate waits before it offers to start with what is ready. */
    const val TALK_ANYWAY_MS = 20_000L

    enum class Leg(
        /** The being's own line while this leg is the one it is waiting on. */
        val waiting: String,
        /** Said after the wait has run long, naming the leg still coming. */
        val still: String,
        /** Said on the call when it starts without this leg ("Talk anyway"). */
        val without: String,
        /** A call can start without it (Talk anyway). */
        val skippable: Boolean,
        /** Its failure ends the attempt (only the refusal is offered). */
        val fatal: Boolean,
    ) {
        MIND("reaching the mind", "the mind has not answered yet",
            "the mind has not answered yet; your first words may wait", skippable = true, fatal = true),
        HEARING("opening my ears", "my ears are still opening",
            "the recogniser is still loading", skippable = false, fatal = true),
        VOICE("finding my voice", "my voice is still warming",
            "the voice is still warming", skippable = false, fatal = true),
        PRESENCE("waking the rest of me", "presence is still warming",
            "presence is still warming; asides are off", skippable = true, fatal = false),
    }

    enum class State { WAITING, READY, FAILED }

    enum class Phase {
        /** Legs still arriving, inside the patience window. */
        ASSEMBLING,
        /** Still arriving after [TALK_ANYWAY_MS]. */
        STALLED,
        /** A leg failed. */
        FAILED,
        /** Every present leg is ready: the call may open. */
        GREEN,
    }

    /** What the overlay shows. [line] is one short line; [ready] drives the structural
     *  elements (one per completed leg); [missing] is what the call must name if it starts
     *  now via Talk anyway. */
    data class View(
        val phase: Phase,
        val line: String,
        val legs: List<Leg>,
        val ready: Set<Leg>,
        val pending: Leg?,
        val failed: Leg?,
        val talkAnyway: Boolean,
        val missing: String,
    ) {
        /** 0..1, how much of the being has assembled. */
        val fraction: Float get() = if (legs.isEmpty()) 1f else ready.size.toFloat() / legs.size
    }

    /** The line the being says when the gate goes green. */
    const val GREEN_LINE = "here — talk to me"

    /** The gate for one call start. [presence]: Presence is on and can arrive this call. */
    class Gate(presence: Boolean) {
        val legs: List<Leg> = Leg.entries.filter { it != Leg.PRESENCE || presence }
        private val states = legs.associateWith { State.WAITING }.toMutableMap()
        private val lines = mutableMapOf<Leg, String>()

        fun state(leg: Leg): State? = states[leg]

        /** A leg's observed state. A leg absent from this gate ignores reports; a report of
         *  `false` takes a ready leg back to waiting (the condition it observed went away). */
        fun report(leg: Leg, ready: Boolean) {
            if (leg !in states) return
            states[leg] = if (ready) State.READY else State.WAITING
            if (ready) lines.remove(leg)
        }

        /** A leg failed, in the app's existing words for that failure. */
        fun fail(leg: Leg, line: String) {
            if (leg !in states) return
            states[leg] = State.FAILED
            lines[leg] = line.ifBlank { leg.still }
        }

        fun view(waitedMs: Long): View {
            val ready = legs.filter { states[it] == State.READY }.toSet()
            val notReady = legs.filter { it !in ready }
            val failed = legs.firstOrNull { states[it] == State.FAILED }
            val pending = notReady.firstOrNull { states[it] == State.WAITING }
            val missing = notReady.joinToString("; ") {
                when {
                    states[it] != State.FAILED -> it.without
                    it == Leg.PRESENCE -> "${lineOf(it)}; asides are off"
                    else -> lineOf(it)
                }
            }
            val canSkip = notReady.all { it.skippable && !(it.fatal && states[it] == State.FAILED) }
            return when {
                notReady.isEmpty() -> View(Phase.GREEN, GREEN_LINE, legs, ready, null, null, false, "")
                failed != null -> View(Phase.FAILED, lineOf(failed), legs, ready, pending, failed,
                    talkAnyway = canSkip, missing = missing)
                waitedMs >= TALK_ANYWAY_MS -> View(Phase.STALLED, pending!!.still, legs, ready, pending, null,
                    talkAnyway = canSkip, missing = missing)
                else -> View(Phase.ASSEMBLING, pending!!.waiting, legs, ready, pending, null, false, missing)
            }
        }

        private fun lineOf(leg: Leg): String = lines[leg] ?: leg.still
    }

    /** How the being looks for a view — the AvatarView inputs, so the assembly is pinned by
     *  a test instead of tuned on a device. [density] is the fraction of particles drawn
     *  (particles converge as legs arrive); [state]/[tool] pick the shape: a diffuse nebula
     *  before anything has answered, the constellation resolving as legs land, the listening
     *  shape at green (the moment it can hear you). */
    data class Assembly(val state: String, val tool: String?, val workload: Float, val density: Float, val stalled: Boolean)

    fun assembly(v: View): Assembly = when (v.phase) {
        Phase.GREEN -> Assembly("listening", null, 0f, 1f, stalled = false)
        Phase.FAILED -> Assembly("idle", null, 0f, 0.25f + 0.5f * v.fraction, stalled = false)
        else -> if (v.ready.isEmpty())
            Assembly("idle", null, 0.1f, 0.25f, stalled = v.phase == Phase.STALLED)
        else
            Assembly("thinking", "memory", 0.25f + 0.6f * v.fraction, 0.25f + 0.75f * v.fraction,
                stalled = v.phase == Phase.STALLED)
    }

    /** The probe verdict as a leg report: `true` ready, `false` still waiting (no answer yet,
     *  or the gateway is warming), `null` a failure — the caller passes ConnectionPhase's pill. */
    fun mindReady(probe: ConnectionPhase.Probe): Boolean? = when (probe) {
        ConnectionPhase.Probe.OK -> true
        ConnectionPhase.Probe.NOT_TESTED, ConnectionPhase.Probe.IN_FLIGHT, ConnectionPhase.Probe.COLD -> false
        else -> null
    }

    /** One log line per change, so a field log can replay the gate. */
    fun logLine(v: View, waitedMs: Long): String =
        "event=call-ready phase=${v.phase.name.lowercase()} " +
            "ready=${v.ready.joinToString(",") { it.name.lowercase() }.ifEmpty { "-" }} " +
            "pending=${v.pending?.name?.lowercase() ?: "-"} failed=${v.failed?.name?.lowercase() ?: "-"} " +
            "legs=${v.legs.size} waitedMs=$waitedMs"
}
