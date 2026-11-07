package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Test

/** The call's resource trace (#179/#191/#209): parsed, summarised, logged in one shape. */
class ResourceProbeTest {

    @Test fun rssIsReadFromProcStatus() {
        val status = "Name:\tcom.hermesvox\nVmPeak:\t 9000000 kB\nVmRSS:\t  2471936 kB\nThreads:\t80\n"
        assertEquals(2471936L, ResourceProbe.rssKb(status))
        assertEquals(-1L, ResourceProbe.rssKb("Name:\tx\n"))
    }

    @Test fun aSampleIsOneLogLine() {
        val s = ResourceProbe.Sample(rssKb = 2_471_936, availKb = 1_048_576, totalKb = 7_864_320, low = false, thermal = 1)
        assertEquals("event=res rssMb=2414 availMb=1024 totalMb=7680 low=false thermal=light", s.line())
    }

    @Test fun theSummaryKeepsTheWorstOfTheCall() {
        val sum = ResourceProbe.Summary()
        sum.add(ResourceProbe.Sample(1_000_000, 3_000_000, 8_000_000, false, 0))
        sum.add(ResourceProbe.Sample(2_500_000, 900_000, 8_000_000, true, 2))
        sum.add(ResourceProbe.Sample(2_000_000, 1_200_000, 8_000_000, false, 1))
        assertEquals("event=res-summary peakRssMb=2441 minAvailMb=878 thermalStart=none thermalEnd=light samples=3 ms=600000",
            sum.line(600_000))
    }

    @Test fun anEmptyCallSaysSo() {
        assertEquals("event=res-summary peakRssMb=-1 minAvailMb=- thermalStart=unknown thermalEnd=unknown samples=0 ms=0",
            ResourceProbe.Summary().line(0))
    }

    @Test fun thermalNamesFollowThePlatformConstants() {
        assertEquals(listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown", "unknown"),
            (0..7).map { ResourceProbe.thermalName(it) })
    }
}
