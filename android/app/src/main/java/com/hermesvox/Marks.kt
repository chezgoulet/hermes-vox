package com.hermesvox

/**
 * Marks — the row-sized drawings that are not one of the being's six motions (#3).
 *
 * A command's row leads with the still frame of the motion its [CommandCatalog.Category]
 * inherits ([VerbMark], #227). That grammar is the being's, and it says what *kind* of thing
 * a command does — which is why `/clear` and `/new` moved alike: both clear something. But
 * the sheet is where a person chooses between them, and "empties this conversation" and
 * "starts a new one" are different actions that must not wear the same glyph.
 *
 * So a command may name its own drawing ([CommandCatalog.Command.glyph]). These are authored
 * in the app's vector vocabulary — the same 24 dp grid and the same [CommandGlyph.Shape]
 * primitives as the commands entry, painted by the same code — so they are:
 *
 *   * geometry data, measurable off-device ([MarksTest]), not a font, a bitmap or an emoji;
 *   * ink from the caller (the row's tint): nothing here names a colour, so the same drawing
 *     is legible on the light page and on the black;
 *   * one stroke weight for everything, so a row's marks look like one set.
 *
 * [Mark.SHARE] is the platform's own share mark — the three-node, two-link graph
 * `ic_menu_share` draws — because "Share with agent" opens Android's share: drawing it as a
 * nucleus (the CHOOSE motion) made it look like the model picker.
 */
object Marks {

    /** The drawings a row can name. One per meaning, never one per command for its own sake. */
    enum class Mark(val label: String) {
        /** The platform's share graph: two nodes on the right joined to one on the left. */
        SHARE("share"),
        /** A new thread: an open ring with a plus — something begun, not something emptied. */
        NEW_THREAD("new thread"),
        /** The threads the agent already has: a short list, its newest line still short. */
        SESSIONS("sessions"),
        ;

        companion object {
            fun of(token: String?): Mark? = entries.firstOrNull { it.name.equals(token, true) }
        }
    }

    /** The stroke weight every mark is drawn with, in [CommandGlyph.GRID] units. */
    const val WEIGHT = 2.0f

    /**
     * The small marks a model row carries (#5) — one drawing per [ModelChoices.Cap], on the
     * same grid and with the same stroke as everything else here. They sit at 16 dp beside the
     * model's name, so each one is a single legible idea: chevrons for code, an eye for vision,
     * a spark for reasoning, a long arrow for a long window, a bolt for speed.
     */
    fun cap(cap: ModelChoices.Cap): List<CommandGlyph.Shape> = when (cap) {
        ModelChoices.Cap.CODE -> listOf(
            CommandGlyph.Poly(WEIGHT, listOf(CommandGlyph.Pt(9.6f, 8.2f), CommandGlyph.Pt(5.2f, 12f), CommandGlyph.Pt(9.6f, 15.8f))),
            CommandGlyph.Poly(WEIGHT, listOf(CommandGlyph.Pt(14.4f, 8.2f), CommandGlyph.Pt(18.8f, 12f), CommandGlyph.Pt(14.4f, 15.8f))),
        )
        ModelChoices.Cap.VISION -> listOf(
            // An almond outline and a pupil: an eye, not a camera.
            CommandGlyph.Arc(1.8f, CommandGlyph.Pt(12f, 12f), 8.4f, 5.0f, 0f, 360f),
            CommandGlyph.Disc(CommandGlyph.Pt(12f, 12f), 2.4f),
        )
        ModelChoices.Cap.REASONING -> listOf(
            // A spark: the model that thinks a moment longer.
            CommandGlyph.Poly(1.9f, listOf(CommandGlyph.Pt(12f, 4.4f), CommandGlyph.Pt(12f, 19.6f))),
            CommandGlyph.Poly(1.9f, listOf(CommandGlyph.Pt(5.4f, 8.2f), CommandGlyph.Pt(18.6f, 15.8f))),
            CommandGlyph.Poly(1.9f, listOf(CommandGlyph.Pt(18.6f, 8.2f), CommandGlyph.Pt(5.4f, 15.8f))),
        )
        ModelChoices.Cap.LONG_CONTEXT -> listOf(
            // A long line with an arrowhead: a window that keeps going.
            CommandGlyph.Poly(1.9f, listOf(CommandGlyph.Pt(4.4f, 12f), CommandGlyph.Pt(19.0f, 12f))),
            CommandGlyph.Poly(1.9f, listOf(CommandGlyph.Pt(14.8f, 8.4f), CommandGlyph.Pt(19.6f, 12f), CommandGlyph.Pt(14.8f, 15.6f))),
        )
        ModelChoices.Cap.FAST -> listOf(
            CommandGlyph.Poly(1.9f, listOf(
                CommandGlyph.Pt(13.8f, 4.4f), CommandGlyph.Pt(7.6f, 13.4f), CommandGlyph.Pt(11.6f, 13.4f),
                CommandGlyph.Pt(10.2f, 19.6f), CommandGlyph.Pt(16.6f, 10.6f), CommandGlyph.Pt(12.6f, 10.6f),
            ), closed = true),
        )
    }

    fun shapes(mark: Mark): List<CommandGlyph.Shape> = when (mark) {
        // The left node in the middle, the two right nodes above and below it, each pair
        // joined by a stroke that stops at the node's edge rather than crossing it.
        Mark.SHARE -> {
            val left = CommandGlyph.Pt(5.6f, 12f)
            val top = CommandGlyph.Pt(18.4f, 5.6f)
            val bottom = CommandGlyph.Pt(18.4f, 18.4f)
            val r = 2.6f
            listOf(
                CommandGlyph.Disc(left, r),
                CommandGlyph.Disc(top, r),
                CommandGlyph.Disc(bottom, r),
                CommandGlyph.Poly(WEIGHT, listOf(CommandGlyph.Pt(8.0f, 10.8f), CommandGlyph.Pt(16.0f, 6.8f))),
                CommandGlyph.Poly(WEIGHT, listOf(CommandGlyph.Pt(8.0f, 13.2f), CommandGlyph.Pt(16.0f, 17.2f))),
            )
        }
        // Three rules, the last one shorter: a list of threads, read top to bottom. It is
        // PULSE's meaning (/sessions and /models both choose) drawn as what is being chosen.
        Mark.SESSIONS -> listOf(
            CommandGlyph.Poly(WEIGHT, listOf(CommandGlyph.Pt(4.8f, 7.6f), CommandGlyph.Pt(19.2f, 7.6f))),
            CommandGlyph.Poly(WEIGHT, listOf(CommandGlyph.Pt(4.8f, 12f), CommandGlyph.Pt(19.2f, 12f))),
            CommandGlyph.Poly(WEIGHT, listOf(CommandGlyph.Pt(4.8f, 16.4f), CommandGlyph.Pt(14.4f, 16.4f))),
        )
        Mark.NEW_THREAD -> {
            // A ring open at the top right — the thread's loose end — around a plus: the one
            // row that begins something, as against SCATTER's dots, which are things leaving.
            listOf(
                CommandGlyph.Arc(WEIGHT, CommandGlyph.Pt(12f, 12f), 8.2f, 8.2f, 40f, 280f),
                CommandGlyph.Poly(WEIGHT, listOf(CommandGlyph.Pt(12f, 8.4f), CommandGlyph.Pt(12f, 15.6f))),
                CommandGlyph.Poly(WEIGHT, listOf(CommandGlyph.Pt(8.4f, 12f), CommandGlyph.Pt(15.6f, 12f))),
            )
        }
    }
}

/**
 * GlyphMark — a [Marks] drawing as a view, in one ink, scaled to whatever square it is given.
 * Its ink arrives as a colour (the row's tint), so it is the theme's and not a literal; the
 * mark carries no words of its own, because the row around it already says them.
 */
class GlyphMark(
    context: android.content.Context,
    private val shapes: List<CommandGlyph.Shape>,
    private val ink: Int,
) : android.view.View(context) {

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val side = minOf(width, height).toFloat()
        if (side <= 0f) return
        CommandGlyph.paint(canvas, shapes, ink, (width - side) / 2f, (height - side) / 2f, side)
    }
}
