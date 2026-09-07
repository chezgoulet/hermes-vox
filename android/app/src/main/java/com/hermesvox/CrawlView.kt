package com.hermesvox

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View

/**
 * CrawlView — renders text as a drifting, top-fading crawl over the black
 * background (the "Star Wars intro" treatment Christopher asked for).
 *
 * As text is appended it is laid out; the block drifts upward over time and the
 * text FADES OUT near the top of the view, so it never reaches/obscures the
 * particle-being above. Two roles:
 *   - "reply"  bright, larger (the agent's words, synced with the voice)
 *   - "sse"    dimmer + 1-2px smaller (the dev stream log; visually distinct)
 */
class CrawlView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var full = ""                      // accumulated (growing) text
    // 0.5.0-previewA speech-locked transcript: chars BEFORE this index have actually been
    // spoken by the voice; chars at/after it are the tail the entity hasn't reached yet and
    // are drawn dim, so you see what is coming while the "spoken" boundary moves with the
    // audio. -1 = no speech lock (system TTS / the sse log) -> the whole block is normal.
    private var spoken = -1
    private var role = "reply"
    private var layout: StaticLayout? = null
    private var drift = 0f                     // upward drift offset (px)
    private var lastN = 0L
    private var padBottom = dp(28f)

    private val replyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD6F4FF.toInt(); textSize = sp(17f); typeface = android.graphics.Typeface.MONOSPACE
        setShadowLayer(6f, 0f, 0f, 0x552AC3DC.toInt())
    }
    private val ssePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5C6B7D.toInt(); textSize = sp(15f); typeface = android.graphics.Typeface.MONOSPACE
    }

    @JvmOverloads
    fun setText(t: String, spokenChars: Int = -1) {
        val textChanged = t != full
        if (!textChanged && spokenChars == spoken) return
        spoken = spokenChars
        if (textChanged) { full = t; layout = null }   // relayout only when the text itself moved
        invalidate()
    }
    fun setRole(r: String) { role = r; layout = null; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastN == 0L) 0.016f else ((now - lastN) / 1e9f).coerceIn(0.001f, 0.05f)
        lastN = now
        if (full.isBlank()) { drift = 0f; postInvalidateOnAnimation(); return }

        val paint = if (role == "sse") ssePaint else replyPaint
        val tl = layout ?: StaticLayout.Builder
            .obtain(full, 0, full.length, paint, (width - dp(16f)).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(dp(3f), 1.18f)
            .build().also { layout = it }

        // Slow, continuous upward crawl (Star-Wars drift), consuming the reply.
        val rate = if (role == "sse") 26f else 30f   // px/sec
        drift += rate * dt
        val baseBottom = height - padBottom
        val blockTop = baseBottom - tl.height - drift
        val fadeBand = height * 0.62f   // bottom of the view is fully bright; fades going up

        for (i in 0 until tl.lineCount) {
            val baseline = tl.getLineBaseline(i)
            val screenY = blockTop + baseline
            if (screenY < -50f) continue
            // top fade: 0 as a line nears the top (approaching the being) -> 1 at the bottom
            val a = (screenY / fadeBand).coerceIn(0f, 1f)
            val lineStart = tl.getLineStart(i)
            val lineEnd = tl.getLineEnd(i)
            val bright = (255 * a).toInt()
            val dim = (bright * UNSPOKEN_ALPHA).toInt()
            // Split the line at the speech cursor: spoken side normal, unspoken side dim.
            // The top-fade alpha still multiplies both, so the crawl is unchanged.
            val cut = if (spoken < 0) lineEnd else spoken.coerceIn(lineStart, lineEnd)
            if (cut > lineStart) {
                paint.alpha = bright
                canvas.drawText(full, lineStart, cut, dp(8f), screenY, paint)
            }
            if (cut < lineEnd) {
                val x = dp(8f) + if (cut > lineStart) paint.measureText(full, lineStart, cut) else 0f
                paint.alpha = dim
                canvas.drawText(full, cut, lineEnd, x, screenY, paint)
            }
        }
        paint.alpha = 255

        // Once the whole reply has crawled off + faded, hide it (clean idle).
        if (blockTop + tl.height < 0) setText("")
        postInvalidateOnAnimation()
    }

    private companion object {
        /** The unspoken tail's weight relative to the line's own fade alpha. */
        const val UNSPOKEN_ALPHA = 0.35f
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density + 0.5f
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity + 0.5f
}
