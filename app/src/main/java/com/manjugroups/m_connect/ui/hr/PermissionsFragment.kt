package com.manjugroups.m_connect.ui.hr

import android.animation.ObjectAnimator
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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

class PermissionsFragment : Fragment() {

    private enum class HistoryFilter { REVIEW, APPROVED, REJECTED }
    private enum class StatusBucket { REVIEW, APPROVED, REJECTED }

    private var _binding: FragmentPermissionsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PermissionsViewModel by viewModels()
    private lateinit var session: SessionManager
    private var screenMode: String = MODE_HISTORY
    private var focusedEntityId: String? = null
    private var historyFilter: HistoryFilter = HistoryFilter.REVIEW
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

        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.btnBack.visibility = if (screenMode == MODE_APPROVAL) View.VISIBLE else View.GONE
        binding.btnApplyPermission.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, ApplyPermissionFragment())
                .addToBackStack(null)
                .commit()
        }

        val cal = Calendar.getInstance()
        binding.tvMonth.text = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)

        if (screenMode == MODE_APPROVAL) {
            binding.tvHeaderTitle.text = "Permission Approvals"
            binding.tvHeaderSubtitle.text = "In Review"
            binding.tvSectionTitle.text = "Permission Approvals"
            binding.tvSectionSubtitle.visibility = View.GONE
            binding.filterRow.visibility = View.GONE
        } else {
            binding.tvHeaderTitle.text = "Permission Summary"
            binding.tvHeaderSubtitle.text = "Submit Permission"
            binding.tvSectionTitle.text = "Recent Requests"
            binding.tvSectionSubtitle.visibility = View.VISIBLE
            binding.filterRow.visibility = View.VISIBLE
            setupFilterTabs()
            updateFilterUi()
        }

        collectState()
        collectEvents()
        viewModel.load(session.bearerToken, session.hasPermission("permissions.approve"))
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

    private fun renderState(state: PermissionsState) {
        val canApprove = session.hasPermission("permissions.approve")
        val displayPermissions = if (screenMode == MODE_APPROVAL) {
            state.pendingApprovals
        } else {
            filterHistoryPermissions(state.myPermissions)
        }
        val isLoading = state.isLoading

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
        renderPermissions(displayPermissions, canApprove && screenMode == MODE_APPROVAL)
    }

    private fun configureHistoryCard(isEmpty: Boolean) {
        val showHeader = screenMode == MODE_APPROVAL || historyFilter == HistoryFilter.REVIEW || isEmpty
        binding.tvSectionTitle.visibility = if (showHeader) View.VISIBLE else View.GONE
        binding.tvSectionSubtitle.visibility = if (showHeader) View.VISIBLE else View.GONE

        if (screenMode == MODE_APPROVAL) {
            binding.tvSectionTitle.text = "Permission Approvals"
            binding.tvSectionSubtitle.visibility = View.GONE
        } else {
            when (historyFilter) {
                HistoryFilter.REVIEW -> {
                    binding.tvSectionTitle.text = "Recent Requests"
                    binding.tvSectionSubtitle.text = "Permission information"
                }
                HistoryFilter.APPROVED -> {
                    binding.tvSectionTitle.text = "Approved Permissions"
                    binding.tvSectionSubtitle.text = "Approved permission information"
                }
                HistoryFilter.REJECTED -> {
                    binding.tvSectionTitle.text = "Rejected Permissions"
                    binding.tvSectionSubtitle.text = "Rejected permission information"
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
            binding.tvEmpty.text = "No Permission Approvals"
            binding.tvEmptyHint.text = "There are no pending permission requests in review right now."
            return
        }
        when (historyFilter) {
            HistoryFilter.REVIEW -> {
                binding.tvEmpty.text = "No Permissions Yet!"
                binding.tvEmptyHint.text =
                    "Need a short break? Tap 'Apply Permission' and we'll handle the rest!"
            }
            HistoryFilter.APPROVED -> {
                binding.tvEmpty.text = "No Approved Permissions"
                binding.tvEmptyHint.text = "Your approved permission requests will appear here."
            }
            HistoryFilter.REJECTED -> {
                binding.tvEmpty.text = "No Rejected Permissions"
                binding.tvEmptyHint.text = "If any permission gets rejected, you'll find it listed here."
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
                    statusNote = "In Review"
                    statusColor = ContextCompat.getColor(requireContext(), R.color.lt_accent_primary)
                    statusIconRes = R.drawable.ic_leave_status_review
                }
            }

            card.findViewById<TextView>(R.id.tvPermDate).text = dateHeadingText
            card.findViewById<TextView>(R.id.tvPermTime).text = rangeText
            card.findViewById<TextView>(R.id.tvPermHours).text = hoursText

            val reasonText = card.findViewById<TextView>(R.id.tvPermReason)
            reasonText.text = statusNote
            reasonText.setTextColor(statusColor)
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

            if (approvalMode) {
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

            if (perm.id == focusedEntityId) {
                card.alpha = 1f
            }

            binding.permissionList.addView(card)
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
