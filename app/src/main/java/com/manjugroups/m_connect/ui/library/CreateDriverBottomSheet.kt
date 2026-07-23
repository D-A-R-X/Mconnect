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
    private var prefillName: String = ""

    /** Seed the name field (create mode) — used when the dispatcher typed a
     *  driver that wasn't on the roster and chose "Add new driver". */
    fun prefillName(name: String) { prefillName = name.trim() }
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

    private var isEditModeFlag = false
    private var initialSnapshot = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // No explicit Edit action — the form is directly editable.
        binding.btnEditDriver.visibility = View.GONE

        // Add red asterisks
        binding.tvLabelName.text = Html.fromHtml("Name <font color='#EF4444'>*</font>")
        binding.tvLabelPhone.text = Html.fromHtml("Phone Number <font color='#EF4444'>*</font>")
        binding.tvLabelAddress.text = Html.fromHtml("Address <font color='#EF4444'>*</font>")

        isEditModeFlag = arguments?.getBoolean(ARG_IS_EDIT_MODE, false) ?: false
        if (isEditModeFlag) {
            binding.tvCreateDriverTitle.text = "Edit Drivers"
            binding.btnSubmitCreate.visibility = View.GONE
            binding.layoutEditButtons.visibility = View.VISIBLE

            val initialName = arguments?.getString(ARG_NAME) ?: ""
            val initialPhone = arguments?.getString(ARG_PHONE) ?: ""
            val initialAddress = arguments?.getString(ARG_ADDRESS) ?: ""
            val status = arguments?.getString(ARG_STATUS) ?: "Active"

            binding.etDriverName.setText(initialName)
            binding.etDriverPhone.setText(initialPhone)
            binding.etDriverAddress.setText(initialAddress)

            // Deactivate / Activate stays available (independent of edits).
            if (status == "Inactive") {
                binding.btnDeactivate.text = "Activate"
                binding.btnDeactivate.setTextColor(Color.parseColor("#22C55E"))
                binding.btnDeactivate.setBackgroundResource(R.drawable.bg_btn_edit_outline_green)
            } else {
                binding.btnDeactivate.text = "Deactivate"
                binding.btnDeactivate.setTextColor(Color.parseColor("#EF4444"))
                binding.btnDeactivate.setBackgroundResource(R.drawable.bg_btn_deactivate_outline_red)
            }
            binding.btnDeactivate.setOnClickListener {
                onDeactivateCallback?.invoke()
                dismiss()
            }

            // Save: grey + disabled until a field changes, then green + clickable.
            binding.btnSave.setOnClickListener {
                if (driverSnapshot() == initialSnapshot) return@setOnClickListener
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
        } else {
            binding.tvCreateDriverTitle.text = "Create Drivers"
            binding.btnSubmitCreate.visibility = View.VISIBLE
            binding.layoutEditButtons.visibility = View.GONE

            binding.etDriverName.setText(prefillName)
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

        // Snapshot after seeding, then watch the fields for the dirty state.
        initialSnapshot = driverSnapshot()
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = refreshSaveState()
        }
        listOf(binding.etDriverName, binding.etDriverPhone, binding.etDriverAddress)
            .forEach { it.addTextChangedListener(watcher) }
        refreshSaveState()
    }

    private fun driverSnapshot(): String =
        listOf(binding.etDriverName, binding.etDriverPhone, binding.etDriverAddress)
            .joinToString("|") { it.text.toString().trim() }

    /** Grey + disabled Save until something changes, then green + clickable.
     *  Only applies in edit mode; create mode's Submit is always active. */
    private fun refreshSaveState() {
        if (_binding == null || !isEditModeFlag) return
        val dirty = driverSnapshot() != initialSnapshot
        val btn = binding.btnSave
        btn.isEnabled = dirty
        if (dirty) {
            btn.setBackgroundResource(R.drawable.bg_allocate_button_green)
            btn.setTextColor(Color.WHITE)
        } else {
            btn.setBackgroundResource(R.drawable.bg_btn_disabled_grey)
            btn.setTextColor(Color.parseColor("#9CA3AF"))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
