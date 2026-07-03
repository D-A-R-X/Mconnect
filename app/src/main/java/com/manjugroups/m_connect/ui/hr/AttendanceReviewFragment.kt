package com.manjugroups.m_connect.ui.hr

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.AttendanceApprovalRecord
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import com.manjugroups.m_connect.ui.common.navigateUp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manager-facing attendance approval queue. Mirrors the Leave Approvals
 * surface — same compact header, same card-per-record list, same approve /
 * reject button pair. The approve action opens a chooser for the five
 * attendance buckets the backend accepts: present, half-day, absent, weekoff,
 * holiday. Reject opens a free-text reason dialog.
 */
class AttendanceReviewFragment : Fragment() {

    private val viewModel: AttendanceReviewViewModel by viewModels()
    private lateinit var session: SessionManager

    private lateinit var contentRoot: View

    private val labels = listOf("Present", "Half-day", "Absent", "Weekoff", "Holiday")
    private val values = listOf("present", "half-day", "absent", "weekoff", "holiday")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        contentRoot = inflater.inflate(R.layout.fragment_attendance_review, container, false)
        return contentRoot
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        view.findViewById<com.manjugroups.m_connect.ui.common.SummaryHeaderView>(R.id.summaryHeader)
            .apply {
                setBackButtonVisible(true)
                setOnBackClickListener { navigateUp() }
            }

        view.findViewById<View>(R.id.btnCategorySelector).setOnClickListener { anchor ->
            showCategoryMenu(anchor)
        }

        collectState()
        collectEvents()
        viewModel.load(session.bearerToken)
    }

    private fun collectState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
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

    private fun render(state: AttendanceReviewState) {
        val skeleton = contentRoot.findViewById<View>(R.id.skeletonContainer)
        val list = contentRoot.findViewById<LinearLayout>(R.id.approvalsList)
        val empty = contentRoot.findViewById<View>(R.id.emptyState)

        // Keep the dropdown label in sync with the active category, even
        // mid-load, so the selection reflects immediately on tap.
        contentRoot.findViewById<TextView>(R.id.tvCategoryLabel).text = state.category.label

        if (state.isLoading) {
            skeleton.visibility = View.VISIBLE
            list.visibility = View.GONE
            empty.visibility = View.GONE
            SkeletonUtils.startSkeletonPulse(skeleton)
            return
        }

        SkeletonUtils.stopSkeletonPulse(skeleton)
        skeleton.visibility = View.GONE
        list.visibility = View.VISIBLE
        empty.visibility = if (state.pending.isEmpty()) View.VISIBLE else View.GONE

        list.removeAllViews()
        state.pending.forEach { record ->
            list.addView(buildCard(record))
        }
    }

    private fun buildCard(record: AttendanceApprovalRecord): View {
        val card = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_attendance_approval, contentRoot.findViewById(R.id.approvalsList), false)

        val staffName = record.staffName?.trim().orEmpty().ifBlank { "Staff" }
        card.findViewById<TextView>(R.id.tvAttStaffName).text = staffName
        card.findViewById<TextView>(R.id.tvAttStaffInitial).text =
            staffName.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

        val meta = listOfNotNull(
            record.employeeId?.takeIf { it.isNotBlank() },
            record.designation?.takeIf { it.isNotBlank() },
            record.department?.takeIf { it.isNotBlank() },
        ).joinToString(" · ").ifBlank { "—" }
        card.findViewById<TextView>(R.id.tvAttStaffMeta).text = meta

        val sourceLabel = when (record.source?.lowercase(Locale.US)) {
            "mobile" -> "Mobile"
            "biometric" -> "Biometric"
            "manual" -> "Manual"
            "csv-import" -> "CSV"
            null, "" -> "—"
            else -> record.source.replaceFirstChar { it.titlecase(Locale.US) }
        }
        card.findViewById<TextView>(R.id.tvAttSource).text = sourceLabel

        card.findViewById<TextView>(R.id.tvAttDate).text =
            formatDateLabel(record.date) ?: (record.date ?: "—")
        card.findViewById<TextView>(R.id.tvAttPunchIn).text = formatTime(record.punchInTime) ?: "—"
        card.findViewById<TextView>(R.id.tvAttPunchOut).text = formatTime(record.punchOutTime) ?: "—"
        card.findViewById<TextView>(R.id.tvAttDuration).text =
            record.totalMinutes?.let { formatDuration(it) } ?: "—"

        card.findViewById<TextView>(R.id.btnApproveAttendance).setOnClickListener {
            record.id?.let { showApproveDialog(it) }
        }
        card.findViewById<TextView>(R.id.btnRejectAttendance).setOnClickListener {
            record.id?.let { showRejectDialog(it) }
        }
        return card
    }

    /**
     * Category dropdown — the same PopupWindow + dim backdrop component the
     * Leaves / Permissions scope selectors use, so the approval surfaces
     * feel identical (rounded card, centred items, dividers, active-in-blue).
     */
    private fun showCategoryMenu(anchor: View) {
        val backdrop = contentRoot.findViewById<View>(R.id.scopeBackdrop)
        backdrop.visibility = View.VISIBLE
        backdrop.alpha = 0f
        backdrop.animate().alpha(1f).setDuration(200).start()

        val popupView = LayoutInflater.from(requireContext())
            .inflate(R.layout.popup_scope_menu_attendance, null)
        val popupWidth = dp(190)
        val popup = android.widget.PopupWindow(
            popupView,
            popupWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            elevation = dp(12).toFloat()
            isOutsideTouchable = true
            setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            )
            setOnDismissListener {
                backdrop.animate()
                    .alpha(0f)
                    .setDuration(150)
                    .withEndAction { backdrop.visibility = View.GONE }
                    .start()
            }
        }
        backdrop.setOnClickListener { popup.dismiss() }

        val current = viewModel.uiState.value.category
        val activeColor = androidx.core.content.ContextCompat.getColor(
            requireContext(), R.color.chat_blue_top,
        )
        val defaultColor = Color.parseColor("#061D3D")

        val rows = listOf(
            R.id.menuMyTeam to ApprovalCategory.MY_TEAM,
            R.id.menuAllTeam to ApprovalCategory.ALL_TEAM,
            R.id.menuBlocking to ApprovalCategory.BLOCKING,
        )
        rows.forEach { (viewId, category) ->
            popupView.findViewById<TextView>(viewId).apply {
                text = category.label
                setTextColor(if (current == category) activeColor else defaultColor)
                setOnClickListener {
                    popup.dismiss()
                    if (current != category) viewModel.selectCategory(session.bearerToken, category)
                }
            }
        }

        popup.showAsDropDown(anchor, 0, dp(4))
        popupView.alpha = 0f
        popupView.scaleX = 0.95f
        popupView.scaleY = 0.95f
        popupView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun showApproveDialog(id: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Approve as")
            .setItems(labels.toTypedArray()) { _, which ->
                viewModel.approve(session.bearerToken, id, values[which])
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
                viewModel.reject(session.bearerToken, id, reason)
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    private fun parseIsoMillis(iso: String): Long? {
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")) {
            runCatching {
                return SimpleDateFormat(pattern, Locale.US).parse(iso)?.time
            }
        }
        return null
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

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setTabBarVisible(false)
        (activity as? MainActivity)?.setTopBarAppearance(
            Color.parseColor("#0B61CA"),
            darkStatusIcons = false,
            fullBleed = true,
        )
    }

    override fun onDestroyView() {
        SkeletonUtils.stopAll()
        super.onDestroyView()
    }

    companion object {
        fun newInstance(): AttendanceReviewFragment = AttendanceReviewFragment()
    }
}
