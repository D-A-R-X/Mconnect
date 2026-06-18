package com.manjugroups.m_connect.ui.library.collections

import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.ItemCollectionBinding
import java.io.File
import java.text.NumberFormat
import java.util.Locale

class CollectionsAdapter : RecyclerView.Adapter<CollectionsAdapter.CollectionVH>() {

    private val items = mutableListOf<CollectionItem>()

    var isAccountantRole: Boolean = false
    var onAcceptClick: ((CollectionItem) -> Unit)? = null
    var onRejectClick: ((CollectionItem) -> Unit)? = null
    var onRectifyClick: ((CollectionItem) -> Unit)? = null
    var onImageClick: ((CollectionItem) -> Unit)? = null
    // Fragment-provided hook: takes the server-side _storage id, resolves
    // it to a signed URL (/api/storage/get-url), and loads the result
    // into the row's thumbnail ImageView via Coil. The fragment owns the
    // coroutine scope + bearer token + a URL cache so we don't refetch
    // the same id on every scroll bind.
    var proofLoader: ((storageId: String, target: ImageView) -> Unit)? = null

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

            // Proof thumbnail.
            //  1. Server-stored proof (`proofStorageId`) → fragment-
            //     provided `proofLoader` resolves the signed URL and
            //     Coil paints into `ivThumbnail`. We clear the view
            //     first so a recycled tile doesn't flash the previous
            //     row's image (or the cash mock) while the URL fetch
            //     is in flight — Coil's crossfade reveals the real
            //     image into a blank tile.
            //  2. Local file still on disk (rectify reuse) → load it.
            //  3. No proof at all → hide the whole thumbnail card so
            //     the row shows a clean null state instead of the
            //     cash drawing.
            val storageId = item.proofStorageId?.takeIf { it.isNotBlank() }
            val localPath = item.photoPath?.takeIf { it.isNotBlank() }
            when {
                storageId != null && proofLoader != null -> {
                    b.cardThumbnail.visibility = View.VISIBLE
                    b.ivThumbnail.setImageDrawable(null)
                    proofLoader?.invoke(storageId, b.ivThumbnail)
                }
                localPath != null && File(localPath).exists() -> {
                    b.cardThumbnail.visibility = View.VISIBLE
                    b.ivThumbnail.setImageURI(Uri.fromFile(File(localPath)))
                }
                else -> {
                    b.cardThumbnail.visibility = View.GONE
                    b.ivThumbnail.setImageDrawable(null)
                }
            }

            // Role and Status visibility configuration
            if (isAccountantRole && item.status == CollectionStatus.PENDING) {
                b.layoutAccountantActions.visibility = View.VISIBLE
            } else {
                b.layoutAccountantActions.visibility = View.GONE
            }

            if (item.status == CollectionStatus.REJECTED) {
                b.layoutRemarks.visibility = View.VISIBLE
                b.tvRemarks.text = item.remarks ?: "No remarks provided"
            } else {
                b.layoutRemarks.visibility = View.GONE
            }

            if (!isAccountantRole && item.status == CollectionStatus.REJECTED) {
                b.btnRectify.visibility = View.VISIBLE
            } else {
                b.btnRectify.visibility = View.GONE
            }

            // Event bindings
            b.btnAccept.setOnClickListener { onAcceptClick?.invoke(item) }
            b.btnReject.setOnClickListener { onRejectClick?.invoke(item) }
            b.btnRectify.setOnClickListener { onRectifyClick?.invoke(item) }
            b.cardThumbnail.setOnClickListener { onImageClick?.invoke(item) }
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
