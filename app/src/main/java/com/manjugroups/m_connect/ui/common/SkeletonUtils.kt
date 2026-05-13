package com.manjugroups.m_connect.ui.common

import android.animation.ObjectAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation

object SkeletonUtils {

    private const val DURATION_MS = 820L

    fun startSkeletonPulse(skeletonContainer: View): ObjectAnimator {
        skeletonContainer.visibility = View.VISIBLE
        val animator = ObjectAnimator.ofFloat(skeletonContainer, View.ALPHA, 0.55f, 1f).apply {
            duration = DURATION_MS
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }
        skeletonContainer.tag = animator
        return animator
    }

    fun stopSkeletonPulse(skeletonContainer: View) {
        val animator = skeletonContainer.tag as? ObjectAnimator
        animator?.cancel()
        skeletonContainer.alpha = 1f
        skeletonContainer.visibility = View.GONE
        skeletonContainer.tag = null
    }

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
