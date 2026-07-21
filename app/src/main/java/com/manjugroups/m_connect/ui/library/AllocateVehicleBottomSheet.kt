package com.manjugroups.m_connect.ui.library

import android.app.TimePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.BottomSheetAllocateVehicleBinding
import java.util.Calendar
import java.util.Locale

/**
 * Allocate a pending trip to one of the agency's real vehicles. Caller passes
 * in the vehicles list + an onAllocate callback; we collect driver name, phone,
 * pickup time and pricing (per-km or package) and hand the structured result
 * back. The caller drives the network call so we don't need to know the API
 * shape inside this sheet.
 */
class AllocateVehicleBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAllocateVehicleBinding? = null
    private val binding get() = _binding!!

    private var vehicles: List<AllocateVehicleOption> = emptyList()
    private var drivers: List<AllocateDriverOption> = emptyList()
    private var showPricing: Boolean = true
    private var onAllocateCallback: ((AllocateVehicleResult) -> Unit)? = null
    // Opens the add-driver form with the typed name; the completion callback
    // hands back the created driver so this sheet can select it and resume.
    private var onAddNewDriver: ((String, (AllocateDriverOption) -> Unit) -> Unit)? = null
    private var pricingType = "km"  // "km" or "package"
    private var selectedDriverId: String? = null

    companion object {
        /**
         * @param showPricing false for external agencies. Their per-km /
         *   package rate is contracted on the agency record in the web app, so
         *   asking for it again per allocation would let a dispatcher quietly
         *   override the agreed rate. Internal (company) vehicles still price
         *   per trip.
         */
        fun newInstance(
            vehicles: List<AllocateVehicleOption>,
            drivers: List<AllocateDriverOption> = emptyList(),
            showPricing: Boolean = true,
            onAddNewDriver: ((String, (AllocateDriverOption) -> Unit) -> Unit)? = null,
            onAllocate: (AllocateVehicleResult) -> Unit,
        ): AllocateVehicleBottomSheet {
            val sheet = AllocateVehicleBottomSheet()
            sheet.vehicles = vehicles
            sheet.drivers = drivers
            sheet.showPricing = showPricing
            sheet.onAddNewDriver = onAddNewDriver
            sheet.onAllocateCallback = onAllocate
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
        _binding = BottomSheetAllocateVehicleBinding.inflate(inflater, container, false)
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

        binding.tvLabelVehicle.text = Html.fromHtml("Vehicle <font color='#EF4444'>*</font>")
        binding.tvLabelDriverName.text = Html.fromHtml("Driver Name <font color='#EF4444'>*</font>")
        binding.tvLabelDriverPhone.text = Html.fromHtml("Driver Phone Number <font color='#EF4444'>*</font>")
        binding.tvLabelPickupTime.text = Html.fromHtml("Pickup Time <font color='#EF4444'>*</font>")
        binding.tvAmountLabel.text = Html.fromHtml("Per Km amount (Rs) <font color='#EF4444'>*</font>")

        // External agencies: the per-km / package rate is contracted on the
        // agency record in the web app, so it isn't asked for per allocation.
        val pricingVis = if (showPricing) View.VISIBLE else View.GONE
        binding.tvPricingLabel.visibility = pricingVis
        binding.rowPricingToggle.visibility = pricingVis
        binding.tvAmountLabel.visibility = pricingVis
        binding.etAmount.visibility = pricingVis

        setupVehicleSpinner()
        setupDriverPicker()
        setupTimePicker()
        setupPricingToggle()

        binding.btnSubmitAllocate.setOnClickListener {
            validateAndSubmit()
        }
    }

    private fun setupDriverPicker() {
        // Free typing stays allowed (backend auto-creates a typed driver), but
        // tapping opens the roster dropdown so the dispatcher can pick an exact
        // driver — which binds the trip by id and avoids shared-phone mix-ups.
        // Editing the name by hand invalidates a previous roster pick.
        binding.etDriverName.setOnClickListener { openDriverDropdown() }
        binding.etDriverName.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val picked = drivers.firstOrNull { it.id == selectedDriverId }
                if (picked != null && s?.toString()?.trim() != picked.name) {
                    selectedDriverId = null
                }
            }
        })
    }

    private fun openDriverDropdown() {
        val options = drivers.map {
            com.manjugroups.m_connect.ui.common.SearchableOption(
                item = it,
                title = it.name,
                subtitle = it.phone,
                keywords = it.phone,
            )
        }
        com.manjugroups.m_connect.ui.common.SearchableSelectionDialog.show(
            context = requireContext(),
            title = "Select driver",
            options = options,
            emptyMessage = "No drivers yet — add one",
            createLabel = "Add new driver",
            onCreateNew = { typedName -> addNewDriver(typedName) },
        ) { driver ->
            selectDriver(driver)
        }
    }

    private fun selectDriver(driver: AllocateDriverOption) {
        selectedDriverId = driver.id
        binding.etDriverName.setText(driver.name)
        binding.etDriverPhone.setText(driver.phone)
    }

    private fun addNewDriver(typedName: String) {
        val cb = onAddNewDriver
        if (cb == null) {
            Toast.makeText(requireContext(), "Add drivers from the Driver tab.", Toast.LENGTH_SHORT).show()
            return
        }
        // The host opens the add-driver form; when it returns the new driver we
        // select it here and the allocation resumes where it left off.
        cb(typedName) { created ->
            drivers = drivers + created
            selectDriver(created)
        }
    }

    private fun setupVehicleSpinner() {
        val labels = vehicles.map { it.label }.ifEmpty { listOf("No vehicles — add one in travel-desk") }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerVehicle.adapter = adapter

        // When the user picks a vehicle, prefill its default driver if no
        // driver name has been entered yet. This matches what travel-desk's
        // server-side allocate does when driverName is left blank.
        binding.spinnerVehicle.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val v = vehicles.getOrNull(position) ?: return
                if (binding.etDriverName.text?.toString()?.trim().isNullOrEmpty()) {
                    v.defaultDriverName?.takeIf { it.isNotBlank() }?.let {
                        binding.etDriverName.setText(it)
                        selectedDriverId = null
                    }
                }
                if (binding.etDriverPhone.text?.toString()?.trim().isNullOrEmpty()) {
                    v.defaultDriverPhone?.takeIf { it.isNotBlank() }?.let {
                        binding.etDriverPhone.setText(it)
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupTimePicker() {
        binding.etPickupTime.setOnClickListener {
            val calendar = Calendar.getInstance()
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val minute = calendar.get(Calendar.MINUTE)
            TimePickerDialog(requireContext(), { _, h, m ->
                val format = if (h >= 12) "PM" else "AM"
                val displayHour = when {
                    h == 0 -> 12
                    h > 12 -> h - 12
                    else -> h
                }
                binding.etPickupTime.setText(
                    String.format(Locale.getDefault(), "%02d:%02d %s", displayHour, m, format)
                )
            }, hour, minute, false).show()
        }
    }

    private fun setupPricingToggle() {
        binding.btnPricePerKm.setOnClickListener {
            pricingType = "km"
            binding.btnPricePerKm.setBackgroundResource(R.drawable.bg_my_trips_tab_active)
            binding.btnPricePerKm.setTextColor(Color.WHITE)

            binding.btnPricePackage.background = null
            binding.btnPricePackage.setTextColor(Color.parseColor("#475467"))

            binding.tvAmountLabel.text = Html.fromHtml("Per Km amount (Rs) <font color='#EF4444'>*</font>")
        }

        binding.btnPricePackage.setOnClickListener {
            pricingType = "package"
            binding.btnPricePackage.setBackgroundResource(R.drawable.bg_my_trips_tab_active)
            binding.btnPricePackage.setTextColor(Color.WHITE)

            binding.btnPricePerKm.background = null
            binding.btnPricePerKm.setTextColor(Color.parseColor("#475467"))

            binding.tvAmountLabel.text = Html.fromHtml("Package amount (Rs) <font color='#EF4444'>*</font>")
        }
    }

    private fun validateAndSubmit() {
        if (vehicles.isEmpty()) {
            Toast.makeText(
                requireContext(),
                "No vehicles on file. Add one in travel-desk first.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val selectedIndex = binding.spinnerVehicle.selectedItemPosition
        val vehicle = vehicles.getOrNull(selectedIndex)
        val driverName = binding.etDriverName.text.toString().trim()
        val driverPhone = binding.etDriverPhone.text.toString().trim()
        val pickupTime = binding.etPickupTime.text.toString().trim()
        val amountText = binding.etAmount.text.toString().trim()

        if (vehicle == null) {
            Toast.makeText(requireContext(), "Select a vehicle", Toast.LENGTH_SHORT).show()
            return
        }
        if (driverName.isEmpty() || driverPhone.isEmpty() || pickupTime.isEmpty()) {
            Toast.makeText(requireContext(), "Please fill all mandatory fields", Toast.LENGTH_SHORT).show()
            return
        }
        // Amount is mandatory only while the pricing block is on screen —
        // otherwise the sheet would refuse to submit over a hidden field.
        var amount = 0.0
        if (showPricing) {
            if (amountText.isEmpty()) {
                Toast.makeText(requireContext(), "Please fill all mandatory fields", Toast.LENGTH_SHORT).show()
                return
            }
            val parsed = amountText.toDoubleOrNull()
            if (parsed == null || parsed < 0) {
                Toast.makeText(requireContext(), "Enter a valid amount", Toast.LENGTH_SHORT).show()
                return
            }
            amount = parsed
        }

        onAllocateCallback?.invoke(
            AllocateVehicleResult(
                vehicleId = vehicle.vehicleId,
                vehicleLabel = vehicle.label,
                driverName = driverName,
                driverPhone = driverPhone,
                driverId = selectedDriverId,
                pickupTime = pickupTime,
                pricingMode = pricingType,
                amount = amount,
            )
        )
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/** A roster driver the agency can pick from when allocating a trip. */
data class AllocateDriverOption(
    val id: String,
    val name: String,
    val phone: String,
)

/** A vehicle the agency can pick from when allocating a trip. */
data class AllocateVehicleOption(
    val vehicleId: String,
    val label: String,
    val defaultDriverName: String?,
    val defaultDriverPhone: String?,
)

/** Structured result handed to the caller's onAllocate callback. */
data class AllocateVehicleResult(
    val vehicleId: String,
    val vehicleLabel: String,
    val driverName: String,
    val driverPhone: String,
    /** The exact roster driver, when picked from the dropdown. */
    val driverId: String? = null,
    val pickupTime: String,
    val pricingMode: String, // "km" | "package"
    val amount: Double,
)
