package com.manjugroups.m_connect.ui.custom

import android.content.Context
import android.util.AttributeSet
import androidx.core.widget.NestedScrollView

/**
 * A [NestedScrollView] whose vertical scroll can be clamped to a maximum, so
 * Home's Overview stops at the SAME limit (banner covered, profile row still
 * visible) on BOTH the Marketing and HR tabs — even though one tab's grid is
 * taller than the other. A plain minimum-height trick only pads short content;
 * it can't cap a tab whose content is naturally tall enough to scroll further.
 *
 * Set [maxScrollY] to the limit for dashboard users, or Int.MAX_VALUE to
 * disable (e.g. the normal trip list, which must scroll all its rows).
 */
class HomeScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : NestedScrollView(context, attrs, defStyleAttr) {

    var maxScrollY: Int = Int.MAX_VALUE

    // NestedScrollView applies drag AND fling positions through onOverScrolled
    // (which bypasses scrollTo), so the clamp must live here to cap every path.
    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        super.onOverScrolled(scrollX, scrollY.coerceIn(0, maxScrollY), clampedX, clampedY)
    }

    // Programmatic scrolls go through scrollTo — clamp those too.
    override fun scrollTo(x: Int, y: Int) {
        super.scrollTo(x, y.coerceIn(0, maxScrollY))
    }
}
