package com.manjugroups.m_connect.ui.common

import android.content.Context
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.manjugroups.m_connect.R

/**
 * Centralised setup for the pull-to-refresh spinner. Every refreshable
 * screen in the app should call this so the spinner colour, background
 * and trigger distance read consistently — and so that a future tweak
 * (e.g. swapping the brand colour) lives in one place.
 *
 * Usage:
 * ```
 * binding.swipeRefresh.setupPullToRefresh { reloadEverything() }
 * // ...later, when loading completes:
 * binding.swipeRefresh.isRefreshing = false
 * ```
 *
 * The `onRefresh` lambda should kick off the same load path the screen
 * uses on first open. The caller is responsible for clearing
 * `isRefreshing` once data has actually landed — that way the spinner
 * stays visible the entire time the user is waiting and disappears the
 * moment the new content paints, instead of vanishing as soon as the
 * gesture lifts.
 */
fun SwipeRefreshLayout.setupPullToRefresh(onRefresh: () -> Unit) {
    val ctx = context
    // Brand colours — the spinner cycles through these to give a
    // recognisable "Mconnect" loader instead of the default material grey.
    setColorSchemeColors(
        ContextCompat.getColor(ctx, R.color.category_labour),     // brand blue
        ContextCompat.getColor(ctx, R.color.category_equipment), // brand green
        ContextCompat.getColor(ctx, R.color.category_materials), // accent orange
    )
    setProgressBackgroundColorSchemeColor(Color.WHITE)
    // Pull distance — defaults are tuned for tablets and feel sluggish
    // on phones. Trigger at ~96dp so the gesture lands quickly.
    setDistanceToTriggerSync(dpToPx(ctx, 96))
    setOnRefreshListener { onRefresh() }
    // Push the spinner DOWN by the status-bar inset so it doesn't appear
    // behind the system clock / notch on full-bleed screens. Without this
    // the resting "end" position of the spinner overlaps the status bar
    // icons on Pixel/Galaxy notch devices.
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val topInset = insets.getInsets(
            androidx.core.view.WindowInsetsCompat.Type.statusBars()
        ).top
        val srl = v as SwipeRefreshLayout
        // Spinner size is ~40dp by default. `start` is the off-screen
        // resting position; `end` is where it lands while pulling.
        // Pushing both by the status-bar inset means the spinner crosses
        // the screen edge cleanly below the system icons and lands in
        // a comfortable spot 24dp into the content area.
        val spinnerSize = dpToPx(ctx, 40)
        val start = -spinnerSize + topInset
        val end = topInset + dpToPx(ctx, 24)
        srl.setProgressViewOffset(false, start, end)
        insets
    }
    androidx.core.view.ViewCompat.requestApplyInsets(this)
}

/**
 * Convenience for the common "trigger this load function, dismiss the
 * spinner when it returns" pattern. Wraps any suspend-style or callback
 * load by accepting a completion block the caller invokes when done.
 */
fun SwipeRefreshLayout.dismissRefresh() {
    if (isRefreshing) isRefreshing = false
}

private fun dpToPx(ctx: Context, dp: Int): Int =
    (dp * ctx.resources.displayMetrics.density).toInt()
