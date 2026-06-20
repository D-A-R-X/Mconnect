package com.manjugroups.m_connect.ui.library.loans

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import java.io.File

/**
 * Loan Desk document upload sheet — renders one row per required
 * document, dynamically driven by the case's documentsChecklist on
 * the server. The previous hardcoded 4-slot grid only worked for
 * Salaried applicants and silently ignored Business / Pension docs
 * configured under Post-sales Settings; this sheet matches whatever
 * the BDO checklist on the web shows for the same case.
 *
 * In view-mode (Legal Manager / Legal Team / Sales-rejected review)
 * each already-uploaded doc renders as a clickable file pill; the
 * camera + dashed drop-zone are hidden so the sheet can't accept new
 * uploads.
 *
 * The submit callback receives a map of `label → cached fileName`
 * (only labels that have a freshly picked file land in the map). The
 * parent fragment iterates that map to upload + post, so the server
 * sees real labels like "Business Proof / GST Certificate" instead
 * of the generic "Document 3".
 */
class LoanDeskUploadBottomSheet : BottomSheetDialogFragment() {

    private data class DocSlotState(
        val label: String,
        val row: View,
        val btnCamera: View,
        val layoutUnuploaded: View,
        val layoutUploaded: View,
        val tvUploadedName: TextView,
        var fileName: String? = null,
        // Already approved/pending review on the server side. Renders
        // as locked-uploaded; uploads still allowed (re-submit path)
        // but the slot pre-populates with the existing fileName.
        val preExistingFileName: String? = null,
        // Convex `_storage` id for the pre-existing upload. Lets the
        // preview fall through to /api/storage/get-url + Coil instead
        // of looking for a local file the device never had.
        val preExistingStorageId: String? = null,
    )

    private val slots = mutableListOf<DocSlotState>()
    private var activeSlotIndex = -1
    private var cameraFile: File? = null
    private var cameraUri: Uri? = null
    private var isViewMode = false

    private lateinit var btnSubmit: TextView

    private var onSubmitted: ((uploads: Map<String, String>) -> Unit)? = null

    // ── File picker / camera launchers (shared across all slots) ──

    private val filePickerLauncher = registerForActivityResult(
        object : ActivityResultContracts.GetContent() {
            override fun createIntent(context: android.content.Context, input: String): Intent {
                val intent = super.createIntent(context, input)
                intent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf"))
                return intent
            }
        },
    ) { uri ->
        if (uri != null) {
            val copied = copyUriToCache(uri)
            val name = copied?.name ?: getFileName(uri) ?: "document_${activeSlotIndex + 1}.pdf"
            onFileUploaded(activeSlotIndex, name)
        }
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        cameraUri?.let { uri ->
            runCatching {
                requireContext().revokeUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        if (result.resultCode == Activity.RESULT_OK) {
            val f = cameraFile
            if (f != null && f.exists() && f.length() > 0) {
                onFileUploaded(activeSlotIndex, f.name)
            }
        }
    }

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchCamera()
        else Toast.makeText(requireContext(), "Camera permission is required", Toast.LENGTH_SHORT).show()
    }

    fun setOnSubmittedListener(listener: (uploads: Map<String, String>) -> Unit) {
        this.onSubmitted = listener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.setOnShowListener { dialogInterface ->
            val bottomSheetDialog = dialogInterface as BottomSheetDialog
            val bottomSheet = bottomSheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                it.setBackgroundResource(R.drawable.bg_auth_sheet)
                androidx.core.view.ViewCompat.setElevation(it, 0f)
                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(it)
                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = inflater.inflate(R.layout.bottom_sheet_loan_desk_upload, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        isViewMode = arguments?.getBoolean("isViewMode", false) ?: false
        val requiredLabels = arguments?.getStringArrayList(ARG_REQUIRED_LABELS).orEmpty()
        val preExistingNames = arguments?.getStringArrayList(ARG_PRE_NAMES).orEmpty()
        val preExistingStorageIds = arguments?.getStringArrayList(ARG_PRE_STORAGE_IDS).orEmpty()

        btnSubmit = view.findViewById(R.id.btnSubmitUploads)
        val docsContainer = view.findViewById<LinearLayout>(R.id.docsContainer)

        if (requiredLabels.isEmpty()) {
            // Fail-soft: show a placeholder row + lock the submit button
            // so the operator knows the case has no configured docs
            // (probably an applicantType mismatch on the server).
            view.findViewById<TextView>(R.id.tvUploadSheetSubtitle)?.text =
                "No documents are configured for this loan case."
            btnSubmit.isEnabled = false
            btnSubmit.alpha = 0.5f
            return
        }

        val title = view.findViewById<TextView>(R.id.tvUploadSheetTitle)
        val subtitle = view.findViewById<TextView>(R.id.tvUploadSheetSubtitle)
        if (isViewMode) {
            title?.text = "Review Documents"
            subtitle?.text = "Tap a row to view the uploaded file"
            btnSubmit.text = "Close"
            btnSubmit.isEnabled = true
            btnSubmit.alpha = 1.0f
        } else {
            title?.text = "Submit Documents"
            subtitle?.text = "Upload the ${requiredLabels.size} required files below to proceed"
        }

        // Inflate one row per required label.
        val inflater = LayoutInflater.from(requireContext())
        requiredLabels.forEachIndexed { index, label ->
            val row = inflater.inflate(R.layout.item_loan_upload_doc, docsContainer, false)
            val tvDocTitle = row.findViewById<TextView>(R.id.tvDocTitle)
            val tvUnuploadedTitle = row.findViewById<TextView>(R.id.tvUnuploadedTitle)
            val tvUnuploadedSubtitle = row.findViewById<TextView>(R.id.tvUnuploadedSubtitle)
            val btnCamera = row.findViewById<View>(R.id.btnCamera)
            val layoutUnuploaded = row.findViewById<View>(R.id.layoutUnuploaded)
            val layoutUploaded = row.findViewById<View>(R.id.layoutUploaded)
            val tvUploadedName = row.findViewById<TextView>(R.id.tvUploadedName)

            tvDocTitle.text = "$label *"
            tvUnuploadedTitle.text = "Upload $label"
            tvUnuploadedSubtitle.text = "Max 2MB · JPG, PNG, PDF"

            val preExisting = preExistingNames.getOrNull(index)?.takeIf { it.isNotBlank() }
            val preExistingStorageId =
                preExistingStorageIds.getOrNull(index)?.takeIf { it.isNotBlank() }

            val slot = DocSlotState(
                label = label,
                row = row,
                btnCamera = btnCamera,
                layoutUnuploaded = layoutUnuploaded,
                layoutUploaded = layoutUploaded,
                tvUploadedName = tvUploadedName,
                preExistingFileName = preExisting,
                preExistingStorageId = preExistingStorageId,
            )
            slots += slot

            // Reflect any pre-existing upload immediately so the row
            // doesn't look empty for docs already approved by BDO /
            // pending review on the server.
            if (preExisting != null) {
                tvUploadedName.text = preExisting
                layoutUnuploaded.visibility = View.GONE
                layoutUploaded.visibility = View.VISIBLE
            }

            // Tap on the uploaded row = preview the file (whether the
            // file lives in the local cache from a fresh upload OR
            // came from Convex storage via the case's storageId).
            // Re-picking is done through the Camera button, or by
            // long-pressing the row to clear back to the dashed
            // dropzone — this way the default tap is non-destructive.
            layoutUploaded.setOnClickListener {
                previewSlot(slot)
            }
            if (isViewMode) {
                btnCamera.visibility = View.GONE
            } else {
                layoutUnuploaded.setOnClickListener {
                    activeSlotIndex = index
                    filePickerLauncher.launch("*/*")
                }
                btnCamera.setOnClickListener {
                    activeSlotIndex = index
                    checkCameraPermissionAndLaunch()
                }
                layoutUploaded.setOnLongClickListener {
                    // Long-press to clear + re-pick. Pre-existing
                    // server-side files clear back to the dashed
                    // state too — server already has them, but the
                    // user wants to replace.
                    slot.fileName = null
                    layoutUploaded.visibility = View.GONE
                    layoutUnuploaded.visibility = View.VISIBLE
                    updateSubmitButtonState()
                    true
                }
            }

            docsContainer.addView(row)
        }

        if (!isViewMode) {
            updateSubmitButtonState()
            btnSubmit.setOnClickListener {
                val uploads = slots.mapNotNull { s ->
                    s.fileName?.let { s.label to it }
                }.toMap()
                if (uploads.isEmpty()) return@setOnClickListener
                Toast.makeText(requireContext(), "Documents submitted", Toast.LENGTH_SHORT).show()
                onSubmitted?.invoke(uploads)
                dismiss()
            }
        } else {
            btnSubmit.setOnClickListener { dismiss() }
        }
    }

    // ── Upload state plumbing ──

    private fun onFileUploaded(slotIndex: Int, name: String) {
        val slot = slots.getOrNull(slotIndex) ?: return
        slot.fileName = name
        slot.tvUploadedName.text = name
        slot.layoutUnuploaded.visibility = View.GONE
        slot.layoutUploaded.visibility = View.VISIBLE
        updateSubmitButtonState()
    }

    private fun updateSubmitButtonState() {
        if (isViewMode) return
        // Submit is enabled when every required slot has EITHER a new
        // file or a pre-existing server-side file. Pre-existing
        // entries cover the rectify path — when Legal rejects, only
        // the flagged docs need re-uploading; the still-approved ones
        // can be re-sent unchanged.
        val allCovered = slots.all { it.fileName != null || it.preExistingFileName != null }
        btnSubmit.isEnabled = allCovered
        btnSubmit.alpha = if (allCovered) 1.0f else 0.5f
    }

    // ── Camera ──

    private fun checkCameraPermissionAndLaunch() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            launchCamera()
        } else {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        val f = createTempPhotoFile("loandesk_cam_") ?: run {
            Toast.makeText(requireContext(), "Unable to create photo file", Toast.LENGTH_SHORT).show()
            return
        }
        cameraFile = f
        val uri = runCatching {
            FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                f,
            )
        }.getOrElse {
            Toast.makeText(requireContext(), "Unable to open camera", Toast.LENGTH_SHORT).show()
            return
        }
        cameraUri = uri
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            clipData = ClipData.newUri(requireContext().contentResolver, "LoanDesk", uri)
        }
        try {
            cameraLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(requireContext(), "No camera app available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createTempPhotoFile(prefix: String): File? = try {
        val dir = File(requireContext().cacheDir, "loandesk").apply { if (!exists()) mkdirs() }
        File.createTempFile(prefix, ".jpg", dir)
    } catch (_: Exception) {
        null
    }

    private fun copyUriToCache(uri: Uri): File? = try {
        val cr = requireContext().contentResolver
        val input = cr.openInputStream(uri) ?: return null
        val displayName = getFileName(uri) ?: "temp_file_${System.currentTimeMillis()}"
        val folder = File(requireContext().cacheDir, "loandesk").apply { if (!exists()) mkdirs() }
        val cacheFile = File(folder, displayName)
        cacheFile.outputStream().use { out -> input.copyTo(out) }
        cacheFile
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) name = it.getString(index)
            }
        }
        return name
    }

    /**
     * Three-way preview source:
     *   1. Freshly picked file from this session → live in the local
     *      `cacheDir/loandesk/` folder, Coil-load straight from disk.
     *   2. Pre-existing upload on the server with `storageId` →
     *      resolve to a signed URL via /api/storage/get-url and Coil
     *      streams the image / PDF page from Convex.
     *   3. Neither → placeholder so the dialog doesn't appear empty.
     */
    private fun previewSlot(slot: DocSlotState) {
        // Prefer the local cache hit (a fresh upload always wins over
        // its pre-existing predecessor — the user just picked it).
        val freshName = slot.fileName
        if (freshName != null) {
            val folder = File(requireContext().cacheDir, "loandesk")
            val file = File(folder, freshName)
            if (file.exists() && file.length() > 0) {
                showPreviewDialog { imageView ->
                    imageView.load(file) {
                        crossfade(true)
                        listener(
                            onSuccess = { _, _ ->
                                (imageView as? com.manjugroups.m_connect.ui.chat.ZoomableImageView)
                                    ?.requestRecenter()
                            },
                        )
                    }
                }
                return
            }
        }
        val storageId = slot.preExistingStorageId
        if (storageId.isNullOrBlank()) {
            // Nothing to preview — neither a local file from this
            // session nor a server-side storageId. Toast instead of
            // opening an empty dialog so the operator gets a clear
            // signal (rather than the previous cash-receipt drawable,
            // which looked like a generic mock placeholder).
            Toast.makeText(
                requireContext(),
                "Preview unavailable",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        // Async URL fetch. Open the dialog with a neutral loading
        // state (no drawable — the fullscreen black theme is the
        // backdrop). Coil's crossfade brings the image in cleanly when
        // the signed URL resolves.
        showPreviewDialog { imageView ->
            imageView.setImageDrawable(null)
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val token = com.manjugroups.m_connect.auth.SessionManager(
                        requireContext(),
                    ).bearerToken
                    val resp = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.manjugroups.m_connect.network.ApiService
                            .create()
                            .getStorageUrl(token, storageId)
                    }
                    val url = resp.url
                    if (resp.success && !url.isNullOrBlank()) {
                        imageView.load(url) {
                            crossfade(true)
                            listener(
                                onSuccess = { _, _ ->
                                    // Coil's CrossfadeDrawable starts
                                    // at 0×0 so the ZoomableImageView
                                    // matrix initially treats this as
                                    // "drawable not ready" and the
                                    // image sticks at identity matrix
                                    // (small + top-left for landscape
                                    // sources). Force a recenter now
                                    // that the real bitmap size is
                                    // available.
                                    (imageView as? com.manjugroups.m_connect.ui.chat.ZoomableImageView)
                                        ?.requestRecenter()
                                },
                            )
                        }
                    } else {
                        Toast.makeText(
                            requireContext(),
                            "Couldn't load preview",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                } catch (_: Exception) {
                    Toast.makeText(
                        requireContext(),
                        "Couldn't load preview",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    private fun showPreviewDialog(bind: (ImageView) -> Unit) {
        val builder = AlertDialog.Builder(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.popup_image_preview, null)
        val imageView = view.findViewById<ImageView>(R.id.ivPreview)
        val closeBtn = view.findViewById<View>(R.id.btnPreviewClose)
        val btnBack = view.findViewById<View>(R.id.btnBack)
        bind(imageView)
        val dialog = builder.setView(view).create()
        closeBtn?.setOnClickListener { dialog.dismiss() }
        btnBack?.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    companion object {
        private const val ARG_REQUIRED_LABELS = "requiredLabels"
        private const val ARG_PRE_NAMES = "preExistingNames"
        private const val ARG_PRE_STORAGE_IDS = "preExistingStorageIds"

        /**
         * @param requiredLabels The doc labels to render (one row per
         *   entry). Drive this from the case's documentsChecklist so
         *   the slot count matches Post-sales Settings.
         * @param preExistingFileNames Aligned with `requiredLabels`
         *   (same length, "" / null for rows without a pre-existing
         *   upload). When non-null, the slot starts in the uploaded
         *   state so the operator sees what the server already has.
         * @param preExistingStorageIds Aligned with `requiredLabels`,
         *   same convention as `preExistingFileNames`. Carries the
         *   Convex `_storage` id so the preview can resolve it to a
         *   signed URL via /api/storage/get-url + Coil — without it,
         *   previewing a server-stored doc that was never on this
         *   device falls back to the placeholder.
         */
        fun newInstance(
            requiredLabels: List<String>,
            preExistingFileNames: List<String?> = emptyList(),
            preExistingStorageIds: List<String?> = emptyList(),
            isViewMode: Boolean,
            onSubmitted: (uploads: Map<String, String>) -> Unit,
        ): LoanDeskUploadBottomSheet = LoanDeskUploadBottomSheet().apply {
            arguments = Bundle().apply {
                putBoolean("isViewMode", isViewMode)
                putStringArrayList(ARG_REQUIRED_LABELS, ArrayList(requiredLabels))
                putStringArrayList(
                    ARG_PRE_NAMES,
                    ArrayList(requiredLabels.indices.map { preExistingFileNames.getOrNull(it).orEmpty() }),
                )
                putStringArrayList(
                    ARG_PRE_STORAGE_IDS,
                    ArrayList(requiredLabels.indices.map { preExistingStorageIds.getOrNull(it).orEmpty() }),
                )
            }
            setOnSubmittedListener(onSubmitted)
        }
    }
}
