package com.manjugroups.m_connect.ui.library

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.network.TravelDeskVehicle

data class CompleteOfflineTripResult(
    val fleetType: String,
    val vehicleId: String?,
    val vehicleLabel: String?,
    val driverName: String?,
    val driverPhone: String?,
    val agencyName: String?,
    val packageAmount: Double,
    val distanceKm: Double?,
)

class AdminFleetCompleteOfflineSheet : BottomSheetDialogFragment() {

    private var trip: AdminFleetTripsFragment.AdminTrip? = null
    private var vehicles: List<TravelDeskVehicle> = emptyList()
    private var onSubmit: ((CompleteOfflineTripResult) -> Unit)? = null

    private var selectedVehicleIndex: Int = -1

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
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
        val spinner = view.findViewById<Spinner>(R.id.spinnerFleetType)

        val fleetTypes = listOf("Internal", "External", "Own Vehicle")
        val spinnerAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            fleetTypes,
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinner.adapter = spinnerAdapter

        val defaultIndex = if (t.external) 1 else 0
        spinner.setSelection(defaultIndex)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                layoutInternal.visibility = if (pos == 0) View.VISIBLE else View.GONE
                layoutExternal.visibility = if (pos == 1) View.VISIBLE else View.GONE
                layoutOwn.visibility = if (pos == 2) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        setupInternalFleet(view, t)
        setupExternalFleet(view, t)
        setupOwnVehicle(view, t)

        view.findViewById<View>(R.id.btnCancelComplete).setOnClickListener {
            dismissAllowingStateLoss()
        }
        view.findViewById<View>(R.id.btnSubmitComplete).setOnClickListener {
            val result = parseResult(view, spinner.selectedItemPosition) ?: return@setOnClickListener
            onSubmit?.invoke(result)
            dismissAllowingStateLoss()
        }
    }

    private fun setupInternalFleet(view: View, t: AdminFleetTripsFragment.AdminTrip) {
        val tvVehicle = view.findViewById<android.widget.TextView>(R.id.tvVehicleSelector)
        val tvDriver = view.findViewById<android.widget.TextView>(R.id.tvSelectedDriver)
        val etPackage = view.findViewById<EditText>(R.id.etPackageAmount)
        val etDistance = view.findViewById<EditText>(R.id.etDistanceKm)

        etPackage.setText(t.packageAmount?.toDisplayText().orEmpty())
        etDistance.setText(t.distanceKm?.toDisplayText().orEmpty())

        val vehicleOptions = vehicles
            .filter { (it.status ?: "active").equals("active", ignoreCase = true) }
            .map {
                val number = it.vehicleNumber ?: "—"
                val typePart = it.type?.takeIf { s -> s.isNotBlank() }?.let { s -> "$s · " }.orEmpty()
                "$typePart$number"
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
        view.findViewById<EditText>(R.id.etAgencyName).setText(t.agencyName.orEmpty())
        view.findViewById<EditText>(R.id.etPackageAmountExternal)
            .setText(t.packageAmount?.toDisplayText().orEmpty())
        view.findViewById<EditText>(R.id.etDistanceKmExternal)
            .setText(t.distanceKm?.toDisplayText().orEmpty())
    }

    private fun setupOwnVehicle(view: View, t: AdminFleetTripsFragment.AdminTrip) {
        view.findViewById<EditText>(R.id.etPackageAmountOwn)
            .setText(t.packageAmount?.toDisplayText().orEmpty())
        view.findViewById<EditText>(R.id.etDistanceKmOwn)
            .setText(t.distanceKm?.toDisplayText().orEmpty())
    }

    private fun parseResult(view: View, fleetTypeIndex: Int): CompleteOfflineTripResult? {
        return try {
            val t = trip!!
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
                        packageAmount = requiredNumber(view, R.id.etPackageAmount, "Package price"),
                        distanceKm = optionalNumber(view, R.id.etDistanceKm, "Distance travelled"),
                    )
                }
                1 -> {
                    val agencyName = view.findViewById<EditText>(R.id.etAgencyName)
                        .text.toString().trim().takeIf { it.isNotBlank() }
                    CompleteOfflineTripResult(
                        fleetType = "external",
                        vehicleId = null,
                        vehicleLabel = null,
                        driverName = null,
                        driverPhone = null,
                        agencyName = agencyName,
                        packageAmount = requiredNumber(view, R.id.etPackageAmountExternal, "Package price"),
                        distanceKm = optionalNumber(view, R.id.etDistanceKmExternal, "Distance travelled"),
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
