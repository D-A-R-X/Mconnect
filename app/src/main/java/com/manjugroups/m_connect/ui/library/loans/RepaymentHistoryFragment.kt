package com.manjugroups.m_connect.ui.library.loans

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentRepaymentHistoryBinding
import com.manjugroups.m_connect.databinding.ItemRepaymentEntryBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.ui.common.navigateUp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RepaymentHistoryFragment : Fragment() {

    companion object {
        private const val ARG_LOAN_ID = "loanId"
        private const val ARG_LOAN_STATUS = "loanStatus"

        fun newInstance(loanId: String, status: LoanStatus = LoanStatus.REPAID) =
            RepaymentHistoryFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_LOAN_ID, loanId)
                    putString(ARG_LOAN_STATUS, status.name)
                }
            }
    }

    private var _binding: FragmentRepaymentHistoryBinding? = null
    private val binding get() = _binding!!

    private val api = ApiService.create()
    private val adapter = RepaymentAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRepaymentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applySystemInsets()

        binding.rvRepayments.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRepayments.adapter = adapter
        binding.rvRepayments.itemAnimator = null

        binding.btnRepaymentBack.setOnClickListener { navigateUp() }

        loadFromApi()
    }

    private fun applySystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = sys.top, bottom = sys.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun loadFromApi() {
        val loanId = arguments?.getString(ARG_LOAN_ID).orEmpty()
        if (loanId.isBlank()) return
        val status = runCatching {
            LoanStatus.valueOf(arguments?.getString(ARG_LOAN_STATUS) ?: LoanStatus.REPAID.name)
        }.getOrDefault(LoanStatus.REPAID)
        val token = SessionManager(requireContext()).bearerToken
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { api.getLoanDetail(token, loanId) }
                .onSuccess { resp ->
                    if (_binding == null) return@onSuccess
                    val loan = resp.loan ?: return@onSuccess
                    val mapped = LoanMapper.fromRemote(loan, status)
                    bindSummary(mapped)
                    adapter.submit(mapped.repayments.sortedByDescending { it.dueMillis })
                }
        }
    }

    private fun bindSummary(loan: Loan) {
        if (_binding == null) return
        binding.loanSummaryCard.visibility = View.VISIBLE
        binding.tvLoanSummaryTitle.text = loan.title
        binding.tvLoanSummaryId.text = loan.loanId.ifBlank { "—" }
        binding.tvLoanSummaryPrincipal.text = LoansAdapter.formatRupees(loan.principal)
        binding.tvLoanSummaryEmi.text = LoansAdapter.formatRupees(loan.nextEmiAmount)
        binding.tvLoanSummaryRemaining.text = LoansAdapter.formatRupees(loan.outstandingBalance)
        binding.tvLoanSummaryDisbursed.text = if (loan.disbursedMillis > 0L) {
            SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(loan.disbursedMillis))
        } else {
            "—"
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setTabBarVisible(false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class RepaymentAdapter : RecyclerView.Adapter<RepaymentAdapter.VH>() {
        private val items = mutableListOf<Repayment>()

        fun submit(list: List<Repayment>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemRepaymentEntryBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = items[position]
            val isFirst = position == 0
            val isLast = position == items.size - 1
            holder.b.timelineTop.visibility = if (isFirst) View.INVISIBLE else View.VISIBLE
            holder.b.timelineBottom.visibility = if (isLast) View.INVISIBLE else View.VISIBLE

            holder.b.tvRepaymentDate.text =
                SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(r.dueMillis))
            holder.b.tvRepaymentEmi.text = "EMI #${r.emiIndex}"
            holder.b.tvRepaymentAmount.text = LoansAdapter.formatRupees(r.amount)

            when (r.status) {
                RepaymentStatus.PAID -> {
                    holder.b.tvRepaymentStatus.text = "PAID"
                    holder.b.tvRepaymentStatus.setBackgroundResource(R.drawable.bg_loan_status_paid)
                    holder.b.tvRepaymentStatus.setTextColor(android.graphics.Color.parseColor("#12B76A"))
                    holder.b.timelineDot.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#12B76A"))
                    holder.b.ivTimelineIcon.setImageResource(R.drawable.ic_success_check)
                    holder.b.ivTimelineIcon.imageTintList =
                        android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FFFFFF"))
                    holder.b.repaymentPaymentMeta.visibility = View.VISIBLE
                    holder.b.tvPaidVia.text = "Paid via ${r.paidVia ?: "Bank"}"
                    holder.b.tvPaidOnTime.text = if (r.onTime) "Payment On Time" else "Late Payment"
                }
                RepaymentStatus.UPCOMING -> {
                    holder.b.tvRepaymentStatus.text = "UPCOMING"
                    holder.b.tvRepaymentStatus.setBackgroundResource(R.drawable.bg_loan_status_upcoming)
                    holder.b.tvRepaymentStatus.setTextColor(android.graphics.Color.parseColor("#0B61CA"))
                    holder.b.timelineDot.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#0B61CA"))
                    holder.b.ivTimelineIcon.setImageResource(R.drawable.ic_clock)
                    holder.b.ivTimelineIcon.imageTintList =
                        android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FFFFFF"))
                    holder.b.repaymentPaymentMeta.visibility = View.GONE
                }
                RepaymentStatus.OVERDUE -> {
                    holder.b.tvRepaymentStatus.text = "OVERDUE"
                    holder.b.tvRepaymentStatus.setBackgroundResource(R.drawable.bg_loan_status_paid)
                    holder.b.tvRepaymentStatus.setTextColor(android.graphics.Color.parseColor("#F04438"))
                    holder.b.timelineDot.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#F04438"))
                    holder.b.ivTimelineIcon.setImageResource(R.drawable.ic_clock)
                    holder.b.ivTimelineIcon.imageTintList =
                        android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FFFFFF"))
                    holder.b.repaymentPaymentMeta.visibility = View.GONE
                }
            }
        }

        override fun getItemCount(): Int = items.size

        class VH(val b: ItemRepaymentEntryBinding) : RecyclerView.ViewHolder(b.root)
    }
}
