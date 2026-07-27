package com.manjugroups.m_connect.ui.library

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.BottomSheetCreateAgencyStaffBinding
import com.manjugroups.m_connect.network.TravelDeskAgencyStaff

data class AgencyStaffFormResult(
    val name: String,
    val phone: String,
    val whatsapp: String,
)

class CreateAgencyStaffBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetCreateAgencyStaffBinding? = null
    private val binding get() = _binding!!
    private var staff: TravelDeskAgencyStaff? = null
    private var onSave: ((AgencyStaffFormResult) -> Unit)? = null
    private var onStatusChange: ((String) -> Unit)? = null

    companion object {
        fun newInstance(
            onSave: (AgencyStaffFormResult) -> Unit,
        ) = CreateAgencyStaffBottomSheet().apply {
            this.onSave = onSave
        }

        fun newEditInstance(
            staff: TravelDeskAgencyStaff,
            onSave: (AgencyStaffFormResult) -> Unit,
            onStatusChange: (String) -> Unit,
        ) = CreateAgencyStaffBottomSheet().apply {
            this.staff = staff
            this.onSave = onSave
            this.onStatusChange = onStatusChange
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.CustomCameraBottomSheetTheme)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetCreateAgencyStaffBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        val dialog = dialog as? com.google.android.material.bottomsheet.BottomSheetDialog
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let {
            it.setBackgroundColor(Color.TRANSPARENT)
            com.google.android.material.bottomsheet.BottomSheetBehavior.from(it).apply {
                state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val current = staff
        binding.tvStaffFormTitle.text = if (current == null) "Create Staff" else "Edit Staff"
        binding.etStaffName.setText(current?.name.orEmpty())
        binding.etStaffPhone.setText(current?.phone.orEmpty())
        binding.etStaffWhatsapp.setText(current?.whatsapp.orEmpty())
        binding.btnStaffStatus.visibility = if (current == null) View.GONE else View.VISIBLE
        binding.btnStaffStatus.text =
            if (current?.status.equals("inactive", ignoreCase = true)) "Activate" else "Deactivate"
        if (current?.status.equals("inactive", ignoreCase = true)) {
            binding.btnStaffStatus.setTextColor(Color.parseColor("#22C55E"))
            binding.btnStaffStatus.setBackgroundResource(R.drawable.bg_btn_edit_outline_green)
        }

        fun applySameAsPhone(checked: Boolean) {
            binding.etStaffWhatsapp.isEnabled = !checked
            binding.etStaffWhatsapp.alpha = if (checked) 0.6f else 1f
            if (checked) binding.etStaffWhatsapp.setText(binding.etStaffPhone.text)
        }
        binding.cbStaffWhatsappSame.isChecked =
            if (current == null) true
            else !current.whatsapp.isNullOrBlank() && current.whatsapp == current.phone
        applySameAsPhone(binding.cbStaffWhatsappSame.isChecked)
        binding.cbStaffWhatsappSame.setOnCheckedChangeListener { _, checked ->
            applySameAsPhone(checked)
        }
        binding.etStaffPhone.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                if (binding.cbStaffWhatsappSame.isChecked) {
                    binding.etStaffWhatsapp.setText(s)
                }
            }
        })

        binding.btnSaveStaff.setOnClickListener {
            val result = collectForm() ?: return@setOnClickListener
            onSave?.invoke(result)
            dismiss()
        }
        binding.btnStaffStatus.setOnClickListener {
            val next = if (current?.status.equals("inactive", ignoreCase = true)) {
                "active"
            } else {
                "inactive"
            }
            onStatusChange?.invoke(next)
            dismiss()
        }
    }

    private fun collectForm(): AgencyStaffFormResult? {
        val name = binding.etStaffName.text.toString().trim()
        val phone = binding.etStaffPhone.text.toString().filter(Char::isDigit).takeLast(10)
        val whatsapp = binding.etStaffWhatsapp.text.toString()
            .filter(Char::isDigit)
            .takeLast(10)
        if (name.isBlank()) {
            binding.etStaffName.error = "Name is required"
            return null
        }
        if (!phone.matches(Regex("[6-9]\\d{9}"))) {
            binding.etStaffPhone.error = "Enter a valid 10-digit mobile number"
            return null
        }
        if (whatsapp.isNotBlank() && !whatsapp.matches(Regex("[6-9]\\d{9}"))) {
            binding.etStaffWhatsapp.error = "Enter a valid WhatsApp number"
            return null
        }
        return AgencyStaffFormResult(name, phone, whatsapp)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
