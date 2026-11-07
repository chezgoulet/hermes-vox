package com.hermesvox

/**
 * ResourceProbe — what a call costs the phone, written to the log (#179, #191, #209).
 *
 * The co-residency gate (#179) needs peak RSS, the worst `MemAvailable` and the thermal trace
 * over a live call; the Presence crash (#191) needs the memory picture before the process
 * dies; the per-release baseline (#209) needs the same numbers at every cut. None of them was
 * logged, so none could be recorded from a field log. During a call the app now writes
 *
 *   event=res rssMb=… availMb=… totalMb=… low=… thermal=…        every [EVERY_MS]
 *   event=res-summary peakRssMb=… minAvailMb=… thermalStart=… thermalEnd=… samples=… ms=…
 *
 * and `scripts/baseline-report.py` turns a log into the baseline table.
 *
 * Pure JVM: the parsing and the running summary are proven off-device; the Android sampler
 * that feeds it lives in MainActivity.
 */
object ResourceProbe {

    const val EVERY_MS = 10_000L

    /** PowerManager.THERMAL_STATUS_* (API 29+), by value; -1 when the phone does not say. */
    fun thermalName(status: Int): String = when (status) {
        0 -> "none"; 1 -> "light"; 2 -> "moderate"; 3 -> "severe"
        4 -> "critical"; 5 -> "emergency"; 6 -> "shutdown"
        else -> "unknown"
    }

    /** VmRSS from /proc/self/status, in kB; -1 when absent. */
    fun rssKb(procStatus: String): Long =
        Regex("""(?m)^VmRSS:\s+(\d+)\s+kB""").find(procStatus)?.groupValues?.get(1)?.toLongOrNull() ?: -1L

    data class Sample(val rssKb: Long, val availKb: Long, val totalKb: Long, val low: Boolean, val thermal: Int) {
        fun line(): String = "event=res rssMb=${mb(rssKb)} availMb=${mb(availKb)} totalMb=${mb(totalKb)} " +
            "low=$low thermal=${thermalName(thermal)}"
    }

    /** The running worst case over one call. */
    class Summary {
        private var peakRss = -1L; private var minAvail = Long.MAX_VALUE
        private var first: Int? = null; private var last = -1; private var n = 0
        fun add(s: Sample) {
            if (s.rssKb > peakRss) peakRss = s.rssKb
            if (s.availKb in 0 until minAvail) minAvail = s.availKb
            if (first == null) first = s.thermal
            last = s.thermal; n++
        }
        val samples get() = n
        fun line(ms: Long): String = "event=res-summary peakRssMb=${mb(peakRss)} " +
            "minAvailMb=${if (minAvail == Long.MAX_VALUE) "-" else mb(minAvail).toString()} " +
            "thermalStart=${thermalName(first ?: -1)} thermalEnd=${thermalName(last)} samples=$n ms=$ms"
    }

    private fun mb(kb: Long): Long = if (kb < 0) -1 else kb / 1024
}
