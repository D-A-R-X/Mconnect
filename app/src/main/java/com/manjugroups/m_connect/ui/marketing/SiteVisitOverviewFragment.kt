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
import com.manjugroups.m_connect.network.SiteVisitIdRequest
import com.manjugroups.m_connect.network.TodayVisit
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
    private var transitioning: Boolean = false

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

        // Outcome buttons
        btnBooking = view.findViewById(R.id.btnOutcomeBooking)
        btnNotInterested = view.findViewById(R.id.btnOutcomeNotInterested)
        btnPostponed = view.findViewById(R.id.btnOutcomePostponed)

        // Bind initial arguments
        bindInitialArgs()

        // Wire stepper circle taps to fire the next-state mutation.
        // Each circle only acts if it represents the IMMEDIATE next
        // step from current — out-of-order taps no-op with a toast so
        // the user can't skip past an unfinished phase.
        wireStepperTaps()

        // Load enriched details
        val id = visitId
        if (!id.isNullOrBlank()) {
            loadEnrichedDetail(id)
        }
    }

    private fun wireStepperTaps() {
        // Index 0 (Scheduled) is the initial state — no tap needed.
        // Index 1 (Assigned) auto-progresses server-side when staff is
        // attached, so we don't wire it as a manual transition.
        // Index 2..4 are the user-driven transitions:
        //   pickedUp        → markPickedUp / markClientStarted
        //   onSite          → markArrivedSite
        //   dropped         → markDropped
        // Index 5 (Done) lands automatically when setOutcome fires.
        circlePickedUp?.setOnClickListener {
            advanceTo(stepIndex = 2, label = "Picked Up")
        }
        circleOnSite?.setOnClickListener {
            advanceTo(stepIndex = 3, label = "On Site")
        }
        circleDropped?.setOnClickListener {
            advanceTo(stepIndex = 4, label = "Dropped")
        }
    }

    /**
     * Fires the mutation that takes the SV from currentStepIndex up
     * to [stepIndex]. Out-of-order taps (jumping multiple steps OR
     * tapping a step already passed) show a toast and no-op so the
     * server's assertTransition rules don't reject us with a 500.
     */
    private fun advanceTo(stepIndex: Int, label: String) {
        if (transitioning) return
        if (stepIndex <= currentStepIndex) {
            Toast.makeText(
                requireContext(),
                "Already at or past '$label'",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        if (stepIndex != currentStepIndex + 1) {
            Toast.makeText(
                requireContext(),
                "Finish the previous step first before marking '$label'",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val svId = visitId
        if (svId.isNullOrBlank()) {
            Toast.makeText(requireContext(), "Site visit id missing", Toast.LENGTH_SHORT).show()
            return
        }
        transitioning = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = when (stepIndex) {
                    2 -> geoApi.markSiteVisitPickedUp(
                        session.bearerToken,
                        SiteVisitIdRequest(svId),
                    )
                    3 -> geoApi.markSiteVisitArrivedSite(
                        session.bearerToken,
                        SiteVisitIdRequest(svId),
                    )
                    4 -> geoApi.markSiteVisitDropped(
                        session.bearerToken,
                        SiteVisitIdRequest(svId),
                    )
                    else -> {
                        transitioning = false
                        return@launch
                    }
                }
                if (!resp.success) {
                    throw Exception(resp.error ?: "Transition failed")
                }
                // Optimistically advance the local stepper; the
                // server's authoritative status will rebind on the
                // next loadEnrichedDetail pass (e.g. when the sheet
                // re-opens). For the in-session UX this is enough.
                updateStepper(stepIndex)
                bindStatusHeader(when (stepIndex) {
                    2 -> "picked_up"
                    3 -> "on_site"
                    4 -> "dropped"
                    else -> ""
                })
                Toast.makeText(
                    requireContext(),
                    "$label ✓",
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    e.message ?: "Couldn't advance to '$label'",
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                transitioning = false
            }
        }
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

        tvTitle?.text = placeName ?: "Site Visit"
        tvClientName?.text = formatPersonName(leadName ?: "Client")
        tvPhone?.text = leadPhone ?: "—"
        tvProject?.text = placeName ?: "—"
        tvPickupAddress?.text = placeAddress ?: "Address not available"
        tvVisitorName?.text = formatPersonName(leadName ?: "Client")

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

        // Stepper state mapping
        val stepIndex = mapStatusToStepIndex(rawStatus)
        updateStepper(stepIndex)

        // Bind status text and dot color
        bindStatusHeader(rawStatus)
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

    private fun updateStepper(activeIndex: Int) {
        currentStepIndex = activeIndex
        val context = requireContext()
        val blueColor = Color.parseColor("#004EEB")
        val grayColor = Color.parseColor("#98A2B3")

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
        if (onSiteReached) {
            btnBooking?.isEnabled = true
            btnBooking?.alpha = 1.0f
            btnNotInterested?.isEnabled = true
            btnNotInterested?.alpha = 1.0f
            btnPostponed?.isEnabled = true
            btnPostponed?.alpha = 1.0f

            // Wire outcome click actions
            btnBooking?.setOnClickListener { saveOutcome("converted_to_booking", "Converted as Booking") }
            btnNotInterested?.setOnClickListener { saveOutcome("not_interested", "Client Not Interested") }
            btnPostponed?.setOnClickListener { saveOutcome("postponed", "Its Been Postponed") }
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
        // Enriched Lead details
        val displayName = formatPersonName(
            visit.client?.clientName
                ?: visit.lead?.contactName
                ?: visit.clientPlace?.name
                ?: "Client"
        )
        tvClientName?.text = displayName
        tvVisitorName?.text = displayName

        val phone = visit.client?.mobileNumber ?: visit.lead?.mobileNumber
        if (!phone.isNullOrBlank()) {
            tvPhone?.text = phone
        }

        // Project / plot name
        val projectName = visit.clientPlace?.name ?: visit.proposedSiteVisit?.projectId ?: "Site Visit"
        tvProject?.text = projectName
        tvTitle?.text = projectName

        // Staff assigned (BDO)
        val staffName = visit.assignedStaff?.staffName ?: "AKASH.B"
        tvBdo?.text = formatPersonName(staffName)

        // Pickup / Site Address
        val addressStr = listOfNotNull(
            visit.clientPlace?.address?.takeIf { it.isNotBlank() },
            visit.clientPlace?.landmark?.takeIf { it.isNotBlank() },
            visit.clientPlace?.city?.takeIf { it.isNotBlank() },
            visit.clientPlace?.state?.takeIf { it.isNotBlank() },
            visit.clientPlace?.pincode?.takeIf { it.isNotBlank() },
        ).joinToString(", ").ifBlank { visit.clientPlace?.formattedAddress.orEmpty() }
        if (addressStr.isNotBlank()) {
            tvPickupAddress?.text = addressStr
        }

        // Expected attendees
        val count = visit.expectedAttendeeCount ?: 1
        tvAttendees?.text = "$count Expected"

        // Site Incharge resolution
        val proposed = visit.proposedSiteVisit
        if (proposed?.inchargeStaffId != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching {
                    val staffResp = api.getStaff(session.bearerToken, status = "active")
                    val matching = staffResp.staff.firstOrNull { it.id == proposed.inchargeStaffId }
                    if (matching != null) {
                        tvIncharge?.text = formatPersonName(matching.name ?: "EVANGELINE PRINCY.S")
                    } else {
                        tvIncharge?.text = "EVANGELINE PRINCY.S"
                    }
                }
            }
        } else {
            tvIncharge?.text = "EVANGELINE PRINCY.S"
        }

        // Visitors Card info
        tvVisitorName?.text = displayName
        val age = visit.attendees?.firstOrNull()?.age ?: "22"
        val diet = if (visit.attendees?.firstOrNull()?.isVeg == true) "Veg" else "Non-Veg"
        val relation = visit.attendees?.firstOrNull()?.relation ?: "Self"
        tvVisitorDetails?.text = "$relation • $age yrs • $diet"

        // Notes card info
        tvNotes?.text = visit.notes?.takeIf { it.isNotBlank() } ?: "Call summary is not available yet."

        // Sync stepper state from enriched fieldVisit status if newer
        val effStatus = visit.fieldVisit?.status ?: visit.status ?: ""
        if (effStatus.isNotEmpty()) {
            val stepIndex = mapStatusToStepIndex(effStatus)
            updateStepper(stepIndex)
            bindStatusHeader(effStatus)
        }
    }

    private fun saveOutcome(outcomeValue: String, label: String) {
        val targetVisitId = visitId ?: return

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
