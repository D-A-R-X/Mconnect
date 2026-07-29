package com.manjugroups.m_connect.ui.library

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doAfterTextChanged
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.TravelDeskVehicle
import com.manjugroups.m_connect.util.fleetTripDistanceKm
import java.io.File

data class CompleteOfflineTripResult(
    val fleetType: String,
    val vehicleId: String?,
    val vehicleLabel: String?,
    val driverName: String?,
    val driverPhone: String?,
    val agencyName: String?,
    val packageAmount: Double?,
    val distanceKm: Double?,
    val standingTimeMinutes: Int?,
    val standingWithAc: Boolean?,
    val startKm: Double? = null,
    val endKm: Double? = null,
    val startImageUri: Uri? = null,
    val endImageUri: Uri? = null,
    val hillCharge: Double? = null,
    val outstationCharge: Double? = null,
    val permitCharge: Double? = null,
    val permitTax: Double? = null,
)

class AdminFleetCompleteOfflineSheet : BottomSheetDialogFragment() {

    private var trip: AdminFleetTripsFragment.AdminTrip? = null
    private var vehicles: List<TravelDeskVehicle> = emptyList()
    private var onSubmit: ((CompleteOfflineTripResult) -> Unit)? = null

    private var selectedVehicleIndex: Int = -1
    private var startImageUri: Uri? = null
    private var endImageUri: Uri? = null
    private var pendingStartCameraUri: Uri? = null
    private var pendingEndCameraUri: Uri? = null
    private val startImagePicker = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        startImageUri = uri
        updateImageButton(true, uri)
    }
    private val endImagePicker = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        endImageUri = uri
        updateImageButton(false, uri)
    }
    private val startCameraPicker = registerForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { captured ->
        if (captured) {
            startImageUri = pendingStartCameraUri
            updateImageButton(true, startImageUri)
        }
    }
    private val endCameraPicker = registerForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { captured ->
        if (captured) {
            endImageUri = pendingEndCameraUri
            updateImageButton(false, endImageUri)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.setBackgroundResource(android.R.color.transparent)
                it.layoutParams = it.layoutParams.apply {
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                BottomSheetBehavior.from(it).apply {
                    isFitToContents = true
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                }
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.dialog_admin_fleet_complete_offline, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val t = trip ?: run { dismissAllowingStateLoss(); return }

        val layoutInternal = view.findViewById<View>(R.id.layoutInternalFleet)
        val layoutExternal = view.findViewById<View>(R.id.layoutExternalFleet)
        val layoutOwn = view.findViewById<View>(R.id.layoutOwnVehicle)
        val fleetTypeSelector = view.findViewById<View>(R.id.layoutFleetTypeSelector)
        val spinner = view.findViewById<Spinner>(R.id.spinnerFleetType)
        val isExternalAgency = SessionManager(requireContext()).isExternalFleetAgencyOperator

        val fleetTypes = listOf("Internal", "External", "Own Vehicle")
        val spinnerAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            fleetTypes,
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinner.adapter = spinnerAdapter

        val defaultIndex = if (isExternalAgency) 1 else 0
        spinner.setSelection(defaultIndex)
        fleetTypeSelector.visibility = View.GONE

        fun showFleetForm(position: Int) {
            layoutInternal.visibility = if (position == 0) View.VISIBLE else View.GONE
            layoutExternal.visibility = if (position == 1) View.VISIBLE else View.GONE
            layoutOwn.visibility = if (position == 2) View.VISIBLE else View.GONE
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                showFleetForm(pos)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        showFleetForm(defaultIndex)

        setupInternalFleet(view, t)
        setupExternalFleet(view, t)
        setupOwnVehicle(view, t)
        setupKeyboardAwareScrolling(view)
        view.findViewById<View>(R.id.btnStartDashboardImage)
            .setOnClickListener { showImageSourceChooser(true) }
        view.findViewById<View>(R.id.btnEndDashboardImage)
            .setOnClickListener { showImageSourceChooser(false) }
        view.findViewById<View>(R.id.btnStartDashboardImageExternal)
            .setOnClickListener { showImageSourceChooser(true) }
        view.findViewById<View>(R.id.btnEndDashboardImageExternal)
            .setOnClickListener { showImageSourceChooser(false) }
        val standingInternal = view.findViewById<EditText>(R.id.etStandingMinutesInternal)
        val standingAcInternal = view.findViewById<CheckBox>(R.id.checkStandingWithAcInternal)
        standingInternal.doAfterTextChanged {
            standingAcInternal.isEnabled = !it.isNullOrBlank()
            if (it.isNullOrBlank()) standingAcInternal.isChecked = false
        }

        view.findViewById<View>(R.id.btnCancelComplete).setOnClickListener {
            dismissAllowingStateLoss()
        }
        view.findViewById<View>(R.id.btnSubmitComplete).setOnClickListener {
            val fleetTypeIndex = if (isExternalAgency) 1 else spinner.selectedItemPosition
            val result = parseResult(view, fleetTypeIndex) ?: return@setOnClickListener
            onSubmit?.invoke(result)
            dismissAllowingStateLoss()
        }
    }

    private fun updateImageButton(isStart: Boolean, uri: Uri?) {
        val ids = if (isStart) {
            listOf(R.id.btnStartDashboardImage, R.id.btnStartDashboardImageExternal)
        } else {
            listOf(R.id.btnEndDashboardImage, R.id.btnEndDashboardImageExternal)
        }
        val emptyLabel = if (isStart) {
            "Select start dashboard image (optional)"
        } else {
            "Select end dashboard image (optional)"
        }
        ids.forEach { id ->
            view?.findViewById<android.widget.Button>(id)?.text =
                if (uri == null) emptyLabel else if (isStart) "Start image selected" else "End image selected"
        }
    }

    private fun showImageSourceChooser(isStart: Boolean) {
        AlertDialog.Builder(requireContext())
            .setTitle(if (isStart) "Add start dashboard image" else "Add end dashboard image")
            .setItems(arrayOf("Take photo", "Choose from gallery")) { _, choice ->
                if (choice == 0) {
                    launchDashboardCamera(isStart)
                } else if (isStart) {
                    startImagePicker.launch("image/*")
                } else {
                    endImagePicker.launch("image/*")
                }
            }
            .show()
    }

    private fun launchDashboardCamera(isStart: Boolean) {
        val directory = File(requireContext().cacheDir, "fleet_dashboard_photos")
            .apply { mkdirs() }
        val file = File.createTempFile(
            if (isStart) "fleet_start_" else "fleet_end_",
            ".jpg",
            directory,
        )
        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            file,
        )
        if (isStart) {
            pendingStartCameraUri = uri
            startCameraPicker.launch(uri)
        } else {
            pendingEndCameraUri = uri
            endCameraPicker.launch(uri)
        }
    }

    private fun setupInternalFleet(view: View, t: AdminFleetTripsFragment.AdminTrip) {
        val tvVehicle = view.findViewById<android.widget.TextView>(R.id.tvVehicleSelector)
        val tvDriver = view.findViewById<android.widget.TextView>(R.id.tvSelectedDriver)
        val etPackage = view.findViewById<EditText>(R.id.etPackageAmount)
        val etStartKm = view.findViewById<EditText>(R.id.etStartKm)
        val etEndKm = view.findViewById<EditText>(R.id.etEndKm)

        etPackage.setText(t.packageAmount?.toDisplayText().orEmpty())
        etStartKm.setText(t.startKm?.toDisplayText().orEmpty())
        etEndKm.setText(t.endKm?.toDisplayText().orEmpty())
        bindTotalKm(
            etStartKm,
            etEndKm,
            view.findViewById(R.id.etTotalKmInternal),
        )

        val vehicleOptions = vehicles
            .filter { (it.status ?: "active").equals("active", ignoreCase = true) }
            .map {
                val number = it.vehicleNumber ?: "—"
                val typePart = it.type?.takeIf { s -> s.isNotBlank() }?.let { s -> "$s · " }.orEmpty()
                "$typePart$number"
            }
        selectedVehicleIndex = vehicles
            .filter { (it.status ?: "active").equals("active", ignoreCase = true) }
            .indexOfFirst { it.vehicleNumber == t.allocatedVehicle }
        if (selectedVehicleIndex >= 0) {
            tvVehicle.text = vehicleOptions[selectedVehicleIndex]
        }

        tvVehicle.setOnClickListener {
            if (vehicleOptions.isEmpty()) {
                Toast.makeText(requireContext(), "No vehicles available.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            com.manjugroups.m_connect.ui.common.SearchableSelectionDialog.show(
                context = requireContext(),
                title = "Select vehicle",
                options = vehicleOptions.mapIndexed { index, label ->
                    val v = vehicles.filter { (it.status ?: "active").equals("active", ignoreCase = true) }[index]
                    com.manjugroups.m_connect.ui.common.SearchableOption(
                        item = index,
                        title = label,
                        subtitle = v.defaultDriverName?.trim()?.takeIf { s -> s.isNotBlank() }
                            ?.let { name -> "Default driver: $name" },
                        keywords = listOfNotNull(label, v.defaultDriverName, v.defaultDriverPhone)
                            .joinToString(" "),
                    )
                },
                emptyMessage = "No vehicles",
            ) { index ->
                selectedVehicleIndex = index
                val v = vehicles.filter { (it.status ?: "active").equals("active", ignoreCase = true) }[index]
                tvVehicle.text = vehicleOptions[index]
                val driverInfo = v.defaultDriverName?.trim()?.takeIf { s -> s.isNotBlank() }?.let { name ->
                    val phone = v.defaultDriverPhone?.trim()?.takeIf { s -> s.isNotBlank() }
                    if (phone != null) "$name · $phone" else name
                }
                if (driverInfo != null) {
                    tvDriver.text = "Driver: $driverInfo"
                    tvDriver.visibility = View.VISIBLE
                } else {
                    tvDriver.visibility = View.GONE
                }
            }
        }

        if (vehicleOptions.size == 1) {
            tvVehicle.performClick()
        }
    }

    private fun setupExternalFleet(view: View, t: AdminFleetTripsFragment.AdminTrip) {
        view.findViewById<EditText>(R.id.etPackageAmountExternal)
            .setText(t.packageAmount?.toDisplayText().orEmpty())
        val totalKm = view.findViewById<EditText>(R.id.etDistanceKmExternal)
        val startKm = view.findViewById<EditText>(R.id.etStartKmExternal)
        val endKm = view.findViewById<EditText>(R.id.etEndKmExternal)
        startKm.setText(t.startKm?.toDisplayText().orEmpty())
        endKm.setText(t.endKm?.toDisplayText().orEmpty())
        bindTotalKm(startKm, endKm, totalKm)

        val standingMinutes = view.findViewById<EditText>(R.id.etStandingMinutesExternal)
        val standingWithAc = view.findViewById<CheckBox>(R.id.checkStandingWithAcExternal)
        standingMinutes.doAfterTextChanged { text ->
            val hasStanding = !text.isNullOrBlank()
            standingWithAc.isEnabled = hasStanding
            if (!hasStanding) standingWithAc.isChecked = false
        }
    }

    private fun setupKeyboardAwareScrolling(view: View) {
        val scroll = view.findViewById<NestedScrollView>(R.id.completeFormScroll)
        val fields: List<EditText> = listOf(
            R.id.etPackageAmountExternal,
            R.id.etDistanceKmExternal,
            R.id.etStartKmExternal,
            R.id.etEndKmExternal,
            R.id.etStandingMinutesExternal,
            R.id.etHillCharge,
            R.id.etOutstationCharge,
            R.id.etPermitCharge,
            R.id.etPermitTax,
        ).map { id -> view.findViewById(id) }

        fields.forEach { field ->
            field.onFocusChangeListener = View.OnFocusChangeListener { focusedView, hasFocus ->
                if (hasFocus) {
                    scroll.postDelayed({
                        if (!isAdded) return@postDelayed
                        val rect = Rect()
                        focusedView.getDrawingRect(rect)
                        scroll.offsetDescendantRectToMyCoords(focusedView, rect)
                        val topGap = (24 * resources.displayMetrics.density).toInt()
                        scroll.smoothScrollTo(0, (rect.top - topGap).coerceAtLeast(0))
                    }, 180L)
                }
            }
        }
    }

    private fun setupOwnVehicle(view: View, t: AdminFleetTripsFragment.AdminTrip) {
        view.findViewById<EditText>(R.id.etPackageAmountOwn)
            .setText(t.packageAmount?.toDisplayText().orEmpty())
        view.findViewById<EditText>(R.id.etDistanceKmOwn)
            .setText(t.distanceKm?.toDisplayText().orEmpty())
    }

    private fun bindTotalKm(start: EditText, end: EditText, total: EditText) {
        fun update() {
            val startValue = start.text.toString().toDoubleOrNull()
            val endValue = end.text.toString().toDoubleOrNull()
            total.setText(
                if (startValue != null && endValue != null && endValue >= startValue) {
                    (endValue - startValue).toDisplayText()
                } else {
                    ""
                },
            )
        }
        start.doAfterTextChanged { update() }
        end.doAfterTextChanged { update() }
        update()
    }

    private fun parseResult(view: View, fleetTypeIndex: Int): CompleteOfflineTripResult? {
        return try {
            val t = trip!!
            val hillCharge = optionalNumber(view, R.id.etHillCharge, "Hill charge")
            val outstationCharge = optionalNumber(
                view, R.id.etOutstationCharge, "Outstation charge",
            )
            val permitCharge = optionalNumber(view, R.id.etPermitCharge, "Permit charge")
            val permitTax = optionalNumber(view, R.id.etPermitTax, "Permit tax")
            when (fleetTypeIndex) {
                0 -> {
                    val activeVehicles = vehicles
                        .filter { (it.status ?: "active").equals("active", ignoreCase = true) }
                    val selectedVehicle = activeVehicles.getOrNull(selectedVehicleIndex)
                        ?: run {
                            Toast.makeText(
                                requireContext(),
                                "Select an internal vehicle",
                                Toast.LENGTH_SHORT,
                            ).show()
                            throw IllegalArgumentException("Vehicle")
                        }
                    val startKm = requiredNumber(view, R.id.etStartKm, "Start kilometer")
                    val endKm = requiredNumber(view, R.id.etEndKm, "End kilometer")
                    val distanceKm = fleetTripDistanceKm(startKm, endKm)
                    if (distanceKm == null) {
                        Toast.makeText(
                            requireContext(),
                            "Check the odometer readings. Trip distance cannot exceed 5,000 km.",
                            Toast.LENGTH_LONG,
                        ).show()
                        throw IllegalArgumentException("End kilometer")
                    }
                    CompleteOfflineTripResult(
                        fleetType = "internal",
                        vehicleId = selectedVehicle?.id,
                        vehicleLabel = selectedVehicle?.let {
                            val num = it.vehicleNumber ?: "—"
                            val typePart = it.type?.takeIf { s -> s.isNotBlank() }?.let { s -> "$s · " }.orEmpty()
                            "$typePart$num"
                        },
                        driverName = selectedVehicle?.defaultDriverName?.trim()?.takeIf { s -> s.isNotBlank() },
                        driverPhone = selectedVehicle?.defaultDriverPhone?.trim()?.takeIf { s -> s.isNotBlank() },
                        agencyName = null,
                        packageAmount = optionalNumber(view, R.id.etPackageAmount, "Package price"),
                        distanceKm = distanceKm,
                        standingTimeMinutes = optionalWholeNumber(
                            view, R.id.etStandingMinutesInternal, "Standing time",
                        ),
                        standingWithAc = view.findViewById<CheckBox>(
                            R.id.checkStandingWithAcInternal,
                        ).isChecked.takeIf {
                            view.findViewById<EditText>(
                                R.id.etStandingMinutesInternal,
                            ).text.isNotBlank()
                        },
                        startKm = startKm,
                        endKm = endKm,
                        startImageUri = startImageUri,
                        endImageUri = endImageUri,
                        hillCharge = hillCharge,
                        outstationCharge = outstationCharge,
                        permitCharge = permitCharge,
                        permitTax = permitTax,
                    )
                }
                1 -> {
                    val startKm = requiredNumber(
                        view, R.id.etStartKmExternal, "Start kilometer",
                    )
                    val endKm = requiredNumber(
                        view, R.id.etEndKmExternal, "End kilometer",
                    )
                    val distanceKm = fleetTripDistanceKm(startKm, endKm)
                    if (distanceKm == null) {
                        Toast.makeText(
                            requireContext(),
                            "Check the odometer readings. Trip distance cannot exceed 5,000 km.",
                            Toast.LENGTH_LONG,
                        ).show()
                        throw IllegalArgumentException("End kilometer")
                    }
                    CompleteOfflineTripResult(
                        fleetType = "external",
                        vehicleId = null,
                        vehicleLabel = null,
                        driverName = null,
                        driverPhone = null,
                        agencyName = null,
                        packageAmount = optionalNumber(
                            view, R.id.etPackageAmountExternal, "Package price",
                        ),
                        distanceKm = distanceKm,
                        standingTimeMinutes = optionalWholeNumber(
                            view,
                            R.id.etStandingMinutesExternal,
                            "Standing time",
                        ),
                        standingWithAc = view.findViewById<CheckBox>(
                            R.id.checkStandingWithAcExternal,
                        ).isChecked.takeIf {
                            view.findViewById<EditText>(
                                R.id.etStandingMinutesExternal,
                            ).text.isNotBlank()
                        },
                        startKm = startKm,
                        endKm = endKm,
                        startImageUri = startImageUri,
                        endImageUri = endImageUri,
                        hillCharge = hillCharge,
                        outstationCharge = outstationCharge,
                        permitCharge = permitCharge,
                        permitTax = permitTax,
                    )
                }
                else -> {
                    CompleteOfflineTripResult(
                        fleetType = "own",
                        vehicleId = null,
                        vehicleLabel = null,
                        driverName = null,
                        driverPhone = null,
                        agencyName = null,
                        packageAmount = requiredNumber(view, R.id.etPackageAmountOwn, "Package price"),
                        distanceKm = optionalNumber(view, R.id.etDistanceKmOwn, "Distance travelled"),
                        standingTimeMinutes = null,
                        standingWithAc = null,
                    )
                }
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun requiredNumber(view: View, id: Int, label: String): Double {
        val value = view.findViewById<EditText>(id).text.toString().trim()
        if (value.isBlank()) {
            Toast.makeText(requireContext(), "$label is required", Toast.LENGTH_SHORT).show()
            throw IllegalArgumentException(label)
        }
        return parseNumber(value, label)
    }

    private fun optionalNumber(view: View, id: Int, label: String): Double? {
        val value = view.findViewById<EditText>(id).text.toString().trim()
        if (value.isBlank()) return null
        return parseNumber(value, label)
    }

    private fun optionalWholeNumber(view: View, id: Int, label: String): Int? {
        val value = view.findViewById<EditText>(id).text.toString().trim()
        if (value.isBlank()) return null
        val parsed = value.toIntOrNull()
        if (parsed == null || parsed < 0) {
            Toast.makeText(
                requireContext(),
                "$label must be a whole number of minutes",
                Toast.LENGTH_SHORT,
            ).show()
            throw IllegalArgumentException(label)
        }
        return parsed
    }

    private fun parseNumber(value: String, label: String): Double {
        val parsed = value.toDoubleOrNull()
        if (parsed == null || parsed < 0.0) {
            Toast.makeText(requireContext(), "$label must be zero or positive", Toast.LENGTH_SHORT).show()
            throw IllegalArgumentException(label)
        }
        return parsed
    }

    private fun Double.toDisplayText(): String =
        if (this == toLong().toDouble()) toLong().toString() else toString()

    companion object {
        fun newInstance(
            trip: AdminFleetTripsFragment.AdminTrip,
            vehicles: List<TravelDeskVehicle>,
            onSubmit: (CompleteOfflineTripResult) -> Unit,
        ): AdminFleetCompleteOfflineSheet = AdminFleetCompleteOfflineSheet().apply {
            this.trip = trip
            this.vehicles = vehicles
            this.onSubmit = onSubmit
        }
    }
}
