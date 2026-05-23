package com.manjugroups.m_connect.ui.projects

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.MarkExpensePaidRequest
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Expense Overview — read-only detail surface. Loads a single expense
 * via /api/projects/expenses/get, renders the design's stacked rows
 * (Amount / Date / Payment Method / Notes), and optionally surfaces a
 * "Mark paid / Mark unpaid" toggle when the viewer has the approve
 * permission (server-gated; we cheaply gate the visibility client-side
 * using the cached IAM keys to avoid showing an action that always
 * 403s).
 */
class ExpenseDetailBottomSheet : BottomSheetDialogFragment() {

    private val api = ApiService.create()
    private lateinit var session: SessionManager

    private val expenseId: String by lazy { requireArguments().getString(ARG_ID).orEmpty() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_expense_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        view.findViewById<MaterialButton>(R.id.btnClose).setOnClickListener {
            dismissAllowingStateLoss()
        }

        if (expenseId.isBlank()) {
            Toast.makeText(requireContext(), "Missing expense id", Toast.LENGTH_SHORT).show()
            dismissAllowingStateLoss()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getProjectExpense(session.bearerToken, expenseId)
                if (!resp.success || resp.expense == null) {
                    Toast.makeText(
                        requireContext(),
                        resp.error ?: "Failed to load expense",
                        Toast.LENGTH_LONG,
                    ).show()
                    dismissAllowingStateLoss()
                    return@launch
                }
                bindExpense(view, resp.expense)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message ?: "Network error", Toast.LENGTH_LONG)
                    .show()
                dismissAllowingStateLoss()
            }
        }
    }

    private fun bindExpense(view: View, expense: com.manjugroups.m_connect.network.ProjectExpenseDetail) {
        val ctx = requireContext()
        val tvCategory = view.findViewById<TextView>(R.id.tvDetailCategory)
        val tvPaidPill = view.findViewById<TextView>(R.id.tvDetailPaidPill)
        val tvAmount = view.findViewById<TextView>(R.id.tvDetailAmount)
        val tvDate = view.findViewById<TextView>(R.id.tvDetailDate)
        val tvPayment = view.findViewById<TextView>(R.id.tvDetailPaymentMethod)
        val tvNotes = view.findViewById<TextView>(R.id.tvDetailNotes)
        val iconBg = view.findViewById<View>(R.id.headerIconBg)
        val btnToggle = view.findViewById<MaterialButton>(R.id.btnTogglePaid)

        tvCategory.text = expense.category.replaceFirstChar { it.uppercase() }
        val color = when (expense.category) {
            "labour" -> ContextCompat.getColor(ctx, R.color.category_labour)
            "materials" -> ContextCompat.getColor(ctx, R.color.category_materials)
            "equipment" -> ContextCompat.getColor(ctx, R.color.category_equipment)
            else -> ContextCompat.getColor(ctx, R.color.category_other)
        }
        iconBg.backgroundTintList = ColorStateList.valueOf(color)

        val currencyFmt = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"))
        currencyFmt.maximumFractionDigits = 0
        tvAmount.text = currencyFmt.format(expense.amount)

        // Pretty-format the YYYY-MM-DD ISO date for display.
        tvDate.text = runCatching {
            val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val display = SimpleDateFormat("MMM d, yyyy", Locale.US)
            display.format(parser.parse(expense.date)!!)
        }.getOrDefault(expense.date)

        tvPayment.text = expense.paymentMethod
            ?.split('_')
            ?.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            ?: "—"

        if (expense.notes.isNullOrBlank()) {
            tvNotes.text = "No notes recorded."
        } else {
            tvNotes.text = expense.notes
        }

        if (expense.paid) {
            tvPaidPill.text = "Paid"
            tvPaidPill.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.category_equipment),
            )
        } else {
            tvPaidPill.text = "Pending"
            tvPaidPill.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.category_materials),
            )
        }

        // Surface the toggle only when the viewer can approve (client-
        // side check matches the server-side IAM guard so we don't
        // dangle an always-403 button).
        val canApprove = session.hasPermission("projects.expenses.approve")
        if (canApprove) {
            btnToggle.visibility = View.VISIBLE
            btnToggle.text = if (expense.paid) "Mark unpaid" else "Mark paid"
            btnToggle.setOnClickListener {
                btnToggle.isEnabled = false
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val target = !expense.paid
                        val r = api.markProjectExpensePaid(
                            session.bearerToken,
                            MarkExpensePaidRequest(id = expense.id, paid = target),
                        )
                        if (r.success) {
                            Toast.makeText(
                                requireContext(),
                                if (target) "Marked paid" else "Marked unpaid",
                                Toast.LENGTH_SHORT,
                            ).show()
                            setFragmentResult(RESULT_KEY, Bundle.EMPTY)
                            dismissAllowingStateLoss()
                        } else {
                            Toast.makeText(
                                requireContext(),
                                r.error ?: "Failed to update",
                                Toast.LENGTH_LONG,
                            ).show()
                            btnToggle.isEnabled = true
                        }
                    } catch (e: Exception) {
                        Toast.makeText(
                            requireContext(),
                            e.message ?: "Network error",
                            Toast.LENGTH_LONG,
                        ).show()
                        btnToggle.isEnabled = true
                    }
                }
            }
        }
    }

    companion object {
        const val RESULT_KEY = "ExpenseDetailUpdated"
        private const val ARG_ID = "expenseId"

        fun newInstance(expenseId: String) = ExpenseDetailBottomSheet().apply {
            arguments = Bundle().apply { putString(ARG_ID, expenseId) }
        }
    }
}
