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
import com.manjugroups.m_connect.databinding.BottomSheetCreateVehicleBinding

class CreateVehicleBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetCreateVehicleBinding? = null
    private val binding get() = _binding!!

    private var onCreateCallback: ((String, String, String, String, String, String) -> Unit)? = null
    private var onSaveCallback: ((String, String, String, String, String, String) -> Unit)? = null

    private var originalVehicleNumberKeyListener: KeyListener? = null
    private var originalVehicleTypeKeyListener: KeyListener? = null
    private var originalCapacityKeyListener: KeyListener? = null
    private var originalDriverNameKeyListener: KeyListener? = null
    private var originalDriverPhoneKeyListener: KeyListener? = null
    private var originalAgencyKeyListener: KeyListener? = null

    companion object {
        private const val ARG_IS_EDIT_MODE = "arg_is_edit_mode"
        private const val ARG_PLATE = "arg_plate"
        private const val ARG_TYPE = "arg_type"
        private const val ARG_CAPACITY = "arg_capacity"
        private const val ARG_NAME = "arg_name"
        private const val ARG_PHONE = "arg_phone"
        private const val ARG_AGENCY = "arg_agency"

        fun newInstance(onCreate: (String, String, String, String, String, String) -> Unit): CreateVehicleBottomSheet {
            val sheet = CreateVehicleBottomSheet()
            sheet.onCreateCallback = onCreate
            return sheet
        }

        fun newEditInstance(
            plate: String,
            type: String,
            capacity: String,
            name: String,
            phone: String,
            agency: String,
            onSave: (String, String, String, String, String, String) -> Unit
        ): CreateVehicleBottomSheet {
            val sheet = CreateVehicleBottomSheet()
            sheet.onSaveCallback = onSave
            val args = Bundle().apply {
                putBoolean(ARG_IS_EDIT_MODE, true)
                putString(ARG_PLATE, plate)
                putString(ARG_TYPE, type)
                putString(ARG_CAPACITY, capacity)
                putString(ARG_NAME, name)
                putString(ARG_PHONE, phone)
                putString(ARG_AGENCY, agency)
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
        _binding = BottomSheetCreateVehicleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? com.google.android.material.bottomsheet.BottomSheetDialog
        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let {
            it.elevation = 0f
            it.background = null
            it.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Save original key listeners
        originalVehicleNumberKeyListener = binding.etVehicleNumber.keyListener
        originalVehicleTypeKeyListener = binding.etVehicleType.keyListener
        originalCapacityKeyListener = binding.etCapacity.keyListener
        originalDriverNameKeyListener = binding.etDriverName.keyListener
        originalDriverPhoneKeyListener = binding.etDriverPhone.keyListener
        originalAgencyKeyListener = binding.etAgency.keyListener

        // Add red asterisks to required fields
        binding.tvLabelVehicleNumber.text = Html.fromHtml("Vehicle Number <font color='#EF4444'>*</font>")
        binding.tvLabelVehicleType.text = Html.fromHtml("Vehicle Type <font color='#EF4444'>*</font>")
        binding.tvLabelCapacity.text = Html.fromHtml("Capacity <font color='#EF4444'>*</font>")
        binding.tvLabelName.text = Html.fromHtml("Name <font color='#EF4444'>*</font>")
        binding.tvLabelPhone.text = Html.fromHtml("Phone Number <font color='#EF4444'>*</font>")
        binding.tvLabelAgency.text = Html.fromHtml("Agency <font color='#EF4444'>*</font>")

        val isEditMode = arguments?.getBoolean(ARG_IS_EDIT_MODE, false) ?: false
        if (isEditMode) {
            // View/Edit Details Mode
            binding.tvCreateVehicleTitle.text = "Vehicle Details"
            binding.btnEditVehicle.visibility = View.VISIBLE
            
            // View Mode Initially: show Close button, hide Edit Buttons
            binding.btnSubmitCreate.visibility = View.VISIBLE
            binding.btnSubmitCreate.text = "Close"
            binding.layoutEditButtons.visibility = View.GONE

            val initialPlate = arguments?.getString(ARG_PLATE) ?: ""
            val initialType = arguments?.getString(ARG_TYPE) ?: ""
            val initialCapacity = arguments?.getString(ARG_CAPACITY) ?: ""
            val initialName = arguments?.getString(ARG_NAME) ?: ""
            val initialPhone = arguments?.getString(ARG_PHONE) ?: ""
            val initialAgency = arguments?.getString(ARG_AGENCY) ?: ""

            binding.etVehicleNumber.setText(initialPlate)
            binding.etVehicleType.setText(initialType)
            binding.etCapacity.setText(initialCapacity)
            binding.etDriverName.setText(initialName)
            binding.etDriverPhone.setText(initialPhone)
            binding.etAgency.setText(initialAgency)

            setFieldsEditable(false)

            var isCurrentlyEditing = false

            binding.btnSubmitCreate.setOnClickListener {
                dismiss() // This acts as Close button in View Mode
            }

            binding.btnEditVehicle.setOnClickListener {
                isCurrentlyEditing = true
                setFieldsEditable(true)
                // Hide Close button, show Edit buttons (Cancel / Save)
                binding.btnSubmitCreate.visibility = View.GONE
                binding.layoutEditButtons.visibility = View.VISIBLE
                
                binding.btnDeactivate.text = "Cancel"
                binding.btnDeactivate.setTextColor(Color.parseColor("#EF4444"))
                binding.btnDeactivate.setBackgroundResource(R.drawable.bg_btn_deactivate_outline_red)
            }

            binding.btnSave.setOnClickListener {
                if (!isCurrentlyEditing) return@setOnClickListener
                val number = binding.etVehicleNumber.text.toString().trim()
                val type = binding.etVehicleType.text.toString().trim()
                val capacity = binding.etCapacity.text.toString().trim()
                val name = binding.etDriverName.text.toString().trim()
                val phone = binding.etDriverPhone.text.toString().trim()
                val agency = binding.etAgency.text.toString().trim()

                if (number.isEmpty() || type.isEmpty() || capacity.isEmpty() || name.isEmpty() || phone.isEmpty() || agency.isEmpty()) {
                    Toast.makeText(requireContext(), "Please fill all fields", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                onSaveCallback?.invoke(number, type, capacity, name, phone, agency)
                dismiss()
            }

            binding.btnDeactivate.setOnClickListener {
                if (isCurrentlyEditing) {
                    isCurrentlyEditing = false
                    setFieldsEditable(false)
                    binding.etVehicleNumber.setText(initialPlate)
                    binding.etVehicleType.setText(initialType)
                    binding.etCapacity.setText(initialCapacity)
                    binding.etDriverName.setText(initialName)
                    binding.etDriverPhone.setText(initialPhone)
                    binding.etAgency.setText(initialAgency)
                    
                    // Show Close button, hide Edit buttons (Cancel / Save)
                    binding.btnSubmitCreate.visibility = View.VISIBLE
                    binding.layoutEditButtons.visibility = View.GONE
                }
            }
        } else {
            // Create Mode: all fields are empty/cleared by default
            binding.tvCreateVehicleTitle.text = "Create Vehicle"
            binding.btnEditVehicle.visibility = View.GONE
            binding.btnSubmitCreate.visibility = View.VISIBLE
            binding.layoutEditButtons.visibility = View.GONE

            binding.etVehicleNumber.setText("")
            binding.etVehicleType.setText("")
            binding.etCapacity.setText("")
            binding.etDriverName.setText("")
            binding.etDriverPhone.setText("")
            binding.etAgency.setText("")

            binding.btnSubmitCreate.setOnClickListener {
                validateAndSubmit()
            }
        }
    }

    private fun setFieldsEditable(editable: Boolean) {
        val editTexts = listOf(
            binding.etVehicleNumber,
            binding.etVehicleType,
            binding.etCapacity,
            binding.etDriverName,
            binding.etDriverPhone,
            binding.etAgency
        )
        if (editable) {
            binding.etVehicleNumber.keyListener = originalVehicleNumberKeyListener
            binding.etVehicleType.keyListener = originalVehicleTypeKeyListener
            binding.etCapacity.keyListener = originalCapacityKeyListener
            binding.etDriverName.keyListener = originalDriverNameKeyListener
            binding.etDriverPhone.keyListener = originalDriverPhoneKeyListener
            binding.etAgency.keyListener = originalAgencyKeyListener

            for (et in editTexts) {
                et.isFocusable = true
                et.isFocusableInTouchMode = true
                et.isCursorVisible = true
                et.isLongClickable = true
            }

            // Edit Mode: Solid green button with white text and white icon
            binding.btnEditVehicle.setBackgroundResource(R.drawable.bg_allocate_button_green)
            binding.btnEditVehicle.setTextColor(Color.WHITE)
            androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_edit_white)?.let { d ->
                val wrapped = androidx.core.graphics.drawable.DrawableCompat.wrap(d).mutate()
                androidx.core.graphics.drawable.DrawableCompat.setTint(wrapped, Color.WHITE)
                binding.btnEditVehicle.setCompoundDrawablesWithIntrinsicBounds(wrapped, null, null, null)
            }
        } else {
            for (et in editTexts) {
                et.keyListener = null
                et.isFocusable = false
                et.isFocusableInTouchMode = false
                et.isCursorVisible = false
                et.isLongClickable = false
            }

            // View Mode: Outlined green button with green text and green icon
            binding.btnEditVehicle.setBackgroundResource(R.drawable.bg_btn_edit_outline_green)
            binding.btnEditVehicle.setTextColor(Color.parseColor("#22C55E"))
            androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_edit_white)?.let { d ->
                val wrapped = androidx.core.graphics.drawable.DrawableCompat.wrap(d).mutate()
                androidx.core.graphics.drawable.DrawableCompat.setTint(wrapped, Color.parseColor("#22C55E"))
                binding.btnEditVehicle.setCompoundDrawablesWithIntrinsicBounds(wrapped, null, null, null)
            }
        }
    }

    private fun validateAndSubmit() {
        val number = binding.etVehicleNumber.text.toString().trim()
        val type = binding.etVehicleType.text.toString().trim()
        val capacity = binding.etCapacity.text.toString().trim()
        val name = binding.etDriverName.text.toString().trim()
        val phone = binding.etDriverPhone.text.toString().trim()
        val agency = binding.etAgency.text.toString().trim()

        if (number.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter vehicle number", Toast.LENGTH_SHORT).show()
            return
        }
        if (type.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter vehicle type", Toast.LENGTH_SHORT).show()
            return
        }
        if (capacity.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter capacity", Toast.LENGTH_SHORT).show()
            return
        }
        if (name.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter driver name", Toast.LENGTH_SHORT).show()
            return
        }
        if (phone.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter phone number", Toast.LENGTH_SHORT).show()
            return
        }
        if (agency.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter agency", Toast.LENGTH_SHORT).show()
            return
        }

        onCreateCallback?.invoke(number, type, capacity, name, phone, agency)
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
