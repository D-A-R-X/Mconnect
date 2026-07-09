package com.manjugroups.m_connect.ui.projects

import android.app.DatePickerDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.CreateDailyLogRequest
import com.manjugroups.m_connect.network.DailyLogApi
import com.manjugroups.m_connect.network.DailyLogAttachment
import com.manjugroups.m_connect.network.DailyLogEquipment
import com.manjugroups.m_connect.network.DailyLogMaterial
import com.manjugroups.m_connect.network.ProjectSummary
import com.manjugroups.m_connect.network.StorageUploader
import com.manjugroups.m_connect.ui.common.SearchableOption
import com.manjugroups.m_connect.ui.common.SearchableSelectionDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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

    private data class PickedMedia(val uri: Uri, val isVideo: Boolean)
    private val pickedMedia = mutableListOf<PickedMedia>()

    // Photo Picker (no storage permission needed); images + videos, up to 10.
    private val pickMedia = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(10),
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val cr = context?.contentResolver
        uris.forEach { uri ->
            val isVideo = cr?.getType(uri)?.startsWith("video") == true
            pickedMedia.add(PickedMedia(uri, isVideo))
        }
        view?.let { renderAttachments(it) }
    }

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
        view.findViewById<View>(R.id.btnAddAttachment).setOnClickListener {
            pickMedia.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
            )
        }
        view.findViewById<MaterialButton>(R.id.btnSubmitDailyLog).setOnClickListener { submit(view) }

        renderAttachments(view)
        loadProjects()
    }

    private fun renderAttachments(root: View) {
        val c = root.findViewById<LinearLayout>(R.id.attachmentsContainer)
        c.removeAllViews()
        val ctx = context ?: return
        pickedMedia.forEach { m ->
            val frame = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(8) }
            }
            val iv = ImageView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(dp(72), dp(72))
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.bg_input)
                clipToOutline = true
                load(m.uri)
            }
            frame.addView(iv)
            if (m.isVideo) {
                frame.addView(TextView(ctx).apply {
                    text = "▶"
                    textSize = 20f
                    setTextColor(Color.WHITE)
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
                    )
                })
            }
            frame.addView(TextView(ctx).apply {
                text = "✕"
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundResource(R.drawable.bg_attn_action_reject)
                setPadding(dp(5), dp(1), dp(5), dp(2))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END,
                ).apply { topMargin = dp(3); marginEnd = dp(3) }
                setOnClickListener { pickedMedia.remove(m); renderAttachments(root) }
            })
            c.addView(frame)
        }
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
        val appCtx = requireContext().applicationContext
        val labourCount = root.findViewById<EditText>(R.id.etLabourCount).text?.toString()?.trim()?.toIntOrNull()
        val labourHours = root.findViewById<EditText>(R.id.etLabourHours).text?.toString()?.trim()?.toDoubleOrNull()
        val materials = collectMaterials(root).ifEmpty { null }
        val equipment = collectEquipment(root).ifEmpty { null }
        val issues = root.findViewById<EditText>(R.id.etIssues).text?.toString()?.trim()?.ifBlank { null }
        val safety = root.findViewById<EditText>(R.id.etSafety).text?.toString()?.trim()?.ifBlank { null }
        val supervisor = root.findViewById<EditText>(R.id.etSupervisor).text?.toString()?.trim()?.ifBlank { null }

        viewLifecycleOwner.lifecycleScope.launch {
            // Upload any picked photos/videos first, then create the log with
            // their storage IDs. A failed upload aborts the save so the entry
            // never lands without its attachments.
            if (pickedMedia.isNotEmpty()) btn.text = "Uploading media…"
            val attachments = uploadPickedMedia(appCtx)
            if (view == null) return@launch
            if (attachments == null) {
                submitting = false
                btn.isEnabled = true
                btn.text = "Save Daily Log"
                Toast.makeText(appCtx, "Couldn't upload media. Check your connection and try again.", Toast.LENGTH_LONG).show()
                return@launch
            }
            btn.text = "Saving…"
            val req = CreateDailyLogRequest(
                projectId = project.id,
                date = date,
                weather = weather,
                siteConditions = condition,
                workSummary = work,
                labourCount = labourCount,
                labourHours = labourHours,
                materialsUsed = materials,
                equipmentUsed = equipment,
                issuesEncountered = issues,
                safetyObservations = safety,
                supervisorName = supervisor,
                attachments = attachments.ifEmpty { null },
            )
            val resp = runCatching { dprApi.createDailyLog(session.bearerToken, req) }.getOrNull()
            submitting = false
            if (view == null) return@launch
            btn.isEnabled = true
            btn.text = "Save Daily Log"
            if (resp?.success == true) {
                setFragmentResult(RESULT_KEY, bundleOf(KEY_PROJECT_ID to project.id))
                Toast.makeText(appCtx, "Daily log saved", Toast.LENGTH_SHORT).show()
                dismissAllowingStateLoss()
            } else {
                Toast.makeText(appCtx, resp?.error ?: "Couldn't save", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Copy each picked media to a temp file and upload it to storage. Returns
     * the attachment list, or null if any upload failed (so the caller aborts).
     */
    private suspend fun uploadPickedMedia(ctx: Context): List<DailyLogAttachment>? =
        withContext(Dispatchers.IO) {
            if (pickedMedia.isEmpty()) return@withContext emptyList()
            val cr = ctx.contentResolver
            val out = mutableListOf<DailyLogAttachment>()
            for (m in pickedMedia.toList()) {
                val mime = cr.getType(m.uri) ?: if (m.isVideo) "video/mp4" else "image/jpeg"
                val ext = if (m.isVideo) "mp4" else "jpg"
                val tmp = runCatching {
                    val f = File.createTempFile("dpr_", ".$ext", ctx.cacheDir)
                    val copied = cr.openInputStream(m.uri)?.use { inp ->
                        f.outputStream().use { inp.copyTo(it) }; true
                    } ?: false
                    if (!copied) { f.delete(); null } else f
                }.getOrNull() ?: return@withContext null
                val result = StorageUploader.upload(api, session.bearerToken, tmp, contentType = mime)
                tmp.delete()
                val id = result.storageId ?: return@withContext null
                out.add(DailyLogAttachment(storageId = id, type = if (m.isVideo) "video" else "image"))
            }
            out
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
