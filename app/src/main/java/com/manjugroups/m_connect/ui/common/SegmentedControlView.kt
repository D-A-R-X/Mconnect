package com.manjugroups.m_connect.ui.common

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.manjugroups.m_connect.R

class SegmentedControlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var activeBackgroundResId = 0
    private var textColorSelected = Color.WHITE
    private var textColorUnselected = Color.DKGRAY
    private var textSizePx = 0f
    private var fontFamilyResId = 0
    private var typeface: Typeface? = null

    private var selectedIndex = -1
    private var onTabSelectedListener: ((Int) -> Unit)? = null
    private val tabTextViews = mutableListOf<TextView>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        val a = context.obtainStyledAttributes(attrs, R.styleable.SegmentedControlView, defStyleAttr, 0)
        activeBackgroundResId = a.getResourceId(R.styleable.SegmentedControlView_scv_active_background, 0)
        textColorSelected = a.getColor(R.styleable.SegmentedControlView_scv_text_color_selected, Color.WHITE)
        textColorUnselected = a.getColor(R.styleable.SegmentedControlView_scv_text_color_unselected, Color.DKGRAY)
        textSizePx = a.getDimension(R.styleable.SegmentedControlView_scv_text_size, 0f)
        fontFamilyResId = a.getResourceId(R.styleable.SegmentedControlView_scv_font_family, 0)
        a.recycle()

        if (fontFamilyResId != 0) {
            runCatching {
                typeface = ResourcesCompat.getFont(context, fontFamilyResId)
            }
        }
    }

    /**
     * Initializes the segmented control with a list of tab labels.
     * Optionally takes an initial index and executes the callback on tab changes.
     */
    fun setTabs(
        labels: List<String>,
        initialIndex: Int = 0,
        onTabSelected: (Int) -> Unit
    ) {
        this.onTabSelectedListener = onTabSelected
        this.tabTextViews.clear()
        removeAllViews()

        labels.forEachIndexed { index, label ->
            val textView = TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                includeFontPadding = false
                
                // Styling
                if (this@SegmentedControlView.textSizePx > 0f) {
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, this@SegmentedControlView.textSizePx)
                } else {
                    textSize = 12f
                }
                
                this@SegmentedControlView.typeface?.let {
                    setTypeface(it)
                }

                // Layout parameters
                val lp = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                layoutParams = lp

                setOnClickListener {
                    if (selectedIndex != index) {
                        selectTab(index)
                        onTabSelectedListener?.invoke(index)
                    }
                }
            }

            tabTextViews.add(textView)
            addView(textView)
        }

        if (labels.isNotEmpty()) {
            val safeIndex = initialIndex.coerceIn(0, labels.size - 1)
            selectTab(safeIndex)
        }
    }

    /**
     * Selects a tab programmatically without triggering the callback listener.
     */
    fun selectTab(index: Int) {
        if (index < 0 || index >= tabTextViews.size) return
        selectedIndex = index

        tabTextViews.forEachIndexed { i, textView ->
            val isSelected = (i == index)
            if (isSelected) {
                if (activeBackgroundResId != 0) {
                    textView.setBackgroundResource(activeBackgroundResId)
                } else {
                    textView.setBackgroundColor(Color.TRANSPARENT)
                }
                textView.setTextColor(textColorSelected)
            } else {
                textView.background = null
                textView.setTextColor(textColorUnselected)
            }
        }
    }

    fun updateTabLabels(labels: List<String>) {
        if (labels.size == tabTextViews.size) {
            labels.forEachIndexed { index, label ->
                tabTextViews[index].text = label
            }
        }
    }

    fun getSelectedIndex(): Int = selectedIndex
}
