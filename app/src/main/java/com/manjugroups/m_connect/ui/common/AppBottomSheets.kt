package com.manjugroups.m_connect.ui.common

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.manjugroups.m_connect.R

/**
 * Reusable app-styled bottom sheets (white rounded card, Inter type, blue
 * primary / red destructive) so feature screens stop reaching for stock
 * AlertDialogs that render with the platform's purple Material buttons.
 */
object AppBottomSheets {

    data class Option(
        val label: String,
        val iconRes: Int? = null,
        val destructive: Boolean = false,
        val onTap: () -> Unit,
    )

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    private fun font(ctx: Context, res: Int, fallback: Typeface): Typeface =
        runCatching { ResourcesCompat.getFont(ctx, res) }.getOrNull() ?: fallback

    private fun sheetRoot(ctx: Context, title: String?): Pair<BottomSheetDialog, LinearLayout> {
        val dialog = BottomSheetDialog(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadii = floatArrayOf(
                    dp(ctx, 22).toFloat(), dp(ctx, 22).toFloat(),
                    dp(ctx, 22).toFloat(), dp(ctx, 22).toFloat(),
                    0f, 0f, 0f, 0f,
                )
            }
            setPadding(dp(ctx, 20), dp(ctx, 10), dp(ctx, 20), dp(ctx, 20))
        }
        // Grab handle.
        root.addView(View(ctx).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E4E7EC"))
                cornerRadius = dp(ctx, 3).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(ctx, 12)
            }
        })
        if (!title.isNullOrBlank()) {
            root.addView(TextView(ctx).apply {
                text = title
                textSize = 17f
                setTextColor(Color.parseColor("#101828"))
                typeface = font(ctx, R.font.inter_bold, Typeface.DEFAULT_BOLD)
                setPadding(0, 0, 0, dp(ctx, 10))
            })
        }
        dialog.setContentView(root)
        (root.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
        return dialog to root
    }

    /** Action list (member actions, row menus). Destructive rows render red. */
    fun showOptions(ctx: Context, title: String?, options: List<Option>) {
        val (dialog, root) = sheetRoot(ctx, title)
        options.forEach { option ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 4), dp(ctx, 14), dp(ctx, 4), dp(ctx, 14))
                isClickable = true
                isFocusable = true
                val attrs = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                foreground = attrs.getDrawable(0)
                attrs.recycle()
                setOnClickListener {
                    dialog.dismiss()
                    option.onTap()
                }
            }
            val color = if (option.destructive) "#D92D20" else "#101828"
            option.iconRes?.let { icon ->
                row.addView(ImageView(ctx).apply {
                    setImageResource(icon)
                    setColorFilter(Color.parseColor(if (option.destructive) "#D92D20" else "#0B61CA"))
                    layoutParams = LinearLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20)).apply {
                        marginEnd = dp(ctx, 14)
                    }
                })
            }
            row.addView(TextView(ctx).apply {
                text = option.label
                textSize = 15f
                setTextColor(Color.parseColor(color))
                typeface = font(ctx, R.font.inter_semibold, Typeface.DEFAULT)
            })
            root.addView(row)
        }
        dialog.show()
    }

    /** Confirmation with a destructive-or-primary action button. */
    fun showConfirm(
        ctx: Context,
        title: String,
        message: String,
        confirmLabel: String,
        destructive: Boolean = false,
        onConfirm: () -> Unit,
    ) {
        val (dialog, root) = sheetRoot(ctx, title)
        root.addView(TextView(ctx).apply {
            text = message
            textSize = 14f
            setTextColor(Color.parseColor("#475467"))
            typeface = font(ctx, R.font.inter_medium, Typeface.DEFAULT)
            setPadding(0, 0, 0, dp(ctx, 18))
        })
        root.addView(actionButton(ctx, confirmLabel, if (destructive) "#D92D20" else "#0B61CA") {
            dialog.dismiss()
            onConfirm()
        })
        root.addView(cancelButton(ctx) { dialog.dismiss() })
        dialog.show()
    }

    /** One- or two-field text editor (e.g. group name + description). */
    fun showTextInput(
        ctx: Context,
        title: String,
        fields: List<Pair<String, String?>>, // hint to initial value
        saveLabel: String = "Save",
        multilineLast: Boolean = true,
        onSave: (List<String>) -> Unit,
    ) {
        val (dialog, root) = sheetRoot(ctx, title)
        val inputs = fields.mapIndexed { index, (hint, initial) ->
            EditText(ctx).apply {
                this.hint = hint
                setText(initial.orEmpty())
                textSize = 15f
                setTextColor(Color.parseColor("#101828"))
                setHintTextColor(Color.parseColor("#98A2B3"))
                typeface = font(ctx, R.font.inter_medium, Typeface.DEFAULT)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F8F9FB"))
                    cornerRadius = dp(ctx, 12).toFloat()
                    setStroke(dp(ctx, 1), Color.parseColor("#E4E7EC"))
                }
                setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
                inputType = if (multilineLast && index == fields.lastIndex && fields.size > 1) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(ctx, 10) }
            }.also(root::addView)
        }
        root.addView(actionButton(ctx, saveLabel, "#0B61CA") {
            dialog.dismiss()
            onSave(inputs.map { it.text.toString().trim() })
        })
        root.addView(cancelButton(ctx) { dialog.dismiss() })
        dialog.show()
    }

    private fun actionButton(ctx: Context, label: String, colorHex: String, onTap: () -> Unit) =
        TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = font(ctx, R.font.inter_semibold, Typeface.DEFAULT_BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(colorHex))
                cornerRadius = dp(ctx, 14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 48),
            ).apply { topMargin = dp(ctx, 4) }
            setOnClickListener { onTap() }
        }

    private fun cancelButton(ctx: Context, onTap: () -> Unit) =
        TextView(ctx).apply {
            text = "Cancel"
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(Color.parseColor("#475467"))
            typeface = font(ctx, R.font.inter_semibold, Typeface.DEFAULT)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 46),
            ).apply { topMargin = dp(ctx, 6) }
            setOnClickListener { onTap() }
        }
}
