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

    /**
     * #500: the commands sheet is the groups and nothing else. It had a search field above
     * them that filtered the list as you typed; the owner does not want it. One row per
     * command, no second "Fits now" section (#499), and no filter box that can raise the
     * keyboard over the list.
     */
    @Test fun theCommandsSheetIsItsGroupsWithNoSearchField() {
        val sheet = File("src/main/java/com/hermesvox/CommandsSheet.kt").readText()
        assertTrue("the sheet grew a text field back", !sheet.contains("EditText"))
        assertTrue("the sheet filters by a typed query again", !sheet.contains("CommandCatalog.search"))
        assertTrue("a second section repeats the commands", !sheet.contains("header(\"Fits now\")"))
        assertTrue("what fits the moment is marked on the row", sheet.contains("CommandCatalog.FITS_NOW"))
    }

    /**
     * Every AlertDialog stands on the app's ground: both themes name the one dialog theme, its
     * card is the row surface, and every colour it draws text in is a token
     * LightModeContrastTest already holds to 4.5:1 on that surface (its "row" ground).
     */
    @Test fun everyDialogUsesTheAppsDialogTheme() {
        for (f in listOf("values/themes.xml", "values-night/themes.xml")) {
            val theme = File("src/main/res/$f").readText()
            assertTrue("$f has no dialog theme",
                theme.contains("<item name=\"alertDialogTheme\">@style/ThemeOverlay.HermesVox.Dialog</item>"))
        }
        val styles = File("src/main/res/values/styles.xml").readText()
        val overlay = styles.substringAfter("name=\"ThemeOverlay.HermesVox.Dialog\"").substringBefore("<!-- #402")
        val colours = Regex("@color/(hv_[a-z_]+)").findAll(overlay).map { it.groupValues[1] }.toSet()
        assertTrue("dialog colours $colours", colours.isNotEmpty() &&
            colours.all { it in setOf("hv_text", "hv_text_dim", "hv_cyan") })
        val card = File("src/main/res/drawable/bg_dialog.xml").readText()
        assertTrue(card.contains("<solid android:color=\"@color/hv_surface\" />"))
    }
}
