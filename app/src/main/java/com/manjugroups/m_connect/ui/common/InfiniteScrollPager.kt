package com.manjugroups.m_connect.ui.common

import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Reusable infinite-scroll windowing for the app's data lists.
 *
 * The list endpoints return the whole result set in one shot (no server
 * cursor), and rendering hundreds of rows at once — especially the manual
 * card-inflation screens (CP/SV visits, leaves, permissions…) — pegged the
 * UI thread and ANR'd. This pager renders only a growing WINDOW of the
 * already-fetched, already-filtered list: it starts at [pageSize] rows and
 * grows by [pageSize] more each time the user scrolls within
 * [nearEndThreshold] rows of the end, repeating until the window covers the
 * whole list.
 *
 * It owns only the window size and the scroll trigger — the host still owns
 * its data, filtering and row rendering. On every extend it invokes
 * [onLoadMore], where the host re-renders using [limit] (e.g.
 * `filtered.take(pager.limit)`).
 *
 * Works with either a RecyclerView ([bindRecyclerView]) or a
 * NestedScrollView wrapping a manual LinearLayout ([bindNestedScroll]).
 * Call [reset] whenever the filter/search/tab changes so a new view starts
 * from the top of the window again.
 *
 * Replaces the old rows-per-page PaginationBarView.
 */
class InfiniteScrollPager(
    val pageSize: Int = DEFAULT_PAGE,
    private val nearEndThreshold: Int = DEFAULT_THRESHOLD,
    private val onLoadMore: () -> Unit,
) {
    private var visibleLimit = pageSize

    /** Number of rows the host should currently render (`list.take(limit)`). */
    val limit: Int get() = visibleLimit

    /** True while the window is smaller than the full filtered list. */
    fun hasMore(total: Int): Boolean = visibleLimit < total

    /** Back to the first page — call on filter/search/tab change. */
    fun reset() {
        visibleLimit = pageSize
    }

    private fun extend(total: Int) {
        if (visibleLimit >= total) return
        visibleLimit += pageSize
        onLoadMore()
    }

    /**
     * Grow the window as the RecyclerView nears the end. [totalCount] returns
     * the full filtered size so we only grow while more rows exist.
     */
    fun bindRecyclerView(
        recycler: RecyclerView,
        layoutManager: LinearLayoutManager,
        totalCount: () -> Int,
    ) {
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return // only on downward scroll
                val last = layoutManager.findLastVisibleItemPosition()
                if (last >= visibleLimit - nearEndThreshold) extend(totalCount())
            }
        })
    }

    /**
     * Grow the window as the NestedScrollView (manual-inflation lists) nears
     * the bottom. Uses a pixel threshold since there is no adapter to count
     * positions — ~[nearEndThreshold] rows' worth of height ahead of the end.
     */
    fun bindNestedScroll(
        scrollView: NestedScrollView,
        totalCount: () -> Int,
        rowHeightPx: Int = DEFAULT_ROW_HEIGHT_DP.dp(scrollView),
    ) {
        scrollView.setOnScrollChangeListener(
            NestedScrollView.OnScrollChangeListener { v, _, scrollY, _, _ ->
                val child = v.getChildAt(0) ?: return@OnScrollChangeListener
                val distanceToBottom = child.measuredHeight - (scrollY + v.measuredHeight)
                if (distanceToBottom <= nearEndThreshold * rowHeightPx) extend(totalCount())
            }
        )
    }

    private fun Int.dp(view: android.view.View): Int =
        (this * view.resources.displayMetrics.density).toInt()

    companion object {
        const val DEFAULT_PAGE = 20
        const val DEFAULT_THRESHOLD = 5
        private const val DEFAULT_ROW_HEIGHT_DP = 84
    }
}
