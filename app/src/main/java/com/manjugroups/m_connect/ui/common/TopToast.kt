package com.manjugroups.m_connect.ui.common

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.manjugroups.m_connect.R

/**
 * Custom top-of-screen toast. Android 11+ silently ignores
 * `Toast.setGravity()` for text toasts and pins them to the bottom,
 * which broke our "Move away from home to clock in" instruction. This
 * renders a small dark pill view ourselves at the top of the activity
 * window so positioning is honoured on every Android version.
 *
 * - Dismisses any previous instance before showing the new one (no
 *   stacking if the user taps repeatedly).
 * - Auto-dismisses after [durationMs].
 * - Slides + fades in/out so it doesn't snap.
 */
object TopToast {

    private const val DEFAULT_DURATION_MS = 2_000L
    private var currentView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { dismiss() }

    fun show(activity: Activity, message: String, durationMs: Long = DEFAULT_DURATION_MS) {
        activity.runOnUiThread {
            dismiss()
            val root = activity.window?.decorView as? ViewGroup ?: return@runOnUiThread
            val density = activity.resources.displayMetrics.density
            val toast = TextView(activity).apply {
                text = message
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
                typeface = androidx.core.content.res.ResourcesCompat
                    .getFont(activity, R.font.inter_medium) ?: typeface
                setBackgroundResource(R.drawable.bg_top_toast)
                val padH = (16 * density).toInt()
                val padV = (10 * density).toInt()
                setPadding(padH, padV, padH, padV)
                alpha = 0f
                translationY = -16f * density
            }
            val statusBarInset = root.rootWindowInsets?.systemWindowInsetTop ?: 0
            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = statusBarInset + (16 * density).toInt()
                leftMargin = (24 * density).toInt()
                rightMargin = (24 * density).toInt()
            }
            root.addView(toast, params)
            currentView = toast

            toast.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180L)
                .start()

            mainHandler.removeCallbacks(dismissRunnable)
            mainHandler.postDelayed(dismissRunnable, durationMs)
        }
    }

    fun dismiss() {
        mainHandler.removeCallbacks(dismissRunnable)
        val view = currentView ?: return
        currentView = null
        view.animate()
            .alpha(0f)
            .translationY(-16f * view.resources.displayMetrics.density)
            .setDuration(160L)
            .withEndAction {
                (view.parent as? ViewGroup)?.removeView(view)
            }
            .start()
    }
}
