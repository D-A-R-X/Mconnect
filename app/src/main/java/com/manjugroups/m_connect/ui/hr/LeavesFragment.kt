package com.manjugroups.m_connect.ui.hr

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentLeavesBinding
import com.manjugroups.m_connect.network.LeaveData
import com.manjugroups.m_connect.notifications.WorkflowNotificationRoute
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class LeavesFragment : Fragment() {

    private var _binding: FragmentLeavesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LeavesViewModel by viewModels()
    private lateinit var session: SessionManager
    private var screenMode: String = MODE_HISTORY
    private var focusedEntityId: String? = null

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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLeavesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.btnApplyLeave.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, ApplyLeaveFragment())
                .addToBackStack(null)
                .commit()
        }
        binding.tvYear.text = Calendar.getInstance().get(Calendar.YEAR).toString()
        binding.tvHeaderTitle.text = if (screenMode == MODE_APPROVAL) "Leave Approvals" else "Leaves"
        binding.tvSectionTitle.text = if (screenMode == MODE_APPROVAL) "Pending Approvals" else "Leave History"

        collectState()
        collectEvents()
        viewModel.load(session.bearerToken, session.hasPermission("leaves.approve"))
    }

    private fun collectState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val canApprove = session.hasPermission("leaves.approve")
                    val displayLeaves = if (screenMode == MODE_APPROVAL) state.pendingApprovals else state.myLeaves

                    // Hide balance section if all allocations are 0
                    val hasAllocation = state.casualTotal > 0 || state.sickTotal > 0 || state.earnedTotal > 0
                    binding.balanceCard.visibility = if (screenMode == MODE_HISTORY && hasAllocation) View.VISIBLE else View.GONE
                    binding.btnApplyLeave.visibility = if (screenMode == MODE_HISTORY) View.VISIBLE else View.GONE

                    if (hasAllocation) {
                        binding.tvCasual.text = "${state.casualLeft}/${state.casualTotal}"
                        binding.tvSick.text = "${state.sickLeft}/${state.sickTotal}"
                        binding.tvEarned.text = "${state.earnedLeft}/${state.earnedTotal}"
                    }
                    binding.tvEmpty.text = if (screenMode == MODE_APPROVAL) {
                        "No leave approvals pending"
                    } else {
                        "No leave records yet"
                    }
                    renderLeaves(displayLeaves, canApprove && screenMode == MODE_APPROVAL)
                }
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

    private fun renderLeaves(leaves: List<LeaveData>, approvalMode: Boolean) {
        binding.leaveList.removeAllViews()
        binding.tvEmpty.visibility = if (leaves.isEmpty()) View.VISIBLE else View.GONE

        val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        val parseFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        leaves.forEach { leave ->
            val card = LayoutInflater.from(requireContext()).inflate(R.layout.item_leave, binding.leaveList, false)

            val from = leave.fromDate?.let { try { dateFmt.format(parseFmt.parse(it)!!) } catch (_: Exception) { it } } ?: ""
            val to = leave.toDate?.let { try { dateFmt.format(parseFmt.parse(it)!!) } catch (_: Exception) { it } } ?: ""

            val days = try {
                val d1 = parseFmt.parse(leave.fromDate!!)!!
                val d2 = parseFmt.parse(leave.toDate!!)!!
                ((d2.time - d1.time) / (1000 * 60 * 60 * 24) + 1).toInt()
            } catch (_: Exception) { 1 }

            card.findViewById<TextView>(R.id.tvLeaveDate).text = if (from == to) from else "$from - $to"
            card.findViewById<TextView>(R.id.tvLeaveType).text = "${leave.leaveType?.replaceFirstChar { it.uppercase() }} Leave · $days day${if (days > 1) "s" else ""}"
            card.findViewById<TextView>(R.id.tvLeaveReason).text = leave.reason ?: ""
            val staffName = card.findViewById<TextView>(R.id.tvLeaveStaffName)
            val actionRow = card.findViewById<View>(R.id.leaveActionRow)
            val approveButton = card.findViewById<TextView>(R.id.btnApproveLeave)
            val rejectButton = card.findViewById<TextView>(R.id.btnRejectLeave)

            if (approvalMode) {
                staffName.visibility = View.VISIBLE
                staffName.text = leave.staffName ?: ""
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
                staffName.visibility = View.GONE
                actionRow.visibility = View.GONE
            }

            val badge = card.findViewById<TextView>(R.id.tvLeaveStatus)
            val status = leave.status ?: "pending"
            badge.text = status.replaceFirstChar { it.uppercase() }
            when (status) {
                "approved" -> {
                    badge.setBackgroundResource(R.drawable.bg_badge_success)
                    badge.setTextColor(resolveColor(R.attr.colorSuccess))
                }
                "rejected" -> {
                    badge.setBackgroundResource(R.drawable.bg_badge_error)
                    badge.setTextColor(resolveColor(R.attr.colorError))
                }
                else -> {
                    badge.setBackgroundResource(R.drawable.bg_badge_warning)
                    badge.setTextColor(resolveColor(R.attr.colorWarning))
                }
            }

            if (leave.id == focusedEntityId) {
                card.alpha = 1f
            }

            binding.leaveList.addView(card)
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

    private fun showApplyDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_apply_leave, null)
        val spinner = dialogView.findViewById<Spinner>(R.id.spinnerLeaveType)
        val etFrom = dialogView.findViewById<EditText>(R.id.etFromDate)
        val etTo = dialogView.findViewById<EditText>(R.id.etToDate)
        val etReason = dialogView.findViewById<EditText>(R.id.etReason)

        val types = viewModel.uiState.value.leaveTypes
        spinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, types.map { it.replaceFirstChar { c -> c.uppercase() } })

        etFrom.setOnClickListener { showDatePicker(etFrom) }
        etTo.setOnClickListener { showDatePicker(etTo) }

        AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setPositiveButton("Apply") { _, _ ->
                val type = types.getOrElse(spinner.selectedItemPosition) { "casual" }
                val from = etFrom.text.toString()
                val to = etTo.text.toString()
                val reason = etReason.text.toString()
                if (from.isNotBlank() && to.isNotBlank() && reason.isNotBlank()) {
                    viewModel.applyLeave(session.bearerToken, type, from, to, reason)
                } else {
                    Toast.makeText(requireContext(), "Fill all fields", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDatePicker(target: EditText) {
        val cal = Calendar.getInstance()
        DatePickerDialog(requireContext(), { _, y, m, d ->
            target.setText(String.format("%04d-%02d-%02d", y, m + 1, d))
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun resolveColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
