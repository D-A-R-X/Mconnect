package com.manjugroups.m_connect.ui.common

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.manjugroups.m_connect.R
import java.util.Locale

data class SearchableOption<T>(
    val item: T,
    val title: String,
    val subtitle: String? = null,
    val keywords: String = ""
)

object SearchableSelectionDialog {
    fun <T> show(
        context: Context,
        title: String,
        options: List<SearchableOption<T>>,
        emptyMessage: String = "No matching records",
        onSelected: (T) -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val empty = TextView(context).apply {
            text = emptyMessage
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(context, 16), dp(context, 34), dp(context, 16), dp(context, 34))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.parseColor("#667085"))
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            addView(
                listContainer,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val search = EditText(context).apply {
            hint = "Search..."
            isSingleLine = true
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(context, 16), dp(context, 11), dp(context, 16), dp(context, 11))
            setBackgroundResource(R.drawable.bg_chip_inactive)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 14), dp(context, 20), dp(context, 20))
            setBackgroundColor(Color.WHITE)
            // Steal initial focus so the search field doesn't auto-open the
            // keyboard — it appears only when the user taps the field.
            isFocusableInTouchMode = true
            addView(View(context).apply {
                setBackgroundColor(Color.parseColor("#E4E7EC"))
            }, LinearLayout.LayoutParams(dp(context, 44), dp(context, 4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(context, 18)
            })
            addView(TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(Color.parseColor("#101828"))
                typeface = ResourcesCompat.getFont(context, R.font.inter_bold)
            })
            addView(search, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(context, 14)
                bottomMargin = dp(context, 12)
            })
            addView(empty, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 460)
            ))
        }

        fun renderRows(query: String) {
            val q = query.trim().lowercase(Locale.US)
            val filtered =
                if (q.isEmpty()) options
                else options.filter { option ->
                    listOf(option.title, option.subtitle.orEmpty(), option.keywords)
                        .joinToString(" ")
                        .lowercase(Locale.US)
                        .contains(q)
                }

            listContainer.removeAllViews()
            empty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            scroll.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE

            filtered.forEachIndexed { index, option ->
                listContainer.addView(makeRow(context, option) {
                    onSelected(option.item)
                    dialog.dismiss()
                })
                if (index != filtered.lastIndex) {
                    listContainer.addView(View(context).apply {
                        setBackgroundColor(Color.parseColor("#EAECF0"))
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1
                    ))
                }
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderRows(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        renderRows("")
        dialog.setContentView(content)
        dialog.setOnShowListener {
            // Keep the keyboard hidden on open; the list is what matters, and
            // the sheet resizes only when the user chooses to type.
            dialog.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            )
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
            content.requestFocus()
        }
        dialog.show()
    }

    private fun <T> makeRow(
        context: Context,
        option: SearchableOption<T>,
        onClick: () -> Unit
    ): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(context, 4), dp(context, 12), dp(context, 4), dp(context, 12))
            setOnClickListener { onClick() }
            addView(TextView(context).apply {
                text = option.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(Color.parseColor("#101828"))
                typeface = ResourcesCompat.getFont(context, R.font.inter_semibold)
            })
            option.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                addView(TextView(context).apply {
                    text = subtitle
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(Color.parseColor("#667085"))
                    setPadding(0, dp(context, 3), 0, 0)
                })
            }
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
