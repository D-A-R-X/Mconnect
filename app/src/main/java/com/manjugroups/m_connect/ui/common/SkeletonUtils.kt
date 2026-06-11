package com.manjugroups.m_connect.ui.common

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import java.util.WeakHashMap

/**
 * Drives the loading-skeleton shimmer.
 *
 * Design notes (why it's built this way):
 *  - The pulse animates the alpha of the *leaf placeholder blocks* (the
 *    grey bars/circles), NOT the container. Earlier this faded the whole
 *    container's alpha down to ~0.45, which (a) made any content sitting
 *    behind the overlay bleed through and (b) read as the entire screen
 *    "blinking". Keeping the container opaque and only breathing the
 *    blocks gives a clean, steady shimmer.
 *  - start is idempotent: calling it again on a container that's already
 *    shimmering is a no-op, so repeated load calls don't restart the
 *    animation mid-cycle (the visible stutter/"glitch").
 *  - stop fully resets: it cancels the animator, restores every block to
 *    full opacity, and hides the container so a recycled view never keeps
 *    a stale mid-pulse alpha.
 */
object SkeletonUtils {

    private const val DURATION_MS = 850L
    private const val MIN_ALPHA = 0.35f
    private const val MAX_ALPHA = 1f

    private val activeSkeletons = WeakHashMap<View, ValueAnimator>()

    /**
     * Starts (or keeps) the shimmer on [skeletonContainer]. The container
     * is made VISIBLE and opaque; its leaf blocks breathe between
     * [MIN_ALPHA] and [MAX_ALPHA].
     */
    fun startSkeletonPulse(skeletonContainer: View): ValueAnimator {
        // Idempotent — already shimmering, leave it running.
        activeSkeletons[skeletonContainer]?.let { return it }

        skeletonContainer.visibility = View.VISIBLE
        skeletonContainer.alpha = 1f

        val blocks = collectLeafBlocks(skeletonContainer)
        val animator = ValueAnimator.ofFloat(MIN_ALPHA, MAX_ALPHA).apply {
            duration = DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val value = anim.animatedValue as Float
                for (block in blocks) block.alpha = value
            }
            start()
        }

        activeSkeletons[skeletonContainer] = animator
        return animator
    }

    /**
     * Cancels the shimmer, restores block opacity, and hides the
     * container. Safe to call even if the container was never started.
     */
    fun stopSkeletonPulse(skeletonContainer: View) {
        activeSkeletons.remove(skeletonContainer)?.cancel()
        for (block in collectLeafBlocks(skeletonContainer)) block.alpha = 1f
        skeletonContainer.clearAnimation()
        skeletonContainer.animate().cancel()
        skeletonContainer.alpha = 1f
        skeletonContainer.visibility = View.GONE
    }

    fun stopAll() {
        val iter = activeSkeletons.entries.iterator()
        while (iter.hasNext()) {
            val (view, animator) = iter.next()
            animator.cancel()
            for (block in collectLeafBlocks(view)) block.alpha = 1f
            view.clearAnimation()
            view.animate().cancel()
            view.alpha = 1f
            view.visibility = View.GONE
            iter.remove()
        }
    }

    /** Collects every non-ViewGroup descendant (the placeholder bars). */
    private fun collectLeafBlocks(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) {
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            } else {
                out.add(v)
            }
        }
        walk(root)
        return out
    }

    fun forEachLeafBlock(root: View, action: (View) -> Unit) {
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                forEachLeafBlock(root.getChildAt(i), action)
            }
        } else {
            action(root)
        }
    }
}
