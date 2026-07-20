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
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.manjugroups.m_connect.R
import java.util.Locale

data class SearchableOption<T>(
    val item: T,
    val title: String,
    val subtitle: String? = null,
    val keywords: String = ""
) {
    /**
     * Lowercased title + subtitle + keywords, built once. The filter used to
     * allocate three strings per option on every keystroke.
     */
    internal val haystack: String by lazy(LazyThreadSafetyMode.NONE) {
        "$title ${subtitle.orEmpty()} $keywords".lowercase(Locale.US)
    }
}

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
        // RecyclerView, not a LinearLayout in a ScrollView. The previous version
        // inflated one view per option (plus a divider) on EVERY keystroke, so
        // a few hundred staff meant ~600 views rebuilt per character typed —
        // which is what made this dialog crawl and, on big lists, ANR.
        val adapter = RowAdapter(options) { item ->
            onSelected(item)
            dialog.dismiss()
        }
        val empty = TextView(context).apply {
            text = emptyMessage
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(context, 16), dp(context, 34), dp(context, 16), dp(context, 34))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.parseColor("#667085"))
        }
        val scroll = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(true)
            this.adapter = adapter
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
                else options.filter { it.haystack.contains(q) }

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

            // Empty copy shows only when there's nothing to pick AND nothing to
            // create — otherwise the Add action leads.
            empty.visibility =
                if (filtered.isEmpty() && !showCreate) View.VISIBLE else View.GONE
            scroll.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
            adapter.submit(filtered)
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

    /**
     * Recycling adapter for the option rows. Views are built in code (matching
     * the dialog's fully-programmatic style) but reused across binds, so
     * filtering costs a rebind per visible row rather than a full re-inflate.
     */
    private class RowAdapter<T>(
        initial: List<SearchableOption<T>>,
        private val onClick: (T) -> Unit,
    ) : RecyclerView.Adapter<RowAdapter<T>.VH>() {

        private var rows: List<SearchableOption<T>> = initial

        fun submit(next: List<SearchableOption<T>>) {
            rows = next
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val context = parent.context
            val title = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(Color.parseColor("#101828"))
                typeface = ResourcesCompat.getFont(context, R.font.inter_semibold)
            }
            val subtitle = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.parseColor("#667085"))
                setPadding(0, dp(context, 3), 0, 0)
            }
            val divider = View(context).apply {
                setBackgroundColor(Color.parseColor("#EAECF0"))
            }
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                isFocusable = true
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                )
                addView(title)
                addView(subtitle)
                addView(
                    divider,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1),
                )
            }
            return VH(root, title, subtitle, divider)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val option = rows[position]
            holder.title.text = option.title
            val sub = option.subtitle?.takeIf { it.isNotBlank() }
            holder.subtitle.text = sub.orEmpty()
            holder.subtitle.visibility = if (sub != null) View.VISIBLE else View.GONE
            holder.divider.visibility =
                if (position == rows.lastIndex) View.GONE else View.VISIBLE
            holder.itemView.setOnClickListener { onClick(option.item) }
        }

        inner class VH(
            root: View,
            val title: TextView,
            val subtitle: TextView,
            val divider: View,
        ) : RecyclerView.ViewHolder(root) {
            init {
                val context = root.context
                root.setPadding(dp(context, 4), dp(context, 12), dp(context, 4), 0)
            }
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
