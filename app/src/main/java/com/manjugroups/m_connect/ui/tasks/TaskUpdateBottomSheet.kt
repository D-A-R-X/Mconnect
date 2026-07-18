package com.manjugroups.m_connect.ui.tasks

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.ui.hr.CalendarRangePickerSheet
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.AddTaskUpdateRequest
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.TaskUpdateImage
import com.manjugroups.m_connect.network.UpdateTaskRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import com.manjugroups.m_connect.ui.common.showOnce

/**
 * Sheet for staff to update an assigned task — date, status, progress,
 * today's notes, blocker, tomorrow's plan, and photo proof.
 *
 * Submit flow:
 *   1. Upload any newly-picked photos to Convex storage in parallel.
 *   2. POST /api/projects/tasks/update — patches the task's static
 *      status/progress/actualStartDate/actualEndDate so dashboards
 *      reflect the latest state.
 *   3. POST /api/projects/tasks/add-update — appends a timeline entry
 *      (date + notes + photos + progress snapshot) so the Time Line
 *      screen shows the per-day history the Figma mockup describes.
 *
 * Progress ↔ Status are linked both ways:
 *   - Clicking "Completed" snaps progress to 100; "Not Started" to 0.
 *   - Dragging progress to 100 selects "Completed"; >0 from a fresh
 *     "Not Started" auto-promotes to "In Progress".
 */
class TaskUpdateBottomSheet : BottomSheetDialogFragment() {

    private val api = ApiService.create()
    private lateinit var session: SessionManager

    private var taskId: String = ""
    private var status: String = "in-progress"
    private var progress: Int = 0
    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val displayFormatter = SimpleDateFormat("MMM d, yyyy", Locale.US)
    private var pickedDateIso: String = isoFormatter.format(Date())

    private var statusViews: Map<String, TextView> = emptyMap()
    private var seekProgress: SeekBar? = null
    private var tvProgressValue: TextView? = null
    private var tvUpdateDate: TextView? = null
    private var etTodaysUpdate: EditText? = null
    private var etBlocker: EditText? = null
    private var etTomorrowsPlan: EditText? = null
    private var photoStrip: LinearLayout? = null
    private var btnAddPhoto: View? = null
    private var tvPhotoCount: TextView? = null
    private var errorText: TextView? = null
    private var submitBtn: View? = null
    private var cancelBtn: View? = null

    /** Picked-but-not-yet-uploaded photos (local content URIs). */
    private val pendingPhotos = mutableListOf<Uri>()

    /** Photos that were on the task already at open time. */
    private val existingPhotos = mutableListOf<TaskUpdateImage>()

    private val photoPicker = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        uris.forEach { pendingPhotos.add(it) }
        refreshPhotoStrip()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        // ADJUST_RESIZE keeps the focused input visible above the soft
        // keyboard. Without it the keyboard overlays the lower half of the
        // sheet — Today's Update, Issues / Blockers and the Submit button all
        // end up underneath it with no way to scroll them back into view.
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_task_update, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        val args = requireArguments()
        taskId = args.getString(ARG_TASK_ID).orEmpty()
        status = args.getString(ARG_STATUS).takeUnless { it.isNullOrBlank() } ?: "in-progress"
        progress = args.getInt(ARG_PROGRESS, 0).coerceIn(0, 100)

        statusViews = mapOf(
            "not-started" to view.findViewById(R.id.statusNotStarted),
            "in-progress" to view.findViewById(R.id.statusInProgress),
            "completed" to view.findViewById(R.id.statusCompleted),
            "delayed" to view.findViewById(R.id.statusDelayed),
        )
        seekProgress = view.findViewById(R.id.seekProgress)
        tvProgressValue = view.findViewById(R.id.tvProgressValue)
        tvUpdateDate = view.findViewById(R.id.tvUpdateDate)
        etTodaysUpdate = view.findViewById(R.id.etTodaysUpdate)
        etBlocker = view.findViewById(R.id.etBlocker)
        etTomorrowsPlan = view.findViewById(R.id.etTomorrowsPlan)
        photoStrip = view.findViewById(R.id.photoStrip)
        btnAddPhoto = view.findViewById(R.id.btnAddPhoto)
        tvPhotoCount = view.findViewById(R.id.tvPhotoCount)
        errorText = view.findViewById(R.id.tvUpdateError)
        submitBtn = view.findViewById(R.id.btnSubmitUpdate)
        cancelBtn = view.findViewById(R.id.btnCancelUpdate)

        etTodaysUpdate?.setText(args.getString(ARG_TODAYS_UPDATE).orEmpty())
        etBlocker?.setText(args.getString(ARG_BLOCKER).orEmpty())
        etTomorrowsPlan?.setText(args.getString(ARG_TOMORROWS_PLAN).orEmpty())

        // ── Date picker — defaults to today. Used as the timeline
        //    entry's date when we POST /api/projects/tasks/add-update.
        tvUpdateDate?.text = displayFormatter.format(Date())
        tvUpdateDate?.setOnClickListener { showDatePicker() }

        // ── Photo strip — re-render any pre-loaded photos + wire add.
        val existingStorageIds = args.getStringArrayList(ARG_EXISTING_PHOTO_STORAGE_IDS)
        val existingUrls = args.getStringArrayList(ARG_EXISTING_PHOTO_URLS)
        if (existingStorageIds != null) {
            existingStorageIds.forEachIndexed { i, sid ->
                existingPhotos.add(
                    TaskUpdateImage(
                        storageId = sid,
                        url = existingUrls?.getOrNull(i),
                        name = null,
                    )
                )
            }
        }
        btnAddPhoto?.setOnClickListener {
            // GetMultipleContents lets the user shift-pick several at once.
            photoPicker.launch("image/*")
        }
        refreshPhotoStrip()

        // ── Status pills — register click handlers BEFORE seeding the
        //    seekbar listener so the listener picks up the auto-sync
        //    when we apply the initial status below.
        statusViews.forEach { (key, tv) ->
            tv.setOnClickListener { setStatus(key, fromUser = true) }
        }

        // ── Progress seekbar — attach the listener FIRST, then seed
        //    the progress value. That way the auto-sync in setStatus()
        //    has a working listener to bump tvProgressValue + the
        //    internal `progress` variable when status is "completed".
        seekProgress?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                progress = value
                tvProgressValue?.text = "$value%"
                if (fromUser) {
                    when {
                        value >= 100 -> setStatus("completed", fromUser = false)
                        value == 0 && status != "delayed" -> setStatus("not-started", fromUser = false)
                        value in 1..99 && status == "not-started" ->
                            setStatus("in-progress", fromUser = false)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        seekProgress?.progress = progress
        tvProgressValue?.text = "$progress%"

        // Apply initial status — auto-bumps progress to 100 if completed.
        setStatus(status, fromUser = false)

        cancelBtn?.setOnClickListener { dismissAllowingStateLoss() }
        submitBtn?.setOnClickListener { performUpdate() }
    }

    private fun showDatePicker() {
        // Use the already-picked date as the initial selection so re-opening
        // the picker doesn't reset to today.
        val initial = pickedDateIso.ifBlank {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        }
        setFragmentResultListener(RESULT_KEY_DATE) { _, bundle ->
            val date = bundle.getString(CalendarRangePickerSheet.KEY_FROM) ?: return@setFragmentResultListener
            pickedDateIso = date
            val display = runCatching {
                val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val fmt = SimpleDateFormat("MMM d, yyyy", Locale.US)
                parser.parse(date)?.let { fmt.format(it) }
            }.getOrNull() ?: date
            tvUpdateDate?.text = display
        }
        CalendarRangePickerSheet.newInstance(
            title = "Select date",
            subtitle = "Update date for this task",
            initialFrom = initial,
            initialTo = initial,
            resultKey = RESULT_KEY_DATE,
        ).showOnce(parentFragmentManager, "task_update_date")
    }

    private fun refreshPhotoStrip() {
        val strip = photoStrip ?: return
        val ctx = strip.context
        val density = ctx.resources.displayMetrics.density
        val side = (68 * density).toInt()
        val gap = (8 * density).toInt()
        val innerPad = (4 * density).toInt()
        // Wipe everything except the trailing "+" tile (last child).
        while (strip.childCount > 1) strip.removeViewAt(0)

        fun thumb(): FrameLayout {
            val cell = FrameLayout(ctx)
            val lp = LinearLayout.LayoutParams(side, side).apply { marginEnd = gap }
            cell.layoutParams = lp
            cell.background = ctx.getDrawable(R.drawable.bg_task_inner_card)
            cell.setPadding(innerPad, innerPad, innerPad, innerPad)
            return cell
        }

        existingPhotos.forEach { img ->
            val cell = thumb()
            val iv = ImageView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = null
            }
            cell.addView(iv)
            img.url?.let { iv.load(it) }
            strip.addView(cell, strip.childCount - 1)
        }
        pendingPhotos.forEach { uri ->
            val cell = thumb()
            val iv = ImageView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = null
                setImageURI(uri)
            }
            cell.addView(iv)
            strip.addView(cell, strip.childCount - 1)
        }
        tvPhotoCount?.text = run {
            val n = existingPhotos.size + pendingPhotos.size
            if (n == 1) "1 Photo" else "$n Photos"
        }
    }

    private fun setStatus(next: String, fromUser: Boolean) {
        status = next
        statusViews.forEach { (key, tv) ->
            val selected = key == next
            tv.setBackgroundResource(
                if (selected) R.drawable.bg_task_status_pill_active
                else R.drawable.bg_task_inner_card
            )
            tv.setTextColor(
                if (selected) Color.WHITE else Color.parseColor("#475467")
            )
        }
        // Auto-sync progress with status when the user explicitly picked
        // a pill. Don't snap on initial seed (`fromUser=false` from the
        // ctor path) unless the saved status is "completed", because we
        // want the slider to honour the persisted progress value first.
        when (next) {
            "completed" -> {
                if ((seekProgress?.progress ?: 0) != 100) {
                    seekProgress?.progress = 100
                }
            }
            "not-started" -> {
                if (fromUser && (seekProgress?.progress ?: 0) != 0) {
                    seekProgress?.progress = 0
                }
            }
            "in-progress" -> {
                if (fromUser && (seekProgress?.progress ?: 0) == 0) {
                    // Nudge to 10% so the slider visibly reflects the
                    // status change. Matches the Figma 80% mock-up
                    // pattern where progress is always non-zero for an
                    // in-progress task.
                    seekProgress?.progress = 10
                }
            }
            // "delayed" — keep current progress; delay is a flag, not
            // a progress reset.
        }
    }

    private fun performUpdate() {
        if (taskId.isBlank()) return
        submitBtn?.isEnabled = false
        errorText?.visibility = View.GONE

        val todayActual = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val updateText = etTodaysUpdate?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val blockerText = etBlocker?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val planText = etTomorrowsPlan?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 1) Upload newly-picked photos in parallel. Each upload
                //    returns a storageId we'll attach to the timeline
                //    entry. Existing photos already have storageIds.
                val uploaded: List<TaskUpdateImage> = if (pendingPhotos.isEmpty()) {
                    emptyList()
                } else {
                    val ctx = requireContext().applicationContext
                    val bearer = session.bearerToken
                    pendingPhotos.map { uri ->
                        async(Dispatchers.IO) { uploadPhoto(ctx, bearer, uri) }
                    }.awaitAll().filterNotNull()
                }
                val allImages = existingPhotos + uploaded

                // 2) Patch the task. Don't include `todaysUpdate` /
                //    `blocker` / `tomorrowsPlan` in the patch — those
                //    are per-day, they live on the timeline entry.
                val patchResp = api.updateTask(
                    session.bearerToken,
                    UpdateTaskRequest(
                        id = taskId,
                        status = status,
                        progress = progress,
                        actualStartDate = if (status == "in-progress" || status == "completed") todayActual else null,
                        actualEndDate = if (status == "completed") todayActual else null,
                    ),
                )
                if (!patchResp.success) {
                    submitBtn?.isEnabled = true
                    showError(patchResp.error ?: "Failed to update task")
                    return@launch
                }

                // 3) Append a timeline entry only when there's something
                //    to record (text or photos). Skipping when empty
                //    matches the web's behaviour of not littering the
                //    Time Line with blank rows.
                val hasTimelineContent =
                    updateText != null || blockerText != null ||
                    planText != null || allImages.isNotEmpty()
                if (hasTimelineContent) {
                    val timelineResp = api.addTaskUpdate(
                        session.bearerToken,
                        AddTaskUpdateRequest(
                            taskId = taskId,
                            date = pickedDateIso,
                            todaysUpdate = updateText,
                            blocker = blockerText,
                            tomorrowsPlan = planText,
                            progressSnapshot = progress,
                            images = allImages.takeIf { it.isNotEmpty() },
                        ),
                    )
                    if (!timelineResp.success) {
                        // The task patch already succeeded — surface
                        // the partial failure but still emit a result
                        // so the caller refreshes.
                        showError(
                            timelineResp.error
                                ?: "Saved task, but timeline entry failed"
                        )
                    }
                }

                setFragmentResult(RESULT_KEY, bundleOf(KEY_UPDATED to true))
                dismissAllowingStateLoss()
            } catch (e: Exception) {
                submitBtn?.isEnabled = true
                showError("Network error: ${e.message ?: "unknown"}")
            }
        }
    }

    /**
     * Copy the picked content URI to a temp file (Convex's storage
     * endpoint wants a real request body) and POST it. Returns null on
     * any failure — the caller filters those out so a single bad photo
     * doesn't break the whole submit.
     */
    private suspend fun uploadPhoto(
        ctx: Context,
        bearer: String,
        uri: Uri,
    ): TaskUpdateImage? = withContext(Dispatchers.IO) {
        try {
            val tmp = File.createTempFile("task_upd_", ".jpg", ctx.cacheDir)
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { out -> input.copyTo(out) }
            } ?: return@withContext null
            val mime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
            val body = tmp.asRequestBody(mime.toMediaTypeOrNull())
            val resp = api.uploadStorageFile(bearer, body)
            tmp.delete()
            val sid = resp.storageId ?: return@withContext null
            TaskUpdateImage(storageId = sid, url = null, name = tmp.name)
        } catch (_: Exception) {
            null
        }
    }

    private fun showError(message: String) {
        errorText?.text = message
        errorText?.visibility = View.VISIBLE
    }

    // Tiny ImageView.load extension so we don't pull in Coil/Glide for
    // a single thumbnail. Loads on a worker, hands back to the UI.
    private fun ImageView.load(url: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                val bytes = runCatching {
                    java.net.URL(url).openStream().use { it.readBytes() }
                }.getOrNull() ?: return@withContext null
                runCatching {
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
            }
            if (bmp != null) setImageBitmap(bmp)
        }
    }

    companion object {
        const val RESULT_KEY = "task_update_result"
        const val KEY_UPDATED = "updated"
        private const val RESULT_KEY_DATE = "task_update_date_calendar"
        private const val ARG_TASK_ID = "arg_task_id"
        private const val ARG_STATUS = "arg_status"
        private const val ARG_PROGRESS = "arg_progress"
        private const val ARG_TODAYS_UPDATE = "arg_todays_update"
        private const val ARG_BLOCKER = "arg_blocker"
        private const val ARG_TOMORROWS_PLAN = "arg_tomorrows_plan"
        private const val ARG_EXISTING_PHOTO_STORAGE_IDS = "arg_existing_photo_storage_ids"
        private const val ARG_EXISTING_PHOTO_URLS = "arg_existing_photo_urls"

        fun newInstance(
            taskId: String,
            currentStatus: String?,
            currentProgress: Int?,
            currentTodaysUpdate: String?,
            currentBlocker: String?,
            currentTomorrowsPlan: String?,
            existingPhotoStorageIds: List<String>? = null,
            existingPhotoUrls: List<String>? = null,
        ): TaskUpdateBottomSheet = TaskUpdateBottomSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_TASK_ID, taskId)
                if (currentStatus != null) putString(ARG_STATUS, currentStatus)
                putInt(ARG_PROGRESS, currentProgress ?: 0)
                if (currentTodaysUpdate != null) putString(ARG_TODAYS_UPDATE, currentTodaysUpdate)
                if (currentBlocker != null) putString(ARG_BLOCKER, currentBlocker)
                if (currentTomorrowsPlan != null) putString(ARG_TOMORROWS_PLAN, currentTomorrowsPlan)
                if (existingPhotoStorageIds != null) {
                    putStringArrayList(
                        ARG_EXISTING_PHOTO_STORAGE_IDS,
                        ArrayList(existingPhotoStorageIds),
                    )
                }
                if (existingPhotoUrls != null) {
                    putStringArrayList(
                        ARG_EXISTING_PHOTO_URLS,
                        ArrayList(existingPhotoUrls),
                    )
                }
            }
        }
    }
}
