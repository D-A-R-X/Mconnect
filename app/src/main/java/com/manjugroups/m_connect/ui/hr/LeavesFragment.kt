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
import android.widget.PopupMenu
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
    private enum class LeaveScope { MY, TEAM, ALL }

    private var _binding: FragmentLeavesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LeavesViewModel by viewModels()
    private lateinit var session: SessionManager
    private var screenMode: String = MODE_HISTORY
    private var activeScope: LeaveScope = LeaveScope.MY
    private var focusedEntityId: String? = null
    private var historyFilter: HistoryFilter = HistoryFilter.REVIEW
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
            parentFragmentManager.setFragmentResultListener(ApplyLeaveBottomSheet.RESULT_KEY_APPLIED, viewLifecycleOwner) { _, bundle ->
                val success = bundle.getBoolean("success", false)
                if (success) {
                    viewModel.load(session.bearerToken, session.hasPermission("leaves.approve"))
                }
            }
            ApplyLeaveBottomSheet.newInstance().show(parentFragmentManager, "apply_leave_sheet")
        }

        val year = Calendar.getInstance().get(Calendar.YEAR)
        binding.tvYear.text = "Period 1 Jan $year - 30 Dec $year"

        val canApprove = session.hasPermission("leaves.approve")
        if (screenMode == MODE_APPROVAL) {
            binding.tvHeaderTitle.text = "Leave Approvals"
            binding.tvHeaderSubtitle.text = "In Review"
            binding.filterRow.visibility = View.GONE
            binding.dropdownScopeSelector.visibility = View.GONE
        } else {
            binding.tvHeaderTitle.text = "Leave Summary"
            binding.tvHeaderSubtitle.text = "Submit Leave"
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

        val displayLeaves = if (screenMode == MODE_APPROVAL) {
            state.pendingApprovals
        } else {
            if (canApprove && activeScope == LeaveScope.TEAM) {
                filterHistoryLeaves(state.pendingApprovals)
            } else {
                filterHistoryLeaves(state.myLeaves)
            }
        }
        val isLoading = state.isLoading

        if (screenMode == MODE_HISTORY) {
            binding.filterRow.visibility = View.VISIBLE
            val listForCounts = if (canApprove && activeScope == LeaveScope.TEAM) state.pendingApprovals else state.myLeaves
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
        val isApprovalForRender = (canApprove && screenMode == MODE_APPROVAL) || (canApprove && screenMode == MODE_HISTORY && activeScope == LeaveScope.TEAM)
        renderLeaves(displayLeaves, isApprovalForRender)
    }

    private fun configureHistoryCard(isEmpty: Boolean) {
        val isTeamScope = screenMode == MODE_APPROVAL
        val showHeader = isTeamScope || historyFilter == HistoryFilter.REVIEW || isEmpty

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
        val isTeam = screenMode == MODE_APPROVAL || (session.hasPermission("leaves.approve") && activeScope == LeaveScope.TEAM)
        if (isTeam) {
            binding.emptyState.setTitle("No Leave Approvals")
            binding.emptyState.setDescription("There are no pending leave requests in review right now.")
            return
        }
        binding.emptyState.setTitle("No Leave Submitted!")
        binding.emptyState.setDescription(
            "Ready to catch some fresh air? Click “Submit Leave” and take that well-deserved break!"
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
                    statusColor = ContextCompat.getColor(requireContext(), R.color.chat_blue_top)
                    statusIconRes = R.drawable.ic_leave_spinner_blue
                }
            }

            card.findViewById<TextView>(R.id.tvLeaveDate).text = dateHeadingText
            card.findViewById<TextView>(R.id.tvLeaveType).text = rangeText
            card.findViewById<TextView>(R.id.tvLeaveStatus).text = "$days Day${if (days > 1) "s" else ""}"

            // Dynamic labels matching Figma: reason as left label, leave type as right label
            val reasonLabel = card.findViewById<TextView>(R.id.tvLeaveReasonLabel)
            val typeLabel = card.findViewById<TextView>(R.id.tvLeaveTypeLabel)
            reasonLabel.text = leave.reason?.trim()?.takeIf { it.isNotBlank() } ?: "Leave Date"
            val leaveTypeDisplay = leave.leaveType?.replaceFirstChar { it.uppercaseChar() }?.let { "$it Leave" } ?: "Total Leave"
            typeLabel.text = leaveTypeDisplay

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
                when (bucket) {
                    StatusBucket.REVIEW -> {
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
                deleteBtn.setOnClickListener {
                    showCancelLeaveDialog(leave.id)
                }
            } else {
                deleteBtn.visibility = View.GONE
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

    private fun showScopePopupMenu(anchor: View) {
        val popupView = LayoutInflater.from(requireContext()).inflate(R.layout.popup_scope_menu, null)
        val popup = android.widget.PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = dp(8).toFloat()
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }

        val menuMy = popupView.findViewById<TextView>(R.id.menuMyLeaves)
        val menuTeam = popupView.findViewById<View>(R.id.menuTeamLeaves)
        val tvMenuTeam = popupView.findViewById<TextView>(R.id.tvMenuTeamLeaves)
        val menuAll = popupView.findViewById<TextView>(R.id.menuAllLeaves)

        if (activeScope == LeaveScope.MY) {
            menuMy.setTypeface(null, android.graphics.Typeface.BOLD)
            menuMy.setTextColor(ContextCompat.getColor(requireContext(), R.color.chat_blue_top))
            
            tvMenuTeam?.setTypeface(null, android.graphics.Typeface.NORMAL)
            tvMenuTeam?.setTextColor(resolveColor(R.attr.colorForegroundPrimary))

            menuAll?.setTypeface(null, android.graphics.Typeface.NORMAL)
            menuAll?.setTextColor(resolveColor(R.attr.colorForegroundPrimary))
        } else if (activeScope == LeaveScope.TEAM) {
            menuMy.setTypeface(null, android.graphics.Typeface.NORMAL)
            menuMy.setTextColor(resolveColor(R.attr.colorForegroundPrimary))
            
            tvMenuTeam?.setTypeface(null, android.graphics.Typeface.BOLD)
            tvMenuTeam?.setTextColor(ContextCompat.getColor(requireContext(), R.color.chat_blue_top))

            menuAll?.setTypeface(null, android.graphics.Typeface.NORMAL)
            menuAll?.setTextColor(resolveColor(R.attr.colorForegroundPrimary))
        } else {
            menuMy.setTypeface(null, android.graphics.Typeface.NORMAL)
            menuMy.setTextColor(resolveColor(R.attr.colorForegroundPrimary))
            
            tvMenuTeam?.setTypeface(null, android.graphics.Typeface.NORMAL)
            tvMenuTeam?.setTextColor(resolveColor(R.attr.colorForegroundPrimary))

            menuAll?.setTypeface(null, android.graphics.Typeface.BOLD)
            menuAll?.setTextColor(ContextCompat.getColor(requireContext(), R.color.chat_blue_top))
        }

        val pendingCount = viewModel.uiState.value.pendingApprovals.size
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

        popupView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val xOffset = anchor.width - popupView.measuredWidth
        popup.showAsDropDown(anchor, xOffset, dp(4))

        popupView.alpha = 0f
        popupView.scaleX = 0.9f
        popupView.scaleY = 0.9f
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
