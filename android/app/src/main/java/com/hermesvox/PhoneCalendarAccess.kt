package com.hermesvox

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.TimeZone

/**
 * PhoneCalendarAccess — the Android edge of `agenda` and `event_create`: `CalendarContract` and
 * nothing else.
 *
 * **No provider-specific behaviour.** The phone does not ask whether the calendar is Google's,
 * Samsung's or a local one: it queries [CalendarContract.Instances] for today and inserts into
 * [CalendarContract.Events] in a calendar that reports itself writable. A device with no writable
 * calendar answers `unavailable`, which is the truth, rather than falling back to a silent export.
 *
 * **Permissions are named exactly.** [permission] returns Android's own string for the first half
 * the app lacks — `android.permission.READ_CALENDAR` or `android.permission.WRITE_CALENDAR` — and
 * the engine puts that string in the refusal. The owner grants both in the sheet; the runtime
 * grant can be revoked at any moment, and the next call says so.
 *
 * **What is read is what the calendar holds:** begin, end, title, calendar name, description,
 * location and attendee **names**. Attendee addresses are never projected or returned; the group
 * answers who, not how to reach them. A field the provider will not give is null.
 */
object PhoneCalendarAccess {

    /** Both halves at runtime. Read is needed to find a writable calendar; write to insert. */
    val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    /** True when Android has granted [permission] to this app for this install. */
    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** The exact permission the app lacks, or null when it holds both. */
    fun permission(context: Context): String? = when {
        !granted(context, Manifest.permission.READ_CALENDAR) -> Manifest.permission.READ_CALENDAR
        !granted(context, Manifest.permission.WRITE_CALENDAR) -> Manifest.permission.WRITE_CALENDAR
        else -> null
    }

    /** True when any calendar can be written to, for the sheet's line and its refusal copy. */
    fun writableCalendarCount(context: Context): Int = try {
        queryCalendars(context, null).size
    } catch (_: Throwable) {
        0
    }

    /**
     * Today's events in the phone's own zone, in start order. The four identity fields plus the
     * description, location and attendee names the provider offers. A field the provider will not
     * give is null, never an invented label.
     */
    fun agenda(context: Context): List<PhoneEngine.PhonePlatform.AgendaEvent>? = try {
        val from = startOfToday()
        val to = Calendar.getInstance().apply {
            timeInMillis = from
            add(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(from.toString())
            .appendPath(to.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.EVENT_ID,
        )
        val out = ArrayList<PhoneEngine.PhonePlatform.AgendaEvent>()
        context.contentResolver.query(
            uri, projection, null, null, CalendarContract.Instances.BEGIN + " ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val begin = c.getLong(0)
                val end = c.getLong(1)
                val eventId = if (c.isNull(6)) null else c.getLong(6)
                out.add(
                    PhoneEngine.PhonePlatform.AgendaEvent(
                        start = PhoneEngine.localIso(begin, offsetMinutes(begin)),
                        end = PhoneEngine.localIso(end, offsetMinutes(end)),
                        title = if (c.isNull(2)) null else c.getString(2),
                        calendar = if (c.isNull(3)) null else c.getString(3),
                        description = if (c.isNull(4)) null else c.getString(4),
                        location = if (c.isNull(5)) null else c.getString(5),
                        attendees = eventId?.let { names(context, it) },
                    ),
                )
            }
        }
        out
    } catch (t: Throwable) {
        VoxLog.w("event=phone-calendar agenda-failed reason=${t.javaClass.simpleName}")
        null
    }

    /**
     * Insert one event and report the id Android gave it, with the times actually stored. Null
     * means nothing was created: no writable calendar, a start that is not a real instant, or an
     * insert Android refused.
     */
    fun createEvent(
        context: Context,
        title: String,
        startLocal: String,
        durationMin: Int,
        calendarName: String?,
    ): PhoneEngine.PhonePlatform.CreatedEvent? = try {
        val local = PhoneCalendar.parseLocal(startLocal)
        val calendar = local?.let { queryCalendars(context, calendarName).firstOrNull() }
        if (local == null || calendar == null) {
            null
        } else {
            val start = millis(local)
            val end = start + durationMin * 60_000L
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendar.id)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, end)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return null
            val id = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: return null
            PhoneEngine.PhonePlatform.CreatedEvent(
                id = id,
                start = PhoneEngine.localIso(start, offsetMinutes(start)),
                end = PhoneEngine.localIso(end, offsetMinutes(end)),
                startMs = start,
                endMs = end,
                calendar = calendar.name,
            )
        }
    } catch (t: Throwable) {
        VoxLog.w("event=phone-calendar create-failed reason=${t.javaClass.simpleName}")
        null
    }

    /** Delete the event this phone created — the undo, and the only destructive call here. */
    fun deleteEvent(context: Context, id: String): Boolean = try {
        val numeric = id.trim().toLongOrNull() ?: return false
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, numeric)
        context.contentResolver.delete(uri, null, null) > 0
    } catch (t: Throwable) {
        VoxLog.w("event=phone-calendar delete-failed reason=${t.javaClass.simpleName}")
        false
    }

    // ---- internals ----

    /**
     * The attendee names the provider offers for one event, or null when it offers none.
     * `ATTENDEE_NAME` only — an address is never in the projection, so it cannot travel into a
     * tool result. Duplicates (the same person twice) are collapsed.
     */
    private fun names(context: Context, eventId: Long): List<String>? = try {
        val out = ArrayList<String>()
        context.contentResolver.query(
            CalendarContract.Attendees.CONTENT_URI,
            arrayOf(CalendarContract.Attendees.ATTENDEE_NAME),
            "${CalendarContract.Attendees.EVENT_ID}=?",
            arrayOf(eventId.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.isNull(0)) continue
                val name = c.getString(0)?.trim().orEmpty()
                if (name.isNotEmpty() && name !in out) out.add(name)
            }
        }
        out.ifEmpty { null }
    } catch (t: Throwable) {
        VoxLog.d("event=phone-calendar attendees-unreadable reason=${t.javaClass.simpleName}")
        null
    }

    private data class Writable(val id: Long, val name: String?, val primary: Boolean)

    /**
     * The calendars Android says this app may write to, in the phone's order, with the primary
     * one first. [name] filters by the calendar's display name when the caller named one.
     */
    private fun queryCalendars(context: Context, name: String?): List<Writable> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.VISIBLE,
        )
        val selection = "${CalendarContract.Calendars.DELETED}=0 AND " +
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=?"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        val out = ArrayList<Writable>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, selection, args, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val shown = if (c.isNull(1)) null else c.getString(1)
                if (name != null && !shown.equals(name, ignoreCase = true)) continue
                val visible = !c.isNull(3) && c.getInt(3) != 0
                if (!visible) continue
                out.add(
                    Writable(
                        id = c.getLong(0),
                        name = shown,
                        primary = !c.isNull(2) && c.getInt(2) != 0,
                    ),
                )
            }
        }
        return out.sortedByDescending { it.primary }
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun millis(local: PhoneCalendar.Local): Long = Calendar.getInstance().apply {
        clear()
        set(
            local.year, local.month - 1, local.day,
            local.hour, local.minute, local.second,
        )
    }.timeInMillis

    private fun offsetMinutes(atMs: Long): Int =
        TimeZone.getDefault().getOffset(atMs) / 60_000
}
