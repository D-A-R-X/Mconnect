package com.manjugroups.m_connect.ui.common

import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Shared entry-animation helpers used across feature fragments so the whole app
 * has a consistent "content lifts into place" character.
 *
 * The Home tab fires a "curtain descent" (content slides DOWN from above).
 * Everywhere else uses the inverse — content rises FROM BELOW — to give Home
 * its signature feel while keeping sub-pages lively.
 */
object EntryAnimations {

    /**
     * Staggered slide-up + fade for a list of views (use for top-level cards).
     *
     * @param views Views to animate, in display order.
     * @param travelDp Distance each view starts below its resting position.
     * @param duration Duration of each individual animation.
     * @param stepDelay Delay between consecutive views in the cascade.
     * @param startDelay Delay before the first view starts animating.
     */
    fun staggerUp(
        views: List<View>,
        travelDp: Float = 28f,
        duration: Long = 380L,
        stepDelay: Long = 75L,
        startDelay: Long = 0L
    ) {
        if (views.isEmpty()) return
        val density = views.first().resources.displayMetrics.density
        val travel = travelDp * density
        views.forEachIndexed { index, view ->
            view.animate().cancel()
            view.alpha = 0f
            view.translationY = travel
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(startDelay + index * stepDelay)
                .setDuration(duration)
                .setInterpolator(DecelerateInterpolator(1.4f))
                .start()
        }
    }

    /**
     * Fade-only entry. Use for header / chrome that should not move.
     */
    fun fadeIn(view: View, duration: Long = 320L, startDelay: Long = 0L) {
        view.animate().cancel()
        view.alpha = 0f
        view.animate()
            .alpha(1f)
            .setStartDelay(startDelay)
            .setDuration(duration)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
}
