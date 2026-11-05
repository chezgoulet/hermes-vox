package com.hermesvox

import java.util.GregorianCalendar
import java.util.Locale

/**
 * PhoneCalendar — the calendar group's bounds and its one parsing rule, pure so they are tested
 * without a phone.
 *
 * `start` is **local ISO-8601**: `YYYY-MM-DDTHH:MM`, seconds optional, in the phone's own time
 * zone. A trailing `Z` or a `+02:00` offset is refused rather than converted, because "local"
 * is the contract: the phone creates the event at the wall-clock time the caller named, and a
 * conversion would silently move it. [parseLocal] is strict about the shape *and* about the date —
 * `2026-02-30` is not a day, and the phone will not invent one.
 *
 * `duration_min` is **minutes**, 1 to [MAX_DURATION_MIN] (a day). Zero, negatives and anything
 * longer than a day are refused: an event that never ends, or a "duration" in seconds, is not
 * something this action can honestly create.
 *
 * The platform edge is [PhoneCalendarAccess], which is `CalendarContract` and nothing else.
 */
object PhoneCalendar {

    /** An event's title is a label the owner will see. */
    const val MAX_TITLE_CHARS = 200

    /** A day, in minutes — the longest event this action will create. */
    const val MAX_DURATION_MIN = 24 * 60

    /** One local wall-clock instant, already validated. */
    data class Local(
        val year: Int,
        val month: Int,
        val day: Int,
        val hour: Int,
        val minute: Int,
        val second: Int,
    ) {
        /** `YYYY-MM-DDTHH:MM:SS`, the shape every result and the audit use. */
        val iso: String
            get() = String.format(
                Locale.US, "%04d-%02d-%02dT%02d:%02d:%02d",
                year, month, day, hour, minute, second,
            )
    }

    private val SHAPE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2})(?::(\\d{2}))?$")

    /**
     * `2026-11-05T09:30` (or with `:00` seconds) as a validated local instant, or null when the
     * text is not one. No `Z`, no offset, no time zone name.
     */
    fun parseLocal(raw: String?): Local? {
        val m = SHAPE.find(raw?.trim().orEmpty()) ?: return null
        val year = m.groupValues[1].toIntOrNull() ?: return null
        val month = m.groupValues[2].toIntOrNull() ?: return null
        val day = m.groupValues[3].toIntOrNull() ?: return null
        val hour = m.groupValues[4].toIntOrNull() ?: return null
        val minute = m.groupValues[5].toIntOrNull() ?: return null
        val second = m.groupValues[6].ifEmpty { "0" }.toIntOrNull() ?: return null
        val local = Local(year, month, day, hour, minute, second)
        val calendar = GregorianCalendar().apply {
            isLenient = false
            clear()
            set(year, month - 1, day, hour, minute, second)
        }
        // `time` is what makes the fields validated: a lenient calendar would roll Feb 30 into
        // March, and a rolled date is not the one the caller named.
        try {
            calendar.time
        } catch (_: Throwable) {
            return null
        }
        return local
    }
}
