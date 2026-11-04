package com.hermesvox

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The UI/UX pass's invariants that are about layout shape rather than one feature. */
class UiPassInvariantsTest {

    private val main = File("src/main/res/layout/activity_main.xml").readText()

    /**
     * The what's-new card once pushed the being, the call controls and its own "Got it" off a
     * 1080×2400 screen, with nothing to scroll. The body must sit in the capped scroller, and
     * the cap must leave most of the screen to the app.
     */
    @Test fun whatsNewBodyScrollsInsideItsCardAndCannotPushTheScreenAway() {
        val scroll = main.indexOf("<com.hermesvox.CappedScrollView")
        val body = main.indexOf("@+id/update_body")
        val close = main.indexOf("</com.hermesvox.CappedScrollView>")
        assertTrue("update_body is not inside the capped scroller", scroll in 0 until body && body < close)
        assertTrue("the cap leaves too little of the screen", CappedScrollView.CAP <= 0.4f)
        val dismiss = main.substringAfter("@+id/update_dismiss").substringBefore("/>")
        assertTrue("Got it is smaller than a finger", dismiss.contains("android:minHeight=\"48dp\""))
    }
}
