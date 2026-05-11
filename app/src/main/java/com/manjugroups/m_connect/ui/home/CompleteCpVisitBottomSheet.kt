package com.manjugroups.m_connect.ui.home

import android.app.Dialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.ConvertCpVisitToSiteVisitRequest
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.MarkClientMetRequest
import com.manjugroups.m_connect.network.MarketingProject
import com.manjugroups.m_connect.network.SetOutcomeRequest
import com.manjugroups.m_connect.network.SiteVisitAttendeeRequest
import com.manjugroups.m_connect.network.StaffData
import com.manjugroups.m_connect.ui.common.SearchableOption
import com.manjugroups.m_connect.ui.common.SearchableSelectionDialog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * KOS-37 / KOS-52: collects the visit outcome (+ outcome-specific details) for
 * a CP visit. Caller (TripNavigationFragment) shows this after OTP verify on a
 * tripType=client_place "Yes, saw client" path; clientMet is implicitly true
 * because the sheet is unreachable without OTP success. On submit the sheet
 * POSTs markClientMet(true) and setOutcome, then signals the host to call
 * completeVisit. The "No, didn't see client" path skips this sheet entirely
 * and is handled directly in TripNavigationFragment.completeCpVisitWithoutClient.
 */
class CompleteCpVisitBottomSheet : BottomSheetDialogFragment() {

    private val geoApi = GeoTrackApi.create()
    private val api = ApiService.create()
    private lateinit var session: SessionManager

    private var selectedOutcome: String? = null
    private val selectedPostponeReasons = linkedSetOf<String>()
    private var selectedProject: MarketingProject? = null
    private var selectedBdo: StaffData? = null
    private var selectedIncharge: StaffData? = null
    private var selectedHod: StaffData? = null
    private var selectedAvp: StaffData? = null
    private var selectedGm: StaffData? = null
    private var selectedSeniorManager: StaffData? = null
    private var salesStaff: List<StaffData> = emptyList()
    private var projects: List<MarketingProject> = emptyList()
    private var projectsLoading = false
    private var siteVisitDate: String = todayYmd()
    private var siteVisitTime: String = defaultTime()
    private var selectedTravelMode: String = "cab"

    private var outcomeRow: LinearLayout? = null
    private var siteVisitDetailsGroup: View? = null
    private var siteVisitProject: TextView? = null
    private var siteVisitDateView: TextView? = null
    private var siteVisitTimeView: TextView? = null
    private var siteVisitTravelMode: TextView? = null
    private var pickupAddress: EditText? = null
    private var siteVisitBdo: TextView? = null
    private var siteVisitIncharge: TextView? = null
    private var siteVisitHod: TextView? = null
    private var siteVisitAvp: TextView? = null
    private var siteVisitGm: TextView? = null
    private var siteVisitSeniorManager: TextView? = null
    private var visitorCount: EditText? = null
    private var visitorRows: LinearLayout? = null
    private var foodPreference: EditText? = null
    private var postponeDetailsGroup: View? = null
    private var postponeRow: LinearLayout? = null
    private var postponeBudgetConcern: EditText? = null
    private var postponeTiming: EditText? = null
    private var postponeProjectDetails: EditText? = null
    private var postponeOtherDetails: EditText? = null
    private var notInterestedDetailsGroup: View? = null
    private var notInterestedNotes: EditText? = null
    private var waitDetailsGroup: View? = null
    private var waitNotes: EditText? = null
    private var errorText: TextView? = null
    private var submitBtn: TextView? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                isCancelable = false
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_cp_visit_complete, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        outcomeRow = view.findViewById(R.id.outcomeChipRow)
        siteVisitDetailsGroup = view.findViewById(R.id.siteVisitDetailsGroup)
        siteVisitProject = view.findViewById(R.id.tvCpSiteVisitProject)
        siteVisitDateView = view.findViewById(R.id.tvCpSiteVisitDate)
        siteVisitTimeView = view.findViewById(R.id.tvCpSiteVisitTime)
        siteVisitTravelMode = view.findViewById(R.id.tvCpSiteVisitTravelMode)
        pickupAddress = view.findViewById(R.id.etCpSiteVisitPickupAddress)
        siteVisitBdo = view.findViewById(R.id.tvCpSiteVisitBdo)
        siteVisitIncharge = view.findViewById(R.id.tvCpSiteVisitIncharge)
        siteVisitHod = view.findViewById(R.id.tvCpSiteVisitHod)
        siteVisitAvp = view.findViewById(R.id.tvCpSiteVisitAvp)
        siteVisitGm = view.findViewById(R.id.tvCpSiteVisitGm)
        siteVisitSeniorManager = view.findViewById(R.id.tvCpSiteVisitSeniorManager)
        visitorCount = view.findViewById(R.id.etCpSiteVisitVisitorCount)
        visitorRows = view.findViewById(R.id.visitorRows)
        foodPreference = view.findViewById(R.id.etCpSiteVisitFoodPreference)
        postponeDetailsGroup = view.findViewById(R.id.postponeDetailsGroup)
        postponeRow = view.findViewById(R.id.postponeReasonRow)
        postponeBudgetConcern = view.findViewById(R.id.etCpPostponeBudgetConcern)
        postponeTiming = view.findViewById(R.id.etCpPostponeTiming)
        postponeProjectDetails = view.findViewById(R.id.etCpPostponeProjectDetails)
        postponeOtherDetails = view.findViewById(R.id.etCpPostponeOtherDetails)
        notInterestedDetailsGroup = view.findViewById(R.id.notInterestedDetailsGroup)
        notInterestedNotes = view.findViewById(R.id.etCpNotInterestedNotes)
        waitDetailsGroup = view.findViewById(R.id.waitDetailsGroup)
        waitNotes = view.findViewById(R.id.etCpWaitNotes)
        errorText = view.findViewById(R.id.tvCpError)
        submitBtn = view.findViewById(R.id.btnCpSubmit)

        arguments?.getString(ARG_CP_OUTCOME)?.takeIf { it.isNotBlank() }?.let(::setOutcome)
        siteVisitProject?.setOnClickListener { pickProject() }
        siteVisitBdo?.isClickable = false
        siteVisitIncharge?.setOnClickListener {
            pickStaff("Select SiteIncharge") {
                selectedIncharge = it
                siteVisitIncharge?.text = "SiteIncharge: ${it.name ?: "Selected"}"
                val reportingTo = it.reportingToId ?: it.reportingTo
                if (selectedHod == null && reportingTo != null) {
                    selectedHod = salesStaff.firstOrNull { staff -> staff.id == reportingTo }
                    selectedHod?.let { hod -> siteVisitHod?.text = "HOD: ${hod.name ?: "Selected"}" }
                }
            }
        }
        siteVisitHod?.setOnClickListener { pickStaff("Select HOD") { selectedHod = it; siteVisitHod?.text = "HOD: ${it.name ?: "Selected"}" } }
        siteVisitAvp?.setOnClickListener { pickStaff("Select AVP") { selectedAvp = it; siteVisitAvp?.text = "AVP: ${it.name ?: "Selected"}" } }
        siteVisitGm?.setOnClickListener { pickStaff("Select GM") { selectedGm = it; siteVisitGm?.text = "GM: ${it.name ?: "Selected"}" } }
        siteVisitSeniorManager?.setOnClickListener {
            pickStaff("Select Senior Manager") {
                selectedSeniorManager = it
                siteVisitSeniorManager?.text = "Senior Manager: ${it.name ?: "Selected"}"
            }
        }
        visitorCount?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderVisitorRows(s?.toString()?.toIntOrNull() ?: 0)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        siteVisitDateView?.apply {
            text = siteVisitDate
            setOnClickListener { pickSiteVisitDate() }
        }
        siteVisitTimeView?.apply {
            text = siteVisitTime
            setOnClickListener { pickSiteVisitTime() }
        }
        siteVisitTravelMode?.setOnClickListener { toggleTravelMode() }
        renderTravelMode()

        renderOutcomeChips()
        renderPostponeReasonChips()
        loadSalesStaff()
        loadProjects(showErrors = false)

        submitBtn?.setOnClickListener { onSubmit() }
    }

    private fun renderOutcomeChips() {
        val row = outcomeRow ?: return
        row.removeAllViews()
        OUTCOME_OPTIONS.forEach { (label, value) ->
            val chip = makeChip(label) { setOutcome(value) }
            applyChipState(chip, selectedOutcome == value)
            chip.tag = value
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.marginEnd = dp(10)
            row.addView(chip, params)
        }
    }

    private fun renderPostponeReasonChips() {
        val row = postponeRow ?: return
        row.removeAllViews()
        POSTPONE_REASON_OPTIONS.forEach { reason ->
            val chip = makeChip(reason) { togglePostponeReason(reason) }
            applyChipState(chip, selectedPostponeReasons.contains(reason))
            chip.tag = reason
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.marginEnd = dp(10)
            row.addView(chip, params)
        }
    }

    private fun setOutcome(outcome: String) {
        selectedOutcome = outcome
        outcomeRow?.let { row ->
            for (i in 0 until row.childCount) {
                val v = row.getChildAt(i) as? TextView ?: continue
                applyChipState(v, v.tag == outcome)
            }
        }
        val isPostpone = outcome == OUTCOME_POSTPONED
        val isSiteVisit = outcome == OUTCOME_SITE_VISIT
        val isNotInterested = outcome == OUTCOME_NOT_INTERESTED
        val isWait = outcome == OUTCOME_WAIT
        siteVisitDetailsGroup?.visibility = if (isSiteVisit) View.VISIBLE else View.GONE
        postponeDetailsGroup?.visibility = if (isPostpone) View.VISIBLE else View.GONE
        notInterestedDetailsGroup?.visibility = if (isNotInterested) View.VISIBLE else View.GONE
        waitDetailsGroup?.visibility = if (isWait) View.VISIBLE else View.GONE
        if (!isPostpone) selectedPostponeReasons.clear()
    }

    private fun pickProject() {
        if (projects.isNotEmpty()) {
            showProjectPicker(projects)
            return
        }
        loadProjects(showErrors = true)
    }

    private fun loadProjects(showErrors: Boolean) {
        if (projectsLoading) return
        projectsLoading = true
        siteVisitProject?.text = "Loading projects..."
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getMarketingProjects(session.bearerToken)
                if (!resp.success) {
                    siteVisitProject?.text = selectedProject?.name ?: "Select project"
                    if (showErrors) showError(resp.error ?: "Failed to load projects")
                    return@launch
                }
                if (resp.projects.isEmpty()) {
                    siteVisitProject?.text = selectedProject?.name ?: "Select project"
                    if (showErrors) showError("No projects available")
                    return@launch
                }
                projects = resp.projects
                siteVisitProject?.text = selectedProject?.name ?: "Select project"
                if (showErrors) showProjectPicker(projects)
            } catch (e: Exception) {
                siteVisitProject?.text = selectedProject?.name ?: "Select project"
                if (showErrors) showError(e.message ?: "Failed to load projects")
            } finally {
                projectsLoading = false
            }
        }
    }

    private fun showProjectPicker(items: List<MarketingProject>) {
        SearchableSelectionDialog.show(
            context = requireContext(),
            title = "Select project",
            options = items.map { project ->
                SearchableOption(
                    item = project,
                    title = project.name ?: "Unnamed project",
                    subtitle = listOfNotNull(project.location, project.status).joinToString(" • ").takeIf { it.isNotBlank() },
                    keywords = listOfNotNull(project.id, project.scope, project.location, project.status).joinToString(" ")
                )
            },
            emptyMessage = "No projects found"
        ) { project ->
            selectedProject = project
            siteVisitProject?.text = project.name ?: "Select project"
        }
    }

    private fun loadSalesStaff() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getStaff(session.bearerToken, status = "active")
                salesStaff = resp.staff.filter {
                    val dept = it.department.orEmpty().lowercase(Locale.US)
                    dept.contains("telesales") || dept.contains("sales")
                }
            } catch (_: Exception) {
                salesStaff = emptyList()
            }
        }
    }

    private fun pickStaff(title: String, onPicked: (StaffData) -> Unit) {
        if (salesStaff.isEmpty()) {
            showError("No Sales & Marketing / Telesales staff found")
            loadSalesStaff()
            return
        }
        SearchableSelectionDialog.show(
            context = requireContext(),
            title = title,
            options = salesStaff.map { staff ->
                SearchableOption(
                    item = staff,
                    title = staff.name ?: "Unnamed staff",
                    subtitle = listOfNotNull(staff.designation, staff.department, staff.employeeId).joinToString(" • ")
                        .takeIf { it.isNotBlank() },
                    keywords = listOfNotNull(staff.phone, staff.role, staff.status).joinToString(" ")
                )
            },
            emptyMessage = "No staff found"
        ) { onPicked(it) }
    }

    private fun renderVisitorRows(count: Int) {
        val rows = visitorRows ?: return
        rows.removeAllViews()
        repeat(count.coerceIn(0, 12)) { index ->
            val group = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setBackgroundResource(R.drawable.bg_stat_card)
                tag = "visitor_row"
            }
            val title = TextView(requireContext()).apply {
                text = "Visitor ${index + 1}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(android.graphics.Color.parseColor("#667085"))
                typeface = resources.getFont(R.font.inter_semibold)
            }
            val name = makeVisitorEdit("Visitor ${index + 1} name").apply { tag = "name" }
            val relation = makeVisitorEdit("Relation").apply { tag = "relation" }
            val age = makeVisitorEdit("Age").apply {
                tag = "age"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            val food = makeChip("Food: Veg") {
                val current = group.findViewWithTag<TextView>("food")
                val next = current?.text?.toString() != "Food: Veg"
                current?.text = if (next) "Food: Veg" else "Food: Non-veg"
            }.apply { tag = "food" }
            applyChipState(food, false)
            group.addView(title)
            group.addView(name)
            group.addView(relation)
            group.addView(age)
            group.addView(food)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            rows.addView(group, params)
        }
    }

    private fun makeVisitorEdit(hint: String): EditText =
        EditText(requireContext()).apply {
            this.hint = hint
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(android.graphics.Color.parseColor("#1D2939"))
            setHintTextColor(android.graphics.Color.parseColor("#98A2B3"))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundResource(R.drawable.bg_chip_inactive)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            layoutParams = params
        }

    private fun collectVisitors(): List<SiteVisitAttendeeRequest> {
        val rows = visitorRows ?: return emptyList()
        val result = mutableListOf<SiteVisitAttendeeRequest>()
        for (i in 0 until rows.childCount) {
            val group = rows.getChildAt(i) as? LinearLayout ?: continue
            val name = group.findViewWithTag<EditText>("name")?.text?.toString()?.trim().orEmpty()
            val relation = group.findViewWithTag<EditText>("relation")?.text?.toString()?.trim().orEmpty()
            val age = group.findViewWithTag<EditText>("age")?.text?.toString()?.trim().orEmpty()
            val isVeg = !group.findViewWithTag<TextView>("food")?.text?.toString().orEmpty().contains("Non-veg")
            result.add(
                SiteVisitAttendeeRequest(
                    name = name.takeIf { it.isNotEmpty() },
                    relation = relation.takeIf { it.isNotEmpty() },
                    age = age.takeIf { it.isNotEmpty() },
                    isVeg = isVeg
                )
            )
        }
        return result
    }

    private fun pickSiteVisitDate() {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            requireContext(),
            { _, y, m, d ->
                cal.set(y, m, d)
                siteVisitDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
                siteVisitDateView?.text = siteVisitDate
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    private fun pickSiteVisitTime() {
        val cal = Calendar.getInstance()
        TimePickerDialog(
            requireContext(),
            { _, hour, minute ->
                siteVisitTime = String.format(Locale.US, "%02d:%02d", hour, minute)
                siteVisitTimeView?.text = siteVisitTime
            },
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            false,
        ).show()
    }

    private fun toggleTravelMode() {
        selectedTravelMode = if (selectedTravelMode == "cab") "own_vehicle" else "cab"
        renderTravelMode()
    }

    private fun renderTravelMode() {
        siteVisitTravelMode?.text =
            if (selectedTravelMode == "own_vehicle") "Client travel: Own vehicle"
            else "Client travel: Cab required"
    }

    private fun togglePostponeReason(reason: String) {
        if (!selectedPostponeReasons.add(reason)) selectedPostponeReasons.remove(reason)
        postponeRow?.let { row ->
            for (i in 0 until row.childCount) {
                val v = row.getChildAt(i) as? TextView ?: continue
                applyChipState(v, selectedPostponeReasons.contains(v.tag as? String ?: ""))
            }
        }
    }

    private fun makeChip(label: String, onClick: () -> Unit): TextView {
        val tv = TextView(requireContext())
        tv.text = label
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        tv.setPadding(dp(16), dp(10), dp(16), dp(10))
        tv.isClickable = true
        tv.isFocusable = true
        tv.setOnClickListener { onClick() }
        return tv
    }

    private fun applyChipState(view: TextView?, active: Boolean) {
        val v = view ?: return
        v.setBackgroundResource(if (active) R.drawable.bg_chip_active else R.drawable.bg_chip_inactive)
        v.setTextColor(
            if (active) android.graphics.Color.WHITE
            else android.graphics.Color.parseColor("#1D2939")
        )
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun todayYmd(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)

    private fun defaultTime(): String =
        SimpleDateFormat("HH:mm", Locale.US).format(Calendar.getInstance().time)

    private fun showError(msg: String) {
        errorText?.text = msg
        errorText?.visibility = View.VISIBLE
    }

    private fun clearError() {
        errorText?.visibility = View.GONE
    }

    private fun onSubmit() {
        clearError()
        val cpVisitId = arguments?.getString(ARG_CP_VISIT_ID)
            ?: return showError("Missing CP visit id")
        // KOS-52: Sheet only shows on the "Yes, I saw the client" branch (after
        // OTP verify), so met is implicitly true. The "No" branch is handled
        // by TripNavigationFragment.completeCpVisitWithoutClient.
        val met = true
        val outcome = selectedOutcome ?: return showError("Please pick an outcome")
        if (outcome == OUTCOME_POSTPONED && selectedPostponeReasons.isEmpty()) {
            return showError("Pick at least one postpone reason")
        }
        if (outcome == OUTCOME_SITE_VISIT && selectedProject == null) {
            return showError("Please select a project for the site visit")
        }

        submitBtn?.isClickable = false
        submitBtn?.text = "Saving…"

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val metResp = geoApi.markClientMet(
                    session.bearerToken,
                    MarkClientMetRequest(
                        id = cpVisitId,
                        clientMet = met
                    )
                )
                if (!metResp.success) {
                    submitBtn?.isClickable = true
                    submitBtn?.text = "Submit"
                    showError(metResp.error ?: "Failed to record client met")
                    return@launch
                }

                if (outcome == OUTCOME_SITE_VISIT) {
                    val project = selectedProject ?: return@launch run {
                        submitBtn?.isClickable = true
                        submitBtn?.text = "Submit"
                        showError("Please select a project for the site visit")
                    }
                    val convertResp = geoApi.convertCpVisitToSiteVisit(
                        session.bearerToken,
                        ConvertCpVisitToSiteVisitRequest(
                            id = cpVisitId,
                            projectId = project.id,
                            scheduledDate = siteVisitDate,
                            scheduledTime = siteVisitTime,
                            assignedTelecallerStaffId = selectedBdo?.id,
                            inchargeStaffId = selectedIncharge?.id,
                            hodStaffId = selectedHod?.id,
                            avpStaffId = selectedAvp?.id,
                            gmStaffId = selectedGm?.id,
                            seniorManagerStaffId = selectedSeniorManager?.id,
                            expectedAttendeeCount = visitorCount?.text?.toString()?.toIntOrNull()?.takeIf { it > 0 },
                            attendees = collectVisitors().takeIf { it.isNotEmpty() },
                            pickupAddress = pickupAddress?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                            travelMode = selectedTravelMode,
                            foodPreferences = foodPreference?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                            notes = "Created from mobile CP visit"
                        )
                    )
                    if (!convertResp.success) {
                        submitBtn?.isClickable = true
                        submitBtn?.text = "Submit"
                        showError(convertResp.error ?: "Failed to create site visit")
                        return@launch
                    }
                    setFragmentResult(
                        RESULT_KEY,
                        bundleOf(
                            KEY_CLIENT_MET to met,
                            KEY_OUTCOME to outcome
                        )
                    )
                    dismissAllowingStateLoss()
                    return@launch
                }

                val outcomeResp = geoApi.setCpVisitOutcome(
                    session.bearerToken,
                    SetOutcomeRequest(
                        id = cpVisitId,
                        outcome = outcome,
                        postponeReasons = if (outcome == OUTCOME_POSTPONED) selectedPostponeReasons.toList() else null,
                        notes = buildOutcomeNotes(outcome)
                    )
                )
                if (!outcomeResp.success) {
                    submitBtn?.isClickable = true
                    submitBtn?.text = "Submit"
                    showError(outcomeResp.error ?: "Failed to set outcome")
                    return@launch
                }

                setFragmentResult(
                    RESULT_KEY,
                    bundleOf(
                        KEY_CLIENT_MET to met,
                        KEY_OUTCOME to outcome
                    )
                )
                dismissAllowingStateLoss()
            } catch (e: Exception) {
                submitBtn?.isClickable = true
                submitBtn?.text = "Submit"
                showError(e.message ?: "Network error")
                Toast.makeText(requireContext(), e.message ?: "Network error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Backend SetOutcomeRequest only carries a single `notes` string, so we
    // bundle the outcome-specific free-text fields into a labeled, multi-line
    // value the back office can display verbatim. Empty fields are omitted.
    private fun buildOutcomeNotes(outcome: String): String? {
        return when (outcome) {
            OUTCOME_POSTPONED -> {
                val parts = listOfNotNull(
                    postponeBudgetConcern?.text?.toString()?.trim()
                        ?.takeIf { it.isNotEmpty() }?.let { "Budget concern: $it" },
                    postponeTiming?.text?.toString()?.trim()
                        ?.takeIf { it.isNotEmpty() }?.let { "Timing: $it" },
                    postponeProjectDetails?.text?.toString()?.trim()
                        ?.takeIf { it.isNotEmpty() }?.let { "Project details: $it" },
                    postponeOtherDetails?.text?.toString()?.trim()
                        ?.takeIf { it.isNotEmpty() }?.let { "Other: $it" },
                )
                parts.joinToString("\n").takeIf { it.isNotBlank() }
            }
            OUTCOME_NOT_INTERESTED ->
                notInterestedNotes?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            OUTCOME_WAIT ->
                waitNotes?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    companion object {
        const val RESULT_KEY = "cp_visit_complete_result"
        const val KEY_CLIENT_MET = "clientMet"
        const val KEY_OUTCOME = "outcome"
        private const val ARG_CP_VISIT_ID = "arg_cp_visit_id"

        // Mapping from PRD §10 Phase B labels to backend outcomeValidator literals.
        private const val OUTCOME_BOOKING = "converted_to_booking"
        private const val OUTCOME_SITE_VISIT = "converted_to_site_visit"
        private const val OUTCOME_POSTPONED = "postponed"
        private const val OUTCOME_NOT_INTERESTED = "not_interested"
        private const val OUTCOME_WAIT = "interested"

        private val OUTCOME_OPTIONS = listOf(
            "Booking" to OUTCOME_BOOKING,
            "Site Visit" to OUTCOME_SITE_VISIT,
            "Postpone" to OUTCOME_POSTPONED,
            "Not Interested" to OUTCOME_NOT_INTERESTED,
            "Wait" to OUTCOME_WAIT,
        )

        private val POSTPONE_REASON_OPTIONS = listOf("Budget", "Timing", "Project", "Other")

        fun newInstance(
            cpVisitId: String,
            cpClientMet: Boolean? = null,
            cpOutcome: String? = null,
        ): CompleteCpVisitBottomSheet =
            CompleteCpVisitBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_CP_VISIT_ID, cpVisitId)
                    if (cpClientMet != null) putBoolean(ARG_CP_CLIENT_MET, cpClientMet)
                    if (!cpOutcome.isNullOrBlank()) putString(ARG_CP_OUTCOME, cpOutcome)
                }
            }

        private const val ARG_CP_CLIENT_MET = "arg_cp_client_met"
        private const val ARG_CP_OUTCOME = "arg_cp_outcome"
    }
}
