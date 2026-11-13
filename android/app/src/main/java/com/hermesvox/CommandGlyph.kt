package com.hermesvox

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * CommandGlyph — the glyph on the commands entry, drawn from our own vector paths.
 *
 * The entry used to be one fixed mark (U+2318). It is now a choice, made in
 * Settings → Appearance by looking at the five drawings rather than reading a name
 * ([Icon]), and every place the mark appears asks [current] for the one pref:
 *
 *   * the commands entry on the main screen ([CommandMark.drawable]),
 *   * the commands sheet's header ([com.hermesvox.CommandsSheet]),
 *   * the Appearance row and its chooser ([com.hermesvox.SettingsActivity]).
 *
 * WHY PATHS, NOT TEXT (#370's rule, kept): a font glyph is whatever the device's font
 * ships; these are the same five drawings on every phone. The geometry below is plain
 * data on a [GRID]×[GRID] viewport, so it is measured off-device by `CommandGlyphTest`
 * and painted by [CommandMark]; no bitmap and no font are involved.
 *
 * INK: nothing here names a colour. Every glyph is painted in [INK] — the theme's text
 * token (`hv_text`) — so the same drawing is ink on the light page and light on the
 * black, and `CommandGlyphTest` holds each to 4.5:1 on both grounds.
 *
 * THE LABEL: the content description of the entry is still "Commands" whatever the
 * glyph is called ([LABEL]). "Clanker" is a choice in a settings list, not a name a
 * screen reader should announce.
 */
object CommandGlyph {

    /** The viewport every glyph is authored on, in dp at 1×. */
    const val GRID = 24f

    /** The pref that carries the choice; read only through [current]. */
    const val PREF = "command_icon"
    private const val STORE = "hv"

    /** The theme token every glyph is painted in — never a literal colour. */
    const val INK = "hv_text"

    /** What the entry announces, whatever the drawing is. */
    const val LABEL = "Commands"

    /** The five drawings, in the order the chooser lists them. */
    enum class Icon(val token: String, val label: String) {
        STEVE("steve", "Steve"),
        BILL("bill", "Bill"),
        LINUS("linus", "Linus"),
        HERMES("hermes", "Hermes"),
        CLANKER("clanker", "Clanker");

        companion object {
            /** An unknown or absent token is the shipped default, never an error. */
            fun of(token: String?): Icon = entries.firstOrNull { it.token == token } ?: DEFAULT
        }
    }

    /** Steve ships: the command symbol is what the entry drew before this was a setting, so
     *  adding the choice changes nothing until someone makes one. The pref is only ever
     *  written by the chooser, so an install that has never chosen gets this, and an explicit
     *  choice — Clanker included — is left exactly as it was saved. */
    val DEFAULT = Icon.STEVE

    /** The one pref read. Every screen calls this; none of them decodes the token itself. */
    fun current(context: Context): Icon = Icon.of(
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString(PREF, DEFAULT.token))

    /** What the entry says to a screen reader, for every icon. */
    @Suppress("UNUSED_PARAMETER")
    fun contentDescription(icon: Icon): String = LABEL

    // ---- the geometry -------------------------------------------------------

    /** A point on the [GRID] viewport. */
    data class Pt(val x: Float, val y: Float)

    /**
     * One drawing primitive. [weight] is the stroke width in viewport units; a filled
     * shape reports 0. Keeping the primitives as data (not `Path` objects) is what makes
     * the drawings measurable without a device.
     */
    sealed interface Shape { val weight: Float }

    /** A polyline (or, [closed], a polygon) drawn with [weight]'s stroke. */
    data class Poly(override val weight: Float, val pts: List<Pt>, val closed: Boolean = false) : Shape

    /** One or more cubic Béziers: 1 + 3n points — start, then (control, control, end) each. */
    data class Curve(override val weight: Float, val pts: List<Pt>) : Shape

    /** An elliptical arc, Android's angle convention (0° = 3 o'clock, clockwise). */
    data class Arc(
        override val weight: Float,
        val centre: Pt,
        val rx: Float,
        val ry: Float,
        val start: Float,
        val sweep: Float,
    ) : Shape

    /** A filled dot — the eyes, the antenna's bead. */
    data class Disc(val centre: Pt, val radius: Float) : Shape {
        override val weight: Float get() = 0f
    }

    /** A rounded outline, the robot's head. */
    data class Rounded(
        override val weight: Float,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val radius: Float,
    ) : Shape

    /**
     * The five drawings.
     *
     * Steve — the command symbol: a square knot between four loops (the original look).
     * Bill — the letters CMD, three stroked letterforms.
     * Linus — a shell prompt: `>` and an underscore.
     * Hermes — a caduceus: winged staff with two interwoven snakes.
     * Clanker — our robot: rounded head, two dot eyes, antenna, a small smile. Drawn
     * from scratch in the spirit of the reference the owner picked (a rounded outline
     * head, dot eyes, a top antenna and a simple mouth); no limbs, no dome, no trace.
     */
    fun shapes(icon: Icon): List<Shape> = when (icon) {
        Icon.STEVE -> {
            val w = 1.9f
            val loop = 3.2f
            // The loops cross the square's corners rather than kissing them: at 24 dp a
            // tangent join reads as four loose dots beside a box.
            listOf(
                Poly(w, listOf(Pt(9.4f, 9.4f), Pt(14.6f, 9.4f), Pt(14.6f, 14.6f), Pt(9.4f, 14.6f)), closed = true),
                Arc(w, Pt(6.4f, 6.4f), loop, loop, 0f, 360f),
                Arc(w, Pt(17.6f, 6.4f), loop, loop, 0f, 360f),
                Arc(w, Pt(6.4f, 17.6f), loop, loop, 0f, 360f),
                Arc(w, Pt(17.6f, 17.6f), loop, loop, 0f, 360f),
            )
        }
        Icon.BILL -> {
            val w = 1.8f
            // Three letterforms on one cap height (7..17): the C's bowl and the D's are the
            // same height as the M's stems, so the wordmark is not a C with two big brothers.
            listOf(
                // C — an open ring, opening to the right.
                Arc(w, Pt(5.4f, 12f), 3.4f, 5f, 50f, 260f),
                // M — down, to the middle, out, up.
                Poly(w, listOf(Pt(9.4f, 17f), Pt(9.4f, 7f), Pt(12.6f, 13.4f), Pt(15.8f, 7f), Pt(15.8f, 17f))),
                // D — stem plus a half-round bowl.
                Poly(w, listOf(Pt(17.8f, 7f), Pt(17.8f, 17f))),
                Arc(w, Pt(17.8f, 12f), 4f, 5f, -90f, 180f),
            )
        }
        Icon.LINUS -> {
            val w = 2.0f
            listOf(
                Poly(w, listOf(Pt(5.8f, 6.4f), Pt(11.8f, 12f), Pt(5.8f, 17.6f))),
                Poly(w, listOf(Pt(13.6f, 17.6f), Pt(19.4f, 17.6f))),
            )
        }
        Icon.HERMES -> {
            val w = 1.7f
            listOf(
                // the staff
                Poly(w, listOf(Pt(12f, 5.8f), Pt(12f, 19.2f))),
                // the bead at its top
                Disc(Pt(12f, 4.4f), 1.2f),
                // the wings, swept up and out from the staff
                Curve(w, listOf(Pt(12f, 7.2f), Pt(9f, 5.2f), Pt(5.6f, 5.6f), Pt(3.8f, 7.6f))),
                Curve(w, listOf(Pt(12f, 7.2f), Pt(15f, 5.2f), Pt(18.4f, 5.6f), Pt(20.2f, 7.6f))),
                // the two snakes: one turn each, crossing on the staff, so at 24 dp the
                // wave is wide enough to survive — a second turn reads as a knot.
                Curve(w, listOf(
                    Pt(12f, 8.6f),
                    Pt(16.6f, 9.8f), Pt(16.6f, 12.2f), Pt(12f, 13.4f),
                    Pt(7.4f, 14.6f), Pt(7.4f, 17f), Pt(12f, 19.4f),
                )),
                Curve(w, listOf(
                    Pt(12f, 8.6f),
                    Pt(7.4f, 9.8f), Pt(7.4f, 12.2f), Pt(12f, 13.4f),
                    Pt(16.6f, 14.6f), Pt(16.6f, 17f), Pt(12f, 19.4f),
                )),
            )
        }
        Icon.CLANKER -> {
            val w = 2.0f
            listOf(
                // head: a rounded outline, no dome, no limbs
                Rounded(w, 3.4f, 7.2f, 20.6f, 19.6f, 4.6f),
                // antenna
                Poly(1.8f, listOf(Pt(12f, 7.2f), Pt(12f, 4.4f))),
                Disc(Pt(12f, 3.2f), 1.2f),
                // eyes: two dots, set either side of the centre, above the mouth
                Disc(Pt(8.7f, 12.2f), 1.3f),
                Disc(Pt(15.3f, 12.2f), 1.3f),
                // mouth: a small smile, not a slot and not a speaker grille
                Arc(1.8f, Pt(12f, 14.6f), 3.4f, 1.6f, 20f, 140f),
            )
        }
    }

    // ---- painting -----------------------------------------------------------

    /** A drawable of one glyph in [ink], scaled to fit its bounds. */
    fun drawable(icon: Icon, ink: Int): Drawable = GlyphDrawable(icon, ink)

    /** The drawable the main entry uses: the glyph in the theme's ink. */
    fun drawable(context: Context, icon: Icon): Drawable =
        drawable(icon, androidx.core.content.ContextCompat.getColor(context, R.color.hv_text))

    /** Paints [icon] into the square of side [size] at ([left], [top]). */
    fun paint(canvas: Canvas, icon: Icon, ink: Int, left: Float, top: Float, size: Float) =
        GlyphPainter.paint(canvas, shapes(icon), ink, left, top, size)

    /**
     * The same painter, for any drawing authored in this vocabulary — the commands sheet's
     * row marks ([Marks]) and the model row's capability marks are geometry data exactly like
     * the entry glyphs, so they are laid out and measured by the same code (#3/#5).
     */
    fun paint(canvas: Canvas, shapes: List<Shape>, ink: Int, left: Float, top: Float, size: Float) =
        GlyphPainter.paint(canvas, shapes, ink, left, top, size)

    private class GlyphDrawable(private val icon: Icon, private val ink: Int) : Drawable() {
        override fun draw(canvas: Canvas) {
            val b = bounds
            val side = minOf(b.width(), b.height()).toFloat()
            if (side <= 0f) return
            paint(canvas, icon, ink, b.exactCenterX() - side / 2f, b.exactCenterY() - side / 2f, side)
        }
        // The ink is the theme's and the drawing is opaque: nothing to tint per draw.
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    private object GlyphPainter {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val path = Path()
        private val oval = RectF()

        fun paint(canvas: Canvas, shapes: List<Shape>, ink: Int, left: Float, top: Float, size: Float) {
            stroke.color = ink
            fill.color = ink
            val k = size / GRID
            if (k <= 0f) return
            canvas.save()
            canvas.translate(left, top)
            canvas.scale(k, k)
            for (shape in shapes) {
                path.reset()
                when (shape) {
                    is Poly -> {
                        path.moveTo(shape.pts[0].x, shape.pts[0].y)
                        for (i in 1 until shape.pts.size) path.lineTo(shape.pts[i].x, shape.pts[i].y)
                        if (shape.closed) path.close()
                        stroke.strokeWidth = shape.weight
                        canvas.drawPath(path, stroke)
                    }
                    is Curve -> {
                        path.moveTo(shape.pts[0].x, shape.pts[0].y)
                        var i = 1
                        while (i + 2 < shape.pts.size) {
                            path.cubicTo(shape.pts[i].x, shape.pts[i].y,
                                shape.pts[i + 1].x, shape.pts[i + 1].y,
                                shape.pts[i + 2].x, shape.pts[i + 2].y)
                            i += 3
                        }
                        stroke.strokeWidth = shape.weight
                        canvas.drawPath(path, stroke)
                    }
                    is Arc -> {
                        if (kotlin.math.abs(shape.sweep) >= 360f && shape.rx == shape.ry) {
                            path.addCircle(shape.centre.x, shape.centre.y, shape.rx, Path.Direction.CW)
                        } else {
                            oval.set(shape.centre.x - shape.rx, shape.centre.y - shape.ry,
                                shape.centre.x + shape.rx, shape.centre.y + shape.ry)
                            path.addArc(oval, shape.start, shape.sweep)
                        }
                        stroke.strokeWidth = shape.weight
                        canvas.drawPath(path, stroke)
                    }
                    is Disc -> canvas.drawCircle(shape.centre.x, shape.centre.y, shape.radius, fill)
                    is Rounded -> {
                        oval.set(shape.left, shape.top, shape.right, shape.bottom)
                        path.addRoundRect(oval, shape.radius, shape.radius, Path.Direction.CW)
                        stroke.strokeWidth = shape.weight
                        canvas.drawPath(path, stroke)
                    }
                }
            }
            canvas.restore()
        }
    }
}
