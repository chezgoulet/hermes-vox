package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Back inside Settings is a stack, one level per press (#second phone pass):
 * submenu → group list → main. The toolbar arrow and the system gesture must agree, so both
 * doors call the same rule and this file holds them to it.
 */
class SettingsBackTest {

    private val src = "src/main/java/com/hermesvox"
    private val settings = File(src, "SettingsActivity.kt").readText()

    @Test fun aSubmenuGoesUpOneLevelToTheGroupList() {
        assertEquals(SettingsBack.To.GROUP_LIST, SettingsBack.from(submenuOpen = true))
    }

    @Test fun theGroupListLeavesSettingsForTheMainScreen() {
        assertEquals(SettingsBack.To.LEAVE, SettingsBack.from(submenuOpen = false))
    }

    @Test fun theArrowAndTheSystemGestureShareOneRule() {
        // One function answers both doors.
        assertTrue(settings.contains("SettingsBack.from(currentSection != null)"))
        assertTrue("the toolbar arrow", settings.contains("R.id.settings_back).setOnClickListener { goBack() }"))
        assertTrue("the system gesture", settings.contains("onBackPressedDispatcher.addCallback"))
        assertTrue(settings.contains("override fun handleOnBackPressed() { goBack() }"))
        // And the submenu → group list step is the same [showGroupList] the arrow always used.
        val goBack = settings.substringAfter("private fun goBack() {").substringBefore("/** WS1: return")
        assertTrue(goBack, goBack.contains("SettingsBack.To.GROUP_LIST -> showGroupList()"))
        assertTrue(goBack, goBack.contains("SettingsBack.To.LEAVE ->"))
    }
}
