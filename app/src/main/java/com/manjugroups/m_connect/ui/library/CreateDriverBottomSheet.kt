package com.manjugroups.m_connect.ui.library

import android.graphics.Color
import android.os.Bundle
import android.text.Html
import android.text.method.KeyListener
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.BottomSheetCreateDriverBinding

class CreateDriverBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetCreateDriverBinding? = null
    private val binding get() = _binding!!

    private var onCreateCallback: ((String, String, String) -> Unit)? = null
    private var onSaveCallback: ((String, String, String) -> Unit)? = null
    private var onDeactivateCallback: (() -> Unit)? = null

    private var originalNameKeyListener: KeyListener? = null
    private var originalPhoneKeyListener: KeyListener? = null
    private var originalAddressKeyListener: KeyListener? = null

    companion object {
        private const val ARG_IS_EDIT_MODE = "arg_is_edit_mode"
        private const val ARG_NAME = "arg_name"
        private const val ARG_PHONE = "arg_phone"
        private const val ARG_ADDRESS = "arg_address"
        private const val ARG_STATUS = "arg_status"

        fun newInstance(onCreate: (String, String, String) -> Unit): CreateDriverBottomSheet {
            val sheet = CreateDriverBottomSheet()
            sheet.onCreateCallback = onCreate
            return sheet
        }

        fun newEditInstance(
            name: String,
            phone: String,
            address: String,
            status: String,
            onSave: (String, String, String) -> Unit,
            onDeactivate: () -> Unit
        ): CreateDriverBottomSheet {
            val sheet = CreateDriverBottomSheet()
            sheet.onSaveCallback = onSave
            sheet.onDeactivateCallback = onDeactivate
            val args = Bundle().apply {
                putBoolean(ARG_IS_EDIT_MODE, true)
                putString(ARG_NAME, name)
                putString(ARG_PHONE, phone)
                putString(ARG_ADDRESS, address)
                putString(ARG_STATUS, status)
            }
            sheet.arguments = args
            return sheet
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.CustomCameraBottomSheetTheme)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetCreateDriverBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        // Lift the sheet above the soft keyboard so the focused field stays
        // visible while typing.
        dialog?.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        val dialog = dialog as? com.google.android.material.bottomsheet.BottomSheetDialog
        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let {
            it.elevation = 0f
            it.background = null
            it.setBackgroundColor(Color.TRANSPARENT)
            val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(it)
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Save original key listeners
        originalNameKeyListener = binding.etDriverName.keyListener
        originalPhoneKeyListener = binding.etDriverPhone.keyListener
        originalAddressKeyListener = binding.etDriverAddress.keyListener

        // Add red asterisks
        binding.tvLabelName.text = Html.fromHtml("Name <font color='#EF4444'>*</font>")
        binding.tvLabelPhone.text = Html.fromHtml("Phone Number <font color='#EF4444'>*</font>")
        binding.tvLabelAddress.text = Html.fromHtml("Address <font color='#EF4444'>*</font>")

        val isEditMode = arguments?.getBoolean(ARG_IS_EDIT_MODE, false) ?: false
        if (isEditMode) {
            binding.tvCreateDriverTitle.text = "Edit Drivers"
            binding.btnEditDriver.visibility = View.VISIBLE
            binding.btnSubmitCreate.visibility = View.GONE
            binding.layoutEditButtons.visibility = View.VISIBLE

            val initialName = arguments?.getString(ARG_NAME) ?: ""
            val initialPhone = arguments?.getString(ARG_PHONE) ?: ""
            val initialAddress = arguments?.getString(ARG_ADDRESS) ?: ""
            val status = arguments?.getString(ARG_STATUS) ?: "Active"

            binding.etDriverName.setText(initialName)
            binding.etDriverPhone.setText(initialPhone)
            binding.etDriverAddress.setText(initialAddress)

            setFieldsEditable(false)

            var isCurrentlyEditing = false

            if (status == "Inactive") {
                binding.btnDeactivate.text = "Activate"
                binding.btnDeactivate.setTextColor(Color.parseColor("#22C55E"))
                binding.btnDeactivate.setBackgroundResource(R.drawable.bg_btn_edit_outline_green)
            } else {
                binding.btnDeactivate.text = "Deactivate"
                binding.btnDeactivate.setTextColor(Color.parseColor("#EF4444"))
                binding.btnDeactivate.setBackgroundResource(R.drawable.bg_btn_deactivate_outline_red)
            }

            binding.btnEditDriver.setOnClickListener {
                isCurrentlyEditing = true
                setFieldsEditable(true)
                binding.btnDeactivate.text = "Cancel"
                binding.btnDeactivate.setTextColor(Color.parseColor("#EF4444"))
                binding.btnDeactivate.setBackgroundResource(R.drawable.bg_btn_deactivate_outline_red)
            }

            binding.btnSave.setOnClickListener {
                if (!isCurrentlyEditing) return@setOnClickListener
                val name = binding.etDriverName.text.toString().trim()
                val phone = binding.etDriverPhone.text.toString().trim()
                val address = binding.etDriverAddress.text.toString().trim()

                if (name.isEmpty() || phone.isEmpty() || address.isEmpty()) {
                    Toast.makeText(requireContext(), "Please fill all fields", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                onSaveCallback?.invoke(name, phone, address)
                dismiss()
            }

            binding.btnDeactivate.setOnClickListener {
                if (isCurrentlyEditing) {
                    isCurrentlyEditing = false
                    setFieldsEditable(false)
                    binding.etDriverName.setText(initialName)
                    binding.etDriverPhone.setText(initialPhone)
                    binding.etDriverAddress.setText(initialAddress)
                    if (status == "Inactive") {
                        binding.btnDeactivate.text = "Activate"
                        binding.btnDeactivate.setTextColor(Color.parseColor("#22C55E"))
                        binding.btnDeactivate.setBackgroundResource(R.drawable.bg_btn_edit_outline_green)
                    } else {
                        binding.btnDeactivate.text = "Deactivate"
                        binding.btnDeactivate.setTextColor(Color.parseColor("#EF4444"))
                        binding.btnDeactivate.setBackgroundResource(R.drawable.bg_btn_deactivate_outline_red)
                    }
                } else {
                    onDeactivateCallback?.invoke()
                    dismiss()
                }
            }

        } else {
            binding.tvCreateDriverTitle.text = "Create Drivers"
            binding.btnEditDriver.visibility = View.GONE
            binding.btnSubmitCreate.visibility = View.VISIBLE
            binding.layoutEditButtons.visibility = View.GONE

            binding.etDriverName.setText("")
            binding.etDriverPhone.setText("")
            binding.etDriverAddress.setText("")

            binding.btnSubmitCreate.setOnClickListener {
                val name = binding.etDriverName.text.toString().trim()
                val phone = binding.etDriverPhone.text.toString().trim()
                val address = binding.etDriverAddress.text.toString().trim()

                if (name.isEmpty()) {
                    Toast.makeText(requireContext(), "Please enter name", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (phone.isEmpty()) {
                    Toast.makeText(requireContext(), "Please enter phone number", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (address.isEmpty()) {
                    Toast.makeText(requireContext(), "Please enter address", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                onCreateCallback?.invoke(name, phone, address)
                dismiss()
            }
        }
    }

    private fun setFieldsEditable(editable: Boolean) {
        if (editable) {
            binding.etDriverName.keyListener = originalNameKeyListener
            binding.etDriverPhone.keyListener = originalPhoneKeyListener
            binding.etDriverAddress.keyListener = originalAddressKeyListener

            val editTexts = listOf(binding.etDriverName, binding.etDriverPhone, binding.etDriverAddress)
            for (et in editTexts) {
                et.isFocusable = true
                et.isFocusableInTouchMode = true
                et.isCursorVisible = true
                et.isLongClickable = true
            }

            // Edit Mode: Solid green button with white text and white icon
            binding.btnEditDriver.setBackgroundResource(R.drawable.bg_allocate_button_green)
            binding.btnEditDriver.setTextColor(Color.WHITE)
            androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_edit_white)?.let { d ->
                val wrapped = androidx.core.graphics.drawable.DrawableCompat.wrap(d).mutate()
                androidx.core.graphics.drawable.DrawableCompat.setTint(wrapped, Color.WHITE)
                binding.btnEditDriver.setCompoundDrawablesWithIntrinsicBounds(wrapped, null, null, null)
            }
        } else {
            val editTexts = listOf(binding.etDriverName, binding.etDriverPhone, binding.etDriverAddress)
            for (et in editTexts) {
                et.keyListener = null
                et.isFocusable = false
                et.isFocusableInTouchMode = false
                et.isCursorVisible = false
                et.isLongClickable = false
            }

            // View Mode: Outlined green button with green text and green icon
            binding.btnEditDriver.setBackgroundResource(R.drawable.bg_btn_edit_outline_green)
            binding.btnEditDriver.setTextColor(Color.parseColor("#22C55E"))
            androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_edit_white)?.let { d ->
                val wrapped = androidx.core.graphics.drawable.DrawableCompat.wrap(d).mutate()
                androidx.core.graphics.drawable.DrawableCompat.setTint(wrapped, Color.parseColor("#22C55E"))
                binding.btnEditDriver.setCompoundDrawablesWithIntrinsicBounds(wrapped, null, null, null)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
