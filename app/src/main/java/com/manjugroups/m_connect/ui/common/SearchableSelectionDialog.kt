package com.manjugroups.m_connect.ui.common

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.manjugroups.m_connect.R
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    /**
     * Server-backed variant used when the complete option list is too large to
     * preload. Results update in the same sheet while the user types, so there
     * is no separate Search action or second selection dialog.
     */
    fun <T> showRemote(
        context: Context,
        scope: CoroutineScope,
        title: String,
        subtitle: String,
        searchHint: String,
        minimumQueryLength: Int = 2,
        idleMessage: String = "Type at least 2 characters to search",
        emptyMessage: String = "No matching records",
        searchRequest: suspend (String) -> List<SearchableOption<T>>,
        errorMessage: (Throwable) -> String = { "Couldn't load results. Try again." },
        onSelected: (T) -> Unit,
    ) {
        val dialog = BottomSheetDialog(context)
        var searchJob: Job? = null
        var requestSequence = 0

        val adapter = RemoteRowAdapter<T> { item ->
            onSelected(item)
            dialog.dismiss()
        }
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
            clipToPadding = false
            setPadding(0, 0, 0, dp(context, 12))
            visibility = View.GONE
        }
        val progress = ProgressBar(context).apply {
            visibility = View.GONE
        }
        val status = TextView(context).apply {
            text = idleMessage
            gravity = Gravity.CENTER
            setPadding(dp(context, 20), dp(context, 38), dp(context, 20), dp(context, 38))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#667085"))
        }
        val search = EditText(context).apply {
            hint = searchHint
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
            setBackgroundResource(R.drawable.bg_sheet_search_field)
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_cpv_search, 0, 0, 0)
            compoundDrawablePadding = dp(context, 10)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 12), dp(context, 20), dp(context, 18))
            setBackgroundColor(Color.WHITE)
            addView(View(context).apply {
                setBackgroundColor(Color.parseColor("#D0D5DD"))
            }, LinearLayout.LayoutParams(dp(context, 42), dp(context, 4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(context, 18)
            })
            addView(TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(Color.parseColor("#101828"))
                typeface = ResourcesCompat.getFont(context, R.font.inter_bold)
            })
            addView(TextView(context).apply {
                text = subtitle
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(Color.parseColor("#667085"))
                setPadding(0, dp(context, 5), 0, 0)
            })
            addView(search, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 52),
            ).apply {
                topMargin = dp(context, 16)
                bottomMargin = dp(context, 8)
            })
            addView(progress, LinearLayout.LayoutParams(dp(context, 30), dp(context, 30)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(context, 24)
                bottomMargin = dp(context, 24)
            })
            addView(status, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(list, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
        }

        fun showStatus(message: String) {
            progress.visibility = View.GONE
            list.visibility = View.GONE
            status.text = message
            status.visibility = View.VISIBLE
            adapter.submit(emptyList())
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchJob?.cancel()
                val query = s?.toString()?.trim().orEmpty()
                if (query.length < minimumQueryLength) {
                    requestSequence += 1
                    showStatus(idleMessage)
                    return
                }
                val sequence = ++requestSequence
                progress.visibility = View.VISIBLE
                status.visibility = View.GONE
                list.visibility = View.GONE
                searchJob = scope.launch {
                    delay(300)
                    try {
                        val rows = searchRequest(query)
                        if (sequence != requestSequence || !dialog.isShowing) return@launch
                        progress.visibility = View.GONE
                        if (rows.isEmpty()) {
                            showStatus(emptyMessage)
                        } else {
                            status.visibility = View.GONE
                            list.visibility = View.VISIBLE
                            adapter.submit(rows)
                        }
                    } catch (error: Exception) {
                        if (sequence != requestSequence || !dialog.isShowing) return@launch
                        showStatus(errorMessage(error))
                    }
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        dialog.setContentView(content)
        dialog.setOnDismissListener { searchJob?.cancel() }
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            )
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.peekHeight = dp(context, 620)
            }
            // Some keyboards restore their previous composing buffer when a
            // fresh field takes focus. A referral search must always begin
            // blank instead of inheriting digits typed in the CP form.
            search.setText("")
            search.clearComposingText()
            search.requestFocus()
            search.post {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        dialog.show()
    }

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
        // Cap the list at 460dp but let it WRAP to the actual content — a short
        // list (e.g. one agency) no longer leaves a big empty gap below it.
        val maxListHeight = dp(context, 460)
        val scroll = object : RecyclerView(context) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                super.onMeasure(
                    widthSpec,
                    MeasureSpec.makeMeasureSpec(maxListHeight, MeasureSpec.AT_MOST),
                )
            }
        }.apply {
            layoutManager = LinearLayoutManager(context)
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
                LinearLayout.LayoutParams.WRAP_CONTENT,
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

    private class RemoteRowAdapter<T>(
        private val onClick: (T) -> Unit,
    ) : RecyclerView.Adapter<RemoteRowAdapter<T>.VH>() {
        private var rows: List<SearchableOption<T>> = emptyList()

        fun submit(next: List<SearchableOption<T>>) {
            rows = next
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val context = parent.context
            val avatar = TextView(context).apply {
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(Color.parseColor("#0B61CA"))
                typeface = ResourcesCompat.getFont(context, R.font.inter_semibold)
                setBackgroundResource(R.drawable.bg_circle_blue_light)
            }
            val title = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(Color.parseColor("#101828"))
                typeface = ResourcesCompat.getFont(context, R.font.inter_semibold)
                maxLines = 1
            }
            val subtitle = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.parseColor("#667085"))
                setPadding(0, dp(context, 4), 0, 0)
                maxLines = 2
            }
            val textBlock = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(subtitle)
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(dp(context, 4), dp(context, 12), dp(context, 4), dp(context, 12))
                addView(avatar, LinearLayout.LayoutParams(dp(context, 42), dp(context, 42)).apply {
                    marginEnd = dp(context, 12)
                })
                addView(textBlock, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            val divider = View(context).apply { setBackgroundColor(Color.parseColor("#EAECF0")) }
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                )
                addView(row)
                addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
            }
            return VH(root, row, avatar, title, subtitle, divider)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val option = rows[position]
            holder.avatar.text = option.title.trim().take(1).uppercase(Locale.US)
            holder.title.text = option.title
            holder.subtitle.text = option.subtitle.orEmpty()
            holder.subtitle.visibility = if (option.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
            holder.divider.visibility = if (position == rows.lastIndex) View.GONE else View.VISIBLE
            holder.row.setOnClickListener { onClick(option.item) }
        }

        inner class VH(
            root: View,
            val row: View,
            val avatar: TextView,
            val title: TextView,
            val subtitle: TextView,
            val divider: View,
        ) : RecyclerView.ViewHolder(root)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
