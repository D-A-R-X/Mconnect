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
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentPermissionsBinding
import com.manjugroups.m_connect.network.PermissionData
import com.manjugroups.m_connect.notifications.WorkflowNotificationRoute
import com.manjugroups.m_connect.ui.common.navigateUp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

class PermissionsFragment : Fragment() {

    private enum class HistoryFilter { REVIEW, APPROVED, REJECTED }
    private enum class StatusBucket { REVIEW, APPROVED, REJECTED }
    // Top-level scope inside History mode: My own permissions vs the
    // user's reporting-team's pending approvals. Team is only
    // available when canApprove() is true. Default = MY.
    private enum class Scope { MY, TEAM, ALL }

    private var _binding: FragmentPermissionsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PermissionsViewModel by viewModels()
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

        fun newInstance(mode: String = MODE_HISTORY, entityId: String? = null): PermissionsFragment {
            return PermissionsFragment().apply {
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
        _binding = FragmentPermissionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        binding.summaryHeader.setOnBackClickListener { navigateUp() }
        binding.summaryHeader.setBackButtonVisible(screenMode == MODE_APPROVAL)
        BottomActionInsets.applyAboveSystemNavAndTabs(binding.btnApplyPermission)
        binding.btnApplyPermission.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, ApplyPermissionFragment())
                .addToBackStack(null)
                .commit()
        }

        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR)
        binding.tvYear.text = "Period 1 Jan $year - 30 Dec $year"

        if (screenMode == MODE_APPROVAL) {
            binding.summaryHeader.setTitle("Permission Approvals")
            binding.summaryHeader.setSubtitle("Review Requests")
            binding.permissionsRefresh.isEnabled = false
            binding.btnApplyPermission.visibility = View.GONE
            binding.filterRow.visibility = View.GONE
        } else {
            binding.summaryHeader.setTitle("Permission Summary")
            binding.summaryHeader.setSubtitle("Submit Permission")
            binding.permissionsRefresh.isEnabled = true
            binding.filterRow.visibility = View.VISIBLE
            // My/Team scope switch is only meaningful to staff who
            // can approve. Plain employees stay on the (default) My
            // scope with no extra control on screen.
            val canApprove = session.hasPermission("permissions.approve")
            if (canApprove) {
                binding.dropdownScopeSelector.setOnClickListener { showScopePopupMenu(it) }
            } else {
                binding.ivScopeChevron.visibility = View.GONE
            }
            binding.tvScopeLabel.text = "My Permission"
            setupFilterTabs()
            updateScopeUi()
        }

        collectState()
        collectEvents()
        viewModel.load(session.bearerToken, session.hasPermission("permissions.approve"))

        binding.permissionsRefresh.setupPullToRefresh {
            viewModel.load(session.bearerToken, session.hasPermission("permissions.approve"))
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

    private fun updateScopeUi() {
        val label = when (scope) {
            Scope.MY -> "My Permission"
            Scope.TEAM -> "Team Permission"
            Scope.ALL -> "All Permission"
        }
        binding.tvScopeLabel.text = label
        // Figma shows filters on all tabs. However, team approvals API currently only returns pending.
        binding.filterRow.visibility = View.VISIBLE
    }

    private fun switchScope(newScope: Scope) {
        scope = newScope
        updateScopeUi()
        renderState(viewModel.uiState.value)
    }

    private fun showScopePopupMenu(anchor: View) {
        binding.scopeBackdrop.visibility = View.VISIBLE
        binding.scopeBackdrop.alpha = 0f
        binding.scopeBackdrop.animate().alpha(1f).setDuration(200).start()

        val popupView = LayoutInflater.from(requireContext()).inflate(R.layout.popup_scope_menu_perm, null)
        popupView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val popup = android.widget.PopupWindow(
            popupView,
            popupView.measuredWidth,
            popupView.measuredHeight,
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

        binding.scopeBackdrop.setOnClickListener { popup.dismiss() }

        val menuMy = popupView.findViewById<TextView>(R.id.menuMyPerm)
        val menuTeam = popupView.findViewById<View>(R.id.menuTeamPerm)
        val tvMenuTeam = popupView.findViewById<TextView>(R.id.tvMenuTeamPerm)
        val menuAll = popupView.findViewById<TextView>(R.id.menuAllPerm)

        val activeColor = ContextCompat.getColor(requireContext(), R.color.chat_blue_top)
        val defaultColor = android.graphics.Color.parseColor("#061D3D")

        menuMy.setTextColor(if (scope == Scope.MY) activeColor else defaultColor)
        tvMenuTeam?.setTextColor(if (scope == Scope.TEAM) activeColor else defaultColor)
        menuAll?.setTextColor(if (scope == Scope.ALL) activeColor else defaultColor)

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
            if (scope != Scope.MY) switchScope(Scope.MY)
        }

        menuTeam.setOnClickListener {
            popup.dismiss()
            if (scope != Scope.TEAM) switchScope(Scope.TEAM)
        }

        menuAll?.setOnClickListener {
            popup.dismiss()
            if (scope != Scope.ALL) switchScope(Scope.ALL)
        }

        val xOffset = anchor.width - popupView.measuredWidth
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

    private fun collectState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> renderState(state) }
            }
        }
    }

    private fun renderState(state: PermissionsState) {
        val canApprove = session.hasPermission("permissions.approve")
        // Approval-mode entry from notifications still renders the
        // pendingApprovals pile with Approve/Reject buttons.
        // History-mode now routes by the user's Scope toggle:
        //   • Scope.MY   → user's own permissions, status-filtered
        //   • Scope.TEAM → team pending approvals (Approve/Reject UI),
        //                   skipping the status filter since the
        //                   server only returns pending rows there.
        val displayPermissions = if (screenMode == MODE_APPROVAL) {
            state.pendingApprovals
        } else when (scope) {
            Scope.MY -> filterHistoryPermissions(state.myPermissions)
            Scope.TEAM -> filterHistoryPermissions(state.pendingApprovals)
            Scope.ALL -> {
                val combined = state.myPermissions + state.pendingApprovals
                filterHistoryPermissions(combined.distinctBy { it.id })
            }
        }
        val isLoading = state.isLoading
        if (!isLoading) binding.permissionsRefresh.dismissRefresh()

        binding.balanceCard.visibility = if (screenMode == MODE_HISTORY) View.VISIBLE else View.GONE
        binding.btnApplyPermission.visibility = if (screenMode == MODE_HISTORY) View.VISIBLE else View.GONE

        val available = max(state.limitHours - state.usedHours, 0)
        binding.tvPermAvailable.text = "$available hrs"
        binding.tvPermUsed.text = "${state.usedHours} hrs"

        configureHistoryCard(displayPermissions.isEmpty() && !isLoading)

        binding.skeletonContainer.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.permissionList.visibility = if (isLoading) View.GONE else View.VISIBLE
        if (isLoading) {
            startSkeletonPulse()
            binding.emptyState.visibility = View.GONE
            return
        }

        stopSkeletonPulse()
        setEmptyCopy(displayPermissions.isEmpty())
        // Approval mode (notification deep-link) AND History-mode Team/All
        // scope both show Approve/Reject; Team/All scope is only entered
        // when the user has the permission, so this is safe.
        val showApprovalActions = (screenMode == MODE_APPROVAL && canApprove)
            || (screenMode == MODE_HISTORY && (scope == Scope.TEAM || scope == Scope.ALL) && canApprove)
        renderPermissions(displayPermissions, showApprovalActions)
    }

    private fun configureHistoryCard(isEmpty: Boolean) {
        // No-op: section title/subtitle removed per design
    }

    private fun setEmptyCopy(isEmpty: Boolean) {
        if (!isEmpty) return
        if (screenMode == MODE_APPROVAL) {
            binding.emptyState.setTitle("No Permission Approvals")
            binding.emptyState.setDescription("There are no pending permission requests in review right now.")
            return
        }
        if (scope == Scope.TEAM) {
            binding.emptyState.setTitle("No Team Permissions")
            binding.emptyState.setDescription("No pending permission requests from your team.")
            return
        }
        when (historyFilter) {
            HistoryFilter.REVIEW -> {
                binding.emptyState.setTitle("No Permissions Yet!")
                binding.emptyState.setDescription(
                    "Need a short break? Tap 'Apply Permission' and we'll handle the rest!"
                )
            }
            HistoryFilter.APPROVED -> {
                binding.emptyState.setTitle("No Approved Permissions")
                binding.emptyState.setDescription("Your approved permission requests will appear here.")
            }
            HistoryFilter.REJECTED -> {
                binding.emptyState.setTitle("No Rejected Permissions")
                binding.emptyState.setDescription("If any permission gets rejected, you'll find it listed here.")
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

    private fun filterHistoryPermissions(items: List<PermissionData>): List<PermissionData> {
        return items.filter { perm ->
            when (historyFilter) {
                HistoryFilter.REVIEW -> bucketForStatus(perm.status) == StatusBucket.REVIEW
                HistoryFilter.APPROVED -> bucketForStatus(perm.status) == StatusBucket.APPROVED
                HistoryFilter.REJECTED -> bucketForStatus(perm.status) == StatusBucket.REJECTED
            }
        }
    }

    private fun renderPermissions(items: List<PermissionData>, approvalMode: Boolean) {
        binding.permissionList.removeAllViews()
        binding.emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

        val headingFmt = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
        val parseFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val statusFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

        items.forEach { perm ->
            val card = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_permission, binding.permissionList, false)

            val date = parseServerDate(parseFmt, perm.date)
            val dateHeadingText = date?.let { headingFmt.format(it) } ?: perm.date ?: "Permission Date"

            val rangeText = buildString {
                append(perm.fromTime ?: "—")
                append(" - ")
                append(perm.toTime ?: "—")
            }
            val hoursText = perm.hours?.let { formatHours(it) } ?: "—"

            val bucket = bucketForStatus(perm.status)
            val statusDate = parseCreationDate(perm.createdAt)
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
                    statusNote = if (statusDateText.isNullOrBlank()) "In Review" else "In Review at $statusDateText"
                    statusColor = ContextCompat.getColor(requireContext(), R.color.chat_blue_top)
                    statusIconRes = R.drawable.ic_leave_status_review
                }
            }

            card.findViewById<TextView>(R.id.tvPermDate).text = dateHeadingText
            card.findViewById<TextView>(R.id.tvPermTime).text = rangeText
            card.findViewById<TextView>(R.id.tvPermHours).text = hoursText

            val reasonLabel = card.findViewById<TextView>(R.id.tvPermReasonLabel)
            reasonLabel.text = perm.reason.takeUnless { it.isNullOrBlank() } ?: "Permission Time"

            val statusNoteView = card.findViewById<TextView>(R.id.tvPermStatusNote)
            statusNoteView.text = statusNote
            statusNoteView.setTextColor(statusColor)
            card.findViewById<ImageView>(R.id.ivPermStatusIcon).setImageResource(statusIconRes)

            val staffName = card.findViewById<TextView>(R.id.tvPermStaffName)
            val staffInitial = card.findViewById<TextView>(R.id.tvPermStaffInitial)
            val staffRow = card.findViewById<View>(R.id.staffInfoRow)
            val byLabel = card.findViewById<TextView>(R.id.tvBy)
            val actionRow = card.findViewById<View>(R.id.permissionActionRow)
            val approveButton = card.findViewById<TextView>(R.id.btnApprovePermission)
            val rejectButton = card.findViewById<TextView>(R.id.btnRejectPermission)

            val displayName = perm.staffName?.trim().takeUnless { it.isNullOrBlank() } ?: "Self"
            val initial = displayName.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
            staffRow.visibility = View.VISIBLE
            byLabel.visibility = View.VISIBLE
            staffName.visibility = View.VISIBLE
            staffName.text = displayName
            staffInitial.text = initial

            if (approvalMode && perm.staffName != null) {
                // Only show approve/reject if this is a team permission (has staffName).
                // My own permissions shouldn't be approvable by me.
                actionRow.visibility = View.VISIBLE
                approveButton.setOnClickListener {
                    perm.id?.let { id ->
                        viewModel.approvePermission(
                            session.bearerToken,
                            id,
                            session.hasPermission("permissions.approve")
                        )
                    }
                }
                rejectButton.setOnClickListener {
                    perm.id?.let { id -> showRejectDialog(id) }
                }
            } else {
                actionRow.visibility = View.GONE
            }

            // Per-row cancel affordance — visible ONLY on the user's
            // own pending requests on the My scope of History mode.
            // Approver-mode rows already get Approve/Reject; cancelled
            // rows live in REJECTED/APPROVED buckets and shouldn't
            // re-surface a trash icon there.
            val cancelIcon = card.findViewById<android.widget.FrameLayout>(R.id.ivPermCancel)
            val canCancel = !approvalMode
                && screenMode == MODE_HISTORY
                && scope == Scope.MY
                && bucket == StatusBucket.REVIEW
                && perm.id != null
            cancelIcon.visibility = if (canCancel) View.VISIBLE else View.GONE
            if (canCancel) {
                cancelIcon.setOnClickListener {
                    val id = perm.id ?: return@setOnClickListener
                    showCancelPermissionDialog(id)
                }
            } else {
                cancelIcon.setOnClickListener(null)
            }

            if (perm.id == focusedEntityId) {
                card.alpha = 1f
            }

            binding.permissionList.addView(card)
        }
    }

    private fun showCancelPermissionDialog(permissionId: String) {
        val dialog = android.app.Dialog(requireContext())
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_cancel_leave, null)
        dialog.setContentView(dialogView)

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<View>(R.id.btnDialogCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<View>(R.id.btnDialogConfirm).setOnClickListener {
            dialog.dismiss()
            viewModel.cancelPermission(
                session.bearerToken,
                permissionId,
                session.hasPermission("permissions.approve"),
            )
        }

        dialog.show()
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

    private fun bucketForStatus(status: String?): StatusBucket {
        return when (status?.trim()?.lowercase(Locale.getDefault())) {
            "approved" -> StatusBucket.APPROVED
            "rejected" -> StatusBucket.REJECTED
            else -> StatusBucket.REVIEW
        }
    }

    private fun formatHours(hours: Double): String {
        // Show "1.5 Hrs" for fractional hours, "2 Hrs" for whole.
        val whole = hours.toInt()
        val isWhole = hours - whole == 0.0
        return if (isWhole) "$whole Hr${if (whole == 1) "" else "s"}"
        else String.format(Locale.getDefault(), "%.1f Hrs", hours)
    }

    private fun showRejectDialog(permissionId: String) {
        val input = EditText(requireContext()).apply {
            hint = "Reason for rejection"
            minLines = 3
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Reject permission request")
            .setView(input)
            .setPositiveButton("Reject") { _, _ ->
                viewModel.rejectPermission(
                    session.bearerToken,
                    permissionId,
                    input.text?.toString()?.trim().orEmpty().ifBlank { "Rejected" },
                    session.hasPermission("permissions.approve")
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopSkeletonPulse()
        _binding = null
    }
}
