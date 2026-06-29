package com.manjugroups.m_connect.ui.common

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.network.StaffData

/**
 * Reusable searchable staff picker matching the app's bottom-sheet design
 * (search bar + styled rows), used in place of a plain AlertDialog list — e.g.
 * for loan nominee selection.
 *
 * Implemented as a plain [BottomSheetDialog] (a Dialog, not a DialogFragment) so
 * it can be shown safely from inside another bottom sheet, and backed by a
 * [RecyclerView] so a large staff list renders instantly and avatars load
 * lazily — inflating every row + firing every image load up front blocked the
 * main thread and ANR'd ("crashed") for big staff lists.
 */
object StaffPickerBottomSheet {

    fun show(
        context: Context,
        title: String,
        subtitle: String = "",
        staff: List<StaffData>,
        onPicked: (StaffData) -> Unit,
    ) {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.sheet_staff_picker, null)
        dialog.setContentView(view)

        view.findViewById<TextView>(R.id.tvPickerTitle).text = title
        val subtitleView = view.findViewById<TextView>(R.id.tvPickerSubtitle)
        if (subtitle.isBlank()) subtitleView.visibility = View.GONE
        else subtitleView.text = subtitle

        val emptyView = view.findViewById<TextView>(R.id.tvPickerEmpty)
        val rv = view.findViewById<RecyclerView>(R.id.rvPickerList)
        rv.layoutManager = LinearLayoutManager(context)
        rv.setHasFixedSize(true)
        val adapter = StaffAdapter { picked ->
            onPicked(picked)
            dialog.dismiss()
        }
        rv.adapter = adapter

        fun applyFilter(query: String) {
            val filtered = if (query.isEmpty()) staff
            else staff.filter { (it.name ?: "").contains(query, ignoreCase = true) }
            emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            adapter.submit(filtered)
        }
        applyFilter("")

        view.findViewById<ImageView>(R.id.btnPickerClose).setOnClickListener { dialog.dismiss() }
        view.findViewById<android.widget.EditText>(R.id.etPickerSearch)
            .addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    applyFilter(s?.toString()?.trim() ?: "")
                }
                override fun afterTextChanged(s: Editable?) {}
            })

        // Tall, fixed height so the list scrolls inside the sheet.
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            ) ?: return@setOnShowListener
            sheet.layoutParams = sheet.layoutParams.apply {
                height = (context.resources.displayMetrics.heightPixels * 0.72f).toInt()
            }
            BottomSheetBehavior.from(sheet).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
        }

        dialog.show()
    }

    private class StaffAdapter(
        private val onPick: (StaffData) -> Unit,
    ) : RecyclerView.Adapter<StaffVH>() {

        private var items: List<StaffData> = emptyList()

        fun submit(list: List<StaffData>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StaffVH {
            val row = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_sheet_employee_row, parent, false)
            // Tap-to-select; the radio isn't used in this picker.
            row.findViewById<RadioButton>(R.id.rbSelect).visibility = View.GONE
            return StaffVH(row)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: StaffVH, position: Int) {
            val s = items[position]
            holder.tvName.text = s.name ?: "Unknown"
            holder.tvDetails.text = "${s.department ?: "Department"} • ${s.role ?: "Staff"}"

            val photo = ProfilePhotos.resolve(s.photo)
            if (!photo.isNullOrEmpty()) {
                holder.ivAvatar.load(photo) {
                    crossfade(true)
                    placeholder(R.drawable.bg_attendance_avatar_placeholder)
                    error(R.drawable.bg_attendance_avatar_placeholder)
                    transformations(CircleCropTransformation())
                }
            } else {
                holder.ivAvatar.load(R.drawable.bg_attendance_avatar_placeholder) {
                    transformations(CircleCropTransformation())
                }
            }

            holder.viewPresence.visibility = if (s.status == "active") View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener { onPick(s) }
        }
    }

    private class StaffVH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvDetails: TextView = view.findViewById(R.id.tvDetails)
        val ivAvatar: ImageView = view.findViewById(R.id.ivAvatar)
        val viewPresence: View = view.findViewById(R.id.viewPresence)
    }
}
