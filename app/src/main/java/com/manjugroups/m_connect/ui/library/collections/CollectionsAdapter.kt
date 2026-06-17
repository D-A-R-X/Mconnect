package com.manjugroups.m_connect.ui.library.collections

import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.ItemCollectionBinding
import java.io.File
import java.text.NumberFormat
import java.util.Locale

class CollectionsAdapter : RecyclerView.Adapter<CollectionsAdapter.CollectionVH>() {

    private val items = mutableListOf<CollectionItem>()

    fun submit(list: List<CollectionItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CollectionVH {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemCollectionBinding.inflate(inflater, parent, false)
        return CollectionVH(binding)
    }

    override fun onBindViewHolder(holder: CollectionVH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class CollectionVH(private val b: ItemCollectionBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: CollectionItem) {
            b.tvAmount.text = formatRupees(item.amount)
            b.tvSiteName.text = item.bookingName.split(" - ").firstOrNull() ?: item.bookingName
            b.tvPaymentMode.text = item.paymentMode
            b.tvRefId.text = item.refId
            b.tvDate.text = item.dateString

            // Status Badge styling
            when (item.status) {
                CollectionStatus.APPROVED -> {
                    b.statusBadge.setBackgroundResource(R.drawable.bg_badge_success)
                    b.vStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#12B76A"))
                    b.tvStatus.text = "Approved"
                    b.tvStatus.setTextColor(Color.parseColor("#12B76A"))
                }
                CollectionStatus.REJECTED -> {
                    b.statusBadge.setBackgroundResource(R.drawable.bg_badge_error)
                    b.vStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F04438"))
                    b.tvStatus.text = "Rejected"
                    b.tvStatus.setTextColor(Color.parseColor("#F04438"))
                }
                CollectionStatus.PENDING -> {
                    b.statusBadge.setBackgroundResource(R.drawable.bg_badge_warning)
                    b.vStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F79009"))
                    b.tvStatus.text = "Pending"
                    b.tvStatus.setTextColor(Color.parseColor("#F79009"))
                }
            }

            // Proof thumbnail
            if (item.photoPath != null) {
                val file = File(item.photoPath)
                if (file.exists()) {
                    b.cardThumbnail.visibility = View.VISIBLE
                    b.ivThumbnail.setImageURI(Uri.fromFile(file))
                } else {
                    b.cardThumbnail.visibility = View.VISIBLE
                    b.ivThumbnail.setImageResource(R.drawable.ic_cash_proof)
                }
            } else {
                b.cardThumbnail.visibility = View.VISIBLE
                b.ivThumbnail.setImageResource(R.drawable.ic_cash_proof)
            }
        }
    }

    companion object {
        private val rupeeFormatter: NumberFormat by lazy {
            NumberFormat.getInstance(Locale("en", "IN"))
        }

        fun formatRupees(amount: Double): String {
            val formatted = rupeeFormatter.format(amount.toLong())
            return "₹$formatted"
        }
    }
}
