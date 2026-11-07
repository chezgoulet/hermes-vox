package com.hermesvox

import android.content.Context

/**
 * PhoneAuditStore — the persisted Recent actions list (#429 step 1), and the sheet's undo.
 *
 * Every read goes to SharedPreferences, so the list the sheet shows is the list the engine wrote,
 * even when the engine instance was created by a call that has since ended. One preference value,
 * bounded by [PhoneAudit.MAX]; a corrupt value reads as an empty list rather than failing a screen.
 *
 * [PhoneUndo] is deliberately separate: an undo only exists while the [PhoneEngine] that registered
 * it is alive, and that engine belongs to the call. A sheet opened after the call can show the
 * action, and tapping it gets an honest `expired` rather than a hopeful no-op.
 */
object PhoneAuditStore {

    private const val PREFS = "hv"
    private const val KEY = "phone_audit"
    private val lock = Any()

    /** Append one call and keep the list bounded. Written synchronously: the sheet reads it next. */
    fun record(context: Context, entry: PhoneAudit.Entry) {
        synchronized(lock) {
            val all = PhoneAudit.decode(read(context)) + entry
            val kept = all.takeLast(PhoneAudit.MAX)
            prefs(context).edit().putString(KEY, PhoneAudit.encode(kept)).apply()
        }
    }

    /** Oldest first; the sheet renders it newest first. */
    fun entries(context: Context): List<PhoneAudit.Entry> = synchronized(lock) {
        PhoneAudit.decode(read(context))
    }

    fun clear(context: Context) {
        synchronized(lock) { prefs(context).edit().remove(KEY).apply() }
    }

    /**
     * Undo [id] for the owner's tap, and record the attempt. The result is the same shape the agent
     * gets, so the toast can say exactly what happened — including that the undo had expired.
     */
    fun undo(context: Context, id: String, nowEpochMs: Long = System.currentTimeMillis()): PhoneResult {
        val result = PhoneUndo.undo(id)
        record(context, PhoneAudit.entry(nowEpochMs, mapOf("action" to "undo", "id" to id), result))
        return result
    }

    private fun read(context: Context): String? = prefs(context).getString(KEY, null)

    private fun prefs(context: Context) =
        (context.applicationContext ?: context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * PhoneUndo — the one bridge from "the owner tapped Undo" to the engine that can still do it.
 *
 * The engine's undo table is per process and belongs to the live call; this holds the most recent
 * one. With no live engine the answer is `expired`, which is true: the action's undo did not survive
 * the call it was made in.
 */
object PhoneUndo {

    @Volatile private var live: ((String) -> PhoneResult)? = null

    /** Called by [LocalActions] when its engine exists. Last one wins; older calls are over. */
    fun attach(undoer: (String) -> PhoneResult) {
        live = undoer
    }

    fun undo(id: String): PhoneResult {
        val fn = live ?: return expired()
        return try {
            fn(id)
        } catch (t: Throwable) {
            VoxLog.w("event=phone-mcp action=undo ok=false reason=unavailable")
            PhoneResult.refused("undo", PhoneReason.UNAVAILABLE, "the phone could not reverse that action")
        }
    }

    private fun expired(): PhoneResult = PhoneResult.refused(
        "undo",
        PhoneReason.EXPIRED,
        "no undo is registered for that id any more; it fired, was superseded, or the call that made it ended",
    )
}
