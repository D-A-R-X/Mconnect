package com.manjugroups.m_connect.ui.library.loans

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.ItemLoanActiveBinding
import com.manjugroups.m_connect.databinding.ItemLoanPreviousBinding
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LoansAdapter(
    private val onPreviousClick: (Loan) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<Loan>()

    fun submit(list: List<Loan>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (items[position].status) {
        LoanStatus.ACTIVE, LoanStatus.PENDING -> TYPE_ACTIVE
        LoanStatus.REPAID -> TYPE_PREVIOUS
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_ACTIVE -> ActiveVH(ItemLoanActiveBinding.inflate(inflater, parent, false))
            else -> PreviousVH(ItemLoanPreviousBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val loan = items[position]
        when (holder) {
            is ActiveVH -> holder.bind(loan)
            is PreviousVH -> holder.bind(loan)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class ActiveVH(val b: ItemLoanActiveBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(loan: Loan) {
            applyIcon(loan, b.ivLoanIcon, b.loanIconTile)
            b.tvLoanTitle.text = loan.title
            b.tvLoanId.text = "Loan ID: ${loan.loanId}"
            b.tvLoanOutstanding.text = formatRupees(loan.outstandingBalance)
            when (loan.status) {
                LoanStatus.PENDING -> {
                    b.tvLoanBadge.text = "• PENDING"
                    b.tvLoanBadge.setBackgroundResource(R.drawable.bg_loan_status_pending)
                    b.tvLoanBadge.setTextColor(android.graphics.Color.parseColor("#F79009"))
                    b.tvLoanOutstandingLabel.text = "LOAN AMOUNT"
                    b.loanEmiRow.visibility = android.view.View.GONE
                }
                else -> {
                    b.tvLoanBadge.text = "• ACTIVE"
                    b.tvLoanBadge.setBackgroundResource(R.drawable.bg_loan_status_active)
                    b.tvLoanBadge.setTextColor(android.graphics.Color.parseColor("#12B76A"))
                    b.tvLoanOutstandingLabel.text = "OUTSTANDING BALANCE"
                    b.loanEmiRow.visibility = android.view.View.VISIBLE
                    b.tvLoanNextEmi.text = formatRupees(loan.nextEmiAmount)
                    b.tvLoanDueDate.text = formatShortDate(loan.nextEmiDueMillis)
                }
            }
        }
    }

    inner class PreviousVH(val b: ItemLoanPreviousBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(loan: Loan) {
            applyIcon(loan, b.ivLoanIcon, b.loanIconTile)
            b.tvLoanTitle.text = loan.title
            b.tvLoanId.text = loan.loanId
            b.tvLoanPrincipal.text = formatRupees(loan.principal)
            b.tvLoanDisbursed.text = formatShortDate(loan.disbursedMillis)
            b.btnViewRepayment.setOnClickListener { onPreviousClick(loan) }
        }
    }

    private fun applyIcon(loan: Loan, icon: android.widget.ImageView, tile: android.widget.FrameLayout) {
        when (loan.type) {
            LoanType.HOME -> {
                icon.setImageResource(R.drawable.ic_loan_home)
                tile.setBackgroundResource(R.drawable.bg_loan_icon_tile)
            }
            LoanType.EDUCATION -> {
                icon.setImageResource(R.drawable.ic_loan_education)
                tile.setBackgroundResource(R.drawable.bg_loan_icon_tile_green)
            }
            LoanType.OTHER -> {
                icon.setImageResource(R.drawable.ic_loan_home)
                tile.setBackgroundResource(R.drawable.bg_loan_icon_tile)
            }
        }
    }

    companion object {
        const val TYPE_ACTIVE = 0
        const val TYPE_PREVIOUS = 1

        private val rupeeFormatter: NumberFormat by lazy {
            NumberFormat.getInstance(Locale("en", "IN"))
        }

        fun formatRupees(amount: Long): String = "₹" + rupeeFormatter.format(amount)

        fun formatShortDate(millis: Long): String {
            if (millis <= 0L) return "—"
            return SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(millis))
        }
    }
}
