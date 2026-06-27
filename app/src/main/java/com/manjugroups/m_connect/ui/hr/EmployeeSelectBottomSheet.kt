package com.manjugroups.m_connect.ui.hr

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import coil.load
import coil.transform.CircleCropTransformation
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.BottomSheetSelectEmployeeBinding
import com.manjugroups.m_connect.databinding.ItemSheetEmployeeRowBinding
import com.manjugroups.m_connect.network.StaffData

class EmployeeSelectBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSelectEmployeeBinding? = null
    private val binding get() = _binding!!

    var staffList: List<StaffData> = emptyList()
    var selectedStaff: StaffData? = null
    var onEmployeeSelected: ((StaffData) -> Unit)? = null

    private var tempSelectedStaff: StaffData? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                // Fix background appearing at bottom/corners
                bottomSheet.setBackgroundResource(android.R.color.transparent)
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                
                // No drop shadow needed
                bottomSheet.elevation = 0f
            }
        }
        return dialog
    }

    override fun getTheme(): Int {
        return R.style.CustomCameraBottomSheetTheme
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSelectEmployeeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tempSelectedStaff = selectedStaff

        binding.btnSheetClose.setOnClickListener {
            dismiss()
        }

        binding.btnAdd.setOnClickListener {
            tempSelectedStaff?.let { staff ->
                onEmployeeSelected?.invoke(staff)
            }
            dismiss()
        }

        binding.etSearchPeople.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                populateList(s?.toString()?.trim() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        populateList("")
    }

    private fun populateList(query: String) {
        binding.llEmployeeContainer.removeAllViews()
        val filtered = if (query.isEmpty()) {
            staffList
        } else {
            staffList.filter { (it.name ?: "").contains(query, ignoreCase = true) }
        }

        if (filtered.isEmpty()) {
            binding.tvEmptyPeople.visibility = View.VISIBLE
        } else {
            binding.tvEmptyPeople.visibility = View.GONE
        }

        filtered.forEach { staff ->
            val rowBinding = ItemSheetEmployeeRowBinding.inflate(
                LayoutInflater.from(requireContext()),
                binding.llEmployeeContainer,
                false
            )

            rowBinding.tvName.text = staff.name ?: "Unknown Employee"
            rowBinding.tvDetails.text = "${staff.department ?: "Sales Department"} • ${staff.role ?: "Staff"}"
            
            // Set Avatar image
            val resolvedPhoto = com.manjugroups.m_connect.ui.common.ProfilePhotos.resolve(staff.photo)
            if (staff.name?.equals("Mari Muthu.R", ignoreCase = true) == true) {
                rowBinding.ivAvatar.load(R.drawable.avatar_mari_muthu) {
                    transformations(CircleCropTransformation())
                }
            } else if (staff.name?.equals("Sudalai Muthu.R", ignoreCase = true) == true) {
                rowBinding.ivAvatar.load(R.drawable.avatar_sudalai_muthu) {
                    transformations(CircleCropTransformation())
                }
            } else if (!resolvedPhoto.isNullOrEmpty()) {
                rowBinding.ivAvatar.load(resolvedPhoto) {
                    crossfade(true)
                    placeholder(R.drawable.bg_attendance_avatar_placeholder)
                    transformations(CircleCropTransformation())
                }
            } else {
                rowBinding.ivAvatar.load(R.drawable.bg_attendance_avatar_placeholder) {
                    transformations(CircleCropTransformation())
                }
            }

            // Presence indicator green dot - active staff is online/active
            if (staff.status == "active") {
                rowBinding.viewPresence.visibility = View.VISIBLE
            } else {
                rowBinding.viewPresence.visibility = View.GONE
            }

            rowBinding.rbSelect.isChecked = (tempSelectedStaff?.id == staff.id)

            rowBinding.root.setOnClickListener {
                tempSelectedStaff = staff
                // Re-populate list to update RadioButton selection states instantly
                populateList(binding.etSearchPeople.text.toString().trim())
            }

            binding.llEmployeeContainer.addView(rowBinding.root)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
