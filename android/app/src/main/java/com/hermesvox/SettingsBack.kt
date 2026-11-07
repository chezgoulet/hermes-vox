package com.hermesvox

/**
 * SettingsBack — where one press of Back goes inside Settings.
 *
 * Settings is a two-level stack: the group list, then a submenu. Back has to mean the same
 * thing whether it arrives from the toolbar arrow or the system gesture, so both ask this
 * one rule:
 *
 *  * a submenu open  → the group list (one level up, still inside Settings);
 *  * the group list  → leave Settings for the main screen.
 *
 * Pure JVM: the stack is proven off-device (`SettingsBackTest`).
 */
object SettingsBack {

    enum class To { GROUP_LIST, LEAVE }

    fun from(submenuOpen: Boolean): To = if (submenuOpen) To.GROUP_LIST else To.LEAVE
}
