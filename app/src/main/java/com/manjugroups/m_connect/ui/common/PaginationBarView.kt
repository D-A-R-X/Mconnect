package com.manjugroups.m_connect.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.manjugroups.m_connect.R

/**
 * Rows-per-page pagination footer (10/25/50/100), mirroring the web tables.
 *
 * Purely presentational: the host owns `page`/`pageSize`/the full list and
 * re-slices on the callbacks. Hides itself while there's nothing to paginate
 * (total ≤ the smallest page size).
 *
 * ```
 * paginationBar.onPageChange = { page -> ... }
 * paginationBar.onPageSizeChange = { size -> ... }   // host resets page to 1
 * paginationBar.bind(total = 172, page = 1, pageSize = 10)
 * ```
 */
class PaginationBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    var onPageChange: ((Int) -> Unit)? = null
    var onPageSizeChange: ((Int) -> Unit)? = null

    private val pillPageSize: View
    private val tvPageSize: TextView
    private val tvPageRange: TextView
    private val btnPrev: View
    private val btnNext: View

    private var total = 0
    private var page = 1
    private var pageSize = PAGE_SIZE_OPTIONS.first()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = (12 * resources.displayMetrics.density).toInt()
        val padV = (9 * resources.displayMetrics.density).toInt()
        setPadding(pad, padV, pad, padV)
        LayoutInflater.from(context).inflate(R.layout.view_pagination_bar, this, true)
        pillPageSize = findViewById(R.id.pillPageSize)
        tvPageSize = findViewById(R.id.tvPageSize)
        tvPageRange = findViewById(R.id.tvPageRange)
        btnPrev = findViewById(R.id.btnPagePrev)
        btnNext = findViewById(R.id.btnPageNext)

        pillPageSize.setOnClickListener { anchor ->
            val menu = PopupMenu(context, anchor)
            PAGE_SIZE_OPTIONS.forEachIndexed { i, size ->
                menu.menu.add(0, i, i, size.toString())
            }
            menu.setOnMenuItemClickListener { item ->
                onPageSizeChange?.invoke(PAGE_SIZE_OPTIONS[item.itemId])
                true
            }
            menu.show()
        }
        btnPrev.setOnClickListener { if (page > 1) onPageChange?.invoke(page - 1) }
        btnNext.setOnClickListener { if (page < pageCount()) onPageChange?.invoke(page + 1) }
    }

    private fun pageCount(): Int =
        if (total <= 0) 1 else (total + pageSize - 1) / pageSize

    /** Refresh the bar for the current list state. */
    fun bind(total: Int, page: Int, pageSize: Int) {
        this.total = total
        this.pageSize = pageSize
        this.page = page.coerceIn(1, pageCount())

        // Nothing to paginate — stay out of the way entirely.
        if (total <= PAGE_SIZE_OPTIONS.first()) {
            visibility = GONE
            return
        }
        visibility = VISIBLE

        tvPageSize.text = pageSize.toString()
        val from = (this.page - 1) * pageSize + 1
        val to = minOf(this.page * pageSize, total)
        tvPageRange.text = "$from–$to of $total"

        btnPrev.isEnabled = this.page > 1
        btnPrev.alpha = if (btnPrev.isEnabled) 1f else 0.35f
        btnNext.isEnabled = this.page < pageCount()
        btnNext.alpha = if (btnNext.isEnabled) 1f else 0.35f
    }

    companion object {
        val PAGE_SIZE_OPTIONS = listOf(10, 25, 50, 100)

        /** Clamp-slice one page out of [list] (1-based [page]). */
        fun <T> pageOf(list: List<T>, page: Int, pageSize: Int): List<T> {
            if (list.size <= PAGE_SIZE_OPTIONS.first()) return list
            val fromIdx = ((page - 1) * pageSize).coerceIn(0, (list.size - 1).coerceAtLeast(0))
            val toIdx = (fromIdx + pageSize).coerceAtMost(list.size)
            return list.subList(fromIdx, toIdx)
        }

        /** The highest valid page for a list of [total] rows. */
        fun maxPage(total: Int, pageSize: Int): Int =
            if (total <= 0) 1 else (total + pageSize - 1) / pageSize
    }
}
