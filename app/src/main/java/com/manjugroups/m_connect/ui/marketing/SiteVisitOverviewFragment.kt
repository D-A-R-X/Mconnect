package com.manjugroups.m_connect.ui.marketing

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.app.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.CompleteVisitRequest
import com.manjugroups.m_connect.network.CpVisitDetail
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.MarkClientMetRequest
import com.manjugroups.m_connect.network.SetOutcomeRequest
import com.manjugroups.m_connect.network.SetSiteVisitOutcomeRequest
// SiteVisitIdRequest import removed — the mobile no longer fires
// markSiteVisitPickedUp / markSiteVisitArrivedSite / markSiteVisitDropped.
// Trip-state advancement is web-only now (see wireStepperReadOnlyHint).
import com.manjugroups.m_connect.network.TodayVisit
import com.manjugroups.m_connect.ui.home.CompleteCpVisitBottomSheet
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SiteVisitOverviewFragment : BottomSheetDialogFragment() {

    private val geoApi = GeoTrackApi.create()
    private val api = ApiService.create()
    private lateinit var session: SessionManager

    private var visitId: String? = null
    private var cpVisitId: String? = null

    // Track the current step index (0=Scheduled, 1=Assigned, 2=Picked
    // Up, 3=On Site, 4=Dropped, 5=Done). Updated whenever the server
    // returns a fresh status — drives stepper colouring AND gates
    // which next-step button is clickable.
    private var currentStepIndex: Int = 0
    // Lock for the lifecycle-transition button so a double-tap doesn't
    // fire two markPickedUp calls back-to-back.
    // `transitioning` flag removed — mobile no longer advances the SV
    // through markSiteVisitPickedUp / markSiteVisitArrivedSite /
    // markSiteVisitDropped. The web is the single source of truth for
    // trip progress; mobile re-reads it on every loadEnrichedDetail.
    private var isOwnVehicleSelected: Boolean = false
    private var isOutcomeLocked: Boolean = false

    // UI elements
    private var tvTitle: TextView? = null
    private var tvStatus: TextView? = null
    private var vStatusDot: View? = null
    private var tvDateTime: TextView? = null
    private var tvType: TextView? = null

    private var tvClientName: TextView? = null
    private var tvPhone: TextView? = null
    private var tvProject: TextView? = null
    private var tvBdo: TextView? = null
    private var tvPickupAddress: TextView? = null
    private var tvAttendees: TextView? = null
    private var tvIncharge: TextView? = null

    private var tvVisitorName: TextView? = null
    private var tvVisitorDetails: TextView? = null
    private var tvNotes: TextView? = null

    // Read-only vehicle-type badge. Mobile no longer flips the travel
    // mode — the WEB owns that decision; mobile just renders whichever
    // flow (Own Vehicle / Cab Vehicle) the office picked so the stepper
    // below matches. The id stays so existing label-set calls keep
    // working — the LinearLayout that used to be a dropdown is no
    // longer click-bound and the chevron is gone from the layout.
    private var tvSelectedVehicleType: TextView? = null

    // Stepper layouts
    private var layoutCabStepper: View? = null
    private var layoutOwnStepper: View? = null

    // Stepper views
    private var stepLine1: View? = null
    private var stepLine2: View? = null
    private var stepLine3: View? = null
    private var stepLine4: View? = null
    private var stepLine5: View? = null

    private var circleScheduled: FrameLayout? = null
    private var circleAssigned: FrameLayout? = null
    private var circlePickedUp: FrameLayout? = null
    private var circleOnSite: FrameLayout? = null
    private var circleDropped: FrameLayout? = null
    private var circleDone: FrameLayout? = null

    private var ivScheduled: ImageView? = null
    private var ivAssigned: ImageView? = null
    private var ivPickedUp: ImageView? = null
    private var ivOnSite: ImageView? = null
    private var ivDropped: ImageView? = null
    private var ivDone: ImageView? = null

    private var labelScheduled: TextView? = null
    private var labelAssigned: TextView? = null
    private var labelPickedUp: TextView? = null
    private var labelOnSite: TextView? = null
    private var labelDropped: TextView? = null
    private var labelDone: TextView? = null

    // Own Stepper lines
    private var stepLineOwn1: View? = null
    private var stepLineOwn2: View? = null
    private var stepLineOwn3: View? = null

    // Own Stepper circles
    private var circleOwnScheduled: FrameLayout? = null
    private var circleOwnClientDeparture: FrameLayout? = null
    private var circleOwnOnSite: FrameLayout? = null
    private var circleOwnDone: FrameLayout? = null

    // Own Stepper icons
    private var ivOwnScheduled: ImageView? = null
    private var ivOwnClientDeparture: ImageView? = null
    private var ivOwnOnSite: ImageView? = null
    private var ivOwnDone: ImageView? = null

    // Own Stepper labels
    private var labelOwnScheduled: TextView? = null
    private var labelOwnClientDeparture: TextView? = null
    private var labelOwnOnSite: TextView? = null
    private var labelOwnDone: TextView? = null

    // Outcome Buttons
    private var btnBooking: LinearLayout? = null
    private var btnNotInterested: LinearLayout? = null
    private var btnPostponed: LinearLayout? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.setBackgroundColor(Color.TRANSPARENT)
                val behavior = BottomSheetBehavior.from(it)
                val displayMetrics = resources.displayMetrics
                val peekHeightPx = (displayMetrics.heightPixels * 0.65).toInt()
                behavior.peekHeight = peekHeightPx
                behavior.state = BottomSheetBehavior.STATE_COLLAPSED
                behavior.skipCollapsed = false
                behavior.isDraggable = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_site_visit_overview, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        // Initialize UI elements
        tvTitle = view.findViewById(R.id.tvOverviewTitle)
        tvStatus = view.findViewById(R.id.tvOverviewStatus)
        vStatusDot = view.findViewById(R.id.vOverviewStatusDot)
        tvDateTime = view.findViewById(R.id.tvOverviewDateTime)
        tvType = view.findViewById(R.id.tvOverviewType)

        tvClientName = view.findViewById(R.id.tvOverviewClientName)
        tvPhone = view.findViewById(R.id.tvOverviewPhone)
        tvProject = view.findViewById(R.id.tvOverviewProject)
        tvBdo = view.findViewById(R.id.tvOverviewBdo)
        tvPickupAddress = view.findViewById(R.id.tvOverviewPickupAddress)
        tvAttendees = view.findViewById(R.id.tvOverviewAttendees)
        tvIncharge = view.findViewById(R.id.tvOverviewIncharge)

        tvVisitorName = view.findViewById(R.id.tvOverviewVisitorName)
        tvVisitorDetails = view.findViewById(R.id.tvOverviewVisitorDetails)
        tvNotes = view.findViewById(R.id.tvOverviewNotes)

        // Stepper lines
        stepLine1 = view.findViewById(R.id.stepLine1)
        stepLine2 = view.findViewById(R.id.stepLine2)
        stepLine3 = view.findViewById(R.id.stepLine3)
        stepLine4 = view.findViewById(R.id.stepLine4)
        stepLine5 = view.findViewById(R.id.stepLine5)

        // Stepper circles
        circleScheduled = view.findViewById(R.id.frameStepScheduled)
        circleAssigned = view.findViewById(R.id.frameStepAssigned)
        circlePickedUp = view.findViewById(R.id.frameStepPickedUp)
        circleOnSite = view.findViewById(R.id.frameStepOnSite)
        circleDropped = view.findViewById(R.id.frameStepDropped)
        circleDone = view.findViewById(R.id.frameStepDone)

        // Stepper icons
        ivScheduled = view.findViewById(R.id.ivStepScheduled)
        ivAssigned = view.findViewById(R.id.ivStepAssigned)
        ivPickedUp = view.findViewById(R.id.ivStepPickedUp)
        ivOnSite = view.findViewById(R.id.ivStepOnSite)
        ivDropped = view.findViewById(R.id.ivStepDropped)
        ivDone = view.findViewById(R.id.ivStepDone)

        // Stepper labels
        labelScheduled = view.findViewById(R.id.tvStepScheduled)
        labelAssigned = view.findViewById(R.id.tvStepAssigned)
        labelPickedUp = view.findViewById(R.id.tvStepPickedUp)
        labelOnSite = view.findViewById(R.id.tvStepOnSite)
        labelDropped = view.findViewById(R.id.tvStepDropped)
        labelDone = view.findViewById(R.id.tvStepDone)

        // Dropdown views
        tvSelectedVehicleType = view.findViewById(R.id.tvSelectedVehicleType)

        // Stepper layouts
        layoutCabStepper = view.findViewById(R.id.layoutCabStepper)
        layoutOwnStepper = view.findViewById(R.id.layoutOwnStepper)

        // Own Stepper lines
        stepLineOwn1 = view.findViewById(R.id.stepLineOwn1)
        stepLineOwn2 = view.findViewById(R.id.stepLineOwn2)
        stepLineOwn3 = view.findViewById(R.id.stepLineOwn3)

        // Own Stepper circles
        circleOwnScheduled = view.findViewById(R.id.frameStepOwnScheduled)
        circleOwnClientDeparture = view.findViewById(R.id.frameStepOwnClientDeparture)
        circleOwnOnSite = view.findViewById(R.id.frameStepOwnOnSite)
        circleOwnDone = view.findViewById(R.id.frameStepOwnDone)

        // Own Stepper icons
        ivOwnScheduled = view.findViewById(R.id.ivStepOwnScheduled)
        ivOwnClientDeparture = view.findViewById(R.id.ivStepOwnClientDeparture)
        ivOwnOnSite = view.findViewById(R.id.ivStepOwnOnSite)
        ivOwnDone = view.findViewById(R.id.ivStepOwnDone)

        // Own Stepper labels
        labelOwnScheduled = view.findViewById(R.id.tvStepOwnScheduled)
        labelOwnClientDeparture = view.findViewById(R.id.tvStepOwnClientDeparture)
        labelOwnOnSite = view.findViewById(R.id.tvStepOwnOnSite)
        labelOwnDone = view.findViewById(R.id.tvStepOwnDone)

        // Outcome buttons
        btnBooking = view.findViewById(R.id.btnOutcomeBooking)
        btnNotInterested = view.findViewById(R.id.btnOutcomeNotInterested)
        btnPostponed = view.findViewById(R.id.btnOutcomePostponed)

        // Vehicle-type dropdown removed — the badge above is now
        // read-only. The travel mode is set by the WEB (Own Vehicle
        // or Cab Vehicle) and mirrored down via loadEnrichedDetail
        // → isOwnVehicleSelected, which then toggles which stepper
        // layout is visible. No click handler here on purpose.

        // Bind initial arguments
        bindInitialArgs()

        // Stepper is READ-ONLY on mobile — the web is the only surface
        // that advances a Site Visit through scheduled → client_started
        // / picked_up → on_site → dropped. Mobile mirrors the current
        // step via updateStepper(...) inside loadEnrichedDetail, and
        // taps on the step circles do nothing (apart from a friendly
        // hint toast). The previous wireStepperTaps() + advanceTo(...)
        // path that fired markSiteVisitPickedUp / markSiteVisitArrivedSite
        // / markSiteVisitDropped from mobile has been gated off — the
        // operator's only writable surface is the Outcome buttons,
        // which unlock once the web pushes status >= on_site.
        wireStepperReadOnlyHint()
        wireBookingResult()

        // Load enriched details
        val id = visitId
        if (!id.isNullOrBlank()) {
            loadEnrichedDetail(id)
        }
    }

    /**
     * Replaces the old wireStepperTaps() that fired markSiteVisitPickedUp
     * / markSiteVisitArrivedSite / markSiteVisitDropped from mobile.
     * Now each step circle just shows a hint toast — the WEB is the
     * single source of truth for progress, and the SV row's status
     * field rebinds here on every loadEnrichedDetail pass. Tapping a
     * step never triggers a state transition.
     *
     * Operators interact with the SV through the Outcome buttons
     * below (Booking / Not Interested / Postponed), which only become
     * active once the web has advanced the SV to on_site.
     */
    private fun wireStepperReadOnlyHint() {
        val hint: (View) -> Unit = {
            Toast.makeText(
                requireContext(),
                "Trip progress is managed from the web admin.",
                Toast.LENGTH_SHORT,
            ).show()
        }
        // Cab vehicle circles
        circlePickedUp?.setOnClickListener(hint)
        circleOnSite?.setOnClickListener(hint)
        circleDropped?.setOnClickListener(hint)
        // Own vehicle circles
        circleOwnClientDeparture?.setOnClickListener(hint)
        circleOwnOnSite?.setOnClickListener(hint)
    }

    private fun bindInitialArgs() {
        val args = arguments ?: return
        visitId = args.getString(ARG_VISIT_ID)
        cpVisitId = args.getString(ARG_CLIENT_PLACE_VISIT_ID)

        val placeName = args.getString(ARG_PLACE_NAME)
        val placeAddress = args.getString(ARG_PLACE_ADDRESS)
        val leadName = args.getString(ARG_LEAD_NAME)
        val leadPhone = args.getString(ARG_LEAD_PHONE)
        val schedDate = args.getString(ARG_SCHEDULED_DATE)
        val schedTime = args.getString(ARG_SCHEDULED_START_TIME)
        val rawStatus = args.getString(ARG_STATUS).orEmpty()
        isOutcomeLocked = isTerminalOutcomeStatus(rawStatus)

        // First-frame bind from list-row arguments. Real values land
        // shortly after via bindEnriched() once the detail fetch
        // returns; until then prefer em-dashes over fake values so the
        // sheet never displays seeded mock data even for a flash.
        tvTitle?.text = placeName?.takeIf { it.isNotBlank() } ?: "Site Visit"
        val initialName = leadName?.takeIf { it.isNotBlank() }
        tvClientName?.text = initialName?.let { formatPersonName(it) } ?: "—"
        tvVisitorName?.text = initialName?.let { formatPersonName(it) } ?: "—"
        tvPhone?.text = leadPhone?.takeIf { it.isNotBlank() } ?: "—"
        tvProject?.text = placeName?.takeIf { it.isNotBlank() } ?: "—"
        tvPickupAddress?.text = placeAddress?.takeIf { it.isNotBlank() } ?: "—"
        // Clear the BDO / incharge / attendees / visitor-details slots
        // up front so any stale text from a layout default disappears
        // before the network call lands.
        tvBdo?.text = "—"
        tvIncharge?.text = "—"
        tvAttendees?.text = "—"
        tvVisitorDetails?.text = "—"
        tvNotes?.text = "Loading…"

        // Schedule label
        val dateLabel = schedDate?.let { formatDateOnly(it) } ?: schedDate ?: ""
        val timeLabel = schedTime ?: ""
        tvDateTime?.text = if (dateLabel.isNotEmpty() && timeLabel.isNotEmpty()) "$dateLabel, $timeLabel" else dateLabel.ifEmpty { timeLabel.ifEmpty { "—" } }

        val visitCategory = args.getString(ARG_VISIT_CATEGORY)
        tvType?.text = when (visitCategory) {
            "sv_cum_cp" -> "SV confirmation CP"
            "direct_cp" -> "Direct CP"
            "site_visit" -> "Site Visit"
            else -> "Site Visit"
        }

        // Default to Cab Vehicle initially
        isOwnVehicleSelected = false
        toggleStepperVisibility()

        // Stepper state mapping
        val stepIndex = mapStatusToStepIndex(rawStatus)
        updateStepper(stepIndex)

        // Bind status text and dot color
        bindStatusHeader(rawStatus)
    }

    private fun toggleStepperVisibility() {
        if (isOwnVehicleSelected) {
            layoutCabStepper?.visibility = View.GONE
            layoutOwnStepper?.visibility = View.VISIBLE
            tvSelectedVehicleType?.text = "Own Vehicle"
        } else {
            layoutCabStepper?.visibility = View.VISIBLE
            layoutOwnStepper?.visibility = View.GONE
            tvSelectedVehicleType?.text = "Cab Vehicle"
        }
    }

    private fun bindStatusHeader(status: String) {
        val lower = status.lowercase(Locale.US)
        val displayName = when {
            lower in setOf("completed", "complete", "done") -> "COMPLETED"
            lower in setOf("cancelled", "canceled", "no_show") -> "CANCELLED"
            lower in setOf("picked_up", "client_started") -> "PICKED UP"
            lower in setOf("on_site", "arrived") -> "ON SITE"
            lower == "dropped" -> "DROPPED"
            lower == "assigned" -> "ASSIGNED"
            else -> "SCHEDULED"
        }
        tvStatus?.text = displayName

        val color = when (displayName) {
            "COMPLETED" -> Color.parseColor("#027A48")
            "CANCELLED" -> Color.parseColor("#B42318")
            "PICKED UP", "ON SITE", "DROPPED" -> Color.parseColor("#B54708")
            else -> Color.parseColor("#004EEB")
        }
        tvStatus?.setTextColor(color)
        vStatusDot?.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
    }

    private fun mapStatusToStepIndex(status: String): Int {
        val lower = status.lowercase(Locale.US)
        return when (lower) {
            "completed", "complete", "done", "closed" -> 5
            "dropped" -> 4
            "on_site", "on site", "arrived" -> 3
            "picked_up", "picked up", "client_started", "client started" -> 2
            "assigned" -> 1
            else -> 0
        }
    }

    private fun mapStatusToOwnStepIndex(status: String): Int {
        val lower = status.lowercase(Locale.US)
        return when (lower) {
            "completed", "complete", "done", "closed" -> 3
            "dropped" -> 2
            "on_site", "on site", "arrived" -> 2
            "picked_up", "picked up", "client_started", "client started" -> 1
            "assigned" -> 1
            else -> 0
        }
    }

    private fun computeOwnStepIndex(
        status: String,
        snapshot: com.manjugroups.m_connect.network.ProposedSiteVisit?,
    ): Int {
        val baseFromStatus = mapStatusToOwnStepIndex(status)

        val driverBoost = when {
            snapshot?.travelDeskEndedAt != null -> 2
            snapshot?.travelDeskOnSiteAt != null -> 2
            snapshot?.travelDeskStartedAt != null -> 1
            else -> -1
        }

        return maxOf(baseFromStatus, driverBoost)
    }

    /**
     * Web-parity stepper computation. Mirrors `cabProgressState` +
     * `cabDriverProgressBoost` from `app/marketing/site-visits/[id]/page.tsx`
     * so the mobile bottom sheet shows the SAME progress as the web SV
     * detail.
     *
     * Mapping (mobile step indices on the right):
     *   0  Scheduled            — status="scheduled" AND no vehicle yet
     *   1  Assigned             — status="scheduled" AND vehicleId/agency set
     *   2  Picked Up            — status="picked_up" / "client_started"
     *                             OR driver tapped "Start trip" (travelDeskStartedAt)
     *   3  On Site              — status="on_site"
     *                             OR driver tapped "Arrived" (travelDeskOnSiteAt)
     *   4  Dropped              — status="dropped"
     *                             OR driver tapped "Ended" (travelDeskEndedAt)
     *   5  Done                 — status="completed" (or any "done" alias)
     *
     * Falling back to bare `mapStatusToStepIndex` when no snapshot is
     * available keeps the legacy initial-args path working before the
     * detail fetch resolves.
     */
    private fun computeWebParityStepIndex(
        status: String,
        snapshot: com.manjugroups.m_connect.network.ProposedSiteVisit?,
    ): Int {
        val baseFromStatus = mapStatusToStepIndex(status)

        // Driver-side boosts: explicit timestamps trump status when
        // they're ahead. Identical precedence to the web's
        // mergeVisitProgress(base, boost) helper.
        val driverBoost = when {
            snapshot?.travelDeskEndedAt != null -> 4
            snapshot?.travelDeskOnSiteAt != null -> 3
            snapshot?.travelDeskStartedAt != null -> 2
            else -> -1
        }

        // Vehicle assignment auto-advances Scheduled → Assigned without
        // any status change on the SV row.
        val hasVehicleAssigned =
            snapshot != null && (
                !snapshot.vehicleId.isNullOrBlank() ||
                !snapshot.travelAgencyId.isNullOrBlank()
            )
        val vehicleBoost = if (baseFromStatus == 0 && hasVehicleAssigned) 1 else -1

        return maxOf(baseFromStatus, driverBoost, vehicleBoost)
    }

    private fun updateStepper(activeIndex: Int) {
        currentStepIndex = activeIndex
        val context = requireContext()
        val blueColor = Color.parseColor("#004EEB")
        val grayColor = Color.parseColor("#98A2B3")

        if (isOwnVehicleSelected) {
            // Translate status-parity activeIndex (0..5) to Own activeIndex (0..3)
            val ownActiveIndex = when {
                activeIndex <= 1 -> 0
                activeIndex == 2 -> 1
                activeIndex in 3..4 -> 2
                else -> 3
            }

            // Helper to update each step circle and label
            fun setOwnStep(
                circle: FrameLayout?,
                icon: ImageView?,
                label: TextView?,
                stepIdx: Int,
                activeIconRes: Int
            ) {
                val state = when {
                    stepIdx < ownActiveIndex -> "done"
                    stepIdx == ownActiveIndex -> "active"
                    else -> "inactive"
                }
                when (state) {
                    "done" -> {
                        circle?.background = ContextCompat.getDrawable(context, R.drawable.bg_trip_progress_figma_active)
                        icon?.setImageResource(R.drawable.ic_check_white)
                        label?.setTextColor(blueColor)
                        label?.typeface = ResourcesCompat.getFont(context, R.font.inter_semibold)
                    }
                    "active" -> {
                        circle?.background = ContextCompat.getDrawable(context, R.drawable.bg_trip_progress_figma_active_current)
                        icon?.setImageResource(activeIconRes)
                        icon?.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                        label?.setTextColor(blueColor)
                        label?.typeface = ResourcesCompat.getFont(context, R.font.inter_bold)
                    }
                    "inactive" -> {
                        circle?.background = ContextCompat.getDrawable(context, R.drawable.bg_trip_progress_figma_inactive)
                        icon?.setImageResource(activeIconRes)
                        icon?.imageTintList = android.content.res.ColorStateList.valueOf(grayColor)
                        label?.setTextColor(grayColor)
                        label?.typeface = ResourcesCompat.getFont(context, R.font.inter_medium)
                    }
                }
            }

            // Apply circle states for Own Stepper
            setOwnStep(circleOwnScheduled, ivOwnScheduled, labelOwnScheduled, 0, R.drawable.ic_check_white)
            setOwnStep(circleOwnClientDeparture, ivOwnClientDeparture, labelOwnClientDeparture, 1, R.drawable.ic_car_outline)
            setOwnStep(circleOwnOnSite, ivOwnOnSite, labelOwnOnSite, 2, R.drawable.ic_task_building)
            setOwnStep(circleOwnDone, ivOwnDone, labelOwnDone, 3, R.drawable.ic_check_circle)

            // Connector lines progress state for Own Stepper
            stepLineOwn1?.setBackgroundColor(if (ownActiveIndex >= 1) blueColor else Color.parseColor("#EAECF0"))
            stepLineOwn2?.setBackgroundColor(if (ownActiveIndex >= 2) blueColor else Color.parseColor("#EAECF0"))
            stepLineOwn3?.setBackgroundColor(if (ownActiveIndex >= 3) blueColor else Color.parseColor("#EAECF0"))

            // Outcome buttons activation gate
            val onSiteReached = ownActiveIndex >= 2
            if (isOutcomeLocked) {
                lockOutcomeButtons("This site visit outcome is already completed.")
            } else if (onSiteReached) {
                btnBooking?.isEnabled = true
                btnBooking?.alpha = 1.0f
                btnNotInterested?.isEnabled = true
                btnNotInterested?.alpha = 1.0f
                btnPostponed?.isEnabled = true
                btnPostponed?.alpha = 1.0f

                // Wire each outcome to the shared CP completion sheet,
                // locked to the chosen form so field staff do not see
                // unrelated Booking / Postpone / Not Interested tabs.
                btnBooking?.setOnClickListener { openSiteVisitOutcomeSheet("converted_to_booking") }
                btnNotInterested?.setOnClickListener { openSiteVisitOutcomeSheet("not_interested") }
                btnPostponed?.setOnClickListener { openSiteVisitOutcomeSheet("postponed") }
            } else {
                btnBooking?.isEnabled = false
                btnBooking?.alpha = 0.4f
                btnNotInterested?.isEnabled = false
                btnNotInterested?.alpha = 0.4f
                btnPostponed?.isEnabled = false
                btnPostponed?.alpha = 0.4f

                val lockedToast: (View) -> Unit = {
                    Toast.makeText(context, "Outcome buttons will activate once you reach on site.", Toast.LENGTH_SHORT).show()
                }
                btnBooking?.setOnClickListener(lockedToast)
                btnNotInterested?.setOnClickListener(lockedToast)
                btnPostponed?.setOnClickListener(lockedToast)
            }
        } else {
            // Cab Vehicle: 6 steps (original implementation)
            // Helper to update each step circle and label
            fun setStep(
                circle: FrameLayout?,
                icon: ImageView?,
                label: TextView?,
                stepIdx: Int,
                activeIconRes: Int
            ) {
                val state = when {
                    stepIdx < activeIndex -> "done"
                    stepIdx == activeIndex -> "active"
                    else -> "inactive"
                }
                when (state) {
                    "done" -> {
                        circle?.background = ContextCompat.getDrawable(context, R.drawable.bg_trip_progress_figma_active)
                        icon?.setImageResource(R.drawable.ic_check_white)
                        label?.setTextColor(blueColor)
                        label?.typeface = ResourcesCompat.getFont(context, R.font.inter_semibold)
                    }
                    "active" -> {
                        circle?.background = ContextCompat.getDrawable(context, R.drawable.bg_trip_progress_figma_active_current)
                        icon?.setImageResource(activeIconRes)
                        icon?.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                        label?.setTextColor(blueColor)
                        label?.typeface = ResourcesCompat.getFont(context, R.font.inter_bold)
                    }
                    "inactive" -> {
                        circle?.background = ContextCompat.getDrawable(context, R.drawable.bg_trip_progress_figma_inactive)
                        icon?.setImageResource(activeIconRes)
                        icon?.imageTintList = android.content.res.ColorStateList.valueOf(grayColor)
                        label?.setTextColor(grayColor)
                        label?.typeface = ResourcesCompat.getFont(context, R.font.inter_medium)
                    }
                }
            }

            // Apply circle states
            setStep(circleScheduled, ivScheduled, labelScheduled, 0, R.drawable.ic_check_white)
            setStep(circleAssigned, ivAssigned, labelAssigned, 1, R.drawable.ic_car_outline)
            setStep(circlePickedUp, ivPickedUp, labelPickedUp, 2, R.drawable.ic_nav_home)
            setStep(circleOnSite, ivOnSite, labelOnSite, 3, R.drawable.ic_task_building)
            setStep(circleDropped, ivDropped, labelDropped, 4, R.drawable.ic_map_pin)
            setStep(circleDone, ivDone, labelDone, 5, R.drawable.ic_check_circle)

            // Connector lines progress state
            stepLine1?.setBackgroundColor(if (activeIndex >= 1) blueColor else Color.parseColor("#EAECF0"))
            stepLine2?.setBackgroundColor(if (activeIndex >= 2) blueColor else Color.parseColor("#EAECF0"))
            stepLine3?.setBackgroundColor(if (activeIndex >= 3) blueColor else Color.parseColor("#EAECF0"))
            stepLine4?.setBackgroundColor(if (activeIndex >= 4) blueColor else Color.parseColor("#EAECF0"))
            stepLine5?.setBackgroundColor(if (activeIndex >= 5) blueColor else Color.parseColor("#EAECF0"))

            // Outcome buttons activation gate
            val onSiteReached = activeIndex >= 3
            if (isOutcomeLocked) {
                lockOutcomeButtons("This site visit outcome is already completed.")
            } else if (onSiteReached) {
                btnBooking?.isEnabled = true
                btnBooking?.alpha = 1.0f
                btnNotInterested?.isEnabled = true
                btnNotInterested?.alpha = 1.0f
                btnPostponed?.isEnabled = true
                btnPostponed?.alpha = 1.0f

                // Wire each outcome to the shared CP completion sheet,
                // locked to the chosen form so field staff do not see
                // unrelated Booking / Postpone / Not Interested tabs.
                btnBooking?.setOnClickListener { openSiteVisitOutcomeSheet("converted_to_booking") }
                btnNotInterested?.setOnClickListener { openSiteVisitOutcomeSheet("not_interested") }
                btnPostponed?.setOnClickListener { openSiteVisitOutcomeSheet("postponed") }
            } else {
                btnBooking?.isEnabled = false
                btnBooking?.alpha = 0.4f
                btnNotInterested?.isEnabled = false
                btnNotInterested?.alpha = 0.4f
                btnPostponed?.isEnabled = false
                btnPostponed?.alpha = 0.4f

                val lockedToast: (View) -> Unit = {
                    Toast.makeText(context, "Outcome buttons will activate once you reach on site.", Toast.LENGTH_SHORT).show()
                }
                btnBooking?.setOnClickListener(lockedToast)
                btnNotInterested?.setOnClickListener(lockedToast)
                btnPostponed?.setOnClickListener(lockedToast)
            }
        }
    }

    private fun lockOutcomeButtons(message: String) {
        btnBooking?.isEnabled = false
        btnBooking?.alpha = 0.4f
        btnNotInterested?.isEnabled = false
        btnNotInterested?.alpha = 0.4f
        btnPostponed?.isEnabled = false
        btnPostponed?.alpha = 0.4f

        val lockedToast: (View) -> Unit = {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
        btnBooking?.setOnClickListener(lockedToast)
        btnNotInterested?.setOnClickListener(lockedToast)
        btnPostponed?.setOnClickListener(lockedToast)
    }

    private fun wireBookingResult() {
        parentFragmentManager.setFragmentResultListener(
            CompleteCpVisitBottomSheet.RESULT_KEY,
            viewLifecycleOwner,
        ) { _, bundle ->
            val outcome = bundle.getString(CompleteCpVisitBottomSheet.KEY_OUTCOME)
            val label = when (outcome) {
                "converted_to_booking" -> "Converted as Booking"
                "not_interested" -> "Client Not Interested"
                "postponed" -> "Its Been Postponed"
                else -> return@setFragmentResultListener
            }

            updateStepper(5)
            bindStatusHeader("completed")
            visitId?.takeIf { it.isNotBlank() }?.let(::loadEnrichedDetail)
            Toast.makeText(
                requireContext(),
                "$label ✓ Outcome saved.",
                Toast.LENGTH_SHORT,
            ).show()
            dismissAllowingStateLoss()
        }
    }

    private fun openSiteVisitOutcomeSheet(outcome: String) {
        if (isOutcomeLocked) {
            Toast.makeText(
                requireContext(),
                "This site visit outcome is already completed.",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val targetVisitId = visitId
        if (targetVisitId.isNullOrBlank()) {
            Toast.makeText(requireContext(), "Site visit id is missing", Toast.LENGTH_SHORT).show()
            return
        }
        CompleteCpVisitBottomSheet
            .forSiteVisit(targetVisitId, outcome)
            .show(parentFragmentManager, "site_visit_${outcome}_outcome")
    }

    private fun loadEnrichedDetail(id: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Fetch enriched detail from Convex backend
                val resp = geoApi.getCpVisitDetail(session.bearerToken, id)
                if (!resp.success || resp.visit == null) return@launch
                bindEnriched(resp.visit)
            } catch (_: Exception) {
                // Keep pre-filled layout intact if network blip occurs
            }
        }
    }

    private fun bindEnriched(visit: CpVisitDetail) {
        // --- Customer name + phone -------------------------------------
        // Precedence: CP-level client → lead → clientPlace label. Show
        // an em-dash only when the backend has nothing — the previous
        // "Client" / "AKASH.B" / "22 yrs Veg" fallbacks made empty
        // records look populated and indistinguishable from real ones.
        val rawDisplayName = visit.client?.clientName?.takeIf { it.isNotBlank() }
            ?: visit.lead?.contactName?.takeIf { it.isNotBlank() }
            ?: visit.clientPlace?.name?.takeIf { it.isNotBlank() }
        val displayName = rawDisplayName?.let { formatPersonName(it) } ?: "—"
        tvClientName?.text = displayName
        tvVisitorName?.text = displayName

        val phone = visit.client?.mobileNumber?.takeIf { it.isNotBlank() }
            ?: visit.lead?.mobileNumber?.takeIf { it.isNotBlank() }
        tvPhone?.text = phone ?: "—"

        // --- Project / title ------------------------------------------
        // Resolution order:
        //   1. visit.project.name (backend pre-resolved)
        //   2. async lookup against proposedSiteVisit.projectId via
        //      api.getMarketingProjects — covers CP-linked SVs where
        //      getForMobileId returns project=null (the CP-level
        //      projectId is unset; the SV's project lives on
        //      proposedSiteVisit). Same self-sufficient fallback the
        //      Site Incharge block below uses for its analogous
        //      "backend didn't pre-resolve, look it up here" case.
        //   3. "—" if neither path yields a project.
        //
        // The previous code fell back to `visit.clientPlace?.name`
        // which leaked the client's name into the Project / Plot row
        // for residential CPs (clientPlace.name defaults to the
        // client's name when the place was created from the
        // telecaller's manual-profile address). Removed entirely —
        // nothing in this field can resemble a client name now.
        val projectName = visit.project?.name?.takeIf { it.isNotBlank() }
        if (!projectName.isNullOrBlank()) {
            tvProject?.text = projectName
            tvTitle?.text = projectName
        } else {
            // Show em-dash up front; the async resolve below replaces
            // it once the project name lands. Prevents a one-frame
            // flash of a wrong/stale value.
            tvProject?.text = "—"
            val proposedProjectId = visit.proposedSiteVisit?.projectId
            if (!proposedProjectId.isNullOrBlank()) {
                viewLifecycleOwner.lifecycleScope.launch {
                    runCatching {
                        val projectsResp = api.getMarketingProjects(session.bearerToken)
                        if (projectsResp.success) {
                            val matching = projectsResp.projects.firstOrNull {
                                it.id == proposedProjectId
                            }
                            val name = matching?.name?.takeIf { it.isNotBlank() }
                            if (!name.isNullOrBlank()) {
                                tvProject?.text = name
                                tvTitle?.text = name
                            }
                        }
                    }
                }
            }
        }

        // --- Staff assigned (BDO) -------------------------------------
        // Convex returns staff.name; older code stuffed it into staffName.
        // Read both so we land the right field regardless of envelope shape.
        val staffName = (visit.assignedStaff?.name ?: visit.assignedStaff?.staffName)
            ?.takeIf { it.isNotBlank() }
        tvBdo?.text = staffName?.let { formatPersonName(it) } ?: "—"

        // --- Pickup / Site Address ------------------------------------
        val addressStr = listOfNotNull(
            visit.clientPlace?.address?.takeIf { it.isNotBlank() },
            visit.clientPlace?.landmark?.takeIf { it.isNotBlank() },
            visit.clientPlace?.city?.takeIf { it.isNotBlank() },
            visit.clientPlace?.state?.takeIf { it.isNotBlank() },
            visit.clientPlace?.pincode?.takeIf { it.isNotBlank() },
        ).joinToString(", ").ifBlank { visit.clientPlace?.formattedAddress.orEmpty() }
        tvPickupAddress?.text = addressStr.ifBlank { "—" }

        // --- Expected attendees ---------------------------------------
        // Don't invent "1 Expected" when the field is null — that
        // masks unspecified parties as a confirmed solo visit.
        val count = visit.expectedAttendeeCount
        tvAttendees?.text = if (count != null && count > 0) "$count Expected" else "—"

        // --- Site Incharge --------------------------------------------
        // 1. Pure-SV envelopes from getForMobileId pre-resolve the
        //    incharge into visit.inchargeStaff — use it directly.
        // 2. CP-converted SVs need a staff-list lookup against
        //    proposedSiteVisit.inchargeStaffId.
        // 3. No incharge set → "—" (no more fake "EVANGELINE PRINCY.S").
        val preresolvedIncharge = (visit.inchargeStaff?.name ?: visit.inchargeStaff?.staffName)
            ?.takeIf { it.isNotBlank() }
        val proposed = visit.proposedSiteVisit
        when {
            !preresolvedIncharge.isNullOrBlank() -> {
                tvIncharge?.text = formatPersonName(preresolvedIncharge)
            }
            proposed?.inchargeStaffId != null -> {
                tvIncharge?.text = "—"
                viewLifecycleOwner.lifecycleScope.launch {
                    runCatching {
                        val staffResp = api.getStaff(session.bearerToken, status = "active")
                        val matching = staffResp.staff.firstOrNull { it.id == proposed.inchargeStaffId }
                        val name = matching?.name?.takeIf { it.isNotBlank() }
                        tvIncharge?.text = name?.let { formatPersonName(it) } ?: "—"
                    }
                }
            }
            else -> tvIncharge?.text = "—"
        }

        // --- Visitors card --------------------------------------------
        // Only render demographic detail when the backend actually has
        // the attendee record. The previous "Self • 22 yrs • Veg"
        // fallback synthesized three values out of nothing.
        val firstAttendee = visit.attendees?.firstOrNull()
        val parts = mutableListOf<String>()
        firstAttendee?.relation?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        firstAttendee?.age?.takeIf { it.isNotBlank() }?.let { parts.add("$it yrs") }
        firstAttendee?.isVeg?.let { parts.add(if (it) "Veg" else "Non-Veg") }
        tvVisitorDetails?.text = if (parts.isEmpty()) "—" else parts.joinToString(" • ")

        // --- Notes card -----------------------------------------------
        tvNotes?.text = visit.notes?.takeIf { it.isNotBlank() }
            ?: "No notes recorded yet."

        val effStatus = visit.status ?: ""
        isOutcomeLocked = isTerminalOutcome(visit)
        
        // Detect vehicle type and toggle layout visibility
        isOwnVehicleSelected = proposed?.travelMode == "own_vehicle" || visit.vehiclePreference == "own_vehicle"
        toggleStepperVisibility()

        if (effStatus.isNotEmpty()) {
            val stepIndex = computeWebParityStepIndex(
                status = effStatus,
                snapshot = proposed,
            )
            updateStepper(stepIndex)
            bindStatusHeader(effStatus)
        }
    }

    private fun saveOutcome(outcomeValue: String, label: String) {
        val targetVisitId = visitId ?: return
        if (isOutcomeLocked) {
            Toast.makeText(
                requireContext(),
                "This site visit outcome is already completed.",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        btnBooking?.isEnabled = false
        btnNotInterested?.isEnabled = false
        btnPostponed?.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Two paths:
                //
                //   1. Pure SV (no cpVisitId behind it) → route through
                //      the siteVisits.setOutcome mutation. The visit's
                //      status MUST be on_site (or dropped) for the
                //      backend assertTransition to accept the outcome;
                //      the stepper UI already gates the outcome buttons
                //      behind currentStepIndex >= 3 so we should be
                //      past that point by the time we get here.
                //
                //   2. SV-via-CP (cpVisitId is set) → route through the
                //      CP path so the CP visit row + its linked SV
                //      both reach the correct terminal state. Mirrors
                //      the legacy CP outcome flow.
                val cpId = cpVisitId
                if (cpId.isNullOrBlank()) {
                    // Pure-SV outcome
                    val postponeReasons = if (outcomeValue == "postponed") listOf("other") else null
                    val notInterestedReasons = if (outcomeValue == "not_interested") listOf("other") else null
                    val resp = geoApi.setSiteVisitOutcome(
                        session.bearerToken,
                        SetSiteVisitOutcomeRequest(
                            id = targetVisitId,
                            outcome = outcomeValue,
                            postponeReasons = postponeReasons,
                            notInterestedReasons = notInterestedReasons,
                            notes = "Outcome: $label recorded via mobile details",
                        ),
                    )
                    if (!resp.success) {
                        throw Exception(resp.error ?: "Failed to save outcome")
                    }
                } else {
                    // SV-via-CP outcome — legacy CP path stays intact.
                    val metResp = geoApi.markClientMet(
                        session.bearerToken,
                        MarkClientMetRequest(id = cpId, clientMet = true),
                    )
                    if (!metResp.success) {
                        throw Exception(metResp.error ?: "Failed to mark client status")
                    }
                    val outcomeResp = geoApi.setCpVisitOutcome(
                        session.bearerToken,
                        SetOutcomeRequest(
                            id = cpId,
                            outcome = outcomeValue,
                            notes = "Outcome: $label recorded via mobile details",
                        ),
                    )
                    if (!outcomeResp.success) {
                        throw Exception(outcomeResp.error ?: "Failed to set outcome")
                    }
                }

                // Stepper visually completes — setOutcome on the
                // server-side moves status to "completed" (Done step).
                updateStepper(5)
                bindStatusHeader("completed")
                Toast.makeText(
                    requireContext(),
                    "$label ✓ Outcome saved.",
                    Toast.LENGTH_SHORT,
                ).show()
                dismiss()
            } catch (e: Exception) {
                btnBooking?.isEnabled = true
                btnNotInterested?.isEnabled = true
                btnPostponed?.isEnabled = true
                Toast.makeText(
                    requireContext(),
                    e.message ?: "Failed to save outcome",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    // ---------- Helper formatting ----------

    private fun formatPersonName(rawName: String): String =
        rawName.lowercase(Locale.getDefault()).split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.titlecase() } }
            .ifBlank { "Client" }

    private fun isTerminalOutcome(visit: CpVisitDetail): Boolean =
        isTerminalOutcomeStatus(visit.status) ||
            isTerminalOutcomeStatus(visit.outcome) ||
            visit.convertedBookingId != null ||
            visit.completedAt != null ||
            visit.cancelledAt != null

    private fun isTerminalOutcomeStatus(value: String?): Boolean {
        val lower = value?.trim()?.lowercase(Locale.US).orEmpty()
        return lower in setOf(
            "completed",
            "complete",
            "done",
            "closed",
            "cancelled",
            "canceled",
            "no_show",
            "converted_to_booking",
            "not_interested",
            "postponed",
            "other",
        )
    }

    private fun formatDateOnly(scheduledDate: String): String {
        val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val parse = runCatching { ymd.parse(scheduledDate) }.getOrNull() ?: return scheduledDate
        return SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(parse)
    }

    companion object {
        private const val ARG_VISIT_ID = "arg_visit_id"
        private const val ARG_CLIENT_PLACE_VISIT_ID = "arg_client_place_visit_id"
        private const val ARG_PLACE_NAME = "arg_place_name"
        private const val ARG_PLACE_ADDRESS = "arg_place_address"
        private const val ARG_LEAD_NAME = "arg_lead_name"
        private const val ARG_LEAD_PHONE = "arg_lead_phone"
        private const val ARG_SCHEDULED_DATE = "arg_scheduled_date"
        private const val ARG_SCHEDULED_START_TIME = "arg_scheduled_start_time"
        private const val ARG_STATUS = "arg_status"
        private const val ARG_VISIT_CATEGORY = "arg_visit_category"

        fun forVisit(visit: TodayVisit): SiteVisitOverviewFragment {
            return SiteVisitOverviewFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_VISIT_ID, visit.id)
                    putString(ARG_CLIENT_PLACE_VISIT_ID, visit.clientPlaceVisitId)
                    putString(ARG_PLACE_NAME, visit.placeName)
                    putString(ARG_PLACE_ADDRESS, visit.placeAddress)
                    putString(ARG_LEAD_NAME, visit.leadName)
                    putString(ARG_LEAD_PHONE, visit.leadPhone)
                    putString(ARG_SCHEDULED_DATE, visit.scheduledDate)
                    putString(ARG_SCHEDULED_START_TIME, visit.scheduledStartTime)
                    putString(ARG_STATUS, visit.status)
                    putString(ARG_VISIT_CATEGORY, visit.visitCategory)
                }
            }
        }
    }
}
