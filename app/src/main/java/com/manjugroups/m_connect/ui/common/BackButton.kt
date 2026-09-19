package com.manjugroups.m_connect.ui.common

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.FragmentManager
import com.manjugroups.m_connect.R

/**
 * The app's one back button: a 32 dp frosted-glass chip with the Attendance
 * History chevron (ic_back_blue).
 *
 * On a dark or coloured header (the blue summary headers) it is white glass
 * with a white chevron; on a light screen, white glass would vanish, so it
 * turns milky blue glass with the blue chevron. The variant is picked from the
 * colour actually behind the button once it is attached.
 *
 * Android views cannot blur what is behind them (there is no backdrop-blur
 * API), so the glass is built from a translucent gradient fill, a highlight
 * and a light rim rather than a live blur.
 *
 * Tapping it goes back by itself: the hosting fragment is popped with
 * [navigateUp] (or, outside a fragment, the activity's back dispatcher). A
 * screen that must do more on back sets its own click listener, which
 * replaces this.
 */
class BackButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val icon = ImageView(context).apply {
        // ic_back_blue is drawn on the same 32 dp canvas as the chip.
        setImageResource(R.drawable.ic_back_blue)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        setBackgroundResource(R.drawable.bg_back_glass_light)
        foreground = context.obtainStyledAttributes(
            intArrayOf(android.R.attr.selectableItemBackgroundBorderless),
        ).let { a -> a.getDrawable(0).also { a.recycle() } }
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.back_button_description)
        addView(icon, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        super.setOnClickListener { goBack() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyVariant(isOnDarkBackground())
    }

    private fun applyVariant(dark: Boolean) {
        if (dark) {
            setBackgroundResource(R.drawable.bg_back_glass_dark)
            icon.setColorFilter(Color.WHITE)
        } else {
            setBackgroundResource(R.drawable.bg_back_glass_light)
            icon.clearColorFilter()
        }
    }

    /** Colour of the nearest ancestor that paints one; the theme's if none. */
    private fun isOnDarkBackground(): Boolean {
        var v: View? = parent as? View
        while (v != null) {
            colorOf(v.background)?.let { return ColorUtils.calculateLuminance(it) < 0.5 }
            v = v.parent as? View
        }
        val tv = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.colorBackground, tv, true) &&
            tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT
        ) {
            return ColorUtils.calculateLuminance(tv.data) < 0.5
        }
        return false
    }

    /** A mostly opaque colour for [d], or null when it can't be read. */
    private fun colorOf(d: Drawable?): Int? = when (d) {
        null -> null
        is ColorDrawable -> d.color.takeIf { Color.alpha(it) > 0x80 }
        is GradientDrawable -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            (d.colors?.firstOrNull() ?: d.color?.defaultColor)?.takeIf { Color.alpha(it) > 0x80 }
        } else {
            null
        }
        is LayerDrawable -> (0 until d.numberOfLayers).firstNotNullOfOrNull { colorOf(d.getDrawable(it)) }
        else -> null
    }

    private fun goBack() {
        val fragment = runCatching { FragmentManager.findFragment<androidx.fragment.app.Fragment>(this) }.getOrNull()
        if (fragment != null) {
            fragment.navigateUp()
        } else {
            var c = context
            while (c is android.content.ContextWrapper && c !is ComponentActivity) c = c.baseContext
            (c as? ComponentActivity)?.onBackPressedDispatcher?.onBackPressed()
        }
    }
}
