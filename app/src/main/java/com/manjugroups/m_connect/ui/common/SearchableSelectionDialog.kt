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
        // When provided, a "create new" action appears under the search field.
        // It receives the current search text (e.g. the name the user typed but
        // couldn't find) so the create form can pre-fill it.
        onCreateNew: ((String) -> Unit)? = null,
        createLabel: String = "Add",
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
        // Optional "create new" action row (accent), shown only when onCreateNew
        // is supplied. Its label reflects the current query so the user sees
        // exactly what will be created.
        val createRow = if (onCreateNew != null) TextView(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.parseColor("#0B61CA"))
            typeface = ResourcesCompat.getFont(context, R.font.inter_semibold)
            setBackgroundResource(R.drawable.bg_chip_inactive)
            isClickable = true
            isFocusable = true
        } else null
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
            createRow?.let {
                addView(it, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(context, 12) })
            }
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

            // The "create new" action only appears once the user has typed a
            // name that ISN'T already an option — i.e. the thing they're
            // searching for doesn't exist yet. Blank query or an exact match
            // hides it.
            val hasExactMatch = options.any { it.title.trim().equals(query.trim(), ignoreCase = true) }
            val showCreate = createRow != null && q.isNotBlank() && !hasExactMatch
            createRow?.let {
                it.visibility = if (showCreate) View.VISIBLE else View.GONE
                it.text = "$createLabel \"${query.trim()}\"  +"
            }

            listContainer.removeAllViews()
            // Empty copy shows only when there's nothing to pick AND nothing to
            // create — otherwise the Add action leads.
            empty.visibility =
                if (filtered.isEmpty() && !showCreate) View.VISIBLE else View.GONE
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

        createRow?.setOnClickListener {
            dialog.dismiss()
            onCreateNew?.invoke(search.text?.toString()?.trim().orEmpty())
        }

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
