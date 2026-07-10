package com.manjugroups.m_connect.ui.dashboard

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment

/** Small shared builders for the dashboard drill-down list screens so the
 *  Calls / Registrations fragments stay consistent and light on XML. */
object DashboardListUi {

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    /** White header bar with a back arrow (pops the back stack) + a title. */
    fun header(fragment: Fragment, title: String): View {
        val ctx = fragment.requireContext()
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(ctx, 12), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        bar.addView(TextView(ctx).apply {
            text = "←"
            textSize = 22f
            setTextColor(Color.parseColor("#101828"))
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 4), 0, dp(ctx, 12), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { fragment.parentFragmentManager.popBackStack() }
        })
        bar.addView(TextView(ctx).apply {
            text = title
            textSize = 18f
            setTextColor(Color.parseColor("#101828"))
            typeface = Typeface.DEFAULT_BOLD
        })
        return bar
    }

    /** Centered empty-state (emoji + line). Caller positions it in a FrameLayout. */
    fun emptyState(ctx: Context, emoji: String, text: String): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
            )
            addView(TextView(ctx).apply { this.text = emoji; textSize = 30f; gravity = Gravity.CENTER })
            addView(TextView(ctx).apply {
                this.text = text; textSize = 14f
                setTextColor(Color.parseColor("#98A2B3")); gravity = Gravity.CENTER
                setPadding(0, dp(ctx, 8), 0, 0)
            })
        }
    }

    /** A list-row card: child(0) = title TextView, child(1) = subtitle TextView. */
    fun rowCard(ctx: Context): LinearLayout {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(com.manjugroups.m_connect.R.drawable.bg_input)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(
                androidx.recyclerview.widget.RecyclerView.LayoutParams.MATCH_PARENT,
                androidx.recyclerview.widget.RecyclerView.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(ctx, 8) }
        }
        card.addView(TextView(ctx).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#101828"))
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        card.addView(TextView(ctx).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#667085"))
            setPadding(0, dp(ctx, 4), 0, 0)
        })
        return card
    }
}
