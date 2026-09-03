package com.manjugroups.m_connect.ui.hr

import android.animation.ObjectAnimator
import com.manjugroups.m_connect.ui.common.BottomActionInsets
import com.manjugroups.m_connect.ui.common.dismissRefresh
import com.manjugroups.m_connect.ui.common.setupPullToRefresh
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.load
import coil.transform.CircleCropTransformation
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentLeavesBinding
import com.manjugroups.m_connect.network.LeaveData
import com.manjugroups.m_connect.notifications.WorkflowNotificationRoute
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import com.manjugroups.m_connect.ui.common.AdvancedListFilterSheet
import com.manjugroups.m_connect.ui.common.navigateUp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.manjugroups.m_connect.ui.common.showOnce

class LeavesFragment : Fragment() {

    private enum class HistoryFilter { REVIEW, APPROVED, REJECTED }
    private enum class StatusBucket { REVIEW, APPROVED, REJECTED }
    private enum class LeaveScope { MY, TEAM, ALL }

    private var _binding: FragmentLeavesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LeavesViewModel by viewModels()
    private lateinit var session: SessionManager
    private var screenMode: String = MODE_HISTORY
    private var activeScope: LeaveScope = LeaveScope.MY
    private var focusedEntityId: String? = null
    private var historyFilter: HistoryFilter = HistoryFilter.REVIEW
    private var filterFromDate: String? = null
    private var filterToDate: String? = null
    private var filterLeaveType: String? = null
    private var filterStaffId: String? = null
    private var skeletonAnimator: ObjectAnimator? = null
    // True once leaves have rendered at least once. Gates the skeleton so
    // a pull-to-refresh doesn't hide the list and flash placeholders over
    // data that's already on screen.
    private var hasRenderedLeavesOnce = false

    // Infinite scroll: render one page of the filtered history at a time and
    // grow the window as the user nears the bottom of the NestedScrollView.
    // windowCtx tracks tab/scope/screen + the source-list identity so the
    // window resets to the first page whenever any of those change.
    private var leavesWindowCtx: String? = null
    private var leavesFilteredCount: Int = 0
    private val leavesPager = com.manjugroups.m_connect.ui.common.InfiniteScrollPager(
        onLoadMore = { if (_binding != null) renderState(viewModel.uiState.value) },
    )

    companion object {
        private const val ARG_MODE = "mode"
        private const val ARG_ENTITY_ID = "entity_id"
        private const val REJECT_RESULT_KEY = "leave_reject_reason"
        private const val ADVANCED_FILTER_RESULT = "leave_advanced_filter_result"
        private const val KEY_DATE = "date"
        private const val KEY_STATUS = "status"
        private const val KEY_TYPE = "leave_type"
        private const val KEY_STAFF = "staff"
        const val MODE_HISTORY = WorkflowNotificationRoute.MODE_HISTORY
        const val MODE_APPROVAL = WorkflowNotificationRoute.MODE_APPROVAL

        fun newInstance(mode: String = MODE_HISTORY, entityId: String? = null): LeavesFragment {
            return LeavesFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, mode)
                    putString(ARG_ENTITY_ID, entityId)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screenMode = arguments?.getString(ARG_MODE) ?: MODE_HISTORY
        focusedEntityId = arguments?.getString(ARG_ENTITY_ID)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLeavesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        // Reject-with-reason bottom sheet result → run the reject with the
        // captured reason (shared component; reject only, not cancel).
        childFragmentManager.setFragmentResultListener(REJECT_RESULT_KEY, viewLifecycleOwner) { _, bundle ->
            val id = bundle.getString(com.manjugroups.m_connect.ui.common.RejectWithReasonBottomSheet.KEY_ITEM_ID).orEmpty()
            val reason = bundle.getString(com.manjugroups.m_connect.ui.common.RejectWithReasonBottomSheet.KEY_REASON).orEmpty()
            if (id.isNotEmpty()) {
                viewModel.rejectLeave(
                    session.bearerToken,
                    id,
                    reason.ifBlank { "Rejected" },
                    session.hasPermission("leaves.approve"),
                )
            }
        }

        binding.summaryHeader.setOnBackClickListener { navigateUp() }
        binding.summaryHeader.setBackButtonVisible(screenMode == MODE_APPROVAL)
        BottomActionInsets.applyAboveSystemNavAndTabs(binding.btnApplyLeave)
        binding.btnAdvancedLeaveFilter.setOnClickListener { showAdvancedFilters() }
        parentFragmentManager.setFragmentResultListener(
            ADVANCED_FILTER_RESULT,
            viewLifecycleOwner,
        ) { _, bundle ->
            val state = AdvancedListFilterSheet.state(bundle) ?: return@setFragmentResultListener
            filterFromDate = state.fromDate
            filterToDate = state.toDate
            filterLeaveType = state.value(KEY_TYPE)
            filterStaffId = state.value(KEY_STAFF)
            historyFilter = state.value(KEY_STATUS)?.let { value ->
                HistoryFilter.entries.firstOrNull { it.name == value }
            } ?: HistoryFilter.REVIEW
            binding.filterRow.selectTab(historyFilter.ordinal)
            viewModel.load(
                session.bearerToken,
                session.hasPermission("leaves.approve"),
                filterFromDate,
                filterToDate,
                if (historyFilter == HistoryFilter.REVIEW) "pending" else historyFilter.name.lowercase(Locale.US),
                filterLeaveType,
                filterStaffId,
            )
        }
        binding.btnApplyLeave.setOnClickListener {
            parentFragmentManager.setFragmentResultListener(ApplyLeaveBottomSheet.RESULT_KEY_APPLIED, viewLifecycleOwner) { _, bundle ->
                val success = bundle.getBoolean("success", false)
                if (success) {
                    viewModel.load(session.bearerToken, session.hasPermission("leaves.approve"))
                }
            }
            ApplyLeaveBottomSheet.newInstance().showOnce(parentFragmentManager, "apply_leave_sheet")
        }

        val year = Calendar.getInstance().get(Calendar.YEAR)
        binding.tvYear.text = "Period 1 Jan $year - 30 Dec $year"

        val canApprove = session.hasPermission("leaves.approve")
        if (screenMode == MODE_APPROVAL) {
            binding.summaryHeader.setTitle("Leave Approvals")
            binding.summaryHeader.setSubtitle("Review Requests")
            binding.leavesRefresh.isEnabled = false
            binding.btnApplyLeave.visibility = View.GONE
        } else {
            binding.summaryHeader.setTitle("Leave Summary")
            binding.summaryHeader.setSubtitle("Apply Leave")
            binding.leavesRefresh.isEnabled = true
            binding.filterRow.visibility = View.VISIBLE
            setupFilterTabs()
            if (canApprove) {
                binding.dropdownScopeSelector.visibility = View.VISIBLE
                binding.dropdownScopeSelector.setOnClickListener {
                    showScopePopupMenu(it)
                }
            } else {
                binding.dropdownScopeSelector.visibility = View.GONE
            }
        }

        collectState()
        collectEvents()
        viewModel.load(session.bearerToken, canApprove)

        // Pull-to-refresh re-runs viewModel.load() so balance + history
        // come back fresh. The spinner is dismissed in collectState() the
        // moment the next non-loading state lands.
        binding.leavesRefresh.setupPullToRefresh {
            viewModel.load(session.bearerToken, session.hasPermission("leaves.approve"))
        }

        // Infinite scroll: grow the render window by a page as the user nears
        // the bottom of the NestedScrollView that hosts the manual card list.
        leavesPager.bindNestedScroll(binding.contentScroll, totalCount = { leavesFilteredCount })
    }

    private fun setupFilterTabs() {
        binding.filterRow.setTabs(
            listOf("Review", "Approved", "Rejected"),
            historyFilter.ordinal
        ) { position ->
            historyFilter = HistoryFilter.values()[position]
            renderState(viewModel.uiState.value)
        }
    }

    private fun collectState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> renderState(state) }
            }
        }
    }

    private fun renderState(state: LeavesState) {
        val canApprove = session.hasPermission("leaves.approve")
        
        // Update red dot in dropdown if visible
        if (screenMode == MODE_HISTORY && canApprove) {
            binding.dotScopeBadge.visibility = if (state.pendingApprovals.isNotEmpty()) View.VISIBLE else View.GONE
        }

        // My Leaves = own history. Team Leaves = direct reports only. All
        // Leaves = every org leave (viewAll). Team and All are now DISTINCT
        // datasets (previously both showed the all-leaves set, so Team was
        // wrong for viewAll users).
        val displayLeaves = if (screenMode == MODE_APPROVAL) {
            filterHistoryLeaves(state.pendingApprovals)
        } else {
            if (canApprove && activeScope != LeaveScope.MY) {
                filterHistoryLeaves(scopedApprovals(state))
            } else {
                filterHistoryLeaves(state.myLeaves)
            }
        }
        val isLoading = state.isLoading

        if (screenMode == MODE_HISTORY) {
            binding.filterRow.visibility = View.VISIBLE
            // Cancelled leaves are dropped entirely — only Review / Approved /
            // Rejected are surfaced (matching the web).
            val listForCounts =
                (if (canApprove && activeScope != LeaveScope.MY) scopedApprovals(state) else state.myLeaves)
                    .filterNot { it.status?.trim()?.lowercase(Locale.getDefault()) == "cancelled" }
            val reviewCount = listForCounts.count { bucketForStatus(it.status) == StatusBucket.REVIEW }
            val approvedCount = listForCounts.count { bucketForStatus(it.status) == StatusBucket.APPROVED }
            val rejectedCount = listForCounts.count { bucketForStatus(it.status) == StatusBucket.REJECTED }
            binding.filterRow.updateTabLabels(
                listOf(
                    "Review ($reviewCount)",
                    "Approved ($approvedCount)",
                    "Rejected ($rejectedCount)"
                )
            )
        }

        // Clear the pull-refresh spinner as soon as a non-loading state
        // arrives — regardless of whether it's success or error.
        if (!isLoading) binding.leavesRefresh.dismissRefresh()

        val hasAllocation = state.casualTotal > 0 || state.sickTotal > 0 || state.earnedTotal > 0
        binding.balanceCard.visibility = if (screenMode == MODE_HISTORY) View.VISIBLE else View.GONE
        binding.btnApplyLeave.visibility = if (screenMode == MODE_HISTORY) View.VISIBLE else View.GONE

        if (hasAllocation) {
            val available = state.casualLeft + state.sickLeft + state.earnedLeft
            val used = (state.casualTotal - state.casualLeft).coerceAtLeast(0) +
                (state.sickTotal - state.sickLeft).coerceAtLeast(0) +
                (state.earnedTotal - state.earnedLeft).coerceAtLeast(0)
            binding.tvLeaveAvailable.text = available.toString()
            binding.tvLeaveUsed.text = used.toString()
        } else {
            binding.tvLeaveAvailable.text = "0"
            binding.tvLeaveUsed.text = "0"
        }

        // Only skeleton on the FIRST load. A refresh keeps the existing
        // list visible while the new data arrives instead of blanking to
        // placeholders.
        val showSkeleton = isLoading && !hasRenderedLeavesOnce
        binding.skeletonContainer.visibility = if (showSkeleton) View.VISIBLE else View.GONE
        binding.leaveList.visibility = if (showSkeleton) View.GONE else View.VISIBLE
        if (showSkeleton) {
            startSkeletonPulse()
            binding.emptyState.visibility = View.GONE
            return
        }

        stopSkeletonPulse()
        if (!isLoading) hasRenderedLeavesOnce = true
        setEmptyCopy(displayLeaves.isEmpty())
        val isApprovalForRender = (canApprove && screenMode == MODE_APPROVAL) || (canApprove && screenMode == MODE_HISTORY && activeScope != LeaveScope.MY)

        // Infinite scroll: bound the render to one page of the FULL filtered
        // history, growing as the user scrolls. Reset the window whenever the
        // tab (historyFilter), scope, screen, or the underlying source list
        // (a new fetch / approval action) changes — keyed off source identity.
        val sourceForWindow = if (screenMode == MODE_APPROVAL) {
            state.pendingApprovals
        } else if (canApprove && activeScope != LeaveScope.MY) {
            scopedApprovals(state)
        } else {
            state.myLeaves
        }
        leavesFilteredCount = displayLeaves.size
        val windowCtx = "$screenMode|$activeScope|$historyFilter|${System.identityHashCode(sourceForWindow)}"
        if (windowCtx != leavesWindowCtx) {
            leavesWindowCtx = windowCtx
            leavesPager.reset()
        }
        // Empty-state stays keyed off the FULL filtered size (above); only the
        // rendered card set is windowed here. The chunked renderer still slices
        // this window ~24 cards per frame.
        renderLeaves(displayLeaves.take(leavesPager.limit), isApprovalForRender)
    }



    private fun setEmptyCopy(isEmpty: Boolean) {
        if (!isEmpty) return
        val isTeam = screenMode == MODE_APPROVAL || (session.hasPermission("leaves.approve") && activeScope != LeaveScope.MY)
        if (isTeam) {
            binding.emptyState.setTitle("No Leave Approvals")
            binding.emptyState.setDescription("There are no pending leave requests in review right now.")
            return
        }
        binding.emptyState.setTitle("No Leave Applied!")
        binding.emptyState.setDescription(
            "Ready to catch some fresh air? Click “Apply Leave” and take that well-deserved break!"
        )
    }

    private fun collectEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.event.collect { msg ->
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** The approver dataset for the active scope: Team Leaves draws from the
     *  direct-report set, All Leaves from the full (viewAll) set. */
    private fun scopedApprovals(state: LeavesState): List<LeaveData> =
        if (activeScope == LeaveScope.TEAM) state.teamLeaves else state.pendingApprovals

    private fun filterHistoryLeaves(leaves: List<LeaveData>): List<LeaveData> {
        return leaves
            // Cancelled leaves are not a category — never show them.
            .filterNot { it.status?.trim()?.lowercase(Locale.getDefault()) == "cancelled" }
            .filter { leave ->
                when (historyFilter) {
                    HistoryFilter.REVIEW -> bucketForStatus(leave.status) == StatusBucket.REVIEW
                    HistoryFilter.APPROVED -> bucketForStatus(leave.status) == StatusBucket.APPROVED
                    HistoryFilter.REJECTED -> bucketForStatus(leave.status) == StatusBucket.REJECTED
                }
            }
            .filter { leave ->
                (filterFromDate.isNullOrBlank() || leave.toDate == null || leave.toDate >= filterFromDate!!) &&
                    (filterToDate.isNullOrBlank() || leave.fromDate == null || leave.fromDate <= filterToDate!!) &&
                    (filterLeaveType == null || leave.leaveType == filterLeaveType) &&
                    (filterStaffId == null || leave.staffId == filterStaffId)
            }
    }

    private fun currentLeaveSource(state: LeavesState): List<LeaveData> {
        val canApprove = session.hasPermission("leaves.approve")
        return if (screenMode == MODE_APPROVAL) state.pendingApprovals
        else if (canApprove && activeScope != LeaveScope.MY) scopedApprovals(state)
        else state.myLeaves
    }

    private fun showAdvancedFilters() {
        val source = currentLeaveSource(viewModel.uiState.value)
            .filterNot { it.status?.trim()?.lowercase(Locale.US) == "cancelled" }
        val types = source.mapNotNull { it.leaveType?.takeIf(String::isNotBlank) }
            .distinct().sorted().map { AdvancedListFilterSheet.Option(it, humanizeFilter(it)) }
        val staff = if (activeScope == LeaveScope.MY && screenMode != MODE_APPROVAL) emptyList()
        else source.mapNotNull { leave ->
            val id = leave.staffId?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            AdvancedListFilterSheet.Option(id, leave.staffName ?: "Staff")
        }.distinctBy { it.value }.sortedBy { it.label.lowercase(Locale.US) }
        val categories = buildList {
            add(AdvancedListFilterSheet.Category(KEY_DATE, "Leave dates", dateRange = true))
            add(AdvancedListFilterSheet.Category(
                KEY_STATUS,
                "Status",
                HistoryFilter.entries.map { AdvancedListFilterSheet.Option(it.name, humanizeFilter(it.name)) },
            ))
            add(AdvancedListFilterSheet.Category(KEY_TYPE, "Leave type", types))
            if (staff.isNotEmpty()) add(AdvancedListFilterSheet.Category(KEY_STAFF, "Staff", staff))
        }
        val initial = AdvancedListFilterSheet.State(
            selected = buildMap {
                put(KEY_STATUS, setOf(historyFilter.name))
                filterLeaveType?.let { put(KEY_TYPE, setOf(it)) }
                filterStaffId?.let { put(KEY_STAFF, setOf(it)) }
            },
            fromDate = filterFromDate,
            toDate = filterToDate,
        )
        AdvancedListFilterSheet.newInstance(categories, initial, ADVANCED_FILTER_RESULT).apply {
            countProvider = { state -> source.count { matchesLeaveState(it, state) } }
        }.showOnce(parentFragmentManager, "leave_advanced_filters")
    }

    private fun matchesLeaveState(leave: LeaveData, state: AdvancedListFilterSheet.State): Boolean {
        val selectedStatus = state.value(KEY_STATUS)?.let { value ->
            HistoryFilter.entries.firstOrNull { it.name == value }
        } ?: HistoryFilter.REVIEW
        val bucketMatches = when (selectedStatus) {
            HistoryFilter.REVIEW -> bucketForStatus(leave.status) == StatusBucket.REVIEW
            HistoryFilter.APPROVED -> bucketForStatus(leave.status) == StatusBucket.APPROVED
            HistoryFilter.REJECTED -> bucketForStatus(leave.status) == StatusBucket.REJECTED
        }
        return bucketMatches &&
            (state.fromDate.isNullOrBlank() || leave.toDate == null || leave.toDate >= state.fromDate) &&
            (state.toDate.isNullOrBlank() || leave.fromDate == null || leave.fromDate <= state.toDate) &&
            (state.value(KEY_TYPE) == null || leave.leaveType == state.value(KEY_TYPE)) &&
            (state.value(KEY_STAFF) == null || leave.staffId == state.value(KEY_STAFF))
    }

    private fun humanizeFilter(value: String): String = value.lowercase(Locale.US)
        .split('_', '-')
        .joinToString(" ") { it.replaceFirstChar(Char::titlecase) }

    // Cancels an in-flight chunked render when a newer pass starts.
    private var renderGen = 0

    private fun renderLeaves(leaves: List<LeaveData>, approvalMode: Boolean) {
        renderGen++
        binding.leaveList.removeAllViews()
        binding.emptyState.visibility = if (leaves.isEmpty()) View.VISIBLE else View.GONE

        val headingFmt = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
        val rangeFmt = SimpleDateFormat("d MMM", Locale.getDefault())
        val parseFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val statusFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

        fun bindCard(leave: LeaveData): View {
            val card = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_leave, binding.leaveList, false)

            val fromDate = parseServerDate(parseFmt, leave.fromDate)
            val toDate = parseServerDate(parseFmt, leave.toDate)

            val headingDate = fromDate ?: toDate
            val dateHeadingText = headingDate?.let { headingFmt.format(it) } ?: "Leave Date"

            val rangeText = when {
                fromDate != null && toDate != null && sameDate(fromDate, toDate) -> rangeFmt.format(fromDate)
                fromDate != null && toDate != null -> "${rangeFmt.format(fromDate)} - ${rangeFmt.format(toDate)}"
                leave.fromDate != null && leave.toDate != null && leave.fromDate == leave.toDate -> leave.fromDate
                leave.fromDate != null && leave.toDate != null -> "${leave.fromDate} - ${leave.toDate}"
                else -> leave.fromDate ?: leave.toDate ?: "-"
            }

            val days = if (fromDate != null && toDate != null) {
                ((toDate.time - fromDate.time) / (1000 * 60 * 60 * 24) + 1).toInt().coerceAtLeast(1)
            } else {
                1
            }

            val bucket = bucketForStatus(leave.status)
            // Decision timestamp first (Approved/Rejected rows), fall
            // back to creation time for Review rows / older data that
            // pre-dates the decidedAt enrichment.
            val decidedDate = parseIsoOrEpoch(leave.decidedAt)
            val statusDate = decidedDate ?: parseCreationDate(leave.createdAt)
            val statusDateText = statusDate?.let { statusFmt.format(it) }

            val isCancelled = leave.status?.trim()?.lowercase(Locale.getDefault()) == "cancelled"
            val statusNote: String
            val statusColor: Int
            val statusIconRes: Int
            if (isCancelled) {
                statusNote = "Cancelled"
                statusColor = ContextCompat.getColor(requireContext(), R.color.lt_foreground_muted)
                statusIconRes = R.drawable.ic_leave_status_rejected
            } else {
                when (bucket) {
                    StatusBucket.APPROVED -> {
                        statusNote = if (statusDateText.isNullOrBlank()) "Approved" else "Approved at $statusDateText"
                        statusColor = ContextCompat.getColor(requireContext(), R.color.lt_success)
                        statusIconRes = R.drawable.ic_leave_status_approved
                    }
                    StatusBucket.REJECTED -> {
                        statusNote = if (statusDateText.isNullOrBlank()) "Rejected" else "Rejected at $statusDateText"
                        statusColor = ContextCompat.getColor(requireContext(), R.color.lt_error)
                        statusIconRes = R.drawable.ic_leave_status_rejected
                    }
                    StatusBucket.REVIEW -> {
                        statusNote = "In Review"
                        statusColor = ContextCompat.getColor(requireContext(), R.color.chat_blue_top)
                        statusIconRes = R.drawable.ic_leave_spinner_blue
                    }
                }
            }

            card.findViewById<TextView>(R.id.tvLeaveDate).text = dateHeadingText
            card.findViewById<TextView>(R.id.tvLeaveType).text = rangeText
            val isHalfDay = leave.isHalfDay == true || leave.leaveType?.lowercase(Locale.getDefault())?.contains("half_day") == true
            card.findViewById<TextView>(R.id.tvLeaveStatus).text = if (isHalfDay) "0.5 Day" else "$days Day${if (days > 1) "s" else ""}"

            // Dynamic labels matching Figma: reason as left label, leave type as right label
            val reasonLabel = card.findViewById<TextView>(R.id.tvLeaveReasonLabel)
            val typeLabel = card.findViewById<TextView>(R.id.tvLeaveTypeLabel)
            reasonLabel.text = leave.reason?.trim()?.takeIf { it.isNotBlank() } ?: "Leave Date"
            val rawType = leave.leaveType?.replace('_', ' ')?.split(" ")?.joinToString(" ") {
                it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(Locale.getDefault()) else c.toString() }
            } ?: "Total"
            val leaveTypeDisplay = if (isHalfDay) {
                val sessionText = leave.halfDaySession?.replaceFirstChar { it.uppercaseChar() } ?: ""
                if (sessionText.isNotEmpty()) "$rawType Half-day ($sessionText) Leave" else "$rawType Half-day Leave"
            } else {
                if (rawType.lowercase().contains("leave")) rawType else "$rawType Leave"
            }
            typeLabel.text = leaveTypeDisplay

            val reasonText = card.findViewById<TextView>(R.id.tvLeaveReason)
            reasonText.text = statusNote
            reasonText.setTextColor(statusColor)
            val statusIconView = card.findViewById<android.widget.ImageView>(R.id.ivLeaveStatusIcon)
            statusIconView.setImageResource(statusIconRes)
            if (isCancelled) {
                statusIconView.setColorFilter(statusColor)
            } else {
                statusIconView.clearColorFilter()
            }

            val staffName = card.findViewById<TextView>(R.id.tvLeaveStaffName)
            val staffInitial = card.findViewById<TextView>(R.id.tvLeaveStaffInitial)
            val staffAvatar = card.findViewById<android.widget.ImageView>(R.id.ivLeaveStaffAvatar)
            val staffRow = card.findViewById<View>(R.id.staffInfoRow)
            val byLabel = card.findViewById<TextView>(R.id.tvBy)
            val actionRow = card.findViewById<View>(R.id.leaveActionRow)
            val layoutTeamReviewActions = card.findViewById<View>(R.id.layoutTeamReviewActions)
            val approveButton = card.findViewById<TextView>(R.id.btnApproveLeave)
            val rejectButton = card.findViewById<TextView>(R.id.btnRejectLeave)
            val btnTeamAccepted = card.findViewById<View>(R.id.btnTeamAccepted)
            val btnTeamRejected = card.findViewById<View>(R.id.btnTeamRejected)

            // For Approved/Rejected rows show the decision-maker (the
            // approver who acted on the request) instead of the
            // submitter — matches the design's "By Elaine" label on
            // the approved/rejected cards. For Review rows we keep the
            // submitter name, which is what the manager UI wants.
            val showApprover = bucket == StatusBucket.APPROVED || bucket == StatusBucket.REJECTED
            val displayName = if (showApprover) {
                leave.approverName?.trim().takeUnless { it.isNullOrBlank() }
                    ?: leave.staffName?.trim().takeUnless { it.isNullOrBlank() }
                    ?: "Self"
            } else {
                leave.staffName?.trim().takeUnless { it.isNullOrBlank() } ?: "Self"
            }
            val initial = displayName.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
            staffRow.visibility = View.VISIBLE
            byLabel.visibility = View.VISIBLE
            staffName.visibility = View.VISIBLE
            staffName.text = displayName
            staffInitial.text = initial

            // Approver photo when present; clear back to the initial
            // chip otherwise so recycled views don't keep the previous
            // person's avatar.
            val photoUrl = if (showApprover) leave.approverPhotoUrl?.takeIf { it.isNotBlank() } else null
            if (photoUrl != null) {
                staffAvatar.visibility = View.VISIBLE
                staffInitial.visibility = View.INVISIBLE
                staffAvatar.load(photoUrl) {
                    crossfade(true)
                    transformations(CircleCropTransformation())
                }
            } else {
                staffAvatar.visibility = View.GONE
                staffAvatar.setImageDrawable(null)
                staffInitial.visibility = View.VISIBLE
            }

            if (approvalMode) {
                actionRow.visibility = View.VISIBLE
                // A person can never approve/reject their OWN leave — even a
                // super admin. Their own row stays visible (so they see it's in
                // review) but carries no action buttons; someone else acts on it.
                val isOwnLeave = !leave.staffId.isNullOrBlank() &&
                    leave.staffId == session.staffId
                when (bucket) {
                    StatusBucket.REVIEW -> {
                        if (isOwnLeave) {
                            actionRow.visibility = View.GONE
                        } else {
                            layoutTeamReviewActions.visibility = View.VISIBLE
                            btnTeamAccepted.visibility = View.GONE
                            btnTeamRejected.visibility = View.GONE
                            approveButton.setOnClickListener {
                                leave.id?.let { id ->
                                    viewModel.approveLeave(
                                        session.bearerToken,
                                        id,
                                        session.hasPermission("leaves.approve")
                                    )
                                }
                            }
                            rejectButton.setOnClickListener {
                                leave.id?.let { id -> showRejectDialog(id) }
                            }
                        }
                    }
                    StatusBucket.APPROVED -> {
                        layoutTeamReviewActions.visibility = View.GONE
                        btnTeamAccepted.visibility = View.VISIBLE
                        btnTeamRejected.visibility = View.GONE
                    }
                    StatusBucket.REJECTED -> {
                        layoutTeamReviewActions.visibility = View.GONE
                        btnTeamAccepted.visibility = View.GONE
                        btnTeamRejected.visibility = View.VISIBLE
                    }
                }
            } else {
                actionRow.visibility = View.GONE
            }

            // Show delete icon on the user's own leave cards (history mode only)
            val deleteBtn = card.findViewById<View>(R.id.btnDeleteLeave)
            if (!approvalMode && leave.id != null) {
                deleteBtn.visibility = View.VISIBLE
                if (isCancelled) {
                    deleteBtn.isEnabled = false
                    deleteBtn.alpha = 0.5f
                    deleteBtn.setOnClickListener(null)
                } else {
                    deleteBtn.isEnabled = true
                    deleteBtn.alpha = 1.0f
                    deleteBtn.setOnClickListener {
                        showCancelLeaveDialog(leave.id)
                    }
                }
            } else {
                deleteBtn.visibility = View.GONE
            }

            if (leave.id == focusedEntityId) {
                card.alpha = 1f
            }

            return card
        }

        // Chunked render — the admin "All" scope can carry hundreds of rows;
        // one synchronous inflate pass froze mid-range phones. ~24 per frame,
        // with the generation token abandoning stale passes on re-render or
        // view teardown.
        val gen = renderGen
        val chunk = 24
        fun renderChunk(start: Int) {
            if (gen != renderGen || _binding == null) return
            val end = minOf(start + chunk, leaves.size)
            for (i in start until end) binding.leaveList.addView(bindCard(leaves[i]))
            if (end < leaves.size) binding.leaveList.post { renderChunk(end) }
        }
        renderChunk(0)
    }

    private fun parseServerDate(parseFmt: SimpleDateFormat, raw: String?): Date? {
        return raw?.let { runCatching { parseFmt.parse(it) }.getOrNull() }
    }

    private fun parseCreationDate(raw: Double?): Date? {
        if (raw == null) return null
        val millis = when {
            raw > 1_000_000_000_000 -> raw.toLong()
            raw > 1_000_000_000 -> (raw * 1000).toLong()
            else -> raw.toLong()
        }
        return runCatching { Date(millis) }.getOrNull()
    }

    /**
     * Accepts either an ISO-8601 string ("2026-06-02T13:45:21.123Z" or
     * "2026-06-02") OR a numeric epoch encoded as a string. Returns
     * null on any failure so the caller can fall back to creationTime.
     * Used to parse the server's `decidedAt` field on approved/rejected
     * leave rows.
     */
    private fun parseIsoOrEpoch(raw: String?): Date? {
        if (raw.isNullOrBlank()) return null
        raw.toDoubleOrNull()?.let { epoch ->
            return parseCreationDate(epoch)
        }
        val isoPatterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
        )
        for (pattern in isoPatterns) {
            runCatching {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                if (pattern.endsWith("'Z'")) {
                    fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                fmt.parse(raw)?.let { return it }
            }
        }
        return null
    }

    private fun sameDate(date1: Date, date2: Date): Boolean {
        val c1 = Calendar.getInstance().apply { time = date1 }
        val c2 = Calendar.getInstance().apply { time = date2 }
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
            c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
    }

    private fun bucketForStatus(status: String?): StatusBucket {
        return when (status?.trim()?.lowercase(Locale.getDefault())) {
            "approved" -> StatusBucket.APPROVED
            "rejected" -> StatusBucket.REJECTED
            else -> StatusBucket.REVIEW
        }
    }

    private fun showRejectDialog(leaveId: String) {
        com.manjugroups.m_connect.ui.common.RejectWithReasonBottomSheet.newInstance(
            itemId = leaveId,
            resultKey = REJECT_RESULT_KEY,
            title = "Reject leave request",
            buttonText = "Reject Leave",
        ).showOnce(childFragmentManager, "reject_leave")
    }

    private fun showCancelLeaveDialog(leaveId: String) {
        val dialog = android.app.Dialog(requireContext())
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_cancel_leave, null)
        dialog.setContentView(dialogView)

        // Set the background transparent so the rounded corners of bg_dialog_card show perfectly
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Wire up buttons
        dialogView.findViewById<View>(R.id.btnDialogCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<View>(R.id.btnDialogConfirm).setOnClickListener {
            dialog.dismiss()
            viewModel.cancelLeave(
                session.bearerToken,
                leaveId,
                session.hasPermission("leaves.approve")
            )
        }

        dialog.show()
    }

    private fun resolveColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun dp(value: Int): Int {
        val density = resources.displayMetrics.density
        return (value * density).toInt()
    }

    private fun startSkeletonPulse() {
        // Delegate to the shared shimmer (breathes the placeholder blocks,
        // keeps the container opaque) instead of fading the whole
        // container — the old whole-container fade read as a blink.
        SkeletonUtils.startSkeletonPulse(binding.skeletonContainer)
    }

    private fun stopSkeletonPulse() {
        SkeletonUtils.stopSkeletonPulse(binding.skeletonContainer)
    }

    private fun showScopePopupMenu(anchor: View) {
        // Show backdrop
        binding.scopeBackdrop.visibility = View.VISIBLE
        binding.scopeBackdrop.alpha = 0f
        binding.scopeBackdrop.animate().alpha(1f).setDuration(200).start()

        val popupView = LayoutInflater.from(requireContext()).inflate(R.layout.popup_scope_menu, null)

        val popupWidth = dp(160)
        val popup = android.widget.PopupWindow(
            popupView,
            popupWidth,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = dp(12).toFloat()
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setOnDismissListener {
                binding.scopeBackdrop.animate()
                    .alpha(0f)
                    .setDuration(150)
                    .withEndAction { binding.scopeBackdrop.visibility = View.GONE }
                    .start()
            }
        }

        // Dismiss popup when backdrop is tapped
        binding.scopeBackdrop.setOnClickListener {
            popup.dismiss()
        }

        val menuMy = popupView.findViewById<TextView>(R.id.menuMyLeaves)
        val menuTeam = popupView.findViewById<View>(R.id.menuTeamLeaves)
        val tvMenuTeam = popupView.findViewById<TextView>(R.id.tvMenuTeamLeaves)
        val menuAll = popupView.findViewById<TextView>(R.id.menuAllLeaves)

        // Figma keeps all items in Inter Regular — highlight the active
        // item with the accent blue, others stay #061D3D.
        val activeColor = ContextCompat.getColor(requireContext(), R.color.chat_blue_top)
        val defaultColor = android.graphics.Color.parseColor("#061D3D")

        menuMy.setTextColor(if (activeScope == LeaveScope.MY) activeColor else defaultColor)
        tvMenuTeam?.setTextColor(if (activeScope == LeaveScope.TEAM) activeColor else defaultColor)
        menuAll?.setTextColor(if (activeScope == LeaveScope.ALL) activeColor else defaultColor)

        // Badge = the team's OPEN (awaiting-review) requests — the actionable
        // number — not the raw size of the whole dataset (which counted every
        // approved/rejected row too, e.g. the misleading "294").
        val pendingCount = viewModel.uiState.value.teamLeaves.count {
            bucketForStatus(it.status) == StatusBucket.REVIEW
        }
        val dotBadge = popupView.findViewById<TextView>(R.id.dotTeamBadge)
        if (dotBadge != null) {
            if (pendingCount > 0) {
                dotBadge.visibility = View.VISIBLE
                dotBadge.text = pendingCount.toString()
            } else {
                dotBadge.visibility = View.GONE
            }
        }

        menuMy.setOnClickListener {
            popup.dismiss()
            if (activeScope != LeaveScope.MY) {
                switchScope(LeaveScope.MY)
            }
        }

        menuTeam.setOnClickListener {
            popup.dismiss()
            if (activeScope != LeaveScope.TEAM) {
                switchScope(LeaveScope.TEAM)
            }
        }

        menuAll?.setOnClickListener {
            popup.dismiss()
            if (activeScope != LeaveScope.ALL) {
                switchScope(LeaveScope.ALL)
            }
        }

        val xOffset = anchor.width - popupWidth
        popup.showAsDropDown(anchor, xOffset, dp(4))

        popupView.alpha = 0f
        popupView.scaleX = 0.95f
        popupView.scaleY = 0.95f
        popupView.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(150)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun switchScope(scope: LeaveScope) {
        activeScope = scope
        filterStaffId = null
        if (scope == LeaveScope.MY) {
            binding.tvBalanceTitle.text = "Total Leave"
            binding.tvSelectedScope.text = "My Leaves"
        } else if (scope == LeaveScope.TEAM) {
            binding.tvBalanceTitle.text = "Team Leaves"
            binding.tvSelectedScope.text = "Team Leaves"
        } else {
            binding.tvBalanceTitle.text = "Total Leave"
            binding.tvSelectedScope.text = "All Leaves"
        }

        // Animate transition on contentscroll
        android.transition.TransitionManager.beginDelayedTransition(binding.contentScroll, android.transition.AutoTransition())

        renderState(viewModel.uiState.value)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopSkeletonPulse()
        _binding = null
    }
}
