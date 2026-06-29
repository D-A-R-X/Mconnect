package com.manjugroups.m_connect.ui.common

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import coil.load
import coil.transform.CircleCropTransformation
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.SheetStaffPickerBinding
import com.manjugroups.m_connect.network.StaffData

/**
 * Reusable searchable staff picker that matches the app's bottom-sheet design
 * (search bar + styled rows), used in place of a plain AlertDialog list — e.g.
 * for loan nominee selection. Configure with [configure] before showing; tap a
 * row to select (returns via the callback and dismisses).
 */
class StaffPickerBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetStaffPickerBinding? = null
    private val binding get() = _binding!!

    private var pickerTitle: String = "Select"
    private var pickerSubtitle: String = ""
    private var staff: List<StaffData> = emptyList()
    private var onPicked: ((StaffData) -> Unit)? = null

    fun configure(
        title: String,
        subtitle: String = "",
        staff: List<StaffData>,
        onPicked: (StaffData) -> Unit,
    ): StaffPickerBottomSheet {
        this.pickerTitle = title
        this.pickerSubtitle = subtitle
        this.staff = staff
        this.onPicked = onPicked
        return this
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetStaffPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // If recreated (config change) without a configured callback, there's
        // nothing to pick — just close.
        if (onPicked == null) {
            dismissAllowingStateLoss()
            return
        }

        binding.tvPickerTitle.text = pickerTitle
        if (pickerSubtitle.isBlank()) {
            binding.tvPickerSubtitle.visibility = View.GONE
        } else {
            binding.tvPickerSubtitle.text = pickerSubtitle
        }

        binding.btnPickerClose.setOnClickListener { dismiss() }

        binding.etPickerSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderList(s?.toString()?.trim() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        renderList("")
    }

    private fun renderList(query: String) {
        if (_binding == null) return
        val container = binding.llPickerContainer
        container.removeAllViews()

        val filtered = if (query.isEmpty()) staff
        else staff.filter { (it.name ?: "").contains(query, ignoreCase = true) }

        binding.tvPickerEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE

        for (s in filtered) {
            val row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_sheet_employee_row, container, false)

            row.findViewById<TextView>(R.id.tvName).text = s.name ?: "Unknown"
            row.findViewById<TextView>(R.id.tvDetails).text =
                "${s.department ?: "Department"} • ${s.role ?: "Staff"}"

            val ivAvatar = row.findViewById<ImageView>(R.id.ivAvatar)
            val photo = ProfilePhotos.resolve(s.photo)
            if (!photo.isNullOrEmpty()) {
                ivAvatar.load(photo) {
                    crossfade(true)
                    placeholder(R.drawable.bg_attendance_avatar_placeholder)
                    error(R.drawable.bg_attendance_avatar_placeholder)
                    transformations(CircleCropTransformation())
                }
            } else {
                ivAvatar.load(R.drawable.bg_attendance_avatar_placeholder) {
                    transformations(CircleCropTransformation())
                }
            }

            row.findViewById<View>(R.id.viewPresence).visibility =
                if (s.status == "active") View.VISIBLE else View.GONE
            // Tap-to-select: the radio isn't needed.
            row.findViewById<RadioButton>(R.id.rbSelect).visibility = View.GONE

            row.setOnClickListener {
                onPicked?.invoke(s)
                dismiss()
            }
            container.addView(row)
        }
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        val sheet = dialog.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet
        ) ?: return
        // Give the sheet a tall, fixed height so the list scrolls inside it
        // (rather than the whole sheet growing past the screen).
        sheet.layoutParams = sheet.layoutParams.apply {
            height = (resources.displayMetrics.heightPixels * 0.72f).toInt()
        }
        BottomSheetBehavior.from(sheet).apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
