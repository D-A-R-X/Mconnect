package com.manjugroups.m_connect.ui.common

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.manjugroups.m_connect.R

/**
 * Reusable, centre-floating outcome picker. Shows one filled-pill button per
 * enabled outcome (icon + label, single accent colour — same shape as the SV
 * outcome buttons, no per-outcome colours). Disabled outcomes are simply not
 * passed in, so they never appear. Purely a selector: it returns the chosen
 * key and touches no business logic. Cancelable — tapping outside / Back
 * dismisses without a selection so the user can re-open and pick again.
 */
object OutcomeSelectionDialog {

    /** One selectable outcome. [key] is returned verbatim on tap. */
    data class Option(val key: String, val label: String, val iconRes: Int)

    fun show(
        context: Context,
        title: String,
        subtitle: String? = null,
        options: List<Option>,
        onSelect: (String) -> Unit,
        onCancel: (() -> Unit)? = null,
    ): AlertDialog {
        val dp = { v: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics,
            ).toInt()
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(18))
            background = ContextCompat.getDrawable(context, R.drawable.bg_outcome_dialog)
        }

        container.addView(TextView(context).apply {
            text = title
            setTextColor(Color.parseColor("#101828"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(Typeface.DEFAULT_BOLD)
        })
        if (!subtitle.isNullOrBlank()) {
            container.addView(TextView(context).apply {
                text = subtitle
                setTextColor(Color.parseColor("#667085"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                (layoutParams as? ViewGroup.MarginLayoutParams) // no-op guard
                setPadding(0, dp(4), 0, 0)
            })
        }

        val dialog = AlertDialog.Builder(context)
            .setView(container)
            .setCancelable(true)
            .create()

        options.forEach { option ->
            // A horizontal row (icon + label) centred as a GROUP — compound
            // drawables pin to the view edge and leave the text visually
            // off-centre, so we lay the icon and text out side by side and
            // centre the whole row instead.
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52),
                ).apply { topMargin = dp(14) }
                background = ContextCompat.getDrawable(context, R.drawable.bg_outcome_choice_pill)
                isClickable = true
                isFocusable = true
                setPadding(dp(16), 0, dp(16), 0)
                setOnClickListener {
                    dialog.dismiss()
                    onSelect(option.key)
                }
            }
            row.addView(android.widget.ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                    marginEnd = dp(10)
                }
                setImageDrawable(
                    ContextCompat.getDrawable(context, option.iconRes)?.mutate(),
                )
                setColorFilter(Color.WHITE)
            })
            row.addView(TextView(context).apply {
                text = option.label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(Typeface.DEFAULT_BOLD)
                includeFontPadding = false
            })
            container.addView(row)
        }

        if (onCancel != null) {
            dialog.setOnCancelListener { onCancel() }
        }
        dialog.setOnShowListener {
            // Float in the middle of the screen with a bounded width, and let the
            // rounded card show (transparent window so no square frame behind it).
            dialog.window?.let { w ->
                w.setBackgroundDrawable(
                    android.graphics.drawable.ColorDrawable(Color.TRANSPARENT),
                )
                val width = (context.resources.displayMetrics.widthPixels * 0.86f).toInt()
                w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
                w.setGravity(Gravity.CENTER)
                // Pop in/out from the centre (scale + fade) instead of the
                // default dialog slide, so it grows from the middle of screen.
                w.setWindowAnimations(R.style.DialogZoomAnimation)
            }
        }
        dialog.show()
        return dialog
    }
}
