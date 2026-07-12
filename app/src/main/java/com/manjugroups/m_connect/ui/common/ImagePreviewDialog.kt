package com.manjugroups.m_connect.ui.common

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import coil.load
import com.manjugroups.m_connect.R

/**
 * Full-screen, in-app image preview. Shows [imageUrl] on a black backdrop
 * with pinch-to-zoom, pan, and double-tap-to-zoom, plus a close button and
 * tap-to-dismiss (when not zoomed). Use this instead of firing an external
 * `ACTION_VIEW` intent so image attachments preview inside the app.
 *
 * [imageUrl] may be a remote URL or a local `file://` / content Uri string —
 * Coil resolves all of them.
 */
object ImagePreviewDialog {

    fun show(context: Context, imageUrl: String) {
        val density = context.resources.displayMetrics.density
        fun px(dp: Float) = (dp * density).toInt()

        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            load(imageUrl)
        }
        root.addView(image)

        // ── Zoom / pan state (driven via view scale + translation) ──
        var scale = 1f
        var tx = 0f
        var ty = 0f
        var lastX = 0f
        var lastY = 0f
        fun apply() {
            image.scaleX = scale
            image.scaleY = scale
            image.translationX = tx
            image.translationY = ty
        }

        val scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    scale = (scale * d.scaleFactor).coerceIn(1f, 5f)
                    if (scale == 1f) { tx = 0f; ty = 0f }
                    apply()
                    return true
                }
            },
        )

        val tapDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    scale = if (scale > 1f) 1f else 2.5f
                    if (scale == 1f) { tx = 0f; ty = 0f }
                    apply()
                    return true
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    // Tap to dismiss only when not zoomed in.
                    if (scale == 1f) dialog.dismiss()
                    return true
                }
            },
        )

        image.setOnTouchListener { v, e ->
            scaleDetector.onTouchEvent(e)
            tapDetector.onTouchEvent(e)
            if (!scaleDetector.isInProgress && scale > 1f) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastX = e.rawX; lastY = e.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        tx += e.rawX - lastX
                        ty += e.rawY - lastY
                        lastX = e.rawX; lastY = e.rawY
                        apply()
                    }
                }
            }
            if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            true
        }

        // ── Close button (top-start) ──
        val btnSize = px(44f)
        val close = ImageView(context).apply {
            setImageResource(R.drawable.ic_sheet_close)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setBackgroundResource(R.drawable.bg_home_new_action_circle)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#59000000"))
            val p = px(10f)
            setPadding(p, p, p, p)
            isClickable = true
            isFocusable = true
            layoutParams = FrameLayout.LayoutParams(btnSize, btnSize).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = px(40f)
                marginStart = px(16f)
            }
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(close)

        dialog.setContentView(root)
        dialog.show()
    }
}
