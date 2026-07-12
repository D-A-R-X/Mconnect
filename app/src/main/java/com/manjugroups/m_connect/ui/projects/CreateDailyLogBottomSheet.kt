package com.manjugroups.m_connect.ui.projects

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import com.manjugroups.m_connect.ui.chat.CustomCameraBottomSheet
import com.manjugroups.m_connect.ui.common.ImagePreviewDialog
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
    private var submitted = false

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

    // Camera permission gate for the rich in-app camera (shared with chat:
    // live preview, flash, 0.5/1x/2x zoom, photo/video toggle, gallery).
    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) showCustomCamera()
        else context?.let { Toast.makeText(it, "Camera permission is required", Toast.LENGTH_SHORT).show() }
    }

    // Material catalog (Library > Material Catalog); fetched once, cached.
    private var materialsCache: List<MaterialCatalogItem>? = null
    private var materialsLoading = false

    // Draft auto-save so a crash / accidental close while filling isn't lost.
    private val gson = com.google.gson.Gson()
    private var restoring = false
    private val draftWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun afterTextChanged(s: Editable?) { if (!restoring) view?.let { saveDraft(it) } }
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
            pickFromList("Weather", weathers) { w ->
                weather = w
                view.findViewById<TextView>(R.id.tvWeatherValue).text = w.replaceFirstChar(Char::uppercase)
                saveDraft(view)
            }
        }
        view.findViewById<View>(R.id.fieldSiteCondition).setOnClickListener {
            pickFromList("Site Conditions", conditions) { c ->
                condition = c
                view.findViewById<TextView>(R.id.tvSiteConditionValue).text = c.replaceFirstChar(Char::uppercase)
                saveDraft(view)
            }
        }
        view.findViewById<View>(R.id.btnAddMaterial).setOnClickListener { pickMaterial(view) }
        view.findViewById<View>(R.id.btnAddEquipment).setOnClickListener { addEquipmentRow(view) }
        view.findViewById<View>(R.id.btnAddAttachment).setOnClickListener { openCustomCamera() }
        view.findViewById<MaterialButton>(R.id.btnSubmitDailyLog).setOnClickListener { submit(view) }

        // Auto-save a draft as the main fields change (crash-safe filling).
        listOf(R.id.etWorkSummary, R.id.etLabourCount, R.id.etLabourHours, R.id.etIssues, R.id.etSafety, R.id.etSupervisor)
            .forEach { view.findViewById<EditText>(it).addTextChangedListener(draftWatcher) }

        renderAttachments(view)
        restoreDraft(view)
        loadProjects()
    }

    override fun onPause() {
        super.onPause()
        // Capture everything (incl. material/equipment rows) before the app
        // can be backgrounded + killed. Skip once submitted (draft is cleared).
        view?.let { if (!submitting && !submitted) saveDraft(it) }
    }

    private fun showLoadingOverlay(label: String) {
        val v = view ?: return
        v.findViewById<TextView>(R.id.tvLoadingLabel).text = label
        v.findViewById<View>(R.id.loadingOverlay).visibility = View.VISIBLE
    }

    private fun hideLoadingOverlay() {
        view?.findViewById<View>(R.id.loadingOverlay)?.visibility = View.GONE
    }

    /**
     * Rich in-app camera shared with chat ([CustomCameraBottomSheet]): live
     * preview, flash, 0.5/1x/2x zoom, photo↔video toggle, and a Gallery button.
     * Captured media comes back as a file:// Uri; Gallery delegates to the same
     * Photo Picker used before. Needs CAMERA permission first (the sheet assumes
     * it's granted before the preview binds).
     */
    private fun openCustomCamera() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            showCustomCamera()
        } else {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun showCustomCamera() {
        if (!isAdded) return
        // Build the listener with a plain local — NOT inside
        // `CustomCameraBottomSheet().apply { ... }`. Inside that apply block
        // the implicit receiver is the CAMERA sheet, so an unqualified `view`
        // resolves to the camera's view (both classes are Fragments), not
        // this sheet's. renderAttachments would then search the camera's
        // layout for attachmentsContainer, get null, and crash on capture.
        // Qualify `view` to this sheet explicitly so it can't happen again.
        val camera = CustomCameraBottomSheet()
        camera.setListener(object : CustomCameraBottomSheet.CameraResultListener {
            override fun onMediaCaptured(uri: Uri, isVideo: Boolean) {
                pickedMedia.add(PickedMedia(uri, isVideo))
                this@CreateDailyLogBottomSheet.view?.let { renderAttachments(it) }
            }

            override fun onGalleryClicked() {
                pickMedia.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                )
            }
        })
        camera.show(childFragmentManager, "daily_log_camera")
    }

    private fun renderAttachments(root: View) {
        // Guard the lookup: if we're ever handed a root that doesn't contain
        // the container (wrong view hierarchy), bail instead of NPE-ing.
        val c = root.findViewById<LinearLayout>(R.id.attachmentsContainer) ?: return
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
                // Tap an image thumbnail to preview it full-screen in-app.
                if (!m.isVideo) setOnClickListener {
                    ImagePreviewDialog.show(requireContext(), m.uri.toString())
                }
            }
            frame.addView(iv)
            if (m.isVideo) {
                frame.addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_home_trip_play)
                    setColorFilter(Color.WHITE)
                    setBackgroundResource(R.drawable.bg_home_new_action_circle)
                    backgroundTintList =
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#66000000"))
                    val p = dp(6); setPadding(p, p, p, p)
                    layoutParams = FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)
                })
            }
            frame.addView(TextView(ctx).apply {
                text = "✕"
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                includeFontPadding = false
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor("#E6111827"))
                    setStroke(dp(2), Color.WHITE)
                }
                layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(4); marginEnd = dp(4)
                }
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
            saveDraft(root)
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
            if (view != null) { root.findViewById<TextView>(R.id.tvDateValue).text = displayDate(date); saveDraft(root) }
        }
        picker.show(childFragmentManager, "daily_log_date")
    }

    /** Bottom-sheet list picker (the app's shared searchable selector). */
    private fun pickFromList(title: String, opts: List<String>, onPick: (String) -> Unit) {
        val options = opts.map { SearchableOption(it, it.replaceFirstChar(Char::uppercase)) }
        SearchableSelectionDialog.show(requireContext(), title, options) { onPick(it) }
    }

    /** Clean circular ✕ remove button shared by material/equipment rows. */
    private fun removeRowButton(onRemove: () -> Unit): TextView {
        val ctx = requireContext()
        return TextView(ctx).apply {
            text = "✕"
            textSize = 12f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(Color.parseColor("#667085"))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#F2F4F7"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(8) }
            setOnClickListener { onRemove() }
        }
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
                addTextChangedListener(draftWatcher)
            })
        }
        row.addView(removeRowButton {
            // row.parent (the container), NOT the unqualified `parent` — inside
            // this lambda `parent` was the ✕ view's parent (the row itself), so
            // removeView(row) was a no-op and Equipment rows couldn't be removed.
            (row.parent as? ViewGroup)?.removeView(row); view?.let { saveDraft(it) }
        })
        return row
    }

    private fun addEquipmentRow(root: View, name: String = "", hours: String = "") {
        val row = buildRow(listOf("Equipment" to 2f, "Hours" to 1f))
        (row.getChildAt(0) as? EditText)?.setText(name)
        (row.getChildAt(1) as? EditText)?.setText(hours)
        root.findViewById<LinearLayout>(R.id.equipmentContainer).addView(row)
        if (!restoring) saveDraft(root)
    }

    /** Pick a material from the master catalog; adds a row with a qty field. */
    private fun pickMaterial(root: View) {
        materialsCache?.let { showMaterialPicker(root, it); return }
        if (materialsLoading) return
        materialsLoading = true
        showLoadingOverlay("Loading materials…")
        viewLifecycleOwner.lifecycleScope.launch {
            val resp = runCatching { api.getMaterials(session.bearerToken) }.getOrNull()
            materialsLoading = false
            if (view == null) return@launch
            hideLoadingOverlay()
            val mats = resp?.materials?.filter { !it.name.isNullOrBlank() } ?: emptyList()
            materialsCache = mats
            if (mats.isEmpty()) {
                context?.let { Toast.makeText(it, "No materials in the catalog", Toast.LENGTH_SHORT).show() }
                return@launch
            }
            showMaterialPicker(root, mats)
        }
    }

    private fun showMaterialPicker(root: View, cached: List<MaterialCatalogItem>) {
        val options = cached.map {
            SearchableOption(it, it.name ?: "Material", listOfNotNull(it.category, it.unit, it.brand).joinToString(" · "))
        }
        SearchableSelectionDialog.show(requireContext(), "Select material", options) { m ->
            if (view == null) return@show
            addCatalogMaterialRow(root, m.name ?: "Material", m.unit ?: "")
        }
    }

    private fun addCatalogMaterialRow(root: View, name: String, unit: String, qty: String = "") {
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
            setText(qty)
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            textSize = 13f
            setTextColor(Color.parseColor("#101828"))
            setHintTextColor(Color.parseColor("#98A2B3"))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(8); marginEnd = dp(6) }
            addTextChangedListener(draftWatcher)
        })
        row.addView(removeRowButton {
            container.removeView(row); view?.let { saveDraft(it) }
        })
        container.addView(row)
        if (!restoring) saveDraft(root)
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
        // Lock the sheet shut while uploading/saving so a swipe/back/tap-outside
        // can't abandon an in-flight photo upload or a half-saved entry.
        isCancelable = false
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
                isCancelable = true
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
                submitted = true
                clearDraft()
                // Remember this log's uploaded media locally so it renders even
                // before the backend `attachments` field is deployed to prod.
                // Key on content (project+date+summary — always reconstructable
                // when the list reloads) and also on the id if the route
                // returned one.
                if (attachments.isNotEmpty()) {
                    DailyLogAttachmentCache.put(
                        appCtx, DailyLogAttachmentCache.key(project.id, date, work), attachments,
                    )
                    DailyLogAttachmentCache.put(appCtx, resp.id, attachments)
                }
                setFragmentResult(RESULT_KEY, bundleOf(KEY_PROJECT_ID to project.id))
                Toast.makeText(appCtx, "Daily log saved", Toast.LENGTH_SHORT).show()
                dismissAllowingStateLoss()
            } else {
                isCancelable = true
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

    // ── Draft persistence (crash-safe filling) ──

    private fun saveDraft(root: View) {
        if (restoring) return
        val prefs = context?.getSharedPreferences(DRAFT_PREFS, Context.MODE_PRIVATE) ?: return
        val draft = DailyLogDraft(
            projectId = selectedProject?.id,
            projectName = selectedProject?.name,
            date = date,
            weather = weather,
            condition = condition,
            workSummary = root.findViewById<EditText>(R.id.etWorkSummary).text?.toString(),
            labourCount = root.findViewById<EditText>(R.id.etLabourCount).text?.toString(),
            labourHours = root.findViewById<EditText>(R.id.etLabourHours).text?.toString(),
            issues = root.findViewById<EditText>(R.id.etIssues).text?.toString(),
            safety = root.findViewById<EditText>(R.id.etSafety).text?.toString(),
            supervisor = root.findViewById<EditText>(R.id.etSupervisor).text?.toString(),
            materials = draftMaterials(root),
            equipment = draftEquipment(root),
        )
        val hasContent = !draft.workSummary.isNullOrBlank() || draft.projectId != null ||
            !draft.labourCount.isNullOrBlank() || !draft.labourHours.isNullOrBlank() ||
            !draft.issues.isNullOrBlank() || !draft.safety.isNullOrBlank() ||
            draft.materials.isNotEmpty() || draft.equipment.isNotEmpty()
        runCatching {
            if (hasContent) prefs.edit().putString(DRAFT_KEY, gson.toJson(draft)).apply()
            else prefs.edit().remove(DRAFT_KEY).apply()
        }
    }

    private fun draftMaterials(root: View): List<DraftMaterial> {
        val c = root.findViewById<LinearLayout>(R.id.materialsContainer)
        val out = mutableListOf<DraftMaterial>()
        for (i in 0 until c.childCount) {
            val r = c.getChildAt(i) as? LinearLayout ?: continue
            val tag = r.tag as? Array<*>
            val name = (tag?.getOrNull(0) as? String).orEmpty()
            val unit = (tag?.getOrNull(1) as? String).orEmpty()
            val qty = (r.getChildAt(1) as? EditText)?.text?.toString().orEmpty()
            if (name.isNotBlank()) out.add(DraftMaterial(name, unit, qty))
        }
        return out
    }

    private fun draftEquipment(root: View): List<DraftEquipment> {
        val c = root.findViewById<LinearLayout>(R.id.equipmentContainer)
        val out = mutableListOf<DraftEquipment>()
        for (i in 0 until c.childCount) {
            val r = c.getChildAt(i) as? LinearLayout ?: continue
            val name = (r.getChildAt(0) as? EditText)?.text?.toString().orEmpty()
            val hours = (r.getChildAt(1) as? EditText)?.text?.toString().orEmpty()
            if (name.isNotBlank() || hours.isNotBlank()) out.add(DraftEquipment(name, hours))
        }
        return out
    }

    private fun restoreDraft(root: View) {
        val prefs = context?.getSharedPreferences(DRAFT_PREFS, Context.MODE_PRIVATE) ?: return
        val json = prefs.getString(DRAFT_KEY, null) ?: return
        val draft = runCatching { gson.fromJson(json, DailyLogDraft::class.java) }.getOrNull() ?: return
        restoring = true
        if (selectedProject == null && !draft.projectId.isNullOrBlank()) {
            selectedProject = ProjectSummary(id = draft.projectId, name = draft.projectName)
            root.findViewById<TextView>(R.id.tvProjectValue).text = draft.projectName ?: "Project"
        }
        draft.date?.takeIf { it.isNotBlank() }?.let {
            date = it; root.findViewById<TextView>(R.id.tvDateValue).text = displayDate(it)
        }
        draft.weather?.takeIf { it.isNotBlank() }?.let {
            weather = it; root.findViewById<TextView>(R.id.tvWeatherValue).text = it.replaceFirstChar(Char::uppercase)
        }
        draft.condition?.takeIf { it.isNotBlank() }?.let {
            condition = it; root.findViewById<TextView>(R.id.tvSiteConditionValue).text = it.replaceFirstChar(Char::uppercase)
        }
        root.findViewById<EditText>(R.id.etWorkSummary).setText(draft.workSummary.orEmpty())
        root.findViewById<EditText>(R.id.etLabourCount).setText(draft.labourCount.orEmpty())
        root.findViewById<EditText>(R.id.etLabourHours).setText(draft.labourHours.orEmpty())
        root.findViewById<EditText>(R.id.etIssues).setText(draft.issues.orEmpty())
        root.findViewById<EditText>(R.id.etSafety).setText(draft.safety.orEmpty())
        if (!draft.supervisor.isNullOrBlank()) root.findViewById<EditText>(R.id.etSupervisor).setText(draft.supervisor)
        draft.materials.forEach { addCatalogMaterialRow(root, it.name, it.unit, it.qty) }
        draft.equipment.forEach { addEquipmentRow(root, it.name, it.hours) }
        restoring = false
        context?.let { Toast.makeText(it, "Draft restored", Toast.LENGTH_SHORT).show() }
    }

    private fun clearDraft() {
        context?.getSharedPreferences(DRAFT_PREFS, Context.MODE_PRIVATE)?.edit()?.remove(DRAFT_KEY)?.apply()
    }

    companion object {
        private const val DRAFT_PREFS = "daily_log_draft"
        private const val DRAFT_KEY = "new_entry"
        const val RESULT_KEY = "daily_log_created"
        const val KEY_PROJECT_ID = "projectId"
        private const val ARG_PROJECT_ID = "arg_project_id"
        private const val ARG_PROJECT_NAME = "arg_project_name"

        fun newInstance(projectId: String?, projectName: String?) = CreateDailyLogBottomSheet().apply {
            arguments = bundleOf(ARG_PROJECT_ID to projectId, ARG_PROJECT_NAME to projectName)
        }
    }
}

private data class DailyLogDraft(
    val projectId: String? = null,
    val projectName: String? = null,
    val date: String? = null,
    val weather: String? = null,
    val condition: String? = null,
    val workSummary: String? = null,
    val labourCount: String? = null,
    val labourHours: String? = null,
    val issues: String? = null,
    val safety: String? = null,
    val supervisor: String? = null,
    val materials: List<DraftMaterial> = emptyList(),
    val equipment: List<DraftEquipment> = emptyList(),
)

private data class DraftMaterial(val name: String, val unit: String, val qty: String)
private data class DraftEquipment(val name: String, val hours: String)
