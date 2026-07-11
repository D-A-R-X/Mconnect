package com.manjugroups.m_connect.ui.common

import android.animation.ObjectAnimator
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import com.google.android.material.card.MaterialCardView
import com.manjugroups.m_connect.R

/**
 * Reusable skeleton placeholder loader. Fills a container with grey card
 * placeholders and runs a soft pulse until real data replaces them — use it
 * anywhere a list/section loads data.
 *
 * Usage:
 *   val anim = SkeletonLoader.show(container, count = 3)   // before the fetch
 *   ...on result...
 *   anim.cancel(); container.alpha = 1f; container.removeAllViews()  // then render
 */
object SkeletonLoader {

    /**
     * Clear [container], fill it with [count] skeleton cards and start a pulse.
     * Returns the running [ObjectAnimator] — cancel it (and reset alpha) when
     * the real content is rendered.
     */
    fun show(container: ViewGroup, count: Int = 3, card: (Context) -> View = ::listCard): ObjectAnimator {
        val ctx = container.context
        container.removeAllViews()
        container.alpha = 1f
        repeat(count) { container.addView(card(ctx)) }
        return pulse(container)
    }

    fun pulse(view: View): ObjectAnimator =
        ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0.45f).apply {
            duration = 750
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

    private fun bar(ctx: Context, widthDp: Int, heightDp: Int, topMarginDp: Int = 0, startMarginDp: Int = 0): View =
        View(ctx).apply {
            setBackgroundResource(R.drawable.bg_skeleton)
            layoutParams = LinearLayout.LayoutParams(
                if (widthDp <= 0) LinearLayout.LayoutParams.MATCH_PARENT else dp(ctx, widthDp),
                dp(ctx, heightDp),
            ).apply { topMargin = dp(ctx, topMarginDp); marginStart = dp(ctx, startMarginDp) }
        }

    /** Default list-item skeleton: a bordered card with a few bars + chips. */
    fun listCard(ctx: Context): View {
        val cardView = MaterialCardView(ctx).apply {
            radius = dp(ctx, 16).toFloat()
            cardElevation = 0f
            strokeColor = 0xFFEAECF0.toInt()
            strokeWidth = dp(ctx, 1)
            setCardBackgroundColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(ctx, 10) }
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 14), dp(ctx, 16), dp(ctx, 14), dp(ctx, 16))
        }
        content.addView(bar(ctx, 120, 14))
        content.addView(bar(ctx, 0, 12, 12))
        content.addView(bar(ctx, 200, 12, 8))
        val pills = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        pills.addView(bar(ctx, 54, 20, 12))
        pills.addView(bar(ctx, 54, 20, 12, 8))
        content.addView(pills)
        cardView.addView(content)
        return cardView
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
