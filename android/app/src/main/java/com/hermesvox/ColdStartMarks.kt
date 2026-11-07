package com.hermesvox

/**
 * The cold-start markers `scripts/cold-start-stt.sh` reads out of logcat.
 *
 * The question the bench answers is a timing one: on a fresh launch, did the eye say
 * *listening* before the recogniser was warm, or before the microphone was delivering
 * audio? That cannot be read back from the speech code by inspection — only from the
 * moments themselves — so each of those moments is stamped once per call under the single
 * parseable prefix `event=cold-stt`.
 *
 * One marker per call, never per frame: [first] ignores a repeat, and [reset] clears the
 * set at the top of a call so a second call on the same process is measured on its own.
 * The prefix is a contract with the bench script; changing it means changing both.
 */
internal class ColdStartMarks {
    private val seen = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** Stamp [line] once per call, so a marker per frame cannot flood the log. */
    fun first(key: String, line: String) {
        if (!seen.add(key)) return
        VoxLog.d("event=cold-stt $line ms=${android.os.SystemClock.uptimeMillis()}")
    }

    /** A new call: every marker may fire again. */
    fun reset() = seen.clear()
}
