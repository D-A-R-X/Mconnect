package com.manjugroups.m_connect.ui.projects

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
import com.manjugroups.m_connect.network.MaterialCatalogItem
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

    // Camera capture (photo / video) into a FileProvider URI.
    private var cameraCaptureUri: Uri? = null
    private var pendingCameraVideo = false
    private val takePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok ->
        val uri = cameraCaptureUri
        if (ok && uri != null) { pickedMedia.add(PickedMedia(uri, false)); view?.let { renderAttachments(it) } }
    }
    private val takeVideoLauncher = registerForActivityResult(
        ActivityResultContracts.CaptureVideo(),
    ) { ok ->
        val uri = cameraCaptureUri
        if (ok && uri != null) { pickedMedia.add(PickedMedia(uri, true)); view?.let { renderAttachments(it) } }
    }
    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchCamera(pendingCameraVideo)
        else context?.let { Toast.makeText(it, "Camera permission is required", Toast.LENGTH_SHORT).show() }
    }

    // Material catalog (Library > Material Catalog); fetched once, cached.
    private var materialsCache: List<MaterialCatalogItem>? = null
    private var materialsLoading = false

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
            pickFromList("Weather", weathers) { w ->
                weather = w
                view.findViewById<TextView>(R.id.tvWeatherValue).text = w.replaceFirstChar(Char::uppercase)
            }
        }
        view.findViewById<View>(R.id.fieldSiteCondition).setOnClickListener {
            pickFromList("Site Conditions", conditions) { c ->
                condition = c
                view.findViewById<TextView>(R.id.tvSiteConditionValue).text = c.replaceFirstChar(Char::uppercase)
            }
        }
        view.findViewById<View>(R.id.btnAddMaterial).setOnClickListener { pickMaterial(view) }
        view.findViewById<View>(R.id.btnAddEquipment).setOnClickListener {
            view.findViewById<LinearLayout>(R.id.equipmentContainer).addView(buildRow(listOf("Equipment" to 2f, "Hours" to 1f)))
        }
        view.findViewById<View>(R.id.btnAddAttachment).setOnClickListener { showMediaSourceChooser() }
        view.findViewById<MaterialButton>(R.id.btnSubmitDailyLog).setOnClickListener { submit(view) }

        renderAttachments(view)
        loadProjects()
        loadMaterials()
    }

    /** Camera (photo/video) or the gallery picker — the app's receipt pattern. */
    private fun showMediaSourceChooser() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Add photo or video")
            .setItems(arrayOf("Take photo", "Record video", "Choose from gallery")) { _, which ->
                when (which) {
                    0 -> requestCameraThen(video = false)
                    1 -> requestCameraThen(video = true)
                    2 -> pickMedia.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                    )
                }
            }
            .show()
    }

    private fun requestCameraThen(video: Boolean) {
        pendingCameraVideo = video
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            launchCamera(video)
        } else {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera(video: Boolean) {
        val ctx = context ?: return
        val f = runCatching {
            File.createTempFile(if (video) "dpr_vid_" else "dpr_img_", if (video) ".mp4" else ".jpg", ctx.cacheDir)
        }.getOrNull() ?: run {
            Toast.makeText(ctx, "Unable to create file", Toast.LENGTH_SHORT).show(); return
        }
        val uri = runCatching {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        }.getOrNull() ?: run {
            Toast.makeText(ctx, "Unable to create file", Toast.LENGTH_SHORT).show(); return
        }
        cameraCaptureUri = uri
        if (video) takeVideoLauncher.launch(uri) else takePhotoLauncher.launch(uri)
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
            // Marketing/projects returns EVERY project the caller can access
            // (admins with projects.viewAll get all of them) — unlike the
            // scoped /api/projects, so the picker isn't limited to a handful.
            val resp = runCatching { api.getMarketingProjects(session.bearerToken) }.getOrNull()
            projectsLoading = false
            if (view == null) return@launch
            projects = resp?.projects
                ?.map { ProjectSummary(id = it.id, name = it.name, status = it.status) }
                ?: emptyList()
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
        val startMs = runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.parse(date)?.time
        }.getOrNull() ?: com.google.android.material.datepicker.MaterialDatePicker.todayInUtcMilliseconds()
        val picker = com.google.android.material.datepicker.MaterialDatePicker.Builder.datePicker()
            .setTitleText("Select date")
            .setSelection(startMs)
            .build()
        picker.addOnPositiveButtonClickListener { sel ->
            val c = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = sel }
            date = String.format(
                Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            )
            if (view != null) root.findViewById<TextView>(R.id.tvDateValue).text = displayDate(date)
        }
        picker.show(childFragmentManager, "daily_log_date")
    }

    /** Bottom-sheet list picker (the app's shared searchable selector). */
    private fun pickFromList(title: String, opts: List<String>, onPick: (String) -> Unit) {
        val options = opts.map { SearchableOption(it, it.replaceFirstChar(Char::uppercase)) }
        SearchableSelectionDialog.show(requireContext(), title, options) { onPick(it) }
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

    private fun loadMaterials() {
        if (materialsLoading || materialsCache != null) return
        materialsLoading = true
        viewLifecycleOwner.lifecycleScope.launch {
            val resp = runCatching { api.getMaterials(session.bearerToken) }.getOrNull()
            materialsLoading = false
            if (view == null) return@launch
            materialsCache = resp?.materials?.filter { !it.name.isNullOrBlank() } ?: emptyList()
        }
    }

    /** Pick a material from the master catalog; adds a row with a qty field. */
    private fun pickMaterial(root: View) {
        val cached = materialsCache
        if (cached == null) {
            if (materialsLoading) context?.let { Toast.makeText(it, "Loading materials…", Toast.LENGTH_SHORT).show() }
            else loadMaterials()
            return
        }
        if (cached.isEmpty()) {
            context?.let { Toast.makeText(it, "No materials in the catalog", Toast.LENGTH_SHORT).show() }
            return
        }
        val options = cached.map {
            SearchableOption(it, it.name ?: "Material", listOfNotNull(it.category, it.unit, it.brand).joinToString(" · "))
        }
        SearchableSelectionDialog.show(requireContext(), "Select material", options) { m ->
            if (view == null) return@show
            addCatalogMaterialRow(root, m.name ?: "Material", m.unit ?: "")
        }
    }

    private fun addCatalogMaterialRow(root: View, name: String, unit: String) {
        val ctx = requireContext()
        val container = root.findViewById<LinearLayout>(R.id.materialsContainer)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = arrayOf(name, unit) // name + unit read back in collectMaterials
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
        val info = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        }
        info.addView(TextView(ctx).apply {
            text = name; textSize = 13f; setTextColor(Color.parseColor("#101828")); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (unit.isNotBlank()) info.addView(TextView(ctx).apply {
            text = unit; textSize = 11f; setTextColor(Color.parseColor("#98A2B3"))
        })
        row.addView(info)
        row.addView(EditText(ctx).apply {
            hint = "Qty"
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            textSize = 13f
            setTextColor(Color.parseColor("#101828"))
            setHintTextColor(Color.parseColor("#98A2B3"))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(8); marginEnd = dp(6) }
        })
        row.addView(TextView(ctx).apply {
            text = "✕"; textSize = 16f; setTextColor(Color.parseColor("#B42318")); setPadding(dp(8), dp(8), dp(4), dp(8))
            setOnClickListener { container.removeView(row) }
        })
        container.addView(row)
    }

    private fun collectMaterials(root: View): List<DailyLogMaterial> {
        val c = root.findViewById<LinearLayout>(R.id.materialsContainer)
        val out = mutableListOf<DailyLogMaterial>()
        for (i in 0 until c.childCount) {
            val r = c.getChildAt(i) as? LinearLayout ?: continue
            val tag = r.tag as? Array<*>
            val name = (tag?.getOrNull(0) as? String)?.trim().orEmpty()
            val unit = (tag?.getOrNull(1) as? String)?.trim().orEmpty()
            val qty = (r.getChildAt(1) as? EditText)?.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0
            if (name.isNotEmpty() && qty > 0) out.add(DailyLogMaterial(name, qty, unit.ifBlank { null }))
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
