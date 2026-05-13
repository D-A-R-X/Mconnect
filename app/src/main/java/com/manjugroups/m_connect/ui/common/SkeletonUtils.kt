package com.manjugroups.m_connect.ui.common

import android.animation.ObjectAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import java.util.WeakHashMap

object SkeletonUtils {

    private const val DURATION_MS = 820L
    private val activeSkeletons = WeakHashMap<View, ObjectAnimator>()

    fun startSkeletonPulse(skeletonContainer: View): ObjectAnimator {
        skeletonContainer.clearAnimation()
        skeletonContainer.animate().cancel()
        skeletonContainer.alpha = 1f
        skeletonContainer.visibility = View.VISIBLE
        val animator = ObjectAnimator.ofFloat(skeletonContainer, View.ALPHA, 0.55f, 1f).apply {
            duration = DURATION_MS
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }
        activeSkeletons[skeletonContainer] = animator
        return animator
    }

    fun stopSkeletonPulse(skeletonContainer: View) {
        activeSkeletons.remove(skeletonContainer)
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
