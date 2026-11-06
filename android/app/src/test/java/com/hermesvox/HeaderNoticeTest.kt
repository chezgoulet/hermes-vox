package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The blue notice (#second phone pass): a change the person caused — a model switch, a
 * session switch — says so on the sub-pill for five to six seconds, in blue, once, and only
 * when the green action line is not using the pill.
 */
class HeaderNoticeTest {

    private val src = "src/main/java/com/hermesvox"
    private val main = File(src, "MainActivity.kt").readText()

    @Test fun theBlueNoticeShowsForFiveToSixSecondsThenGoes() {
        assertTrue("five to six seconds", Header.NOTICE_MS in 5_000L..6_000L)
        val notice = HeaderNotice()
        val t0 = 1_000L
        assertTrue(notice.arm("DeepSeek V4 Flash", t0))
        assertEquals("DeepSeek V4 Flash", notice.text(t0))
        // Still up five seconds later, gone by six.
        assertEquals("DeepSeek V4 Flash", notice.text(t0 + 5_000L))
        assertTrue(notice.showing(t0 + 5_000L))
        assertFalse(notice.showing(t0 + 6_000L))
        assertNull(notice.text(t0 + 6_000L))
        assertTrue(notice.expired(t0 + 6_000L))
        assertEquals(0L, notice.remaining(t0 + 6_000L))
    }

    @Test fun aNoticeNeverStacksAndNeverDisplacesAGreenAction() {
        // One at a time: a second switch while the first notice is up is dropped.
        val notice = HeaderNotice()
        assertTrue(notice.arm("DeepSeek V4 Flash", 0L))
        assertFalse("a second notice does not stack", notice.arm("Trail conditions", 100L))
        assertEquals("DeepSeek V4 Flash", notice.text(100L))

        // The green action line wins: no notice is armed while one owns the pill.
        val busy = HeaderNotice()
        assertFalse(busy.arm("DeepSeek V4 Flash", 0L, actionRunning = true))
        assertNull(busy.text(0L))
        assertFalse(busy.showing(0L))

        // Retiring a notice (an action taking the pill) clears it at once.
        assertTrue(notice.arm("Trail conditions", Header.NOTICE_MS + 1L))
        notice.clear()
        assertFalse(notice.showing(Header.NOTICE_MS + 2L))
    }

    @Test fun theActivityPaintsTheNoticeInBlueAndRestsOnTheModel() {
        assertTrue("the notice rides the one sub-pill", main.contains("HeaderNotice()"))
        assertTrue(main.contains("showHeaderNotice("))
        assertTrue("the notice is the blue token", main.contains("R.color.hv_notice"))
        assertTrue("a model switch announces the catalogue name", main.contains("showHeaderNotice(line)"))
        assertTrue("a session switch announces the session's title", main.contains("showHeaderNotice(row.label)"))
        // With nothing to act on, the sub-pill is the model's name — it is not blanked.
        assertTrue(main.contains("status.text = if (show) text else idleStatus()"))
        assertTrue(main.contains("Header.modelLine("))
    }
}
