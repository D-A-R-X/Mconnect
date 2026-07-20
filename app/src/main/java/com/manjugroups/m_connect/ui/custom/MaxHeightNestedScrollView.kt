package com.manjugroups.m_connect.ui.custom

import android.content.Context
import android.util.AttributeSet
import androidx.core.widget.NestedScrollView

/**
 * A [NestedScrollView] that actually honours `android:maxHeight` (the base
 * class silently ignores it). Used by bottom-sheet forms so the scrollable
 * field area caps at a maximum while a footer button below it stays PINNED —
 * the sheet wraps content when the form is short, and the fields scroll inside
 * the cap when it's long, without the footer ever scrolling off.
 */
class MaxHeightNestedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : NestedScrollView(context, attrs, defStyleAttr) {

    private var maxHeightPx: Int = 0

    init {
        attrs?.let {
            val a = context.obtainStyledAttributes(it, intArrayOf(android.R.attr.maxHeight))
            maxHeightPx = a.getDimensionPixelSize(0, 0)
            a.recycle()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = if (maxHeightPx > 0) {
            MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
        } else {
            heightMeasureSpec
        }
        super.onMeasure(widthMeasureSpec, spec)
    }
}
