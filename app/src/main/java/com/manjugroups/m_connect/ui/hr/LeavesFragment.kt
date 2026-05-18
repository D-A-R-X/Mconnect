package com.manjugroups.m_connect.ui.hr

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.PopupWindow
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
import com.manjugroups.m_connect.ui.common.ProfilePhotos
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class LeavesFragment : Fragment() {

    private enum class HistoryFilter { REVIEW, APPROVED, REJECTED }
    private enum class StatusBucket { REVIEW, APPROVED, REJECTED }

    // Three-way scope (matches the Attendance screen's role-aware tabs).
    // MINE always works; TEAM reuses the existing pending-approvals feed
    // for the Review tab; ALL is layout-only until the org-wide endpoint
    // lands, so it shows a backend-pending notice rather than an empty
    // grey state.
    private enum class Scope(val label: String) {
        MINE("My Leaves"),
        TEAM("Team Leaves"),
        ALL("All Leaves")
    }

    private var _binding: FragmentLeavesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LeavesViewModel by viewModels()
    private lateinit var session: SessionManager
    private var screenMode: String = MODE_HISTORY
    private var focusedEntityId: String? = null
    private var historyFilter: HistoryFilter = HistoryFilter.REVIEW
    private var scope: Scope = Scope.MINE
    private var skeletonAnimator: ObjectAnimator? = null
    // First onResume runs immediately after onViewCreated's initial load —
    // skip it so we don't fire a duplicate request on first open. Every
    // subsequent onResume (returning from ApplyLeave, switching apps, etc.)
    // triggers a refresh so just-submitted / just-cancelled rows show up.
    private var skipNextResumeRefresh: Boolean = true

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

        applyStatusBarInset()
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.btnBack.visibility = if (screenMode == MODE_APPROVAL) View.VISIBLE else View.GONE
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
            binding.scopeRow.visibility = View.GONE
        } else {
            binding.tvHeaderTitle.text = "Leave Summary"
            binding.tvHeaderSubtitle.text = "Submit Leave"
            binding.tvSectionTitle.text = "Leave Submitted"
            binding.tvSectionSubtitle.visibility = View.VISIBLE
            binding.filterRow.visibility = View.VISIBLE
            setupFilterTabs()
            setupScopeDropdown()
            updateFilterUi()
            updateScopeLabel()
        }

        collectState()
        collectEvents()
        viewModel.load(
            session.bearerToken,
            canApprove = session.hasPermission("leaves.approve"),
            canViewAll = session.hasPermission("leaves.viewAll"),
        )
        skipNextResumeRefresh = true
    }

    /**
     * Grow the banner header's top padding by the system-bar inset so
     * the "Leave Summary" title always sits below the status bar and
     * any front-camera cutout. We use full-bleed mode on this screen
     * (the blue gradient extends behind the status bar), so we have to
     * pad manually — same pattern as `HomeFragment.applyStatusBarInset`.
     */
    private fun applyStatusBarInset() {
        val basePaddingTop = binding.headerContainer.paddingTop
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.headerContainer) { _, insets ->
            val b = _binding ?: return@setOnApplyWindowInsetsListener insets
            val topInset = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
            b.headerContainer.setPadding(
                b.headerContainer.paddingLeft,
                basePaddingTop + topInset,
                b.headerContainer.paddingRight,
                b.headerContainer.paddingBottom
            )
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(binding.headerContainer)
    }

    /**
     * Scope dropdown — only shown to users with `leaves.viewAll` /
     * `leaves.approve`. Lets a manager flip between their own leaves,
     * their direct reports' queue and the org-wide list.
     *
     * Uses a custom PopupWindow (rounded white card per design) rather
     * than the default Material PopupMenu, so the styling stays
     * consistent with the rest of the leave UI. The Team Leaves row
     * carries a red badge whenever the pending-approvals queue is
     * non-empty.
     */
    private fun setupScopeDropdown() {
        val canViewAll = session.hasPermission("leaves.viewAll") ||
            session.hasPermission("leaves.approve")
        binding.scopeRow.visibility = if (canViewAll) View.VISIBLE else View.GONE
        if (!canViewAll) return

        binding.scopeRow.setOnClickListener { anchor -> showScopeMenu(anchor) }
    }

    private fun showScopeMenu(anchor: View) {
        val content = layoutInflater.inflate(R.layout.popup_scope_menu, null, false)
        val popup = PopupWindow(
            content,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            elevation = resources.displayMetrics.density * 6f
            // Transparent background so the rounded drawable on the
            // inflated content reads cleanly (system would otherwise
            // paint a square white backdrop behind it).
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            isOutsideTouchable = true
        }

        // Light the Team row's red badge whenever the team queue has any
        // items in Review — same signal as the chip's own dot.
        val pendingTeam = viewModel.uiState.value.pendingApprovals
            .any { bucketForStatus(it.status) == StatusBucket.REVIEW }
        content.findViewById<View>(R.id.dotTeamBadge).visibility =
            if (pendingTeam) View.VISIBLE else View.GONE

        fun pick(target: Scope) {
            popup.dismiss()
            if (target != scope) {
                scope = target
                updateScopeLabel()
                renderState(viewModel.uiState.value)
            }
        }
        content.findViewById<View>(R.id.menuMyLeaves).setOnClickListener { pick(Scope.MINE) }
        content.findViewById<View>(R.id.menuTeamLeaves).setOnClickListener { pick(Scope.TEAM) }
        content.findViewById<View>(R.id.menuAllLeaves).setOnClickListener { pick(Scope.ALL) }

        // Anchor below the chip with a small downward offset so the popup
        // visually hangs off the chip rather than sitting flush against it.
        popup.showAsDropDown(anchor, 0, dp(4))
    }

    private fun updateScopeLabel() {
        binding.tvScopeLabel.text = scope.label
        // Card title flips between owner ("Total Leave") and manager
        // ("Team Leaves") framing depending on which scope is active,
        // matching the design's header swap across the four frames.
        binding.tvBalanceTitle.text = when (scope) {
            Scope.MINE -> "Total Leave"
            Scope.TEAM, Scope.ALL -> "Team Leaves"
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

    /**
     * Inline count next to each tab label, e.g. "Review (3)". Design
     * always shows the count — even at zero — so the tabs read as
     * stat readouts, not state toggles. Counts come from the currently-
     * scoped source list so manager-tab counts reflect the team pipeline.
     */
    private fun updateTabCounts(source: List<LeaveData>) {
        val reviewCount = source.count { bucketForStatus(it.status) == StatusBucket.REVIEW }
        val approvedCount = source.count { bucketForStatus(it.status) == StatusBucket.APPROVED }
        val rejectedCount = source.count { bucketForStatus(it.status) == StatusBucket.REJECTED }

        binding.tabReview.text = "Review ($reviewCount)"
        binding.tabApproved.text = "Approved ($approvedCount)"
        binding.tabRejected.text = "Rejected ($rejectedCount)"
    }

    /**
     * Surfaces the red notification dot on the scope chip whenever the
     * current scope has at least one item in Review. Lets a manager see
     * at a glance that the team queue is non-empty without expanding
     * the dropdown.
     */
    private fun updateScopeBadge(source: List<LeaveData>) {
        val hasPending = source.any { bucketForStatus(it.status) == StatusBucket.REVIEW }
        binding.dotScopeBadge.visibility = if (hasPending) View.VISIBLE else View.GONE
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
        val isLoading = state.isLoading

        // Resolve the source list per scope. All three scopes are now
        // live: MINE → user's own, TEAM → pending-approvals queue,
        // ALL → org-wide list (server-side gated by `leaves.viewAll`).
        val sourceList = when {
            screenMode == MODE_APPROVAL -> state.pendingApprovals
            scope == Scope.MINE -> state.myLeaves
            scope == Scope.TEAM -> state.pendingApprovals
            scope == Scope.ALL -> state.allLeaves
            else -> emptyList()
        }
        val displayLeaves = when (screenMode) {
            MODE_APPROVAL -> state.pendingApprovals
            else -> filterHistoryLeaves(sourceList)
        }

        // Refresh the per-tab count badges off the un-filtered scope source
        // so a manager always sees how many items sit in each bucket. The
        // scope-chip red dot follows the same source — it lights up when
        // the current scope has anything in Review.
        if (screenMode == MODE_HISTORY) {
            updateTabCounts(sourceList)
            updateScopeBadge(sourceList)
        }

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
        // Team / All scopes: surface Accept/Reject on Review items when
        // the user can approve. The backend will reject the call for any
        // row this specific user isn't authorized for, so the button
        // visibility is best-effort permission gating, not load-bearing.
        val showActions = (screenMode == MODE_APPROVAL) ||
            (scope != Scope.MINE && historyFilter == HistoryFilter.REVIEW && canApprove)
        renderLeaves(displayLeaves, showActions)
    }

    private fun configureHistoryCard(isEmpty: Boolean) {
        // Design only surfaces the "Leave Submitted / Leave information"
        // header when the list is empty (it sits inside the empty state).
        // For populated lists the cards speak for themselves.
        val showHeader = isEmpty || screenMode == MODE_APPROVAL
        binding.tvSectionTitle.visibility = if (showHeader) View.VISIBLE else View.GONE
        binding.tvSectionSubtitle.visibility = if (showHeader) View.VISIBLE else View.GONE

        if (screenMode == MODE_APPROVAL) {
            binding.tvSectionTitle.text = "Leave Approvals"
            binding.tvSectionSubtitle.visibility = View.GONE
        } else {
            // Manager scopes get an "Approvals" framing for the Review tab
            // since the rows are actionable; My-scope keeps the staff-side
            // "Leave Submitted" framing.
            val managerScope = scope != Scope.MINE
            when (historyFilter) {
                HistoryFilter.REVIEW -> {
                    binding.tvSectionTitle.text =
                        if (managerScope) "Leave Approvals" else "Leave Submitted"
                    binding.tvSectionSubtitle.text =
                        if (managerScope) "Pending review" else "Leave information"
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
            // For a Review row we want the *application* date in the chip
            // ("In Review at 19 Sept 2024"), not the most-recent action.
            // appliedOn / _creationTime both encode that.
            val reviewDateText = (parseCreationDate(leave.appliedOn ?: leave.createdAt))
                ?.let { statusFmt.format(it) }
            // For Approved/Rejected we prefer the action timestamp.
            val decisionDate = parseCreationDate(leave.approvedOn) ?: parseCreationDate(leave.createdAt)
            val decisionDateText = decisionDate?.let { statusFmt.format(it) }
            // "By <name>" — `currentApproverName` is the workflow engine's
            // next-actor for in-review docs; for decided docs use the
            // approver's name. Fall back to the legacy reporting officer
            // when neither is present so the chip is never empty.
            val approverName = leave.currentApproverName?.takeIf { it.isNotBlank() }
                ?: leave.approvedByName?.takeIf { it.isNotBlank() }
                ?: leave.reportingToName?.takeIf { it.isNotBlank() }

            val statusNote: String
            val statusColor: Int
            val statusIconRes: Int
            when (bucket) {
                StatusBucket.APPROVED -> {
                    // Design uses "Accepted" as the success chip label.
                    statusNote = if (decisionDateText.isNullOrBlank()) "Accepted"
                        else "Accepted at $decisionDateText"
                    statusColor = ContextCompat.getColor(requireContext(), R.color.lt_success)
                    statusIconRes = R.drawable.ic_leave_status_approved
                }
                StatusBucket.REJECTED -> {
                    statusNote = if (decisionDateText.isNullOrBlank()) "Rejected"
                        else "Rejected at $decisionDateText"
                    statusColor = ContextCompat.getColor(requireContext(), R.color.lt_error)
                    statusIconRes = R.drawable.ic_leave_status_rejected
                }
                StatusBucket.REVIEW -> {
                    // "In Review at 19 Sept 2024 By Mukesh" — fall back to
                    // shorter forms when a piece is missing rather than
                    // showing "By null".
                    statusNote = buildString {
                        append("In Review")
                        if (!reviewDateText.isNullOrBlank()) append(" at $reviewDateText")
                        if (approverName != null) append(" By $approverName")
                    }
                    statusColor = ContextCompat.getColor(requireContext(), R.color.lt_accent_primary)
                    statusIconRes = R.drawable.ic_leave_status_review
                }
            }

            card.findViewById<TextView>(R.id.tvLeaveDate).text = dateHeadingText
            // Left column: reason as the label, date range as the bold value.
            // Right column: leave type as the label, days as the bold value.
            // Matches the design's "Family Trip to Thailand / 20 Sept - 22 Sept"
            // + "Sick Leave / 2 Days" layout.
            val reasonLabel = leave.reason?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "Leave"
            card.findViewById<TextView>(R.id.tvLeaveReasonLabel).text = reasonLabel
            card.findViewById<TextView>(R.id.tvLeaveType).text = rangeText
            card.findViewById<TextView>(R.id.tvLeaveTypeLabel).text =
                prettyLeaveType(leave.leaveType)
            card.findViewById<TextView>(R.id.tvLeaveStatus).text =
                "$days Day${if (days > 1) "s" else ""}"

            val reasonText = card.findViewById<TextView>(R.id.tvLeaveReason)
            reasonText.text = statusNote
            reasonText.setTextColor(statusColor)
            card.findViewById<android.widget.ImageView>(R.id.ivLeaveStatusIcon).setImageResource(statusIconRes)

            val staffName = card.findViewById<TextView>(R.id.tvLeaveStaffName)
            val staffInitial = card.findViewById<TextView>(R.id.tvLeaveStaffInitial)
            val staffAvatar = card.findViewById<android.widget.ImageView>(R.id.ivLeaveStaffAvatar)
            val staffVerified = card.findViewById<android.widget.ImageView>(R.id.ivLeaveStaffVerified)
            val staffRow = card.findViewById<View>(R.id.staffInfoRow)
            val byLabel = card.findViewById<TextView>(R.id.tvBy)
            val actionRow = card.findViewById<View>(R.id.leaveActionRow)
            // The Accept/Reject buttons are now LinearLayout (icon + label)
            // per the design's full-width pill shape — pick them up as View
            // so the click handlers don't care about the inner structure.
            val approveButton = card.findViewById<View>(R.id.btnApproveLeave)
            val rejectButton = card.findViewById<View>(R.id.btnRejectLeave)

            val displayName = leave.staffName?.trim().takeUnless { it.isNullOrBlank() } ?: "Self"
            val initial = displayName.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
            staffRow.visibility = View.VISIBLE
            byLabel.visibility = View.VISIBLE
            staffName.visibility = View.VISIBLE
            staffName.text = displayName
            staffInitial.text = initial

            // If this leave belongs to the current user, swap the initial chip for
            // their profile photo and surface the verified-tick badge next to the
            // name (same convention the Home header uses).
            val isCurrentUser = displayName.equals("Self", ignoreCase = true) ||
                displayName.equals(session.userName?.trim(), ignoreCase = true) ||
                leave.staffName.isNullOrBlank()
            val resolvedPhoto = if (isCurrentUser) ProfilePhotos.resolve(session.userPhotoUrl) else null
            if (resolvedPhoto != null) {
                staffAvatar.visibility = View.VISIBLE
                staffInitial.visibility = View.INVISIBLE
                staffAvatar.load(resolvedPhoto) {
                    crossfade(true)
                    transformations(CircleCropTransformation())
                }
            } else {
                staffAvatar.visibility = View.GONE
                staffInitial.visibility = View.VISIBLE
            }
            staffVerified.visibility = if (isCurrentUser) View.VISIBLE else View.GONE

            val decisionRow = card.findViewById<View>(R.id.leaveDecisionRow)
            val decisionPill = card.findViewById<TextView>(R.id.tvLeaveDecisionPill)
            val decisionIcon = card.findViewById<android.widget.ImageView>(R.id.ivLeaveDecisionIcon)
            val cancelBtn = card.findViewById<android.widget.ImageView>(R.id.btnLeaveCancel)
            // Four card states:
            //   • Team scope, pending → Accept/Reject full-width buttons.
            //   • Team scope, decided → read-only "Accepted"/"Rejected"
            //     full-width pill (matches design).
            //   • My scope, pending → trash icon visible to cancel.
            //   • My scope, decided → no row; status text is enough.
            val managerView = approvalMode || scope != Scope.MINE
            val showLiveActions = approvalMode && bucket == StatusBucket.REVIEW
            val showDecisionPill = managerView && bucket != StatusBucket.REVIEW &&
                screenMode == MODE_HISTORY
            val showCancel = scope == Scope.MINE && bucket == StatusBucket.REVIEW &&
                screenMode == MODE_HISTORY

            if (showLiveActions) {
                actionRow.visibility = View.VISIBLE
                decisionRow.visibility = View.GONE
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
            } else if (showDecisionPill) {
                actionRow.visibility = View.GONE
                decisionRow.visibility = View.VISIBLE
                when (bucket) {
                    StatusBucket.APPROVED -> {
                        decisionRow.setBackgroundResource(R.drawable.bg_leave_decision_accepted)
                        decisionIcon.setImageResource(R.drawable.ic_leave_action_check)
                        decisionIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(requireContext(), R.color.lt_success)
                        )
                        decisionPill.text = "Accepted"
                        decisionPill.setTextColor(
                            ContextCompat.getColor(requireContext(), R.color.lt_success)
                        )
                    }
                    StatusBucket.REJECTED -> {
                        decisionRow.setBackgroundResource(R.drawable.bg_leave_decision_rejected)
                        decisionIcon.setImageResource(R.drawable.ic_leave_action_x)
                        decisionIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(requireContext(), R.color.lt_error)
                        )
                        decisionPill.text = "Rejected"
                        decisionPill.setTextColor(
                            ContextCompat.getColor(requireContext(), R.color.lt_error)
                        )
                    }
                    else -> { /* unreachable */ }
                }
            } else {
                actionRow.visibility = View.GONE
                decisionRow.visibility = View.GONE
            }

            cancelBtn.visibility = if (showCancel) View.VISIBLE else View.GONE
            if (showCancel) {
                cancelBtn.setOnClickListener {
                    leave.id?.let { id -> confirmCancelLeave(id) }
                }
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

    private fun sameDate(date1: Date, date2: Date): Boolean {
        val c1 = Calendar.getInstance().apply { time = date1 }
        val c2 = Calendar.getInstance().apply { time = date2 }
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
            c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * Backend leave codes ➝ user-facing labels matching the design's
     * category list. Anything not in the map is title-cased so policy-
     * driven extras (e.g. "compensatory") still display sensibly.
     */
    private fun prettyLeaveType(raw: String?): String {
        val key = raw?.trim()?.lowercase(Locale.getDefault()) ?: return "Leave"
        return when (key) {
            "sick" -> "Sick Leave"
            "earned", "annual", "vacation" -> "Annual Leave"
            "casual", "personal" -> "Personal Leave"
            "maternity", "paternity" -> "Maternity/Paternity Leave"
            "bereavement" -> "Bereavement Leave"
            "jury", "jury_duty" -> "Jury Duty Leave"
            "compassionate" -> "Compassionate Leave"
            "unpaid" -> "Unpaid Leave"
            "compensatory" -> "Compensatory Off"
            else -> key.split("_", " ").joinToString(" ") { part ->
                part.replaceFirstChar { c ->
                    if (c.isLowerCase()) c.titlecase(Locale.getDefault()) else c.toString()
                }
            }.ifBlank { "Leave" }
        }
    }

    private fun bucketForStatus(status: String?): StatusBucket {
        return when (status?.trim()?.lowercase(Locale.getDefault())) {
            "approved" -> StatusBucket.APPROVED
            "rejected" -> StatusBucket.REJECTED
            else -> StatusBucket.REVIEW
        }
    }

    /**
     * Cancel-leave confirmation. Only invoked for the user's own
     * still-pending leaves (the trash icon is hidden in every other
     * state). Backend rejects a cancel call on already-decided leaves,
     * so this is a soft guardrail rather than load-bearing logic.
     */
    private fun confirmCancelLeave(leaveId: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Cancel leave request")
            .setMessage("Withdraw this leave request? Your approver will be notified.")
            .setPositiveButton("Cancel leave") { _, _ ->
                viewModel.cancelLeave(
                    session.bearerToken,
                    leaveId,
                    session.hasPermission("leaves.approve")
                )
            }
            .setNegativeButton("Keep", null)
            .show()
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
        com.manjugroups.m_connect.ui.common.SkeletonUtils.startSkeletonPulse(binding.skeletonContainer)
    }

    private fun stopSkeletonPulse() {
        com.manjugroups.m_connect.ui.common.SkeletonUtils.stopSkeletonPulse(binding.skeletonContainer)
    }

    override fun onResume() {
        super.onResume()
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(false)
        // Match the Attendance/Home page convention — system status bar transparent,
        // blue header bleeds to the very top of the screen.
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTopBarAppearance(
            android.graphics.Color.parseColor("#0B61CA"),
            darkStatusIcons = false,
            fullBleed = true,
        )
        // Re-fetch on every resume except the one immediately after the
        // initial load in onViewCreated. Picks up rows submitted in the
        // apply flow, cancellations done elsewhere, and team-queue updates.
        if (skipNextResumeRefresh) {
            skipNextResumeRefresh = false
        } else {
            viewModel.refresh()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopSkeletonPulse()
        _binding = null
    }
}
