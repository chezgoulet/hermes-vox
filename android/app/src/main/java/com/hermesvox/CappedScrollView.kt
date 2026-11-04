package com.hermesvox

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * A ScrollView that is only as tall as its content, up to [CAP] of the screen, and scrolls past
 * that. The what's-new card is the case: a long release note pushed the being, the call controls
 * and the card's own "Got it" off the bottom of the screen, with nothing to scroll — the app was
 * unusable on the first launch after an upgrade. Capped, the note scrolls inside its card and
 * everything under it stays where it lives.
 */
class CappedScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = (resources.displayMetrics.heightPixels * CAP).toInt()
        val given = MeasureSpec.getSize(heightMeasureSpec)
        val limit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) cap else minOf(cap, given)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
    }

    companion object {
        /** The most of the screen a note may take before it scrolls. */
        const val CAP = 0.34f
    }
}
