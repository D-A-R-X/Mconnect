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
    var status: String, // "Docs Pending" or "App Received"
    var pills: List<String>
)

class LoanDeskAdapter(
    private var items: List<LoanDeskItem>,
    private val onItemClick: (LoanDeskItem) -> Unit
) : RecyclerView.Adapter<LoanDeskAdapter.ViewHolder>() {

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
        holder.bind(item, onItemClick)
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

        fun bind(item: LoanDeskItem, onItemClick: (LoanDeskItem) -> Unit) {
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
            if (item.status == "Docs Pending") {
                statusBadgeContainer.setBackgroundResource(R.drawable.bg_badge_pending)
                ivStatusIcon.setImageResource(R.drawable.ic_clock_bold)
                ivStatusIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#B93815"))
                tvStatus.text = "Docs Pending"
                tvStatus.setTextColor(Color.parseColor("#B93815"))
            } else {
                statusBadgeContainer.setBackgroundResource(R.drawable.bg_badge_received)
                ivStatusIcon.setImageResource(R.drawable.ic_leave_action_check)
                ivStatusIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#B42318"))
                tvStatus.text = "App Received"
                tvStatus.setTextColor(Color.parseColor("#B42318"))
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

            itemView.setOnClickListener {
                onItemClick(item)
            }
        }
    }
}
