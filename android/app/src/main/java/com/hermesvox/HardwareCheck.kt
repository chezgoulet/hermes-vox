package com.hermesvox

import android.content.Context
import android.content.SharedPreferences

/**
 * HardwareCheck — #403/D29: can this phone carry Presence? Two stages, and it only ever advises.
 *
 *  1. **At first run, for everyone** ([runOnce]): a cheap read of total RAM against the bar
 *     ([BAR_GB], 8 GB) and of free memory against a floor ([FREE_FLOOR_MB]), before any model is
 *     fetched. Recorded once; an upgrade runs it on its first start of this version.
 *  2. **On the first call that loads Presence** ([noteFirstLoad]): the real load attempt. If it
 *     fails, the Presence selection is **demoted** — switched off, never on — and the advice says
 *     so. Later loads do not demote: the first attempt is the check, the rest are ordinary.
 *
 * The verdict is shown in the Alpha panel ([advice]) and, when the phone falls short, in first run
 * ([firstRunLine]) — one line saying why and where to turn Presence on. Nothing here enables
 * anything: the Alpha gate stays the user's.
 *
 * Pure JVM except the thin `SharedPreferences`/`ActivityManager` adapters.
 */
object HardwareCheck {

    /** D29's bar: 8 GB of total RAM. */
    const val BAR_GB = 8

    /**
     * The free-memory floor at first run. Presence's measured resident cost is ~2.5 GB (#391:
     * idle PSS 2.71 GB median), so a phone with less than that free cannot hold it without the
     * system killing something.
     *
     * HYPOTHESIS (D20): the value is the measured resident cost plus about a fifth for the
     * process around it, never a field failure. Retire it when a phone over the floor fails the
     * first load (the demote log will show it) or one under it holds Presence through a call.
     */
    const val FREE_FLOOR_MB = 3_072L

    const val KEY_TOTAL_MB = "hw_check_total_mb"
    const val KEY_AVAIL_MB = "hw_check_avail_mb"
    const val KEY_FIRST_LOAD = "hw_check_first_load"   // "ok" | "failed", absent until a load ran

    /**
     * The phone's marketed size from what the kernel reports. Android's `totalMem` excludes the
     * memory the kernel and firmware reserve, so an "8 GB" phone reports ~7.3–7.6 GB; comparing
     * that to 8 × 1024 MB would refuse every phone at the bar. Rounding up to the next whole GB
     * reads it the way the box does.
     */
    fun nominalGb(totalMb: Long): Int = ((totalMb + 1023) / 1024).toInt()

    enum class Verdict { UNKNOWN, CARRIES, BELOW_BAR, LOW_FREE, LOAD_FAILED }

    fun verdict(totalMb: Long, availMb: Long, firstLoad: String?): Verdict = when {
        firstLoad == LOAD_FAILED -> Verdict.LOAD_FAILED
        totalMb <= 0 -> Verdict.UNKNOWN
        nominalGb(totalMb) < BAR_GB -> Verdict.BELOW_BAR
        availMb in 0 until FREE_FLOOR_MB -> Verdict.LOW_FREE
        else -> Verdict.CARRIES
    }

    /** Where Presence is turned on — the same words everywhere the check speaks. */
    const val WHERE = "Settings → Alpha features → Presence"

    /** The Alpha panel's line for the Presence set (#403). Null when nothing has been measured. */
    fun advice(v: Verdict, totalMb: Long, availMb: Long): String? = when (v) {
        Verdict.UNKNOWN -> null
        Verdict.CARRIES ->
            "This phone meets Presence's bar: ${nominalGb(totalMb)} GB of memory, " +
                "${gb(availMb)} GB free at first run. It stays off until you select it."
        Verdict.BELOW_BAR ->
            "This phone has ${nominalGb(totalMb)} GB of memory; Presence needs $BAR_GB GB. " +
                "You can still select it to try."
        Verdict.LOW_FREE ->
            "Only ${gb(availMb)} GB was free at first run; Presence " +
                "needs about ${FREE_FLOOR_MB / 1024} GB. You can still select it to try."
        Verdict.LOAD_FAILED ->
            "Presence could not start on this phone on its first call, so it was turned off. " +
                "Select it again to retry."
    }

    /**
     * First run's line about Presence, with the check folded in: why it stays off when the phone
     * falls short, and where to turn it on either way. Presence is off at first run for everyone
     * (the Alpha gate is shut), so the line never says it is on.
     */
    fun firstRunLine(v: Verdict, totalMb: Long, availMb: Long): String = when (v) {
        Verdict.BELOW_BAR ->
            "· Presence — the being's own voice on your phone — stays off: this phone has " +
                "${nominalGb(totalMb)} GB of memory and Presence needs $BAR_GB GB. " +
                "To try it anyway: $WHERE."
        Verdict.LOW_FREE ->
            "· Presence — the being's own voice on your phone — stays off: only " +
                "${gb(availMb)} GB was free and it needs about " +
                "${FREE_FLOOR_MB / 1024} GB. To try it anyway: $WHERE."
        else ->
            "· Presence — the being's own voice on your phone. It is unfinished (alpha), so it " +
                "is off until you turn it on: $WHERE."
    }

    private fun gb(mb: Long): String = String.format(java.util.Locale.US, "%.1f", mb / 1024.0)

    const val LOAD_OK = "ok"
    const val LOAD_FAILED = "failed"

    /** The check's storage, narrow enough for an in-memory fake. */
    interface Store {
        fun contains(key: String): Boolean
        fun getLong(key: String): Long
        fun getString(key: String): String?
        fun putLong(key: String, value: Long)
        fun putString(key: String, value: String)
        fun putBoolean(key: String, value: Boolean)
    }

    /** Stage one. Recorded once; a second call (a second start) changes nothing. */
    fun runOnce(s: Store, totalMb: Long, availMb: Long): Boolean {
        if (s.contains(KEY_TOTAL_MB)) return false
        s.putLong(KEY_TOTAL_MB, totalMb)
        s.putLong(KEY_AVAIL_MB, availMb)
        return true
    }

    /**
     * Stage two: the first real load. A failure demotes the Presence selection (off — the only
     * direction this object may move it) and is remembered so the advice can say why. Returns
     * true when this call was the first load, i.e. it recorded something.
     */
    fun noteFirstLoad(s: Store, ok: Boolean): Boolean {
        if (s.contains(KEY_FIRST_LOAD)) return false
        s.putString(KEY_FIRST_LOAD, if (ok) LOAD_OK else LOAD_FAILED)
        if (!ok) s.putBoolean(AlphaGate.FeatureSet.PRESENCE.key, false)
        return true
    }

    fun verdict(s: Store): Verdict = verdict(
        if (s.contains(KEY_TOTAL_MB)) s.getLong(KEY_TOTAL_MB) else -1L,
        if (s.contains(KEY_AVAIL_MB)) s.getLong(KEY_AVAIL_MB) else -1L,
        s.getString(KEY_FIRST_LOAD),
    )

    fun advice(s: Store): String? =
        advice(verdict(s), s.getLong(KEY_TOTAL_MB), s.getLong(KEY_AVAIL_MB))

    fun firstRunLine(s: Store): String =
        firstRunLine(verdict(s), s.getLong(KEY_TOTAL_MB), s.getLong(KEY_AVAIL_MB))

    // ---- Android adapters ----

    private class PrefsStore(private val p: SharedPreferences) : Store {
        override fun contains(key: String) = p.contains(key)
        override fun getLong(key: String) = p.getLong(key, -1L)
        override fun getString(key: String): String? = p.getString(key, null)
        override fun putLong(key: String, value: Long) { p.edit().putLong(key, value).apply() }
        override fun putString(key: String, value: String) { p.edit().putString(key, value).apply() }
        override fun putBoolean(key: String, value: Boolean) { p.edit().putBoolean(key, value).apply() }
    }

    fun store(prefs: SharedPreferences): Store = PrefsStore(prefs)

    /** Stage one on a real phone: read ActivityManager's memory picture and record it once. */
    fun runOnce(context: Context, prefs: SharedPreferences) {
        if (prefs.contains(KEY_TOTAL_MB)) return
        val mi = android.app.ActivityManager.MemoryInfo()
        context.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(mi)
        val total = mi.totalMem / 1_048_576
        val avail = mi.availMem / 1_048_576
        if (runOnce(store(prefs), total, avail)) {
            VoxLog.d("event=hw-check stage=first-run totalMb=$total availMb=$avail " +
                "verdict=${verdict(total, avail, null).name.lowercase()}")
        }
    }
}
