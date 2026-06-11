package com.manjugroups.m_connect.ui.library.loans

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.ItemRequestedLoanBinding
import com.manjugroups.m_connect.network.LoanData
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class RequestedLoansAdapter(
    private val onAcceptClick: (LoanData) -> Unit,
    private val onRejectClick: (LoanData) -> Unit
) : ListAdapter<LoanData, RequestedLoansAdapter.ViewHolder>(LoanDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRequestedLoanBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemRequestedLoanBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(loan: LoanData) {
            val isAdvance = loan.interestType.equals("Salary Advance", ignoreCase = true) ||
                            loan.interestType.equals("salary_advance", ignoreCase = true) ||
                            loan.purpose?.contains("advance", ignoreCase = true) == true
            // Set icon
            binding.ivLoanIcon.setImageResource(
                if (isAdvance) R.drawable.ic_loan_rupee else R.drawable.ic_loan_home
            )
            // Title and ID
            binding.tvLoanTitle.text = loan.purpose?.takeIf { it.isNotBlank() } ?: if (isAdvance) "Salary Advance" else "Home Loan"
            binding.tvLoanId.text = loan.loanId ?: "LN..."

            // Format amounts
            val formatter = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
            formatter.maximumFractionDigits = 0
            val amount = loan.loanAmount ?: loan.principalAmount ?: 0.0
            binding.tvPrincipal.text = formatter.format(amount)

            // Format date
            val rawDate = loan.disbursedDate ?: ""
            binding.tvDisbursed.text = if (rawDate.isNotBlank()) rawDate else "Not disbursed"

            // Requester
            binding.tvRequesterName.text = loan.staffName ?: loan.employeeId ?: "Unknown"

            // Buttons visibility based on status
            if (loan.status == "APPROVED") {
                binding.layoutActionButtons.visibility = android.view.View.GONE
                binding.btnAccepted.visibility = android.view.View.VISIBLE
            } else {
                binding.layoutActionButtons.visibility = android.view.View.VISIBLE
                binding.btnAccepted.visibility = android.view.View.GONE
            }

            // Buttons
            binding.btnAccept.setOnClickListener { onAcceptClick(loan) }
            binding.btnReject.setOnClickListener { onRejectClick(loan) }
        }
    }

    private class LoanDiffCallback : DiffUtil.ItemCallback<LoanData>() {
        override fun areItemsTheSame(oldItem: LoanData, newItem: LoanData): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: LoanData, newItem: LoanData): Boolean {
            return oldItem == newItem
        }
    }
}
