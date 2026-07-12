package com.manjugroups.m_connect.ui.common

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.manjugroups.m_connect.R

/**
 * Reusable horizontally-scrollable "filter tabs" pill row.
 *
 * A single-select row of rounded pills (e.g. All / Labour / Materials /
 * Equipments / Others) that scrolls when the labels overflow the width.
 * The selected pill paints with the brand-blue [selectedBgRes] + light
 * text; the rest use the muted [unselectedBgRes] + dark text. On select
 * it smoothly scrolls the chosen pill into view.
 *
 * This is intentionally UI-only and state-light: give it labels and a
 * callback, and it reports the tapped index. Unlike [SegmentedControlView]
 * (equal-weight fixed toggle) this is meant for variable-width, possibly
 * overflowing category filters.
 *
 * Usage:
 *   tabs.setTabs(listOf("All", "Labour", "Materials")) { index -> ... }
 */
class FilterTabsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val pills = mutableListOf<TextView>()
    private var selectedIndex = -1
    private var listener: ((Int) -> Unit)? = null

    // ── Style knobs (sensible brand defaults, overridable) ──
    var selectedBgRes: Int = R.drawable.bg_filter_tab_selected
    var unselectedBgRes: Int = R.drawable.bg_filter_tab_unselected
    var textColorSelected: Int = 0xFFFFFFFF.toInt()
    var textColorUnselected: Int = 0xFF475467.toInt()
    private val font = runCatching {
        ResourcesCompat.getFont(context, R.font.inter_semibold)
    }.getOrNull()

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        clipToPadding = false
        addView(
            row,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
        )
    }

    /**
     * Populate the row. [initialIndex] is selected without firing the
     * callback; user taps do fire it. Re-callable to swap the label set.
     */
    fun setTabs(
        labels: List<String>,
        initialIndex: Int = 0,
        onSelected: (Int) -> Unit,
    ) {
        listener = onSelected
        pills.clear()
        row.removeAllViews()
        selectedIndex = -1

        val padH = dp(18f)
        val padV = dp(9f)
        val gap = dp(8f)

        labels.forEachIndexed { index, label ->
            val pill = TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setPadding(padH, padV, padH, padV)
                font?.let { typeface = it }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (selectedIndex != index) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        selectTab(index)
                        listener?.invoke(index)
                    }
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { if (index > 0) marginStart = gap }
            row.addView(pill, lp)
            pills.add(pill)
        }

        if (labels.isNotEmpty()) {
            selectTab(initialIndex.coerceIn(0, labels.size - 1), scrollIntoView = false)
        }
    }

    /** Programmatically select [index] without firing the callback. */
    fun selectTab(index: Int, scrollIntoView: Boolean = true) {
        if (index < 0 || index >= pills.size || index == selectedIndex) {
            if (index == selectedIndex) return
        }
        if (index < 0 || index >= pills.size) return
        selectedIndex = index
        pills.forEachIndexed { i, pill ->
            val active = i == index
            pill.setBackgroundResource(if (active) selectedBgRes else unselectedBgRes)
            pill.setTextColor(if (active) textColorSelected else textColorUnselected)
        }
        if (scrollIntoView) scrollPillIntoView(index)
    }

    fun getSelectedIndex(): Int = selectedIndex

    private fun scrollPillIntoView(index: Int) {
        val pill = pills.getOrNull(index) ?: return
        post {
            val target = pill.left - (width - pill.width) / 2
            smoothScrollTo(target.coerceAtLeast(0), 0)
        }
    }

    private fun dp(v: Float): Int =
        (v * resources.displayMetrics.density).toInt()
}
