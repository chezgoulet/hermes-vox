package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #3: `/clear` and `/new` drew the same glyph, and the share row drew a nucleus instead of
 * the platform's share mark.
 *
 * Every mark is geometry on the [CommandGlyph.GRID] grid, so these hold it to what a person
 * sees without a device: the share mark really is the three-node, two-link graph
 * `ic_menu_share` draws; the new-thread mark is a different drawing from the dots `/clear`
 * inherits; nothing spills off the grid; and no drawing names a colour — the ink is the
 * row's tint, which `LightModeContrastTest` already holds to its floor on both grounds.
 */
class MarksTest {

    private val marks = Marks.Mark.entries

    /** Every point a shape draws, including a disc's rim, so "inside the grid" means it. */
    private fun points(shapes: List<CommandGlyph.Shape>): List<CommandGlyph.Pt> = shapes.flatMap { s ->
        when (s) {
            is CommandGlyph.Poly -> s.pts
            is CommandGlyph.Curve -> s.pts
            is CommandGlyph.Arc -> listOf(
                CommandGlyph.Pt(s.centre.x - s.rx, s.centre.y - s.ry),
                CommandGlyph.Pt(s.centre.x + s.rx, s.centre.y + s.ry))
            is CommandGlyph.Disc -> listOf(
                CommandGlyph.Pt(s.centre.x - s.radius, s.centre.y - s.radius),
                CommandGlyph.Pt(s.centre.x + s.radius, s.centre.y + s.radius))
            is CommandGlyph.Rounded -> listOf(CommandGlyph.Pt(s.left, s.top), CommandGlyph.Pt(s.right, s.bottom))
        }
    }

    private fun bounds(mark: Marks.Mark): Pair<Float, Float> {
        val pts = points(Marks.shapes(mark))
        return pts.minOf { it.x } to pts.maxOf { it.x }
    }

    @Test fun everyMarkFitsTheGrid() {
        assertTrue(marks.isNotEmpty())
        for (mark in marks) {
            assertTrue("$mark draws nothing", Marks.shapes(mark).isNotEmpty())
            val pts = points(Marks.shapes(mark))
            val lo = pts.minOf { minOf(it.x, it.y) }
            val hi = pts.maxOf { maxOf(it.x, it.y) }
            assertTrue("$mark spills off the ${CommandGlyph.GRID} grid ($lo..$hi)", lo >= 0.5f && hi <= 23.5f)
        }
    }

    @Test fun shareDrawsThePlatformsShareGraph() {
        val shapes = Marks.shapes(Marks.Mark.SHARE)
        val discs = shapes.filterIsInstance<CommandGlyph.Disc>()
        val lines = shapes.filterIsInstance<CommandGlyph.Poly>()
        assertEquals("three dots", 3, discs.size)
        assertEquals("two joining lines", 2, lines.size)
        // One node on the left, two on the right above and below it — ic_menu_share's shape.
        val left = discs.minByOrNull { it.centre.x }!!
        val right = discs.sortedByDescending { it.centre.x }.take(2)
        assertTrue("the left node sits between the two on the right",
            left.centre.y > right[0].centre.y && left.centre.y < right[1].centre.y ||
                left.centre.y > right[1].centre.y && left.centre.y < right[0].centre.y)
        assertTrue("the two right nodes are stacked, not side by side",
            kotlin.math.abs(right[0].centre.x - right[1].centre.x) < 0.5f)
        // Every link starts near one node and ends near another: a graph, not decoration.
        for (line in lines) {
            val ends = listOf(line.pts.first(), line.pts.last())
            assertEquals("a link touches a node at each end", 2, ends.count { end ->
                discs.any { d -> kotlin.math.hypot(d.centre.x - end.x, d.centre.y - end.y) <= d.radius + Marks.WEIGHT + 1f }
            })
            assertEquals(2, line.pts.size)
        }
    }

    @Test fun newThreadIsNotTheDotsClearInherits() {
        // /clear keeps the being's SCATTER still; /new names its own drawing. Two commands
        // that clear different things no longer wear one glyph.
        val clear = CommandCatalog.find("/clear")!!
        val new = CommandCatalog.find("/new")!!
        assertEquals("no glyph of its own", null, clear.glyph)
        assertEquals(Marks.Mark.NEW_THREAD, new.glyph)
        assertEquals(CommandMotion.Verb.SCATTER, CommandMotion.verbFor(clear.category))
        // The being still thinks both clear something — that is the motion, not the row mark.
        assertEquals(CommandMotion.verbFor(clear.category), CommandMotion.verbFor(new.category))
    }

    @Test fun theShareRowDrawsTheShareMark() {
        assertEquals(Marks.Mark.SHARE, CommandCatalog.SHARE.glyph)
        // Its motion is unchanged: it is still a CHOOSE, and the being still pulses for it.
        assertEquals(CommandMotion.Verb.PULSE, CommandMotion.verbFor(CommandCatalog.SHARE.category))
    }

    @Test fun sessionsIsNotTheSameDrawingAsTheModelPicker() {
        // Both /sessions and /models are CHOOSE → PULSE, and they sit one above the other;
        // /sessions names a list instead.
        val sessions = CommandCatalog.find("/sessions", CommandCatalog.Gate.EVERYTHING)!!
        assertEquals(Marks.Mark.SESSIONS, sessions.glyph)
        assertEquals(null, CommandCatalog.find("/models")!!.glyph)
        // Every row on the sheet a fresh install shows draws a different mark, so no two
        // visible rows can be mistaken for each other.
        fun drawnOf(list: List<CommandCatalog.Command>) = list.map {
            it.glyph?.let { g -> "mark:$g" } ?: "verb:" + CommandMotion.verbFor(it.category)
        }
        val shown = drawnOf(CommandCatalog.grouped().flatMap { it.second } + CommandCatalog.SHARE)
        assertEquals("two rows draw the same mark: $shown", shown.size, shown.toSet().size)
    }

    @Test fun everyDrawingIsDistinctAndNamedForWhatItSays() {
        val signatures = marks.associateWith { Marks.shapes(it).toString() }
        assertEquals("two marks share one drawing", marks.size, signatures.values.toSet().size)
        for (mark in marks) assertTrue("$mark has no words", mark.label.isNotBlank())
        assertEquals(Marks.Mark.SHARE, Marks.Mark.of("share"))
        assertEquals(null, Marks.Mark.of("nope"))
        // One stroke weight for the whole set, so the rows read as one icon family.
        for (mark in marks) for (shape in Marks.shapes(mark))
            if (shape !is CommandGlyph.Disc) assertEquals("$mark", Marks.WEIGHT, shape.weight)
    }

    @Test fun everyCapabilityMarkIsItsOwnDrawingAndFitsTheGrid() {
        val caps = ModelChoices.Cap.entries
        val signatures = caps.associateWith { Marks.cap(it).toString() }
        assertEquals("two capabilities share one drawing", caps.size, signatures.values.toSet().size)
        for (cap in caps) {
            assertTrue("$cap draws nothing", Marks.cap(cap).isNotEmpty())
            val pts = points(Marks.cap(cap))
            val lo = pts.minOf { minOf(it.x, it.y) }
            val hi = pts.maxOf { maxOf(it.x, it.y) }
            assertTrue("$cap spills off the grid ($lo..$hi)", lo >= 0.5f && hi <= 23.5f)
            // One idea per mark, so it still reads at the row's 16 dp.
            assertTrue("$cap is too fine to read at 16 dp", Marks.cap(cap).size <= 3)
        }
        // The model sheet draws them at the row size and says each one in the row's own
        // announcement — a screen reader never has to see a drawing.
        val sheet = File("src/main/java/com/hermesvox/ModelSheet.kt").readText()
        assertTrue(sheet.contains("dp(16), dp(16)"))
        assertTrue(sheet.contains("caps.map(capWords)"))
        for (cap in caps) assertTrue("the sheet drops ${cap.says}", sheet.contains("caps"))
    }

    @Test fun theInkIsTheThemesAndNeverALiteral() {
        for (file in listOf("Marks.kt", "CommandsSheet.kt")) {
            val source = File("src/main/java/com/hermesvox/$file").readText()
            assertFalse("$file hard-codes an ARGB colour",
                Regex("0x[0-9A-Fa-f]{6,8}").containsMatchIn(source))
            assertFalse("$file builds a colour at draw time",
                Regex("""Color\.(parseColor|rgb|argb|valueOf|BLACK|WHITE|GRAY)""").containsMatchIn(source))
        }
        val source = File("src/main/java/com/hermesvox/CommandsSheet.kt").readText()
        assertTrue("the row mark is tinted by the theme's accent",
            source.contains("R.color.hv_cyan") && source.contains("R.color.hv_warn"))
        // The mark is placed at the sheet's row size, where the 2-unit stroke still reads.
        assertTrue(source.contains("dp(26), dp(26)"))
    }
}
