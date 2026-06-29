package com.manjugroups.m_connect.ui.hr

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import coil.load
import coil.transform.CircleCropTransformation
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.BottomSheetSelectEmployeeBinding
import com.manjugroups.m_connect.databinding.ItemSheetEmployeeRowBinding
import com.manjugroups.m_connect.network.StaffData

class EmployeeSelectBottomSheet : DialogFragment() {

    private var _binding: BottomSheetSelectEmployeeBinding? = null
    private val binding get() = _binding!!

    var staffList: List<StaffData> = emptyList()
    var selectedStaff: StaffData? = null
    var onEmployeeSelected: ((StaffData) -> Unit)? = null

    private var tempSelectedStaff: StaffData? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, android.R.style.Theme_Translucent_NoTitleBar)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSelectEmployeeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        try {
            dialog?.window?.let { window ->
                window.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT
                )
                window.setGravity(Gravity.BOTTOM)
                window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                window.setDimAmount(0.5f)
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
        } catch (_: Exception) {}
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tempSelectedStaff = selectedStaff

        binding.btnSheetClose.setOnClickListener {
            dismissAllowingStateLoss()
        }

        binding.btnAdd.setOnClickListener {
            tempSelectedStaff?.let { staff ->
                onEmployeeSelected?.invoke(staff)
            }
            dismissAllowingStateLoss()
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
        if (_binding == null) return
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
            if (!resolvedPhoto.isNullOrEmpty()) {
                rowBinding.ivAvatar.load(resolvedPhoto) {
                    crossfade(true)
                    placeholder(R.drawable.bg_attendance_avatar_placeholder)
                    error(R.drawable.bg_attendance_avatar_placeholder)
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
