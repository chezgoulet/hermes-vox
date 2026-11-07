package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The commands entry's glyph is a choice (#command-icon), and this is the contract that
 * keeps it honest off-device:
 *
 *   * the pref maps to exactly five drawings, and an absent/unknown token is the shipped
 *     default (Clanker); an explicit saved token — Steve included — is never overwritten;
 *   * every drawing is our own geometry on the 24 dp grid — no two are the same drawing;
 *   * nothing in the drawing names a colour: the ink is the theme's `hv_text` token, and
 *     each glyph's stroke clears 4.5:1 on the light page and on the dark ground, on both
 *     grounds' surfaces (the page and a command/settings row);
 *   * the entry still says "Commands", whatever the glyph is called.
 *
 * The numbers are printed so the report's contrast table is measured here, not eyeballed.
 */
class CommandGlyphTest {

    private val icons = CommandGlyph.Icon.entries

    // ---- the setting ---------------------------------------------------------

    @Test fun theDefaultIsClankerAndAnExplicitChoiceSurvives() {
        assertEquals("Clanker", CommandGlyph.DEFAULT.label)
        assertEquals("clanker", CommandGlyph.DEFAULT.token)
        // An install that never chose one (no pref, or a token that names nothing) gets Clanker.
        assertEquals(CommandGlyph.Icon.CLANKER, CommandGlyph.Icon.of(null))
        assertEquals(CommandGlyph.Icon.CLANKER, CommandGlyph.Icon.of(""))
        assertEquals(CommandGlyph.Icon.CLANKER, CommandGlyph.Icon.of("bugdroid"))
        // Every token the picker can write maps back to itself, and to nothing else — so a
        // saved Steve stays Steve even though it is no longer the default.
        for (icon in icons) assertEquals(icon, CommandGlyph.Icon.of(icon.token))
        assertEquals(CommandGlyph.Icon.STEVE, CommandGlyph.Icon.of("steve"))
    }

    @Test fun theFiveChoicesAreTheOnesTheOwnerNamed() {
        assertEquals(listOf("Steve", "Bill", "Linus", "Hermes", "Clanker"), icons.map { it.label })
        assertEquals(listOf("steve", "bill", "linus", "hermes", "clanker"), icons.map { it.token })
        assertEquals("five options", 5, icons.size)
    }

    @Test fun fiveDistinctDrawings() {
        val signatures = icons.associateWith { icon -> CommandGlyph.shapes(icon).toString() }
        assertEquals("every icon must draw something", icons.size, signatures.size)
        assertEquals("two icons share one drawing", icons.size, signatures.values.toSet().size)
        for (icon in icons) assertTrue("${icon.label} has no geometry", CommandGlyph.shapes(icon).isNotEmpty())
        // The four borrowed-from-nowhere glyphs and our robot each stay inside the grid.
        for (icon in icons) {
            val (lo, hi) = bounds(icon)
            assertTrue("${icon.label} spills off the 24 dp grid (${"%.2f".format(lo)}..${"%.2f".format(hi)})",
                lo >= 0.5f && hi <= 23.5f)
        }
    }

    // ---- the ink -------------------------------------------------------------

    @Test fun theGlyphsArePaintedInTheThemesInkNotALiteralColour() {
        assertEquals("hv_text", CommandGlyph.INK)
        val source = File("src/main/java/com/hermesvox/CommandGlyph.kt").readText()
        // No colour literal and no colour construction: the ink arrives as a parameter.
        assertFalse("a hard-coded ARGB in the glyph drawing",
            Regex("0x[0-9A-Fa-f]{6,8}").containsMatchIn(source))
        assertFalse("a colour built at draw time",
            Regex("""Color\.(parseColor|rgb|argb|valueOf|BLACK|WHITE|GRAY)""").containsMatchIn(source))
        assertTrue("the mark resolves the theme's text token",
            File("src/main/java/com/hermesvox/CommandMark.kt").readText()
                .contains("R.color.hv_text"))
        assertTrue("the entry's drawable resolves the theme's text token",
            source.contains("R.color.hv_text"))
    }

    @Test fun eachGlyphClears4_5OnBothGrounds() {
        println("icon,light_page,light_row,dark_page,dark_row")
        val floor = 4.5f
        for (icon in icons) {
            val cell = ArrayList<String>()
            for (night in listOf(false, true)) {
                val ink = resColor("hv_text", night)
                // Where the glyph is drawn: the page the entry sits on and a row/sheet
                // surface (commands rows and the Settings row are their own ground).
                val page = resColor("hv_bg", night)
                val row = resColor("hv_surface", night)
                for ((where, back) in listOf("page" to page, "row" to row)) {
                    val r = Contrast.ratio(ink, back)
                    cell += "%.2f".format(r)
                    assertTrue("${icon.label} on the ${if (night) "dark" else "light"} $where: " +
                        "${"%.2f".format(r)}:1 (ink #%06X on #%06X, floor %.1f)"
                            .format(ink and 0xFFFFFF, back and 0xFFFFFF, floor), r >= floor)
                }
            }
            println("${icon.label}," + cell.joinToString(","))
        }
    }

    // ---- the label -----------------------------------------------------------

    @Test fun theEntryStillSaysCommandsWhateverTheIconIsCalled() {
        assertEquals("Commands", CommandGlyph.LABEL)
        for (icon in icons) {
            assertEquals("Commands", CommandGlyph.contentDescription(icon))
            assertNotEquals("the icon's name must not be announced", icon.label,
                CommandGlyph.contentDescription(icon))
        }
        // The layout states it before any code runs, and the activity keeps it.
        val layout = File("src/main/res/layout/activity_main.xml").readText()
        val entry = Regex("""android:id="@\+id/commands"[\s\S]*?android:contentDescription="([^"]*)""")
            .find(layout)?.groupValues?.get(1)
        assertEquals("Commands", entry)
        assertTrue(File("src/main/java/com/hermesvox/MainActivity.kt").readText()
            .contains("contentDescription = CommandGlyph.contentDescription(icon)"))
    }

    // ---- where it applies ----------------------------------------------------

    @Test fun everyPlaceThatDrewTheCommandIsTheOneThatAsksThePref() {
        // The main entry, the commands sheet's header, and the Appearance row: one pref
        // read (CommandGlyph.current), asked wherever the glyph is drawn.
        for (file in listOf("MainActivity.kt", "CommandsSheet.kt", "SettingsActivity.kt")) {
            assertTrue("$file does not ask the pref",
                File("src/main/java/com/hermesvox/$file").readText().contains("CommandGlyph.current("))
        }
        assertFalse("the fixed ⌘ resource is still shipped — a second drawing",
            File("src/main/res/drawable/ic_command.xml").exists())
    }

    // ---- helpers -------------------------------------------------------------

    /** The outer extent of one icon's ink (geometry plus half its stroke). */
    private fun bounds(icon: CommandGlyph.Icon): Pair<Float, Float> {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        fun note(x: Float, y: Float) {
            for (v in listOf(x, y)) { lo = minOf(lo, v); hi = maxOf(hi, v) }
        }
        for (s in CommandGlyph.shapes(icon)) when (s) {
            is CommandGlyph.Poly -> s.pts.forEach { note(it.x, it.y) }
            is CommandGlyph.Curve -> s.pts.forEach { note(it.x, it.y) }
            is CommandGlyph.Arc -> {
                note(s.centre.x - s.rx, s.centre.y - s.ry)
                note(s.centre.x + s.rx, s.centre.y + s.ry)
            }
            is CommandGlyph.Disc -> {
                note(s.centre.x - s.radius, s.centre.y - s.radius)
                note(s.centre.x + s.radius, s.centre.y + s.radius)
            }
            is CommandGlyph.Rounded -> { note(s.left, s.top); note(s.right, s.bottom) }
        }
        // A stroke is drawn astride the centreline, so half of it reaches past the point.
        val half = CommandGlyph.shapes(icon).maxOf { it.weight } / 2f
        return (lo - half) to (hi + half)
    }

    private fun resColor(name: String, night: Boolean): Int {
        val dir = if (night) "values-night" else "values"
        val f = listOf(File("src/main/res/$dir/colors.xml"),
            File("android/app/src/main/res/$dir/colors.xml")).firstOrNull { it.isFile }
            ?: throw IllegalStateException("cannot find $dir/colors.xml from ${File(".").absolutePath}")
        val xml = f.readText()
        val m = Regex("""<color name="$name">#([0-9A-Fa-f]{6,8})</color>""").find(xml)
            ?: throw AssertionError("$name missing from ${f.path}")
        val hex = m.groupValues[1]
        val v = hex.toLong(16)
        return if (hex.length == 8) v.toInt() else (0xFF000000L or v).toInt()
    }
}
