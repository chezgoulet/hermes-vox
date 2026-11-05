package com.hermesvox

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Icon
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * PipGlyph — the two RemoteAction marks, drawn from our own vector paths.
 *
 * The PiP controls are not part of the compact view: they belong to the system's window
 * chrome. What the system needs is an `Icon`, and an app that hands it a font glyph gets
 * whatever the device's font ships. These are the same kind of geometry as [CommandGlyph]
 * — authored on a 24×24 viewport, plain data, no text — rasterised only because
 * `RemoteAction` takes a bitmap.
 *
 * INK: [R.color.hv_text], the token [LightModeContrastTest] already holds to 4.5:1
 * against [R.color.hv_bg] — the ground the PiP window paints — on both themes. The colour
 * follows the theme with the rest of the app, so the controls read on the window's own
 * background rather than assuming a dark chrome.
 *
 * The shapes: STOP is the filled square every transport control has used for decades;
 * MUTE is a microphone with a slash, and UNMUTE the same microphone without it.
 */
object PipGlyph {

    /** The viewport every glyph is authored on, in dp at 1×. */
    const val GRID = 24f

    private const val STROKE = 1.9f

    /** The vector source for one action, on the [GRID] viewport. */
    private fun paths(action: Pip.Action): List<Path> = when (action) {
        Pip.Action.STOP -> {
            // The classic stop: a filled square with softly rounded corners.
            val square = Path().apply {
                addRoundRect(RectF(6.5f, 6.5f, 17.5f, 17.5f), 1.6f, 1.6f, Path.Direction.CW)
            }
            listOf(square)
        }
        Pip.Action.MUTE, Pip.Action.UNMUTE -> {
            val mic = Path().apply {
                // capsule body
                addRoundRect(RectF(10f, 3.4f, 14f, 13.2f), 2f, 2f, Path.Direction.CW)
                // the stand: a U under the body, a stem, a foot
                moveTo(6.9f, 11.1f); lineTo(6.9f, 12.2f)
                cubicTo(6.9f, 16.1f, 9.1f, 17.7f, 12f, 17.7f)
                cubicTo(14.9f, 17.7f, 17.1f, 16.1f, 17.1f, 12.2f)
                lineTo(17.1f, 11.1f)
                moveTo(12f, 17.7f); lineTo(12f, 20.6f)
                moveTo(9.3f, 20.6f); lineTo(14.7f, 20.6f)
            }
            val shapes = mutableListOf(mic)
            if (action == Pip.Action.MUTE) {
                // The slash: a clean diagonal across the body, with a small gap either side
                // so it reads as a slash and not as part of the microphone.
                shapes += Path().apply {
                    moveTo(4.4f, 4.4f); lineTo(19.6f, 19.6f)
                }
            }
            shapes
        }
    }

    /** How a [Pip.Action]'s shapes are painted: STOP is filled, the microphone is stroked. */
    private fun filled(action: Pip.Action): Boolean = action == Pip.Action.STOP

    /**
     * The bitmap an `Icon` carries. [sizePx] is the square the mark is drawn into; the
     * RemoteAction menu sizes are small, so this is authored at 24 dp at the device's
     * density and never upscaled.
     */
    fun bitmap(context: Context, action: Pip.Action, sizePx: Int = (GRID * context.resources.displayMetrics.density).roundToInt()): Bitmap {
        val side = sizePx.coerceAtLeast(GRID.roundToInt())
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val scale = side / GRID
        canvas.scale(scale, scale)
        val ink = ContextCompat.getColor(context, R.color.hv_text)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            style = if (filled(action)) Paint.Style.FILL else Paint.Style.STROKE
            strokeWidth = STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (path in paths(action)) canvas.drawPath(path, paint)
        return out
    }

    /** The `Icon` a `RemoteAction` takes, in the app's ink. */
    fun icon(context: Context, action: Pip.Action): Icon = Icon.createWithBitmap(bitmap(context, action))
}
