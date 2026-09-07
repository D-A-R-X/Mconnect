package com.manjugroups.m_connect.ui.hr

import android.os.Bundle
import com.manjugroups.m_connect.ui.common.setupPullToRefresh
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.graphics.Color
import android.widget.ImageView
import android.widget.Toast
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import coil.load
import coil.transform.CircleCropTransformation
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentAttendanceHistoryBinding
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import com.manjugroups.m_connect.ui.common.AdvancedListFilterSheet
import com.manjugroups.m_connect.ui.common.AvatarUtils.loadUserAvatar
import com.manjugroups.m_connect.ui.common.RejectWithReasonBottomSheet
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.AttendanceCancelRequest
import com.manjugroups.m_connect.network.AttendanceRecord
import com.manjugroups.m_connect.network.ApproveAttendanceRequest
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.manjugroups.m_connect.network.RejectRequest
import com.manjugroups.m_connect.network.AttendanceApprovalRecord
import com.manjugroups.m_connect.ui.common.HorizontalTabLayout
import com.manjugroups.m_connect.ui.common.LocalCache
import com.manjugroups.m_connect.ui.common.navigateUp
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import java.text.SimpleDateFormat
import java.util.*
import com.manjugroups.m_connect.ui.common.showOnce

class AttendanceHistoryFragment : Fragment() {

    private var _binding: FragmentAttendanceHistoryBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager
    private val api = ApiService.create()
    private lateinit var attendanceAdapter: AttendanceAdapter

    private var filterFromDate: String = ""
    private var filterToDate: String = ""
    private var filterAttendanceStatus: String? = null
    private var filterStaffId: String? = null

    // Web-style rows-per-page pagination (10/25/50/100). The page resets
    // whenever the tab / search / date-filter context changes — tracked by
    // signature in submitPagedList so no listener needs to remember to.
    // Infinite scroll: render 20, extend by 20 near the end of the list.
    private var lastPageContext: String? = null
    private var attendanceFilteredCount = 0
    private val attendancePager = com.manjugroups.m_connect.ui.common.InfiniteScrollPager(
        onLoadMore = {
            filterCurrentListOnTyping(binding.etSearch.text?.toString().orEmpty())
        },
        // The window above only pages through rows already in memory. The All
        // tab's range is company-wide and far too large to fetch at once, so
        // reaching the end also has to pull the next page from the server.
        onEndReached = { loadNextAllAttendancePage() },
    )

    // Server-side paging for the All tab. `hasMore` is the backend telling us
    // rows remain past the cursor; `loading` collapses the burst of
    // end-of-list scroll callbacks into a single in-flight request.
    private var allAttendanceCursor: String? = null
    private var allAttendanceHasMore: Boolean = false
    private var loadingAllAttendancePage: Boolean = false
    // All-time bounds for HR Review (which shows the entire review backlog).
    // The "All" tab now honors the date filter instead (filterFromDate/To),
    // so "Last month" and friends actually apply there.
    private val ALL_TAB_FROM_DATE = "2000-01-01"
    private val ALL_TAB_TO_DATE = "2100-12-31"
    // Reject-reason result keys for the shared RejectWithReasonBottomSheet —
    // two keys encode whether the row is an attendanceRequests doc so the
    // decision routes correctly even across process recreation.
    private val REJECT_KEY_REQUEST = "reject_attn_request"
    private val REJECT_KEY_REVIEW = "reject_attn_review"
    // Role/hierarchy-based tab visibility (mirrors the web). Logical tab ids:
    // 0=My Attendance, 1=Team Attendance, 2=Team Approval, 3=All Approval,
    // 4=HR Review, 5=All. `visibleLogicalTabs` is the subset actually shown, in
    // order, so the visible tab position maps back to a logical id.
    private var isReportingOfficer = false
    private var teamCount = 0
    private var visibleLogicalTabs: List<Int> = listOf(0, 1, 2, 3, 4, 5)
    private val submittedRemarkDates = mutableSetOf<String>()

    private var cachedMyRecords: List<AttendanceRecord> = emptyList()
    private var cachedApprovals: List<AttendanceApprovalRecord> = emptyList()      // Team Approval · Attendance
    // Team Approval · Requests — the caller's team correction/remark requests
    // waiting on the reporting officer (web's "Waiting on RO"). Their ids are
    // attendanceRequests ids: approve/reject with isRequest = true.
    private var cachedTeamRequests: List<AttendanceApprovalRecord> = emptyList()
    // True once a REAL request feed exists (server `requests` array or the
    // hr-review fallback). Only then are request-linked shadow rows filtered
    // out of the Team Approval attendance list — otherwise a plain reporting
    // officer on a pre-deploy backend would lose request visibility entirely.
    private var teamRequestsSourced = false
    private var cachedAllApprovals: List<AttendanceApprovalRecord> = emptyList()   // All Approval
    private var cachedHrReview: List<AttendanceApprovalRecord> = emptyList()       // HR Review (both sub-tabs)
    private var cachedTeamAttendance: List<AttendanceApprovalRecord> = emptyList() // Team Attendance
    private var cachedAllAttendance: List<AttendanceApprovalRecord> = emptyList()  // All (company-wide)
    private var cachedFines: List<com.manjugroups.m_connect.network.FineDeductionItem> = emptyList()

    // True while refreshAllData's fetches are in flight. A tab whose cache
    // is still empty shows the skeleton during this window instead of a
    // premature "no data" empty state.
    private var isFetchingData = false

    // Debounced server-side search for the All tab (see the TextWatcher).
    private var allTabSearchJob: kotlinx.coroutines.Job? = null

    private var activeTab = 0
    private var activeSubTab = 0 // 0 = Attendance, 1 = Request
    private val labels = listOf("Present", "Half-day", "Absent", "Weekoff", "Holiday")
    private val values = listOf("present", "half-day", "absent", "weekoff", "holiday")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAttendanceHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        attendanceAdapter = AttendanceAdapter()
        val attLm = LinearLayoutManager(requireContext())
        binding.rvAttendance.layoutManager = attLm
        binding.rvAttendance.adapter = attendanceAdapter
        attendancePager.bindRecyclerView(binding.rvAttendance, attLm) { attendanceFilteredCount }

        binding.btnBack.setOnClickListener { navigateUp() }

        binding.btnAttendanceSearch.setOnClickListener {
            if (binding.layoutSearch.visibility == View.VISIBLE) {
                binding.layoutSearch.visibility = View.GONE
                binding.etSearch.text?.clear()
                val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
            } else {
                binding.layoutSearch.visibility = View.VISIBLE
                binding.etSearch.requestFocus()
                val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(binding.etSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }

        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterCurrentListOnTyping(s?.toString().orEmpty())
                // The All tab's default feed is capped to the newest ~750 rows
                // company-wide (a few days), so filtering it locally hides a
                // person's older records. Ask the SERVER for the searched
                // staff's full range instead (debounced).
                if (activeTab == 5) scheduleAllTabServerSearch(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // Pull-to-refresh runs refreshAllData without showing skeleton.
        binding.attendanceRefresh.setupPullToRefresh { refreshAllData(showSkeleton = false, forceRefresh = true) }

        // Default range = current calendar month (1st → today), so My
        // Attendance opens on month-to-date only and the header label reads
        // like "July 2026". Users can still pick any range via the filter.
        val cal = Calendar.getInstance()
        val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        filterToDate = ymd.format(cal.time)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        filterFromDate = ymd.format(cal.time)
        updateRangeLabel()

        binding.btnAttendanceFilter.setOnClickListener {
            showAdvancedFilters()
        }

        setFragmentResultListener(ATTENDANCE_FILTER_KEY) { _, bundle ->
            val state = AdvancedListFilterSheet.state(bundle) ?: return@setFragmentResultListener
            filterFromDate = state.fromDate.orEmpty()
            filterToDate = state.toDate.orEmpty()
            filterAttendanceStatus = state.value(KEY_STATUS)
            filterStaffId = state.value(KEY_STAFF)
            updateRangeLabel()
            refreshAllData(showSkeleton = true, forceRefresh = true)
        }

        // Rejection reason via the shared reject-with-reason bottom sheet
        // (same component as leaves/permissions) instead of a bare AlertDialog.
        setFragmentResultListener(REJECT_KEY_REQUEST) { _, b ->
            rejectRecord(
                b.getString(RejectWithReasonBottomSheet.KEY_ITEM_ID).orEmpty(),
                b.getString(RejectWithReasonBottomSheet.KEY_REASON).orEmpty(),
                isRequest = true,
            )
        }
        setFragmentResultListener(REJECT_KEY_REVIEW) { _, b ->
            rejectRecord(
                b.getString(RejectWithReasonBottomSheet.KEY_ITEM_ID).orEmpty(),
                b.getString(RejectWithReasonBottomSheet.KEY_REASON).orEmpty(),
                isRequest = false,
            )
        }

        // Tabs are role/hierarchy based (mirrors the web). Fetch whether the
        // caller is a reporting officer first so a manager who has a team but
        // no explicit approve permission still gets the Team tabs.
        applyGreenGradient(binding.tvTotalDays)
        applyGreenGradient(binding.tvTotalHours)
        viewLifecycleOwner.lifecycleScope.launch {
            isReportingOfficer = try {
                val resp = api.getAttendanceTeamScope(session.bearerToken)
                teamCount = resp.teamCount
                resp.hasTeam
            } catch (_: Exception) {
                false
            }
            if (_binding != null) buildAttendanceTabs()
        }
    }

    private fun buildAttendanceTabs() {
        val canApproveTeam = session.hasPermission("attendance.teamApprove") ||
            session.hasPermission("attendance.approve")
        val canViewAllAppr = session.hasPermission("attendance.viewAllApprovals")
        // `attendance.hrReview` is the dedicated IAM grant. Keep
        // viewAllApprovals as a compatibility fallback for existing roles
        // that received HR Review before the permission was split.
        val canHrReview = session.hasPermission("attendance.hrReview") ||
            session.hasPermission("attendance.viewAllApprovals")
        val canViewAll = session.hasPermission("attendance.viewAll")

        // (logical id, label). Order mirrors the existing screen.
        val logical = mutableListOf<Pair<Int, String>>()
        logical.add(0 to "My Attendance")
        if (canApproveTeam || isReportingOfficer) {
            logical.add(1 to "Team Attendance")
            logical.add(2 to "Team Approval")
        }
        if (canViewAllAppr) logical.add(3 to "All Approval")
        if (canHrReview) logical.add(4 to "HR Review")
        if (canViewAll) logical.add(5 to "All")

        visibleLogicalTabs = logical.map { it.first }
        if (activeTab !in visibleLogicalTabs) activeTab = 0
        val defaultPos = visibleLogicalTabs.indexOf(activeTab).coerceAtLeast(0)

        // A lone "My Attendance" tab is noise — staff with no team/approval
        // visibility get no tab bar at all, just their records.
        binding.tabLayout.visibility = if (logical.size > 1) View.VISIBLE else View.GONE

        binding.tabLayout.setTabs(
            logical.map { HorizontalTabLayout.Tab(it.second) },
            defaultSelection = defaultPos,
        )
        // Team Approval (2) and HR Review (4) both host the
        // Attendance/Requests sub-tab strip.
        if (activeTab == 2 || activeTab == 4) {
            binding.layoutSubTabs.visibility = View.VISIBLE
            updateSubTabStyles()
        } else {
            binding.layoutSubTabs.visibility = View.GONE
            activeSubTab = 0
        }
        binding.tabLayout.setOnTabSelectedListener(object : HorizontalTabLayout.OnTabSelectedListener {
            override fun onTabSelected(index: Int) {
                activeTab = visibleLogicalTabs.getOrElse(index) { 0 }
                filterStaffId = null
                binding.etSearch.text?.clear()
                binding.layoutSearch.visibility = View.GONE
                if (activeTab == 2 || activeTab == 4) {
                    binding.layoutSubTabs.visibility = View.VISIBLE
                    activeSubTab = 0
                    updateSubTabStyles()
                } else {
                    binding.layoutSubTabs.visibility = View.GONE
                    activeSubTab = 0
                }
                loadData()
            }
        })
        setupSubTabs()
        refreshAllData(showSkeleton = true)
    }

    /** Update the badge for a LOGICAL tab id, only if that tab is visible. */
    private fun updateBadgeFor(logical: Int, count: Int) {
        val pos = visibleLogicalTabs.indexOf(logical)
        if (pos >= 0) binding.tabLayout.updateBadge(pos, count)
    }

    private fun updateRangeLabel() {
        val parseFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val display = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        val from = runCatching { parseFmt.parse(filterFromDate) }.getOrNull()
        val to = runCatching { parseFmt.parse(filterToDate) }.getOrNull()
        binding.tvMonth.text = when {
            from != null && to != null && sameMonthYear(from, to) ->
                SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(from)
            from != null && to != null ->
                "${display.format(from)} – ${display.format(to)}"
            else -> ""
        }
    }

    private fun currentAttendanceFilterSource(): List<Any> = when (activeTab) {
        0 -> fillAbsentDays(cachedMyRecords, filterFromDate, filterToDate)
        1 -> cachedTeamAttendance
        2 -> if (activeSubTab == 1) teamApprovalRequestRows() else teamApprovalAttendanceRows()
        3 -> allApprovalAttendanceRows()
        4 -> if (activeSubTab == 0) hrReviewAttendanceRows() else hrReviewRequestRows()
        5 -> cachedAllAttendance
        else -> emptyList()
    }

    private fun showAdvancedFilters() {
        val source = currentAttendanceFilterSource()
        val statuses = source.mapNotNull(::attendanceStatus)
            .distinct().sorted().map { value ->
                AdvancedListFilterSheet.Option(value, humanizeAttendanceFilter(value))
            }
        val staff = if (activeTab == 0) emptyList() else source.mapNotNull { item ->
            (item as? AttendanceApprovalRecord)?.let { row ->
                val id = row.staffId?.takeIf(String::isNotBlank) ?: return@let null
                val label = row.staffName?.takeIf(String::isNotBlank)
                    ?: row.employeeId?.takeIf(String::isNotBlank)
                    ?: "Staff"
                AdvancedListFilterSheet.Option(id, label, row.employeeId)
            }
        }.distinctBy { it.value }.sortedBy { it.label.lowercase(Locale.US) }
        val categories = buildList {
            add(AdvancedListFilterSheet.Category(KEY_DATE, "Date range", dateRange = true))
            add(AdvancedListFilterSheet.Category(KEY_STATUS, "Status", statuses))
            if (staff.isNotEmpty()) add(AdvancedListFilterSheet.Category(KEY_STAFF, "Staff", staff))
        }
        val initial = AdvancedListFilterSheet.State(
            selected = buildMap {
                filterAttendanceStatus?.let { put(KEY_STATUS, setOf(it)) }
                filterStaffId?.let { put(KEY_STAFF, setOf(it)) }
            },
            fromDate = filterFromDate.ifBlank { null },
            toDate = filterToDate.ifBlank { null },
        )
        AdvancedListFilterSheet.newInstance(categories, initial, ATTENDANCE_FILTER_KEY).apply {
            countProvider = { state -> source.count { matchesAttendanceState(it, state) } }
        }.showOnce(parentFragmentManager, "attendance_advanced_filter")
    }

    private fun attendanceStatus(item: Any): String? = when (item) {
        is AttendanceRecord -> item.approvedAttendance?.takeIf(String::isNotBlank)
            ?: item.status?.takeIf(String::isNotBlank)
            ?: if (item.id == null) "absent" else null
        is AttendanceApprovalRecord -> item.approvedAttendance?.takeIf(String::isNotBlank)
            ?: item.status?.takeIf(String::isNotBlank)
        else -> null
    }?.trim()?.lowercase(Locale.US)?.replace('_', '-')

    private fun attendanceDate(item: Any): String? = when (item) {
        is AttendanceRecord -> item.date
        is AttendanceApprovalRecord -> item.date
        else -> null
    }

    private fun attendanceStaffId(item: Any): String? =
        (item as? AttendanceApprovalRecord)?.staffId

    private fun matchesAttendanceState(item: Any, state: AdvancedListFilterSheet.State): Boolean {
        val date = attendanceDate(item)
        return (state.fromDate.isNullOrBlank() || date == null || date >= state.fromDate) &&
            (state.toDate.isNullOrBlank() || date == null || date <= state.toDate) &&
            (state.value(KEY_STATUS) == null || attendanceStatus(item) == state.value(KEY_STATUS)) &&
            (state.value(KEY_STAFF) == null || attendanceStaffId(item) == state.value(KEY_STAFF))
    }

    private fun humanizeAttendanceFilter(value: String): String = value
        .split('_', '-')
        .joinToString(" ") { it.replaceFirstChar(Char::titlecase) }

    private fun sameMonthYear(a: Date, b: Date): Boolean {
        val ca = Calendar.getInstance().apply { time = a }
        val cb = Calendar.getInstance().apply { time = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.MONTH) == cb.get(Calendar.MONTH) &&
            ca.get(Calendar.DAY_OF_MONTH) == 1 &&
            cb.get(Calendar.DAY_OF_MONTH) == cb.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    private fun setupSubTabs() {
        binding.subTabAttendance.setOnClickListener {
            activeSubTab = 0
            updateSubTabStyles()
            loadData()
        }
        binding.subTabRequest.setOnClickListener {
            activeSubTab = 1
            updateSubTabStyles()
            loadData()
        }
    }

    private fun updateSubTabStyles() {
        val activeBg = ContextCompat.getDrawable(requireContext(), R.drawable.bg_review_tab_active)
        val inactiveBg = null
        val activeColor = Color.parseColor("#0B61CA")
        val inactiveColor = Color.parseColor("#667085")

        binding.subTabAttendance.background = if (activeSubTab == 0) activeBg else inactiveBg
        binding.subTabAttendance.setTextColor(if (activeSubTab == 0) activeColor else inactiveColor)

        binding.subTabRequest.background = if (activeSubTab == 1) activeBg else inactiveBg
        binding.subTabRequest.setTextColor(if (activeSubTab == 1) activeColor else inactiveColor)

        // The strip is shared by HR Review (tab 4) and Team Approval (tab 2);
        // counts come from whichever tab is hosting it.
        val attCount: Int
        val reqCount: Int
        if (activeTab == 2) {
            attCount = teamApprovalAttendanceRows().size
            reqCount = teamApprovalRequestRows().size
        } else {
            attCount = hrReviewAttendanceRows().size
            reqCount = hrReviewRequestRows().size
        }
        binding.subTabAttendance.text = String.format(Locale.US, "Attendance (%02d)", attCount)
        binding.subTabRequest.text = String.format(Locale.US, "Requests (%02d)", reqCount)
    }

    private fun refreshAllData(showSkeleton: Boolean = true, forceRefresh: Boolean = false) {
        if (forceRefresh) {
            cachedMyRecords = emptyList()
            cachedTeamAttendance = emptyList()
            cachedApprovals = emptyList()
            cachedTeamRequests = emptyList()
            cachedAllApprovals = emptyList()
            cachedHrReview = emptyList()
            cachedAllAttendance = emptyList()
            // The cursor belongs to the range we just discarded — carrying it
            // into a new range would append rows from the old one.
            allAttendanceCursor = null
            allAttendanceHasMore = false
            cachedFines = emptyList()
        }

        // Marks every tab whose cache is still empty as "loading" — a tab
        // switch mid-fetch shows the skeleton instead of a premature
        // "no data" empty state (renderCurrentTab checks this flag).
        isFetchingData = true

        if (showSkeleton) {
            SkeletonUtils.startSkeletonPulse(binding.skeletonContainer)
            binding.rvAttendance.visibility = View.GONE
            binding.emptyState.visibility = View.GONE
        }

        // Thread the active search into the All-tab fetch so the backend returns
        // the searched person's FULL date range, not the capped ~750 newest
        // company-wide rows (which, after a "Last month" filter, collapsed to
        // just the last few days).
        val allSearch = binding.etSearch.text?.toString()?.trim()?.ifBlank { null }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val token = session.bearerToken
                if (token.isBlank()) return@launch

                val myDeferred = async {
                    if (cachedMyRecords.isNotEmpty()) null
                    else runCatching {
                        api.getMyAttendance(
                            token,
                            filterFromDate,
                            filterToDate,
                            status = filterAttendanceStatus,
                            staffId = filterStaffId,
                            pageSize = 200,
                        )
                    }.getOrNull()
                }
                val teamDeferred = async {
                    if (cachedTeamAttendance.isNotEmpty()) null
                    else runCatching {
                        api.getTeamAttendance(
                            token,
                            filterFromDate,
                            filterToDate,
                            status = filterAttendanceStatus,
                            staffId = filterStaffId,
                            pageSize = 200,
                        )
                    }.getOrNull()
                }
                val approvalsDeferred = async {
                    if (cachedApprovals.isNotEmpty()) null
                    // Team Approval matches the web's My Team scope: only
                    // employees who report directly to the current officer.
                    else runCatching {
                        api.getPendingAttendanceApprovals(
                            token,
                            scope = "direct",
                            requests = true,
                            fromDate = filterFromDate,
                            toDate = filterToDate,
                            status = filterAttendanceStatus,
                            staffId = filterStaffId,
                            pageSize = 200,
                        )
                    }.getOrNull()
                }
                // Company-wide surfaces are HR-only — skip the guaranteed-403
                // fetches when the caller doesn't hold the permission.
                val canViewAllApprovals = session.hasPermission("attendance.viewAllApprovals")
                val canHrReview = session.hasPermission("attendance.hrReview") ||
                    canViewAllApprovals
                val allApprovalsDeferred = async {
                    if (cachedAllApprovals.isNotEmpty() || !canViewAllApprovals) null
                    else runCatching {
                        api.getPendingAttendanceApprovals(
                            token,
                            all = true,
                            fromDate = filterFromDate,
                            toDate = filterToDate,
                            status = filterAttendanceStatus,
                            staffId = filterStaffId,
                            pageSize = 200,
                        )
                    }.getOrNull()
                }
                val hrReviewDeferred = async {
                    if (cachedHrReview.isNotEmpty() || !canHrReview) null
                    else runCatching { api.getHrReview(token, ALL_TAB_FROM_DATE, ALL_TAB_TO_DATE) }.getOrNull()
                }
                val allDeferred = async {
                    // Honor the date filter (e.g. "Last month") AND any active
                    // search — search bypasses the backend's ~750-row cap and
                    // returns the full range for that person.
                    runCatching {
                        api.getAllAttendance(
                            token,
                            filterFromDate,
                            filterToDate,
                            search = allSearch,
                            status = filterAttendanceStatus,
                            staffId = filterStaffId,
                        )
                    }.getOrNull()
                }
                val finesDeferred = async {
                    runCatching { api.listFines(token, status = "active") }.getOrNull()
                }

                myDeferred.await()?.let { if (it.success) cachedMyRecords = it.records }
                // Offline-resilience for the personal "My Attendance" tab: on a
                // successful non-empty fetch, persist to disk; when the fetch
                // yielded nothing (offline / failure), hydrate the last-known
                // month from disk so the calendar shows real days instead of a
                // wall of synthesized "Absent".
                run {
                    val ctx = context?.applicationContext
                    val key = myAttnCacheKey()
                    if (ctx != null && key != null) {
                        if (cachedMyRecords.isNotEmpty()) {
                            LocalCache.put(ctx, key, cachedMyRecords, System.currentTimeMillis())
                        } else {
                            val cached: List<AttendanceRecord>? = LocalCache.get(
                                ctx, key, object : TypeToken<List<AttendanceRecord>>() {}.type,
                            )
                            if (!cached.isNullOrEmpty()) cachedMyRecords = cached
                        }
                    }
                }
                updateBadgeFor(0, cachedMyRecords.size)

                teamDeferred.await()?.let { if (it.success) cachedTeamAttendance = it.records }
                updateBadgeFor(1, cachedTeamAttendance.size)

                approvalsDeferred.await()?.let {
                    if (it.success) {
                        cachedApprovals = it.records
                        // orEmpty — a backend without the requests param yet
                        // returns no field at all (Gson → null).
                        cachedTeamRequests = it.requests.orEmpty()
                        teamRequestsSourced = it.requests != null
                        // Fallback while the backend predates ?requests=true:
                        // hr-review ALREADY returns the true request rows
                        // (attendanceRequests ids + requestStage), just
                        // company-wide — scope them to the caller's subtree
                        // using the Team Attendance staff set. Gated on
                        // attendance.approve (the hr-review route's gate);
                        // retires automatically once the server sends
                        // `requests`.
                        if (it.requests == null &&
                            session.hasPermission("attendance.approve")
                        ) {
                            val teamIds = cachedTeamAttendance
                                .mapNotNull { r -> r.staffId }
                                .toSet()
                            if (teamIds.isNotEmpty()) {
                                val hr = runCatching {
                                    api.getHrReview(token, ALL_TAB_FROM_DATE, ALL_TAB_TO_DATE)
                                }.getOrNull()
                                if (hr?.success == true) {
                                    cachedTeamRequests = hr.records.filter { row ->
                                        row.requestStage == "ro" && row.staffId in teamIds
                                    }
                                    // hr-review rows carry requestStage only
                                    // on true request rows — if none do, the
                                    // backend predates the field and we have
                                    // no real request feed.
                                    teamRequestsSourced =
                                        hr.records.any { row -> row.requestStage != null }
                                }
                            }
                        }
                    }
                }
                updateBadgeFor(
                    2,
                    teamApprovalAttendanceRows().size + teamApprovalRequestRows().size,
                )

                allApprovalsDeferred.await()?.let { if (it.success) cachedAllApprovals = it.records }
                updateBadgeFor(3, allApprovalAttendanceRows().size)

                hrReviewDeferred.await()?.let { if (it.success) cachedHrReview = it.records }
                updateBadgeFor(4, cachedHrReview.size)
                updateSubTabStyles()

                val allResp = allDeferred.await()
                if (allResp?.success == true) {
                    cachedAllAttendance = allResp.records
                    allAttendanceCursor = allResp.nextCursor
                    allAttendanceHasMore = allResp.hasMore && allResp.nextCursor != null
                    updateBadgeFor(5, cachedAllAttendance.size)
                }

                val finesResp = finesDeferred.await()
                if (finesResp != null) {
                    cachedFines = finesResp.fines ?: emptyList()
                }
            } catch (_: Exception) {} finally {
                isFetchingData = false
                if (_binding != null) {
                    SkeletonUtils.stopSkeletonPulse(binding.skeletonContainer)
                    binding.attendanceRefresh.isRefreshing = false
                    // Render whatever we have — real rows, or the tab's
                    // empty state when the fetch came back empty/failed.
                    renderCurrentTab()
                }
            }
        }
    }

    /** Cache key for the personal "My Attendance" tab, namespaced by staff and
     *  the active date range so different filters never overwrite each other. */
    private fun myAttnCacheKey(): String? {
        val staffId = session.staffId?.takeIf { it.isNotBlank() } ?: return null
        return "attn_my:$staffId:$filterFromDate:$filterToDate"
    }

    private fun renderCurrentTab() {
        // Tab data still on its way → skeleton, never a premature empty
        // state. The fetch's finally block re-renders when it lands.
        if (isFetchingData && activeTabBackingIsEmpty()) {
            binding.rvAttendance.visibility = View.GONE
            binding.emptyState.visibility = View.GONE
            SkeletonUtils.startSkeletonPulse(binding.skeletonContainer)
            return
        }
        SkeletonUtils.stopSkeletonPulse(binding.skeletonContainer)

        // Update My Attendance summary values
        if (activeTab == 0) {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val daysPresent = cachedMyRecords.count { r ->
                if (r.date == today) return@count false
                val av = r.approvedAttendance?.lowercase()
                when (av) {
                    "absent", "weekoff", "holiday",
                    "comp-off", "comp_off", "compoff", "compensatory" -> false
                    "present", "half-day" -> true
                    else -> (r.totalMinutes ?: 0) > 0
                }
            }
            val totalMinutes = cachedMyRecords.sumOf { it.totalMinutes ?: 0 }
            val totalHours = totalMinutes / 60
            val remainingMins = totalMinutes % 60

            binding.tvTotalDays.text = daysPresent.toString()
            binding.tvTotalHours.text = String.format(Locale.getDefault(), "%02d:%02d Hrs", totalHours, remainingMins)
        }

        when (activeTab) {
            0 -> renderRecords(fillAbsentDays(cachedMyRecords, filterFromDate, filterToDate), "")
            1 -> renderTeamAttendance(cachedTeamAttendance, showFines = false, "")
            2 -> renderApprovals(
                if (activeSubTab == 1) teamApprovalRequestRows()
                else teamApprovalAttendanceRows(),
                ""
            )
            3 -> renderApprovals(allApprovalAttendanceRows(), "")
            4 -> {
                val source = if (activeSubTab == 0) hrReviewAttendanceRows()
                else hrReviewRequestRows()
                renderApprovals(source, "")
            }
            5 -> renderTeamAttendance(cachedAllAttendance, showFines = true, "")
        }

        filterCurrentListOnTyping(binding.etSearch.text?.toString().orEmpty())
    }

    /**
     * True when an attendance row is just the shadow of a pending
     * correction/remark REQUEST (the pending-approvals feed folds those in
     * with requestType set). They must not appear in the Attendance
     * approval lists — acting on the day directly while its request is
     * open is exactly the confusion the Requests tabs exist to avoid; the
     * request itself surfaces there with the proper review modal.
     */
    private fun isRequestLinked(r: AttendanceApprovalRecord): Boolean =
        r.requestType == "remarks" || r.requestType == "correction"

    /**
     * True when this record's `id` is an attendanceRequests doc (a correction /
     * remark request), so its approve/reject must route through the REQUEST
     * mutation (`isRequest = true`). Derived from the RECORD — never the active
     * tab — so a request approved from search / the All tab still routes
     * correctly instead of hitting the plain-attendance path (which 500s on a
     * request id). `requestStage` is the reliable id-type signal; requestType
     * is the same predicate the Requests sub-tabs are built from.
     */
    private fun isRequestRecord(r: AttendanceApprovalRecord): Boolean =
        isRequestLinked(r) || !r.requestStage.isNullOrBlank()

    /** staff|date key, or null when the row has no staffId (never collapsed). */
    private fun staffDateKey(r: AttendanceApprovalRecord): String? {
        val staff = r.staffId?.trim().orEmpty()
        if (staff.isBlank()) return null
        return "$staff|${r.date.orEmpty()}"
    }

    /** staff|date keys of every pending correction/remark request in [rows]. */
    private fun requestStaffDateKeys(rows: List<AttendanceApprovalRecord>): Set<String> =
        rows.filter(::isRequestLinked).mapNotNull(::staffDateKey).toSet()

    /**
     * Team Approval · Attendance rows. Correction/remarks rows are ALWAYS
     * excluded.
     *
     * This used to be gated on [teamRequestsSourced], the idea being not to
     * hide request rows until a real Requests feed existed to hold them. But a
     * backend that predates `?requests=true` then left them sitting in the
     * Attendance list, where they render as attendance approvals that don't
     * exist — tapping one opened the attendance review, while Approve opened
     * the time-correction dialog. Deriving the Requests feed locally (see
     * [teamApprovalRequestRows]) covers that case properly.
     */
    private fun teamApprovalAttendanceRows(): List<AttendanceApprovalRecord> {
        // Also hide plain attendance rows whose staff+date already has a pending
        // correction request — the day must surface once, on the Requests tab,
        // not as both an approval AND a correction.
        val requestKeys = requestStaffDateKeys(cachedApprovals) +
            (if (teamRequestsSourced) cachedTeamRequests.mapNotNull(::staffDateKey).toSet()
            else emptySet())
        return cachedApprovals.filter { r ->
            if (isRequestLinked(r)) return@filter false
            val key = staffDateKey(r)
            key == null || key !in requestKeys
        }
    }

    /**
     * Team Approval · Requests rows: the server feed when the backend supplies
     * one, otherwise the request rows carried inside the approvals feed.
     */
    private fun teamApprovalRequestRows(): List<AttendanceApprovalRecord> =
        dedupeRequestRows(
            if (teamRequestsSourced) cachedTeamRequests
            else cachedApprovals.filter(::isRequestLinked)
        )

    /**
     * All Approval tab rows: plain attendance approvals only, with days that
     * already have a pending correction request removed (the correction is
     * actioned on the HR Review · Requests tab, so the day never appears as
     * both an approval and a correction).
     */
    private fun allApprovalAttendanceRows(): List<AttendanceApprovalRecord> {
        val requestKeys = requestStaffDateKeys(cachedAllApprovals)
        return cachedAllApprovals.filter { r ->
            if (isRequestLinked(r)) return@filter false
            val key = staffDateKey(r)
            key == null || key !in requestKeys
        }
    }

    /**
     * HR Review · Attendance sub-tab rows (non-request punch records), with any
     * row whose staff+date already has a pending correction request removed so
     * a day never shows as both an attendance approval AND a correction.
     */
    private fun hrReviewAttendanceRows(): List<AttendanceApprovalRecord> {
        val requestKeys = requestStaffDateKeys(cachedHrReview)
        return cachedHrReview.filter { r ->
            if (isRequestLinked(r)) return@filter false
            val key = staffDateKey(r)
            key == null || key !in requestKeys
        }
    }

    /** HR Review · Requests sub-tab rows, deduped (see [dedupeRequestRows]). */
    private fun hrReviewRequestRows(): List<AttendanceApprovalRecord> =
        dedupeRequestRows(
            cachedHrReview.filter {
                it.requestType == "remarks" || it.requestType == "correction"
            }
        )

    /**
     * Collapse duplicate request rows for the SAME staff + date. A single
     * correction/remark can surface twice in the pending feed — the real
     * attendanceRequests doc PLUS a biometric "shadow" of the same day —
     * which showed as two identical cards for one person. Keep the one
     * that's actually actionable: prefer a true request row (requestStage
     * set), then one that carries the reason, else the first seen. Rows with
     * no staffId are never collapsed (keyed by their own id) so we can't
     * accidentally merge unrelated records.
     */
    private fun dedupeRequestRows(
        rows: List<AttendanceApprovalRecord>,
    ): List<AttendanceApprovalRecord> {
        fun score(r: AttendanceApprovalRecord): Int =
            (if (!r.requestStage.isNullOrBlank()) 2 else 0) +
                (if (!r.requestReason.isNullOrBlank()) 1 else 0)

        val byKey = LinkedHashMap<String, AttendanceApprovalRecord>()
        for (r in rows) {
            val staff = r.staffId?.trim().orEmpty()
            val key = if (staff.isBlank()) "id:${r.id}" else "$staff|${r.date.orEmpty()}"
            val existing = byKey[key]
            byKey[key] = when {
                existing == null -> r
                score(r) > score(existing) -> r
                else -> existing
            }
        }
        return byKey.values.toList()
    }

    /** Whether the ACTIVE tab's backing cache has nothing to show yet. */
    private fun activeTabBackingIsEmpty(): Boolean = when (activeTab) {
        0 -> cachedMyRecords.isEmpty()
        1 -> cachedTeamAttendance.isEmpty()
        2 -> cachedApprovals.isEmpty() && cachedTeamRequests.isEmpty()
        3 -> cachedAllApprovals.isEmpty()
        4 -> cachedHrReview.isEmpty()
        5 -> cachedAllAttendance.isEmpty()
        else -> true
    }

    private fun loadData() {
        renderCurrentTab()
    }

    /**
     * All-tab server search: fetches the searched staff's FULL date range via
     * the backend's per-staff index scan. The default (blank) feed is the
     * newest ~750 rows across the whole company — a few days — so local
     * filtering alone silently missed a person's older records.
     */
    private fun scheduleAllTabServerSearch(query: String) {
        allTabSearchJob?.cancel()
        allTabSearchJob = viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(400)
            val resp = runCatching {
                api.getAllAttendance(
                    session.bearerToken,
                    filterFromDate,
                    filterToDate,
                    search = query.trim().ifBlank { null },
                )
            }.getOrNull() ?: return@launch
            if (_binding == null || !resp.success) return@launch
            cachedAllAttendance = resp.records
            allAttendanceCursor = resp.nextCursor
            allAttendanceHasMore = resp.hasMore && resp.nextCursor != null
            if (activeTab == 5) renderCurrentTab()
        }
    }

    /**
     * Pull the next page of company-wide attendance once the user scrolls to
     * the end of what we hold. No-ops unless the backend said more rows exist,
     * so a fully loaded range costs nothing however far the user scrolls.
     */
    private fun loadNextAllAttendancePage() {
        if (activeTab != 5 || !allAttendanceHasMore || loadingAllAttendancePage) return
        val cursor = allAttendanceCursor ?: return
        loadingAllAttendancePage = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = runCatching {
                    api.getAllAttendance(
                        session.bearerToken,
                        filterFromDate,
                        filterToDate,
                        search = binding.etSearch.text?.toString()?.trim()?.ifBlank { null },
                        cursor = cursor,
                    )
                }.getOrNull()
                if (_binding == null || resp?.success != true) return@launch
                // Append only rows we don't already hold. Pages don't overlap,
                // but a punch written between two requests could shift the
                // boundary, and a duplicate day is more visible than a gap.
                val seen = cachedAllAttendance.mapNotNull { it.id }.toHashSet()
                val fresh = resp.records.filter { it.id == null || seen.add(it.id) }
                cachedAllAttendance = cachedAllAttendance + fresh
                allAttendanceCursor = resp.nextCursor
                allAttendanceHasMore = resp.hasMore && resp.nextCursor != null
                updateBadgeFor(5, cachedAllAttendance.size)
                if (activeTab == 5) renderCurrentTab()
            } finally {
                loadingAllAttendancePage = false
            }
        }
    }

    private fun showEmptyState(title: String, desc: String, imageRes: Int) {
        binding.emptyState.visibility = View.VISIBLE
        binding.emptyState.setEmptyState(imageRes, title, desc)
    }

    /**
     * Merge the real attendance rows with synthetic "Absent" rows for every
     * past day in [fromDate, toDate] that has no record — the days the staff
     * never punched. Newest day first. Today is skipped when it has no row
     * yet (the day is still running, not absent). Synthetic rows carry no
     * id, which the edit sheet already treats as a no-punch day.
     */
    private fun fillAbsentDays(
        records: List<AttendanceRecord>,
        fromDate: String,
        toDate: String,
    ): List<AttendanceRecord> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val from = runCatching { fmt.parse(fromDate) }.getOrNull() ?: return records
        var to = runCatching { fmt.parse(toDate) }.getOrNull() ?: return records
        val today = runCatching { fmt.parse(fmt.format(Date())) }.getOrNull() ?: Date()
        if (to.after(today)) to = today
        if (to.before(from)) return records

        val byDate = records.associateBy { it.date }
        val out = mutableListOf<AttendanceRecord>()
        val cal = Calendar.getInstance().apply { time = to }
        while (!cal.time.before(from)) {
            val dStr = fmt.format(cal.time)
            val existing = byDate[dStr]
            when {
                existing != null -> out.add(existing)
                cal.time.before(today) -> out.add(
                    AttendanceRecord(
                        id = null,
                        date = dStr,
                        status = null,
                        totalMinutes = 0,
                        approvedAttendance = null,
                    )
                )
                // Today with no row yet → still running, not absent → skip.
            }
            cal.add(Calendar.DAY_OF_MONTH, -1)
        }
        return out
    }

    private fun renderRecords(records: List<AttendanceRecord>, cacheKey: String) {
        if (records.isEmpty()) {
            showEmptyState(
                title = "No attendance records",
                desc = "Your attendance history for this period is empty.",
                imageRes = R.drawable.ic_leave_empty
            )
            binding.rvAttendance.visibility = View.GONE
            attendanceAdapter.submitList(emptyList())
            return
        }
        binding.emptyState.visibility = View.GONE
        binding.rvAttendance.visibility = View.VISIBLE
        attendanceAdapter.submitList(records)
    }

    private fun bindRecordCard(card: View, record: AttendanceRecord, parseFmt: SimpleDateFormat, dateFmt: SimpleDateFormat) {
            val searchParts = listOf(
                record.date ?: "",
                record.approvedAttendance ?: "",
                if (record.id == null) "absent" else ""
            )
            card.tag = searchParts.joinToString(" ").lowercase(Locale.US)

            val parsed = record.date?.let { runCatching { parseFmt.parse(it) }.getOrNull() }
            card.findViewById<TextView>(R.id.tvHistoryItemDate).text =
                parsed?.let { dateFmt.format(it) } ?: (record.date ?: "")

            // Total worked hours. Blank ("—") when the backend hasn't
            // surfaced a total — today's row before the midnight finalize
            // (a mobile clock-out no longer closes/totals the day) and
            // absent days both come through as null/0. Mirrors the web.
            val mins = record.totalMinutes ?: 0
            card.findViewById<TextView>(R.id.tvHistoryItemHours).text =
                if (mins > 0) String.format(Locale.getDefault(), "%02d:%02d:00 hrs", mins / 60, mins % 60)
                else "—"

            // Punch-out value mirrors the web table: prefer the
            // server-derived `punchOutTime` (the backend fills this from
            // the last touch for any record with ≥ 2 punch events), then
            // fall back to "Not Punched Out" when there's still an open
            // session (single-touch day), then "--" for truly empty rows.
            val firstIn = record.punchInTime ?: record.sessions?.firstOrNull()?.punchInTime
            val inLabel = firstIn?.let(::formatIsoTime) ?: "--"
            val resolvedOut = record.punchOutTime ?: record.sessions?.lastOrNull()?.punchOutTime
            // Three-state label for the "Clock in & Out" column:
            //   - resolvedOut set → render the time.
            //   - currently clocked in (hasOpenSession) → "---".
            //   - PENDING row with a punch-in but no out → "Not Punched
            //     Out". Once HR finalises (status flips off pending),
            //     drop to "--" — calling a closed Present row
            //     "Not Punched Out" is self-contradictory.
            //   - otherwise → "--".
            val outLabel = when {
                resolvedOut != null -> formatIsoTime(resolvedOut)
                record.hasOpenSession == true -> "---"
                firstIn != null -> "Not Punched Out"
                else -> "--"
            }
            card.findViewById<TextView>(R.id.tvHistoryItemRange).text =
                "$inLabel · $outLabel"

            // Present (HR-approved) / Absent (zero-worked) pill on each
            // past-day card. Today's row stays unbadged because the day
            // is still running.
            AttendanceStatusBadge.bind(
                card.findViewById(R.id.tvHistoryItemStatus),
                record,
            )
            bindPenaltyNotice(card, record.resolvedPenaltyReason())

            // Open the punch-event log sheet on tap — mirrors the web
            // popup that lists every individual IN/OUT event with its
            // source chip and time.
            card.setOnClickListener {
                AttendancePunchLogSheet
                    .newInstance(record)
                    .showOnce(parentFragmentManager, "attendance_punch_log")
            }

            // Attendance remark + time-correction feature removed: no edit icon
            // and no "remark submitted" badge on history cards.
            card.findViewById<ImageView>(R.id.btnHistoryItemEdit).visibility = View.GONE
            card.findViewById<View>(R.id.badgeRemarkSubmitted).visibility = View.GONE

            // Fines banner
            val llFinesBanner = card.findViewById<View>(R.id.llFinesBanner)
            val tvLateText = card.findViewById<TextView>(R.id.tvLateText)
            val tvFineAmount = card.findViewById<TextView>(R.id.tvFineAmount)

            // Fines banner is driven by real backend data or client-side calculation
            val lateMins = record.lateMinutes ?: 0
            val earlyOutMins = calculateEarlyOutMinutes(record)
            val totalLateMins = lateMins + earlyOutMins
            val fine = record.lateFineDeduction ?: record.fineAmount
            if (totalLateMins > 0 && fine != null && fine > 0) {
                llFinesBanner.visibility = View.VISIBLE
                tvLateText.text = "Late by ${totalLateMins}mins"
                tvFineAmount.text = "Fine : ₹${fine.toInt()}"
            } else {
                llFinesBanner.visibility = View.GONE
            }

            // Other fines — manual HR deductions attributed to this
            // day's date. Inflate one blue row per entry beneath the
            // late-fine banner so the staff sees each deduction (loss
            // of property, indiscipline, etc.) as its own line item
            // matching the iOS UX.
            renderOtherFines(card, record.otherFines.orEmpty())

            // Decision footer — surfaces "Approved/Rejected at <date>
            // By <approver>" on terminal rows. Mirrors the leaves
            // history card's footer so the two surfaces feel like one
            // feature. auto-approved rows skip the footer because
            // there's no human approver to credit.
            bindDecisionFooter(card, record)
    }

    /**
     * Inflate one row per HR-logged "Other Fine" into the attendance
     * card's vertical container. Each row mirrors the late-fine banner
     * styling but uses the blue bg_other_fine_banner drawable so the
     * staff can distinguish a punctuality penalty from a manual HR
     * deduction at a glance. The container hides itself when no fines
     * landed on this date.
     */
    private fun renderOtherFines(
        card: View,
        fines: List<com.manjugroups.m_connect.network.OtherFineData>,
    ) {
        val container = card.findViewById<android.widget.LinearLayout>(R.id.llOtherFinesContainer)
        container.removeAllViews()
        val visible = fines.filter { (it.amount ?: 0.0) > 0 }
        if (visible.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        val density = resources.displayMetrics.density
        val topMarginPx = (8 * density).toInt()
        val padHPx = (12 * density).toInt()
        val padVPx = (8 * density).toInt()
        val iconPx = (14 * density).toInt()
        val textMarginPx = (6 * density).toInt()
        val amountMarginPx = (4 * density).toInt()
        val blue = android.graphics.Color.parseColor("#0B61CA")
        for (fine in visible) {
            val block = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(padHPx, padVPx, padHPx, padVPx)
                setBackgroundResource(R.drawable.bg_other_fine_banner)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = topMarginPx }
            }
            val row = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            val icon = android.widget.ImageView(requireContext()).apply {
                setImageResource(R.drawable.ic_clock)
                imageTintList = android.content.res.ColorStateList.valueOf(blue)
                layoutParams = android.widget.LinearLayout.LayoutParams(iconPx, iconPx)
            }
            val label = TextView(requireContext()).apply {
                text = fine.typeName?.takeIf { it.isNotBlank() } ?: "Other Fine"
                setTextColor(blue)
                textSize = 12f
                typeface = androidx.core.content.res.ResourcesCompat.getFont(
                    requireContext(), R.font.inter_medium,
                )
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply { marginStart = textMarginPx }
            }
            val receipt = android.widget.ImageView(requireContext()).apply {
                setImageResource(R.drawable.ic_receipt_red)
                imageTintList = android.content.res.ColorStateList.valueOf(blue)
                layoutParams = android.widget.LinearLayout.LayoutParams(iconPx, iconPx)
            }
            val amount = TextView(requireContext()).apply {
                text = "Fine : ₹${(fine.amount ?: 0.0).toInt()}"
                setTextColor(blue)
                textSize = 12f
                typeface = androidx.core.content.res.ResourcesCompat.getFont(
                    requireContext(), R.font.inter_medium,
                )
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = amountMarginPx }
            }
            row.addView(icon)
            row.addView(label)
            row.addView(receipt)
            row.addView(amount)
            block.addView(row)
            // The reason HR entered on the web (loss of property, indiscipline,
            // late warning…) — shown beneath the fine so the staff sees WHY.
            fine.notes?.trim()?.takeUnless { it.isEmpty() }?.let { reason ->
                block.addView(TextView(requireContext()).apply {
                    text = reason
                    setTextColor(android.graphics.Color.parseColor("#5B7FA6"))
                    textSize = 11f
                    typeface = androidx.core.content.res.ResourcesCompat.getFont(
                        requireContext(), R.font.inter_regular,
                    )
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = (3 * density).toInt()
                        marginStart = iconPx + textMarginPx
                    }
                })
            }
            container.addView(block)
        }
    }

    private fun bindDecisionFooter(card: View, record: AttendanceRecord) {
        val row = card.findViewById<View>(R.id.historyItemDecisionRow)
        val status = record.status?.lowercase(Locale.US).orEmpty()
        val isApproved = status == "approved"
        val isRejected = status == "rejected"
        if (!isApproved && !isRejected) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE

        val icon = card.findViewById<ImageView>(R.id.ivHistoryItemDecisionIcon)
        val text = card.findViewById<TextView>(R.id.tvHistoryItemDecisionText)
        val verb: String
        if (isApproved) {
            icon.setImageResource(R.drawable.ic_leave_status_approved)
            text.setTextColor(android.graphics.Color.parseColor("#169B2F"))
            verb = "Approved"
        } else {
            icon.setImageResource(R.drawable.ic_leave_status_rejected)
            text.setTextColor(android.graphics.Color.parseColor("#B42318"))
            verb = "Rejected"
        }
        val decidedDate = parseIsoOrEpoch(record.decidedAt)
        val decidedLabel = decidedDate?.let {
            SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(it)
        }
        text.text = if (decidedLabel != null) "$verb at $decidedLabel" else verb

        val approverName = record.approverName?.trim().orEmpty().ifBlank { "HR" }
        val nameView = card.findViewById<TextView>(R.id.tvHistoryItemApproverName)
        val initialView = card.findViewById<TextView>(R.id.tvHistoryItemApproverInitial)
        val photoView = card.findViewById<ImageView>(R.id.ivHistoryItemApproverPhoto)
        nameView.text = approverName
        initialView.text = approverName.firstOrNull { it.isLetterOrDigit() }
            ?.uppercaseChar()?.toString() ?: "?"

        val photoUrl = record.approverPhotoUrl?.takeIf { it.isNotBlank() }
        if (photoUrl != null) {
            photoView.visibility = View.VISIBLE
            initialView.visibility = View.INVISIBLE
            photoView.load(photoUrl) {
                crossfade(true)
                transformations(CircleCropTransformation())
            }
        } else {
            photoView.visibility = View.GONE
            photoView.setImageDrawable(null)
            initialView.visibility = View.VISIBLE
        }
    }

    /**
     * ISO date or numeric-epoch string → Date. Mirrors the helper in
     * LeavesFragment so both surfaces parse decidedAt identically.
     */
    private fun parseIsoOrEpoch(raw: String?): Date? {
        if (raw.isNullOrBlank()) return null
        raw.toDoubleOrNull()?.let { epoch ->
            val millis = when {
                epoch > 1_000_000_000_000 -> epoch.toLong()
                epoch > 1_000_000_000 -> (epoch * 1000).toLong()
                else -> epoch.toLong()
            }
            return runCatching { Date(millis) }.getOrNull()
        }
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
        )
        for (pattern in patterns) {
            runCatching {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                if (pattern.endsWith("'Z'")) {
                    fmt.timeZone = TimeZone.getTimeZone("UTC")
                }
                fmt.parse(raw)?.let { return it }
            }
        }
        return null
    }

    /**
     * Two-step confirm → cancel flow. Mirrors the leaves cancel UX in
     * LeavesFragment so the user gets the same affordance on both
     * surfaces. On success we reload — the row disappears (delete) or
     * flips status per backend semantics.
     */
    private fun confirmAndCancelAttendance(record: AttendanceRecord) {
        val date = record.date ?: return
        AlertDialog.Builder(requireContext())
            .setTitle("Withdraw attendance?")
            .setMessage(
                "This will remove your submitted attendance for $date. " +
                    "You can punch in again after.",
            )
            .setPositiveButton("Withdraw") { _, _ -> cancelAttendance(date) }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun cancelAttendance(date: String) {
        val token = session.bearerToken
        if (token.isBlank()) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.cancelMyAttendance(token, AttendanceCancelRequest(date))
                if (resp.success) {
                    Toast.makeText(
                        requireContext(),
                        "Attendance withdrawn",
                        Toast.LENGTH_SHORT,
                    ).show()
                    loadData()
                } else {
                    Toast.makeText(
                        requireContext(),
                        resp.error ?: "Failed to withdraw attendance",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (e: Exception) {
                val serverMessage = extractHttpErrorMessage(e)
                Toast.makeText(
                    requireContext(),
                    serverMessage ?: e.message ?: "Network error",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /**
     * Pull the {error: "..."} field out of a Retrofit HttpException's
     * response body so the toast shows the actual server message
     * ("Cannot delete approved attendance…") instead of "HTTP 500".
     */
    private fun extractHttpErrorMessage(e: Throwable): String? {
        val httpEx = e as? retrofit2.HttpException ?: return null
        val raw = runCatching { httpEx.response()?.errorBody()?.string() }.getOrNull()
            ?: return null
        return runCatching {
            val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject
            (obj.get("error")?.asString ?: obj.get("message")?.asString)
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun formatIsoTime(iso: String): String {
        val millis = parseIsoMillis(iso) ?: return "--"
        return SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(millis))
    }

    private fun parseIsoMillis(iso: String): Long? {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
        )
        for (pattern in patterns) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                if (pattern.endsWith("'Z'")) {
                    fmt.timeZone = TimeZone.getTimeZone("UTC")
                }
                return fmt.parse(iso)?.time
            } catch (_: Exception) {
                // try next format
            }
        }
        return null
    }

    private fun resolveColor(attr: Int): Int {
        val tv = TypedValue()
        requireContext().theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    override fun onResume() {
        super.onResume()
        // White system status bar with dark icons to match the white in-app header.
        (activity as? MainActivity)?.setTopBarAppearance(Color.WHITE, true)
        (activity as? MainActivity)?.setTabBarVisible(false)
    }

    override fun onPause() {
        // Restore the default tab top-bar look for sibling tabs.
        (activity as? MainActivity)?.setTopBarAppearance(
            Color.parseColor("#FEFEFE"), true
        )
        super.onPause()
    }

    private fun applyGreenGradient(textView: TextView) {
        textView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val height = textView.height.toFloat()
            if (height > 0 && textView.paint.shader == null) {
                val textShader = android.graphics.LinearGradient(
                    0f, 0f, 0f, height,
                    android.graphics.Color.parseColor("#1BCA0B"),
                    android.graphics.Color.parseColor("#3D9D02"),
                    android.graphics.Shader.TileMode.CLAMP
                )
                textView.paint.shader = textShader
                textView.invalidate()
            }
        }
    }

    private fun calculateEarlyOutMinutes(record: AttendanceRecord): Int {
        val fine = record.lateFineDeduction ?: record.fineAmount ?: 0.0
        if (fine <= 0) return 0

        val punchOut = record.punchOutTime ?: record.sessions?.lastOrNull()?.punchOutTime
        if (punchOut != null) {
            val millis = parseIsoMillis(punchOut)
            if (millis != null) {
                try {
                    val cal = Calendar.getInstance().apply { timeInMillis = millis }
                    val expectedEndMinutes = 18 * 60 + 30 // 18:30 (06:30 PM)
                    val hour = cal.get(Calendar.HOUR_OF_DAY)
                    val minute = cal.get(Calendar.MINUTE)
                    val punchOutMinutes = hour * 60 + minute
                    
                    if (punchOutMinutes < expectedEndMinutes) {
                        return expectedEndMinutes - punchOutMinutes
                    }
                } catch (_: Exception) {}
            }
        }
        return record.earlyOutMinutes ?: record.earlyMinutes ?: record.earlyOut ?: 0
    }

    private fun formatDateLabel(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(raw) ?: return null
            SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(parsed)
        }.getOrNull()
    }

    private fun formatTime(iso: String?): String? {
        if (iso.isNullOrBlank()) return null
        val millis = parseIsoMillis(iso) ?: return null
        return SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(millis))
    }

    private fun formatDuration(totalMinutes: Int): String {
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }

    private fun renderApprovals(approvals: List<AttendanceApprovalRecord>, cacheKey: String) {
        if (approvals.isEmpty()) {
            val noTeam = teamCount == 0 && activeTab == 2
            showEmptyState(
                title = if (noTeam) "No team members" else "No attendance to review",
                desc = if (noTeam) "You don't have any team members reporting to you yet."
                else "Pending punches from your team will land here.",
                imageRes = R.drawable.ic_leave_empty
            )
            binding.rvAttendance.visibility = View.GONE
            attendanceAdapter.submitList(emptyList())
            return
        }
        binding.emptyState.visibility = View.GONE
        binding.rvAttendance.visibility = View.VISIBLE
        attendanceAdapter.submitList(approvals)
    }

    private fun bindApprovalCard(card: View, record: AttendanceApprovalRecord) {
            val searchParts = listOf(
                record.staffName ?: "",
                record.designation ?: "",
                record.employeeId ?: "",
                record.source ?: "",
                record.date ?: "",
                record.approvedAttendance ?: ""
            )
            card.tag = searchParts.joinToString(" ").lowercase(Locale.US)

            val staffName = record.staffName?.trim().orEmpty().ifBlank { "Staff" }
            card.findViewById<TextView>(R.id.tvAttStaffName).text = staffName

            card.findViewById<TextView>(R.id.tvAttDate).text =
                formatDateLabel(record.date) ?: (record.date ?: "—")

            val inLabel = formatTime(record.punchInTime) ?: "--"
            val outLabel = formatTime(record.punchOutTime) ?: "--"
            card.findViewById<TextView>(R.id.tvAttPunchOut).text = "$inLabel — $outLabel"

            card.findViewById<TextView>(R.id.tvAttDuration).text =
                record.totalMinutes?.let { formatDuration(it) } ?: "—"

            card.findViewById<ImageView>(R.id.ivAttStaffAvatar)
                .loadUserAvatar(record.staffPhotoUrl, record.staffName)

            val btnReject = card.findViewById<View>(R.id.btnRejectAttendance)
            val btnApprove = card.findViewById<View>(R.id.btnApproveAttendance)
            val btnHrReview = card.findViewById<View>(R.id.btnHrReviewAction)

            // Every approval decision goes through the Review Attendance
            // Request modal — recorded punches, the employee's requested
            // correction + notes, and Absent / Present / Reject — exactly the
            // web's review dialog. Rows on the Requests sub-tab are
            // attendanceRequests docs, so their decision routes through the
            // request mutations (isRequest).
            // Request rows (attendanceRequests ids → isRequest=true on
            // approve/reject) are detected from the RECORD, not the active tab,
            // so a correction request approved from search or the All tab still
            // routes through the request mutation instead of the plain
            // attendance approve (which 500s on a request id).
            val isRequestRow = isRequestRecord(record)
            val openReviewModal = View.OnClickListener {
                val sheet = ReviewAttendanceRequestBottomSheet.newInstance(record, object : ReviewAttendanceRequestBottomSheet.OnActionClickListener {
                    override fun onApprove(recordId: String, status: String) {
                        approveRecord(recordId, status, isRequestRow)
                    }
                    override fun onReject(recordId: String) {
                        showRejectDialog(recordId, isRequestRow)
                    }
                    override fun onCorrectTimes(
                        recordId: String,
                        correctedPunchIn: String?,
                        correctedPunchOut: String?,
                        reason: String?,
                    ) {
                        correctPunchTimes(recordId, correctedPunchIn, correctedPunchOut, reason)
                    }
                })
                sheet.showOnce(parentFragmentManager, "review_attendance_request")
            }
            // Card body opens the journey review (map + travel + timeline)
            // for context; request rows have no journey, so they open the
            // decision modal directly.
            val openCardDetail = if (isRequestRow) openReviewModal else View.OnClickListener {
                val sheet = AttendanceReviewBottomSheet.newInstance(record, object : AttendanceReviewBottomSheet.OnActionClickListener {
                    override fun onApprove(recordId: String, status: String) {
                        approveRecord(recordId, status)
                    }
                    override fun onReject(recordId: String) {
                        showRejectDialog(recordId)
                    }
                    override fun onHold(recordId: String) {
                        showHoldDialog(recordId)
                    }
                })
                sheet.showOnce(parentFragmentManager, "attendance_review")
            }

            // One button, one destination. Inline Approve/Reject used to open
            // the compact decision modal while tapping the card opened the full
            // journey review — two different forms for the same row, and the
            // button's own label promised a decision it didn't actually make
            // (it opened a dialog). A single "Actions" entry point that lands
            // on the same sheet as the card removes both surprises; Approve /
            // Reject / Hold all live inside that sheet.
            val actionLabel = card.findViewById<TextView>(R.id.tvHrReviewActionLabel)
            btnReject.visibility = View.GONE
            btnApprove.visibility = View.GONE
            btnHrReview.visibility = View.VISIBLE
            actionLabel?.text = if (isRequestRow) "HR Review" else "Actions"

            card.setOnClickListener(openCardDetail)
            btnHrReview.setOnClickListener(openCardDetail)
    }

    private fun showApproveDialog(id: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Approve as")
            .setItems(labels.toTypedArray()) { _, which ->
                approveRecord(id, values[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRejectDialog(id: String, isRequest: Boolean = false) {
        RejectWithReasonBottomSheet.newInstance(
            itemId = id,
            resultKey = if (isRequest) REJECT_KEY_REQUEST else REJECT_KEY_REVIEW,
            title = "Reject attendance",
            subtitle = "Add a reason for rejecting this attendance",
            buttonText = "Reject",
        ).showOnce(parentFragmentManager, "reject_attendance")
    }

    private fun showHoldDialog(id: String) {
        val input = EditText(requireContext()).apply {
            hint = "Reason for holding"
            minLines = 3
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Hold attendance")
            .setView(input)
            .setPositiveButton("Hold") { _, _ ->
                val reason = input.text?.toString()?.trim().orEmpty().ifBlank { "On hold" }
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val resp = api.holdAttendance(session.bearerToken, RejectRequest(id, reason))
                        if (resp.success) {
                            Toast.makeText(requireContext(), "Put on hold", Toast.LENGTH_SHORT).show()
                            refreshAllData(showSkeleton = false, forceRefresh = true)
                        } else {
                            Toast.makeText(requireContext(), resp.error ?: "Failed to hold", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun approveRecord(id: String, approvedAttendance: String, isRequest: Boolean = false) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.approveAttendance(
                    session.bearerToken,
                    ApproveAttendanceRequest(id, approvedAttendance, isRequest = if (isRequest) true else null)
                )
                if (resp.success) {
                    Toast.makeText(requireContext(), "Approved successfully", Toast.LENGTH_SHORT).show()
                    refreshAllData(showSkeleton = false, forceRefresh = true)
                } else {
                    Toast.makeText(requireContext(), "Failed to approve", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun rejectRecord(id: String, reason: String, isRequest: Boolean = false) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.rejectAttendance(
                    session.bearerToken,
                    RejectRequest(id, reason, isRequest = if (isRequest) true else null)
                )
                if (resp.success) {
                    Toast.makeText(requireContext(), "Rejected successfully", Toast.LENGTH_SHORT).show()
                    refreshAllData(showSkeleton = false, forceRefresh = true)
                } else {
                    Toast.makeText(requireContext(), "Failed to reject", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun filterCurrentListOnTyping(query: String) {
        val queryLower = query.trim().lowercase(Locale.US)

        val originalList = currentAttendanceFilterSource().filter { item ->
            (filterAttendanceStatus == null || attendanceStatus(item) == filterAttendanceStatus) &&
                (filterStaffId == null || attendanceStaffId(item) == filterStaffId)
        }

        if (queryLower.isEmpty()) {
            submitPagedList(originalList, "")
            // Empty tab → keep the empty state the render function just
            // showed ("No team members", "No attendance to review", …).
            // Unconditionally hiding it here left empty tabs fully blank.
            if (originalList.isNotEmpty()) {
                binding.emptyState.visibility = View.GONE
                binding.rvAttendance.visibility = View.VISIBLE
            }
            return
        }

        val filtered = originalList.filter { item ->
            when (item) {
                is AttendanceRecord -> {
                    val searchParts = listOf(
                        item.date ?: "",
                        item.approvedAttendance ?: "",
                        if (item.id == null) "absent" else ""
                    )
                    searchParts.joinToString(" ").lowercase(Locale.US).contains(queryLower)
                }
                is AttendanceApprovalRecord -> {
                    val searchParts = listOf(
                        item.staffName ?: "",
                        item.designation ?: "",
                        item.employeeId ?: "",
                        item.source ?: "",
                        item.date ?: "",
                        item.approvedAttendance ?: ""
                    )
                    searchParts.joinToString(" ").lowercase(Locale.US).contains(queryLower)
                }
                else -> false
            }
        }

        if (filtered.isEmpty()) {
            binding.rvAttendance.visibility = View.GONE
            showEmptyState(
                title = "No search results",
                desc = "Try refining your search query.",
                imageRes = R.drawable.ic_leave_empty
            )
        } else {
            binding.emptyState.visibility = View.GONE
            binding.rvAttendance.visibility = View.VISIBLE
            submitPagedList(filtered, queryLower)
        }
    }

    /** Submit only the current scroll window (20, +20 near the end). */
    private fun submitPagedList(display: List<Any>, query: String) {
        attendanceFilteredCount = display.size
        val pageCtx =
            "$activeTab|$activeSubTab|$query|$filterFromDate|$filterToDate|" +
                "$filterAttendanceStatus|$filterStaffId"
        if (pageCtx != lastPageContext) {
            lastPageContext = pageCtx
            attendancePager.reset()
            binding.rvAttendance.scrollToPosition(0)
        }
        attendanceAdapter.submitList(display.take(attendancePager.limit))
    }

    private inner class AttendanceAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items: List<Any> = emptyList()

        fun submitList(newItems: List<Any>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int {
            val item = items[position]
            return when (item) {
                is AttendanceRecord -> 0
                is AttendanceApprovalRecord -> {
                    if (activeTab == 1 || activeTab == 5) 1 else 2
                }
                else -> 0
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val view = when (viewType) {
                0 -> inflater.inflate(R.layout.item_attendance_history_card, parent, false)
                1 -> inflater.inflate(R.layout.item_team_attendance_card, parent, false)
                else -> inflater.inflate(R.layout.item_team_approval_card, parent, false)
            }
            return SimpleViewHolder(view)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            val card = holder.itemView
            when (item) {
                is AttendanceRecord -> {
                    val parseFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    val dateFmt = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
                    bindRecordCard(card, item, parseFmt, dateFmt)
                }
                is AttendanceApprovalRecord -> {
                    if (getItemViewType(position) == 1) {
                        val showFines = (activeTab == 5)
                        bindTeamAttendanceCard(card, item, showFines)
                    } else {
                        bindApprovalCard(card, item)
                    }
                }
            }
        }

        override fun getItemCount(): Int = items.size

        private inner class SimpleViewHolder(view: View) : RecyclerView.ViewHolder(view)
    }

    private fun renderTeamAttendance(records: List<AttendanceApprovalRecord>, showFines: Boolean, cacheKey: String) {
        if (records.isEmpty()) {
            val noTeam = teamCount == 0 && activeTab == 1
            showEmptyState(
                title = if (noTeam) "No team members" else "No team attendance",
                desc = if (noTeam) "You don't have any team members reporting to you yet."
                else "No team attendance records for this period.",
                imageRes = R.drawable.ic_leave_empty
            )
            binding.rvAttendance.visibility = View.GONE
            attendanceAdapter.submitList(emptyList())
            return
        }
        binding.emptyState.visibility = View.GONE
        binding.rvAttendance.visibility = View.VISIBLE
        attendanceAdapter.submitList(records)
    }

    private fun bindTeamAttendanceCard(card: View, record: AttendanceApprovalRecord, showFines: Boolean) {
            val searchParts = listOf(
                record.staffName ?: "",
                record.designation ?: "",
                record.employeeId ?: "",
                record.source ?: "",
                record.date ?: "",
                record.approvedAttendance ?: ""
            )
            card.tag = searchParts.joinToString(" ").lowercase(Locale.US)

            card.findViewById<TextView>(R.id.tvHistoryItemDate).text =
                formatDateLabel(record.date) ?: (record.date ?: "—")

            val tvStatus = card.findViewById<TextView>(R.id.tvHistoryItemStatus)
            val tvHours = card.findViewById<TextView>(R.id.tvHistoryItemHours)
            val tvRange = card.findViewById<TextView>(R.id.tvHistoryItemRange)

            applyAttendanceStatus(tvStatus, record)

            val mins = record.totalMinutes ?: 0
            tvHours.text = String.format(Locale.getDefault(), "%02d:%02d:00 hrs", mins / 60, mins % 60)

            val inLabel = formatTime(record.punchInTime) ?: "--"
            val outLabel = formatTime(record.punchOutTime) ?: "--"
            tvRange.text = "$inLabel · $outLabel"

            // Attendance-fine banner — the REAL per-DAY late fine for THIS
            // record (server-computed lateFineDeduction), shown only when it's
            // actually > 0. Previously this matched cachedFines by staff name,
            // which smeared one active monthly fine onto every one of that
            // person's cards (the "₹10 on every date" bug for a staffer with
            // no fine). Now a day with no fine shows no banner.
            val fineBanner = card.findViewById<View>(R.id.llTeamFinesBanner)
            val tvFineAmount = card.findViewById<TextView>(R.id.tvTeamFineAmount)
            val tvFineText = card.findViewById<TextView>(R.id.tvTeamFineText)
            val fineAmt = (record.lateFineDeduction ?: record.fineAmount) ?: 0.0
            if (showFines && fineAmt > 0.0) {
                fineBanner.visibility = View.VISIBLE
                tvFineText.text = record.lateMinutes?.takeIf { it > 0 }
                    ?.let { "Late by ${it} min" }
                    ?: "Attendance fine"
                tvFineAmount.text = String.format(Locale.getDefault(), "Fine : ₹%.0f", fineAmt)
            } else {
                fineBanner.visibility = View.GONE
            }

            bindPenaltyNotice(card, record.resolvedPenaltyReason())

            // Name + employee id so two DIFFERENT staff who share a name (e.g.
            // duplicate "DHIVAGAR.B" accounts) are distinguishable — each row's
            // photo is correct for its own staffId; the id makes that clear.
            val name = record.staffName?.trim().orEmpty().ifBlank { "Staff Member" }
            val empId = record.employeeId?.trim().orEmpty()
            card.findViewById<TextView>(R.id.tvStaffName).text =
                if (empId.isNotBlank()) "$name · $empId" else name
            card.findViewById<ImageView>(R.id.ivStaffAvatar)
                .loadUserAvatar(record.staffPhotoUrl, record.staffName)
    }

    /** Colour + label the status pill from the record's approved bucket
     *  (falls back to Present when time was logged, else Pending). */
    private fun applyAttendanceStatus(tv: TextView, record: AttendanceApprovalRecord) {
        val bucket = record.approvedAttendance?.lowercase(Locale.US)
            ?: if ((record.totalMinutes ?: 0) > 0) "present" else null
        when (bucket) {
            "present" -> {
                tv.text = "Present"
                tv.setBackgroundResource(R.drawable.bg_pill_green_light)
                tv.setTextColor(Color.parseColor("#067647"))
            }
            "half-day" -> {
                tv.text = "Half Day"
                tv.setBackgroundResource(R.drawable.bg_pill_green_light)
                tv.setTextColor(Color.parseColor("#067647"))
            }
            "absent" -> {
                tv.text = if (record.hasAbsentPenalty()) "Absent · Penalty" else "Absent"
                tv.setBackgroundResource(R.drawable.bg_pill_red_light)
                tv.setTextColor(Color.parseColor("#B42318"))
            }
            "weekoff" -> {
                tv.text = "Week Off"
                tv.setBackgroundResource(R.drawable.bg_chip_inactive)
                tv.setTextColor(Color.parseColor("#475467"))
            }
            "holiday" -> {
                tv.text = "Holiday"
                tv.setBackgroundResource(R.drawable.bg_chip_inactive)
                tv.setTextColor(Color.parseColor("#475467"))
            }
            "comp-off", "comp_off", "compoff", "compensatory" -> {
                // Paid comp-off day — render neutrally, not as the "Pending"
                // fallback (which read as unresolved) or Absent.
                tv.text = "Comp Off"
                tv.setBackgroundResource(R.drawable.bg_chip_inactive)
                tv.setTextColor(Color.parseColor("#475467"))
            }
            else -> {
                tv.text = "Pending"
                tv.setBackgroundResource(R.drawable.bg_chip_inactive)
                tv.setTextColor(Color.parseColor("#475467"))
            }
        }
    }

    private fun bindPenaltyNotice(card: View, reason: String?) {
        val banner = card.findViewById<View>(R.id.llAttendancePenaltyBanner) ?: return
        val reasonView = card.findViewById<TextView>(R.id.tvAttendancePenaltyReason)
        if (reason.isNullOrBlank()) {
            banner.visibility = View.GONE
        } else {
            banner.visibility = View.VISIBLE
            reasonView.text = "Reason: $reason"
        }
    }

    override fun onDestroyView() {
        SkeletonUtils.stopAll()
        super.onDestroyView()
        _binding = null
    }

    /**
     * Save a reviewer's punch-time correction, then reload so the row shows the
     * corrected times rather than the stale ones.
     *
     * The backend owns the rules — it requires `attendance.correctPunchTimes`
     * and refuses a self-correction — so its message is surfaced verbatim
     * instead of being second-guessed here.
     */
    private fun correctPunchTimes(
        recordId: String,
        correctedPunchIn: String?,
        correctedPunchOut: String?,
        reason: String?,
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.correctAttendancePunchTimes(
                    session.bearerToken,
                    com.manjugroups.m_connect.network.CorrectPunchTimesRequest(
                        id = recordId,
                        correctedPunchIn = correctedPunchIn,
                        correctedPunchOut = correctedPunchOut,
                        reason = reason,
                    ),
                )
                if (resp.success) {
                    Toast.makeText(
                        requireContext(),
                        "Punch times corrected",
                        Toast.LENGTH_SHORT,
                    ).show()
                    loadData()
                } else {
                    Toast.makeText(
                        requireContext(),
                        resp.error ?: "Couldn't correct the punch times",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    e.message ?: "Couldn't correct the punch times",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    companion object {
        private const val ATTENDANCE_FILTER_KEY = "attendance_advanced_filter_result"
        private const val KEY_DATE = "date"
        private const val KEY_STATUS = "status"
        private const val KEY_STAFF = "staff"
    }

}
