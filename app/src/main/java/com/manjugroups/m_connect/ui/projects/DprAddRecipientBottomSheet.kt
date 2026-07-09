package com.manjugroups.m_connect.ui.projects

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.AddDprRecipientRequest
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.DailyLogApi
import com.manjugroups.m_connect.ui.common.SearchableOption
import com.manjugroups.m_connect.ui.common.SearchableSelectionDialog
import kotlinx.coroutines.launch

/** Add a DPR WhatsApp recipient for a project. Returns [RESULT_KEY] on add. */
class DprAddRecipientBottomSheet : BottomSheetDialogFragment() {

    private val api = ApiService.create()
    private val dprApi = DailyLogApi.create()
    private val session by lazy { SessionManager(requireContext()) }

    private var projectId: String = ""
    private var staffId: String? = null

    // Staff list is fetched on first tap; cache it and guard the in-flight
    // request so rapid taps can't stack multiple picker dialogs.
    private var staffCache: List<com.manjugroups.m_connect.network.StaffData>? = null
    private var staffLoading = false

    // Project selector lives INSIDE this form (the list page has none).
    private var projects: List<com.manjugroups.m_connect.network.ProjectSummary> = emptyList()
    private var projectsLoading = false
    private var pendingProjectPick = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { di ->
            (di as BottomSheetDialog).findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let {
                it.setBackgroundResource(R.drawable.bg_bottom_sheet)
                BottomSheetBehavior.from(it).state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.bottom_sheet_dpr_recipient, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        projectId = arguments?.getString(ARG_PROJECT_ID).orEmpty()
        arguments?.getString(ARG_PROJECT_NAME)?.takeIf { it.isNotBlank() }?.let {
            view.findViewById<TextView>(R.id.tvDprProjectValue).text = it
        }

        view.findViewById<View>(R.id.fieldDprProject).setOnClickListener { pickProject(view) }
        view.findViewById<View>(R.id.fieldDprStaff).setOnClickListener { pickStaff(view) }
        view.findViewById<MaterialButton>(R.id.btnAddRecipient).setOnClickListener { add(view) }

        loadProjects()
    }

    private fun loadProjects() {
        if (projectsLoading) return
        projectsLoading = true
        viewLifecycleOwner.lifecycleScope.launch {
            val resp = runCatching { api.getMyProjects(session.bearerToken) }.getOrNull()
            projectsLoading = false
            if (view == null) return@launch
            projects = resp?.projects ?: emptyList()
            if (pendingProjectPick && projects.isNotEmpty()) {
                pendingProjectPick = false
                view?.let { pickProject(it) }
            }
        }
    }

    private fun pickProject(root: View) {
        if (projects.isEmpty()) {
            if (projectsLoading) {
                pendingProjectPick = true
                root.findViewById<TextView>(R.id.tvDprProjectValue).text = "Loading projects…"
            } else {
                context?.let { Toast.makeText(it, "No projects available", Toast.LENGTH_SHORT).show() }
                loadProjects()
            }
            return
        }
        val options = projects.map { SearchableOption(it, it.name ?: "Untitled project", it.status) }
        SearchableSelectionDialog.show(requireContext(), "Select project", options) { p ->
            if (view == null) return@show
            projectId = p.id
            root.findViewById<TextView>(R.id.tvDprProjectValue).text = p.name ?: "Project"
        }
    }

    private fun pickStaff(root: View) {
        // Cached → open instantly. In-flight → ignore extra taps.
        staffCache?.let { showStaffDialog(root, it); return }
        if (staffLoading) return
        staffLoading = true
        val label = root.findViewById<TextView>(R.id.tvDprStaffValue)
        val prevText = label.text
        label.text = "Loading staff…"
        viewLifecycleOwner.lifecycleScope.launch {
            val resp = runCatching { api.getStaff(session.bearerToken) }.getOrNull()
            staffLoading = false
            if (view == null) return@launch
            label.text = prevText
            val staff = resp?.staff ?: emptyList()
            if (staff.isEmpty()) {
                context?.let { Toast.makeText(it, "Couldn't load staff", Toast.LENGTH_SHORT).show() }
                return@launch
            }
            staffCache = staff
            showStaffDialog(root, staff)
        }
    }

    private fun showStaffDialog(root: View, staff: List<com.manjugroups.m_connect.network.StaffData>) {
        val options = staff.map { SearchableOption(it, it.name ?: "Staff", listOfNotNull(it.designation, it.phone).joinToString(" · ")) }
        SearchableSelectionDialog.show(requireContext(), "Select staff", options) { s ->
            if (view == null) return@show
            staffId = s.id
            root.findViewById<TextView>(R.id.tvDprStaffValue).text = s.name ?: "Staff"
            if (!s.phone.isNullOrBlank()) root.findViewById<EditText>(R.id.etDprPhone).setText(s.phone)
        }
    }

    private fun add(root: View) {
        val phone = root.findViewById<EditText>(R.id.etDprPhone).text?.toString()?.trim().orEmpty()
        val name = root.findViewById<TextView>(R.id.tvDprStaffValue).text?.toString()?.takeUnless { it == "Select staff…" }.orEmpty()
        if (projectId.isBlank()) { context?.let { Toast.makeText(it, "Select a project", Toast.LENGTH_SHORT).show() }; return }
        if (phone.isEmpty()) { root.findViewById<EditText>(R.id.etDprPhone).error = "Enter a number"; return }
        val btn = root.findViewById<MaterialButton>(R.id.btnAddRecipient).apply { isEnabled = false }
        viewLifecycleOwner.lifecycleScope.launch {
            val resp = runCatching {
                dprApi.addDprRecipient(session.bearerToken, AddDprRecipientRequest(projectId, staffId, name.ifBlank { phone }, phone))
            }.getOrNull()
            if (view == null) return@launch
            btn.isEnabled = true
            if (resp?.success == true) {
                setFragmentResult(RESULT_KEY, bundleOf())
                dismissAllowingStateLoss()
            } else {
                context?.let { Toast.makeText(it, resp?.error ?: "Couldn't add recipient", Toast.LENGTH_LONG).show() }
            }
        }
    }

    companion object {
        const val RESULT_KEY = "dpr_recipient_added"
        private const val ARG_PROJECT_ID = "arg_project_id"
        private const val ARG_PROJECT_NAME = "arg_project_name"
        fun newInstance(projectId: String? = null, projectName: String? = null) =
            DprAddRecipientBottomSheet().apply {
                arguments = bundleOf(ARG_PROJECT_ID to projectId, ARG_PROJECT_NAME to projectName)
            }
    }
}
