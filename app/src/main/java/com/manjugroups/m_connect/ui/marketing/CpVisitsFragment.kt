package com.manjugroups.m_connect.ui.marketing

import android.app.Dialog
import com.manjugroups.m_connect.ui.common.dismissRefresh
import com.manjugroups.m_connect.ui.common.setupPullToRefresh
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.CpVisitState
import com.manjugroups.m_connect.network.CreateCpVisitRequest
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.TodayVisit
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import com.manjugroups.m_connect.ui.home.CompleteCpVisitBottomSheet
import com.manjugroups.m_connect.ui.home.TripNavigationFragment
import com.manjugroups.m_connect.ui.hr.AttendanceFlowViewModel
import com.manjugroups.m_connect.ui.common.navigateUp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class CpVisitsFragment : Fragment() {
    private val geoApi = GeoTrackApi.create()
    // Shared with the Attendance dashboard so we get the same clock-in state without
    // adding a new endpoint or duplicating the attendance load.
    private val attendanceVm: AttendanceFlowViewModel by activityViewModels()
    private lateinit var session: SessionManager
    private var rootView: View? = null

    private enum class Filter { ALL, SCHEDULED, POSTPONED, IN_PROGRESS, COMPLETED, CANCELLED }

    private var allVisits: List<TodayVisit> = emptyList()
    private var currentFilter: Filter = Filter.SCHEDULED
    private var searchQuery: String = ""
    private var pendingEntryAnimation = true
    // True once the user has punched in at least once today — sticky for the
    // rest of the day even after subsequent clock-outs. Mid-day clock-outs
    // (the user steps away, locks the punch-out time at midnight) must NOT
    // re-gate the CP cards to "Need to Clock In", so we read this field
    // from AttendanceFlowState rather than the right-now `isClockedIn` one.
    private var isClockedIn: Boolean = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_cp_visits, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        session = SessionManager(requireContext())
        rootView = view

        view.findViewById<View>(R.id.btnCpVisitsBack).setOnClickListener {
            navigateUp()
        }
        view.findViewById<View>(R.id.btnCreateCpVisit).setOnClickListener { showCreateDialog() }

        setupSearch(view)
        setupFilterPills(view)
        observeAttendanceState()

        // Pull-to-refresh: re-runs the list load. The spinner is dismissed
        // inside loadVisits when the response (or error) lands.
        view.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(
            R.id.cpvRefresh
        ).setupPullToRefresh { loadVisits() }

        loadVisits()
        attendanceVm.loadTodayAttendance(session.bearerToken)

        primeEntryAnimation(view)
        view.post { playEntryAnimation(view) }
    }

    override fun onResume() {
        super.onResume()
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTopBarAppearance(Color.WHITE, true)
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(false)
        // Refresh clock-in state in case the user clocked in/out from another tab.
        attendanceVm.loadTodayAttendance(session.bearerToken)
    }

    override fun onPause() {
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(true)
        super.onPause()
    }

    override fun onDestroyView() {
        SkeletonUtils.stopAll()
        pendingEntryAnimation = true
        rootView = null
        super.onDestroyView()
    }

    // ---------- Clock-in observation ----------

    private fun observeAttendanceState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                attendanceVm.uiState.collect { state ->
                    // Use hasClockedInToday, not isClockedIn. The latter
                    // flips to false the moment a user taps Clock Out, but
                    // the one-time-Clock-In rule means trips/CP visits
                    // should stay unlocked until the day ends. After the
                    // midnight finalize the next morning's load resets
                    // both flags via loadTodayAttendance().
                    val newValue = state.hasClockedInToday
                    if (newValue != isClockedIn) {
                        isClockedIn = newValue
                        renderList()
                    }
                }
            }
        }
    }

    // ---------- Search ----------

    private fun setupSearch(root: View) {
        val search = root.findViewById<EditText>(R.id.etCpvSearch)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim().orEmpty()
                renderList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    // ---------- Filter pills ----------

    private fun pillsAndFilters(root: View): List<Pair<TextView, Filter>> = listOf(
        root.findViewById<TextView>(R.id.pillAll) to Filter.ALL,
        root.findViewById<TextView>(R.id.pillScheduled) to Filter.SCHEDULED,
        root.findViewById<TextView>(R.id.pillPostponed) to Filter.POSTPONED,
        root.findViewById<TextView>(R.id.pillInProgress) to Filter.IN_PROGRESS,
        root.findViewById<TextView>(R.id.pillCompleted) to Filter.COMPLETED,
        root.findViewById<TextView>(R.id.pillCancelled) to Filter.CANCELLED,
    )

    private fun setupFilterPills(root: View) {
        pillsAndFilters(root).forEach { (pill, filter) ->
            pill.setOnClickListener {
                if (currentFilter != filter) {
                    currentFilter = filter
                    applyPillStyles(root)
                    renderList()
                }
            }
        }
        applyPillStyles(root)
    }

    private fun applyPillStyles(root: View) {
        pillsAndFilters(root).forEach { (pill, filter) ->
            val isActive = filter == currentFilter
            pill.background = ContextCompat.getDrawable(
                pill.context,
                if (isActive) R.drawable.bg_cpv_filter_pill_active
                else R.drawable.bg_cpv_filter_pill_inactive
            )
            pill.setTextColor(Color.parseColor(if (isActive) "#FFFFFF" else "#475467"))
            pill.typeface = ResourcesCompat.getFont(
                pill.context,
                if (isActive) R.font.inter_semibold else R.font.inter_medium
            )
        }
    }

    // Status classification mirrors HomeFragment.createVisitItem so the workflow logic
    // stays in lockstep with what the backend returns.
    private fun isPostponed(visit: TodayVisit): Boolean =
        !visit.cpVisit?.postponeReasons.isNullOrEmpty() ||
            visit.cpVisit?.outcome.equals("postponed", ignoreCase = true)

    private fun isInProgress(status: String): Boolean = status in setOf(
        "in-progress", "in_progress", "ongoing", "started", "active", "arrived"
    )

    private fun isCompleted(status: String): Boolean = status in setOf(
        "completed", "complete", "done", "closed"
    )

    private fun isCancelled(status: String): Boolean = status in setOf(
        "cancelled", "canceled"
    )

    private fun matchesFilter(visit: TodayVisit, filter: Filter): Boolean {
        val status = visit.status.lowercase(Locale.US)
        return when (filter) {
            Filter.ALL -> true
            Filter.POSTPONED -> isPostponed(visit) && !isCancelled(status) && !isCompleted(status)
            Filter.IN_PROGRESS -> isInProgress(status) && !isCancelled(status) && !isCompleted(status)
            Filter.COMPLETED -> isCompleted(status)
            Filter.CANCELLED -> isCancelled(status)
            Filter.SCHEDULED -> !isInProgress(status) && !isCompleted(status) &&
                !isCancelled(status) && !isPostponed(visit)
        }
    }

    // ---------- Data loading + render ----------

    private fun loadVisits() {
        val root = rootView ?: return
        val skeletonContainer = root.findViewById<View>(R.id.skeletonContainer)
        val empty = root.findViewById<View>(R.id.cpvEmptyState)
        val list = root.findViewById<LinearLayout>(R.id.cpVisitsList)
        SkeletonUtils.startSkeletonPulse(skeletonContainer)
        empty.visibility = View.GONE
        list.removeAllViews()

        val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -30)
        val from = ymd.format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, 60)
        val to = ymd.format(cal.time)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Switched from /api/sitevisits/my (legacy fieldVisits) to
                // /api/marketing/clientPlaceVisits/my so each row carries
                // the proposedSiteVisit / lead.followUpStatus / party
                // data we need to label the category (SV confirmation CP vs
                // Direct CP) on each card. The mapper below converts to
                // the existing TodayVisit shape so downstream filter /
                // sort / render code is unchanged.
                val resp = geoApi.getMyMarketingCpVisits(
                    session.bearerToken,
                    fromDate = null,
                    toDate = null,
                )
                SkeletonUtils.stopSkeletonPulse(skeletonContainer)
                if (!resp.success) {
                    showLoadError(resp.error ?: "Failed to load CP visits")
                    Toast.makeText(
                        requireContext(),
                        "CP fetch failed: ${resp.error ?: "unknown"}",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                // Sort by creationTime descending so the most recently
                // ASSIGNED / created CP lands at the top — which is what
                // "newest" means to the field staff opening the app. The
                // previous sortedByDescending { scheduledDate } ordered
                // by FUTURE-most planned visit instead, pushing brand-
                // new assignments below older-but-later-scheduled ones.
                // Falls back to scheduledDate when creationTime is
                // missing (legacy rows from before the Convex
                // auto-populated _creationTime got threaded through to
                // the mobile envelope).
                allVisits = resp.visits
                    .mapNotNull { it.toCpListVisitOrNull() }
                    .sortedWith(
                        compareByDescending<TodayVisit> { it.creationTime ?: 0.0 }
                            .thenByDescending { it.scheduledDate }
                    )
                // Diagnostic: surface server-reported count alongside
                // the client-mapped count so the user can tell "0 from
                // server" apart from "server returned N but mapper
                // dropped them all".
                if (resp.visits.isEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        "CP /my: server returned 0 visits for your account",
                        Toast.LENGTH_LONG,
                    ).show()
                } else if (allVisits.isEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        "CP /my: server sent ${resp.visits.size} but mapper dropped all",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                renderList()
            } catch (e: Exception) {
                SkeletonUtils.stopSkeletonPulse(skeletonContainer)
                showLoadError("Network error: ${e.message ?: "unknown"}")
                Toast.makeText(
                    requireContext(),
                    "CP network error: ${e.message ?: "unknown"}",
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                root.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(
                    R.id.cpvRefresh
                )?.dismissRefresh()
            }
        }
    }

    /**
     * Map a marketing CpVisitDetail onto the TodayVisit shape the rest
     * of this fragment already knows how to render. Mirrors
     * HomeViewModel.toTodayVisitOrNull so the two surfaces show the
     * same category badge for the same row. Returns null when the row
     * is too sparse to produce a usable card (no id or scheduled date).
     */
    private fun com.manjugroups.m_connect.network.CpVisitDetail.toCpListVisitOrNull(): TodayVisit? {
        val cpId = this.id ?: return null
        val scheduled = this.scheduledDate ?: return null
        val effectiveStatus = this.fieldVisit?.status?.takeIf { it.isNotBlank() }
            ?: this.status?.takeIf { it.isNotBlank() }
            ?: "scheduled"
        val proposedHasFields = this.proposedSiteVisit?.let { p ->
            !p.projectId.isNullOrBlank() ||
                !p.scheduledDate.isNullOrBlank() ||
                !p.scheduledTime.isNullOrBlank() ||
                !p.inchargeStaffId.isNullOrBlank() ||
                !p.hodStaffId.isNullOrBlank() ||
                !p.bdoStaffId.isNullOrBlank() ||
                !p.avpStaffId.isNullOrBlank() ||
                !p.gmStaffId.isNullOrBlank() ||
                !p.seniorManagerStaffId.isNullOrBlank()
        } ?: false
        val leadFlaggedSvFixed = this.lead?.followUpStatus
            ?.lowercase(java.util.Locale.getDefault())
            ?.let { s -> s == "sv_fixed" || s.contains("sv_fixed") || s.contains("sv-fixed") }
            ?: false
        val hasSvFixParty = (this.expectedAttendeeCount ?: 0) > 0 ||
            (this.attendees?.isNotEmpty() == true) ||
            !this.foodPreferences.isNullOrBlank() ||
            !this.vehiclePreference.isNullOrBlank()
        val category = if (proposedHasFields || leadFlaggedSvFixed || hasSvFixParty) {
            "sv_cum_cp"
        } else {
            "direct_cp"
        }
        // Prefer the canonical client name (client.clientName from
        // manualProfile) over the dialer-typed lead.contactName so the
        // header reads "Abhi" — matching the web Client profile card —
        // rather than the lower-case typed string "abi".
        val canonicalClient = this.client?.clientName?.takeIf { it.isNotBlank() }
        val typedContact = this.lead?.contactName?.takeIf { it.isNotBlank() }
        val placeLabel = this.clientPlace?.name?.takeIf { it.isNotBlank() }
        val displayName = canonicalClient ?: typedContact ?: placeLabel ?: "CP visit"
        // Carry the CP outcome onto the mapped TodayVisit so the card
        // renderer can detect a "completed but no decision yet" state
        // for SV-via-CP visits (telecaller-fixed SV path).
        //
        // Defensive: when the row already carries a convertedSiteVisitId
        // or convertedBookingId, the conversion happened — even if the
        // outcome string field is somehow blank (legacy data, partial
        // patch, or a future mutation that forgets the explicit outcome
        // write). Derive a synthetic outcome from those linkages so the
        // Pending UI doesn't lure the user into a re-convert that would
        // either no-op (idempotent path) or worse, double-action.
        val effectiveOutcome = this.outcome?.takeIf { it.isNotBlank() }
            ?: this.convertedSiteVisitId?.takeIf { it.isNotBlank() }
                ?.let { "converted_to_site_visit" }
            ?: this.convertedBookingId?.takeIf { it.isNotBlank() }
                ?.let { "converted_to_booking" }
        val cpState = CpVisitState(
            clientMet = this.clientMet,
            clientMetAt = this.clientMetAt,
            clientNoShowReason = this.clientNoShowReason,
            outcome = effectiveOutcome,
            postponeReasons = this.postponeReasons,
        )
        return TodayVisit(
            id = cpId,
            clientPlaceId = this.clientPlaceId ?: cpId,
            scheduledDate = scheduled,
            status = effectiveStatus,
            visitCategory = category,
            placeName = displayName,
            placeAddress = this.clientPlace?.address
                ?: this.clientPlace?.formattedAddress,
            placeLat = this.clientPlace?.lat,
            placeLng = this.clientPlace?.lng,
            tripType = "client_place",
            clientPlaceVisitId = cpId,
            leadName = canonicalClient ?: typedContact,
            leadPhone = this.lead?.mobileNumber ?: this.client?.mobileNumber,
            scheduledStartTime = this.scheduledTime,
            cpVisit = cpState,
            // Thread the CP's `createdAt` ms timestamp into the
            // envelope's `creationTime` slot so the CP list's
            // newest-first sort (in renderList / loadCpVisits) has
            // something to order by. Without this the mapper dropped
            // the field, every row landed with creationTime=null, and
            // the sort silently fell back to scheduledDate — which
            // is what made the user see future-most-scheduled CPs at
            // the top instead of most-recently-assigned ones.
            // Convex auto-populates createdAt to the same value as
            // _creationTime at insert time, so this stays consistent
            // with the SV list's _creationTime-based sort.
            creationTime = this.createdAt?.toDouble(),
        )
    }

    private fun renderList() {
        val root = rootView ?: return
        val list = root.findViewById<LinearLayout>(R.id.cpVisitsList)
        val empty = root.findViewById<LinearLayout>(R.id.cpvEmptyState)
        val emptyTitle = root.findViewById<TextView>(R.id.tvCpvEmptyTitle)
        val emptySubtitle = root.findViewById<TextView>(R.id.tvCpvEmptySubtitle)
        list.removeAllViews()

        val needle = searchQuery.lowercase(Locale.US)
        val visible = allVisits
            .filter { matchesFilter(it, currentFilter) }
            .filter { v ->
                if (needle.isBlank()) return@filter true
                listOf(v.placeName, v.leadName, v.placeAddress)
                    .any { it?.lowercase(Locale.US)?.contains(needle) == true }
            }

        if (visible.isEmpty()) {
            list.visibility = View.GONE
            empty.visibility = View.VISIBLE
            emptyTitle.text = when (currentFilter) {
                Filter.SCHEDULED -> "No Cp Visits Yet"
                Filter.POSTPONED -> "Nothing Postponed"
                Filter.IN_PROGRESS -> "No Visits In Progress"
                Filter.COMPLETED -> "No Completed Visits"
                Filter.CANCELLED -> "No Cancelled Visits"
                Filter.ALL -> if (searchQuery.isBlank()) "No Cp Visits Yet" else "No Matches Found"
            }
            emptySubtitle.text = if (searchQuery.isNotBlank()) {
                "Try a different search term or switch filters to see other client place visits."
            } else {
                "Stay organized by creating or joining teams. Groups help you manage tasks, track progress, and collaborate with your team in one place."
            }
        } else {
            empty.visibility = View.GONE
            list.visibility = View.VISIBLE
            visible.forEach { list.addView(createRow(it, list)) }
        }
    }

    private fun showLoadError(message: String) {
        val root = rootView ?: return
        val list = root.findViewById<LinearLayout>(R.id.cpVisitsList)
        val empty = root.findViewById<LinearLayout>(R.id.cpvEmptyState)
        val emptyTitle = root.findViewById<TextView>(R.id.tvCpvEmptyTitle)
        val emptySubtitle = root.findViewById<TextView>(R.id.tvCpvEmptySubtitle)
        list.visibility = View.GONE
        empty.visibility = View.VISIBLE
        emptyTitle.text = "Couldn’t Load"
        emptySubtitle.text = message
    }

    // ---------- Row rendering (mirrors HomeFragment.createVisitItem workflow) ----------

    private fun createRow(visit: TodayVisit, parent: ViewGroup): View {
        val itemView = layoutInflater.inflate(R.layout.item_home_today_visit, parent, false)
        val name = itemView.findViewById<TextView>(R.id.tvVisitItemStaffName)
        val role = itemView.findViewById<TextView>(R.id.tvVisitItemStaffRole)
        val avatar = itemView.findViewById<TextView>(R.id.tvVisitItemAvatar)
        val title = itemView.findViewById<TextView>(R.id.tvVisitItemTitle)
        val timeLabel = itemView.findViewById<TextView>(R.id.tvVisitItemTimeLabel)
        val time = itemView.findViewById<TextView>(R.id.tvVisitItemTime)
        val distance = itemView.findViewById<TextView>(R.id.tvVisitItemDistance)
        val eta = itemView.findViewById<TextView>(R.id.tvVisitItemEta)
        val statusPill = itemView.findViewById<LinearLayout>(R.id.visitItemStatusPill)
        val statusDot = itemView.findViewById<View>(R.id.visitItemStatusDot)
        val statusText = itemView.findViewById<TextView>(R.id.tvVisitItemStatus)
        val actionBtn = itemView.findViewById<LinearLayout>(R.id.btnVisitItemAction)
        val actionLabel = itemView.findViewById<TextView>(R.id.tvVisitItemActionLabel)
        val actionIcon = itemView.findViewById<ImageView>(R.id.ivVisitItemActionIcon)
        val lead = itemView.findViewById<TextView>(R.id.tvVisitItemLead)
        // Category badge now lives in the body's Type cell (tvVisitItemTitle),
        // so the standalone tvVisitItemLead row underneath the grid is hidden.
        // Same de-dupe rule HomeFragment follows on Today's Trip.
        val categoryLabel = when (visit.visitCategory) {
            "sv_cum_cp" -> "SV confirmation CP"
            "direct_cp" -> "Direct CP"
            "site_visit" -> "Site Visit"
            else -> if (visit.clientPlaceVisitId != null) "CP visit" else "Visit"
        }
        lead.visibility = View.GONE

        // Identity header — CP visits identify the CLIENT, not the staff member.
        // Use placeName / leadName as primary, placeAddress as the supporting line.
        val clientName = formatDisplayName(visit.placeName ?: visit.leadName ?: "Client")
        val supportingLine = visit.placeAddress?.takeIf { it.isNotBlank() }
            ?: visit.leadPhone?.takeIf { it.isNotBlank() }
            ?: visit.placeType?.takeIf { it.isNotBlank() }
            ?: "Client Place"
        name.text = clientName
        role.text = supportingLine
        role.visibility = View.VISIBLE
        // Keep address readable when it spans two lines without breaking layout.
        role.maxLines = 2
        role.ellipsize = android.text.TextUtils.TruncateAt.END
        avatar.text = clientName.firstOrNull()?.uppercase() ?: "C"

        // Body — Type / Date-Time / Distance / ETA. The client name is
        // already in the header above, so the body's left cell carries the
        // visit Type ("Direct CP" / "SV confirmation CP" / …) instead of
        // repeating the client name.
        title.text = categoryLabel
        timeLabel.text = "Date/Time"
        time.text = formatDateTime(visit) ?: "—"
        distance.text = formatDistance(visit)
        eta.text = formatEta(visit)

        applyStatusAndAction(
            visit = visit,
            statusPill = statusPill,
            statusDot = statusDot,
            statusText = statusText,
            actionBtn = actionBtn,
            actionLabel = actionLabel,
            actionIcon = actionIcon,
            itemView = itemView,
        )

        val params = itemView.layoutParams as? LinearLayout.LayoutParams
            ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.bottomMargin = (10 * resources.displayMetrics.density).toInt()
        itemView.layoutParams = params
        return itemView
    }

    private fun applyStatusAndAction(
        visit: TodayVisit,
        statusPill: LinearLayout,
        statusDot: View,
        statusText: TextView,
        actionBtn: LinearLayout,
        actionLabel: TextView,
        actionIcon: ImageView,
        itemView: View,
    ) {
        val ctx = requireContext()
        val status = visit.status.lowercase(Locale.US)
        val postponed = isPostponed(visit)
        val cancelled = isCancelled(status)
        val completed = isCompleted(status)
        val inProgress = isInProgress(status)
        val needsCpDetails = (visit.tripType == "client_place" || visit.clientPlaceVisitId != null) &&
            status == "arrived" && visit.cpVisit?.outcome.isNullOrBlank()
        // "Outcome pending" — the trip is over (status=completed) but the
        // CP visit's outcome is still blank, so the backend has no record
        // of what happened on this visit. This covers two distinct miss
        // paths:
        //   1. SV-via-CP: field staff dismissed the Reject/Confirm sheet
        //      without choosing (telecaller's pre-fixed SV stuck pending).
        //   2. Regular CP: field staff swiped Trip Complete but the
        //      outcome sheet was killed by the OS, app crash, or the user
        //      backing out before picking Booking/SV/Postpone/NotInterested.
        // In either case the card would otherwise route to the read-only
        // Completed Detail screen and the decision would be stuck forever.
        // Tapping the Pending action reopens CompleteCpVisitBottomSheet —
        // SV-via-CP visits land in the locked SV tab automatically via
        // the sheet's own detectAndApplyLockedSvMode signal, regular CP
        // visits open in the default Booking-tab flow.
        val isOutcomePending = completed &&
            visit.cpVisit?.outcome.isNullOrBlank()

        // Three click outcomes: open the trip flow, open the completed-visit
        // detail (read-only summary), or no-op (cancelled cards stay inert).
        var tapMode: TapMode = TapMode.TRIP

        when {
            cancelled -> {
                statusPill.background = ContextCompat.getDrawable(ctx, R.drawable.bg_cpv_status_cancelled)
                statusDot.background = ContextCompat.getDrawable(ctx, R.drawable.bg_cpv_status_dot_cancelled)
                statusText.text = "Cancelled"
                statusText.setTextColor(Color.parseColor("#B42318"))

                actionBtn.background = ContextCompat.getDrawable(ctx, R.drawable.bg_cpv_action_cancelled)
                actionLabel.text = "Cancelled"
                actionLabel.setTextColor(Color.parseColor("#7A0F0A"))
                actionIcon.setImageResource(R.drawable.ic_cpv_action_cancelled)
                actionIcon.visibility = View.VISIBLE
                actionIcon.imageTintList = null
                tapMode = TapMode.NONE
            }
            isOutcomePending -> {
                // CP visit's trip is complete but the outcome was never
                // recorded (sheet dismissed, app crash, OS kill). Render an
                // amber "Pending" status + Pending action — tap reopens
                // the CompleteCpVisitBottomSheet so the user can still
                // record the outcome.
                statusPill.background = ContextCompat.getDrawable(ctx, R.drawable.bg_home_trip_status_progress)
                statusDot.background = ContextCompat.getDrawable(ctx, R.drawable.bg_home_trip_status_dot)
                statusText.text = "Pending"
                statusText.setTextColor(Color.parseColor("#B54708"))

                actionBtn.background = ContextCompat.getDrawable(ctx, R.drawable.bg_home_trip_action_ready)
                actionLabel.text = "Pending"
                actionLabel.setTextColor(Color.WHITE)
                actionIcon.setImageResource(R.drawable.ic_home_trip_play)
                actionIcon.visibility = View.VISIBLE
                actionIcon.imageTintList = null
                tapMode = TapMode.REOPEN_CONFIRM
            }
            completed -> {
                statusPill.background = ContextCompat.getDrawable(ctx, R.drawable.bg_home_trip_status_ready)
                statusDot.background = ContextCompat.getDrawable(ctx, R.drawable.bg_home_trip_status_dot)
                statusText.text = "Completed"
                statusText.setTextColor(Color.parseColor("#169B2F"))

                actionBtn.background = ContextCompat.getDrawable(ctx, R.drawable.bg_cpv_action_completed)
                actionLabel.text = "Completed"
                actionLabel.setTextColor(Color.parseColor("#1F7A3F"))
                actionIcon.setImageResource(R.drawable.ic_cpv_action_completed)
                actionIcon.visibility = View.VISIBLE
                actionIcon.imageTintList = null
                // Tap routes to the read-only Completed Visit Detail screen.
                tapMode = TapMode.COMPLETED_DETAIL
            }
            needsCpDetails -> {
                statusPill.background = ContextCompat.getDrawable(ctx, R.drawable.bg_home_trip_status_progress)
                statusDot.background = ContextCompat.getDrawable(ctx, R.drawable.bg_home_trip_status_dot)
                statusText.text = "Reaching"
                statusText.setTextColor(Color.parseColor("#B54708"))

                actionBtn.background = ContextCompat.getDrawable(ctx, R.drawable.bg_home_trip_action_ready)
                // Match the Trip Details screen's CTA: when this is a
                // telecaller-fixed SV-via-CP visit (visitCategory =
                // sv_cum_cp) the next action is to fill the SV form,
                // not "complete" a regular CP. TripNavigationFragment
                // already does this label flip on its own button —
                // mirror it here so the user sees the same affordance
                // on the list card.
                actionLabel.text = if (visit.visitCategory == "sv_cum_cp") {
                    "Complete SV details"
                } else {
                    "Complete Trip"
                }
                actionLabel.setTextColor(Color.WHITE)
                actionIcon.setImageResource(R.drawable.ic_home_trip_play)
                actionIcon.visibility = View.VISIBLE
                actionIcon.imageTintList = null
            }
            inProgress -> {
                statusPill.background = ContextCompat.getDrawable(ctx, R.drawable.bg_home_trip_status_progress)
                statusDot.background = ContextCompat.getDrawable(ctx, R.drawable.bg_home_trip_status_dot)
                statusText.text = if (status == "arrived") "Reaching" else "Enroute"
                statusText.setTextColor(Color.parseColor("#B54708"))

                actionBtn.background = ContextCompat.getDrawable(ctx, R.drawable.bg_home_trip_action_progress)
                // Same SV-via-CP rename as the needsCpDetails branch —
                // covers the case where the card lands on a generic
                // "arrived" state without the outcome-blank gate firing.
                val arrivedLabel = if (visit.visitCategory == "sv_cum_cp") {
                    "Complete SV details"
                } else {
                    "Complete Trip"
                }
                actionLabel.text = if (status == "arrived") arrivedLabel else "Enroute"
                actionLabel.setTextColor(Color.parseColor("#B54708"))
                actionIcon.visibility = View.GONE
            }
            postponed -> {
                statusPill.background = ContextCompat.getDrawable(ctx, R.drawable.bg_home_trip_status_done)
                statusDot.background = ContextCompat.getDrawable(ctx, R.drawable.bg_home_trip_status_dot)
                statusText.text = "Postponed"
                statusText.setTextColor(Color.parseColor("#475467"))

                actionBtn.background = ContextCompat.getDrawable(ctx, R.drawable.bg_home_trip_action_ready)
                actionLabel.text = "Reschedule"
                actionLabel.setTextColor(Color.WHITE)
                actionIcon.setImageResource(R.drawable.ic_home_trip_play)
                actionIcon.visibility = View.VISIBLE
                actionIcon.imageTintList = null
            }
            !isClockedIn -> {
                // Scheduled but the user hasn't clocked in for the day. Matches the
                // design's "Need to Clock In" state. Tap routes to the clock-in
                // attendance screen — opening the trip flow at this point would let
                // the salesperson start a visit without an active attendance session,
                // which the backend allows but defeats the field-tracking guarantee.
                statusPill.background = ContextCompat.getDrawable(ctx, R.drawable.bg_home_trip_status_ready)
                statusDot.background = ContextCompat.getDrawable(ctx, R.drawable.bg_home_trip_status_dot)
                statusText.text = "Ready"
                statusText.setTextColor(Color.parseColor("#169B2F"))

                actionBtn.background = ContextCompat.getDrawable(ctx, R.drawable.bg_home_trip_action_ready)
                actionLabel.text = "Need to Clock In"
                actionLabel.setTextColor(Color.WHITE)
                actionIcon.setImageResource(R.drawable.ic_cpv_action_clockin)
                actionIcon.visibility = View.VISIBLE
                actionIcon.imageTintList = null
                tapMode = TapMode.CLOCK_IN
            }
            else -> {
                statusPill.background = ContextCompat.getDrawable(ctx, R.drawable.bg_home_trip_status_ready)
                statusDot.background = ContextCompat.getDrawable(ctx, R.drawable.bg_home_trip_status_dot)
                statusText.text = "Ready"
                statusText.setTextColor(Color.parseColor("#169B2F"))

                actionBtn.background = ContextCompat.getDrawable(ctx, R.drawable.bg_home_trip_action_ready)
                actionLabel.text = "Start Trip"
                actionLabel.setTextColor(Color.WHITE)
                actionIcon.setImageResource(R.drawable.ic_home_trip_play)
                actionIcon.visibility = View.VISIBLE
                actionIcon.imageTintList = null
            }
        }

        when (tapMode) {
            TapMode.TRIP -> {
                val openNav: (View) -> Unit = { openVisit(visit) }
                itemView.isClickable = true
                itemView.setOnClickListener(openNav)
                actionBtn.isClickable = true
                actionBtn.setOnClickListener(openNav)
            }
            TapMode.COMPLETED_DETAIL -> {
                val openDetail: (View) -> Unit = { openCompletedDetail(visit) }
                itemView.isClickable = true
                itemView.setOnClickListener(openDetail)
                actionBtn.isClickable = true
                actionBtn.setOnClickListener(openDetail)
            }
            TapMode.CLOCK_IN -> {
                val openClockIn: (View) -> Unit = { openClockInFlow() }
                itemView.isClickable = true
                itemView.setOnClickListener(openClockIn)
                actionBtn.isClickable = true
                actionBtn.setOnClickListener(openClockIn)
            }
            TapMode.REOPEN_CONFIRM -> {
                val reopen: (View) -> Unit = { reopenConfirmSheet(visit) }
                itemView.isClickable = true
                itemView.setOnClickListener(reopen)
                actionBtn.isClickable = true
                actionBtn.setOnClickListener(reopen)
            }
            TapMode.NONE -> {
                itemView.isClickable = false
                itemView.setOnClickListener(null)
                actionBtn.isClickable = false
                actionBtn.setOnClickListener(null)
            }
        }
    }

    private enum class TapMode { TRIP, COMPLETED_DETAIL, CLOCK_IN, REOPEN_CONFIRM, NONE }

    /**
     * Reopens [CompleteCpVisitBottomSheet] for a CP visit whose trip is
     * complete but no SV outcome was chosen yet. The sheet's own
     * `detectAndApplyLockedSvMode` then resolves the SV-via-CP signals
     * and shows the Reject / Confirm footer — same UX the user got
     * immediately after the trip-complete swipe.
     */
    private fun reopenConfirmSheet(visit: TodayVisit) {
        val cpId = visit.clientPlaceVisitId ?: visit.id
        // Pre-pass the SV-fixed hint only when the row is actually a
        // telecaller-fixed SV-via-CP visit. For regular CP visits the
        // sheet should open in its default multi-tab Booking flow, not
        // locked to the SV tab. detectAndApplyLockedSvMode still runs
        // server-side either way as a backstop.
        val isSvFixed = visit.visitCategory == "sv_cum_cp"
        CompleteCpVisitBottomSheet.newInstance(
            cpVisitId = cpId,
            cpClientMet = null,
            cpOutcome = null,
            isSvFixedHint = isSvFixed,
        ).show(parentFragmentManager, "CompleteCpVisitBottomSheet")
    }

    /**
     * Routes the "Need to Clock In" card tap to the clock-in attendance
     * screen so the user has to actually start their day before launching
     * a trip. Same destination HrDashboardFragment uses for its Clock In
     * tile — see HrDashboardFragment.kt:160.
     */
    private fun openClockInFlow() {
        android.widget.Toast.makeText(
            requireContext(),
            "Clock in to continue",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
        parentFragmentManager.beginTransaction()
            .replace(
                R.id.fragmentContainer,
                com.manjugroups.m_connect.ui.hr.ClockInAreaFragment(),
            )
            .addToBackStack(null)
            .commit()
    }

    private fun openCompletedDetail(visit: TodayVisit) {
        parentFragmentManager.beginTransaction()
            .replace(
                R.id.fragmentContainer,
                CompletedVisitDetailFragment.forVisit(visit),
            )
            .addToBackStack(null)
            .commit()
    }

    private fun openVisit(visit: TodayVisit) {
        parentFragmentManager.beginTransaction()
            .replace(
                R.id.fragmentContainer,
                TripNavigationFragment.forVisit(
                    visitId = visit.id,
                    placeName = visit.placeName,
                    placeAddress = visit.placeAddress,
                    destLat = visit.placeLat,
                    destLng = visit.placeLng,
                    status = visit.status,
                    tripType = visit.tripType,
                    clientPlaceVisitId = visit.clientPlaceVisitId,
                    cpClientMet = visit.cpVisit?.clientMet,
                    cpOutcome = visit.cpVisit?.outcome,
                    visitCategory = visit.visitCategory,
                )
            )
            .addToBackStack(null)
            .commit()
    }

    // ---------- Formatting helpers ----------

    private fun formatDisplayName(rawName: String): String =
        rawName.lowercase(Locale.getDefault()).split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.titlecase() } }
            .ifBlank { "User" }

    /**
     * Renders "dd/MM/yyyy hh:mm a" — matches the rest of the app's
     * date convention (Indian dd/MM/yyyy, not US MM/dd/yy). Uses
     * scheduledStartTime if provided, otherwise the date-only field.
     */
    private fun formatDateTime(visit: TodayVisit): String? {
        val dateOut = SimpleDateFormat("dd/MM/yyyy", Locale.US)
        val timeOut = SimpleDateFormat("hh:mm a", Locale.US)

        val isoCandidates = listOf(visit.scheduledStartTime, visit.scheduledDate)
        val parsedDate = isoCandidates
            .asSequence()
            .filter { !it.isNullOrBlank() }
            .mapNotNull { parseIsoDate(it!!) }
            .firstOrNull() ?: return null

        // If the ISO string was just yyyy-MM-dd with no time, only show the date.
        val hasTime = isoCandidates.any { !it.isNullOrBlank() && it!!.contains('T') }
        return if (hasTime) "${dateOut.format(parsedDate)} ${timeOut.format(parsedDate)}"
        else dateOut.format(parsedDate)
    }

    private fun parseIsoDate(iso: String): java.util.Date? {
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

    private fun formatDistance(visit: TodayVisit): String =
        if (visit.placeLat != null && visit.placeLng != null) "Open route" else "Not mapped"

    private fun formatEta(visit: TodayVisit): String {
        val status = visit.status.lowercase(Locale.US)
        return when {
            isCancelled(status) -> "Cancelled"
            isCompleted(status) -> "Complete"
            status == "arrived" -> "At client place"
            isInProgress(status) -> "Tracking"
            else -> "After start"
        }
    }

    // ---------- Entry animation (matches Home / Attendance / Apps cadence) ----------

    private fun primeEntryAnimation(root: View) {
        val density = resources.displayMetrics.density
        val back = root.findViewById<View>(R.id.btnCpVisitsBack)
        val title = root.findViewById<View>(R.id.tvCpVisitsTitle)
        val plus = root.findViewById<View>(R.id.btnCreateCpVisit)
        val search = root.findViewById<View>(R.id.cpvSearchContainer)
        val pills = root.findViewById<View>(R.id.cpvFilterScroll)
        val scroll = root.findViewById<View>(R.id.cpvScroll)

        listOf(back, title, plus).forEach {
            it.animate().cancel()
            it.alpha = 0f
            it.translationY = -8f * density
        }
        search.animate().cancel()
        search.alpha = 0f
        search.translationY = 16f * density
        pills.animate().cancel()
        pills.alpha = 0f
        pills.translationY = 16f * density
        scroll.animate().cancel()
        scroll.alpha = 0f
        scroll.translationY = 24f * density
    }

    private fun playEntryAnimation(root: View) {
        if (!pendingEntryAnimation) return
        pendingEntryAnimation = false
        val emphasized = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
        val expoOut = android.view.animation.PathInterpolator(0.19f, 1f, 0.22f, 1f)

        val back = root.findViewById<View>(R.id.btnCpVisitsBack)
        val title = root.findViewById<View>(R.id.tvCpVisitsTitle)
        val plus = root.findViewById<View>(R.id.btnCreateCpVisit)
        val search = root.findViewById<View>(R.id.cpvSearchContainer)
        val pills = root.findViewById<View>(R.id.cpvFilterScroll)
        val scroll = root.findViewById<View>(R.id.cpvScroll)

        back.animate().alpha(1f).translationY(0f).setStartDelay(40L).setDuration(320L).setInterpolator(emphasized).start()
        title.animate().alpha(1f).translationY(0f).setStartDelay(80L).setDuration(360L).setInterpolator(emphasized).start()
        plus.animate().alpha(1f).translationY(0f).setStartDelay(120L).setDuration(360L).setInterpolator(emphasized).start()

        search.animate().alpha(1f).translationY(0f).setStartDelay(180L).setDuration(420L).setInterpolator(expoOut).start()
        pills.animate().alpha(1f).translationY(0f).setStartDelay(260L).setDuration(420L).setInterpolator(expoOut).start()

        scroll.animate().alpha(1f).translationY(0f).setStartDelay(340L).setDuration(460L).setInterpolator(expoOut).start()
    }

    // ---------- Create dialog ----------

    private fun showCreateDialog() {
        setFragmentResultListener(CreateCpVisitBottomSheet.RESULT_KEY_CREATED) { _, bundle ->
            val success = bundle.getBoolean("success", false)
            if (success) {
                loadVisits()
            }
        }
        CreateCpVisitBottomSheet.newInstance().show(parentFragmentManager, "create_cp_visit")
    }
}
