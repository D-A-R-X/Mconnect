package com.manjugroups.m_connect.ui.common

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.manjugroups.m_connect.R

class HorizontalTabLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    data class Tab(
        val title: String,
        var badgeCount: Int? = null
    )

    interface OnTabSelectedListener {
        fun onTabSelected(index: Int)
    }

    private val container: LinearLayout
    private var tabs: List<Tab> = emptyList()
    private var selectedIndex = 0
    private var listener: OnTabSelectedListener? = null

    init {
        overScrollMode = OVER_SCROLL_NEVER
        isHorizontalScrollBarEnabled = false

        container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }
        addView(container)

        val pad = dpToPx(20)
        val padBottom = dpToPx(12)
        setPadding(pad, 0, pad, padBottom)
        clipToPadding = false
    }

    fun setTabs(tabs: List<Tab>, defaultSelection: Int = 0) {
        this.tabs = tabs
        this.selectedIndex = defaultSelection
        rebuildTabs()
    }

    fun setOnTabSelectedListener(listener: OnTabSelectedListener) {
        this.listener = listener
    }

    fun getSelectedIndex(): Int = selectedIndex

    fun selectTab(index: Int) {
        if (index in tabs.indices && selectedIndex != index) {
            val oldIndex = selectedIndex
            selectedIndex = index

            val oldView = container.getChildAt(oldIndex)
            if (oldView != null) {
                updateTabStyle(
                    oldView,
                    oldView.findViewById(R.id.tvTabText),
                    oldView.findViewById(R.id.tvTabBadge),
                    false
                )
            }

            val newView = container.getChildAt(index)
            if (newView != null) {
                updateTabStyle(
                    newView,
                    newView.findViewById(R.id.tvTabText),
                    newView.findViewById(R.id.tvTabBadge),
                    true
                )
            }

            listener?.onTabSelected(index)
        }
    }

    fun updateBadge(index: Int, count: Int?) {
        if (index in tabs.indices) {
            tabs[index].badgeCount = count
            val tabView = container.getChildAt(index) ?: return
            val badge = tabView.findViewById<TextView>(R.id.tvTabBadge) ?: return
            if (count != null && count > 0) {
                badge.text = count.toString()
                badge.visibility = View.VISIBLE
            } else {
                badge.visibility = View.GONE
            }
        }
    }

    private fun rebuildTabs() {
        container.removeAllViews()
        tabs.forEachIndexed { index, tab ->
            val tabView = LayoutInflater.from(context).inflate(R.layout.view_horizontal_tab_item, container, false)

            val params = tabView.layoutParams as LinearLayout.LayoutParams
            if (index > 0) {
                params.marginStart = dpToPx(8)
            } else {
                params.marginStart = 0
            }
            tabView.layoutParams = params

            val tvText = tabView.findViewById<TextView>(R.id.tvTabText)
            val tvBadge = tabView.findViewById<TextView>(R.id.tvTabBadge)

            tvText.text = tab.title

            val badgeVal = tab.badgeCount
            if (badgeVal != null && badgeVal > 0) {
                tvBadge.text = badgeVal.toString()
                tvBadge.visibility = View.VISIBLE
            } else {
                tvBadge.visibility = View.GONE
            }

            val isSelected = index == selectedIndex
            updateTabStyle(tabView, tvText, tvBadge, isSelected)

            tabView.setOnClickListener {
                selectTab(index)
            }

            container.addView(tabView)
        }
    }

    private fun updateTabStyle(tabView: View, tvText: TextView, tvBadge: TextView, isSelected: Boolean) {
        if (isSelected) {
            tabView.setBackgroundResource(R.drawable.bg_tab_active_blue_gradient)
            tvText.setTextColor(Color.WHITE)
            tvText.typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.inter_semibold)

            tvBadge.setBackgroundResource(R.drawable.bg_tab_badge_white_circle)
            tvBadge.setTextColor(Color.parseColor("#0B61CA"))
        } else {
            tabView.setBackgroundResource(R.drawable.bg_tab_inactive_gray)
            tvText.setTextColor(Color.parseColor("#475467"))
            tvText.typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.inter_medium)

            tvBadge.setBackgroundResource(R.drawable.bg_tab_badge_blue_circle)
            tvBadge.setTextColor(Color.WHITE)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
