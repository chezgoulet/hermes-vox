package com.hermesvox

/**
 * When the eye may say *listening*.
 *
 * `scripts/cold-start-stt.sh` measured the cold start on a Pixel 7: the recogniser was warm
 * 0.18–0.68 s BEFORE the eye was shown, but the eye arrived 30–90 ms before the recorder was
 * even recording and 170–220 ms before the microphone delivered its first samples. So the
 * eye was not lying about the model — it was lying about the microphone, and an utterance
 * begun on it lost its opening (that audio did not exist yet, so it was neither decoded nor
 * queued).
 *
 * The rule this class encodes is one sentence: the eye is a claim that the leg is hearing,
 * so it may only be shown when the recogniser is warm AND the recorder is recording AND a
 * read has actually returned samples. Anything less is "arming", and the honest state for
 * arming is not "listening".
 *
 * Pure (no Android, no clock) so every transition is pinned by a unit test; the controller
 * owns the calls and the moments. Phases:
 *
 *  * [Phase.COLD]  — no warm recogniser: the eye must never be shown, whatever the mic does.
 *  * [Phase.READY] — recogniser warm, waiting for this segment's first samples.
 *  * [Phase.LISTENING] — warm + recording + samples delivered: the eye may be shown.
 */
internal class ListenReadiness {

    enum class Phase { COLD, READY, LISTENING }

    var phase: Phase = Phase.COLD
        private set

    /** The eye is up, i.e. the leg is hearing. */
    val listening: Boolean get() = phase == Phase.LISTENING

    /** The recogniser's answer (`VoxStt.init`'s callback).
     *
     *  A failed load returns to [Phase.COLD] — the caller must not keep an eye up against a
     *  leg that cannot decode. A late duplicate `true` (a second init in the same call) does
     *  NOT drop a listening eye back to READY: the eye is up because audio is flowing, and
     *  that has not changed. */
    fun recognizer(ready: Boolean) {
        phase = when {
            !ready -> Phase.COLD
            phase == Phase.LISTENING -> Phase.LISTENING
            else -> Phase.READY
        }
    }

    /** A new segment of the capture loop (the previous turn ended): the eye goes back down
     *  until this segment's first samples arrive, because a reopened recorder is not audible
     *  yet and the caller is about to speak on it. */
    fun rearm() {
        if (phase == Phase.LISTENING) phase = Phase.READY
    }

    /** The microphone's answer: is it recording, and did this read return [samples] samples?
     *  Returns true only on the read that earns the eye (one transition per segment). */
    fun capture(recording: Boolean, samples: Int): Boolean {
        if (phase != Phase.READY) return false
        if (!recording || samples <= 0) return false
        phase = Phase.LISTENING
        return true
    }

    /** The call ended (or the loop exited): back to cold, so a stale READY cannot show an eye
     *  on the next call before its recogniser has answered. */
    fun stop() { phase = Phase.COLD }
}
