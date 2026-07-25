package com.manjugroups.m_connect.ui.library

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R

data class CompleteOfflineTripResult(
    val packageAmount: Double,
    val distanceKm: Double?,
    val driverName: String?,
    val driverPhone: String?,
    val beta: Double?,
    val tollAmount: Double?,
)

class AdminFleetCompleteOfflineSheet : BottomSheetDialogFragment() {

    private var trip: AdminFleetTripsFragment.AdminTrip? = null
    private var onSubmit: ((CompleteOfflineTripResult) -> Unit)? = null

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

        view.findViewById<EditText>(R.id.etPackageAmount).setText(t.packageAmount?.toDisplayText().orEmpty())
        view.findViewById<EditText>(R.id.etDistanceKm).setText(t.distanceKm?.toDisplayText().orEmpty())
        view.findViewById<EditText>(R.id.etDriverName).setText(t.driverName.orEmpty())
        view.findViewById<EditText>(R.id.etDriverPhone).setText(t.driverPhone.orEmpty())
        view.findViewById<EditText>(R.id.etBeta).setText(t.beta?.toDisplayText().orEmpty())
        view.findViewById<EditText>(R.id.etTollAmount).setText(t.tollAmount?.toDisplayText().orEmpty())

        view.findViewById<View>(R.id.btnCancelComplete).setOnClickListener {
            dismissAllowingStateLoss()
        }
        view.findViewById<View>(R.id.btnSubmitComplete).setOnClickListener {
            val result = parseResult(view) ?: return@setOnClickListener
            onSubmit?.invoke(result)
            dismissAllowingStateLoss()
        }
    }

    private fun parseResult(view: View): CompleteOfflineTripResult? {
        return try {
            CompleteOfflineTripResult(
                packageAmount = requiredNumber(view, R.id.etPackageAmount, "Package price"),
                distanceKm = optionalNumber(view, R.id.etDistanceKm, "Distance travelled"),
                driverName = view.findViewById<EditText>(R.id.etDriverName)
                    .text.toString().trim().takeIf { it.isNotBlank() },
                driverPhone = view.findViewById<EditText>(R.id.etDriverPhone)
                    .text.toString().trim().takeIf { it.isNotBlank() },
                beta = optionalNumber(view, R.id.etBeta, "Beta"),
                tollAmount = optionalNumber(view, R.id.etTollAmount, "Toll amount"),
            )
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
            onSubmit: (CompleteOfflineTripResult) -> Unit,
        ): AdminFleetCompleteOfflineSheet = AdminFleetCompleteOfflineSheet().apply {
            this.trip = trip
            this.onSubmit = onSubmit
        }
    }
}
