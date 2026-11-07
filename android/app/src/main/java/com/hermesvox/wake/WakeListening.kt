package com.hermesvox.wake

import android.content.Context
import android.content.SharedPreferences
import com.hermesvox.AlphaGate

/**
 * WakeListening — the one question the wake word asks before it opens the microphone.
 *
 * The dimmed Settings row is only the door. Turning the Alpha set off has to stop the wake word
 * *listening*, not merely hide or dim a row, so the capture path asks this — and this is the
 * same [AlphaGate] answer the row is dimmed by, so the two cannot disagree.
 *
 * Pure JVM above the thin `SharedPreferences` adapters.
 */
object WakeListening {

    /** What the enrolment screen says instead of recording. */
    const val REFUSAL =
        "Alpha features are off for the wake word — select Wake word under Alpha features above."

    fun listening(r: AlphaGate.Reader): Boolean =
        AlphaGate.allows(r, AlphaGate.FeatureSet.WAKE_WORD)

    fun listening(prefs: SharedPreferences): Boolean = listening(AlphaGate.reader(prefs))

    fun listening(context: Context): Boolean =
        listening(context.getSharedPreferences("hv", Context.MODE_PRIVATE))
}
