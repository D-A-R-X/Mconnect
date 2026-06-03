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
import com.manjugroups.m_connect.ui.common.navigateUp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class LeavesFragment : Fragment() {

    private enum class HistoryFilter { REVIEW, APPROVED, REJECTED }
    private enum class StatusBucket { REVIEW, APPROVED, REJECTED }
    // Top-level scope in History mode for higher-role users:
    //   • MY    – the user's own leaves (default; only option for
    //             regular employees, chip is hidden)
    //   • TEAM  – leaves submitted by the user's reporting team
    //             (pendingApprovals, surfaces Approve/Reject buttons)
    //   • ALL   – every leave across the org (admin / HR view; for
    //             now reuses myLeaves as a passthrough since the
    //             mobile API doesn't yet ship an "all-leaves" feed)
    private enum class Scope(val label: String) {
        MY("My Leaves"),
        TEAM("Team Leaves"),
        ALL("All Leaves"),
    }

    private var _binding: FragmentLeavesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LeavesViewModel by viewModels()
    private lateinit var session: SessionManager
    private var screenMode: String = MODE_HISTORY
    private var focusedEntityId: String? = null
    private var historyFilter: HistoryFilter = HistoryFilter.REVIEW
    private var scope: Scope = Scope.MY
    private var skeletonAnimator: ObjectAnimator? = null

    companion object {
        private const val ARG_MODE = "mode"
        private const val ARG_ENTITY_ID = "entity_id"
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

        binding.btnBack.setOnClickListener { navigateUp() }
        binding.btnBack.visibility = if (screenMode == MODE_APPROVAL) View.VISIBLE else View.GONE
        BottomActionInsets.applyAboveSystemNavAndTabs(binding.btnApplyLeave)
        binding.btnApplyLeave.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, ApplyLeaveFragment())
                .addToBackStack(null)
                .commit()
        }

        val year = Calendar.getInstance().get(Calendar.YEAR)
        binding.tvYear.text = "Period 1 Jan $year - 30 Dec $year"

        if (screenMode == MODE_APPROVAL) {
            binding.tvHeaderTitle.text = "Leave Approvals"
            binding.tvHeaderSubtitle.text = "In Review"
            binding.tvSectionTitle.text = "Leave Approvals"
            binding.tvSectionSubtitle.visibility = View.GONE
            binding.filterRow.visibility = View.GONE
        } else {
            binding.tvHeaderTitle.text = "Leave Summary"
            binding.tvHeaderSubtitle.text = "Submit Leave"
            binding.tvSectionTitle.text = "Leave Submitted"
            binding.tvSectionSubtitle.visibility = View.VISIBLE
            binding.filterRow.visibility = View.VISIBLE
            setupFilterTabs()
            updateFilterUi()
            // Higher-role scope chip — visible only when the user can
            // approve leaves. Regular employees never see the chip and
            // stay on the My Leaves view by default.
            val canApprove = session.hasPermission("leaves.approve")
            binding.scopeChip.visibility = if (canApprove) View.VISIBLE else View.GONE
            if (canApprove) setupScopeChip()
            updateScopeUi()
        }

        collectState()
        collectEvents()
        viewModel.load(session.bearerToken, session.hasPermission("leaves.approve"))

        // Pull-to-refresh re-runs viewModel.load() so balance + history
        // come back fresh. The spinner is dismissed in collectState() the
        // moment the next non-loading state lands.
        binding.leavesRefresh.setupPullToRefresh {
            viewModel.load(session.bearerToken, session.hasPermission("leaves.approve"))
        }
    }

    private fun setupFilterTabs() {
        binding.tabReview.setOnClickListener {
            historyFilter = HistoryFilter.REVIEW
            updateFilterUi()
            renderState(viewModel.uiState.value)
        }
        binding.tabApproved.setOnClickListener {
            historyFilter = HistoryFilter.APPROVED
            updateFilterUi()
            renderState(viewModel.uiState.value)
        }
        binding.tabRejected.setOnClickListener {
            historyFilter = HistoryFilter.REJECTED
            updateFilterUi()
            renderState(viewModel.uiState.value)
        }
    }

    private fun setupScopeChip() {
        binding.scopeChip.setOnClickListener { anchor ->
            val popup = android.widget.PopupMenu(requireContext(), anchor)
            // Stable menu ids — Scope.ordinal lines up with menu order
            // so we can map back without a separate switch.
            popup.menu.add(0, Scope.MY.ordinal, 0, Scope.MY.label)
            popup.menu.add(0, Scope.TEAM.ordinal, 1, Scope.TEAM.label)
            popup.menu.add(0, Scope.ALL.ordinal, 2, Scope.ALL.label)
            popup.setOnMenuItemClickListener { item ->
                val picked = Scope.values()
                    .firstOrNull { it.ordinal == item.itemId } ?: return@setOnMenuItemClickListener false
                if (picked != scope) {
                    scope = picked
                    updateScopeUi()
                    renderState(viewModel.uiState.value)
                }
                true
            }
            popup.show()
        }
    }

    private fun updateScopeUi() {
        binding.tvScopeLabel.text = scope.label
        // Switch the header title so the screen self-labels (matches
        // the design — "Total Leave" for My, "Team Leaves" for Team).
        binding.tvBalanceTitle.text = when (scope) {
            Scope.MY -> "Total Leave"
            Scope.TEAM -> "Team Leaves"
            Scope.ALL -> "All Leaves"
        }
        // Status filter chips (Review / Approved / Rejected) are only
        // meaningful for the My scope today — Team scope ships the
        // pending pile straight through (server returns pending only),
        // and All scope reuses My data so chips still apply there.
        binding.filterRow.visibility =
            if (scope == Scope.TEAM) View.GONE else View.VISIBLE
    }

    private fun updateFilterUi() {
        styleFilterTab(binding.tabReview, historyFilter == HistoryFilter.REVIEW)
        styleFilterTab(binding.tabApproved, historyFilter == HistoryFilter.APPROVED)
        styleFilterTab(binding.tabRejected, historyFilter == HistoryFilter.REJECTED)
    }

    private fun styleFilterTab(tab: TextView, selected: Boolean) {
        if (selected) {
            tab.setBackgroundResource(R.drawable.bg_leave_filter_active)
            tab.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
        } else {
            tab.background = null
            tab.setTextColor(resolveColor(R.attr.colorForegroundSecondary))
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
        // Notification deep-link → approver pile (unchanged).
        // History mode now routes by scope:
        //   • MY   → own leaves, status-filtered
        //   • TEAM → team pending approvals, Approve/Reject inline
        //   • ALL  → own + status filter applied (passthrough until
        //            a dedicated "all-leaves" endpoint exists)
        val displayLeaves = if (screenMode == MODE_APPROVAL) {
            state.pendingApprovals
        } else when (scope) {
            Scope.MY -> filterHistoryLeaves(state.myLeaves)
            Scope.TEAM -> state.pendingApprovals
            Scope.ALL -> filterHistoryLeaves(state.myLeaves)
        }
        val isLoading = state.isLoading
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

        configureHistoryCard(displayLeaves.isEmpty() && !isLoading)

        binding.skeletonContainer.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.leaveList.visibility = if (isLoading) View.GONE else View.VISIBLE
        if (isLoading) {
            startSkeletonPulse()
            binding.emptyState.visibility = View.GONE
            return
        }

        stopSkeletonPulse()
        setEmptyCopy(displayLeaves.isEmpty())
        // Approve/Reject inline on rows in:
        //   • approval mode (notification deep-link), AND
        //   • history mode with Team scope (manager reviewing pending
        //     team leaves from the chip dropdown)
        val showApprovalActions = canApprove && (
            screenMode == MODE_APPROVAL
                || (screenMode == MODE_HISTORY && scope == Scope.TEAM)
        )
        renderLeaves(displayLeaves, showApprovalActions)
    }

    private fun configureHistoryCard(isEmpty: Boolean) {
        val showHeader = screenMode == MODE_APPROVAL || historyFilter == HistoryFilter.REVIEW || isEmpty
        binding.tvSectionTitle.visibility = if (showHeader) View.VISIBLE else View.GONE
        binding.tvSectionSubtitle.visibility = if (showHeader) View.VISIBLE else View.GONE

        if (screenMode == MODE_APPROVAL) {
            binding.tvSectionTitle.text = "Leave Approvals"
            binding.tvSectionSubtitle.visibility = View.GONE
        } else {
            when (historyFilter) {
                HistoryFilter.REVIEW -> {
                    binding.tvSectionTitle.text = "Leave Submitted"
                    binding.tvSectionSubtitle.text = "Leave information"
                }
                HistoryFilter.APPROVED -> {
                    binding.tvSectionTitle.text = "Approved Leave"
                    binding.tvSectionSubtitle.text = "Approved leave information"
                }
                HistoryFilter.REJECTED -> {
                    binding.tvSectionTitle.text = "Rejected Leave"
                    binding.tvSectionSubtitle.text = "Rejected leave information"
                }
            }
        }

        if (showHeader) {
            binding.historyCard.setBackgroundResource(R.drawable.bg_stat_card)
            binding.historyCard.setPadding(dp(12), dp(12), dp(12), dp(12))
        } else {
            binding.historyCard.background = null
            binding.historyCard.setPadding(0, 0, 0, 0)
        }
    }

    private fun setEmptyCopy(isEmpty: Boolean) {
        if (!isEmpty) return
        if (screenMode == MODE_APPROVAL) {
            binding.tvEmpty.text = "No Leave Approvals"
            binding.tvEmptyHint.text = "There are no pending leave requests in review right now."
            return
        }
        when (historyFilter) {
            HistoryFilter.REVIEW -> {
                binding.tvEmpty.text = "No Leave Submitted!"
                binding.tvEmptyHint.text =
                    "Ready to catch some fresh air? Click 'Submit Leave' and take that well-deserved break!"
            }
            HistoryFilter.APPROVED -> {
                binding.tvEmpty.text = "No Approved Leave"
                binding.tvEmptyHint.text = "Your approved leave requests will appear here."
            }
            HistoryFilter.REJECTED -> {
                binding.tvEmpty.text = "No Rejected Leave"
                binding.tvEmptyHint.text = "If any leave gets rejected, you'll find it listed here."
            }
        }
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

    private fun filterHistoryLeaves(leaves: List<LeaveData>): List<LeaveData> {
        return leaves.filter { leave ->
            when (historyFilter) {
                HistoryFilter.REVIEW -> bucketForStatus(leave.status) == StatusBucket.REVIEW
                HistoryFilter.APPROVED -> bucketForStatus(leave.status) == StatusBucket.APPROVED
                HistoryFilter.REJECTED -> bucketForStatus(leave.status) == StatusBucket.REJECTED
            }
        }
    }

    private fun renderLeaves(leaves: List<LeaveData>, approvalMode: Boolean) {
        binding.leaveList.removeAllViews()
        binding.emptyState.visibility = if (leaves.isEmpty()) View.VISIBLE else View.GONE

        val headingFmt = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
        val rangeFmt = SimpleDateFormat("d MMM", Locale.getDefault())
        val parseFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val statusFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

        leaves.forEach { leave ->
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

            val statusNote: String
            val statusColor: Int
            val statusIconRes: Int
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
                    statusColor = ContextCompat.getColor(requireContext(), R.color.lt_accent_primary)
                    statusIconRes = R.drawable.ic_leave_status_review
                }
            }

            card.findViewById<TextView>(R.id.tvLeaveDate).text = dateHeadingText
            card.findViewById<TextView>(R.id.tvLeaveType).text = rangeText
            card.findViewById<TextView>(R.id.tvLeaveStatus).text = "$days Day${if (days > 1) "s" else ""}"

            val reasonText = card.findViewById<TextView>(R.id.tvLeaveReason)
            reasonText.text = statusNote
            reasonText.setTextColor(statusColor)
            card.findViewById<android.widget.ImageView>(R.id.ivLeaveStatusIcon).setImageResource(statusIconRes)

            val staffName = card.findViewById<TextView>(R.id.tvLeaveStaffName)
            val staffInitial = card.findViewById<TextView>(R.id.tvLeaveStaffInitial)
            val staffAvatar = card.findViewById<android.widget.ImageView>(R.id.ivLeaveStaffAvatar)
            val staffRow = card.findViewById<View>(R.id.staffInfoRow)
            val byLabel = card.findViewById<TextView>(R.id.tvBy)
            val actionRow = card.findViewById<View>(R.id.leaveActionRow)
            val approveButton = card.findViewById<TextView>(R.id.btnApproveLeave)
            val rejectButton = card.findViewById<TextView>(R.id.btnRejectLeave)

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
            } else {
                actionRow.visibility = View.GONE
            }

            // Per-row cancel affordance — visible ONLY on the user's
            // own pending requests in History mode (My Leaves view).
            // Mirrors the Permissions screen's trash icon. Hidden on
            // approver-mode rows (those already get Approve/Reject)
            // and on Approved/Rejected buckets (terminal states).
            val cancelIcon = card.findViewById<android.widget.ImageView>(R.id.ivLeaveCancel)
            val canCancel = !approvalMode
                && screenMode == MODE_HISTORY
                && scope == Scope.MY
                && bucket == StatusBucket.REVIEW
                && leave.id != null
            cancelIcon.visibility = if (canCancel) View.VISIBLE else View.GONE
            if (canCancel) {
                cancelIcon.setOnClickListener {
                    val id = leave.id ?: return@setOnClickListener
                    AlertDialog.Builder(requireContext())
                        .setTitle("Cancel leave request?")
                        .setMessage("This will withdraw your pending request.")
                        .setPositiveButton("Cancel Request") { _, _ ->
                            viewModel.cancelLeave(
                                session.bearerToken,
                                id,
                                session.hasPermission("leaves.approve"),
                            )
                        }
                        .setNegativeButton("Keep", null)
                        .show()
                }
            } else {
                cancelIcon.setOnClickListener(null)
            }

            if (leave.id == focusedEntityId) {
                card.alpha = 1f
            }

            binding.leaveList.addView(card)
        }
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
        val input = EditText(requireContext()).apply {
            hint = "Reason for rejection"
            minLines = 3
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Reject leave request")
            .setView(input)
            .setPositiveButton("Reject") { _, _ ->
                viewModel.rejectLeave(
                    session.bearerToken,
                    leaveId,
                    input.text?.toString()?.trim().orEmpty().ifBlank { "Rejected" },
                    session.hasPermission("leaves.approve")
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        if (skeletonAnimator?.isRunning == true) return
        skeletonAnimator = ObjectAnimator.ofFloat(binding.skeletonContainer, View.ALPHA, 0.55f, 1f).apply {
            duration = 650L
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun stopSkeletonPulse() {
        skeletonAnimator?.cancel()
        skeletonAnimator = null
        binding.skeletonContainer.alpha = 1f
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopSkeletonPulse()
        _binding = null
    }
}
