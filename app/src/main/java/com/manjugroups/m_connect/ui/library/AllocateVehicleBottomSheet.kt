package com.manjugroups.m_connect.ui.library

import android.app.TimePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    // Index into [vehicles] of the picked vehicle, or -1 until one is chosen.
    private var selectedVehicleIndex: Int = -1
    // Internal MMS fleet: the driver is whoever is set as the vehicle's default
    // driver — the dispatcher can't type a different one. The name + phone
    // fields are locked and always reflect the selected vehicle's default.
    private var lockDriverToVehicleDefault: Boolean = false
    // Pre-fills the pickup time from the SV's scheduled time.
    private var fixedPickupTime: String? = null
    private var onAllocateCallback: ((AllocateVehicleResult) -> Unit)? = null
    // Opens the add-driver form with the typed name; the completion callback
    // hands back the created driver so this sheet can select it and resume.
    private var onAddNewDriver: ((String, (AllocateDriverOption) -> Unit) -> Unit)? = null
    private var selectedDriverId: String? = null
    private var agencies: List<AllocateAgencyOption> = emptyList()
    // When true, the sheet opens on a source chooser (Travel agency vs MFPL)
    // step before showing any fields — set by the internal-fleet dispatch.
    private var showSourceChoice: Boolean = false
    // "none" (chooser step, nothing picked yet), "internal" (MFPL vehicle), or
    // "external" (travel agency).
    private var assignMode: String = "internal"
    private var selectedAgencyId: String? = null
    private var selectedAgencyName: String? = null

    companion object {
        fun newInstance(
            vehicles: List<AllocateVehicleOption>,
            drivers: List<AllocateDriverOption> = emptyList(),
            lockDriverToVehicleDefault: Boolean = false,
            fixedPickupTime: String? = null,
            // External travel agencies the dispatcher can allot to instead of an
            // MFPL vehicle. When non-empty the "Assign from" toggle appears
            // (mirrors the web dialog); empty keeps the sheet internal-only.
            agencies: List<AllocateAgencyOption> = emptyList(),
            // Show the initial Travel agency / MFPL chooser step. Independent of
            // whether agencies have loaded — the internal dispatch always offers
            // the choice; the agency list fills in when available.
            showSourceChoice: Boolean = false,
            onAddNewDriver: ((String, (AllocateDriverOption) -> Unit) -> Unit)? = null,
            onAllocate: (AllocateVehicleResult) -> Unit,
        ): AllocateVehicleBottomSheet {
            val sheet = AllocateVehicleBottomSheet()
            sheet.vehicles = vehicles
            sheet.drivers = drivers
            sheet.lockDriverToVehicleDefault = lockDriverToVehicleDefault
            sheet.fixedPickupTime = fixedPickupTime
            sheet.agencies = agencies
            sheet.showSourceChoice = showSourceChoice
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
        // Hide the fleet portal's floating nav so it doesn't show through the
        // scrim while this sheet is open. No-op when opened outside the fleet
        // container (e.g. from Home).
        fleetContainer()?.setNavChromeVisible(false)
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        fleetContainer()?.setNavChromeVisible(true)
        super.onDismiss(dialog)
    }

    private fun fleetContainer(): AdminFleetContainerFragment? {
        var f = parentFragment
        while (f != null) {
            if (f is AdminFleetContainerFragment) return f
            f = f.parentFragment
        }
        return null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvLabelVehicle.text = Html.fromHtml("Vehicle <font color='#EF4444'>*</font>")
        binding.tvLabelDriverName.text = Html.fromHtml("Driver Name <font color='#EF4444'>*</font>")
        binding.tvLabelDriverPhone.text = Html.fromHtml("Driver Phone Number <font color='#EF4444'>*</font>")
        binding.tvLabelPickupTime.text = Html.fromHtml("Pickup Time <font color='#EF4444'>*</font>")

        setupVehicleSpinner()
        setupDriverPicker()
        setupTimePicker()
        setupAssignSource()

        // Internal fleet: driver is fixed to the vehicle's default — lock the
        // name + phone so the dispatcher can't retype them.
        if (lockDriverToVehicleDefault) {
            listOf(binding.etDriverName, binding.etDriverPhone).forEach { field ->
                field.keyListener = null
                field.isFocusable = false
                field.isFocusableInTouchMode = false
                field.isCursorVisible = false
                field.setOnClickListener(null)
            }
        }
        // Pickup time imported from the SV's scheduled time.
        fixedPickupTime?.takeIf { it.isNotBlank() }?.let {
            binding.etPickupTime.setText(it)
        }

        binding.btnSubmitAllocate.setOnClickListener {
            validateAndSubmit()
        }
    }

    private fun setupDriverPicker() {
        // Internal fleet locks the driver to the vehicle default — no roster
        // dropdown, no free typing.
        if (lockDriverToVehicleDefault) return
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
        // Tapping the field opens the same searchable picker the driver field
        // uses — one shared app component (SearchableSelectionDialog) instead of
        // a bare Android Spinner, so a long fleet is searchable by number/model.
        binding.vehicleSelector.setOnClickListener { openVehicleDropdown() }

        // Auto-select when there is exactly one vehicle, so the common
        // single-vehicle agency doesn't have to open the picker at all.
        if (vehicles.size == 1) selectVehicle(0)
    }

    private fun openVehicleDropdown() {
        if (vehicles.isEmpty()) {
            Toast.makeText(
                requireContext(),
                "No vehicles on file. Add one in travel-desk first.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val options = vehicles.mapIndexed { index, v ->
            com.manjugroups.m_connect.ui.common.SearchableOption(
                item = index,
                title = v.label,
                subtitle = v.defaultDriverName?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { "Default driver: $it" },
                keywords = listOfNotNull(v.label, v.defaultDriverName, v.defaultDriverPhone)
                    .joinToString(" "),
            )
        }
        com.manjugroups.m_connect.ui.common.SearchableSelectionDialog.show(
            context = requireContext(),
            title = "Select vehicle",
            options = options,
            emptyMessage = "No vehicles — add one in travel-desk",
        ) { index ->
            selectVehicle(index)
        }
    }

    private fun selectVehicle(index: Int) {
        val v = vehicles.getOrNull(index) ?: return
        selectedVehicleIndex = index
        binding.tvVehicleValue.text = v.label
        // When the user picks a vehicle, prefill its default driver if no driver
        // name has been entered yet. Mirrors travel-desk's server-side allocate
        // when driverName is left blank.
        if (lockDriverToVehicleDefault) {
            // Internal fleet: the driver ALWAYS follows the vehicle's default
            // (overwrite whatever was there), never editable.
            binding.etDriverName.setText(v.defaultDriverName?.trim().orEmpty())
            binding.etDriverPhone.setText(v.defaultDriverPhone?.trim().orEmpty())
            selectedDriverId = null
            return
        }
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

    // ── Assign-from source (Internal MFPL vs External agency) ────────────────

    private val internalOnlyViews get() = listOf(
        binding.tvLabelVehicle, binding.vehicleSelector,
        binding.tvLabelDriverName, binding.etDriverName,
        binding.tvLabelDriverPhone, binding.etDriverPhone,
        binding.tvLabelPickupTime, binding.etPickupTime,
    )

    private fun setupAssignSource() {
        // Callers that don't want the chooser (e.g. the agency portal) get the
        // plain internal form, no toggle.
        if (!showSourceChoice) {
            binding.tvLabelAssignSource.visibility = View.GONE
            binding.rowAssignSource.visibility = View.GONE
            applyAssignMode("internal")
            return
        }
        // Internal dispatch: open on the chooser step — ONLY the two source
        // buttons show (no fields, no submit) until one is picked.
        binding.tvLabelAssignSource.visibility = View.VISIBLE
        binding.rowAssignSource.visibility = View.VISIBLE
        binding.btnSourceInternal.setOnClickListener { applyAssignMode("internal") }
        binding.btnSourceExternal.setOnClickListener {
            // Just reveal the agency field — the picker opens only when the user
            // taps "Select agency", not automatically.
            applyAssignMode("external")
        }
        binding.agencySelector.setOnClickListener { openAgencyDropdown() }
        applyAssignMode("none")
    }

    private fun applyAssignMode(mode: String) {
        assignMode = mode
        val external = mode == "external"
        val internal = mode == "internal"
        val chosen = external || internal

        // Toggle pill styling — highlight the active choice; "none" leaves both
        // idle so the chooser reads as an un-answered question.
        val active = R.drawable.bg_my_trips_tab_active
        val activeText = Color.WHITE
        val idleText = Color.parseColor("#475467")
        binding.btnSourceInternal.setBackgroundResource(if (internal) active else 0)
        binding.btnSourceInternal.setTextColor(if (internal) activeText else idleText)
        binding.btnSourceExternal.setBackgroundResource(if (external) active else 0)
        binding.btnSourceExternal.setTextColor(if (external) activeText else idleText)

        // Internal → vehicle/driver/pickup. External → agency picker. None →
        // neither (chooser step). Submit is hidden until a source is picked.
        internalOnlyViews.forEach { it.visibility = if (internal) View.VISIBLE else View.GONE }
        binding.tvLabelAgency.visibility = if (external) View.VISIBLE else View.GONE
        binding.agencySelector.visibility = if (external) View.VISIBLE else View.GONE
        binding.btnSubmitAllocate.visibility = if (chosen) View.VISIBLE else View.GONE
        binding.btnSubmitAllocate.text = if (external) "Allot" else "Allocate"
    }

    private fun openAgencyDropdown() {
        if (agencies.isEmpty()) {
            Toast.makeText(
                requireContext(),
                "No travel agencies available yet.",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val options = agencies.mapIndexed { index, a ->
            com.manjugroups.m_connect.ui.common.SearchableOption(
                item = index,
                title = a.name,
                subtitle = a.phone?.trim()?.takeIf { it.isNotBlank() },
                keywords = listOfNotNull(a.name, a.phone).joinToString(" "),
            )
        }
        com.manjugroups.m_connect.ui.common.SearchableSelectionDialog.show(
            context = requireContext(),
            title = "Select agency",
            options = options,
            emptyMessage = "No travel agencies",
        ) { index ->
            agencies.getOrNull(index)?.let { a ->
                selectedAgencyId = a.id
                selectedAgencyName = a.name
                binding.tvAgencyValue.text = a.name
                // Just fill the field — the visible "Allot" button confirms.
            }
        }
    }

    private fun validateAndSubmit() {
        if (assignMode == "external") {
            submitExternalAllot()
            return
        }
        if (vehicles.isEmpty()) {
            Toast.makeText(
                requireContext(),
                "No vehicles on file. Add one in travel-desk first.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val vehicle = vehicles.getOrNull(selectedVehicleIndex)
        val driverName = binding.etDriverName.text.toString().trim()
        val driverPhone = binding.etDriverPhone.text.toString().trim()
        val pickupTime = binding.etPickupTime.text.toString().trim()

        if (vehicle == null) {
            Toast.makeText(requireContext(), "Select a vehicle", Toast.LENGTH_SHORT).show()
            return
        }
        if (driverName.isEmpty() || driverPhone.isEmpty() || pickupTime.isEmpty()) {
            Toast.makeText(requireContext(), "Please fill all mandatory fields", Toast.LENGTH_SHORT).show()
            return
        }
        onAllocateCallback?.invoke(
            AllocateVehicleResult(
                assignMode = "internal",
                vehicleId = vehicle.vehicleId,
                vehicleLabel = vehicle.label,
                driverName = driverName,
                driverPhone = driverPhone,
                driverId = selectedDriverId,
                pickupTime = pickupTime,
            )
        )
        dismiss()
    }

    private fun submitExternalAllot() {
        val agencyId = selectedAgencyId
        if (agencyId.isNullOrBlank()) {
            Toast.makeText(requireContext(), "Select a travel agency", Toast.LENGTH_SHORT).show()
            return
        }
        onAllocateCallback?.invoke(
            AllocateVehicleResult(
                assignMode = "external",
                travelAgencyId = agencyId,
                travelAgencyName = selectedAgencyName,
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

/** An external travel agency the dispatcher can allot a trip to. */
data class AllocateAgencyOption(
    val id: String,
    val name: String,
    val phone: String? = null,
)

/**
 * Structured result handed to the caller's onAllocate callback. [assignMode]
 * decides which fields are meaningful: "internal" fills vehicle/driver/pricing;
 * "external" fills only the travel-agency fields (the agency assigns the cab in
 * Travel Desk, so no vehicle/driver/rate is captured here).
 */
data class AllocateVehicleResult(
    val assignMode: String = "internal", // "internal" | "external"
    val vehicleId: String = "",
    val vehicleLabel: String = "",
    val driverName: String = "",
    val driverPhone: String = "",
    /** The exact roster driver, when picked from the dropdown. */
    val driverId: String? = null,
    val pickupTime: String = "",
    val travelAgencyId: String? = null,
    val travelAgencyName: String? = null,
)
