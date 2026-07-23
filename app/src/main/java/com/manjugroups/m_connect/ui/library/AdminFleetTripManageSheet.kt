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

/**
 * Tap-through for an allocated agency trip. Shows the trip details + who it
 * went to + how far it's progressed, and lets the admin reassign the vehicle
 * or remove the driver — the same actions the travel-desk web offers.
 *
 * Reassign/Remove are hidden once the driver has set off (the backend refuses
 * both then) and for completed/expired trips, which are read-only history.
 */
class AdminFleetTripManageSheet : BottomSheetDialogFragment() {

    private var trip: AdminFleetTripsFragment.AdminTrip? = null
    private var onReassign: (() -> Unit)? = null
    private var onRemove: (() -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.setBackgroundResource(android.R.color.transparent)
                // The host defaults to match_parent, so an EXPANDED sheet leaves
                // a white gap below short content. Wrap it to the content height.
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

        view.findViewById<TextView>(R.id.tvManageTitle).text =
            t.address.takeIf { it.isNotBlank() && !it.startsWith("Project:") }
                ?.let { "Site visit" } ?: "Site visit"
        view.findViewById<TextView>(R.id.tvManageWhen).text = t.time
        view.findViewById<TextView>(R.id.tvManageAddress).text = t.address
        view.findViewById<TextView>(R.id.tvManageVehicle).text =
            t.allocatedVehicle?.let { "Vehicle: $it" } ?: "Vehicle assigned"
        view.findViewById<TextView>(R.id.tvManageDriver).text = when {
            t.driverName != null && t.driverPhone != null -> "${t.driverName} · ${t.driverPhone}"
            t.driverName != null -> t.driverName!!
            else -> "Driver not set"
        }
        view.findViewById<TextView>(R.id.tvManageProgress).text =
            if (t.expired) "Expired" else t.progressLabel

        bindStatsBar(view, t)
        bindTripRecord(view, t)

        val actions = view.findViewById<View>(R.id.manageActions)
        val note = view.findViewById<TextView>(R.id.tvManageNote)
        val completed = t.status == "Completed"

        // Once started (or completed/expired), the assignment is locked: the
        // backend rejects reassign/unallocate, so offer read-only tracking.
        if (t.started || completed || t.expired) {
            actions.visibility = View.GONE
            note.visibility = View.VISIBLE
            note.text = when {
                completed -> "This trip is completed."
                t.expired -> "This trip's date has passed."
                else -> "Trip has started — the driver is running it now."
            }
        } else {
            actions.visibility = View.VISIBLE
            note.visibility = View.GONE
            view.findViewById<View>(R.id.btnManageReassign).setOnClickListener {
                onReassign?.invoke()
                dismissAllowingStateLoss()
            }
            view.findViewById<View>(R.id.btnManageRemove).setOnClickListener {
                onRemove?.invoke()
                dismissAllowingStateLoss()
            }
        }
    }

    /**
     * The 5-stage trip stats bar the driver advances live (Assigned → Picked
     * from CP → On Site → Picked from Site → Dropped). Segments up to and
     * including the current stage turn green; the current stage's label is
     * highlighted. Derived from the same progress label the card shows, so it
     * tracks whatever the driver last reported.
     */
    private fun bindStatsBar(view: View, t: AdminFleetTripsFragment.AdminTrip) {
        val stage = when (t.progressLabel.trim().lowercase()) {
            "dropped", "completed", "complete" -> 4
            "picked from site" -> 3
            "on site" -> 2
            "picked from cp", "picked up" -> 1
            else -> 0 // Assigned / Reached client / Awaiting pickup
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

    /**
     * The driver's captured record — start/end dashboard photos and km. Shown
     * once the trip has started (that's when the first photo exists); each half
     * degrades to an empty state when its photo or km wasn't captured.
     */
    private fun bindTripRecord(view: View, t: AdminFleetTripsFragment.AdminTrip) {
        val record = view.findViewById<View>(R.id.tripRecord)
        // No record to show before the trip starts.
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
        if (start != null && end != null && end >= start) {
            total.visibility = View.VISIBLE
            total.text = "Distance: ${fmtKm(end - start)} km"
        } else {
            total.visibility = View.GONE
        }
    }

    private fun bindPhoto(image: ImageView, empty: TextView, storageId: String?) {
        // The box only looks like a real photo card when there IS a photo.
        // With none, drop the card chrome so an empty bordered box doesn't read
        // as a broken / placeholder image.
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
        // /api/storage/serve is a public route, so Coil loads it by URL.
        val url = "${BuildConfig.BASE_URL}api/storage/serve?storageId=$storageId"
        image.load(url)
    }

    private fun fmtKm(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.1f", v)

    companion object {
        fun newInstance(
            trip: AdminFleetTripsFragment.AdminTrip,
            onReassign: () -> Unit,
            onRemove: () -> Unit,
        ): AdminFleetTripManageSheet = AdminFleetTripManageSheet().apply {
            this.trip = trip
            this.onReassign = onReassign
            this.onRemove = onRemove
        }
    }
}
