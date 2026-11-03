package com.hermesvox

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/**
 * CrawlView — renders the transcript as a STATIC, top-fading block over the
 * black background. 0.5.0.3: the "Star Wars intro" upward drift is gone (field
 * verdict, verbatim: "we should simply eliminate the Star Wars scroll and have
 * the text render in") — the reply appears at rest, anchored above the bottom
 * pad, and still FADES OUT near the top of the view, so it never reaches or
 * obscures the particle-being above. The speech-locked reveal (spoken bright /
 * unspoken dim) is untouched. Two roles:
 *   - "reply"  bright, larger (the agent's words, synced with the voice)
 *   - "sse"    dimmer + 1-2px smaller (the dev stream log; visually distinct)
 *
 * #368 — scrollback. The crawl is canvas-drawn, so this is a vertical offset into
 * one layout, not a ScrollView. [CrawlHistory] holds the last few finished lines;
 * the in-flight reply is still [full]. [CrawlDocument] joins them (history first,
 * live last) and the speech cursor applies only to the live part. A press + downward
 * drag enters [CrawlScroll.State.READING]; a tap, or dragging back to the live edge,
 * exits. While reading a streamed reply may keep growing at the end — it is recorded
 * behind the reader, never yanked into view ([CrawlScroll.onContentAppended]).
 *
 * The being is deliberately not involved: it keeps breathing, the call keeps running,
 * and neither is an input to whether the trail may move.
 */
class CrawlView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var full = ""                      // accumulated (growing) text: the live reply
    private var historyText = ""               // finished lines, oldest first (#368)
    private var document = ""                  // history + gap + live, rebuilt on change
    // 0.5.0-previewA speech-locked transcript: chars BEFORE this index have actually been
    // spoken by the voice; chars at/after it are the tail the entity hasn't reached yet and
    // are drawn dim, so you see what is coming while the "spoken" boundary moves with the
    // audio. -1 = no speech lock (system TTS / the sse log) -> the whole block is normal.
    private var spoken = -1
    private var role = "reply"
    private var layout: StaticLayout? = null
    private var padBottom = dp(28f)

    private val scroll = CrawlScroll()
    private var reanchorPending = false
    private var returnStartedAt = 0L
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private val touchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop.toFloat() }

    /** #275: the crawl is text on the app's ground, so it follows the ground's tokens
     *  instead of the near-white that was painted for the old black surface. The glow
     *  shadow is a dark-theme effect only: on paper it only softens the glyph edge. */
    private val lightGround = (context.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) !=
        android.content.res.Configuration.UI_MODE_NIGHT_YES

    private val replyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = androidx.core.content.ContextCompat.getColor(context, R.color.hv_crawl_reply)
        textSize = sp(17f); typeface = android.graphics.Typeface.MONOSPACE
        if (!lightGround) setShadowLayer(6f, 0f, 0f, 0x552AC3DC.toInt())
    }
    private val ssePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = androidx.core.content.ContextCompat.getColor(context, R.color.hv_crawl_dim)
        textSize = sp(15f); typeface = android.graphics.Typeface.MONOSPACE
    }

    @JvmOverloads
    fun setText(t: String, spokenChars: Int = -1) {
        val textChanged = t != full
        if (!textChanged && spokenChars == spoken) return
        val oldDoc = document
        spoken = spokenChars
        if (textChanged) {
            full = t
            refreshDocument()
            if (role == "reply") {
                scroll.onContentAppended(CrawlDocument.appended(oldDoc, document))
                if (scroll.state == CrawlScroll.State.READING) reanchorPending = true
                updateContentDescription()
            }
            layout = null   // relayout only when the text itself moved
        }
        invalidate()
    }

    /** #368: the finished trail drawn above the live reply, oldest first. */
    fun setHistory(t: String) {
        if (t == historyText) return
        val oldDoc = document
        historyText = t
        refreshDocument()
        if (role == "reply") {
            scroll.onContentAppended(CrawlDocument.appended(oldDoc, document))
            if (scroll.state == CrawlScroll.State.READING) reanchorPending = true
            updateContentDescription()
        }
        layout = null
        invalidate()
    }

    fun setRole(r: String) {
        role = r; layout = null
        refreshDocument()
        // The SSE/dev log is diagnostic noise for a screen reader.
        importantForAccessibility = if (r == "sse") IMPORTANT_FOR_ACCESSIBILITY_NO else IMPORTANT_FOR_ACCESSIBILITY_YES
        if (r != "reply") scroll.reset()
        updateContentDescription()
        invalidate()
    }

    /** A new conversation: the trail is gone with it. */
    fun resetScroll() {
        scroll.reset(); reanchorPending = false; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (scroll.state == CrawlScroll.State.RETURNING) {
            val f = ((android.os.SystemClock.uptimeMillis() - returnStartedAt).toFloat() /
                RETURN_MS).coerceIn(0f, 1f)
            scroll.returnProgress(f)
        }

        val doc = document
        if (doc.isBlank()) { postInvalidateOnAnimation(); return }
        val tl = ensureLayout() ?: run { postInvalidateOnAnimation(); return }
        val paint = if (role == "sse") ssePaint else replyPaint

        // 0.5.0.3: the text renders IN PLACE — a static, top-faded transcript at
        // rest above the bottom pad. The upward drift (Star-Wars crawl) is gone;
        // the reveal boundary below still moves with the voice.
        val baseBottom = height - padBottom
        val blockTop0 = baseBottom - tl.height
        val fadeBand = height * 0.62f   // bottom of the view is fully bright; fades going up
        // The trail may be pulled down until its first line reaches the bright band
        // below the fade. A reply that already fits cannot be moved at all.
        scroll.setMaxOffset(maxOf(0f, fadeBand - blockTop0))
        if (scroll.state != CrawlScroll.State.READING) {
            reanchorPending = false
        } else if (reanchorPending) {
            reanchor(tl, blockTop0)
            reanchorPending = false
        }
        val blockTop = blockTop0 + scroll.offsetPx

        // #368: the speech cursor belongs to the live reply only; everything before it
        // is finished speech and paints at full line alpha.
        val prefix = if (role == "sse") 0 else CrawlDocument.liveStart(historyText, full)
        val docSpoken = if (spoken < 0) -1 else prefix + spoken.coerceIn(0, full.length)

        for (i in 0 until tl.lineCount) {
            val baseline = tl.getLineBaseline(i)
            val screenY = blockTop + baseline
            if (screenY < -50f) continue
            // top fade: 0 as a line nears the top (approaching the being) -> 1 at the bottom.
            // #372: the fade and the unspoken weight are per ground — on paper the fade
            // stops at a legible floor and the tail sits at its constant weight, because
            // dimming dark ink toward the page is the operation that removed the contrast.
            val a = CrawlInk.lineAlpha(screenY, fadeBand, lightGround)
            val lineStart = tl.getLineStart(i)
            val lineEnd = tl.getLineEnd(i)
            val bright = CrawlInk.spokenAlpha(a)
            val dim = CrawlInk.tailAlpha(bright, a, lightGround)
            // Split the line at the speech cursor: spoken side normal, unspoken side dim.
            // On night the top-fade alpha still multiplies both (unchanged); on paper the
            // weights are per ground, so the tail never drops under 4.5:1 (CrawlInk).
            val cut = if (docSpoken < 0) lineEnd else docSpoken.coerceIn(lineStart, lineEnd)
            if (cut > lineStart) {
                paint.alpha = bright
                canvas.drawText(doc, lineStart, cut, dp(8f), screenY, paint)
            }
            if (cut < lineEnd) {
                val x = dp(8f) + if (cut > lineStart) paint.measureText(doc, lineStart, cut) else 0f
                paint.alpha = dim
                canvas.drawText(doc, cut, lineEnd, x, screenY, paint)
            }
        }
        paint.alpha = 255

        postInvalidateOnAnimation()
    }

    // ---- #368: the press-and-swipe gesture -----------------------------------
    // There is no control and no permanent hint. The gesture is direct manipulation:
    // the transcript follows the finger, so dragging DOWN brings older lines down into
    // view. Tapping (a press that never moves) is the exit, and dragging back up to the
    // live edge is the same exit by movement.

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (role != "reply") return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.y; lastY = event.y; dragging = false
                scroll.press()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && kotlin.math.abs(event.y - downY) > touchSlop) dragging = true
                if (dragging) {
                    ensureLayout()
                    if (scroll.drag(event.y - lastY)) {
                        captureAnchor()
                        lastY = event.y
                        invalidate()
                        return true
                    }
                }
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) captureAnchor()
                val next = scroll.release()
                if (next == CrawlScroll.State.RETURNING) startReturn() else invalidate()
                if (!dragging && event.actionMasked == MotionEvent.ACTION_UP) performClick()
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun startReturn() {
        if (scroll.state != CrawlScroll.State.RETURNING) scroll.beginReturn()
        returnStartedAt = android.os.SystemClock.uptimeMillis()
        invalidate()
    }

    /** Remember where the eye is, measured from the document's end. */
    private fun captureAnchor() {
        if (scroll.state != CrawlScroll.State.READING) return
        if (scroll.offsetPx <= CrawlScroll.EDGE_PX) return
        val tl = layout ?: return
        val docLen = document.length
        if (docLen == 0) return
        val blockTop = (height - padBottom) - tl.height + scroll.offsetPx
        val refY = height * 0.5f - blockTop
        val line = tl.getLineForVertical(refY.toInt().coerceIn(0, (tl.height - 1).coerceAtLeast(0)))
        scroll.noteAnchor(docLen - tl.getLineStart(line))
    }

    /** Put the anchored line back at the middle of the view after the document grew. */
    private fun reanchor(tl: StaticLayout, blockTop0: Float) {
        val docLen = tl.text.length
        val anchor = scroll.anchorChar(docLen)
        val line = tl.getLineForOffset(anchor)
        val baseline = tl.getLineBaseline(line)
        scroll.setOffset(height * 0.5f - baseline - blockTop0)
    }

    private fun ensureLayout(): StaticLayout? {
        layout?.let { return it }
        if (width <= 0) return null
        val doc = document
        if (doc.isEmpty()) return null
        val paint = if (role == "sse") ssePaint else replyPaint
        return StaticLayout.Builder
            .obtain(doc, 0, doc.length, paint, (width - dp(16f)).toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(dp(3f), 1.18f)
            .build().also { layout = it }
    }

    private fun refreshDocument() {
        document = if (role == "sse") full else CrawlDocument.join(historyText, full)
        layout = null
    }

    /**
     * #368: the crawl is canvas-drawn, so TalkBack can only see it through this
     * description. It is the whole document — including the history a scroll reveals —
     * so the scrolled-back region is reachable, not just the live line. Deliberately
     * not a live region: the voice already speaks the reply, and a scroll must not
     * re-announce it.
     */
    private fun updateContentDescription() {
        if (role != "reply") { contentDescription = null; return }
        val doc = document.trim()
        contentDescription = if (doc.isEmpty()) null else doc
    }

    // #372: the draw-time weights and their floors live in CrawlInk (not here) because
    // they are contrast, and contrast is measured: CrawlDrawContrastTest composites the
    // same constants this view paints with, on both grounds.

    private fun dp(v: Float) = v * resources.displayMetrics.density + 0.5f
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity + 0.5f

    private companion object {
        /** The exit animation: fast enough not to feel like a second mode. */
        const val RETURN_MS = 180f
    }
}
