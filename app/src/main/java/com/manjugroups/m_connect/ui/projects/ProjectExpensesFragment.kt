package com.manjugroups.m_connect.ui.projects

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.datepicker.MaterialDatePicker
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.ProjectExpense
import com.manjugroups.m_connect.network.ProjectSummary
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * "Expenses" screen — entry point for the on-site spend log. Lists
 * project expenses for the user's selected project, with category
 * filter pills, optional date-range filter, and totals header.
 * Tapping the FAB opens [ExpenseCreateBottomSheet]; tapping a row
 * opens the same sheet (pre-fill TODO). The project picker fetches
 * the user's project list via `/api/projects`.
 *
 * IAM:
 *  - View requires `projects.expenses.view` (server-side gated).
 *  - Add Expense requires `projects.expenses.create`.
 *  - Marking paid/unpaid requires `projects.expenses.approve` (server).
 */
class ProjectExpensesFragment : Fragment() {

    private val api = ApiService.create()
    private lateinit var session: SessionManager

    private var projects: List<ProjectSummary> = emptyList()
    private var selectedProjectId: String? = null
    private var selectedCategory: String? = null
    private var fromDate: String? = null
    private var toDate: String? = null

    private lateinit var btnProjectPicker: LinearLayout
    private lateinit var tvProjectPickerLabel: TextView
    private lateinit var dotRed: View
    private lateinit var tvTotalAmount: TextView
    private lateinit var tvTotalLabour: TextView
    private lateinit var tvTotalMaterials: TextView
    private lateinit var tvTotalEquipment: TextView
    private lateinit var tvDateRange: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var rvExpenses: RecyclerView
    private lateinit var donut: DonutChartView
    private lateinit var btnAddExpense: MaterialButton

    private val adapter = ExpenseAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_project_expenses, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        // Push the blue header below the OS status bar.
        val expenseHeader = view.findViewById<View>(R.id.expenseHeader)
        val baseHeaderTopPadding = expenseHeader.paddingTop
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(expenseHeader) { v, insets ->
            val topInset = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.statusBars()
            ).top
            v.setPadding(
                v.paddingLeft,
                baseHeaderTopPadding + topInset,
                v.paddingRight,
                v.paddingBottom,
            )
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(expenseHeader)

        view.findViewById<ImageView>(R.id.btnBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        btnProjectPicker = view.findViewById(R.id.btnProjectPicker)
        tvProjectPickerLabel = view.findViewById(R.id.tvProjectPickerLabel)
        dotRed = view.findViewById(R.id.dotRed)
        tvTotalAmount = view.findViewById(R.id.tvTotalAmount)
        tvTotalLabour = view.findViewById(R.id.tvTotalLabour)
        tvTotalMaterials = view.findViewById(R.id.tvTotalMaterials)
        tvTotalEquipment = view.findViewById(R.id.tvTotalEquipment)
        tvDateRange = view.findViewById(R.id.tvDateRange)
        tvEmpty = view.findViewById(R.id.tvEmpty)
        rvExpenses = view.findViewById(R.id.rvExpenses)
        donut = view.findViewById(R.id.donutChart)
        btnAddExpense = view.findViewById(R.id.btnAddExpense)

        rvExpenses.layoutManager = LinearLayoutManager(requireContext())
        rvExpenses.adapter = adapter

        btnProjectPicker.setOnClickListener { showProjectPicker() }

        view.findViewById<View>(R.id.btnDateFilter).setOnClickListener {
            showDateFilter()
        }

        val chips = view.findViewById<ChipGroup>(R.id.categoryPills)
        chips.setOnCheckedStateChangeListener { _, checkedIds ->
            val checked = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            selectedCategory = when (checked) {
                R.id.chipLabour -> "labour"
                R.id.chipMaterials -> "materials"
                R.id.chipEquipment -> "equipment"
                R.id.chipOther -> "other"
                else -> null
            }
            // Smooth fade transition for the list
            rvExpenses.animate().alpha(0f).setDuration(150L).withEndAction {
                refreshExpenses()
                rvExpenses.animate().alpha(1f).setDuration(150L).start()
            }.start()
        }

        btnAddExpense.setOnClickListener {
            val projectId = selectedProjectId
            if (projectId == null) {
                Toast.makeText(requireContext(), "Pick a project first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ExpenseCreateBottomSheet
                .newInstance(projectId, selectedCategory)
                .show(parentFragmentManager, "expense_create")
        }

        // Refresh list when create sheet OR detail-sheet mark-paid emits a result.
        parentFragmentManager.setFragmentResultListener(
            ExpenseCreateBottomSheet.RESULT_KEY,
            viewLifecycleOwner,
        ) { _, _ -> refreshExpenses() }
        parentFragmentManager.setFragmentResultListener(
            ExpenseDetailBottomSheet.RESULT_KEY,
            viewLifecycleOwner,
        ) { _, _ -> refreshExpenses() }

        loadProjects()
        
        // Initial entry animations
        playEntryAnimations(view)
    }

    private fun playEntryAnimations(view: View) {
        val totalsCard = view.findViewById<View>(R.id.cardTotals)
        val chips = view.findViewById<View>(R.id.categoryPills).parent as View
        
        totalsCard.alpha = 0f
        totalsCard.translationY = 40f
        totalsCard.animate().alpha(1f).translationY(0f).setDuration(400L).setStartDelay(100L).start()
        
        chips.alpha = 0f
        chips.translationY = 20f
        chips.animate().alpha(1f).translationY(0f).setDuration(400L).setStartDelay(200L).start()
    }

    override fun onResume() {
        super.onResume()
        // Blue full-bleed header — let the gradient draw behind the status
        // bar, paint the system icons light so they stay readable.
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTopBarAppearance(
            android.graphics.Color.parseColor("#0B61CA"),
            darkStatusIcons = false,
            fullBleed = true,
        )
    }

    private fun loadProjects() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getMyProjects(session.bearerToken)
                if (resp.success) {
                    projects = resp.projects
                    if (projects.isEmpty()) {
                        tvProjectPickerLabel.text = "No projects assigned"
                        dotRed.visibility = View.GONE
                        Toast.makeText(
                            requireContext(),
                            "You're not assigned to any projects yet.",
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        // Auto-select the first project so the user sees data
                        // immediately. They can still switch via the picker.
                        val first = projects.first()
                        selectedProjectId = first.id
                        tvProjectPickerLabel.text = first.name ?: "Untitled project"
                        dotRed.visibility = View.VISIBLE
                        refreshExpenses()
                    }
                } else {
                    Toast.makeText(
                        requireContext(),
                        resp.error ?: "Failed to load projects",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message ?: "Network error", Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    private fun showProjectPicker() {
        if (projects.isEmpty()) return
        // Result listener — re-registered each open so a stale closure
        // doesn't capture a stale `projects` list across reloads.
        parentFragmentManager.setFragmentResultListener(
            ProjectPickerBottomSheet.RESULT_KEY,
            viewLifecycleOwner,
        ) { _, bundle ->
            val pickedId = bundle.getString(ProjectPickerBottomSheet.RESULT_PROJECT_ID)
                ?: return@setFragmentResultListener
            val pickedName = bundle.getString(ProjectPickerBottomSheet.RESULT_PROJECT_NAME)
                ?: "Untitled project"
            if (pickedId != selectedProjectId) {
                selectedProjectId = pickedId
                tvProjectPickerLabel.text = pickedName
                dotRed.visibility = View.VISIBLE
                refreshExpenses()
            }
        }
        ProjectPickerBottomSheet
            .newInstance(
                ids = projects.map { it.id },
                names = projects.map { it.name ?: "Untitled project" },
                selectedId = selectedProjectId,
            )
            .show(parentFragmentManager, "project_picker")
    }

    private fun showDateFilter() {
        DateFilterBottomSheet.newInstance().show(parentFragmentManager, "date_filter")
        parentFragmentManager.setFragmentResultListener(DateFilterBottomSheet.REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            fromDate = bundle.getString(DateFilterBottomSheet.RESULT_FROM)
            toDate = bundle.getString(DateFilterBottomSheet.RESULT_TO)
            
            // Format for display
            val display = runCatching {
                val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val formatter = SimpleDateFormat("MMM d, yyyy", Locale.US)
                val start = formatter.format(parser.parse(fromDate!!)!!)
                val end = formatter.format(parser.parse(toDate!!)!!)
                "$start - $end"
            }.getOrDefault("$fromDate - $toDate")
            
            tvDateRange.text = display
            refreshExpenses()
        }
    }

    private fun refreshExpenses() {
        val projectId = selectedProjectId ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.listProjectExpenses(
                    session.bearerToken,
                    projectId = projectId,
                    fromDate = fromDate,
                    toDate = toDate,
                    category = selectedCategory,
                )
                if (resp.success) {
                    adapter.submit(resp.expenses)
                    tvEmpty.visibility =
                        if (resp.expenses.isEmpty()) View.VISIBLE else View.GONE
                    rvExpenses.visibility =
                        if (resp.expenses.isEmpty()) View.GONE else View.VISIBLE
                    val totals = resp.totals
                    // Design uses "RS 2,40,000" (uppercase RS) for the headline
                    // total and "Rs 40,000" (lowercase) for legend rows. Use a
                    // plain en-IN number formatter and prepend the prefix so we
                    // don't get the ₹ glyph from the currency formatter.
                    tvTotalAmount.text = formatRsUpper(totals?.total ?: 0.0)
                    tvTotalLabour.text = formatRs(totals?.byCategory?.labour ?: 0.0)
                    tvTotalMaterials.text = formatRs(totals?.byCategory?.materials ?: 0.0)
                    tvTotalEquipment.text = formatRs(totals?.byCategory?.equipment ?: 0.0)
                    // Drive the donut chart from the same aggregate.
                    donut.setValues(
                        labour = totals?.byCategory?.labour ?: 0.0,
                        materials = totals?.byCategory?.materials ?: 0.0,
                        equipment = totals?.byCategory?.equipment ?: 0.0,
                        other = totals?.byCategory?.other ?: 0.0,
                    )
                    if (fromDate == null && toDate == null) {
                        tvDateRange.text = "All time"
                    }
                } else {
                    Toast.makeText(
                        requireContext(),
                        resp.error ?: "Failed to load expenses",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message ?: "Network error", Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    // ── Formatting helpers ──

    /** Indian-locale grouping (e.g. "2,40,000") with no fractional digits.
     *  Reused for the headline total and the legend amounts. */
    private val inNumberFmt by lazy {
        NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN")).apply {
            maximumFractionDigits = 0
        }
    }

    private fun formatRs(value: Double): String = "Rs ${inNumberFmt.format(value)}"
    private fun formatRsUpper(value: Double): String = "RS ${inNumberFmt.format(value)}"

    // ── Adapter ──

    private inner class ExpenseAdapter :
        RecyclerView.Adapter<ExpenseAdapter.ExpenseViewHolder>() {

        private val items = mutableListOf<ProjectExpense>()

        fun submit(list: List<ProjectExpense>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExpenseViewHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_project_expense, parent, false)
            return ExpenseViewHolder(v)
        }

        override fun onBindViewHolder(holder: ExpenseViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ExpenseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvCategory: TextView = itemView.findViewById(R.id.tvCategory)
            private val tvAmount: TextView = itemView.findViewById(R.id.tvAmount)
            private val tvDate: TextView = itemView.findViewById(R.id.tvDate)
            private val tvPaidPill: TextView = itemView.findViewById(R.id.tvPaidPill)
            private val iconBg: View = itemView.findViewById(R.id.iconBg)

            fun bind(item: ProjectExpense) {
                tvCategory.text = item.category.replaceFirstChar { it.uppercase() }
                tvAmount.text = formatRs(item.amount)
                // Design shows "January 2024" (month + year) on the row.
                // Server gives ISO yyyy-MM-dd; fall back to raw on parse failure.
                tvDate.text = runCatching {
                    val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    val display = SimpleDateFormat("MMMM yyyy", Locale.US)
                    display.format(parser.parse(item.date)!!)
                }.getOrDefault(item.date)
                val ctx = itemView.context
                val color = when (item.category) {
                    "labour" -> ContextCompat.getColor(ctx, R.color.category_labour)
                    "materials" -> ContextCompat.getColor(ctx, R.color.category_materials)
                    "equipment" -> ContextCompat.getColor(ctx, R.color.category_equipment)
                    else -> ContextCompat.getColor(ctx, R.color.category_other)
                }
                iconBg.backgroundTintList = ColorStateList.valueOf(color)
                if (item.paid) {
                    tvPaidPill.text = "Paid"
                    tvPaidPill.visibility = View.VISIBLE
                    tvPaidPill.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.category_equipment),
                    )
                } else {
                    tvPaidPill.text = "Pending"
                    tvPaidPill.visibility = View.VISIBLE
                    tvPaidPill.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.category_materials),
                    )
                }
                // Whole-row click → Expense Detail bottom sheet.
                itemView.setOnClickListener {
                    ExpenseDetailBottomSheet
                        .newInstance(item.id)
                        .show(parentFragmentManager, "expense_detail")
                }
            }
        }
    }
}
