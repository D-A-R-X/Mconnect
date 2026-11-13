package com.manjugroups.m_connect.ui.common

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.manjugroups.m_connect.R
import java.lang.ref.WeakReference
import kotlin.math.PI
import kotlin.math.cos

/**
 * The one button loading state for the whole app: a small spinner next to an
 * in-progress label ("Signing in", "Saving"), in the button's own text colour.
 *
 *   ButtonLoader.start(binding.btnSubmit, "Saving")
 *   ...
 *   ButtonLoader.stop(binding.btnSubmit)
 *
 * Works on a MaterialButton / Button / TextView, and on a custom button
 * container (FrameLayout or LinearLayout) whose label is its first TextView.
 * While loading, the button keeps its colour and width and ignores taps;
 * stop() puts back exactly what it showed before (text, icon, width,
 * clickability). Calling start() twice or stop() without start() is safe.
 */
object ButtonLoader {

    private class State(
        val label: TextView,
        val text: CharSequence?,
        val startDrawable: Drawable?,
        val padding: Int,
        val minWidth: Int,
        val clickable: Boolean,
        val enabled: Boolean,
        val materialIcon: Drawable?,
        val materialIconGravity: Int,
        val materialIconPadding: Int,
        val spinner: SpinnerDrawable,
    )

    fun isLoading(button: View): Boolean = button.getTag(R.id.button_loader_state) is State

    fun start(button: View, label: CharSequence) {
        if (isLoading(button)) {
            labelOf(button)?.text = label
            return
        }
        val textView = labelOf(button) ?: return
        val spinner = SpinnerDrawable(
            host = textView,
            color = textView.currentTextColor,
            sizePx = dp(textView, 18),
            strokePx = dp(textView, 2).toFloat(),
        )
        val material = textView as? MaterialButton
        val state = State(
            label = textView,
            text = textView.text,
            startDrawable = textView.compoundDrawablesRelative[0],
            padding = textView.compoundDrawablePadding,
            minWidth = button.minimumWidth,
            clickable = button.isClickable,
            enabled = button.isEnabled,
            materialIcon = material?.icon,
            materialIconGravity = material?.iconGravity ?: 0,
            materialIconPadding = material?.iconPadding ?: 0,
            spinner = spinner,
        )
        button.setTag(R.id.button_loader_state, state)

        // Keep the width so the button doesn't jump when the label changes.
        if (button.width > 0) button.minimumWidth = button.width
        val gap = dp(button, 8)
        if (material != null) {
            material.icon = spinner
            material.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            material.iconPadding = gap
            material.iconTint = null
        } else {
            spinner.setBounds(0, 0, spinner.intrinsicWidth, spinner.intrinsicHeight)
            val d = textView.compoundDrawablesRelative
            textView.setCompoundDrawablesRelative(spinner, d[1], d[2], d[3])
            textView.compoundDrawablePadding = gap
        }
        textView.text = label
        spinner.start()
        // Taps are ignored without greying the button out (a disabled
        // MaterialButton loses its colour).
        button.isClickable = false
        button.isEnabled = true
        button.contentDescription = label
    }

    fun stop(button: View) {
        val state = button.getTag(R.id.button_loader_state) as? State ?: return
        button.setTag(R.id.button_loader_state, null)
        state.spinner.stop()
        val textView = state.label
        val material = textView as? MaterialButton
        if (material != null) {
            material.icon = state.materialIcon
            material.iconGravity = state.materialIconGravity
            material.iconPadding = state.materialIconPadding
        } else {
            val d = textView.compoundDrawablesRelative
            textView.setCompoundDrawablesRelative(state.startDrawable, d[1], d[2], d[3])
            textView.compoundDrawablePadding = state.padding
        }
        textView.text = state.text
        button.minimumWidth = state.minWidth
        button.isClickable = state.clickable
        button.isEnabled = state.enabled
        button.contentDescription = null
    }

    /** start(label) when [loading], else stop(). */
    fun set(button: View, loading: Boolean, label: CharSequence) {
        if (loading) start(button, label) else stop(button)
    }

    private fun labelOf(button: View): TextView? = when (button) {
        is TextView -> button
        is ViewGroup -> firstTextView(button)
        else -> null
    }

    private fun firstTextView(group: ViewGroup): TextView? {
        for (i in 0 until group.childCount) {
            when (val child = group.getChildAt(i)) {
                is TextView -> if (child.visibility == View.VISIBLE) return child
                is ViewGroup -> firstTextView(child)?.let { return it }
            }
        }
        return null
    }

    private fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density).toInt()

    /**
     * A rotating arc that grows and shrinks. Self-contained: it drives its own
     * frames and invalidates the host view directly, so it animates the same
     * whether it sits in a MaterialButton icon slot or a compound drawable.
     * (Material's IndeterminateDrawable stayed frozen as a dot as a button icon.)
     */
    private class SpinnerDrawable(
        host: View,
        color: Int,
        private val sizePx: Int,
        strokePx: Float,
    ) : Drawable(), Animatable {

        private val host = WeakReference(host)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            strokeCap = Paint.Cap.ROUND
            this.color = color
        }
        private val oval = RectF()
        private var startedAt = SystemClock.uptimeMillis()
        private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                invalidateSelf()
                this@SpinnerDrawable.host.get()?.invalidate()
            }
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val inset = paint.strokeWidth / 2f
            oval.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
            val t = (SystemClock.uptimeMillis() - startedAt).toFloat()
            // One full turn every 0.9 s; the arc breathes between 40° and 270°
            // every 1.4 s so the motion reads as "working", not a static ring.
            val rotation = (t / 900f * 360f) % 360f
            val phase = (t % 1_400f) / 1_400f
            val sweep = 40f + 230f * (0.5f - 0.5f * cos(2.0 * PI * phase).toFloat())
            canvas.drawArc(oval, rotation - 90f, sweep, false, paint)
        }

        override fun start() {
            if (ticker.isRunning) return
            startedAt = SystemClock.uptimeMillis()
            ticker.start()
        }

        override fun stop() = ticker.cancel()
        override fun isRunning(): Boolean = ticker.isRunning
        override fun getIntrinsicWidth(): Int = sizePx
        override fun getIntrinsicHeight(): Int = sizePx

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
