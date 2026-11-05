package com.hermesvox

/**
 * LocalActionSpeech — the words for a phone action and its result (#421, D38).
 *
 * Two rules, both from the review's invariants:
 *
 *  1. **The confirmation must be true.** The false-action-claim guard exists because a phone that
 *     says "I've set a timer" when it did not is worse than one that says nothing. So a failure has
 *     its own line, naming what failed, and a permission the app does not have is named as a
 *     permission — never dressed up as a success.
 *  2. **One short line.** It is what the caller hears when the mind reports a phone action, or what
 *     the phone says when that action rings.
 *
 * Pure JVM, unit-tested in LocalActionSpeechTest.
 */
object LocalActionSpeech {

    fun timerSet(durationMs: Long): String = "Timer set for ${spokenDuration(durationMs)}."

    fun alarmSet(hour: Int, minute: Int): String = "Alarm set for ${spokenClock(hour, minute)}."

    fun dnd(on: Boolean): String = if (on) "Do not disturb on." else "Do not disturb off."

    fun volume(percent: Int): String = "Volume $percent percent."

    /** A plain failure: the action was recognised, the phone could not do it. */
    fun failed(kind: String): String = "I couldn't set that $kind."

    /**
     * Do Not Disturb access is a special access only the user can grant in system settings. We do
     * not ask for it here (the manifest already declares it for #325's quiet calls), so when it is
     * missing the phone says exactly that rather than pretending.
     */
    fun dndDenied(): String = "I can't change do-not-disturb, the phone hasn't given me permission."

    /** The volume API refuses while Do Not Disturb owns the stream on some devices. */
    fun volumeDenied(): String = "I can't change the volume while do-not-disturb is in charge."

    /** "5 minutes", "90 seconds", "1 hour and 5 minutes" — spoken, not abbreviated. */
    fun spokenDuration(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val parts = ArrayList<String>(3)
        if (hours > 0) parts.add(plural(hours, "hour"))
        if (minutes > 0) parts.add(plural(minutes, "minute"))
        if (seconds > 0) parts.add(plural(seconds, "second"))
        return when {
            parts.isEmpty() -> "0 seconds"
            parts.size == 1 -> parts[0]
            else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
        }
    }

    /** "7:30 am" — the way a person reads an alarm back. */
    fun spokenClock(hour: Int, minute: Int): String {
        val suffix = if (hour < 12) "am" else "pm"
        val h12 = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return if (minute == 0) "$h12 $suffix" else "$h12:%02d $suffix".format(minute)
    }

    /** The registry's compact form: "3m12s", "45s", "1h05m" — B1's block is under 200 characters. */
    fun remaining(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> "%dh%02dm".format(hours, minutes)
            minutes > 0 -> "%dm%02ds".format(minutes, seconds)
            else -> "${seconds}s"
        }
    }

    /** The registry's compact form of a wall-clock alarm: "07:30". */
    fun clock24(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

    private fun plural(n: Long, unit: String): String = if (n == 1L) "1 $unit" else "$n ${unit}s"
}
