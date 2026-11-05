package com.hermesvox

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * CommandMark — one [CommandGlyph.Icon] drawn live, in the theme's ink.
 *
 * This is the drawing as a control can hold it: the commands sheet's header puts one
 * beside the title, and Settings puts one in the Appearance row and one per choice in
 * the picker, each at that row's own size. Nothing is pre-rendered, so the glyph is
 * sharp at any density and follows the ground (light ink on the page, light on black).
 *
 * Decorative by default: the words beside it and the row's own label are what a screen
 * reader announces (see [CommandGlyph.LABEL] for the entry itself).
 */
class CommandMark @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val ink = ContextCompat.getColor(context, R.color.hv_text)

    /** The drawing this mark shows. Re-setting it repaints; the ink never changes. */
    var icon: CommandGlyph.Icon = CommandGlyph.DEFAULT
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = minOf(width, height).toFloat()
        if (side <= 0f) return
        CommandGlyph.paint(canvas, icon, ink,
            (width - side) / 2f, (height - side) / 2f, side)
    }
}
