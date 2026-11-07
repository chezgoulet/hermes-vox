package com.hermesvox

import android.content.SharedPreferences

/**
 * AlphaBlock — every alpha-gated section of Settings, in the order the home list draws them
 * (#402/D29, the owner's restructure of 2026-11-06).
 *
 * The master switch used to be the first row of Settings and the gated sections were wherever
 * they had grown. The owner's arrangement is: the app as it is, then one contiguous block of
 * everything unfinished with the master switch immediately above it, then About & Diagnostics.
 *
 * The arrangement is data here, not prose in a layout comment: the list draws these rows in
 * this order, each row is dimmed and disabled by its own set ([AlphaGate.allows]), and a
 * section refuses to open unless [canOpen] says so. That is what makes "gated ⇒ in the block"
 * a fact a test can read — a gated section cannot be left out of the list, and the block cannot
 * disagree with the gate about what may open.
 *
 * Pure JVM except the thin `SharedPreferences` adapter.
 */
object AlphaBlock {

    /** The master switch's own row — the row immediately above the block. */
    const val MASTER_ROW = "row_grp_alpha"

    /** The last row of the screen: the block ends where About begins. */
    const val ABOUT_ROW = "row_grp_about"

    /**
     * One row of the block. [section] is the Settings section token the row opens ("" when the
     * row opens a screen of its own, like wake-word enrolment); [row] is the home row's id.
     */
    data class Row(val section: String, val row: String, val set: AlphaGate.FeatureSet)

    /** The block, top to bottom. Presence first, as the panel lists it. The section tokens are
     *  [SettingsActivity]'s own (`SECTION_PRESENCE`, `SECTION_PHONE`); they are written out
     *  here because that companion is private, and pinned by a test. A row that opens a screen
     *  of its own still names its token: the door checks the same answer the row is dimmed by. */
    val ROWS: List<Row> = listOf(
        Row("presence", "row_grp_presence", AlphaGate.FeatureSet.PRESENCE),
        Row("phone", "row_grp_phone", AlphaGate.FeatureSet.PHONE_TOOLS),
        Row("wake", "row_grp_wake", AlphaGate.FeatureSet.WAKE_WORD),
        Row("assistant", "row_grp_assistant", AlphaGate.FeatureSet.DIGITAL_ASSISTANT),
    )

    private val BY_SECTION: Map<String, Row> = ROWS.filter { it.section.isNotEmpty() }.associateBy { it.section }

    /** The row that owns [section], or null when the section is not alpha-gated. */
    fun rowFor(section: String): Row? = BY_SECTION[section]

    /**
     * May [section] open right now? An ungated section always may. This is asked by
     * [SettingsActivity.showSection] itself, so a banner extra, a stale link or the debug
     * harness cannot reach what the gate keeps closed.
     */
    fun canOpen(r: AlphaGate.Reader, section: String): Boolean {
        val row = BY_SECTION[section] ?: return true
        return AlphaGate.allows(r, row.set)
    }

    fun canOpen(prefs: SharedPreferences, section: String): Boolean =
        canOpen(AlphaGate.reader(prefs), section)
}
