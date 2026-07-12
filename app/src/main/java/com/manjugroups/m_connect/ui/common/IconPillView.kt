package com.manjugroups.m_connect.ui.common

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.manjugroups.m_connect.R

/**
 * Small rounded metadata pill: an optional leading vector icon + a text
 * label. Reusable wherever a card needs to show a compact stat/attribute
 * (counts, hours, weather, status) — replaces the ad-hoc emoji-prefixed
 * text pills that were scattered around.
 *
 * Usage:
 *   IconPillView(ctx).bind(R.drawable.ic_clock, "8 hrs")
 *   IconPillView(ctx).bind(0, "Good")            // text-only pill
 */
class IconPillView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val icon = ImageView(context)
    private val label = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(9), dp(4), dp(10), dp(5))
        background = GradientDrawable().apply {
            cornerRadius = dp(9).toFloat()
            setColor(Color.parseColor("#F2F4F7"))
        }
        val s = dp(13)
        icon.layoutParams = LayoutParams(s, s).apply { marginEnd = dp(5) }
        addView(icon)
        label.setTextColor(Color.parseColor("#475467"))
        label.textSize = 11f
        label.includeFontPadding = false
        runCatching { label.typeface = ResourcesCompat.getFont(context, R.font.inter_medium) }
        addView(label)
    }

    /**
     * @param iconRes drawable to show; pass 0 for a text-only pill.
     * @param iconTint tint colour for the icon, or null to keep the drawable's
     *                 own colours (e.g. a multi-colour weather glyph).
     */
    fun bind(iconRes: Int, text: String, iconTint: Int? = 0xFF667085.toInt()): IconPillView {
        if (iconRes != 0) {
            icon.visibility = View.VISIBLE
            icon.setImageResource(iconRes)
            if (iconTint != null) icon.setColorFilter(iconTint) else icon.clearColorFilter()
        } else {
            icon.visibility = View.GONE
        }
        label.text = text
        return this
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
