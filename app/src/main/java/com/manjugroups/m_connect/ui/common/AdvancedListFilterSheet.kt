package com.manjugroups.m_connect.ui.common

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import android.widget.EditText
import android.text.Editable
import android.text.TextWatcher
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.ui.hr.CalendarRangePickerSheet
import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Reusable full-screen filter surface with a category rail and persistent
 * Clear/Apply actions. Screens own the filtering rules; this class owns only
 * selection UX, date-range editing, and state transfer.
 */
class AdvancedListFilterSheet : DialogFragment() {

    data class Option(
        val value: String,
        val label: String,
        val subtitle: String? = null,
    ) : Serializable

    data class Category(
        val key: String,
        val label: String,
        val options: List<Option> = emptyList(),
        val single: Boolean = true,
        val dateRange: Boolean = false,
        val searchable: Boolean = !dateRange,
    ) : Serializable

    data class State(
        val selected: Map<String, Set<String>> = emptyMap(),
        val fromDate: String? = null,
        val toDate: String? = null,
    ) : Serializable {
        fun value(key: String): String? = selected[key]?.firstOrNull()
        fun values(key: String): Set<String> = selected[key].orEmpty()
        fun activeCount(): Int = selected.values.sumOf { it.size } +
            if (!fromDate.isNullOrBlank() || !toDate.isNullOrBlank()) 1 else 0
    }

    var countProvider: ((State) -> Int)? = null

    private lateinit var categories: List<Category>
    private lateinit var resultKey: String
    private var selectedCategory = 0
    private val selected = linkedMapOf<String, MutableSet<String>>()
    private var fromDate: String? = null
    private var toDate: String? = null

    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var optionAdapter: OptionAdapter
    private var resultCount: TextView? = null
    private var activeSummary: TextView? = null
    private var optionSearch: EditText? = null
    private var optionsEmpty: TextView? = null
    private var optionQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_Mconnect)
        @Suppress("DEPRECATION")
        categories = (requireArguments().getSerializable(ARG_CATEGORIES) as? ArrayList<Category>)
            ?.toList().orEmpty()
        resultKey = requireArguments().getString(ARG_RESULT_KEY).orEmpty()
        @Suppress("DEPRECATION")
        val initial = requireArguments().getSerializable(ARG_INITIAL) as? State ?: State()
        initial.selected.forEach { (key, values) -> selected[key] = values.toMutableSet() }
        fromDate = initial.fromDate
        toDate = initial.toDate
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setOnShowListener {
                window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_advanced_list_filter, container, false)

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        resultCount = view.findViewById(R.id.tvFilterResultCount)
        activeSummary = view.findViewById(R.id.tvFilterActiveSummary)
        optionSearch = view.findViewById(R.id.etFilterOptionSearch)
        optionsEmpty = view.findViewById(R.id.tvFilterOptionsEmpty)

        categoryAdapter = CategoryAdapter()
        optionAdapter = OptionAdapter()
        view.findViewById<RecyclerView>(R.id.rvFilterCategories).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = categoryAdapter
        }
        view.findViewById<RecyclerView>(R.id.rvFilterOptions).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = optionAdapter
        }
        optionSearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                optionQuery = s?.toString()?.trim().orEmpty()
                optionAdapter.notifyDataSetChanged()
                refreshOptionPane()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        childFragmentManager.setFragmentResultListener(DATE_RESULT_KEY, viewLifecycleOwner) { _, b ->
            fromDate = b.getString(CalendarRangePickerSheet.KEY_FROM)
            toDate = b.getString(CalendarRangePickerSheet.KEY_TO)
            optionAdapter.notifyDataSetChanged()
            refreshFooter()
        }

        view.findViewById<View>(R.id.btnFilterBack).setOnClickListener { dismissAllowingStateLoss() }
        view.findViewById<View>(R.id.btnFilterClear).setOnClickListener {
            selected.clear()
            fromDate = null
            toDate = null
            categoryAdapter.notifyDataSetChanged()
            optionAdapter.notifyDataSetChanged()
            refreshFooter()
        }
        view.findViewById<View>(R.id.btnFilterApply).setOnClickListener {
            setFragmentResult(resultKey, bundleOf(KEY_STATE to currentState()))
            dismissAllowingStateLoss()
        }

        categoryAdapter.notifyDataSetChanged()
        optionAdapter.notifyDataSetChanged()
        refreshOptionPane()
        refreshFooter()
    }

    private fun currentState(): State = State(
        selected = selected.mapValues { it.value.toSet() },
        fromDate = fromDate,
        toDate = toDate,
    )

    private fun refreshFooter() {
        val state = currentState()
        val count = countProvider?.invoke(state)
        resultCount?.text = when (count) {
            null -> "Results update after Apply"
            1 -> "1 result"
            else -> "$count results"
        }
        activeSummary?.text = when (val active = state.activeCount()) {
            0 -> "No filters selected"
            1 -> "1 filter selected"
            else -> "$active filters selected"
        }
    }

    private fun category(): Category? = categories.getOrNull(selectedCategory)

    private fun refreshOptionPane() {
        val current = category()
        val searchable = current?.searchable == true && current.dateRange.not()
        optionSearch?.visibility = if (searchable) View.VISIBLE else View.GONE
        val visibleCount = optionAdapter.itemCount
        optionsEmpty?.apply {
            visibility = if (current?.dateRange != true && visibleCount == 0) View.VISIBLE else View.GONE
            text = if (optionQuery.isBlank()) "No options available" else "No matching options"
        }
    }

    private fun toggle(category: Category, value: String) {
        val values = selected.getOrPut(category.key) { linkedSetOf() }
        if (value in values) {
            values.remove(value)
        } else {
            if (category.single) values.clear()
            values.add(value)
        }
        if (values.isEmpty()) selected.remove(category.key)
        categoryAdapter.notifyItemChanged(selectedCategory)
        optionAdapter.notifyDataSetChanged()
        refreshFooter()
    }

    private fun openDatePicker() {
        CalendarRangePickerSheet.newInstance(
            title = "Date range",
            subtitle = "Choose the records to include",
            initialFrom = fromDate,
            initialTo = toDate,
            resultKey = DATE_RESULT_KEY,
        ).showOnce(childFragmentManager, "advanced_filter_date")
    }

    private fun applyDatePreset(value: String) {
        val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val now = Calendar.getInstance()
        when (value) {
            DATE_TODAY -> {
                fromDate = ymd.format(now.time)
                toDate = fromDate
            }
            DATE_LAST_7 -> {
                toDate = ymd.format(now.time)
                now.add(Calendar.DAY_OF_YEAR, -6)
                fromDate = ymd.format(now.time)
            }
            DATE_THIS_MONTH -> {
                toDate = ymd.format(now.time)
                now.set(Calendar.DAY_OF_MONTH, 1)
                fromDate = ymd.format(now.time)
            }
            DATE_CUSTOM -> openDatePicker()
        }
        optionAdapter.notifyDataSetChanged()
        refreshFooter()
    }

    private fun dateLabel(): String {
        val from = fromDate
        val to = toDate
        if (from.isNullOrBlank() && to.isNullOrBlank()) return "Choose dates"
        val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val display = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        fun pretty(value: String?): String = value?.let {
            runCatching { display.format(ymd.parse(it) ?: Date()) }.getOrDefault(it)
        }.orEmpty()
        return if (from == to) pretty(from) else "${pretty(from)} - ${pretty(to)}"
    }

    private inner class CategoryAdapter : RecyclerView.Adapter<CategoryAdapter.Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
            layoutInflater.inflate(R.layout.item_advanced_filter_category, parent, false),
        )

        override fun getItemCount(): Int = categories.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val category = categories[position]
            holder.label.text = category.label
            val count = if (category.dateRange) {
                if (fromDate != null || toDate != null) 1 else 0
            } else selected[category.key]?.size ?: 0
            holder.badge.text = count.toString()
            holder.badge.visibility = if (count > 0) View.VISIBLE else View.GONE
            holder.itemView.isSelected = position == selectedCategory
            holder.itemView.setOnClickListener {
                val next = holder.bindingAdapterPosition
                if (next == RecyclerView.NO_POSITION) return@setOnClickListener
                val old = selectedCategory
                selectedCategory = next
                optionQuery = ""
                optionSearch?.setText("")
                notifyItemChanged(old)
                notifyItemChanged(selectedCategory)
                optionAdapter.notifyDataSetChanged()
                refreshOptionPane()
            }
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val label: TextView = view.findViewById(R.id.tvFilterCategoryLabel)
            val badge: TextView = view.findViewById(R.id.tvFilterCategoryBadge)
        }
    }

    private inner class OptionAdapter : RecyclerView.Adapter<OptionAdapter.Holder>() {
        private val dateOptions = listOf(
            Option(DATE_TODAY, "Today"),
            Option(DATE_LAST_7, "Last 7 days"),
            Option(DATE_THIS_MONTH, "This month"),
            Option(DATE_CUSTOM, "Custom range", dateLabel()),
        )

        private fun options(): List<Option> = if (category()?.dateRange == true) {
            dateOptions.map { if (it.value == DATE_CUSTOM) it.copy(subtitle = dateLabel()) else it }
        } else category()?.options.orEmpty().filter { option ->
            optionQuery.isBlank() || option.label.contains(optionQuery, ignoreCase = true) ||
                option.subtitle?.contains(optionQuery, ignoreCase = true) == true
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
            layoutInflater.inflate(R.layout.item_advanced_filter_option, parent, false),
        )

        override fun getItemCount(): Int = options().size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val category = category() ?: return
            val option = options()[position]
            holder.check.text = option.label
            holder.subtitle.text = option.subtitle.orEmpty()
            holder.subtitle.visibility = if (option.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
            holder.check.isChecked = if (category.dateRange) {
                when (option.value) {
                    DATE_TODAY -> isTodayRange()
                    DATE_LAST_7 -> isLast7Range()
                    DATE_THIS_MONTH -> isThisMonthRange()
                    DATE_CUSTOM -> fromDate != null && toDate != null &&
                        !isTodayRange() && !isLast7Range() && !isThisMonthRange()
                    else -> false
                }
            } else option.value in selected[category.key].orEmpty()
            holder.itemView.setOnClickListener {
                if (category.dateRange) applyDatePreset(option.value) else toggle(category, option.value)
            }
            holder.check.setOnClickListener {
                if (category.dateRange) applyDatePreset(option.value) else toggle(category, option.value)
            }
        }

        private fun expectedRange(daysBack: Int = 0, monthStart: Boolean = false): Pair<String, String> {
            val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val now = Calendar.getInstance()
            val end = ymd.format(now.time)
            if (monthStart) now.set(Calendar.DAY_OF_MONTH, 1) else now.add(Calendar.DAY_OF_YEAR, -daysBack)
            return ymd.format(now.time) to end
        }

        private fun isTodayRange() = (fromDate to toDate) == expectedRange()
        private fun isLast7Range() = (fromDate to toDate) == expectedRange(daysBack = 6)
        private fun isThisMonthRange() = (fromDate to toDate) == expectedRange(monthStart = true)

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val check: MaterialCheckBox = view.findViewById(R.id.cbFilterOption)
            val subtitle: TextView = view.findViewById(R.id.tvFilterOptionSubtitle)
        }
    }

    companion object {
        const val KEY_STATE = "advanced_filter_state"
        const val DATE_TODAY = "today"
        const val DATE_LAST_7 = "last_7"
        const val DATE_THIS_MONTH = "this_month"
        const val DATE_CUSTOM = "custom"

        private const val ARG_CATEGORIES = "categories"
        private const val ARG_INITIAL = "initial"
        private const val ARG_RESULT_KEY = "result_key"
        private const val DATE_RESULT_KEY = "advanced_filter_date_result"

        fun newInstance(
            categories: List<Category>,
            initial: State,
            resultKey: String,
        ) = AdvancedListFilterSheet().apply {
            arguments = bundleOf(
                ARG_CATEGORIES to ArrayList(categories),
                ARG_INITIAL to initial,
                ARG_RESULT_KEY to resultKey,
            )
        }

        @Suppress("DEPRECATION")
        fun state(bundle: Bundle): State? = bundle.getSerializable(KEY_STATE) as? State
    }
}
