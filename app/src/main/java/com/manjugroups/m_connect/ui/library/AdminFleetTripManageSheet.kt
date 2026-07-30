package com.manjugroups.m_connect.ui.library

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import coil.load
import com.manjugroups.m_connect.BuildConfig
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.util.fleetTripDistanceKm

/**
 * Tap-through for an allocated agency trip. Shows the trip details + who it
 * went to + how far it's progressed, and lets the admin:
 * - Reassign the vehicle / remove the driver (before start)
 * - Advance the trip through each stage (reached → start → on-site →
 *   picked-from-site → end)
 * - Complete final fleet details after the Site Visit outcome is recorded
 */
class AdminFleetTripManageSheet : BottomSheetDialogFragment() {

    private var trip: AdminFleetTripsFragment.AdminTrip? = null
    private var onReassign: (() -> Unit)? = null
    private var onRemove: (() -> Unit)? = null
    private var onComplete: (() -> Unit)? = null
    private var onProgressAction: ((action: String, km: Double?, toll: Double?, beta: Double?) -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.setBackgroundResource(android.R.color.transparent)
                it.layoutParams = it.layoutParams.apply {
                    height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
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
    ): View = inflater.inflate(R.layout.dialog_admin_fleet_trip_manage, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val t = trip ?: run { dismissAllowingStateLoss(); return }

        view.findViewById<TextView>(R.id.tvManageTitle).text = "Site visit"
        view.findViewById<TextView>(R.id.tvManageWhen).text = t.time
        view.findViewById<TextView>(R.id.tvManageAddress).text = t.address
        view.findViewById<TextView>(R.id.tvManageVehicle).text = when {
            t.external -> t.agencyName?.let { "External · $it" } ?: "External agency"
            t.allocatedVehicle != null -> "Internal · ${t.allocatedVehicle}"
            else -> "Vehicle assigned"
        }
        view.findViewById<TextView>(R.id.tvManageDriver).text = when {
            t.driverName != null && t.driverPhone != null -> "${t.driverName} · ${t.driverPhone}"
            t.driverName != null -> t.driverName!!
            else -> "Driver not set"
        }
        view.findViewById<TextView>(R.id.tvManageProgress).text =
            if (t.expired) "Expired" else t.progressLabel

        bindStatsBar(view, t)
        bindTripRecord(view, t)
        bindProgressActions(view, t)

        val actions = view.findViewById<View>(R.id.manageActions)
        val note = view.findViewById<TextView>(R.id.tvManageNote)
        val completed = t.status == "Completed"
        val canCompleteDetails = t.outcomeRecorded && onComplete != null

        if (t.started || completed || t.expired || t.outcomeRecorded) {
            actions.visibility = if (canCompleteDetails) View.VISIBLE else View.GONE
            note.visibility = View.VISIBLE
            note.text = when {
                completed && canCompleteDetails ->
                    "This trip is completed. You can correct its fleet and billing details."
                canCompleteDetails ->
                    "The Site Visit outcome is recorded. Complete the remaining fleet details."
                completed -> "This trip is completed."
                t.outcomeRecorded -> "Waiting for the responsible fleet to complete the remaining trip details."
                t.expired -> "This trip's date has passed."
                else -> "Trip has started — the driver is running it now."
            }
            if (canCompleteDetails) {
                view.findViewById<android.widget.Button>(R.id.btnManageReassign).apply {
                    text = if (completed) "Edit trip details" else "Complete trip details"
                    setOnClickListener {
                        onComplete?.invoke()
                        dismissAllowingStateLoss()
                    }
                }
                view.findViewById<android.widget.Button>(R.id.btnManageRemove).visibility = View.GONE
            }
        } else {
            actions.visibility = View.VISIBLE
            note.visibility = View.GONE
            view.findViewById<android.widget.Button>(R.id.btnManageReassign).apply {
                text = if (t.external) "Change agency" else "Reassign"
                setOnClickListener {
                    onReassign?.invoke()
                    dismissAllowingStateLoss()
                }
            }
            view.findViewById<android.widget.Button>(R.id.btnManageRemove).apply {
                visibility = View.VISIBLE
                text = if (t.external) "Remove agency" else "Remove driver"
                setOnClickListener {
                    onRemove?.invoke()
                    dismissAllowingStateLoss()
                }
            }
        }
    }

    /**
     * Shows the next progress-action the admin can take for an active trip.
     *
     * Stage progression:
     *   Awaiting pickup → "Mark Reached"
     *   Reached client  → "Start trip" (needs km)
     *   Picked from CP  → "Mark On Site"
     *   On site          → "Mark Picked from Site"
     *   Picked from Site → "End trip" (needs km + toll + beta)
     *   Dropped          → nothing (completed)
     */
    private fun bindProgressActions(view: View, t: AdminFleetTripsFragment.AdminTrip) {
        val container = view.findViewById<View>(R.id.manageProgressActions)
        val btnLabel = view.findViewById<TextView>(R.id.tvProgressActionLabel)
        val hint = view.findViewById<TextView>(R.id.tvProgressActionHint)
        val kmLayout = view.findViewById<View>(R.id.layoutProgressKm)
        val tollBetaLayout = view.findViewById<View>(R.id.layoutProgressTollBeta)
        val btn = view.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnProgressAction)
        val error = view.findViewById<TextView>(R.id.tvProgressActionError)

        if (t.expired || t.status == "Completed" || t.outcomeRecorded) {
            container.visibility = View.GONE
            return
        }

        val nextAction = nextActionForStage(t.progressLabel)
        if (nextAction == null) {
            container.visibility = View.GONE
            return
        }

        container.visibility = View.VISIBLE
        btnLabel.text = nextAction.label
        hint.text = nextAction.hint
        error.visibility = View.GONE

        kmLayout.visibility = if (nextAction.needsKm) View.VISIBLE else View.GONE
        tollBetaLayout.visibility = if (nextAction.needsTollBeta) View.VISIBLE else View.GONE

        btn.text = nextAction.buttonText
        btn.setOnClickListener {
            val kmText = view.findViewById<TextView>(R.id.etProgressKm)?.text?.toString()?.trim()
            val tollText = view.findViewById<TextView>(R.id.etProgressToll)?.text?.toString()?.trim()
            val betaText = view.findViewById<TextView>(R.id.etProgressBeta)?.text?.toString()?.trim()

            val km = kmText?.toDoubleOrNull()
            val toll = tollText?.toDoubleOrNull()
            val beta = betaText?.toDoubleOrNull()

            btn.isEnabled = false
            onProgressAction?.invoke(nextAction.action, km, toll, beta)
        }
    }

    private fun nextActionForStage(progressLabel: String): ProgressAction? {
        return when (progressLabel.trim().lowercase()) {
            "awaiting pickup", "" -> ProgressAction(
                action = "reached",
                label = "Update progress",
                hint = "Mark that the driver has reached the client.",
                buttonText = "Mark Reached",
            )
            "reached client" -> ProgressAction(
                action = "start",
                label = "Start trip",
                hint = "Driver has reached and is picking up from CP.",
                buttonText = "Start trip",
                needsKm = true,
            )
            "picked from cp", "picked up" -> ProgressAction(
                action = "on_site",
                label = "Update progress",
                hint = "Mark that the driver has arrived at the site.",
                buttonText = "Mark On Site",
            )
            "on site" -> ProgressAction(
                action = "picked_from_site",
                label = "Update progress",
                hint = "Mark that the passenger has been picked from site.",
                buttonText = "Mark Picked from Site",
            )
            "picked from site" -> ProgressAction(
                action = "end",
                label = "End trip",
                hint = "Driver is dropping the passenger. Enter final details.",
                buttonText = "End Trip",
                needsKm = true,
                needsTollBeta = true,
            )
            else -> null
        }
    }

    private data class ProgressAction(
        val action: String,
        val label: String,
        val hint: String,
        val buttonText: String,
        val needsKm: Boolean = false,
        val needsTollBeta: Boolean = false,
    )

    private fun bindStatsBar(view: View, t: AdminFleetTripsFragment.AdminTrip) {
        val stage = when (t.progressLabel.trim().lowercase()) {
            "dropped", "completed", "complete" -> 4
            "picked from site" -> 3
            "on site" -> 2
            "picked from cp", "picked up" -> 1
            else -> 0
        }
        val green = android.graphics.Color.parseColor("#12B76A")
        val segs = listOf(
            R.id.statSeg0, R.id.statSeg1, R.id.statSeg2, R.id.statSeg3, R.id.statSeg4,
        )
        segs.forEachIndexed { i, id ->
            view.findViewById<View>(id).setBackgroundResource(
                if (i <= stage) R.drawable.bg_trip_progress_line_active
                else R.drawable.bg_trip_progress_line_inactive,
            )
        }
        val labels = listOf(
            R.id.statLbl0, R.id.statLbl1, R.id.statLbl2, R.id.statLbl3, R.id.statLbl4,
        )
        labels.forEachIndexed { i, id ->
            view.findViewById<TextView>(id).setTextColor(
                if (i <= stage) green else android.graphics.Color.parseColor("#98A2B3"),
            )
        }
    }

    private fun bindTripRecord(view: View, t: AdminFleetTripsFragment.AdminTrip) {
        val record = view.findViewById<View>(R.id.tripRecord)
        if (!t.started && t.status != "Completed") {
            record.visibility = View.GONE
            return
        }
        record.visibility = View.VISIBLE

        bindPhoto(
            view.findViewById(R.id.ivStartPhoto),
            view.findViewById(R.id.tvStartPhotoEmpty),
            t.startPhotoId,
        )
        bindPhoto(
            view.findViewById(R.id.ivEndPhoto),
            view.findViewById(R.id.tvEndPhotoEmpty),
            t.endPhotoId,
        )

        view.findViewById<TextView>(R.id.tvStartKm).text =
            t.startKm?.let { "${fmtKm(it)} km" } ?: "Km —"
        view.findViewById<TextView>(R.id.tvEndKm).text =
            t.endKm?.let { "${fmtKm(it)} km" } ?: "Km —"

        val total = view.findViewById<TextView>(R.id.tvTotalKm)
        val start = t.startKm
        val end = t.endKm
        val distanceKm =
            if (start != null && end != null) fleetTripDistanceKm(start, end) else null
        if (distanceKm != null) {
            total.visibility = View.VISIBLE
            total.text = "Total km travelled: ${fmtKm(distanceKm)} km"
        } else if (start != null && end != null) {
            total.visibility = View.VISIBLE
            total.text = "Check odometer readings"
        } else {
            total.visibility = View.GONE
        }

        val billing = view.findViewById<TextView>(R.id.tvBillingDetails)
        val billingLines = listOfNotNull(
            t.packageAmount?.let { "Package: ${fmtMoney(it)}" },
            t.beta?.let { "Beta: ${fmtMoney(it)}" },
            t.tollAmount?.let { "Toll: ${fmtMoney(it)}" },
            t.hillCharge?.let { "Hill charge: ${fmtMoney(it)}" },
            t.outstationCharge?.let { "Outstation charge: ${fmtMoney(it)}" },
            t.permitCharge?.let { "Permit charge: ${fmtMoney(it)}" },
            t.permitTax?.let { "Permit tax: ${fmtMoney(it)}" },
            t.standingCharge?.let { "Standing charge: ${fmtMoney(it)}" },
        ) +
            t.customCharges.mapNotNull { charge ->
                val label = charge.label?.trim().orEmpty()
                val amount = charge.amount
                if (label.isEmpty() || amount == null) null
                else "$label: ${fmtMoney(amount)}"
            } +
            listOfNotNull(
                t.totalAmount?.let { "Total: ${fmtMoney(it)}" },
            )
        billing.visibility = if (billingLines.isEmpty()) View.GONE else View.VISIBLE
        billing.text = billingLines.joinToString("\n")
    }

    private fun bindPhoto(image: ImageView, empty: TextView, storageId: String?) {
        val box = image.parent as? View
        if (storageId.isNullOrBlank()) {
            image.visibility = View.GONE
            empty.visibility = View.VISIBLE
            empty.text = "Not captured"
            box?.background = null
            return
        }
        image.visibility = View.VISIBLE
        empty.visibility = View.GONE
        box?.setBackgroundResource(R.drawable.bg_trip_detail_map_card)
        val url = "${BuildConfig.BASE_URL}api/storage/serve?storageId=$storageId"
        image.load(url)
    }

    private fun fmtKm(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.1f", v)

    private fun fmtMoney(value: Double): String =
        "₹" + if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            String.format("%.2f", value)
        }

    /** Called by the host after a progress API call completes. */
    fun showProgressError(message: String) {
        val view = view ?: return
        val error = view.findViewById<TextView>(R.id.tvProgressActionError)
        val btn = view.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnProgressAction)
        error.text = message
        error.visibility = View.VISIBLE
        btn.isEnabled = true
    }

    companion object {
        fun newInstance(
            trip: AdminFleetTripsFragment.AdminTrip,
            onReassign: () -> Unit,
            onRemove: () -> Unit,
            onComplete: (() -> Unit)?,
            onProgressAction: (action: String, km: Double?, toll: Double?, beta: Double?) -> Unit,
        ): AdminFleetTripManageSheet = AdminFleetTripManageSheet().apply {
            this.trip = trip
            this.onReassign = onReassign
            this.onRemove = onRemove
            this.onComplete = onComplete
            this.onProgressAction = onProgressAction
        }
    }
}
