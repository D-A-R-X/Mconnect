package com.manjugroups.m_connect.ui.hr

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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
import com.manjugroups.m_connect.databinding.FragmentPermissionsBinding
import com.manjugroups.m_connect.network.PermissionData
import com.manjugroups.m_connect.notifications.WorkflowNotificationRoute
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class PermissionsFragment : Fragment() {

    private var _binding: FragmentPermissionsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PermissionsViewModel by viewModels()
    private lateinit var session: SessionManager
    private var screenMode: String = MODE_HISTORY

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
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPermissionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.btnApplyPermission.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, ApplyPermissionFragment())
                .addToBackStack(null)
                .commit()
        }

        val cal = Calendar.getInstance()
        val monthFmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        binding.tvMonth.text = monthFmt.format(cal.time)
        binding.tvHeaderTitle.text = if (screenMode == MODE_APPROVAL) "Permission Approvals" else "Permissions"
        binding.tvSectionTitle.text = if (screenMode == MODE_APPROVAL) "Pending Approvals" else "Recent Requests"

        collectState()
        collectEvents()
        viewModel.load(session.bearerToken, session.hasPermission("permissions.approve"))
    }

    private fun collectState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val canApprove = session.hasPermission("permissions.approve")
                    val displayPermissions = if (screenMode == MODE_APPROVAL) {
                        state.pendingApprovals
                    } else {
                        state.myPermissions
                    }
                    binding.tvUsedHours.text = "${state.usedHours} hrs"
                    binding.tvAllowedHours.text = "${state.limitHours} hrs"
                    binding.tvRequestCount.text = state.count.toString()
                    binding.btnApplyPermission.visibility = if (screenMode == MODE_HISTORY) View.VISIBLE else View.GONE
                    binding.tvEmpty.text = if (screenMode == MODE_APPROVAL) {
                        "No permission approvals pending"
                    } else {
                        "No permission records yet"
                    }
                    renderPermissions(displayPermissions, canApprove && screenMode == MODE_APPROVAL)
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

    private fun renderPermissions(list: List<PermissionData>, approvalMode: Boolean) {
        binding.permissionList.removeAllViews()
        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

        val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        val parseFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        list.forEach { perm ->
            val card = LayoutInflater.from(requireContext()).inflate(R.layout.item_permission, binding.permissionList, false)

            val date = perm.date?.let { try { dateFmt.format(parseFmt.parse(it)!!) } catch (_: Exception) { it } } ?: ""
            card.findViewById<TextView>(R.id.tvPermDate).text = date
            card.findViewById<TextView>(R.id.tvPermTime).text = "${perm.fromTime ?: ""} - ${perm.toTime ?: ""}"
            card.findViewById<TextView>(R.id.tvPermReason).text = perm.reason ?: ""
            val staffName = card.findViewById<TextView>(R.id.tvPermStaffName)
            val actionRow = card.findViewById<View>(R.id.permissionActionRow)
            val approveButton = card.findViewById<TextView>(R.id.btnApprovePermission)
            val rejectButton = card.findViewById<TextView>(R.id.btnRejectPermission)

            if (approvalMode) {
                staffName.visibility = View.VISIBLE
                staffName.text = perm.staffName ?: ""
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
                staffName.visibility = View.GONE
                actionRow.visibility = View.GONE
            }

            val badge = card.findViewById<TextView>(R.id.tvPermStatus)
            val status = perm.status ?: "pending"
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

            binding.permissionList.addView(card)
        }
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

    private fun showApplyDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_apply_permission, null)
        val etDate = dialogView.findViewById<EditText>(R.id.etDate)
        val etFrom = dialogView.findViewById<EditText>(R.id.etFromTime)
        val etTo = dialogView.findViewById<EditText>(R.id.etToTime)
        val etReason = dialogView.findViewById<EditText>(R.id.etReason)

        etDate.setOnClickListener { showDatePicker(etDate) }
        etFrom.setOnClickListener { showTimePicker(etFrom) }
        etTo.setOnClickListener { showTimePicker(etTo) }

        AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setPositiveButton("Apply") { _, _ ->
                val date = etDate.text.toString()
                val from = etFrom.text.toString()
                val to = etTo.text.toString()
                val reason = etReason.text.toString()
                if (date.isNotBlank() && from.isNotBlank() && to.isNotBlank() && reason.isNotBlank()) {
                    viewModel.applyPermission(session.bearerToken, date, from, to, reason)
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

    private fun showTimePicker(target: EditText) {
        val cal = Calendar.getInstance()
        TimePickerDialog(requireContext(), { _, h, m ->
            target.setText(String.format("%02d:%02d", h, m))
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
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
