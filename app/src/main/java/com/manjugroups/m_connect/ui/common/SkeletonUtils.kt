package com.manjugroups.m_connect.ui.common

import android.animation.ObjectAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import java.util.WeakHashMap

object SkeletonUtils {

    private const val DURATION_MS = 1000L
    private val activeSkeletons = WeakHashMap<View, ObjectAnimator>()

    /**
     * Starts a pulsing animation on the descendants of the given [skeletonContainer].
     * The container itself remains opaque to hide any content behind it.
     */
    fun startSkeletonPulse(skeletonContainer: View): ObjectAnimator {
        // Stop any previous animation on this container
        activeSkeletons[skeletonContainer]?.cancel()

        skeletonContainer.visibility = View.VISIBLE
        skeletonContainer.alpha = 1f // Keep container opaque

        // We pulse from 0.45 to 1.0 for a more distinct "loading" feel
        val animator = ObjectAnimator.ofFloat(skeletonContainer, View.ALPHA, 0.45f, 1f).apply {
            duration = DURATION_MS
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }

        activeSkeletons[skeletonContainer] = animator
        return animator
    }

    /**
     * Stays consistent with the start method but hides the container.
     */
    fun stopSkeletonPulse(skeletonContainer: View) {
        val animator = activeSkeletons.remove(skeletonContainer)
        animator?.cancel()
        
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
            view.clearAnimation()
            view.animate().cancel()
            view.alpha = 1f
            view.visibility = View.GONE
            iter.remove()
        }
    }

    @Deprecated("Use startSkeletonPulse instead")
    fun startSkeletonPulseLegacy(skeletonContainer: View): AlphaAnimation {
        skeletonContainer.visibility = View.VISIBLE
        val anim = AlphaAnimation(0.55f, 1f).apply {
            duration = DURATION_MS
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
        }
        forEachLeafBlock(skeletonContainer) { it.startAnimation(anim) }
        skeletonContainer.tag = anim
        return anim
    }

    @Deprecated("Use stopSkeletonPulse instead")
    fun stopSkeletonPulseLegacy(skeletonContainer: View) {
        val anim = skeletonContainer.tag as? AlphaAnimation
        if (anim != null) {
            forEachLeafBlock(skeletonContainer) { it.clearAnimation() }
        }
        skeletonContainer.visibility = View.GONE
        skeletonContainer.tag = null
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
