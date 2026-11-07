package com.hermesvox

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #451 — the phone tools sheet, measured on a phone instead of argued about.
 *
 * The sheet is the only way to set the phone up as an MCP server, and on a device it could not be
 * used past its capability list: the poll repainted it once a second while a setup was waiting, so
 * the content view was replaced under the owner's finger — the scroll went back to the top and a
 * drag in flight was dropped. Nothing below the fold was reachable, which read as "the sheet does
 * not scroll".
 *
 * These are the two things that have to hold on a real screen:
 *  * the column is taller than the viewport it sits in, the sheet has a scroll range, the setup
 *    state leads it (because enabling the set is not the same as the agent knowing about the
 *    phone), and the last section is reachable rather than clipped;
 *  * a poll tick while the sheet would draw the same thing does not rebuild it, and the one repaint
 *    a real state change causes leaves the owner where they were.
 *
 * Runs on a device (`./gradlew connectedDebugAndroidTest`), never in the gate: it needs a screen to
 * measure. [PhoneToolsSheetPollTest] proves the rule itself without one.
 */
@RunWith(AndroidJUnit4::class)
class PhoneToolsSheetLayoutTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /**
     * Settings on, Phone tools selected, [seed]'s setup record in place, and the sheet open and
     * laid out.
     */
    private fun openSheet(seed: (SettingsActivity) -> Unit): Pair<ActivityScenario<SettingsActivity>, PhoneToolsSheet> {
        val scenario = ActivityScenario.launch(SettingsActivity::class.java)
        lateinit var sheet: PhoneToolsSheet
        scenario.onActivity { act ->
            act.getSharedPreferences("hv", Context.MODE_PRIVATE).edit()
                .putBoolean(AlphaGate.KEY_MASTER, true)
                .putBoolean(AlphaGate.FeatureSet.PHONE_TOOLS.key, true)
                .commit()
            seed(act)
            sheet = PhoneToolsSheet(act)
            sheet.show()
        }
        // The dialog lays out on the frame after it is shown, so wait for a measured viewport.
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            var measured = false
            scenario.onActivity { measured = (sheet.scroller?.height ?: 0) > 0 }
            if (measured) break
            Thread.sleep(50L)
        }
        return scenario to sheet
    }

    private fun close(scenario: ActivityScenario<SettingsActivity>, sheet: PhoneToolsSheet) {
        scenario.onActivity { sheet.close() }
        scenario.close()
    }

    @Test fun the_setup_state_leads_the_sheet_and_its_last_section_is_reachable() {
        // A phone that has never asked its agent: the sheet must lead with the step that is missing,
        // not bury it under a list of grants the agent cannot use yet.
        val (scenario, sheet) = openSheet { PhoneSetupStore.rotate(it) }
        try {
            scenario.onActivity {
                val scroll = sheet.scroller!!
                val col = scroll.getChildAt(0)
                assertTrue("the sheet must be laid out (viewport ${scroll.height})", scroll.height > 0)
                assertTrue(
                    "the column must be taller than the viewport it sits in: " +
                        "column ${col.height}, viewport ${scroll.height}",
                    col.height > scroll.height,
                )
                assertTrue("the sheet must have a scroll range", scroll.canScrollVertically(1))

                val texts = textsIn(col)
                for (subhead in listOf("Set up with your agent", "Capabilities", "Recent actions", "This phone")) {
                    assertTrue("the sheet must draw \"$subhead\"; it drew $texts", texts.any { it.equals(subhead, true) })
                }
                assertTrue(
                    "the set is on and nothing has connected: the loud state must say so; it drew $texts",
                    texts.any { it == PhoneToolsView.NOT_SET_UP_HEADING },
                )
                assertFalse(
                    "nothing has connected, so the sheet must not say Connected",
                    texts.any { it == PhoneToolsView.CONNECTED_HEADING },
                )
                assertTrue(
                    "the setup turn must be on the sheet itself, not behind a disclosure",
                    texts.any { it.contains("hermes mcp add") },
                )
                assertTrue(
                    "and the restart sentence must follow it",
                    texts.any { it == PhoneToolsView.SETUP_RESTART },
                )
                for (row in listOf(
                    "Reachability", "Server", "Do Not Disturb access",
                    "Volume permission", "Last request", "Token",
                )) {
                    assertTrue("This phone must draw \"$row\"; it drew $texts", texts.any { it.equals(row, true) })
                }
                assertTrue(
                    "Recent actions must show its empty state",
                    texts.any { it.startsWith("Nothing yet.") },
                )
            }

            // The short path is on screen with the state, before the turn's full text.
            scenario.onActivity {
                val scroll = sheet.scroller!!
                val setup = buttonsIn(scroll).firstOrNull { it.text.toString() == PhoneBootstrap.BUTTON }
                assertNotNull("Set this up with my agent must be a button on the sheet", setup)
                assertTrue(
                    "the Set up button must be on screen and whole, not clipped by the viewport",
                    onScreen(setup!!, scroll),
                )
            }

            // The turn and its copy action are reachable by scrolling, not clipped away.
            scenario.onActivity {
                val scroll = sheet.scroller!!
                val copy = buttonsIn(scroll).firstOrNull { it.text.toString() == "COPY SETUP PROMPT" }
                assertNotNull("the setup turn needs its copy action", copy)
                val at = IntArray(2)
                copy!!.getLocationOnScreen(at)
                val box = IntArray(2)
                scroll.getLocationOnScreen(box)
                scroll.scrollTo(0, scroll.scrollY + (at[1] - box[1]))
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val scroll = sheet.scroller!!
                val copy = buttonsIn(scroll).firstOrNull { it.text.toString() == "COPY SETUP PROMPT" }
                assertTrue("the copy action must be reachable", onScreen(copy!!, scroll))
            }

            scenario.onActivity {
                val scroll = sheet.scroller!!
                // The last page: the scroll clamps to the bottom, which is the point.
                scroll.scrollTo(0, scroll.getChildAt(0).height)
            }
            instrumentation.waitForIdleSync()

            scenario.onActivity {
                val scroll = sheet.scroller!!
                assertFalse("the sheet must reach its bottom", scroll.canScrollVertically(1))
                assertTrue(
                    "the last section — the manual fallback — must be drawn",
                    textsIn(scroll).any { it.equals("Manual fallback", true) },
                )
            }
        } finally {
            close(scenario, sheet)
        }
    }

    @Test fun a_tick_while_waiting_does_not_rebuild_the_sheet_and_a_repaint_keeps_the_place() {
        // A setup that is waiting for the agent: this is the state that used to repaint every second.
        val (scenario, sheet) = openSheet { PhoneSetupStore.begin(it, System.currentTimeMillis()) }
        try {
            var opened: ScrollView? = null
            scenario.onActivity { opened = sheet.scroller }
            assertNotNull("the sheet must be open", opened)

            // Two poll ticks pass and the sheet would draw exactly the same thing.
            Thread.sleep(2_500L)
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertSame(
                    "a tick that draws the same sheet must not replace its content view",
                    opened,
                    sheet.scroller,
                )
            }

            var at = 0
            scenario.onActivity {
                val scroll = sheet.scroller!!
                scroll.scrollTo(0, 600)
                at = scroll.scrollY
                assertTrue("the test needs a sheet it can scroll (scrollY=$at)", at > 0)
            }
            instrumentation.waitForIdleSync()

            // The one repaint a real state change causes.
            scenario.onActivity { sheet.refresh() }
            instrumentation.waitForIdleSync()

            scenario.onActivity {
                assertEquals(
                    "a repaint must leave the owner where they were in the sheet",
                    at,
                    sheet.scroller!!.scrollY,
                )
            }
        } finally {
            close(scenario, sheet)
        }
    }

    /** Every text in the sheet's column, so an assertion can name what it found. */
    private fun textsIn(v: View): List<String> = when (v) {
        is TextView -> if (v.text.isNullOrBlank()) emptyList() else listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { textsIn(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun buttonsIn(v: View): List<Button> = when (v) {
        is Button -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { buttonsIn(v.getChildAt(it)) }
        else -> emptyList()
    }

    /** Whole and inside [container]'s own rect — what "tappable" means for a clipped row. */
    private fun onScreen(v: View, container: View): Boolean {
        val at = IntArray(2)
        v.getLocationOnScreen(at)
        val box = IntArray(2)
        container.getLocationOnScreen(box)
        val top = box[1]
        val bottom = box[1] + container.height
        return at[1] >= top && at[1] + v.height <= bottom
    }
}
