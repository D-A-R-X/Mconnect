package com.manjugroups.m_connect.ui.marketing

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
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
import com.manjugroups.m_connect.ui.common.showOnce

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
    private var hasFleetArrived = false
    private var hasFleetStart = false
    private var hasFleetOnSite = false
    // Non-null ("POSTPONED"/"CANCELLED") when the SV is in a terminal status —
    // the cab/own stepper then shows the reached prefix + this terminal node.
    private var currentTerminalLabel: String? = null
    private var isConsultingStatus = false
    // Lock for the lifecycle-transition button so a double-tap doesn't
    // fire two markPickedUp calls back-to-back.
    // `transitioning` flag removed — mobile no longer advances the SV
    // through markSiteVisitPickedUp / markSiteVisitArrivedSite /
    // markSiteVisitDropped. The web is the single source of truth for
    // trip progress; mobile re-reads it on every loadEnrichedDetail.
    private var isOwnVehicleSelected: Boolean = false
    private var isOutcomeLocked: Boolean = false
    // Outcome form opens only once the SV has actually reached counselling (the
    // client QR scan → on_counselling) and stays open through the later
    // statuses (picked_from_site / dropped) until the outcome is recorded —
    // mirroring the backend setOutcome contract. Driver-boosted stepper
    // position (e.g. a cab that reached "on site") must NOT open it.
    private var outcomeStatusEligible: Boolean = false
    // Fleet "completed offline" — the admin marked this SV as done without a
    // live trip. The site incharge must record the outcome. Bypass the normal
    // stepper gate so the buttons are immediately usable.
    private var isFleetOutcomePending: Boolean = false

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

    // Call Client / Call Driver buttons above the pickup address + their numbers.
    private var btnCallClient: View? = null
    private var btnCallDriver: View? = null
    private var tvCallClient: TextView? = null
    private var tvCallDriver: TextView? = null
    private var clientPhone: String? = null
    private var driverPhone: String? = null
    private var clientDisplayName: String? = null
    private var driverDisplayName: String? = null

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
    private var stepLine6: View? = null
    private var stepLine7: View? = null
    private var stepLine8: View? = null

    private var circleScheduled: FrameLayout? = null
    private var circleAssigned: FrameLayout? = null
    private var circleReachedCp: FrameLayout? = null
    private var circlePickedUp: FrameLayout? = null
    private var circleOnSite: FrameLayout? = null
    private var circleConsulting: FrameLayout? = null
    private var circlePickedFromSite: FrameLayout? = null
    private var circleDropped: FrameLayout? = null
    private var circleDone: FrameLayout? = null

    private var ivScheduled: ImageView? = null
    private var ivAssigned: ImageView? = null
    private var ivReachedCp: ImageView? = null
    private var ivPickedUp: ImageView? = null
    private var ivOnSite: ImageView? = null
    private var ivConsulting: ImageView? = null
    private var ivPickedFromSite: ImageView? = null
    private var ivDropped: ImageView? = null
    private var ivDone: ImageView? = null

    private var labelScheduled: TextView? = null
    private var labelAssigned: TextView? = null
    private var labelReachedCp: TextView? = null
    private var labelPickedUp: TextView? = null
    private var labelOnSite: TextView? = null
    private var labelConsulting: TextView? = null
    private var labelPickedFromSite: TextView? = null
    private var labelDropped: TextView? = null
    private var labelDone: TextView? = null

    // Own Stepper lines
    private var stepLineOwn1: View? = null
    private var stepLineOwn2: View? = null
    private var stepLineOwn3: View? = null
    private var stepLineOwn4: View? = null

    // Own Stepper circles
    private var circleOwnScheduled: FrameLayout? = null
    private var circleOwnClientDeparture: FrameLayout? = null
    private var circleOwnOnSite: FrameLayout? = null
    private var circleOwnConsulting: FrameLayout? = null
    private var circleOwnDone: FrameLayout? = null

    // Own Stepper icons
    private var ivOwnScheduled: ImageView? = null
    private var ivOwnClientDeparture: ImageView? = null
    private var ivOwnOnSite: ImageView? = null
    private var ivOwnConsulting: ImageView? = null
    private var ivOwnDone: ImageView? = null

    // Own Stepper labels
    private var labelOwnScheduled: TextView? = null
    private var labelOwnClientDeparture: TextView? = null
    private var labelOwnOnSite: TextView? = null
    private var labelOwnConsulting: TextView? = null
    private var labelOwnDone: TextView? = null

    // Outcome Buttons
    private var btnBooking: LinearLayout? = null
    private var btnNotInterested: LinearLayout? = null
    private var btnPostponed: LinearLayout? = null
    private var btnOther: LinearLayout? = null
    private var btnPostponeSiteVisit: LinearLayout? = null
    private var btnCancelSiteVisit: LinearLayout? = null

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

        btnCallClient = view.findViewById(R.id.btnOverviewCallClient)
        btnCallDriver = view.findViewById(R.id.btnOverviewCallDriver)
        tvCallClient = view.findViewById(R.id.tvOverviewCallClient)
        tvCallDriver = view.findViewById(R.id.tvOverviewCallDriver)
        btnCallClient?.setOnClickListener { dialPhone(clientPhone) }
        btnCallDriver?.setOnClickListener { dialPhone(driverPhone) }
        refreshCallButtons()

        tvVisitorName = view.findViewById(R.id.tvOverviewVisitorName)
        tvVisitorDetails = view.findViewById(R.id.tvOverviewVisitorDetails)
        tvNotes = view.findViewById(R.id.tvOverviewNotes)

        // Stepper lines
        stepLine1 = view.findViewById(R.id.stepLine1)
        stepLine2 = view.findViewById(R.id.stepLine2)
        stepLine3 = view.findViewById(R.id.stepLine3)
        stepLine4 = view.findViewById(R.id.stepLine4)
        stepLine5 = view.findViewById(R.id.stepLine5)
        stepLine6 = view.findViewById(R.id.stepLine6)
        stepLine7 = view.findViewById(R.id.stepLine7)
        stepLine8 = view.findViewById(R.id.stepLine8)

        // Stepper circles
        circleScheduled = view.findViewById(R.id.frameStepScheduled)
        circleAssigned = view.findViewById(R.id.frameStepAssigned)
        circleReachedCp = view.findViewById(R.id.frameStepReachedCp)
        circlePickedUp = view.findViewById(R.id.frameStepPickedUp)
        circleOnSite = view.findViewById(R.id.frameStepOnSite)
        circleConsulting = view.findViewById(R.id.frameStepConsulting)
        circlePickedFromSite = view.findViewById(R.id.frameStepPickedFromSite)
        circleDropped = view.findViewById(R.id.frameStepDropped)
        circleDone = view.findViewById(R.id.frameStepDone)

        // Stepper icons
        ivScheduled = view.findViewById(R.id.ivStepScheduled)
        ivAssigned = view.findViewById(R.id.ivStepAssigned)
        ivReachedCp = view.findViewById(R.id.ivStepReachedCp)
        ivPickedUp = view.findViewById(R.id.ivStepPickedUp)
        ivOnSite = view.findViewById(R.id.ivStepOnSite)
        ivConsulting = view.findViewById(R.id.ivStepConsulting)
        ivPickedFromSite = view.findViewById(R.id.ivStepPickedFromSite)
        ivDropped = view.findViewById(R.id.ivStepDropped)
        ivDone = view.findViewById(R.id.ivStepDone)

        // Stepper labels
        labelScheduled = view.findViewById(R.id.tvStepScheduled)
        labelAssigned = view.findViewById(R.id.tvStepAssigned)
        labelReachedCp = view.findViewById(R.id.tvStepReachedCp)
        labelPickedUp = view.findViewById(R.id.tvStepPickedUp)
        labelOnSite = view.findViewById(R.id.tvStepOnSite)
        labelConsulting = view.findViewById(R.id.tvStepConsulting)
        labelPickedFromSite = view.findViewById(R.id.tvStepPickedFromSite)
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
        stepLineOwn4 = view.findViewById(R.id.stepLineOwn4)

        // Own Stepper circles
        circleOwnScheduled = view.findViewById(R.id.frameStepOwnScheduled)
        circleOwnClientDeparture = view.findViewById(R.id.frameStepOwnClientDeparture)
        circleOwnOnSite = view.findViewById(R.id.frameStepOwnOnSite)
        circleOwnConsulting = view.findViewById(R.id.frameStepOwnConsulting)
        circleOwnDone = view.findViewById(R.id.frameStepOwnDone)

        // Own Stepper icons
        ivOwnScheduled = view.findViewById(R.id.ivStepOwnScheduled)
        ivOwnClientDeparture = view.findViewById(R.id.ivStepOwnClientDeparture)
        ivOwnOnSite = view.findViewById(R.id.ivStepOwnOnSite)
        ivOwnConsulting = view.findViewById(R.id.ivStepOwnConsulting)
        ivOwnDone = view.findViewById(R.id.ivStepOwnDone)

        // Own Stepper labels
        labelOwnScheduled = view.findViewById(R.id.tvStepOwnScheduled)
        labelOwnClientDeparture = view.findViewById(R.id.tvStepOwnClientDeparture)
        labelOwnOnSite = view.findViewById(R.id.tvStepOwnOnSite)
        labelOwnConsulting = view.findViewById(R.id.tvStepOwnConsulting)
        labelOwnDone = view.findViewById(R.id.tvStepOwnDone)

        // Outcome buttons
        btnBooking = view.findViewById(R.id.btnOutcomeBooking)
        btnNotInterested = view.findViewById(R.id.btnOutcomeNotInterested)
        btnPostponed = view.findViewById(R.id.btnOutcomePostponed)
        btnOther = view.findViewById(R.id.btnOutcomeOther)
        btnPostponeSiteVisit = view.findViewById(R.id.btnPostponeSiteVisit)
        btnPostponeSiteVisit?.visibility = View.GONE
        btnPostponeSiteVisit?.setOnClickListener {
            if (isOutcomeLocked) {
                Toast.makeText(
                    requireContext(),
                    "A completed site visit cannot be postponed.",
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                visitId?.takeIf { it.isNotBlank() }?.let { id ->
                    PostponeSiteVisitBottomSheet
                        .newInstance(id)
                        .showOnce(parentFragmentManager, "PostponeSiteVisitBottomSheet")
                }
            }
        }
        btnCancelSiteVisit = view.findViewById(R.id.btnCancelSiteVisit)
        btnCancelSiteVisit?.visibility = View.GONE
        btnCancelSiteVisit?.setOnClickListener {
            visitId?.takeIf { it.isNotBlank() }?.let { id ->
                CancelSiteVisitBottomSheet
                    .newInstance(id)
                    .showOnce(parentFragmentManager, "CancelSiteVisitBottomSheet")
            }
        }

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
        parentFragmentManager.setFragmentResultListener(
            PostponeSiteVisitBottomSheet.RESULT_KEY,
            viewLifecycleOwner,
        ) { _, _ ->
            dismissAllowingStateLoss()
        }
        parentFragmentManager.setFragmentResultListener(
            CancelSiteVisitBottomSheet.RESULT_KEY,
            viewLifecycleOwner,
        ) { _, _ ->
            dismissAllowingStateLoss()
        }

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
        circleConsulting?.setOnClickListener(hint)
        circlePickedFromSite?.setOnClickListener(hint)
        circleDropped?.setOnClickListener(hint)
        // Own vehicle circles
        circleOwnClientDeparture?.setOnClickListener(hint)
        circleOwnOnSite?.setOnClickListener(hint)
        circleOwnConsulting?.setOnClickListener(hint)
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
        // First-frame gate from the list-row status only. A lifecycle
        // "completed"/"done" must NOT pre-lock the form (outcome may still be
        // pending) — only an actually recorded/terminal outcome status does.
        // bindEnriched() re-derives this from the full visit once it loads.
        isOutcomeLocked = isOutcomeRecordedStatus(rawStatus)
        outcomeStatusEligible = isOutcomeStatusEligible(rawStatus)
        currentTerminalLabel = terminalStepLabelFor(rawStatus)
        updatePostponeVisibility(rawStatus)
        updateCancelVisibility(rawStatus)
        bindLeadTemperature(args.getString(ARG_LEAD_TEMPERATURE))

        // First-frame bind from list-row arguments. Real values land
        // shortly after via bindEnriched() once the detail fetch
        // returns; until then prefer em-dashes over fake values so the
        // sheet never displays seeded mock data even for a flash.
        tvTitle?.text = placeName?.takeIf { it.isNotBlank() } ?: "Site Visit"
        val initialName = leadName?.takeIf { it.isNotBlank() }
        clientDisplayName = initialName?.let { formatPersonName(it) }
        tvClientName?.text = initialName?.let { formatPersonName(it) } ?: "—"
        tvVisitorName?.text = initialName?.let { formatPersonName(it) } ?: "—"
        tvPhone?.text = leadPhone?.takeIf { it.isNotBlank() } ?: "—"
        clientPhone = leadPhone?.takeIf { it.isNotBlank() }
        refreshCallButtons()
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

        // Seed the vehicle mode from the list-row args so the FIRST frame
        // already shows the right stepper. Defaulting to Cab and flipping in
        // bindEnriched made the badge/stepper visibly jump between "Own
        // Vehicle" and "Cab Vehicle" on open. The enriched fetch still
        // re-asserts this, but now it usually confirms rather than corrects.
        val argTravelMode = args.getString(ARG_TRAVEL_MODE)
        val argVehiclePref = args.getString(ARG_VEHICLE_PREFERENCE)
        isOwnVehicleSelected =
            argTravelMode == "own_vehicle" || argVehiclePref == "own_vehicle"
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

    /** "POSTPONED" / "CANCELLED" for terminal statuses (drives the stepper's
     *  terminal node); null for every other status. */
    private fun terminalStepLabelFor(status: String?): String? {
        return when (status?.trim()?.lowercase(Locale.US)) {
            "postponed" -> "POSTPONED"
            "cancelled", "canceled" -> "CANCELLED"
            "no_show" -> "NO SHOW"
            else -> null
        }
    }

    private fun bindStatusHeader(status: String) {
        val lower = status.lowercase(Locale.US)
        val displayName = when {
            lower in setOf("completed", "complete", "done") -> "COMPLETED"
            lower in setOf("cancelled", "canceled", "no_show") -> "CANCELLED"
            lower == "postponed" -> "POSTPONED"
            lower in setOf("reached_cp", "reached cp") -> "REACHED CP"
            lower in setOf("picked_up", "client_started") -> "PICKED FROM CP"
            lower in setOf("on_site", "arrived") -> "ON SITE"
            lower in setOf("consulting", "on_counselling", "on counselling") -> "ON COUNSELLING"
            lower == "picked_from_site" -> "PICKED FROM SITE"
            lower == "dropped" -> "DROPPED"
            lower == "assigned" -> "ASSIGNED"
            else -> "SCHEDULED"
        }
        tvStatus?.text = displayName

        val color = when (displayName) {
            "COMPLETED" -> Color.parseColor("#027A48")
            "CANCELLED" -> Color.parseColor("#B42318")
            "POSTPONED" -> Color.parseColor("#B54708")
            "REACHED CP", "PICKED FROM CP", "ON SITE", "ON COUNSELLING", "PICKED FROM SITE", "DROPPED" ->
                Color.parseColor("#B54708")
            else -> Color.parseColor("#004EEB")
        }
        tvStatus?.setTextColor(color)
        vStatusDot?.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
    }

    private fun mapStatusToStepIndex(status: String): Int {
        val lower = status.lowercase(Locale.US)
        // 9-node cab stepper: Scheduled(0) Assigned(1) ReachedCP(2) PickedFromCP(3)
        // OnSite(4) Consulting(5) PickedFromSite(6) Dropped(7) Done(8). ReachedCP
        // is timestamp-driven (no status maps to it); a picked_up status implies
        // the driver already reached CP.
        return when (lower) {
            "completed", "complete", "done", "closed" -> 8
            "dropped" -> 7
            "picked_from_site", "picked from site" -> 6
            "consulting", "on_counselling", "on counselling" -> 5
            "on_site", "on site", "arrived" -> 4
            "picked_up", "picked up", "client_started", "client started" -> 3
            "assigned" -> 1
            else -> 0
        }
    }

    private fun mapStatusToOwnStepIndex(status: String): Int {
        val lower = status.lowercase(Locale.US)
        return when (lower) {
            "completed", "complete", "done", "closed" -> 4
            "consulting", "on_counselling", "on counselling" -> 3
            "dropped" -> 2
            "picked_from_site", "picked from site" -> 2
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

        // Postponed/cancelled keep their reached progress; the own-vehicle
        // stepper renders the terminal node from it (see updateStepper).
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
     *   2  Picked from CP       — status="picked_up" / "client_started"
     *                             OR driver tapped "Start trip" (travelDeskStartedAt)
     *   3  On Site              — status="on_site"
     *                             OR driver tapped "Arrived" (travelDeskOnSiteAt)
     *   4  Picked from Site     — status="picked_from_site"
     *                             OR driver tapped "Picked from site"
     *                             (travelDeskPickedFromSiteAt)
     *   5  Dropped              — status="dropped"
     *                             OR driver tapped "Ended" (travelDeskEndedAt)
     *   6  Done                 — status="completed" (or any "done" alias)
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

        // Driver-side boosts: explicit timestamps trump status when they're
        // ahead. travelDeskArrivedAt = Reached CP (2); travelDeskStartedAt =
        // Picked from CP (3). Same precedence as the web mergeVisitProgress.
        // (Postponed/cancelled keep their reached progress here — updateStepper
        // renders the terminal node from it + the header carries the tag.)
        val driverBoost = when {
            snapshot?.travelDeskEndedAt != null -> 7
            snapshot?.travelDeskPickedFromSiteAt != null -> 6
            snapshot?.travelDeskOnSiteAt != null -> 4
            snapshot?.travelDeskStartedAt != null -> 3
            snapshot?.travelDeskArrivedAt != null -> 2
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

        // Cab visits have TWO decoupled tracks. The SV status covers the visit
        // up to counselling (step 5); the fleet return steps — Picked from Site
        // (6), Dropped (7), Done (8) — belong to the driver and come only from
        // the travelDesk* timestamps, so recording the SV outcome must NOT slide
        // the bar past where the driver actually is. Once the driver HAS ended
        // (travelDeskEndedAt) the SV status may fill the final step so a
        // completed outcome shows Done. Own-vehicle visits keep the full status
        // contribution.
        val fleetEnded = snapshot?.travelDeskEndedAt != null
        val statusContribution =
            if (!isOwnVehicleSelected && !fleetEnded) minOf(baseFromStatus, 5)
            else baseFromStatus

        return maxOf(statusContribution, driverBoost, vehicleBoost)
    }

    private fun updateStepper(activeIndex: Int) {
        currentStepIndex = activeIndex
        val context = requireContext()
        val blueColor = Color.parseColor("#004EEB")
        val grayColor = Color.parseColor("#98A2B3")

        if (isOwnVehicleSelected) {
            // Translate the shared cab-parity index to the five own-vehicle stages.
            val ownActiveIndex = when {
                activeIndex <= 1 -> 0
                activeIndex == 2 -> 1
                activeIndex == 3 -> 2
                activeIndex == 4 -> 3
                else -> 4
            }

            // Own-vehicle 5-node stepper + 4 connector lines.
            val ownCircles = listOf(
                circleOwnScheduled, circleOwnClientDeparture, circleOwnOnSite,
                circleOwnConsulting, circleOwnDone,
            )
            val ownIcons = listOf(
                ivOwnScheduled, ivOwnClientDeparture, ivOwnOnSite, ivOwnConsulting, ivOwnDone,
            )
            val ownStepLabels = listOf(
                labelOwnScheduled, labelOwnClientDeparture, labelOwnOnSite,
                labelOwnConsulting, labelOwnDone,
            )
            val ownDefaultLabels = listOf(
                "SCHEDULED", "CLIENT DEPARTURE", "ON SITE", "CONSULTING", "DONE",
            )
            val ownIconResList = listOf(
                R.drawable.ic_check_white, R.drawable.ic_car_outline,
                R.drawable.ic_task_building, R.drawable.ic_auth_user, R.drawable.ic_check_circle,
            )
            val ownStepLines = listOf(stepLineOwn1, stepLineOwn2, stepLineOwn3, stepLineOwn4)
            val ownLineGray = Color.parseColor("#EAECF0")

            fun paintOwnNode(i: Int, state: String, labelText: String) {
                val circle = ownCircles[i]
                val icon = ownIcons[i]
                val label = ownStepLabels[i]
                when (state) {
                    "done" -> {
                        circle?.background = ContextCompat.getDrawable(context, R.drawable.bg_trip_progress_figma_active)
                        icon?.setImageResource(R.drawable.ic_check_white)
                        icon?.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                        label?.setTextColor(blueColor)
                        label?.typeface = ResourcesCompat.getFont(context, R.font.inter_semibold)
                    }
                    "active" -> {
                        circle?.background = ContextCompat.getDrawable(context, R.drawable.bg_trip_progress_figma_active_current)
                        icon?.setImageResource(ownIconResList[i])
                        icon?.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                        label?.setTextColor(blueColor)
                        label?.typeface = ResourcesCompat.getFont(context, R.font.inter_bold)
                    }
                    else -> {
                        circle?.background = ContextCompat.getDrawable(context, R.drawable.bg_trip_progress_figma_inactive)
                        icon?.setImageResource(ownIconResList[i])
                        icon?.imageTintList = android.content.res.ColorStateList.valueOf(grayColor)
                        label?.setTextColor(grayColor)
                        label?.typeface = ResourcesCompat.getFont(context, R.font.inter_medium)
                    }
                }
                label?.text = labelText
            }

            val ownTerminal = currentTerminalLabel
            if (ownTerminal != null) {
                val terminalIdx = (ownActiveIndex.coerceIn(0, ownCircles.size - 2)) + 1
                for (i in ownCircles.indices) {
                    val wrapper = ownCircles[i]?.parent as? View
                    when {
                        i < terminalIdx -> {
                            wrapper?.visibility = View.VISIBLE
                            paintOwnNode(i, "done", ownDefaultLabels[i])
                        }
                        i == terminalIdx -> {
                            wrapper?.visibility = View.VISIBLE
                            paintOwnNode(i, "done", ownTerminal)
                        }
                        else -> wrapper?.visibility = View.GONE
                    }
                }
                for (i in ownStepLines.indices) {
                    val show = i < terminalIdx
                    ownStepLines[i]?.visibility = if (show) View.VISIBLE else View.GONE
                    ownStepLines[i]?.setBackgroundColor(if (show) blueColor else ownLineGray)
                }
            } else {
                for (c in ownCircles) (c?.parent as? View)?.visibility = View.VISIBLE
                for (l in ownStepLines) l?.visibility = View.VISIBLE
                for (i in ownCircles.indices) {
                    val state = when {
                        i < ownActiveIndex -> "done"
                        i == ownActiveIndex -> "active"
                        else -> "inactive"
                    }
                    paintOwnNode(i, state, ownDefaultLabels[i])
                }
                for (i in ownStepLines.indices) {
                    ownStepLines[i]?.setBackgroundColor(
                        if (ownActiveIndex >= (i + 1)) blueColor else ownLineGray,
                    )
                }
            }

            // Outcome buttons activation gate — only once counselling has
            // started (client QR scan) and through the later statuses, until
            // the outcome is recorded. Not driven by the driver's stepper.
            if (isOutcomeLocked) {
                lockOutcomeButtons("This site visit outcome is already completed.")
            } else if (outcomeStatusEligible || isFleetOutcomePending) {
                btnBooking?.isEnabled = true
                btnBooking?.alpha = 1.0f
                btnNotInterested?.isEnabled = true
                btnNotInterested?.alpha = 1.0f
                btnPostponed?.isEnabled = true
                btnPostponed?.alpha = 1.0f
                btnOther?.isEnabled = true
                btnOther?.alpha = 1.0f

                // Wire each outcome to the shared CP completion sheet,
                // locked to the chosen form so field staff do not see
                // unrelated Booking / Postpone / Not Interested tabs.
                btnBooking?.setOnClickListener { openSiteVisitOutcomeSheet("converted_to_booking") }
                btnNotInterested?.setOnClickListener { openSiteVisitOutcomeSheet("not_interested") }
                btnPostponed?.setOnClickListener { openSiteVisitOutcomeSheet("follow_up") }
                btnOther?.setOnClickListener { openSiteVisitOutcomeSheet("other") }
            } else {
                btnBooking?.isEnabled = false
                btnBooking?.alpha = 0.4f
                btnNotInterested?.isEnabled = false
                btnNotInterested?.alpha = 0.4f
                btnPostponed?.isEnabled = false
                btnPostponed?.alpha = 0.4f
                btnOther?.isEnabled = false
                btnOther?.alpha = 0.4f

                val lockedToast: (View) -> Unit = {
                    Toast.makeText(context, "Outcome opens after the client QR scan (counselling).", Toast.LENGTH_SHORT).show()
                }
                btnBooking?.setOnClickListener(lockedToast)
                btnNotInterested?.setOnClickListener(lockedToast)
                btnPostponed?.setOnClickListener(lockedToast)
                btnOther?.setOnClickListener(lockedToast)
            }
        } else {
            // Cab Vehicle: 9-node stepper (Scheduled, Assigned, Reached CP,
            // Picked from CP, On Site, Consulting, Picked from Site, Dropped,
            // Done) + 8 connector lines, driven off `activeIndex`.
            val circles = listOf(
                circleScheduled, circleAssigned, circleReachedCp, circlePickedUp,
                circleOnSite, circleConsulting, circlePickedFromSite, circleDropped, circleDone,
            )
            val icons = listOf(
                ivScheduled, ivAssigned, ivReachedCp, ivPickedUp,
                ivOnSite, ivConsulting, ivPickedFromSite, ivDropped, ivDone,
            )
            val stepLabels = listOf(
                labelScheduled, labelAssigned, labelReachedCp, labelPickedUp,
                labelOnSite, labelConsulting, labelPickedFromSite, labelDropped, labelDone,
            )
            val defaultLabels = listOf(
                "SCHEDULED", "ASSIGNED", "REACHED CP", "PICKED FROM CP",
                "ON SITE", "CONSULTING", "PICKED FROM SITE", "DROPPED", "DONE",
            )
            val iconResList = listOf(
                R.drawable.ic_check_white, R.drawable.ic_car_outline, R.drawable.ic_map_pin,
                R.drawable.ic_nav_home, R.drawable.ic_task_building, R.drawable.ic_auth_user,
                R.drawable.ic_nav_home, R.drawable.ic_map_pin, R.drawable.ic_check_circle,
            )
            val stepLines = listOf(
                stepLine1, stepLine2, stepLine3, stepLine4,
                stepLine5, stepLine6, stepLine7, stepLine8,
            )
            val lineGray = Color.parseColor("#EAECF0")

            fun paintNode(i: Int, state: String, labelText: String) {
                val circle = circles[i]
                val icon = icons[i]
                val label = stepLabels[i]
                when (state) {
                    "done" -> {
                        circle?.background = ContextCompat.getDrawable(context, R.drawable.bg_trip_progress_figma_active)
                        icon?.setImageResource(R.drawable.ic_check_white)
                        icon?.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                        label?.setTextColor(blueColor)
                        label?.typeface = ResourcesCompat.getFont(context, R.font.inter_semibold)
                    }
                    "active" -> {
                        circle?.background = ContextCompat.getDrawable(context, R.drawable.bg_trip_progress_figma_active_current)
                        icon?.setImageResource(iconResList[i])
                        icon?.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                        label?.setTextColor(blueColor)
                        label?.typeface = ResourcesCompat.getFont(context, R.font.inter_bold)
                    }
                    else -> {
                        circle?.background = ContextCompat.getDrawable(context, R.drawable.bg_trip_progress_figma_inactive)
                        icon?.setImageResource(iconResList[i])
                        icon?.imageTintList = android.content.res.ColorStateList.valueOf(grayColor)
                        label?.setTextColor(grayColor)
                        label?.typeface = ResourcesCompat.getFont(context, R.font.inter_medium)
                    }
                }
                label?.text = labelText
            }

            val terminal = currentTerminalLabel
            if (terminal != null) {
                // Postponed / cancelled: show the reached prefix, then a terminal
                // node in place of the next step, and hide everything after —
                // parity with the web stepper's terminalProgressSnapshot.
                val terminalIdx = (activeIndex.coerceIn(0, circles.size - 2)) + 1
                for (i in circles.indices) {
                    val wrapper = circles[i]?.parent as? View
                    when {
                        i < terminalIdx -> {
                            wrapper?.visibility = View.VISIBLE
                            paintNode(i, "done", defaultLabels[i])
                        }
                        i == terminalIdx -> {
                            wrapper?.visibility = View.VISIBLE
                            paintNode(i, "done", terminal)
                        }
                        else -> wrapper?.visibility = View.GONE
                    }
                }
                for (i in stepLines.indices) {
                    val show = i < terminalIdx
                    stepLines[i]?.visibility = if (show) View.VISIBLE else View.GONE
                    stepLines[i]?.setBackgroundColor(if (show) blueColor else lineGray)
                }
            } else {
                // Normal progression — every node/line visible.
                for (c in circles) (c?.parent as? View)?.visibility = View.VISIBLE
                for (l in stepLines) l?.visibility = View.VISIBLE
                for (i in circles.indices) {
                    // Consulting-gap: the SV can reach counselling while the fleet
                    // legs trail — hold Reached CP / Picked from CP / On Site
                    // inactive until the matching travelDesk* stamp exists.
                    val gap = isConsultingStatus && (
                        (i == 2 && !hasFleetArrived) ||
                        (i == 3 && !hasFleetStart) ||
                        (i == 4 && !hasFleetOnSite)
                    )
                    val state = when {
                        gap -> "inactive"
                        i < activeIndex -> "done"
                        i == activeIndex -> "active"
                        else -> "inactive"
                    }
                    paintNode(i, state, defaultLabels[i])
                }
                // Line i sits between node i and i+1 → blue once node i+1 is
                // reached, held back if the destination node is in the fleet gap.
                for (i in stepLines.indices) {
                    val destGap = isConsultingStatus && (
                        (i == 1 && !hasFleetArrived) ||
                        (i == 2 && !hasFleetStart) ||
                        (i == 3 && !hasFleetOnSite)
                    )
                    val on = activeIndex >= (i + 1) && !destGap
                    stepLines[i]?.setBackgroundColor(if (on) blueColor else lineGray)
                }
            }

            // Outcome buttons activation gate — only once counselling has
            // started (client QR scan) and through the later statuses, until
            // the outcome is recorded. A cab merely reaching "on site" (driver
            // stepper) must NOT open it.
            if (isOutcomeLocked) {
                lockOutcomeButtons("This site visit outcome is already completed.")
            } else if (outcomeStatusEligible || isFleetOutcomePending) {
                btnBooking?.isEnabled = true
                btnBooking?.alpha = 1.0f
                btnNotInterested?.isEnabled = true
                btnNotInterested?.alpha = 1.0f
                btnPostponed?.isEnabled = true
                btnPostponed?.alpha = 1.0f
                btnOther?.isEnabled = true
                btnOther?.alpha = 1.0f

                // Wire each outcome to the shared CP completion sheet,
                // locked to the chosen form so field staff do not see
                // unrelated Booking / Postpone / Not Interested tabs.
                btnBooking?.setOnClickListener { openSiteVisitOutcomeSheet("converted_to_booking") }
                btnNotInterested?.setOnClickListener { openSiteVisitOutcomeSheet("not_interested") }
                btnPostponed?.setOnClickListener { openSiteVisitOutcomeSheet("follow_up") }
                btnOther?.setOnClickListener { openSiteVisitOutcomeSheet("other") }
            } else {
                btnBooking?.isEnabled = false
                btnBooking?.alpha = 0.4f
                btnNotInterested?.isEnabled = false
                btnNotInterested?.alpha = 0.4f
                btnPostponed?.isEnabled = false
                btnPostponed?.alpha = 0.4f
                btnOther?.isEnabled = false
                btnOther?.alpha = 0.4f

                val lockedToast: (View) -> Unit = {
                    Toast.makeText(context, "Outcome opens after the client QR scan (counselling).", Toast.LENGTH_SHORT).show()
                }
                btnBooking?.setOnClickListener(lockedToast)
                btnNotInterested?.setOnClickListener(lockedToast)
                btnPostponed?.setOnClickListener(lockedToast)
                btnOther?.setOnClickListener(lockedToast)
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
        btnOther?.isEnabled = false
        btnOther?.alpha = 0.4f

        val lockedToast: (View) -> Unit = {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
        btnBooking?.setOnClickListener(lockedToast)
        btnNotInterested?.setOnClickListener(lockedToast)
        btnPostponed?.setOnClickListener(lockedToast)
        btnOther?.setOnClickListener(lockedToast)
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
                "postponed", "follow_up" -> "Follow up"
                "other" -> "Others"
                else -> return@setFragmentResultListener
            }

            updateStepper(8)
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
            .showOnce(parentFragmentManager, "site_visit_${outcome}_outcome")
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

    private fun refreshCallButtons() {
        tvCallClient?.text = callLabel("Client", clientDisplayName)
        tvCallDriver?.text = callLabel("Driver", driverDisplayName)
        setCallButtonEnabled(btnCallClient, !clientPhone.isNullOrBlank())
        setCallButtonEnabled(btnCallDriver, !driverPhone.isNullOrBlank())
    }

    private fun callLabel(role: String, name: String?): String =
        name?.takeIf { it.isNotBlank() && it != "—" }?.let { "Call $it" } ?: "Call $role"

    private fun setCallButtonEnabled(button: View?, enabled: Boolean) {
        button?.isEnabled = enabled
        button?.isClickable = enabled
        button?.alpha = if (enabled) 1f else 0.4f
    }

    /** Open the phone dialer pre-filled with the number. ACTION_DIAL needs no
     *  permission (unlike ACTION_CALL) — the user taps the green call button. */
    private fun dialPhone(phone: String?) {
        val number = phone?.trim().orEmpty()
        if (number.isBlank()) {
            Toast.makeText(requireContext(), "No number available", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_DIAL,
                    android.net.Uri.parse("tel:$number"),
                ),
            )
        }.onFailure {
            Toast.makeText(requireContext(), "Couldn't open the dialer", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindEnriched(visit: CpVisitDetail) {
        // --- Customer name + phone -------------------------------------
        // Precedence: CP-level client → lead → clientPlace label. Show
        // an em-dash only when the backend has nothing — the previous
        // "Client" / "AKASH.B" / "22 yrs Veg" fallbacks made empty
        // records look populated and indistinguishable from real ones.
        // Client name comes only from the resolved client / lead — NEVER from
        // clientPlace.name. For a pure SV the backend synthesizes clientPlace
        // from the PROJECT, so clientPlace.name is the project name; using it
        // here leaked the project ("GS - TMZ 4.0 Phase II") into the client
        // field. "—" is correct when the backend has no client/lead name.
        val rawDisplayName = visit.client?.clientName?.takeIf { it.isNotBlank() }
            ?: visit.lead?.contactName?.takeIf { it.isNotBlank() }
        val displayName = rawDisplayName?.let { formatPersonName(it) } ?: "—"
        clientDisplayName = displayName
        tvClientName?.text = displayName
        tvVisitorName?.text = displayName

        val phone = visit.client?.mobileNumber?.takeIf { it.isNotBlank() }
            ?: visit.lead?.mobileNumber?.takeIf { it.isNotBlank() }
        tvPhone?.text = phone ?: "—"
        clientPhone = phone
        // CP-linked SVs carry driver contact in proposedSiteVisit; pure-SV
        // envelopes return it on the root visit. Support both so an assigned
        // driver's call action does not remain incorrectly disabled.
        val rawDriverName = visit.proposedSiteVisit?.driverName?.takeIf { it.isNotBlank() }
            ?: visit.driverName?.takeIf { it.isNotBlank() }
        driverDisplayName = rawDriverName?.let { formatPersonName(it) }
        driverPhone = visit.proposedSiteVisit?.driverPhone?.takeIf { it.isNotBlank() }
            ?: visit.driverPhone?.takeIf { it.isNotBlank() }
        refreshCallButtons()
        bindLeadTemperature(visit.lead?.temperature)

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

        val effStatus = visit.proposedSiteVisit?.status
            ?.takeIf { it.isNotBlank() }
            ?: visit.status
            ?: ""
        isConsultingStatus = effStatus.lowercase(Locale.US) in setOf(
            "consulting",
            "on_counselling",
            "on counselling",
        )
        hasFleetArrived = proposed?.travelDeskArrivedAt != null
        hasFleetStart = proposed?.travelDeskStartedAt != null
        hasFleetOnSite = proposed?.travelDeskOnSiteAt != null
        currentTerminalLabel = terminalStepLabelFor(effStatus)
        isOutcomeLocked = isOutcomeAlreadyRecorded(visit)

        // Vehicle type first — computeWebParityStepIndex reads it.
        isOwnVehicleSelected = proposed?.travelMode == "own_vehicle" || visit.vehiclePreference == "own_vehicle"

        // Compute the SAME step index that drives the visible stepper, then open
        // the outcome whenever the client has reached the site (ON SITE, step 4)
        // through DROPPED / DONE. This mirrors the web (canRecordOutcomeNow:
        // status in on_counselling / picked_from_site / dropped) AND survives the
        // cab case where sv.status lags behind the fleet's travelDesk* return leg
        // — the earlier status-only check left the outcome greyed at DROPPED.
        // isOutcomeLocked still closes it the moment an outcome is recorded.
        val outcomeStepIndex = if (effStatus.isNotEmpty()) {
            computeWebParityStepIndex(status = effStatus, snapshot = proposed)
        } else 0
        val fleetReachedSite = proposed?.travelDeskOnSiteAt != null ||
            proposed?.travelDeskPickedFromSiteAt != null ||
            proposed?.travelDeskEndedAt != null
        outcomeStatusEligible =
            isOutcomeStatusEligible(effStatus) ||
            isOutcomeStatusEligible(visit.status) ||
            fleetReachedSite ||
            outcomeStepIndex >= ON_SITE_STEP_INDEX
        isFleetOutcomePending = visit.completedOffline == true && visit.outcome.isNullOrBlank()
        updatePostponeVisibility(effStatus)
        updateCancelVisibility(effStatus)

        toggleStepperVisibility()

        if (effStatus.isNotEmpty()) {
            updateStepper(outcomeStepIndex)
            // Drive the header off the SAME computed step, not the raw
            // status. Agency trips advance via travelDesk* timestamps while
            // sv.status stays "scheduled"; binding the header to the raw
            // status showed "SCHEDULED" over a stepper sitting on DROPPED.
            bindStatusHeaderForStep(outcomeStepIndex, effStatus)
        }
    }

    /**
     * Header label that always agrees with the stepper. Terminal outcomes
     * (completed / cancelled) keep their own coloured labels; every other
     * state is synthesised from the computed step index so the header can't
     * lag the progress bar when the SV row's status trails the driver's taps.
     */
    private fun bindStatusHeaderForStep(stepIndex: Int, rawStatus: String) {
        val lower = rawStatus.lowercase(Locale.US)
        when {
            lower in setOf("completed", "complete", "done", "closed") ->
                bindStatusHeader("completed")
            // Closed states keep their own tag and must NOT be relabelled from
            // the driver-boosted step (a postponed cab trip whose driver had
            // tapped "arrived" was showing "ON SITE" instead of "POSTPONED").
            lower in setOf("cancelled", "canceled", "no_show", "postponed") ->
                bindStatusHeader(rawStatus)
            else -> bindStatusHeader(
                when (stepIndex) {
                    8 -> "completed"
                    7 -> "dropped"
                    6 -> "picked_from_site"
                    5 -> "on_counselling"
                    4 -> "on_site"
                    3 -> "picked_up"
                    2 -> "reached_cp"
                    1 -> "assigned"
                    else -> "scheduled"
                }
            )
        }
    }

    private fun bindLeadTemperature(rawTemperature: String?) {
        val badge = when (rawTemperature?.trim()?.lowercase(Locale.US)) {
            "hot" -> Triple("HOT LEAD", R.drawable.bg_sv_status_red, R.attr.colorError)
            "warm" -> Triple("WARM LEAD", R.drawable.bg_sv_status_orange, R.attr.colorWarning)
            "cold" -> Triple("COLD LEAD", R.drawable.bg_sv_status_blue, R.attr.colorAccentPrimary)
            else -> null
        }
        tvType?.apply {
            visibility = if (badge == null) View.GONE else View.VISIBLE
            badge?.let { (label, backgroundRes, colorAttr) ->
                text = label
                setBackgroundResource(backgroundRes)
                setTextColor(resolveThemeColor(colorAttr))
            }
        }
    }

    private fun resolveThemeColor(attr: Int): Int {
        val value = TypedValue()
        requireContext().theme.resolveAttribute(attr, value, true)
        return value.data
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
        btnOther?.isEnabled = false

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
                updateStepper(8)
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
                btnOther?.isEnabled = true
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
            visit.cancelledAt != null

    /**
     * True only when a SALES OUTCOME has actually been recorded (or the SV was
     * cancelled / converted) — the point at which the outcome form must lock.
     *
     * A bare lifecycle "completed" / "done" WITHOUT a recorded outcome is NOT a
     * lock signal: the fleet can finish the cab trip (advanceCabLifecycle /
     * completed-offline) before field staff capture the result, and that visit
     * must still be able to record its outcome. Mirrors the backend setOutcome
     * contract, which accepts "completed" while `outcome` is still empty.
     */
    private fun isOutcomeAlreadyRecorded(visit: CpVisitDetail): Boolean {
        // For a CP-linked SV the mobile envelope carries the linked CP's
        // identity — including the CP's own outcome / convertedBookingId /
        // cancelledAt — while the SV's own terminal fields live on
        // proposedSiteVisit. Prefer the SV's values so a converted CP (whose
        // outcome is always set) doesn't falsely lock the SV outcome buttons.
        // When proposedSiteVisit is present (always for an SV envelope) use ONLY
        // the SV's own fields — never fall back to the leaked top-level CP values,
        // or a null SV outcome would drop through to the CP's and re-lock.
        val sv = visit.proposedSiteVisit
        val outcome = if (sv != null) sv.outcome else visit.outcome
        val convertedBookingId = if (sv != null) sv.convertedBookingId else visit.convertedBookingId
        val cancelledAt = if (sv != null) sv.cancelledAt else visit.cancelledAt
        // Read the SV's OWN status when present (same precedence as the fields
        // above) so a CP-linked envelope whose top-level status is the CP's can
        // never false-lock the SV outcome. getForMobileId mirrors sv.status onto
        // the top level today, but this keeps it correct if that ever changes.
        val statusForLock = if (sv?.status?.isNotBlank() == true) sv.status else visit.status
        return !outcome.isNullOrBlank() ||
            isOutcomeRecordedStatus(statusForLock) ||
            convertedBookingId != null ||
            cancelledAt != null
    }

    /** Statuses that mean the outcome/terminal decision is already taken —
     *  deliberately EXCLUDES lifecycle "completed"/"done"/"closed" so a trip
     *  finished without an outcome keeps the form open. */
    private fun isOutcomeRecordedStatus(value: String?): Boolean {
        val lower = value?.trim()?.lowercase(Locale.US).orEmpty()
        return lower in setOf(
            "cancelled",
            "canceled",
            "no_show",
            "converted_to_booking",
            "not_interested",
            "postponed",
            "other",
        )
    }
    // NOTE: `completedAt` is deliberately NOT a lock signal. For cab visits the
    // fleet return-leg advances the status (picked_from_site / dropped) while a
    // stale `completedAt` can linger, and a trip can finish before the field
    // staff record the outcome — both left the outcome form greyed at
    // picked_from_site even though the backend `setOutcome` still accepts
    // on_counselling / picked_from_site / dropped. A genuinely completed visit
    // is still caught by its terminal status, recorded outcome,
    // convertedBookingId, or cancelledAt above, so dropping the raw
    // `completedAt` check only unblocks the outcome-still-pending case.

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

    /**
     * Statuses at which the outcome form may be recorded — the SV must have
     * reached counselling (client QR scan) and may still be at a later stage.
     * Deliberately excludes on_site and earlier, so a driver reaching the site
     * does not open the form before counselling has started. Mirrors the
     * backend `setOutcome` transition set (on_counselling / picked_from_site /
     * dropped).
     */
    private fun isOutcomeStatusEligible(status: String?): Boolean {
        val lower = status?.trim()?.lowercase(Locale.US).orEmpty()
        return lower in setOf(
            "on_counselling",
            "on counselling",
            "consulting",
            "picked_from_site",
            "picked from site",
            "dropped",
            // Lifecycle-finished but outcome-pending: the fleet may mark the
            // cab trip completed/done before field staff record the result, so
            // the outcome form must stay recordable here too (the lock above is
            // what closes it once an outcome actually exists).
            "completed",
            "complete",
            "done",
            "closed",
        )
    }

    private fun updatePostponeVisibility(status: String?) {
        val lower = status?.trim()?.lowercase(Locale.US).orEmpty()
        val canEdit = session.hasPermission("marketing.siteVisits.edit")
        // Reschedule (Postpone) is only offered BEFORE the client reaches the
        // site. From on_site onwards the visit is happening — it runs to an
        // outcome, so hide the postpone action.
        val arrivedOrLater = lower in setOf(
            "on_site",
            "on site",
            "consulting",
            "on_counselling",
            "on counselling",
            "picked_from_site",
            "picked from site",
            "dropped",
            "completed",
            "complete",
            "done",
            "closed",
            "cancelled",
            "canceled",
            "no_show",
        )
        btnPostponeSiteVisit?.visibility =
            if (canEdit && !isOutcomeLocked && !arrivedOrLater) View.VISIBLE else View.GONE
    }

    private fun updateCancelVisibility(status: String?) {
        val terminal = status?.trim()?.lowercase(Locale.US).orEmpty() in setOf(
            "completed",
            "complete",
            "done",
            "closed",
            "cancelled",
            "canceled",
            "no_show",
            "postponed",
        )
        btnCancelSiteVisit?.visibility =
            if (session.hasPermission("marketing.siteVisits.cancel") && !terminal) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    private fun formatDateOnly(scheduledDate: String): String {
        val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val parse = runCatching { ymd.parse(scheduledDate) }.getOrNull() ?: return scheduledDate
        return SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(parse)
    }

    companion object {
        // Cab web-parity stepper index for "On Site" (Scheduled 0 · Assigned 1 ·
        // Reached CP 2 · Picked from CP 3 · On Site 4 · Consulting 5 · Picked
        // from Site 6 · Dropped 7 · Done 8). Outcome opens at this step or later.
        private const val ON_SITE_STEP_INDEX = 4
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
        private const val ARG_TRAVEL_MODE = "arg_travel_mode"
        private const val ARG_VEHICLE_PREFERENCE = "arg_vehicle_preference"
        private const val ARG_LEAD_TEMPERATURE = "arg_lead_temperature"

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
                    putString(ARG_TRAVEL_MODE, visit.travelMode)
                    putString(ARG_VEHICLE_PREFERENCE, visit.vehiclePreference)
                }
            }
        }

        fun forScannedVisit(
            siteVisitId: String,
            leadTemperature: String?,
        ): SiteVisitOverviewFragment {
            return SiteVisitOverviewFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_VISIT_ID, siteVisitId)
                    putString(ARG_STATUS, "consulting")
                    putString(ARG_LEAD_TEMPERATURE, leadTemperature)
                }
            }
        }
    }
}
