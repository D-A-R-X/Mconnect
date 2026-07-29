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
    val kmRate: Double? = null,
    val distanceKm: Double?,
    val standingTimeMinutes: Int?,
    val standingWithAc: Boolean?,
    val startKm: Double? = null,
    val endKm: Double? = null,
    val startImageUri: Uri? = null,
    val endImageUri: Uri? = null,
    val beta: Double? = null,
    val beta2: Double? = null,
    val tollAmount: Double? = null,
    val hillCharge: Double? = null,
    val outstationCharge: Double? = null,
    val permitCharge: Double? = null,
    val permitTax: Double? = null,
    val standingCharge: Double? = null,
)

class AdminFleetCompleteOfflineSheet : BottomSheetDialogFragment() {

    private var trip: AdminFleetTripsFragment.AdminTrip? = null
    private var vehicles: List<TravelDeskVehicle> = emptyList()
    private var onSubmit: ((CompleteOfflineTripResult) -> Unit)? = null

    private var selectedVehicleIndex: Int = -1
    private var selectedExternalVehicleIndex: Int = -1
    // Internal km-priced trips edit a per-km rate instead of a flat package.
    private var internalKmMode: Boolean = false
    private var activeFleetIndex: Int = 0
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
            activeFleetIndex = position
            recomputeBillingTotal(view)
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
        view.findViewById<EditText>(R.id.etBeta)
            .setText(t.beta?.toDisplayText().orEmpty())
        view.findViewById<EditText>(R.id.etBeta2)
            .setText(t.beta2?.toDisplayText().orEmpty())
        view.findViewById<EditText>(R.id.etTollAmount)
            .setText(t.tollAmount?.toDisplayText().orEmpty())
        view.findViewById<EditText>(R.id.etHillCharge)
            .setText(t.hillCharge?.toDisplayText().orEmpty())
        view.findViewById<EditText>(R.id.etOutstationCharge)
            .setText(t.outstationCharge?.toDisplayText().orEmpty())
        view.findViewById<EditText>(R.id.etPermitCharge)
            .setText(t.permitCharge?.toDisplayText().orEmpty())
        view.findViewById<EditText>(R.id.etPermitTax)
            .setText(t.permitTax?.toDisplayText().orEmpty())
        view.findViewById<EditText>(R.id.etStandingCharge)
            .setText(t.standingCharge?.toDisplayText().orEmpty())
        setupKeyboardAwareScrolling(view)
        wireBillingTotal(view)
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

        // Match the web recomplete form: km-priced trips edit a per-km rate.
        internalKmMode = t.pricingMode.equals("km", ignoreCase = true)
        val baseLabel = view.findViewById<android.widget.TextView>(R.id.tvInternalBaseLabel)
        if (internalKmMode) {
            baseLabel.text = "Per km rate"
            etPackage.hint = "Enter per km rate"
            etPackage.setText(t.kmRate?.toDisplayText().orEmpty())
        } else {
            baseLabel.text = "Package price"
            etPackage.hint = "Enter package price"
            etPackage.setText(t.packageAmount?.toDisplayText().orEmpty())
        }
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
        val activeVehicles = vehicles
            .filter { (it.status ?: "active").equals("active", ignoreCase = true) }
        val vehicleLabels = activeVehicles.map {
            listOfNotNull(
                it.vehicleNumber?.trim()?.takeIf(String::isNotBlank),
                it.model?.trim()?.takeIf(String::isNotBlank),
            ).joinToString(" · ").ifBlank { "Vehicle" }
        }
        val spinner = view.findViewById<Spinner>(R.id.spinnerExternalVehicle)
        spinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            vehicleLabels,
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        selectedExternalVehicleIndex =
            activeVehicles.indexOfFirst { it.vehicleNumber == t.allocatedVehicle }
                .takeIf { it >= 0 } ?: if (activeVehicles.isNotEmpty()) 0 else -1
        if (selectedExternalVehicleIndex >= 0) {
            spinner.setSelection(selectedExternalVehicleIndex)
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                selectedView: View?,
                position: Int,
                id: Long,
            ) {
                selectedExternalVehicleIndex = position
                val selected = activeVehicles.getOrNull(position) ?: return
                if (view.findViewById<EditText>(R.id.etExternalDriverName).text.isBlank()) {
                    view.findViewById<EditText>(R.id.etExternalDriverName)
                        .setText(selected.defaultDriverName.orEmpty())
                }
                if (view.findViewById<EditText>(R.id.etExternalDriverPhone).text.isBlank()) {
                    view.findViewById<EditText>(R.id.etExternalDriverPhone)
                        .setText(selected.defaultDriverPhone.orEmpty())
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        view.findViewById<EditText>(R.id.etExternalDriverName)
            .setText(t.driverName.orEmpty())
        view.findViewById<EditText>(R.id.etExternalDriverPhone)
            .setText(t.driverPhone.orEmpty())
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
            R.id.etExternalDriverName,
            R.id.etExternalDriverPhone,
            R.id.etDistanceKmExternal,
            R.id.etStartKmExternal,
            R.id.etEndKmExternal,
            R.id.etStandingMinutesExternal,
            R.id.etBeta,
            R.id.etBeta2,
            R.id.etTollAmount,
            R.id.etHillCharge,
            R.id.etOutstationCharge,
            R.id.etPermitCharge,
            R.id.etPermitTax,
            R.id.etStandingCharge,
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

    /** Recompute the live billing total whenever any amount/odometer changes. */
    private fun wireBillingTotal(view: View) {
        val watched = listOf(
            R.id.etPackageAmount, R.id.etStartKm, R.id.etEndKm,
            R.id.etPackageAmountExternal, R.id.etStartKmExternal, R.id.etEndKmExternal,
            R.id.etPackageAmountOwn, R.id.etDistanceKmOwn,
            R.id.etBeta, R.id.etBeta2, R.id.etTollAmount, R.id.etHillCharge,
            R.id.etOutstationCharge, R.id.etPermitCharge, R.id.etPermitTax,
            R.id.etStandingCharge,
        )
        watched.forEach { id ->
            view.findViewById<EditText>(id).doAfterTextChanged { recomputeBillingTotal(view) }
        }
        recomputeBillingTotal(view)
    }

    /**
     * Mirrors the web recomplete form's billing preview: base (per-km rate ×
     * distance for km-priced internal trips, otherwise the flat package) plus
     * every optional charge. Shows "—" until a km-priced base can be computed.
     */
    private fun recomputeBillingTotal(view: View) {
        val tv = view.findViewById<android.widget.TextView>(R.id.tvBillingTotal) ?: return
        fun num(id: Int): Double =
            view.findViewById<EditText>(id).text.toString().toDoubleOrNull()
                ?.takeIf { it >= 0.0 } ?: 0.0
        fun raw(id: Int): Double? =
            view.findViewById<EditText>(id).text.toString().toDoubleOrNull()

        val charges = num(R.id.etBeta) + num(R.id.etBeta2) + num(R.id.etTollAmount) +
            num(R.id.etHillCharge) + num(R.id.etOutstationCharge) +
            num(R.id.etPermitCharge) + num(R.id.etPermitTax) + num(R.id.etStandingCharge)

        val base: Double? = when (activeFleetIndex) {
            0 -> if (internalKmMode) {
                val start = raw(R.id.etStartKm)
                val end = raw(R.id.etEndKm)
                if (start != null && end != null && end >= start) {
                    (end - start) * num(R.id.etPackageAmount)
                } else {
                    null
                }
            } else {
                num(R.id.etPackageAmount)
            }
            1 -> num(R.id.etPackageAmountExternal)
            else -> num(R.id.etPackageAmountOwn)
        }

        tv.text = if (base == null) {
            "Billing total: —"
        } else {
            "Billing total: ₹${(base + charges).toDisplayText()}"
        }
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
            val beta = optionalNumber(view, R.id.etBeta, "Beta")
            val beta2 = optionalNumber(view, R.id.etBeta2, "Beta 2")
            val tollAmount = optionalNumber(view, R.id.etTollAmount, "Toll charge")
            val hillCharge = optionalNumber(view, R.id.etHillCharge, "Hill charge")
            val outstationCharge = optionalNumber(
                view, R.id.etOutstationCharge, "Outstation charge",
            )
            val permitCharge = optionalNumber(view, R.id.etPermitCharge, "Permit charge")
            val permitTax = optionalNumber(view, R.id.etPermitTax, "Permit tax")
            val standingCharge = optionalNumber(
                view, R.id.etStandingCharge, "Standing charge",
            )
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
                        packageAmount = if (internalKmMode) null else optionalNumber(
                            view, R.id.etPackageAmount, "Package price",
                        ),
                        kmRate = if (internalKmMode) optionalNumber(
                            view, R.id.etPackageAmount, "Per km rate",
                        ) else null,
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
                        beta = beta,
                        beta2 = beta2,
                        tollAmount = tollAmount,
                        hillCharge = hillCharge,
                        outstationCharge = outstationCharge,
                        permitCharge = permitCharge,
                        permitTax = permitTax,
                        standingCharge = standingCharge,
                    )
                }
                1 -> {
                    val activeVehicles = vehicles.filter {
                        (it.status ?: "active").equals("active", ignoreCase = true)
                    }
                    val selectedVehicle = activeVehicles.getOrNull(
                        selectedExternalVehicleIndex,
                    ) ?: run {
                        Toast.makeText(
                            requireContext(),
                            "Select the vehicle sent for this trip",
                            Toast.LENGTH_SHORT,
                        ).show()
                        throw IllegalArgumentException("Vehicle")
                    }
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
                        vehicleId = selectedVehicle.id,
                        vehicleLabel = selectedVehicle.vehicleNumber,
                        driverName = view.findViewById<EditText>(
                            R.id.etExternalDriverName,
                        ).text.toString().trim().ifBlank { null },
                        driverPhone = view.findViewById<EditText>(
                            R.id.etExternalDriverPhone,
                        ).text.toString().trim().ifBlank { null },
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
                        beta = beta,
                        beta2 = beta2,
                        tollAmount = tollAmount,
                        hillCharge = hillCharge,
                        outstationCharge = outstationCharge,
                        permitCharge = permitCharge,
                        permitTax = permitTax,
                        standingCharge = standingCharge,
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
