package com.manjugroups.m_connect.ui.marketing

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil.load
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.CpVisitDetail
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.ui.home.CompleteCpVisitBottomSheet
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only detail view for a CP visit that has already been completed.
 *
 * Renders the same enriched visit shape the web's
 * `clientPlaceVisits.get()` query returns — lead, client, place, fieldVisit,
 * arrival proof (photo + OTP + GPS), and a timeline of created / assigned /
 * field-visit-started / OTP-verified / client-met / completed events.
 *
 * The fragment opens with the basic identity from the list (`TodayVisit`) so
 * users see something immediately, then upgrades to the rich detail once the
 * `GET /api/marketing/clientPlaceVisits/get` call returns.
 */
class CompletedVisitDetailFragment : Fragment() {

    private val geoApi = GeoTrackApi.create()
    private lateinit var session: SessionManager
    private var pendingEntryAnimation = true
    private var visitId: String? = null
    // Cached "this CP visit is the telecaller-fixed SV-via-CP variant"
    // flag, set true if the enriched detail load surfaces any of the
    // signals CompleteCpVisitBottomSheet's detectAndApplyLockedSvMode
    // uses. Lets the "Record Outcome" tap pre-pass the hint so the
    // sheet skips the Booking-tab flash before locking to SV mode.
    private var isSvFixedHint: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_completed_visit_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        view.findViewById<View>(R.id.btnCvdBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        bindContent(view)
        primeEntryAnimation(view)
        view.post { playEntryAnimation(view) }

        // Upgrade to the enriched detail (arrival proof, telecaller, timeline,
        // etc.) as soon as the network round-trip returns. The list payload
        // we already have is enough for the initial paint.
        val id = visitId
        if (!id.isNullOrBlank()) loadEnrichedDetail(view, id)
    }

    override fun onResume() {
        super.onResume()
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTopBarAppearance(
            Color.WHITE,
            darkStatusIcons = true,
        )
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(false)
    }

    override fun onPause() {
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(true)
        super.onPause()
    }

    override fun onDestroyView() {
        pendingEntryAnimation = true
        super.onDestroyView()
    }

    // ---------- Binding ----------

    private fun bindContent(root: View) {
        val args = requireArguments()

        // The visit's parent clientPlaceVisitId is what the backend's get(id)
        // expects. Fall back to the field visit id if the parent isn't set.
        visitId = args.getString(ARG_CLIENT_PLACE_VISIT_ID).trimOrNull()
            ?: args.getString(ARG_VISIT_ID).trimOrNull()

        val placeName = args.getString(ARG_PLACE_NAME).trimOrNull()
        val placeAddress = args.getString(ARG_PLACE_ADDRESS).trimOrNull()
        val leadName = args.getString(ARG_LEAD_NAME).trimOrNull()
        val leadPhone = args.getString(ARG_LEAD_PHONE).trimOrNull()
        val scheduledDate = args.getString(ARG_SCHEDULED_DATE).trimOrNull()
        val scheduledStartTime = args.getString(ARG_SCHEDULED_START_TIME).trimOrNull()
        val scheduledEndTime = args.getString(ARG_SCHEDULED_END_TIME).trimOrNull()
        val tripType = args.getString(ARG_TRIP_TYPE).trimOrNull()
        val placeLat = if (args.containsKey(ARG_PLACE_LAT)) args.getDouble(ARG_PLACE_LAT) else null
        val placeLng = if (args.containsKey(ARG_PLACE_LNG)) args.getDouble(ARG_PLACE_LNG) else null
        val outcome = args.getString(ARG_OUTCOME).trimOrNull()
        val clientMet = if (args.containsKey(ARG_CLIENT_MET)) args.getBoolean(ARG_CLIENT_MET) else null
        val clientMetAt = args.getLong(ARG_CLIENT_MET_AT, 0L).takeIf { it > 0 }
        val clientNoShowReason = args.getString(ARG_CLIENT_NO_SHOW_REASON).trimOrNull()
        val postponeReasons = args.getStringArrayList(ARG_POSTPONE_REASONS).orEmpty()

        // ---- Identity ----
        val displayClientName = formatPersonName(
            placeName ?: leadName ?: "Client"
        )
        root.findViewById<TextView>(R.id.tvCvdClientName).text = displayClientName
        root.findViewById<TextView>(R.id.tvCvdClientInitial).text =
            displayClientName.firstOrNull()?.uppercase() ?: "C"
        root.findViewById<TextView>(R.id.tvCvdClientPhone).text =
            leadPhone ?: "—"
        root.findViewById<TextView>(R.id.tvCvdAddress).text =
            placeAddress ?: "Address not mapped"

        // Initial status-card paint uses whatever outcome we got from
        // the list args. When the server-side enrichment lands (a
        // few hundred ms later) bindEnriched re-runs this with the
        // authoritative outcome + conversion linkage so a row that
        // was converted to SV/Booking flips off Pending immediately.
        applyStatusCard(
            root = root,
            outcome = outcome,
            clientMet = clientMet,
            clientMetAt = clientMetAt,
            convertedSiteVisitId = null,
            convertedBookingId = null,
        )

        // ---- Visit info grid ----
        root.findViewById<TextView>(R.id.tvCvdDate).text =
            formatDateOnly(scheduledDate) ?: scheduledDate ?: "—"
        root.findViewById<TextView>(R.id.tvCvdTime).text =
            formatTimeRange(scheduledStartTime, scheduledEndTime, scheduledDate) ?: "—"
        root.findViewById<TextView>(R.id.tvCvdTripType).text = when (tripType) {
            "client_place" -> "Client place"
            "internal" -> "Internal"
            null -> "Client visit"
            else -> tripType.replaceFirstChar { it.titlecase() }
        }
        root.findViewById<TextView>(R.id.tvCvdLocation).text =
            if (placeLat != null && placeLng != null) "Geo-mapped" else "Not mapped"

        // ---- Client met card ----
        val metIcon = root.findViewById<android.widget.ImageView>(R.id.ivCvdClientMetIcon)
        val metLabel = root.findViewById<TextView>(R.id.tvCvdClientMetLabel)
        val metNote = root.findViewById<TextView>(R.id.tvCvdClientMetNote)
        when (clientMet) {
            true -> {
                metIcon.setImageResource(R.drawable.ic_cpv_action_completed)
                metLabel.text = "Client met"
                metLabel.setTextColor(Color.parseColor("#1F7A3F"))
                metNote.visibility = View.GONE
            }
            false -> {
                metIcon.setImageResource(R.drawable.ic_cpv_action_cancelled)
                metLabel.text = "Client not seen"
                metLabel.setTextColor(Color.parseColor("#B42318"))
                if (clientNoShowReason != null) {
                    metNote.text = clientNoShowReason
                    metNote.visibility = View.VISIBLE
                } else {
                    metNote.visibility = View.GONE
                }
            }
            null -> {
                root.findViewById<View>(R.id.cvdClientMetCard).visibility = View.GONE
            }
        }

        // ---- Outcome card ----
        applyOutcomePill(
            root = root,
            outcome = outcome,
            convertedSiteVisitId = null,
            convertedBookingId = null,
            postponeReasons = postponeReasons,
        )
    }

    /**
     * Binds the small "Outcome" pill on the lower card. Same
     * conversion-linkage fallback as [applyStatusCard] so a row that
     * carries convertedSiteVisitId without an explicit outcome string
     * still reads "Converted to Site Visit" rather than "Not recorded".
     */
    private fun applyOutcomePill(
        root: View,
        outcome: String?,
        convertedSiteVisitId: String?,
        convertedBookingId: String?,
        postponeReasons: List<String>,
    ) {
        val effectiveOutcome = outcome?.takeIf { it.isNotBlank() }
            ?: convertedSiteVisitId?.takeIf { it.isNotBlank() }
                ?.let { "converted_to_site_visit" }
            ?: convertedBookingId?.takeIf { it.isNotBlank() }
                ?.let { "converted_to_booking" }
        val outcomePill = root.findViewById<TextView>(R.id.tvCvdOutcomeLabel)
        val (outcomeLabel, outcomeBg, outcomeFg) = outcomePresentation(effectiveOutcome)
        outcomePill.text = outcomeLabel
        outcomePill.setBackgroundResource(outcomeBg)
        outcomePill.setTextColor(Color.parseColor(outcomeFg))

        if (effectiveOutcome == "postponed" && postponeReasons.isNotEmpty()) {
            renderPostponeChips(root, postponeReasons)
            root.findViewById<View>(R.id.tvCvdPostponeLabel).visibility = View.VISIBLE
            root.findViewById<View>(R.id.cvdPostponeChipScroll).visibility = View.VISIBLE
        } else {
            root.findViewById<View>(R.id.tvCvdPostponeLabel).visibility = View.GONE
            root.findViewById<View>(R.id.cvdPostponeChipScroll).visibility = View.GONE
        }
    }

    private fun outcomePresentation(outcome: String?): Triple<String, Int, String> {
        return when (outcome?.lowercase(Locale.US)) {
            "converted_to_booking" -> Triple(
                "Converted to Booking",
                R.drawable.bg_completed_outcome_pill,
                "#0369A1",
            )
            "converted_to_site_visit" -> Triple(
                "Converted to Site Visit",
                R.drawable.bg_completed_outcome_pill,
                "#0369A1",
            )
            "postponed" -> Triple(
                "Postponed",
                R.drawable.bg_completed_chip_postpone,
                "#B54708",
            )
            "not_interested" -> Triple(
                "Not Interested",
                R.drawable.bg_cpv_status_cancelled,
                "#B42318",
            )
            "interested" -> Triple(
                "Interested",
                R.drawable.bg_home_trip_status_ready,
                "#169B2F",
            )
            "other" -> Triple(
                "Other",
                R.drawable.bg_completed_outcome_pill,
                "#0369A1",
            )
            null, "" -> Triple(
                "Not recorded",
                R.drawable.bg_completed_outcome_pill,
                "#475467",
            )
            else -> Triple(
                outcome.replaceFirstChar { it.titlecase() },
                R.drawable.bg_completed_outcome_pill,
                "#0369A1",
            )
        }
    }

    private fun renderPostponeChips(root: View, reasons: List<String>) {
        val row = root.findViewById<LinearLayout>(R.id.cvdPostponeChipRow)
        row.removeAllViews()
        val density = resources.displayMetrics.density
        reasons.forEachIndexed { index, reason ->
            val chip = TextView(row.context).apply {
                text = reason.replaceFirstChar { it.titlecase() }
                setBackgroundResource(R.drawable.bg_completed_chip_postpone)
                setTextColor(Color.parseColor("#B54708"))
                textSize = 11f
                includeFontPadding = false
                typeface = ResourcesCompat.getFont(row.context, R.font.inter_medium)
                setPadding(
                    (12 * density).toInt(),
                    (6 * density).toInt(),
                    (12 * density).toInt(),
                    (6 * density).toInt(),
                )
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                if (index > 0) lp.marginStart = (8 * density).toInt()
                layoutParams = lp
            }
            row.addView(chip)
        }
    }

    // ---------- Entry animation ----------

    private fun primeEntryAnimation(root: View) {
        val density = resources.displayMetrics.density
        listOf(
            R.id.btnCvdBack,
            R.id.tvCvdTitle,
        ).forEach { id ->
            root.findViewById<View>(id)?.apply {
                alpha = 0f
                translationY = -8f * density
            }
        }
        root.findViewById<View>(R.id.cvdScroll)?.apply {
            alpha = 0f
            translationY = 24f * density
        }
    }

    private fun playEntryAnimation(root: View) {
        if (!pendingEntryAnimation) return
        pendingEntryAnimation = false
        val emphasized = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
        val expoOut = android.view.animation.PathInterpolator(0.19f, 1f, 0.22f, 1f)

        root.findViewById<View>(R.id.btnCvdBack)?.animate()
            ?.alpha(1f)?.translationY(0f)
            ?.setStartDelay(40L)?.setDuration(320L)?.setInterpolator(emphasized)?.start()
        root.findViewById<View>(R.id.tvCvdTitle)?.animate()
            ?.alpha(1f)?.translationY(0f)
            ?.setStartDelay(80L)?.setDuration(360L)?.setInterpolator(emphasized)?.start()
        root.findViewById<View>(R.id.cvdScroll)?.animate()
            ?.alpha(1f)?.translationY(0f)
            ?.setStartDelay(180L)?.setDuration(460L)?.setInterpolator(expoOut)?.start()
    }

    // ---------- Enriched detail fetch + bind ----------

    private fun loadEnrichedDetail(root: View, id: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val response = runCatching {
                geoApi.getCpVisitDetail(session.bearerToken, id)
            }.getOrNull()
            if (view == null) return@launch
            val visit = response?.visit ?: return@launch
            bindEnriched(root, visit)
        }
    }

    /**
     * Renders the top status card. Treats a row as "outcome pending"
     * only when:
     *   1. The explicit `outcome` field is blank, AND
     *   2. Neither convertedSiteVisitId nor convertedBookingId is set
     *      (so we can be sure no conversion landed server-side).
     *
     * Conversion-linkage fallback covers the case where the server
     * silently dropped the outcome string field but actually moved
     * the visit to a completed state by creating the linked SV /
     * booking row — same defensive read the CP list card uses.
     */
    private fun applyStatusCard(
        root: View,
        outcome: String?,
        clientMet: Boolean?,
        clientMetAt: Long?,
        convertedSiteVisitId: String?,
        convertedBookingId: String?,
    ) {
        val effectiveOutcome = outcome?.takeIf { it.isNotBlank() }
            ?: convertedSiteVisitId?.takeIf { it.isNotBlank() }
                ?.let { "converted_to_site_visit" }
            ?: convertedBookingId?.takeIf { it.isNotBlank() }
                ?.let { "converted_to_booking" }
        val outcomePending = effectiveOutcome.isNullOrBlank()

        val statusSummary = when {
            outcomePending -> "Outcome pending"
            clientMet == true -> "Client met"
            clientMet == false -> "Client not seen"
            else -> "Visit completed"
        }
        root.findViewById<TextView>(R.id.tvCvdStatusSummary).text = statusSummary

        val statusSub = when {
            outcomePending ->
                "Trip was completed but no outcome was recorded. Tap below to finish."
            clientMetAt != null -> "Outcome recorded on ${formatEpochMillis(clientMetAt)}"
            else -> "Outcome captured for this client visit"
        }
        root.findViewById<TextView>(R.id.tvCvdStatusSubtitle).text = statusSub

        val statusPill = root.findViewById<TextView>(R.id.tvCvdStatusLabel)
        if (outcomePending) {
            statusPill.text = "Pending"
            statusPill.setTextColor(Color.parseColor("#B54708"))
            (statusPill.parent as? View)?.setBackgroundResource(R.drawable.bg_home_trip_status_progress)
        } else {
            statusPill.text = "Completed"
            statusPill.setTextColor(Color.parseColor("#169B2F"))
            (statusPill.parent as? View)?.setBackgroundResource(R.drawable.bg_home_trip_status_ready)
        }

        val recordBtn = root.findViewById<TextView>(R.id.btnCvdRecordOutcome)
        if (outcomePending) {
            recordBtn.visibility = View.VISIBLE
            recordBtn.setOnClickListener { openOutcomeSheet() }
        } else {
            recordBtn.visibility = View.GONE
            recordBtn.setOnClickListener(null)
        }
    }

    /**
     * Reopens [CompleteCpVisitBottomSheet] so the user can finish the
     * outcome step they skipped/lost. Uses the same args path the
     * trip-complete swipe used; the sheet's own detect helper figures
     * out SV-via-CP locking from the server-side row.
     */
    private fun openOutcomeSheet() {
        val cpId = visitId?.takeIf { it.isNotBlank() } ?: return
        CompleteCpVisitBottomSheet.newInstance(
            cpVisitId = cpId,
            cpClientMet = null,
            cpOutcome = null,
            isSvFixedHint = isSvFixedHint,
        ).show(parentFragmentManager, "CompleteCpVisitBottomSheet")
    }

    private fun bindEnriched(root: View, visit: CpVisitDetail) {
        // Capture SV-via-CP signals for the Record-Outcome reopen path.
        // Mirrors the detection logic in CompleteCpVisitBottomSheet.
        val proposed = visit.proposedSiteVisit
        val proposedMeaningful = proposed != null && (
            !proposed.projectId.isNullOrBlank() ||
                !proposed.scheduledDate.isNullOrBlank() ||
                !proposed.scheduledTime.isNullOrBlank() ||
                !proposed.inchargeStaffId.isNullOrBlank() ||
                !proposed.hodStaffId.isNullOrBlank() ||
                !proposed.bdoStaffId.isNullOrBlank() ||
                !proposed.avpStaffId.isNullOrBlank() ||
                !proposed.gmStaffId.isNullOrBlank() ||
                !proposed.seniorManagerStaffId.isNullOrBlank()
            )
        val leadSvFixed = visit.lead?.followUpStatus
            ?.lowercase(Locale.getDefault())
            ?.let { s -> s == "sv_fixed" || s.contains("sv_fixed") || s.contains("sv-fixed") }
            ?: false
        val hasSvFixParty = (visit.expectedAttendeeCount ?: 0) > 0 ||
            (visit.attendees?.isNotEmpty() == true) ||
            !visit.foodPreferences.isNullOrBlank() ||
            !visit.vehiclePreference.isNullOrBlank()
        isSvFixedHint = proposedMeaningful || leadSvFixed || hasSvFixParty

        // Re-paint the status card with authoritative server state.
        // Critical when the list-passed `outcome` arg was blank but the
        // row actually carries convertedSiteVisitId / convertedBookingId
        // — e.g. the Farima case where the CP was converted to SV and
        // the row is fully complete, but the explicit outcome column
        // never got the converted_to_site_visit write (legacy / partial
        // patch). The conversion-linkage fallback flips Pending off.
        applyStatusCard(
            root = root,
            outcome = visit.outcome,
            clientMet = visit.clientMet,
            clientMetAt = visit.clientMetAt,
            convertedSiteVisitId = visit.convertedSiteVisitId,
            convertedBookingId = visit.convertedBookingId,
        )
        // Same fallback for the small Outcome pill on the lower card —
        // shows "Converted to Site Visit" / "Converted to Booking"
        // when the linkage is present, instead of "Not recorded".
        applyOutcomePill(
            root = root,
            outcome = visit.outcome,
            convertedSiteVisitId = visit.convertedSiteVisitId,
            convertedBookingId = visit.convertedBookingId,
            postponeReasons = visit.postponeReasons ?: emptyList(),
        )

        // Upgrade client info — prefer the fully-resolved name + phone from
        // the joined lead/client docs over the placeholders captured at list time.
        val displayName = formatPersonName(
            visit.client?.clientName
                ?: visit.lead?.contactName
                ?: visit.clientPlace?.name
                ?: "Client"
        )
        root.findViewById<TextView>(R.id.tvCvdClientName).text = displayName
        root.findViewById<TextView>(R.id.tvCvdClientInitial).text =
            displayName.firstOrNull()?.uppercase() ?: "C"
        val phone = visit.client?.mobileNumber ?: visit.lead?.mobileNumber
        if (!phone.isNullOrBlank()) {
            val phoneView = root.findViewById<TextView>(R.id.tvCvdClientPhone)
            phoneView.text = phone
            phoneView.setOnClickListener { dialPhone(phone) }
            // "Open lead in dialer →" — mirrors the web's quick-action link.
            val dialerLink = root.findViewById<TextView>(R.id.tvCvdOpenInDialer)
            dialerLink.visibility = View.VISIBLE
            dialerLink.setOnClickListener { dialPhone(phone) }
        }

        // Lead extras — city + preferred area land under the contact row when
        // the enriched lead doc has them.
        val city = visit.client?.city ?: visit.lead?.city
        val preferredArea = visit.lead?.preferredArea
        if (!city.isNullOrBlank() || !preferredArea.isNullOrBlank()) {
            root.findViewById<View>(R.id.cvdLeadExtrasRow).visibility = View.VISIBLE
            root.findViewById<TextView>(R.id.tvCvdLeadCity).text =
                city?.takeIf { it.isNotBlank() } ?: "—"
            root.findViewById<TextView>(R.id.tvCvdLeadPreferredArea).text =
                preferredArea?.takeIf { it.isNotBlank() } ?: "—"
        }

        val placeAddress = listOfNotNull(
            visit.clientPlace?.address?.takeIf { it.isNotBlank() },
            visit.clientPlace?.landmark?.takeIf { it.isNotBlank() },
            visit.clientPlace?.city?.takeIf { it.isNotBlank() },
            visit.clientPlace?.state?.takeIf { it.isNotBlank() },
            visit.clientPlace?.pincode?.takeIf { it.isNotBlank() },
        ).joinToString(", ").ifBlank { visit.clientPlace?.formattedAddress.orEmpty() }
        if (placeAddress.isNotBlank()) {
            root.findViewById<TextView>(R.id.tvCvdAddress).text = placeAddress
        }

        // Origin (telecaller / walk-in / campaign / referral) — top-level
        // visit field, displayed in the Visit grid card as a separate row.
        visit.origin?.takeIf { it.isNotBlank() }?.let { rawOrigin ->
            root.findViewById<View>(R.id.cvdOriginRow).visibility = View.VISIBLE
            root.findViewById<TextView>(R.id.tvCvdOrigin).text = formatOrigin(rawOrigin)
        }

        // ---- Arrival proof card ----
        val proof = visit.arrivalProof
        // Surface the salesperson's free-text context alongside the photo.
        // Order: clientNoShowReason (set explicitly on the "didn't see client"
        // path) → visit notes (set by setOutcome). Either one means the field
        // staff left something the office should read.
        val arrivalDescription = visit.clientNoShowReason
            ?.takeIf { it.isNotBlank() && !it.equals("Client not seen", ignoreCase = true) }
            ?: visit.notes?.takeIf { it.isNotBlank() && !it.equals("Client not seen", ignoreCase = true) }
        val hasDescription = !arrivalDescription.isNullOrBlank()
        if (proof != null && (proof.photoUrl != null || proof.otpVerifiedAt != null || proof.gpsLat != null || hasDescription)) {
            root.findViewById<View>(R.id.cvdArrivalProofCard).visibility = View.VISIBLE
            val photo = root.findViewById<ImageView>(R.id.ivCvdArrivalPhoto)
            if (!proof.photoUrl.isNullOrBlank()) {
                photo.load(proof.photoUrl) {
                    crossfade(true)
                    placeholder(android.R.color.darker_gray)
                    error(android.R.color.darker_gray)
                }
            }
            val descriptionView = root.findViewById<TextView>(R.id.tvCvdArrivalDescription)
            if (hasDescription) {
                descriptionView.text = arrivalDescription
                descriptionView.visibility = View.VISIBLE
            } else {
                descriptionView.visibility = View.GONE
            }
            root.findViewById<TextView>(R.id.tvCvdOtpVerifiedAt).text =
                proof.otpVerifiedAt?.let { formatEpochMillis(it) } ?: "—"
            root.findViewById<TextView>(R.id.tvCvdOtpRequestedAt).text =
                proof.otpRequestedAt?.let { formatEpochMillis(it) } ?: "—"
            val coords = if (proof.gpsLat != null && proof.gpsLng != null) {
                String.format(Locale.US, "%.5f, %.5f", proof.gpsLat, proof.gpsLng)
            } else "—"
            root.findViewById<TextView>(R.id.tvCvdGpsCoords).text = coords
            root.findViewById<TextView>(R.id.tvCvdGpsDistance).text =
                proof.distanceFromPlaceMeters?.let { "${it.toInt()} m" } ?: "—"
        }

        // ---- People card ----
        val fieldStaffName = visit.assignedStaff?.staffName
        val telecallerName = visit.telecaller?.staffName
        if (!fieldStaffName.isNullOrBlank() || !telecallerName.isNullOrBlank()) {
            root.findViewById<View>(R.id.cvdPeopleCard).visibility = View.VISIBLE
            if (!fieldStaffName.isNullOrBlank()) {
                root.findViewById<View>(R.id.cvdFieldStaffRow).visibility = View.VISIBLE
                root.findViewById<TextView>(R.id.tvCvdFieldStaffName).text =
                    formatPersonName(fieldStaffName)
            }
            if (!telecallerName.isNullOrBlank()) {
                root.findViewById<View>(R.id.cvdTelecallerRow).visibility = View.VISIBLE
                root.findViewById<TextView>(R.id.tvCvdTelecallerName).text =
                    formatPersonName(telecallerName)
            }
        }

        // ---- Timeline ----
        renderTimeline(root, visit)
    }

    private fun renderTimeline(root: View, visit: CpVisitDetail) {
        // Timeline = mirror of the web's "Created → Assigned → Field visit
        // started → Arrival OTP verified → Client met → Completed" order.
        data class Event(val label: String, val millis: Long?)
        val events = listOf(
            Event("Created", visit.createdAt),
            Event("Assigned", visit.assignedAt),
            Event("Field visit started", visit.fieldVisit?.startedAt),
            Event("Arrival OTP verified", visit.arrivalProof?.otpVerifiedAt),
            Event("Client met", visit.clientMetAt),
            Event("Completed", visit.completedAt),
        ).filter { it.millis != null && it.millis > 0L }

        val rows = root.findViewById<LinearLayout>(R.id.cvdTimelineRows)
        val empty = root.findViewById<TextView>(R.id.tvCvdTimelineEmpty)
        rows.removeAllViews()
        if (events.isEmpty()) {
            empty.visibility = View.VISIBLE
            return
        }
        empty.visibility = View.GONE
        val density = resources.displayMetrics.density
        events.forEachIndexed { index, event ->
            val row = LinearLayout(rows.context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    if (index > 0) topMargin = (8 * density).toInt()
                }
            }
            val label = TextView(rows.context).apply {
                text = "${event.label}:"
                setTextColor(Color.parseColor("#667085"))
                textSize = 12f
                includeFontPadding = false
                typeface = ResourcesCompat.getFont(rows.context, R.font.inter_regular)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            val value = TextView(rows.context).apply {
                text = formatEpochMillis(event.millis!!)
                setTextColor(Color.parseColor("#101828"))
                textSize = 12f
                includeFontPadding = false
                typeface = ResourcesCompat.getFont(rows.context, R.font.inter_semibold)
            }
            row.addView(label)
            row.addView(value)
            rows.addView(row)
        }
    }

    private fun dialPhone(phone: String) {
        val digits = phone.filter { it.isDigit() || it == '+' }
        if (digits.isBlank()) return
        val intent = android.content.Intent(android.content.Intent.ACTION_DIAL).apply {
            data = android.net.Uri.parse("tel:$digits")
        }
        runCatching { startActivity(intent) }
    }

    private fun formatOrigin(raw: String): String =
        raw.replace('_', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.titlecase() } }

    // ---------- Formatting helpers ----------

    private fun String?.trimOrNull(): String? = this?.trim()?.takeIf { it.isNotBlank() }

    private fun formatPersonName(rawName: String): String =
        rawName.lowercase(Locale.getDefault()).split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.titlecase() } }
            .ifBlank { "Client" }

    private fun formatDateOnly(scheduledDate: String?): String? {
        if (scheduledDate.isNullOrBlank()) return null
        val parsed = parseIso(scheduledDate) ?: return null
        return SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(parsed)
    }

    /**
     * Returns "09:30 AM" or "09:30 AM – 11:00 AM" depending on what's
     * available. Falls back to extracting the time portion from the
     * scheduledDate ISO string if the explicit start/end fields are blank.
     */
    private fun formatTimeRange(
        startIso: String?,
        endIso: String?,
        scheduledDate: String?,
    ): String? {
        val start = parseIso(startIso) ?: parseIso(scheduledDate)?.takeIf {
            scheduledDate?.contains('T') == true
        }
        val end = parseIso(endIso)
        val timeFmt = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val startLabel = start?.let { timeFmt.format(it) }
        val endLabel = end?.let { timeFmt.format(it) }
        return when {
            startLabel != null && endLabel != null -> "$startLabel – $endLabel"
            startLabel != null -> startLabel
            else -> null
        }
    }

    private fun parseIso(iso: String?): Date? {
        if (iso.isNullOrBlank()) return null
        val trimmed = iso.substringBefore(".").substringBefore("Z")
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd",
        )
        for (p in patterns) {
            runCatching {
                return SimpleDateFormat(p, Locale.US).parse(trimmed)
            }
        }
        return null
    }

    private fun formatEpochMillis(millis: Long): String =
        SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(millis))

    companion object {
        private const val ARG_VISIT_ID = "arg_visit_id"
        private const val ARG_CLIENT_PLACE_VISIT_ID = "arg_client_place_visit_id"
        private const val ARG_PLACE_NAME = "arg_place_name"
        private const val ARG_PLACE_ADDRESS = "arg_place_address"
        private const val ARG_PLACE_LAT = "arg_place_lat"
        private const val ARG_PLACE_LNG = "arg_place_lng"
        private const val ARG_LEAD_NAME = "arg_lead_name"
        private const val ARG_LEAD_PHONE = "arg_lead_phone"
        private const val ARG_SCHEDULED_DATE = "arg_scheduled_date"
        private const val ARG_SCHEDULED_START_TIME = "arg_scheduled_start_time"
        private const val ARG_SCHEDULED_END_TIME = "arg_scheduled_end_time"
        private const val ARG_TRIP_TYPE = "arg_trip_type"
        private const val ARG_OUTCOME = "arg_outcome"
        private const val ARG_CLIENT_MET = "arg_client_met"
        private const val ARG_CLIENT_MET_AT = "arg_client_met_at"
        private const val ARG_CLIENT_NO_SHOW_REASON = "arg_client_no_show_reason"
        private const val ARG_POSTPONE_REASONS = "arg_postpone_reasons"

        fun forVisit(visit: com.manjugroups.m_connect.network.TodayVisit): CompletedVisitDetailFragment {
            return CompletedVisitDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_VISIT_ID, visit.id)
                    // clientPlaceVisitId is what the get(id) Convex query
                    // accepts. The visit.id is a fieldVisit id; resolve helper
                    // in the backend handles either, but passing the explicit
                    // CP visit id is the cleaner path when we have it.
                    putString(ARG_CLIENT_PLACE_VISIT_ID, visit.clientPlaceVisitId)
                    putString(ARG_PLACE_NAME, visit.placeName)
                    putString(ARG_PLACE_ADDRESS, visit.placeAddress)
                    visit.placeLat?.let { putDouble(ARG_PLACE_LAT, it) }
                    visit.placeLng?.let { putDouble(ARG_PLACE_LNG, it) }
                    putString(ARG_LEAD_NAME, visit.leadName)
                    putString(ARG_LEAD_PHONE, visit.leadPhone)
                    putString(ARG_SCHEDULED_DATE, visit.scheduledDate)
                    putString(ARG_SCHEDULED_START_TIME, visit.scheduledStartTime)
                    putString(ARG_SCHEDULED_END_TIME, visit.scheduledEndTime)
                    putString(ARG_TRIP_TYPE, visit.tripType)
                    putString(ARG_OUTCOME, visit.cpVisit?.outcome)
                    visit.cpVisit?.clientMet?.let { putBoolean(ARG_CLIENT_MET, it) }
                    visit.cpVisit?.clientMetAt?.let { putLong(ARG_CLIENT_MET_AT, it) }
                    putString(ARG_CLIENT_NO_SHOW_REASON, visit.cpVisit?.clientNoShowReason)
                    visit.cpVisit?.postponeReasons?.let {
                        putStringArrayList(ARG_POSTPONE_REASONS, ArrayList(it))
                    }
                }
            }
        }
    }
}
