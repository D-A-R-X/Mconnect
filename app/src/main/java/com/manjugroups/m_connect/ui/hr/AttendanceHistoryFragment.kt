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
import com.manjugroups.m_connect.ui.common.AvatarUtils.loadUserAvatar
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.AttendanceCancelRequest
import com.manjugroups.m_connect.network.AttendanceRecord
import com.manjugroups.m_connect.network.ApproveAttendanceRequest
import com.manjugroups.m_connect.network.RejectRequest
import com.manjugroups.m_connect.network.AttendanceApprovalRecord
import com.manjugroups.m_connect.ui.common.HorizontalTabLayout
import com.manjugroups.m_connect.ui.common.navigateUp
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import java.text.SimpleDateFormat
import java.util.*

class AttendanceHistoryFragment : Fragment() {

    private var _binding: FragmentAttendanceHistoryBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager
    private val api = ApiService.create()

    private var filterFromDate: String = ""
    private var filterToDate: String = ""
    // The "All" tab shows the entire attendance log, so it queries from an
    // all-time start date instead of the current filter window.
    private val ALL_TAB_FROM_DATE = "2000-01-01"
    // Role/hierarchy-based tab visibility (mirrors the web). Logical tab ids:
    // 0=My Attendance, 1=Team Attendance, 2=Team Approval, 3=All Approval,
    // 4=HR Review, 5=All. `visibleLogicalTabs` is the subset actually shown, in
    // order, so the visible tab position maps back to a logical id.
    private var isReportingOfficer = false
    private var teamCount = 0
    private var visibleLogicalTabs: List<Int> = listOf(0, 1, 2, 3, 4, 5)
    private val submittedRemarkDates = mutableSetOf<String>()

    private var cachedMyRecords: List<AttendanceRecord> = emptyList()
    private var cachedApprovals: List<AttendanceApprovalRecord> = emptyList()      // Team Approval
    private var cachedAllApprovals: List<AttendanceApprovalRecord> = emptyList()   // All Approval
    private var cachedHrReview: List<AttendanceApprovalRecord> = emptyList()       // HR Review (both sub-tabs)
    private var cachedTeamAttendance: List<AttendanceApprovalRecord> = emptyList() // Team Attendance
    private var cachedAllAttendance: List<AttendanceApprovalRecord> = emptyList()  // All (company-wide)
    private var cachedFines: List<com.manjugroups.m_connect.network.FineDeductionItem> = emptyList()
    private val viewCache = mutableMapOf<String, List<View>>()

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
            AttendanceFilterSheet
                .newInstance(filterFromDate, filterToDate)
                .show(parentFragmentManager, "attendance_filter")
        }

        setFragmentResultListener(AttendanceFilterSheet.RESULT_KEY) { _, bundle ->
            filterFromDate = bundle.getString(AttendanceFilterSheet.KEY_FROM).orEmpty()
            filterToDate = bundle.getString(AttendanceFilterSheet.KEY_TO).orEmpty()
            updateRangeLabel()
            refreshAllData(showSkeleton = true, forceRefresh = true)
        }

        // A submitted correction/remark request reloads the list so any
        // pending-request state the backend surfaces is reflected.
        setFragmentResultListener(EditAttendanceBottomSheet.RESULT_KEY) { _, bundle ->
            if (bundle.getBoolean(EditAttendanceBottomSheet.KEY_SUBMITTED, false)) {
                val date = bundle.getString("date")
                if (date != null) {
                    submittedRemarkDates.add(date)
                }
                refreshAllData(showSkeleton = false, forceRefresh = true)
            }
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
        val canApprove = session.hasPermission("attendance.approve")
        val canViewAllAppr = session.hasPermission("attendance.viewAllApprovals")
        val canViewAll = session.hasPermission("attendance.viewAll")

        // (logical id, label). Order mirrors the existing screen.
        val logical = mutableListOf<Pair<Int, String>>()
        logical.add(0 to "My Attendance")
        if (canApprove || isReportingOfficer) {
            logical.add(1 to "Team Attendance")
            logical.add(2 to "Team Approval")
        }
        if (canViewAllAppr) logical.add(3 to "All Approval")
        if (canApprove) logical.add(4 to "HR Review")
        if (canViewAll) logical.add(5 to "All")

        visibleLogicalTabs = logical.map { it.first }
        if (activeTab !in visibleLogicalTabs) activeTab = 0
        val defaultPos = visibleLogicalTabs.indexOf(activeTab).coerceAtLeast(0)

        binding.tabLayout.setTabs(
            logical.map { HorizontalTabLayout.Tab(it.second) },
            defaultSelection = defaultPos,
        )
        if (activeTab == 4) {
            binding.layoutSubTabs.visibility = View.VISIBLE
            updateSubTabStyles()
        } else {
            binding.layoutSubTabs.visibility = View.GONE
            activeSubTab = 0
        }
        binding.tabLayout.setOnTabSelectedListener(object : HorizontalTabLayout.OnTabSelectedListener {
            override fun onTabSelected(index: Int) {
                activeTab = visibleLogicalTabs.getOrElse(index) { 0 }
                binding.etSearch.text?.clear()
                binding.layoutSearch.visibility = View.GONE
                if (activeTab == 4) {
                    binding.layoutSubTabs.visibility = View.VISIBLE
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

        val attCount = cachedHrReview.count { it.requestType != "remarks" }
        val reqCount = cachedHrReview.count { it.requestType == "remarks" }
        binding.subTabAttendance.text = String.format(Locale.US, "Attendance (%02d)", attCount)
        binding.subTabRequest.text = String.format(Locale.US, "Request (%02d)", reqCount)
    }

    private fun refreshAllData(showSkeleton: Boolean = true, forceRefresh: Boolean = false) {
        if (forceRefresh) {
            binding.attendanceList.removeAllViews()
            cachedMyRecords = emptyList()
            cachedTeamAttendance = emptyList()
            cachedApprovals = emptyList()
            cachedAllApprovals = emptyList()
            cachedHrReview = emptyList()
            cachedAllAttendance = emptyList()
            cachedFines = emptyList()
        }

        if (showSkeleton) {
            SkeletonUtils.startSkeletonPulse(binding.skeletonContainer)
            binding.attendanceScroll.visibility = View.GONE
            binding.emptyState.visibility = View.GONE
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val token = session.bearerToken
                if (token.isBlank()) return@launch

                // Reuse anything the visible tab already fetched — only hit the
                // network for the counts we don't have yet, so warming badges
                // never re-runs the current tab's request.
                val myDeferred = async {
                    if (cachedMyRecords.isNotEmpty()) null
                    else runCatching { api.getMyAttendance(token, filterFromDate, filterToDate) }.getOrNull()
                }
                val teamDeferred = async {
                    if (cachedTeamAttendance.isNotEmpty()) null
                    else runCatching { api.getTeamAttendance(token, filterFromDate, filterToDate) }.getOrNull()
                }
                val approvalsDeferred = async {
                    if (cachedApprovals.isNotEmpty()) null
                    else runCatching { api.getPendingAttendanceApprovals(token) }.getOrNull()
                }
                val allApprovalsDeferred = async {
                    if (cachedAllApprovals.isNotEmpty()) null
                    else runCatching { api.getPendingAttendanceApprovals(token, all = true) }.getOrNull()
                }
                val hrReviewDeferred = async {
                    if (cachedHrReview.isNotEmpty()) null
                    else runCatching { api.getHrReview(token, ALL_TAB_FROM_DATE, filterToDate) }.getOrNull()
                }
                val allDeferred = async {
                    // The All tab shows the whole log, not the 30-day window.
                    runCatching { api.getAllAttendance(token, ALL_TAB_FROM_DATE, filterToDate) }.getOrNull()
                }
                val finesDeferred = async {
                    runCatching { api.listFines(token, status = "active") }.getOrNull()
                }

                myDeferred.await()?.let { if (it.success) cachedMyRecords = it.records }
                updateBadgeFor(0, cachedMyRecords.size)

                teamDeferred.await()?.let { if (it.success) cachedTeamAttendance = it.records }
                updateBadgeFor(1, cachedTeamAttendance.size)

                approvalsDeferred.await()?.let { if (it.success) cachedApprovals = it.records }
                updateBadgeFor(2, cachedApprovals.size)

                allApprovalsDeferred.await()?.let { if (it.success) cachedAllApprovals = it.records }
                updateBadgeFor(3, cachedAllApprovals.size)

                hrReviewDeferred.await()?.let { if (it.success) cachedHrReview = it.records }
                updateBadgeFor(4, cachedHrReview.size)
                updateSubTabStyles()

                val allResp = allDeferred.await()
                if (allResp?.success == true) {
                    cachedAllAttendance = allResp.records
                    updateBadgeFor(5, cachedAllAttendance.size)
                }

                val finesResp = finesDeferred.await()
                if (finesResp != null) {
                    cachedFines = finesResp.fines ?: emptyList()
                }

                // Clear view cache because we have fresh data
                viewCache.clear()

                // Render current active tab
                renderCurrentTab()

                // Post delayed pre-rendering for other tabs
                viewLifecycleOwner.lifecycleScope.launch {
                    kotlinx.coroutines.delay(2000)
                    if (isAdded) {
                        preRenderAllTabs()
                    }
                }
            } catch (_: Exception) {} finally {
                SkeletonUtils.stopSkeletonPulse(binding.skeletonContainer)
                binding.attendanceScroll.visibility = View.VISIBLE
                binding.attendanceRefresh.isRefreshing = false
            }
        }
    }

    private fun renderCurrentTab() {
        val cacheKey = getCacheKeyForCurrentTab()

        // Update My Attendance summary values
        if (activeTab == 0) {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val daysPresent = cachedMyRecords.count { r ->
                if (r.date == today) return@count false
                val av = r.approvedAttendance?.lowercase()
                when (av) {
                    "absent", "weekoff", "holiday" -> false
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

        if (viewCache.containsKey(cacheKey)) {
            renderCurrentTabFromCache(cacheKey)
            filterCurrentListOnTyping(binding.etSearch.text?.toString().orEmpty())
            return
        }

        when (activeTab) {
            0 -> renderRecords(fillAbsentDays(cachedMyRecords, filterFromDate, filterToDate), cacheKey)
            1 -> renderTeamAttendance(cachedTeamAttendance, showFines = false, cacheKey)
            2 -> renderApprovals(cachedApprovals, cacheKey)
            3 -> renderApprovals(cachedAllApprovals, cacheKey)
            4 -> {
                val source = if (activeSubTab == 0) {
                    cachedHrReview.filter { it.requestType != "remarks" }
                } else {
                    cachedHrReview.filter { it.requestType == "remarks" }
                }
                renderApprovals(source, cacheKey)
            }
            5 -> renderTeamAttendance(cachedAllAttendance, showFines = true, cacheKey)
        }

        filterCurrentListOnTyping(binding.etSearch.text?.toString().orEmpty())
        binding.attendanceScroll.visibility = View.VISIBLE
    }

    private fun loadData() {
        renderCurrentTab()
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
        val childViews = mutableListOf<View>()
        for (i in 0 until binding.attendanceList.childCount) {
            binding.attendanceList.getChildAt(i).visibility = View.GONE
        }
        if (records.isEmpty()) {
            showEmptyState(
                title = "No attendance records",
                desc = "Your attendance history for this period is empty.",
                imageRes = R.drawable.ic_leave_empty
            )
            viewCache[cacheKey] = emptyList()
            return
        }
        binding.emptyState.visibility = View.GONE

        val parseFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dateFmt = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())

        records.forEach { record ->
            val card = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_attendance_history_card, binding.attendanceList, false)
            bindRecordCard(card, record, parseFmt, dateFmt)
            binding.attendanceList.addView(card)
            childViews.add(card)
        }
        viewCache[cacheKey] = childViews
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

            // Open the punch-event log sheet on tap — mirrors the web
            // popup that lists every individual IN/OUT event with its
            // source chip and time.
            card.setOnClickListener {
                AttendancePunchLogSheet
                    .newInstance(record)
                    .show(parentFragmentManager, "attendance_punch_log")
            }

            // Withdraw button is replaced by Edit button
            val editBtn = card.findViewById<ImageView>(R.id.btnHistoryItemEdit)
            val badgeRemarkSubmitted = card.findViewById<View>(R.id.badgeRemarkSubmitted)
            val isSubmitted = record.date?.let { date ->
                submittedRemarkDates.contains(date)
            } == true

            if (isSubmitted) {
                badgeRemarkSubmitted.visibility = View.VISIBLE
                editBtn.visibility = View.GONE
            } else {
                badgeRemarkSubmitted.visibility = View.GONE
                editBtn.visibility = View.VISIBLE
                editBtn.setOnClickListener {
                    EditAttendanceBottomSheet.newInstance(record)
                        .show(parentFragmentManager, "edit_attendance")
                }
            }

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
            val row = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(padHPx, padVPx, padHPx, padVPx)
                setBackgroundResource(R.drawable.bg_other_fine_banner)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = topMarginPx }
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
            container.addView(row)
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

    private fun preRenderApprovals(records: List<AttendanceApprovalRecord>, cacheKey: String) {
        val context = context ?: return
        val childViews = mutableListOf<View>()
        records.forEach { record ->
            val card = LayoutInflater.from(context)
                .inflate(R.layout.item_team_approval_card, binding.attendanceList, false)
            bindApprovalCard(card, record)
            card.visibility = View.GONE
            binding.attendanceList.addView(card)
            childViews.add(card)
        }
        viewCache[cacheKey] = childViews
    }

    private fun renderApprovals(approvals: List<AttendanceApprovalRecord>, cacheKey: String) {
        val childViews = mutableListOf<View>()
        for (i in 0 until binding.attendanceList.childCount) {
            binding.attendanceList.getChildAt(i).visibility = View.GONE
        }
        if (approvals.isEmpty()) {
            val noTeam = teamCount == 0 && activeTab == 2
            showEmptyState(
                title = if (noTeam) "No team members" else "No attendance to review",
                desc = if (noTeam) "You don't have any team members reporting to you yet."
                else "Pending punches from your team will land here.",
                imageRes = R.drawable.ic_leave_empty
            )
            viewCache[cacheKey] = emptyList()
            return
        }
        binding.emptyState.visibility = View.GONE

        approvals.forEach { record ->
            val card = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_team_approval_card, binding.attendanceList, false)
            bindApprovalCard(card, record)
            binding.attendanceList.addView(card)
            childViews.add(card)
        }
        viewCache[cacheKey] = childViews
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

            val openSheetListener = View.OnClickListener {
                if (activeTab == 4 && activeSubTab == 1) {
                    val sheet = ReviewAttendanceRequestBottomSheet.newInstance(record, object : ReviewAttendanceRequestBottomSheet.OnActionClickListener {
                        override fun onApprove(recordId: String, status: String) {
                            approveRecord(recordId, status)
                        }
                        override fun onReject(recordId: String) {
                            showRejectDialog(recordId)
                        }
                    })
                    sheet.show(parentFragmentManager, "review_attendance_request")
                } else {
                    val sheet = AttendanceReviewBottomSheet.newInstance(record, object : AttendanceReviewBottomSheet.OnActionClickListener {
                        override fun onApprove(recordId: String) {
                            approveRecord(recordId, "present")
                        }
                        override fun onReject(recordId: String) {
                            showRejectDialog(recordId)
                        }
                    })
                    sheet.show(parentFragmentManager, "attendance_review")
                }
            }

            if (activeTab == 4 && activeSubTab == 1) {
                btnReject.visibility = View.GONE
                btnApprove.visibility = View.GONE
                btnHrReview.visibility = View.VISIBLE
                
                btnHrReview.setOnClickListener(openSheetListener)
                card.setOnClickListener(openSheetListener)
            } else {
                btnReject.visibility = View.VISIBLE
                btnApprove.visibility = View.VISIBLE
                btnHrReview.visibility = View.GONE
                
                card.setOnClickListener(openSheetListener)
                btnApprove.setOnClickListener(openSheetListener)
                btnReject.setOnClickListener(openSheetListener)
            }
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

    private fun showRejectDialog(id: String) {
        val input = EditText(requireContext()).apply {
            hint = "Reason for rejection"
            minLines = 3
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Reject attendance")
            .setView(input)
            .setPositiveButton("Reject") { _, _ ->
                val reason = input.text?.toString()?.trim().orEmpty().ifBlank { "Rejected" }
                rejectRecord(id, reason)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun approveRecord(id: String, approvedAttendance: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.approveAttendance(
                    session.bearerToken,
                    ApproveAttendanceRequest(id, approvedAttendance)
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

    private fun rejectRecord(id: String, reason: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.rejectAttendance(
                    session.bearerToken,
                    RejectRequest(id, reason)
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

    private suspend fun preRenderAllTabs() {
        if (!viewCache.containsKey("team_attendance")) {
            preRenderTeamAttendance(cachedTeamAttendance, showFines = false, "team_attendance")
            kotlinx.coroutines.delay(150)
        }
        if (!viewCache.containsKey("team_approval")) {
            preRenderApprovals(cachedApprovals, "team_approval")
            kotlinx.coroutines.delay(150)
        }
        if (!viewCache.containsKey("all_approval")) {
            preRenderApprovals(cachedAllApprovals, "all_approval")
            kotlinx.coroutines.delay(150)
        }
        if (!viewCache.containsKey("hr_review_0")) {
            val source0 = cachedHrReview.filter { it.requestType != "remarks" }
            preRenderApprovals(source0, "hr_review_0")
            kotlinx.coroutines.delay(150)
        }
        if (!viewCache.containsKey("hr_review_1")) {
            val source1 = cachedHrReview.filter { it.requestType == "remarks" }
            preRenderApprovals(source1, "hr_review_1")
            kotlinx.coroutines.delay(150)
        }
        if (!viewCache.containsKey("all_fines")) {
            // The "All" tab is company-wide (cachedAllAttendance), not team-scoped.
            preRenderTeamAttendance(cachedAllAttendance, showFines = true, "all_fines")
        }
    }

    private fun getCacheKeyForCurrentTab(): String {
        return when (activeTab) {
            0 -> "my_attendance"
            1 -> "team_attendance"
            2 -> "team_approval"
            3 -> "all_approval"
            4 -> "hr_review_$activeSubTab"
            5 -> "all_fines"
            else -> "unknown"
        }
    }

    private fun renderCurrentTabFromCache(cacheKey: String) {
        for (i in 0 until binding.attendanceList.childCount) {
            binding.attendanceList.getChildAt(i).visibility = View.GONE
        }
        val cached = viewCache[cacheKey].orEmpty()
        if (cached.isEmpty()) {
            // Team tabs with no direct reports → "No team members", not the
            // generic "nothing to review" copy (mirrors the web's hasNoTeam).
            val noTeam = teamCount == 0 && (activeTab == 1 || activeTab == 2)
            val title = if (noTeam) "No team members" else when (activeTab) {
                0 -> "No attendance records"
                1 -> "No team attendance"
                2, 3 -> "No attendance to review"
                4 -> "No attendance to review"
                else -> "No team attendance"
            }
            val desc = if (noTeam) "You don't have any team members reporting to you yet."
            else when (activeTab) {
                0 -> "Your attendance history for this period is empty."
                1, 5 -> "No team attendance records for this period."
                else -> "Pending punches from your team will land here."
            }
            showEmptyState(title, desc, R.drawable.ic_leave_empty)
        } else {
            binding.emptyState.visibility = View.GONE
            cached.forEach { view ->
                if (view.parent == null) {
                    binding.attendanceList.addView(view)
                }
                view.visibility = View.VISIBLE
            }
        }
        binding.attendanceScroll.visibility = View.VISIBLE
        binding.attendanceRefresh.isRefreshing = false
    }

    private fun filterCurrentListOnTyping(query: String) {
        val queryLower = query.trim().lowercase(Locale.US)
        // Every tab's cards live in the SAME list container — other tabs'
        // pre-rendered cards sit in it as GONE children. Only the ACTIVE
        // tab's views (the ones in its viewCache entry) may be toggled here;
        // the old blanket "show everything on an empty query" resurrected
        // the hidden cards and bled My Attendance rows into the Team /
        // Approval tabs.
        val currentViews = viewCache[getCacheKeyForCurrentTab()]?.toHashSet()
        var visibleCount = 0
        var currentTabCards = 0

        for (i in 0 until binding.attendanceList.childCount) {
            val child = binding.attendanceList.getChildAt(i)
            if (currentViews != null && child !in currentViews) {
                child.visibility = View.GONE
                continue
            }
            val tag = child.tag as? String
            if (tag == null) {
                child.visibility = View.VISIBLE
                continue
            }
            currentTabCards++

            if (queryLower.isEmpty() || tag.contains(queryLower)) {
                child.visibility = View.VISIBLE
                visibleCount++
            } else {
                child.visibility = View.GONE
            }
        }

        if (visibleCount == 0 && currentTabCards > 0) {
            binding.emptyState.visibility = View.VISIBLE
            binding.emptyState.setEmptyState(
                R.drawable.ic_leave_empty,
                "No search results",
                "Try refining your search query."
            )
        } else if (visibleCount > 0) {
            binding.emptyState.visibility = View.GONE
        }
        // currentTabCards == 0 → the tab is genuinely empty; keep whatever
        // empty state the render function already showed ("No team members",
        // "No attendance to review", …) instead of overwriting or hiding it.
    }

    private fun preRenderTeamAttendance(records: List<AttendanceApprovalRecord>, showFines: Boolean, cacheKey: String) {
        val context = context ?: return
        val childViews = mutableListOf<View>()
        records.forEach { record ->
            val card = LayoutInflater.from(context)
                .inflate(R.layout.item_team_attendance_card, binding.attendanceList, false)
            bindTeamAttendanceCard(card, record, showFines)
            card.visibility = View.GONE
            binding.attendanceList.addView(card)
            childViews.add(card)
        }
        viewCache[cacheKey] = childViews
    }

    private fun renderTeamAttendance(records: List<AttendanceApprovalRecord>, showFines: Boolean, cacheKey: String) {
        val childViews = mutableListOf<View>()
        for (i in 0 until binding.attendanceList.childCount) {
            binding.attendanceList.getChildAt(i).visibility = View.GONE
        }
        if (records.isEmpty()) {
            val noTeam = teamCount == 0 && activeTab == 1
            showEmptyState(
                title = if (noTeam) "No team members" else "No team attendance",
                desc = if (noTeam) "You don't have any team members reporting to you yet."
                else "No team attendance records for this period.",
                imageRes = R.drawable.ic_leave_empty
            )
            viewCache[cacheKey] = emptyList()
            return
        }
        binding.emptyState.visibility = View.GONE

        records.forEach { record ->
            val card = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_team_attendance_card, binding.attendanceList, false)
            bindTeamAttendanceCard(card, record, showFines)
            binding.attendanceList.addView(card)
            childViews.add(card)
        }
        viewCache[cacheKey] = childViews
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

            // Active-fine badge — only on the All tab, when the staff has a
            // matching active fine.
            val tvFine = card.findViewById<TextView>(R.id.tvHistoryItemFine)
            val fine = if (showFines) cachedFines.firstOrNull {
                it.staffName.equals(record.staffName, ignoreCase = true) ||
                    (!record.employeeId.isNullOrBlank() && it.employeeId.equals(record.employeeId, ignoreCase = true))
            } else null
            if (fine != null) {
                tvFine.visibility = View.VISIBLE
                tvFine.text = String.format(Locale.getDefault(), "Fine: ₹%.0f", fine.amount)
            } else {
                tvFine.visibility = View.GONE
            }

            card.findViewById<TextView>(R.id.tvStaffName).text =
                record.staffName?.trim().orEmpty().ifBlank { "Staff Member" }
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
                tv.text = "Absent"
                tv.setBackgroundResource(R.drawable.bg_chip_inactive)
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
            else -> {
                tv.text = "Pending"
                tv.setBackgroundResource(R.drawable.bg_chip_inactive)
                tv.setTextColor(Color.parseColor("#475467"))
            }
        }
    }

    override fun onDestroyView() {
        SkeletonUtils.stopAll()
        super.onDestroyView()
        _binding = null
    }
}
