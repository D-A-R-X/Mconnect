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

    private var onCreateCallback: ((VehicleFormResult) -> Unit)? = null
    private var onSaveCallback: ((VehicleFormResult) -> Unit)? = null

    private var originalVehicleNumberKeyListener: KeyListener? = null
    private var originalVehicleTypeKeyListener: KeyListener? = null
    private var originalCapacityKeyListener: KeyListener? = null
    private var originalDriverNameKeyListener: KeyListener? = null
    private var originalDriverPhoneKeyListener: KeyListener? = null
    private var originalAgencyKeyListener: KeyListener? = null

    companion object {
        private const val ARG_IS_EDIT_MODE = "arg_is_edit_mode"
        private const val ARG_PLATE = "arg_plate"
        private const val ARG_MAKE = "arg_make"
        private const val ARG_MODEL = "arg_model"
        private const val ARG_MODEL_YEAR = "arg_model_year"
        private const val ARG_TYPE = "arg_type"
        private const val ARG_CAPACITY = "arg_capacity"
        private const val ARG_WHATSAPP = "arg_whatsapp"
        private const val ARG_NAME = "arg_name"
        private const val ARG_PHONE = "arg_phone"
        private const val ARG_AGENCY = "arg_agency"
        // Set when the caller wants the Agency field locked to a specific
        // value (used by the mobile portal: the logged-in agency creates a
        // vehicle for themselves, so the field is auto-filled + read-only).
        private const val ARG_FIXED_AGENCY = "arg_fixed_agency"

        fun newInstance(onCreate: (VehicleFormResult) -> Unit): CreateVehicleBottomSheet {
            val sheet = CreateVehicleBottomSheet()
            sheet.onCreateCallback = onCreate
            return sheet
        }

        /** Variant for the mobile portal: the Agency field is pre-filled with
         *  the caller's own agency name and made read-only. The web caller
         *  keeps the editable form via [newInstance]. */
        fun newInstanceLockedToAgency(
            agencyName: String,
            onCreate: (VehicleFormResult) -> Unit,
        ): CreateVehicleBottomSheet {
            val sheet = CreateVehicleBottomSheet()
            sheet.onCreateCallback = onCreate
            sheet.arguments = Bundle().apply {
                putString(ARG_FIXED_AGENCY, agencyName)
            }
            return sheet
        }

        fun newEditInstance(
            plate: String,
            make: String,
            model: String,
            modelYear: String,
            type: String,
            capacity: String,
            name: String,
            phone: String,
            whatsapp: String,
            agency: String,
            onSave: (VehicleFormResult) -> Unit
        ): CreateVehicleBottomSheet {
            val sheet = CreateVehicleBottomSheet()
            sheet.onSaveCallback = onSave
            val args = Bundle().apply {
                putBoolean(ARG_IS_EDIT_MODE, true)
                putString(ARG_PLATE, plate)
                putString(ARG_MAKE, make)
                putString(ARG_MODEL, model)
                putString(ARG_MODEL_YEAR, modelYear)
                putString(ARG_TYPE, type)
                putString(ARG_CAPACITY, capacity)
                putString(ARG_NAME, name)
                putString(ARG_PHONE, phone)
                putString(ARG_WHATSAPP, whatsapp)
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
        // Lift the sheet above the soft keyboard so the focused field stays
        // visible while typing. Without this the sheet stays anchored and the
        // bottom inputs (driver name / phone) get covered.
        dialog?.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        val dialog = dialog as? com.google.android.material.bottomsheet.BottomSheetDialog
        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let {
            it.elevation = 0f
            it.background = null
            it.setBackgroundColor(Color.TRANSPARENT)
            // Expand + lock so the keyboard can't snap the sheet to a peek
            // state mid-edit.
            val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(it)
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
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

        // WhatsApp "same as mobile": disable + mirror the mobile field.
        fun applyWhatsappSame(checked: Boolean) {
            binding.etDriverWhatsapp.isEnabled = !checked
            binding.etDriverWhatsapp.alpha = if (checked) 0.6f else 1f
            if (checked) {
                binding.etDriverWhatsapp.setText(binding.etDriverPhone.text)
            }
        }
        applyWhatsappSame(binding.cbWhatsappSameAsMobile.isChecked)
        binding.cbWhatsappSameAsMobile.setOnCheckedChangeListener { _, checked ->
            applyWhatsappSame(checked)
        }
        binding.etDriverPhone.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (binding.cbWhatsappSameAsMobile.isChecked) {
                    binding.etDriverWhatsapp.setText(s)
                }
            }
        })

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
            val initialMake = arguments?.getString(ARG_MAKE) ?: ""
            val initialModel = arguments?.getString(ARG_MODEL) ?: ""
            val initialModelYear = arguments?.getString(ARG_MODEL_YEAR) ?: ""
            val initialType = arguments?.getString(ARG_TYPE) ?: ""
            val initialCapacity = arguments?.getString(ARG_CAPACITY) ?: ""
            val initialName = arguments?.getString(ARG_NAME) ?: ""
            val initialPhone = arguments?.getString(ARG_PHONE) ?: ""
            val initialWhatsapp = arguments?.getString(ARG_WHATSAPP) ?: ""
            val initialAgency = arguments?.getString(ARG_AGENCY) ?: ""

            binding.etVehicleNumber.setText(initialPlate)
            binding.etMake.setText(initialMake)
            binding.etModel.setText(initialModel)
            binding.etModelYear.setText(initialModelYear)
            binding.etVehicleType.setText(initialType)
            binding.etCapacity.setText(initialCapacity)
            binding.etDriverName.setText(initialName)
            binding.etDriverPhone.setText(initialPhone)
            binding.etDriverWhatsapp.setText(initialWhatsapp)
            binding.etAgency.setText(initialAgency)
            // If a WhatsApp differs from the mobile, un-tick "same as mobile".
            binding.cbWhatsappSameAsMobile.isChecked =
                initialWhatsapp.isBlank() || initialWhatsapp == initialPhone

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
                val result = collectForm()
                if (result == null) {
                    Toast.makeText(requireContext(), "Please fill all required fields", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                onSaveCallback?.invoke(result)
                dismiss()
            }

            binding.btnDeactivate.setOnClickListener {
                if (isCurrentlyEditing) {
                    isCurrentlyEditing = false
                    setFieldsEditable(false)
                    binding.etVehicleNumber.setText(initialPlate)
                    binding.etMake.setText(initialMake)
                    binding.etModel.setText(initialModel)
                    binding.etModelYear.setText(initialModelYear)
                    binding.etVehicleType.setText(initialType)
                    binding.etCapacity.setText(initialCapacity)
                    binding.etDriverName.setText(initialName)
                    binding.etDriverPhone.setText(initialPhone)
                    binding.etDriverWhatsapp.setText(initialWhatsapp)
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
            binding.etMake.setText("")
            binding.etModel.setText("")
            binding.etModelYear.setText("")
            binding.etVehicleType.setText("")
            binding.etCapacity.setText("")
            binding.etDriverName.setText("")
            binding.etDriverPhone.setText("")
            binding.etDriverWhatsapp.setText("")

            binding.btnSubmitCreate.setOnClickListener {
                validateAndSubmit()
            }
        }

        // Lock the Agency field when a fixed agency was passed in (mobile
        // path: the logged-in external-fleet agency is creating the vehicle
        // for themselves). Applied AFTER the create-mode setup so its
        // setText("") doesn't wipe the value we just locked.
        val fixedAgency = arguments?.getString(ARG_FIXED_AGENCY)?.takeIf { it.isNotBlank() }
        if (fixedAgency != null) {
            binding.etAgency.setText(fixedAgency)
            binding.etAgency.keyListener = null
            binding.etAgency.isFocusable = false
            binding.etAgency.isFocusableInTouchMode = false
            binding.etAgency.isClickable = false
            binding.etAgency.isLongClickable = false
            binding.etAgency.alpha = 0.7f
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
        val result = collectForm() ?: run {
            Toast.makeText(requireContext(), "Please fill all required fields", Toast.LENGTH_SHORT).show()
            return
        }
        onCreateCallback?.invoke(result)
        dismiss()
    }

    /**
     * Read every field into a result, validating the required ones. Vehicle
     * number, type, capacity, driver name and phone are required; make, model,
     * model year and WhatsApp are optional. The agency is no longer collected —
     * the backend binds the vehicle to the logged-in agency. Returns null if a
     * required field is blank.
     */
    private fun collectForm(): VehicleFormResult? {
        val number = binding.etVehicleNumber.text.toString().trim()
        val type = binding.etVehicleType.text.toString().trim()
        val capacity = binding.etCapacity.text.toString().trim()
        val name = binding.etDriverName.text.toString().trim()
        val phone = binding.etDriverPhone.text.toString().trim()
        if (number.isEmpty() || type.isEmpty() || capacity.isEmpty() ||
            name.isEmpty() || phone.isEmpty()
        ) return null
        val whatsapp =
            if (binding.cbWhatsappSameAsMobile.isChecked) phone
            else binding.etDriverWhatsapp.text.toString().trim()
        return VehicleFormResult(
            vehicleNumber = number,
            make = binding.etMake.text.toString().trim(),
            model = binding.etModel.text.toString().trim(),
            modelYear = binding.etModelYear.text.toString().trim(),
            type = type,
            capacity = capacity,
            driverName = name,
            driverPhone = phone,
            driverWhatsapp = whatsapp,
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/** Everything the vehicle create/edit form collects. */
data class VehicleFormResult(
    val vehicleNumber: String,
    val make: String,
    val model: String,
    val modelYear: String,
    val type: String,
    val capacity: String,
    val driverName: String,
    val driverPhone: String,
    val driverWhatsapp: String,
)
