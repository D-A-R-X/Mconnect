package com.manjugroups.m_connect.ui.custom

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * Root container for Home that keeps the blue header PINNED while the white
 * content scrolls up *over* it — but still lets the scrolled-over Overview
 * (tabs, cards) receive touches.
 *
 * Why this exists: the header and the full-height scroll panel overlap. The
 * header draws first (behind); the scroll panel draws on top so the white
 * slides over the blue (via clipChildren=false). A plain FrameLayout would
 * then send every touch to the scroll panel (topmost), so the header's own
 * buttons (avatar / bell / "View Summary") wouldn't work at rest — and the
 * older pinned-sibling layout had the opposite bug (the header owned the
 * touches even where the content had scrolled over it, so the tabs were
 * untappable once scrolled).
 *
 * Fix: route each gesture by where it starts relative to the *visible* header
 * bottom (= headerHeight − scrollY). Above that line the header is showing →
 * the header handles it; below it the content covers the header → the scroll
 * panel handles it. Degrades to normal FrameLayout dispatch until wired.
 */
class HomeTouchRouterLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** The pinned header (drawn behind). */
    var headerView: View? = null

    /** The full-height scroll panel (drawn in front). */
    var scrollContainer: View? = null

    /** Screen-y (in this view's coords) of the visible header bottom:
     *  headerHeight − scrollY. Touches above go to the header, below to scroll. */
    var coverLineProvider: (() -> Int)? = null

    private var routeToHeader = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val header = headerView
        val scroll = scrollContainer
        val provider = coverLineProvider
        if (header == null || scroll == null || provider == null) {
            return super.dispatchTouchEvent(ev)
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            routeToHeader = ev.y < provider.invoke()
        }
        // Keep the whole gesture on the child chosen at DOWN.
        return (if (routeToHeader) header else scroll).dispatchTouchEvent(ev)
    }
}
