package com.manjugroups.m_connect.ui.projects

import android.app.DatePickerDialog
import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
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
import com.google.android.material.button.MaterialButton
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.CreateDailyLogRequest
import com.manjugroups.m_connect.network.DailyLogApi
import com.manjugroups.m_connect.network.DailyLogEquipment
import com.manjugroups.m_connect.network.DailyLogMaterial
import com.manjugroups.m_connect.network.ProjectSummary
import com.manjugroups.m_connect.ui.common.SearchableOption
import com.manjugroups.m_connect.ui.common.SearchableSelectionDialog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** New Daily Log form. Returns [RESULT_KEY] on save so the list reloads. */
class CreateDailyLogBottomSheet : BottomSheetDialogFragment() {

    private val api = ApiService.create()
    private val dprApi = DailyLogApi.create()
    private val session by lazy { SessionManager(requireContext()) }

    private var projects: List<ProjectSummary> = emptyList()
    private var selectedProject: ProjectSummary? = null
    private var date: String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)
    private var weather: String? = null
    private var condition: String? = null
    private var submitting = false

    private val weathers = listOf("sunny", "cloudy", "rainy", "windy", "stormy")
    private val conditions = listOf("good", "fair", "poor")

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { di ->
            (di as BottomSheetDialog).findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let {
                it.setBackgroundResource(R.drawable.bg_bottom_sheet)
                val b = BottomSheetBehavior.from(it)
                b.peekHeight = (resources.displayMetrics.heightPixels * 0.9f).toInt()
                b.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        // Keyboard pushes the sheet content up instead of covering the
        // focused input (matches the app's other form sheets).
        dialog?.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.bottom_sheet_create_daily_log, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val preId = arguments?.getString(ARG_PROJECT_ID)
        val preName = arguments?.getString(ARG_PROJECT_NAME)
        if (!preId.isNullOrBlank()) {
            selectedProject = ProjectSummary(id = preId, name = preName)
            view.findViewById<TextView>(R.id.tvProjectValue).text = preName ?: "Project"
        }
        view.findViewById<TextView>(R.id.tvDateValue).text = displayDate(date)
        view.findViewById<EditText>(R.id.etSupervisor).setText(session.userName.orEmpty())

        view.findViewById<View>(R.id.fieldProject).setOnClickListener { pickProject(view) }
        view.findViewById<View>(R.id.fieldDate).setOnClickListener { pickDate(view) }
        view.findViewById<View>(R.id.fieldWeather).setOnClickListener {
            pickOption("Weather", weathers) { weather = it; view.findViewById<TextView>(R.id.tvWeatherValue).text = it.replaceFirstChar(Char::uppercase) }
        }
        view.findViewById<View>(R.id.fieldSiteCondition).setOnClickListener {
            pickOption("Site Conditions", conditions) { condition = it; view.findViewById<TextView>(R.id.tvSiteConditionValue).text = it.replaceFirstChar(Char::uppercase) }
        }
        view.findViewById<View>(R.id.btnAddMaterial).setOnClickListener {
            view.findViewById<LinearLayout>(R.id.materialsContainer).addView(buildRow(listOf("Material" to 2f, "Qty" to 1f, "Unit" to 1f)))
        }
        view.findViewById<View>(R.id.btnAddEquipment).setOnClickListener {
            view.findViewById<LinearLayout>(R.id.equipmentContainer).addView(buildRow(listOf("Equipment" to 2f, "Hours" to 1f)))
        }
        view.findViewById<MaterialButton>(R.id.btnSubmitDailyLog).setOnClickListener { submit(view) }

        loadProjects()
    }

    private var projectsLoading = false
    private var pendingProjectPick = false

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
                root.findViewById<TextView>(R.id.tvProjectValue).text = "Loading projects…"
            } else {
                context?.let { Toast.makeText(it, "No projects", Toast.LENGTH_SHORT).show() }
                loadProjects()
            }
            return
        }
        val options = projects.map { SearchableOption(it, it.name ?: "Untitled project", it.status) }
        SearchableSelectionDialog.show(requireContext(), "Select project", options) { p ->
            if (view == null) return@show
            selectedProject = p
            root.findViewById<TextView>(R.id.tvProjectValue).text = p.name ?: "Project"
        }
    }

    private fun pickDate(root: View) {
        val cal = Calendar.getInstance()
        runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date) }.getOrNull()?.let { cal.time = it }
        DatePickerDialog(requireContext(), { _, y, m, d ->
            date = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
            root.findViewById<TextView>(R.id.tvDateValue).text = displayDate(date)
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun pickOption(title: String, opts: List<String>, onPick: (String) -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setItems(opts.map { it.replaceFirstChar(Char::uppercase) }.toTypedArray()) { _, i -> onPick(opts[i]) }
            .show()
    }

    private fun buildRow(fields: List<Pair<String, Float>>): LinearLayout {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        fields.forEach { (hint, weight) ->
            row.addView(EditText(ctx).apply {
                this.hint = hint
                setBackgroundResource(R.drawable.bg_input)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                textSize = 13f
                setTextColor(Color.parseColor("#101828"))
                setHintTextColor(Color.parseColor("#98A2B3"))
                if (hint == "Qty" || hint == "Hours") inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply { marginEnd = dp(6) }
            })
        }
        row.addView(TextView(ctx).apply {
            text = "✕"; textSize = 16f; setTextColor(Color.parseColor("#B42318")); setPadding(dp(8), dp(8), dp(4), dp(8))
            setOnClickListener { (parent as? ViewGroup)?.removeView(row) }
        })
        return row
    }

    private fun collectMaterials(root: View): List<DailyLogMaterial> {
        val c = root.findViewById<LinearLayout>(R.id.materialsContainer)
        val out = mutableListOf<DailyLogMaterial>()
        for (i in 0 until c.childCount) {
            val r = c.getChildAt(i) as? LinearLayout ?: continue
            val n = (r.getChildAt(0) as? EditText)?.text?.toString()?.trim().orEmpty()
            val q = (r.getChildAt(1) as? EditText)?.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0
            val u = (r.getChildAt(2) as? EditText)?.text?.toString()?.trim().orEmpty()
            if (n.isNotEmpty() && q > 0) out.add(DailyLogMaterial(n, q, u.ifBlank { null }))
        }
        return out
    }

    private fun collectEquipment(root: View): List<DailyLogEquipment> {
        val c = root.findViewById<LinearLayout>(R.id.equipmentContainer)
        val out = mutableListOf<DailyLogEquipment>()
        for (i in 0 until c.childCount) {
            val r = c.getChildAt(i) as? LinearLayout ?: continue
            val n = (r.getChildAt(0) as? EditText)?.text?.toString()?.trim().orEmpty()
            val h = (r.getChildAt(1) as? EditText)?.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0
            if (n.isNotEmpty() && h > 0) out.add(DailyLogEquipment(n, h))
        }
        return out
    }

    private fun submit(root: View) {
        val project = selectedProject
        val work = root.findViewById<EditText>(R.id.etWorkSummary).text?.toString()?.trim().orEmpty()
        if (project == null) { context?.let { Toast.makeText(it, "Select a project", Toast.LENGTH_SHORT).show() }; return }
        if (work.isEmpty()) { root.findViewById<EditText>(R.id.etWorkSummary).error = "Required"; return }
        if (submitting) return
        submitting = true
        val btn = root.findViewById<MaterialButton>(R.id.btnSubmitDailyLog).apply { isEnabled = false }
        val req = CreateDailyLogRequest(
            projectId = project.id,
            date = date,
            weather = weather,
            siteConditions = condition,
            workSummary = work,
            labourCount = root.findViewById<EditText>(R.id.etLabourCount).text?.toString()?.trim()?.toIntOrNull(),
            labourHours = root.findViewById<EditText>(R.id.etLabourHours).text?.toString()?.trim()?.toDoubleOrNull(),
            materialsUsed = collectMaterials(root).ifEmpty { null },
            equipmentUsed = collectEquipment(root).ifEmpty { null },
            issuesEncountered = root.findViewById<EditText>(R.id.etIssues).text?.toString()?.trim()?.ifBlank { null },
            safetyObservations = root.findViewById<EditText>(R.id.etSafety).text?.toString()?.trim()?.ifBlank { null },
            supervisorName = root.findViewById<EditText>(R.id.etSupervisor).text?.toString()?.trim()?.ifBlank { null },
        )
        viewLifecycleOwner.lifecycleScope.launch {
            val resp = runCatching { dprApi.createDailyLog(session.bearerToken, req) }.getOrNull()
            submitting = false
            if (view == null) return@launch
            btn.isEnabled = true
            if (resp?.success == true) {
                setFragmentResult(RESULT_KEY, bundleOf(KEY_PROJECT_ID to project.id))
                context?.let { Toast.makeText(it, "Daily log saved", Toast.LENGTH_SHORT).show() }
                dismissAllowingStateLoss()
            } else {
                context?.let { Toast.makeText(it, resp?.error ?: "Couldn't save", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun displayDate(iso: String): String = runCatching {
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)!!)
    }.getOrDefault(iso)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val RESULT_KEY = "daily_log_created"
        const val KEY_PROJECT_ID = "projectId"
        private const val ARG_PROJECT_ID = "arg_project_id"
        private const val ARG_PROJECT_NAME = "arg_project_name"

        fun newInstance(projectId: String?, projectName: String?) = CreateDailyLogBottomSheet().apply {
            arguments = bundleOf(ARG_PROJECT_ID to projectId, ARG_PROJECT_NAME to projectName)
        }
    }
}
