package com.hermesvox

/**
 * ConnectionPhase — the pure, emulator-free rule for WHICH PHASE the connection is
 * in, and what the status pill says about it (0.5.1 Part B).
 *
 * THE FIELD BUG THIS EXISTS TO KILL
 * ---------------------------------
 * The pill had exactly one pre-connected state, "Warming up…", and it was sticky:
 * it went up while the local voice pipeline (STT/TTS/VAD) loaded and it never
 * described anything else. Meanwhile the connection test reported
 * `conn-test: ping=false(unknown) stream=false(unknown)` at pipe startup — a verdict
 * on a gateway that had not been dialled yet, with a reason string of "unknown"
 * (see [reason]; the exception carried no message). So the user was shown one word
 * for two completely different waits and a failure verdict for neither of them.
 *
 * TWO WAITS, TWO WORDS
 * --------------------
 *   WARMING UP  the LOCAL pipeline is still loading — models, TTS, STT, VAD. Nothing
 *               has been asked of the network yet, so no gateway verdict is possible
 *               and [resolve] refuses to give one: while warmth is false the probe
 *               result is ignored entirely, whatever it says.
 *   DIALING     the app is reaching out to the configured gateway — endpoint set, key
 *               present, a request actually in flight. This is the phase that was
 *               missing, and it is the one the user asked for by name.
 *   CONNECTED   the gateway answered.
 *
 * ...and the failures are no longer one blanket sentence. "Couldn't reach the
 * gateway, check your network" is a lie when the gateway answered and simply was not
 * ready; [classify] separates REACHED-BUT-COLD, REACHED-BUT-REJECTED-THE-KEY and
 * REACHED-BUT-BROKEN from genuinely UNREACHABLE, and each gets its own copy.
 *
 * Pure JVM — no Android deps — so the phase machine and every line of user copy are
 * proven off-device, like MotionState / EndpointRule / BargeGate.
 */
object ConnectionPhase {

    /** What the status pill is describing right now. */
    enum class Phase {
        /** No endpoint stored (first run / cleared). */
        NO_ENDPOINT,
        /** Endpoint stored but no user-entered key (C0). */
        NEEDS_KEY,
        /** The LOCAL voice pipeline is still loading. Never a gateway verdict. */
        WARMING,
        /** Reaching out to the gateway: a request is in flight (or about to be). */
        DIALING,
        /** The gateway answered. */
        CONNECTED,
        /** Reached the gateway; it is up but not ready to talk yet. */
        GATEWAY_COLD,
        /** Reached the gateway; it rejected the key. */
        AUTH_REJECTED,
        /** Reached the gateway; it answered with something we can't use. */
        GATEWAY_ERROR,
        /** Never reached the gateway at all (DNS / connect / no network). */
        UNREACHABLE
    }

    /** The outcome of one connection probe. [NOT_TESTED] is a real, honest value:
     *  it is what we have before anything has been asked of the network. */
    enum class Probe { NOT_TESTED, IN_FLIGHT, OK, COLD, AUTH, GATEWAY_ERROR, UNREACHABLE, BLOCKED }

    /**
     * What the LIVE voice channel — the real streamed turn through the gateway
     * session — has actually observed about auth. 0.5.2: this is the ground truth
     * the one-shot probe defers to.
     *
     *   UNKNOWN     no live turn has been attempted yet this session.
     *   AUTHORIZED  a live stream opened / delivered events: the gateway accepted
     *               our credentials, whatever a synthetic probe says.
     *   REJECTED    a live turn came back with a genuine auth failure.
     */
    enum class Live { UNKNOWN, AUTHORIZED, REJECTED }

    /** No HTTP response at all (the leg threw). Distinct from any status code. */
    const val NO_RESPONSE = 0
    /** The leg was not run (a ping-only probe passes this for the stream leg). */
    const val NOT_RUN = -1

    /**
     * The phase machine. Order matters and is the whole fix:
     *
     *  1. identity first (no endpoint / no key) — those are Settings problems and no
     *     amount of network will change them;
     *  2. LOCAL WARMTH SECOND, and it SHORT-CIRCUITS: while the pipeline is cold the
     *     probe is not consulted at all, so a pre-warm test result can never be
     *     rendered as a gateway verdict. That is the field bug, closed structurally
     *     rather than by remembering not to call the test too early;
     *  3. only then does the probe decide, and each outcome gets its own phase.
     */
    fun resolve(endpointSet: Boolean, keyPresent: Boolean, modelsWarm: Boolean, probe: Probe): Phase {
        if (!endpointSet) return Phase.NO_ENDPOINT
        if (!keyPresent) return Phase.NEEDS_KEY
        if (!modelsWarm) return Phase.WARMING
        return when (probe) {
            Probe.NOT_TESTED, Probe.IN_FLIGHT, Probe.BLOCKED -> Phase.DIALING
            Probe.OK -> Phase.CONNECTED
            Probe.COLD -> Phase.GATEWAY_COLD
            Probe.AUTH -> Phase.AUTH_REJECTED
            Probe.GATEWAY_ERROR -> Phase.GATEWAY_ERROR
            Probe.UNREACHABLE -> Phase.UNREACHABLE
        }
    }

    /** The status-pill copy for a phase. Short — it is a pill, not a dialog. */
    fun pill(phase: Phase): String = when (phase) {
        Phase.NO_ENDPOINT -> "No gateway set — open Settings"
        Phase.NEEDS_KEY -> "Key needed — open Settings"
        Phase.WARMING -> "Warming up…"
        Phase.DIALING -> "Dialing…"
        Phase.CONNECTED -> "Connected"
        Phase.GATEWAY_COLD -> "Gateway is warming up…"
        Phase.AUTH_REJECTED -> "Gateway rejected the key"
        Phase.GATEWAY_ERROR -> "Gateway answered with an error"
        Phase.UNREACHABLE -> "Can't reach the gateway"
    }

    /** Whether the pill for this phase should be VISIBLE. Connected is the quiet
     *  steady state (the app has always hidden it); everything else is news. */
    fun shows(phase: Phase): Boolean = phase != Phase.CONNECTED

    /**
     * Classify one probe. [pingCode] / [streamCode] are HTTP status codes, or
     * [NO_RESPONSE] when the leg threw, or [NOT_RUN] when it was skipped.
     *
     * The distinction the old code could not make: an EXCEPTION on the ping means we
     * never got to the gateway; a STATUS CODE means we did, and everything after that
     * is a question of what it said.
     */
    fun classify(pingCode: Int, pingErr: String, streamCode: Int, streamErr: String): Probe {
        if (blocked(pingErr) || blocked(streamErr)) return Probe.BLOCKED
        if (pingCode == NO_RESPONSE) return Probe.UNREACHABLE
        pingCode.let {
            if (it == 401 || it == 403) return Probe.AUTH
            if (cold(it)) return Probe.COLD
            if (it < 200 || it >= 400) return Probe.GATEWAY_ERROR
        }
        // The ping reached a live gateway. The stream leg now says whether it is
        // actually ready to talk.
        if (streamCode == NOT_RUN) return Probe.OK
        if (streamCode == NO_RESPONSE) {
            // We reached it a moment ago, so this is not "unreachable" — it is a
            // gateway that accepted the socket and then didn't answer in time.
            return if (timeout(streamErr)) Probe.COLD else Probe.GATEWAY_ERROR
        }
        if (streamCode == 401 || streamCode == 403) return Probe.AUTH
        if (cold(streamCode)) return Probe.COLD
        if (streamCode < 200 || streamCode >= 400) return Probe.GATEWAY_ERROR
        return Probe.OK
    }

    /**
     * 0.5.2 — THE FALSE-401 GATE. [classify] reports what the one-shot probe saw;
     * this reports what is TRUE, by letting the live voice channel overrule it.
     *
     * The field log (2026-09-07) read `conn-test: verdict=auth ping=401 stream=-1`
     * at pipe startup and then streamed real turns fine, seconds later, over the
     * same gateway and the same key. Both facts cannot be true, and the live
     * channel is the one that moved bytes — so an AUTH verdict is only allowed to
     * survive when the live channel has NOT authorized.
     *
     * The rule, both ways:
     *   - probe says AUTH but the live stream authorized -> the probe is wrong
     *     (stale/rewrapped credential state, not a rejected user). Report OK.
     *   - probe says OK but a live turn was genuinely rejected -> the probe is
     *     wrong in the other direction. Report AUTH, because the user does need
     *     to fix the key.
     * Everything else passes through untouched: a cold gateway, an unreachable
     * network and a broken gateway are the probe's own business.
     */
    fun reconcile(probe: Probe, live: Live): Probe = when {
        probe == Probe.AUTH && live == Live.AUTHORIZED -> Probe.OK
        probe == Probe.OK && live == Live.REJECTED -> Probe.AUTH
        else -> probe
    }

    /**
     * Whether a LIVE turn's error text is a genuine auth rejection (the gateway
     * said no to the credential) rather than a transport/timeout/model failure.
     * Pure string work so the classification is proven off-device: the live path
     * only ever hands us the gateway's message.
     */
    fun authFailure(err: String): Boolean {
        val e = err.lowercase()
        return e.contains("401") || e.contains("403") ||
            e.contains("unauthorized") || e.contains("unauthenticated") ||
            e.contains("forbidden") || e.contains("invalid api key") ||
            e.contains("invalid_api_key") || e.contains("invalid key")
    }

    /** Reached, alive, NOT ready: the codes a loading/overloaded gateway returns. */
    private fun cold(code: Int): Boolean =
        code == 408 || code == 425 || code == 429 || code == 502 || code == 503 || code == 504

    private fun timeout(err: String): Boolean {
        val e = err.lowercase()
        return e.contains("timeout") || e.contains("timed out")
    }

    /** The test was run on the UI thread and Android refused it before a single byte
     *  moved. This is what produced the field log's `ping=false(unknown)`: the
     *  exception carries NO message, so the old `e.message ?: "unknown"` erased the
     *  one fact that mattered. */
    private fun blocked(err: String): Boolean = err.contains("NetworkOnMainThread")

    /**
     * The dialog copy for a probe outcome. The verbose form is kept (WS3); what
     * changes is that a cold gateway is no longer told it is unreachable.
     */
    fun copy(probe: Probe, debug: String): String {
        val body = when (probe) {
            Probe.OK -> return "Connected to your agent. Everything's working."
            Probe.NOT_TESTED -> "Not tested yet — the gateway hasn't been dialled."
            Probe.IN_FLIGHT -> "Testing the connection…"
            Probe.COLD -> "Reached your gateway — it answered, but it isn't ready to talk yet " +
                "(still warming up). Give it a moment and test again."
            Probe.AUTH -> "Reached your gateway and it needs valid auth — it rejected this key. " +
                "Re-enter the key in Settings › Entity & Connection."
            Probe.GATEWAY_ERROR -> "Reached your gateway, but it answered with something this app " +
                "can't use. Check that the address points at the gateway itself."
            Probe.UNREACHABLE -> "Couldn't reach the gateway. Check that your network is on and " +
                "the address is right."
            Probe.BLOCKED -> "The connection test couldn't run — it was blocked before it " +
                "reached the network. Try again."
        }
        return if (debug.isBlank()) body else "$body\n\n(debug: $debug)"
    }

    /** A short stable token for the runtime log. */
    fun token(probe: Probe): String = probe.name.lowercase()

    /**
     * The reason string for one failed leg. NEVER "unknown": an exception with no
     * message (NetworkOnMainThreadException is the one that bit us) still has a
     * class name, and the class name was the whole diagnosis.
     */
    fun reason(kind: String, message: String?): String {
        val m = message?.trim().orEmpty()
        return if (m.isEmpty()) kind else "$kind: $m"
    }
}
