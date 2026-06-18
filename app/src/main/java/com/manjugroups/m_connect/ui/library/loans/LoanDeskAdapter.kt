package com.manjugroups.m_connect.ui.library.loans

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.manjugroups.m_connect.R

data class LoanDeskItem(
    val id: String,
    val name: String,
    val phone: String,
    val amount: String,
    val location: String,
    val date: String,
    var status: String, // "Docs Pending", "App Received", "Approved", "Rejected"
    var pills: List<String>,
    var rejectionRemarks: String? = null
)

class LoanDeskAdapter(
    private var items: List<LoanDeskItem>,
    private val onItemClick: (LoanDeskItem) -> Unit,
    private val onAcceptClick: (LoanDeskItem) -> Unit,
    private val onRejectClick: (LoanDeskItem) -> Unit,
    private val onRectifyClick: (LoanDeskItem) -> Unit
) : RecyclerView.Adapter<LoanDeskAdapter.ViewHolder>() {

    private var isLegalTeamMode = true // Default matches start state

    fun setLegalTeamMode(enabled: Boolean) {
        this.isLegalTeamMode = enabled
        notifyDataSetChanged()
    }

    fun updateList(newItems: List<LoanDeskItem>) {
        this.items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_loan_desk_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, isLegalTeamMode, onItemClick, onAcceptClick, onRejectClick, onRectifyClick)
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvInitial: TextView = itemView.findViewById(R.id.tvInitial)
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvPhone: TextView = itemView.findViewById(R.id.tvPhone)
        private val statusBadgeContainer: View = itemView.findViewById(R.id.statusBadgeContainer)
        private val ivStatusIcon: ImageView = itemView.findViewById(R.id.ivStatusIcon)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
        private val tvAmount: TextView = itemView.findViewById(R.id.tvAmount)
        private val tvLocation: TextView = itemView.findViewById(R.id.tvLocation)
        private val tvDate: TextView = itemView.findViewById(R.id.tvDate)
        
        private val pillPan: View = itemView.findViewById(R.id.pillPan)
        private val pillAadhaar: View = itemView.findViewById(R.id.pillAadhaar)
        private val pillCount: View = itemView.findViewById(R.id.pillCount)
        private val tvPillCountText: TextView = itemView.findViewById(R.id.tvPillCountText)

        private val layoutRemarks: View = itemView.findViewById(R.id.layoutRemarks)
        private val tvRemarks: TextView = itemView.findViewById(R.id.tvRemarks)
        private val layoutLegalActions: View = itemView.findViewById(R.id.layoutLegalActions)
        private val btnAccept: View = itemView.findViewById(R.id.btnAccept)
        private val btnReject: View = itemView.findViewById(R.id.btnReject)
        private val btnRectify: View = itemView.findViewById(R.id.btnRectify)

        fun bind(
            item: LoanDeskItem,
            isLegalTeamMode: Boolean,
            onItemClick: (LoanDeskItem) -> Unit,
            onAcceptClick: (LoanDeskItem) -> Unit,
            onRejectClick: (LoanDeskItem) -> Unit,
            onRectifyClick: (LoanDeskItem) -> Unit
        ) {
            // Set initials and select background tint based on name initials
            val initials = item.name.split(" ").mapNotNull { it.firstOrNull() }.joinToString("").take(2).uppercase()
            tvInitial.text = initials
            
            // Assign different background colors to initials circles
            val avatarColor = when (item.id) {
                "1" -> Color.parseColor("#0B61CA") // Blue
                "2" -> Color.parseColor("#9333EA") // Purple
                else -> Color.parseColor("#F79009") // Orange
            }
            tvInitial.backgroundTintList = ColorStateList.valueOf(avatarColor)

            tvName.text = item.name
            tvPhone.text = item.phone
            tvAmount.text = item.amount
            tvLocation.text = item.location
            tvDate.text = item.date

            // Style status badge
            when (item.status) {
                "Docs Pending" -> {
                    statusBadgeContainer.setBackgroundResource(R.drawable.bg_badge_pending)
                    ivStatusIcon.setImageResource(R.drawable.ic_clock_bold)
                    ivStatusIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#B93815"))
                    tvStatus.text = "Docs Pending"
                    tvStatus.setTextColor(Color.parseColor("#B93815"))
                }
                "App Received" -> {
                    statusBadgeContainer.setBackgroundResource(R.drawable.bg_badge_received)
                    ivStatusIcon.setImageResource(R.drawable.ic_leave_action_check)
                    ivStatusIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#B42318"))
                    tvStatus.text = "App Received"
                    tvStatus.setTextColor(Color.parseColor("#B42318"))
                }
                "Approved" -> {
                    statusBadgeContainer.setBackgroundResource(R.drawable.bg_badge_success)
                    ivStatusIcon.setImageResource(R.drawable.ic_leave_action_check)
                    ivStatusIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#12B76A"))
                    tvStatus.text = "Approved"
                    tvStatus.setTextColor(Color.parseColor("#12B76A"))
                }
                "Rejected" -> {
                    statusBadgeContainer.setBackgroundResource(R.drawable.bg_badge_error)
                    ivStatusIcon.setImageResource(R.drawable.ic_outcome_close)
                    ivStatusIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#F04438"))
                    tvStatus.text = "Rejected"
                    tvStatus.setTextColor(Color.parseColor("#F04438"))
                }
            }

            // Bind document pills
            pillPan.visibility = if (item.pills.contains("PAN")) View.VISIBLE else View.GONE
            pillAadhaar.visibility = if (item.pills.contains("Aadhaar")) View.VISIBLE else View.GONE
            
            val countPill = item.pills.firstOrNull { it.startsWith("+") }
            if (countPill != null) {
                pillCount.visibility = View.VISIBLE
                tvPillCountText.text = countPill
            } else {
                pillCount.visibility = View.GONE
            }

            // Role and Status adaptive layout
            if (isLegalTeamMode) {
                btnRectify.visibility = View.GONE
                layoutRemarks.visibility = View.GONE
                
                if (item.status == "App Received") {
                    layoutLegalActions.visibility = View.VISIBLE
                    btnAccept.setOnClickListener { onAcceptClick(item) }
                    btnReject.setOnClickListener { onRejectClick(item) }
                } else {
                    layoutLegalActions.visibility = View.GONE
                }
                itemView.setOnClickListener(null)
            } else {
                layoutLegalActions.visibility = View.GONE
                
                if (item.status == "Rejected") {
                    layoutRemarks.visibility = View.VISIBLE
                    tvRemarks.text = item.rejectionRemarks ?: "No remarks specified."
                    btnRectify.visibility = View.VISIBLE
                    btnRectify.setOnClickListener { onRectifyClick(item) }
                    itemView.setOnClickListener { onRectifyClick(item) }
                } else {
                    layoutRemarks.visibility = View.GONE
                    btnRectify.visibility = View.GONE
                    
                    if (item.status == "Docs Pending") {
                        itemView.setOnClickListener { onItemClick(item) }
                    } else {
                        itemView.setOnClickListener(null)
                    }
                }
            }
        }
    }
}
