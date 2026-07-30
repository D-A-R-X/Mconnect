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
        // Same option sets as the web Travel Desk vehicle form
        // (features/fleet/types.ts VEHICLE_MAKES / VEHICLE_TYPES).
        private val VEHICLE_MAKES = listOf(
            "Maruti Suzuki", "Mahindra", "Toyota", "Hyundai", "Tata", "Kia",
            "Honda", "MG", "Renault", "Nissan", "Volkswagen", "Skoda", "Ford",
            "Force", "Isuzu", "Other",
        )
        private val VEHICLE_CAPACITY_BY_TYPE = linkedMapOf(
            "SUV" to 7,
            "Sedan" to 5,
            "Hatchback" to 5,
        )
        private val VEHICLE_TYPES = VEHICLE_CAPACITY_BY_TYPE.keys.toList()

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
            // Give the sheet a bounded (tall) height so the root's match_parent
            // resolves and the footer Save can pin to the bottom while the
            // fields scroll. Without a fixed height a wrap_content sheet lets
            // the footer scroll away with the content.
            val screenH = resources.displayMetrics.heightPixels
            it.layoutParams = it.layoutParams.apply { height = (screenH * 0.92f).toInt() }
            // Expand + lock so the keyboard can't snap the sheet to a peek
            // state mid-edit.
            val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(it)
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }
    }

    private var isEditModeFlag = false
    private var initialSnapshot = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // No explicit Edit action — the form is directly editable. The old
        // Edit button stays GONE.
        binding.btnEditVehicle.visibility = View.GONE
        binding.layoutEditButtons.visibility = View.GONE

        // Make supports a custom value. Vehicle type is restricted to the
        // external-fleet options and controls seating capacity.
        setupChoiceField(binding.etMake, "Select make", VEHICLE_MAKES)
        setupChoiceField(
            field = binding.etVehicleType,
            title = "Select vehicle type",
            options = VEHICLE_TYPES,
            allowCustomValue = false,
        ) { updateCapacityForType(it) }

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
            refreshSaveState()
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
        binding.tvLabelVehicleNumber.text = "Vehicle Number"
        binding.tvLabelModel.text = Html.fromHtml("Model <font color='#EF4444'>*</font>")
        binding.tvLabelModelYear.text = Html.fromHtml("Model Year <font color='#EF4444'>*</font>")
        binding.tvLabelVehicleType.text = Html.fromHtml("Vehicle Type <font color='#EF4444'>*</font>")
        binding.tvLabelCapacity.text = "Seating Capacity"
        binding.tvLabelName.text = Html.fromHtml("Name <font color='#EF4444'>*</font>")
        binding.tvLabelPhone.text = Html.fromHtml("Phone Number <font color='#EF4444'>*</font>")
        binding.tvLabelAgency.text = Html.fromHtml("Agency <font color='#EF4444'>*</font>")

        isEditModeFlag = arguments?.getBoolean(ARG_IS_EDIT_MODE, false) ?: false
        if (isEditModeFlag) {
            // Edit / details mode — fields pre-filled and directly editable.
            binding.tvCreateVehicleTitle.text = "Vehicle Details"

            binding.etVehicleNumber.setText(arguments?.getString(ARG_PLATE) ?: "")
            binding.etMake.setText(arguments?.getString(ARG_MAKE) ?: "")
            binding.etModel.setText(arguments?.getString(ARG_MODEL) ?: "")
            binding.etModelYear.setText(arguments?.getString(ARG_MODEL_YEAR) ?: "")
            binding.etVehicleType.setText(arguments?.getString(ARG_TYPE) ?: "")
            updateCapacityForType(binding.etVehicleType.text.toString())
            binding.etDriverName.setText(arguments?.getString(ARG_NAME) ?: "")
            binding.etDriverPhone.setText(arguments?.getString(ARG_PHONE) ?: "")
            val initialWhatsapp = arguments?.getString(ARG_WHATSAPP) ?: ""
            val initialPhone = arguments?.getString(ARG_PHONE) ?: ""
            binding.etDriverWhatsapp.setText(initialWhatsapp)
            binding.etAgency.setText(arguments?.getString(ARG_AGENCY) ?: "")
            binding.cbWhatsappSameAsMobile.isChecked =
                initialWhatsapp.isBlank() || initialWhatsapp == initialPhone

            // The Save bar is the fixed footer: grey + disabled until something
            // changes, then green + clickable.
            binding.btnSubmitCreate.text = "Save Changes"
            binding.btnSubmitCreate.setOnClickListener {
                if (formSnapshot() == initialSnapshot) return@setOnClickListener
                val result = collectForm() ?: run {
                    Toast.makeText(requireContext(), "Please fill all required fields", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                onSaveCallback?.invoke(result)
                dismiss()
            }
        } else {
            // Create mode — empty fields, always-active Submit.
            binding.tvCreateVehicleTitle.text = "Create Vehicle"
            binding.btnSubmitCreate.text = "Submit"
            binding.btnSubmitCreate.setOnClickListener { validateAndSubmit() }
        }

        // Lock the Agency field when a fixed agency was passed in (mobile
        // path: the logged-in external-fleet agency is creating the vehicle
        // for themselves).
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

        // Snapshot the starting state AFTER all fields are seeded, then watch
        // every field so the Save bar reflects "has anything changed".
        initialSnapshot = formSnapshot()
        attachDirtyWatchers()
        refreshSaveState()
    }

    /** All the fields whose text feeds the dirty check. */
    private fun editableFields() = listOf(
        binding.etVehicleNumber, binding.etMake, binding.etModel,
        binding.etModelYear, binding.etVehicleType, binding.etCapacity,
        binding.etDriverName, binding.etDriverPhone, binding.etDriverWhatsapp,
    )

    private fun formSnapshot(): String =
        editableFields().joinToString("|") { it.text.toString().trim() } +
            "|wa=" + binding.cbWhatsappSameAsMobile.isChecked

    private fun attachDirtyWatchers() {
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = refreshSaveState()
        }
        editableFields().forEach { it.addTextChangedListener(watcher) }
    }

    /** Grey + disabled when nothing changed; green + clickable once it has.
     *  Create mode keeps the Submit button always active. */
    private fun refreshSaveState() {
        if (_binding == null) return
        val btn = binding.btnSubmitCreate
        if (!isEditModeFlag) {
            btn.isEnabled = true
            btn.setBackgroundResource(R.drawable.bg_allocate_button_green)
            btn.setTextColor(Color.WHITE)
            return
        }
        val dirty = formSnapshot() != initialSnapshot
        btn.isEnabled = dirty
        if (dirty) {
            btn.setBackgroundResource(R.drawable.bg_allocate_button_green)
            btn.setTextColor(Color.WHITE)
        } else {
            btn.setBackgroundResource(R.drawable.bg_btn_disabled_grey)
            btn.setTextColor(Color.parseColor("#9CA3AF"))
        }
    }

    /**
     * Turn an EditText into a tap-to-pick dropdown backed by [options].
     * Choice fields that allow "Other" can switch to free-text entry.
     */
    private fun setupChoiceField(
        field: android.widget.EditText,
        title: String,
        options: List<String>,
        allowCustomValue: Boolean = true,
        onPicked: (String) -> Unit = {},
    ) {
        field.setOnClickListener {
            com.manjugroups.m_connect.ui.common.SearchableSelectionDialog.show(
                context = requireContext(),
                title = title,
                options = options.map {
                    com.manjugroups.m_connect.ui.common.SearchableOption(item = it, title = it)
                },
            ) { picked ->
                if (allowCustomValue && picked == "Other") {
                    field.setOnClickListener(null)
                    field.isFocusableInTouchMode = true
                    field.isFocusable = true
                    field.isCursorVisible = true
                    field.keyListener = android.text.method.TextKeyListener.getInstance()
                    field.setCompoundDrawablesWithIntrinsicBounds(
                        field.compoundDrawablesRelative[0], null, null, null,
                    )
                    field.setText("")
                    field.requestFocus()
                } else {
                    field.setText(picked)
                    onPicked(picked)
                }
            }
        }
    }

    private fun updateCapacityForType(type: String) {
        binding.etCapacity.setText(VEHICLE_CAPACITY_BY_TYPE[type]?.toString().orEmpty())
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
     * Read every field into a result, validating the required ones. Model,
     * model year, type, driver name and phone are required. Vehicle number,
     * make and WhatsApp are optional, while capacity is derived from type.
     */
    private fun collectForm(): VehicleFormResult? {
        val number = binding.etVehicleNumber.text.toString().trim()
        val model = binding.etModel.text.toString().trim()
        val modelYear = binding.etModelYear.text.toString().trim()
        val type = binding.etVehicleType.text.toString().trim()
        val capacity = VEHICLE_CAPACITY_BY_TYPE[type]?.toString() ?: return null
        val name = binding.etDriverName.text.toString().trim()
        val phone = binding.etDriverPhone.text.toString().trim()
        if (model.isEmpty() || modelYear.length != 4 || modelYear.toIntOrNull() == null ||
            name.isEmpty() || phone.isEmpty()
        ) return null
        val whatsapp =
            if (binding.cbWhatsappSameAsMobile.isChecked) phone
            else binding.etDriverWhatsapp.text.toString().trim()
        return VehicleFormResult(
            vehicleNumber = number,
            make = binding.etMake.text.toString().trim(),
            model = model,
            modelYear = modelYear,
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
