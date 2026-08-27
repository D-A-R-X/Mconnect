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
import com.manjugroups.m_connect.ui.common.applySmoothTransitions
import com.manjugroups.m_connect.ui.common.pushDetail
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import com.manjugroups.m_connect.ui.common.showOnce
import com.manjugroups.m_connect.ui.common.commitOnce

class CpVisitsFragment : Fragment() {
    private val geoApi = GeoTrackApi.create()
    // Shared with the Attendance dashboard so we get the same clock-in state without
    // adding a new endpoint or duplicating the attendance load.
    private val attendanceVm: AttendanceFlowViewModel by activityViewModels()
    private lateinit var session: SessionManager
    private var rootView: View? = null

    private enum class Filter { ALL, SCHEDULED, POSTPONED, IN_PROGRESS, COMPLETED, CANCELLED }

    private var allVisits: List<TodayVisit> = emptyList()
    private var focusArgApplied = false

    /**
     * A CP visit id to open as soon as the list has loaded — set when the user
     * arrives from their task, so the task lands on THAT visit's trip screen
     * rather than on a list they then have to search.
     *
     * Cleared once consumed so a back-navigation returns to the list instead
     * of bouncing straight back into the trip.
     */
    private var focusCpVisitId: String? = null
    private var currentFilter: Filter = Filter.ALL
    private var searchQuery: String = ""
    // Debounces the server-side search reload so a super-admin can find an
    // older client that's beyond the recency cap of the default list.
    private val searchDebounce =
        android.os.Handler(android.os.Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    // Active date-range filter (yyyy-MM-dd). Null = default window.
    private var filterFromDate: String? = null
    private var filterToDate: String? = null
    private val DATE_FILTER_KEY = "cp_date_filter_result"
    // Row cache: a visit's card is inflated at most ONCE per (data,
    // clock-state) generation — re-inflating up to 200 card layouts on every
    // tab switch is what used to make this screen feel slow. Pagination now
    // bounds each render to one page of rows; cards inflate lazily on the
    // first page that shows them and come back from this cache afterwards.
    private var rowViewCache = java.util.IdentityHashMap<TodayVisit, View>()
    private var rowsBuiltFor: List<TodayVisit>? = null
    private var rowsBuiltClockedIn: Boolean? = null
    // Infinite scroll: render 20 rows, extend by 20 as the list nears its end.
    private var cpWindowCtx: String? = null
    private var cpMatchedCount = 0
    private val cpPager = com.manjugroups.m_connect.ui.common.InfiniteScrollPager(
        onLoadMore = { renderList() },
    )
    private var pendingEntryAnimation = true
    // True once the first CP-visit fetch has rendered. Gates the skeleton
    // so refreshes / re-opens with data already on screen don't flash the
    // list back to placeholders.
    private var hasLoadedOnce = false
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
        // Read the requested visit ONCE. Re-reading it on every view creation
        // would re-open the trip each time the user came back to the list.
        if (!focusArgApplied) {
            focusArgApplied = true
            focusCpVisitId = arguments?.getString(ARG_FOCUS_CP_VISIT_ID)
        }

        view.findViewById<View>(R.id.btnCpVisitsBack).setOnClickListener {
            navigateUp()
        }
        view.findViewById<View>(R.id.btnCreateCpVisit).setOnClickListener { showCreateDialog() }

        setupSearch(view)
        setupFilterPills(view)
        setupDateFilter(view)
        observeAttendanceState()

        // Infinite scroll: render the next 20 rows as the user nears the end.
        view.findViewById<androidx.core.widget.NestedScrollView>(R.id.cpvScroll)?.let { scroll ->
            cpPager.bindNestedScroll(scroll, totalCount = { cpMatchedCount })
        }

        // Pull-to-refresh: re-runs the list load. The spinner is dismissed
        // inside loadVisits when the response (or error) lands.
        view.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(
            R.id.cpvRefresh
        ).setupPullToRefresh { loadVisits() }

        wireApprovalsBanner(view)
        loadVisits()
        attendanceVm.loadTodayAttendance(session.bearerToken, requireContext())

        primeEntryAnimation(view)
        view.post { playEntryAnimation(view) }
    }

    override fun onResume() {
        super.onResume()
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTopBarAppearance(Color.WHITE, true)
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(false)
        // Refresh clock-in state in case the user clocked in/out from another tab.
        attendanceVm.loadTodayAttendance(session.bearerToken, requireContext())
        // A GM may have cleared approvals elsewhere (push → queue); re-check.
        refreshApprovalsBanner()
    }

    // ---------- GM approvals entry ----------

    /**
     * The out-of-geofence CP-completion approval queue is otherwise reachable only
     * by tapping the approval push. GMs who miss/dismiss that notification had no
     * way in — hence "can't approve by clicking". This banner is a reliable,
     * always-visible entry: the endpoint is GM-scoped, so it stays hidden for
     * anyone who isn't the resolved approver of a pending completion.
     */
    private fun wireApprovalsBanner(root: View) {
        root.findViewById<View>(R.id.cpvApprovalsBanner).setOnClickListener {
            CpApprovalQueueBottomSheet.show(childFragmentManager)
        }
        // The queue emits this after each approve/reject (and on dismiss) so the
        // banner count and the visit list stay in sync without a manual refresh.
        childFragmentManager.setFragmentResultListener(
            CpApprovalQueueBottomSheet.RESULT_KEY, viewLifecycleOwner,
        ) { _, _ ->
            if (!isAdded) return@setFragmentResultListener
            refreshApprovalsBanner()
            loadVisits()
        }
        refreshApprovalsBanner()
    }

    private fun refreshApprovalsBanner() {
        val banner = rootView?.findViewById<View>(R.id.cpvApprovalsBanner) ?: return
        val title = rootView?.findViewById<TextView>(R.id.tvApprovalsBannerTitle)
        viewLifecycleOwner.lifecycleScope.launch {
            val count = runCatching {
                geoApi.getPendingCpApprovals(session.bearerToken).items.size
            }.getOrDefault(0)
            if (!isAdded) return@launch
            if (count > 0) {
                title?.text =
                    if (count == 1) "1 approval waiting" else "$count approvals waiting"
                banner.visibility = View.VISIBLE
            } else {
                banner.visibility = View.GONE
            }
        }
    }

    override fun onPause() {
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(true)
        super.onPause()
    }

    override fun onDestroyView() {
        SkeletonUtils.stopAll()
        pendingEntryAnimation = true
        rootView = null
        // Cached rows belong to the destroyed view tree — never reattach them.
        rowViewCache.clear()
        rowsBuiltFor = null
        rowsBuiltClockedIn = null
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
                // Instant filter over what's already loaded…
                renderList()
                // …plus a debounced backend search so results BEYOND the loaded
                // recency window (older clients on a super-admin's list) surface.
                searchRunnable?.let { searchDebounce.removeCallbacks(it) }
                val r = Runnable { if (isAdded) loadVisits() }
                searchRunnable = r
                searchDebounce.postDelayed(r, 350)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    // ---------- Date-range filter ----------

    private fun setupDateFilter(root: View) {
        root.findViewById<View>(R.id.btnFilterCalendar)?.setOnClickListener {
            com.manjugroups.m_connect.ui.hr.CalendarRangePickerSheet.newInstance(
                title = "Filter by date",
                subtitle = "Pick a day or a date range",
                initialFrom = filterFromDate,
                initialTo = filterToDate,
                resultKey = DATE_FILTER_KEY,
            ).show(childFragmentManager, "cp_date_filter")
        }
        childFragmentManager.setFragmentResultListener(
            DATE_FILTER_KEY, viewLifecycleOwner,
        ) { _, bundle ->
            filterFromDate = bundle.getString(
                com.manjugroups.m_connect.ui.hr.CalendarRangePickerSheet.KEY_FROM,
            )
            filterToDate = bundle.getString(
                com.manjugroups.m_connect.ui.hr.CalendarRangePickerSheet.KEY_TO,
            )
            updateDateFilterChip()
            loadVisits()
        }
        root.findViewById<TextView>(R.id.tvDateFilterChip)?.setOnClickListener {
            filterFromDate = null
            filterToDate = null
            updateDateFilterChip()
            loadVisits()
        }
    }

    private fun updateDateFilterChip() {
        val chip = rootView?.findViewById<TextView>(R.id.tvDateFilterChip) ?: return
        val from = filterFromDate
        val to = filterToDate
        if (from == null || to == null) {
            chip.visibility = View.GONE
            return
        }
        chip.visibility = View.VISIBLE
        chip.text = if (from == to) {
            "${prettyDate(from)}  ✕"
        } else {
            "${prettyDate(from)} – ${prettyDate(to)}  ✕"
        }
    }

    private fun prettyDate(iso: String): String {
        val parsed = runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)
        }.getOrNull() ?: return iso
        return SimpleDateFormat("dd MMM", Locale.getDefault()).format(parsed)
    }

    /** Keep only visits whose scheduledDate falls in the active range. */
    private fun inDateRange(v: TodayVisit): Boolean {
        val from = filterFromDate ?: return true
        val to = filterToDate ?: return true
        val d = v.scheduledDate.takeIf { it.isNotBlank() } ?: return true
        return d in from..to
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
        // Skeleton only on the first load. On a pull-to-refresh / return
        // the existing rows stay put until renderList() rebuilds them —
        // no flash back to placeholders over data that's already there.
        if (!hasLoadedOnce) {
            SkeletonUtils.startSkeletonPulse(skeletonContainer)
            empty.visibility = View.GONE
            list.removeAllViews()
        }

        // Pass the active range to the backend when a filter is set so distant
        // dates load; otherwise fetch the default (all) window.
        val from = filterFromDate
        val to = filterToDate

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Switched from /api/sitevisits/my (legacy fieldVisits) to
                // /api/marketing/clientPlaceVisits/my so each row carries
                // the proposedSiteVisit / lead.followUpStatus / party
                // data we need to label the category (SV confirmation CP vs
                // Direct CP) on each card. The mapper below converts to
                // the existing TodayVisit shape so downstream filter /
                // sort / render code is unchanged.
                // Retry ONE transient network failure (a brief signal drop or a
                // timeout on the heavy 200-row query) before surfacing an error —
                // this is what showed the intermittent "CP network error" on an
                // otherwise-reachable backend. Only IOException/timeout is
                // retried; a parse/business error fails fast.
                val resp = retryIo(times = 1, initialDelayMs = 500) {
                    geoApi.getMyMarketingCpVisits(
                        session.bearerToken,
                        fromDate = from,
                        toDate = to,
                        // Browsable list: pull a wide window so the on-screen
                        // search can reach any client, not just the newest 20.
                        limit = 200,
                        // When searching, ask the backend to full-text search so a
                        // client OLDER than the recency window is still found (the
                        // super-admin "on web but not mobile" case).
                        search = searchQuery.ifBlank { null },
                    )
                }
                SkeletonUtils.stopSkeletonPulse(skeletonContainer)
                hasLoadedOnce = true
                if (!resp.success) {
                    showLoadError(resp.error ?: "Failed to load CP visits")
                    Toast.makeText(
                        requireContext(),
                        "CP fetch failed: ${resp.error ?: "unknown"}",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                // Sort: ongoing first (in-progress / arrived / reaching),
                // then pending (scheduled / not started), then completed
                // at the bottom. Within each status group, newest-first
                // by creationTime (= most recently assigned) with
                // scheduledDate as the legacy-row fallback.
                allVisits = resp.visits
                    .mapNotNull { it.toCpListVisitOrNull() }
                    .sortedWith(
                        compareBy<TodayVisit> { statusGroupPriority(it.status) }
                            .thenByDescending { it.creationTime ?: 0.0 }
                            .thenByDescending { it.scheduledDate }
                    )
                consumeFocusVisit()
                // Empty-state diagnostic toasts removed — the
                // "No Cp Visits Yet" empty-state UI already conveys
                // the same information, and the "server sent N but
                // mapper dropped all" debug toast was leaking dev
                // noise into the user-facing app. Both branches
                // collapse to a no-op render call below.
                renderList()
            } catch (e: Exception) {
                SkeletonUtils.stopSkeletonPulse(skeletonContainer)
                if (allVisits.isNotEmpty()) {
                    // A transient failure on a refresh must NOT blank a list the
                    // user already has — keep the last-loaded rows and mention it
                    // quietly instead of throwing them to the error screen.
                    renderList()
                    Toast.makeText(
                        requireContext(),
                        "Couldn't refresh — showing your last loaded CP visits.",
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    showLoadError("Network error: ${e.message ?: "unknown"}")
                    Toast.makeText(
                        requireContext(),
                        "CP network error: ${e.message ?: "unknown"}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
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
        // The CP-level "pending_gm_approval" hold must win over the fieldVisit
        // lifecycle: the app calls completeVisit after every outcome, so the
        // fieldVisit reads "completed" while the CP is actually held. Without
        // this the card would show "Completed" instead of "Pending Approval".
        val effectiveStatus = this.status?.takeIf { it == "pending_gm_approval" }
            ?: this.fieldVisit?.status?.takeIf { it.isNotBlank() }
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
        val profileClient = this.lead?.manualProfile?.clientName.asClientNameOrNull()
        val canonicalClient = this.client?.clientName.asClientNameOrNull()
        val typedContact = this.lead?.contactName.asClientNameOrNull()
        val placeLabel = this.clientPlace?.name.asClientNameOrNull()
        val phoneLabel = this.lead?.mobileNumber?.takeIf { it.isNotBlank() }
            ?: this.client?.mobileNumber?.takeIf { it.isNotBlank() }
            ?: this.clientPlace?.contactPhone?.takeIf { it.isNotBlank() }
        val resolvedClientName = profileClient
            ?: canonicalClient
            ?: typedContact
            ?: placeLabel
        val displayName = resolvedClientName ?: phoneLabel ?: "CP visit"
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
            cpType = this.cpType,
        )
        return TodayVisit(
            id = cpId,
            clientPlaceId = this.clientPlaceId ?: cpId,
            scheduledDate = scheduled,
            status = effectiveStatus,
            approvalGmName = this.approvalGmName,
            rejectRemark = this.rejectRemark,
            reassignedFromRejection = this.reassignedFromRejection,
            visitCategory = category,
            placeName = displayName,
            placeAddress = this.clientPlace?.address
                ?: this.clientPlace?.formattedAddress,
            placeLat = this.clientPlace?.lat,
            placeLng = this.clientPlace?.lng,
            tripType = "client_place",
            clientPlaceVisitId = cpId,
            leadName = resolvedClientName,
            leadPhone = phoneLabel,
            scheduledStartTime = this.scheduledTime,
            // LMO = the telecaller who owns the CP visit (the creator).
            lmoName = this.telecaller?.name?.takeIf { it.isNotBlank() }
                ?: this.telecaller?.staffName?.takeIf { it.isNotBlank() },
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

    private fun String?.asClientNameOrNull(): String? {
        val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val compact = value.filterNot { it.isWhitespace() || it == '+' || it == '-' || it == '(' || it == ')' }
        val digitCount = value.count { it.isDigit() }
        val phoneLike = digitCount >= 8 && compact.all { it.isDigit() }
        return value.takeUnless { phoneLike }
    }

    private fun renderList() {
        val root = rootView ?: return
        val list = root.findViewById<LinearLayout>(R.id.cpVisitsList)
        val empty = root.findViewById<LinearLayout>(R.id.cpvEmptyState)
        val emptyTitle = root.findViewById<TextView>(R.id.tvCpvEmptyTitle)
        val emptySubtitle = root.findViewById<TextView>(R.id.tvCpvEmptySubtitle)

        // Invalidate the row cache only when the data set is replaced (new
        // fetch) or the clock-in gate flips (row action buttons depend on it).
        if (rowsBuiltFor !== allVisits || rowsBuiltClockedIn != isClockedIn) {
            rowViewCache.clear()
            rowsBuiltFor = allVisits
            rowsBuiltClockedIn = isClockedIn
        }

        fun matches(v: TodayVisit): Boolean {
            if (!matchesFilter(v, currentFilter)) return false
            if (!inDateRange(v)) return false
            return com.manjugroups.m_connect.util.VisitSearch.matches(v, searchQuery)
        }
        val matched = allVisits.filter { matches(it) }
        cpMatchedCount = matched.size

        // Reset the scroll window whenever the filter / search / data changes.
        val windowCtx =
            "$currentFilter|$searchQuery|${System.identityHashCode(allVisits)}"
        if (windowCtx != cpWindowCtx) {
            cpWindowCtx = windowCtx
            cpPager.reset()
        }

        // Attach only the current window's rows (20, +20 on scroll).
        list.removeAllViews()
        matched.take(cpPager.limit).forEach { visit ->
            val rowView = rowViewCache.getOrPut(visit) { createRow(visit, list) }
            rowView.visibility = View.VISIBLE
            list.addView(rowView)
        }

        if (matched.isEmpty()) {
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

        // LMO (telecaller/creator) — shown only when the mapping supplied it.
        val lmoRow = itemView.findViewById<View>(R.id.rowVisitItemLmo)
        val lmoNameView = itemView.findViewById<TextView>(R.id.tvVisitItemLmoName)
        val lmo = visit.lmoName?.takeIf { it.isNotBlank() }
        if (lmo != null) {
            lmoNameView.text = lmo
            lmoRow.visibility = View.VISIBLE
        } else {
            lmoRow.visibility = View.GONE
        }

        // Category badge now lives in the body's Type cell (tvVisitItemTitle),
        // so the standalone tvVisitItemLead row underneath the grid is hidden.
        // Same de-dupe rule HomeFragment follows on Today's Trip.
        val categoryLabel = formatCpVisitTypeLabel(
            visitCategory = visit.visitCategory,
            cpType = visit.cpVisit?.cpType,
            hasCpRow = visit.clientPlaceVisitId != null,
        )
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

    /**
     * Runs [block], retrying up to [times] more times on a transient
     * IOException (connection drop / socket timeout) with exponential backoff.
     * Non-IO errors (Gson parse, business failures) are NOT retried — they would
     * just fail again — so they surface immediately.
     */
    private suspend fun <T> retryIo(
        times: Int,
        initialDelayMs: Long,
        block: suspend () -> T,
    ): T {
        var attempt = 0
        var delayMs = initialDelayMs
        while (true) {
            try {
                return block()
            } catch (e: java.io.IOException) {
                if (attempt >= times) throw e
                attempt++
                kotlinx.coroutines.delay(delayMs)
                delayMs *= 2
            }
        }
    }

    /** "1st", "2nd", "3rd", "4th"… for the "rescheduled Nth time" notice. */
    private fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) {
            "th"
        } else {
            when (n % 10) {
                1 -> "st"
                2 -> "nd"
                3 -> "rd"
                else -> "th"
            }
        }
        return "$n$suffix"
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
        // Out-of-geofence completion held for the GM: outcome is recorded but
        // the visit isn't final until the GM approves (→ completed) or rejects
        // (→ reopened for this staff).
        val pendingApproval = status == "pending_gm_approval"
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
            pendingApproval -> {
                // Outcome recorded but out of geofence — waiting on the GM.
                statusPill.background = ContextCompat.getDrawable(ctx, R.drawable.bg_home_trip_status_progress)
                statusDot.background = ContextCompat.getDrawable(ctx, R.drawable.bg_home_trip_status_dot)
                statusText.text = "Pending Approval"
                statusText.setTextColor(Color.parseColor("#B54708"))

                actionBtn.background = ContextCompat.getDrawable(ctx, R.drawable.bg_cpv_action_completed)
                // Tell the staff exactly which GM they're waiting on.
                actionLabel.text = visit.approvalGmName?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { "Awaiting: $it" } ?: "Awaiting GM"
                actionLabel.setTextColor(Color.parseColor("#1F7A3F"))
                actionIcon.setImageResource(R.drawable.ic_cpv_action_completed)
                actionIcon.visibility = View.VISIBLE
                actionIcon.imageTintList = null
                tapMode = TapMode.COMPLETED_DETAIL
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
            // Expired feature removed — a past-slot CP visit is no longer shown as
            // "Expired"; it falls through to its normal live status below.
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

        // Bounce-back / repeat-visit notices on the ETA line, only while the
        // visit is live (a completed/pending/cancelled row keeps its own text).
        // Priority: 3-strike client-unavailable warning → GM reject bounce →
        // "client not met" auto-reschedule notice.
        if (!completed && !pendingApproval && !cancelled) {
            val eta = itemView.findViewById<TextView>(R.id.tvVisitItemEta)
            val rejectRemark = visit.rejectRemark?.trim()
            val rescheduleCount = visit.rescheduleCount ?: 0
            when {
                visit.clientUnavailableWarning == true -> {
                    eta?.text = "⚠ Client unavailable — last 3 visits missed"
                    eta?.setTextColor(Color.parseColor("#B42318"))
                }
                visit.reassignedFromRejection == true -> {
                    eta?.text = if (!rejectRemark.isNullOrEmpty()) {
                        "GM sent back: $rejectRemark"
                    } else {
                        "Reassigned by GM"
                    }
                    eta?.setTextColor(Color.parseColor("#B54708"))
                }
                rescheduleCount > 0 -> {
                    // Auto-rescheduled after a "client not met" — tell them the
                    // last miss date and how many times it's bounced.
                    val date = visit.lastNotMetDate?.trim()
                    val nth = ordinal(rescheduleCount)
                    eta?.text = if (!date.isNullOrEmpty()) {
                        "Client not met on $date · rescheduled $nth time"
                    } else {
                        "Client not met · rescheduled $nth time"
                    }
                    eta?.setTextColor(Color.parseColor("#B54708"))
                }
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

    /** Three-bucket status priority used by the list sort. Lower
     *  number = higher in the list:
     *    0 → ongoing (in-progress / arrived / on-site / reaching)
     *    1 → pending (scheduled / not started)
     *    2 → completed / cancelled (done — pinned to the bottom)
     *  Unknown statuses fall into the pending bucket so a stale
     *  enum from an older backend never gets buried. */
    private fun statusGroupPriority(rawStatus: String?): Int {
        val s = rawStatus.orEmpty().lowercase(Locale.US)
        return when (s) {
            "in-progress", "in_progress", "ongoing", "started", "active",
            "arrived", "arrival_verified", "arrival-verified",
            "on_site", "on-site", "reaching" -> 0
            "completed", "complete", "done", "closed",
            "cancelled", "canceled" -> 2
            else -> 1
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
        // Per-cpType routing — gift_distribution / old_client /
        // collection_cp have dedicated sheets handled by the trip nav
        // screen; opening the default booking-outcome sheet here is
        // wrong UI for them. Punt those into the trip nav, which has
        // both the per-type click dispatcher and the belt-and-braces
        // guard in showCpCompletionSheet().
        val cpType = visit.cpVisit?.cpType?.lowercase()
        if (cpType == "collection_cp" || cpType == "old_client" || cpType == "gift_distribution") {
            openVisit(visit)
            return
        }
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
            cpType = visit.cpVisit?.cpType,
        ).showOnce(parentFragmentManager, "CompleteCpVisitBottomSheet")
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
        parentFragmentManager.pushDetail(
            com.manjugroups.m_connect.ui.hr.ClockInAreaFragment(),
        )
    }

    private fun openCompletedDetail(visit: TodayVisit) {
        parentFragmentManager.pushDetail(
            CompletedVisitDetailFragment.forVisit(visit),
        )
    }

    /**
     * Open the visit the caller asked to focus, if it is in the loaded list.
     *
     * Silently does nothing when the id isn't found — the visit may be outside
     * the current filter or scope, and dropping the user on the list is a far
     * better outcome than an error about a record they can still scroll to.
     */
    private fun consumeFocusVisit() {
        val target = focusCpVisitId?.takeIf { it.isNotBlank() } ?: return
        // Strip the argument too: the list reloads on every resume, and a
        // still-present argument would fling the user back into the trip each
        // time they navigated back to the list.
        arguments?.remove(ARG_FOCUS_CP_VISIT_ID)
        val match = allVisits.firstOrNull {
            it.clientPlaceVisitId == target || it.id == target
        } ?: return
        focusCpVisitId = null
        openVisit(match)
    }

    private fun openVisit(visit: TodayVisit) {
        parentFragmentManager.pushDetail(
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
                cpType = visit.cpVisit?.cpType,
                clientMobile = visit.leadPhone,
                lmoName = visit.lmoName,
                deadline = com.manjugroups.m_connect.util.VisitDeadline.format(
                    visit.scheduledDate,
                    visit.scheduledEndTime ?: visit.scheduledStartTime,
                ),
            )
        )
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
        CreateCpVisitBottomSheet.newInstance().showOnce(parentFragmentManager, "create_cp_visit")
    }

    companion object {
        private const val ARG_FOCUS_CP_VISIT_ID = "arg_focus_cp_visit_id"

        /** Entry used by the task router: opens the list, then that visit. */
        fun newInstance(focusCpVisitId: String? = null): CpVisitsFragment =
            CpVisitsFragment().apply {
                if (!focusCpVisitId.isNullOrBlank()) {
                    arguments = android.os.Bundle().apply {
                        putString(ARG_FOCUS_CP_VISIT_ID, focusCpVisitId)
                    }
                }
            }
    }

}
