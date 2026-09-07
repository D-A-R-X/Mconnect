package com.manjugroups.m_connect.ui.library.loans

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
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
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.DeleteLoanDocumentRequest
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.LoanCaseDocument
import com.manjugroups.m_connect.util.ScanPdfBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Loan Desk document upload sheet.
 *
 * Pipeline (upload-on-pick):
 *   1. User scans or picks a file — slot flips to UPLOADING
 *      with a spinner. Submit is gated off until every UPLOADING slot
 *      resolves, so the user can't dispatch a half-uploaded payload.
 *   2. We copy the URI to the app cache off-main and POST to the Loan Desk
 *      upload endpoint immediately. On success we persist
 *      `{label, fileName, storageId}` to SharedPreferences keyed by
 *      caseId so an accidental dismiss doesn't lose work — reopening
 *      the sheet for the same case restores the slots in READY state.
 *   3. Submit only fires once every required slot is READY or has a
 *      pre-existing server-side storageId. The fragment receives a
 *      ready-to-send `List<SubmittedDoc>` (storageIds already
 *      uploaded) so the submitLoanRequest call is a single round trip.
 *
 * In view-mode (Legal Manager / Legal Team / Sales post-assignment)
 * the upload affordances are hidden; each slot renders as a clickable
 * file pill that previews the uploaded file.
 */
class LoanDeskUploadBottomSheet : BottomSheetDialogFragment() {

    /** One successfully uploaded document, ready for /loans/submit. */
    data class SubmittedDoc(
        val label: String,
        val storageId: String,
        val fileName: String,
    )

    private enum class SlotUiState { IDLE, UPLOADING, READY, FAILED }

    private data class DocSlotState(
        val index: Int,
        val label: String,
        val row: View,
        val btnCamera: View,
        val layoutUnuploaded: View,
        val layoutUploading: View,
        val layoutFailed: View,
        val layoutUploaded: View,
        val tvUploadedName: TextView,
        val tvUploadingLabel: TextView,
        val tvFailedLabel: TextView,
        val btnRetryUpload: TextView,
        val btnDeleteUploaded: View,
        // Server-side state — already uploaded and approved at some
        // point. Lets the slot pre-fill and preview from /api/storage
        // without re-picking.
        var preExistingFileName: String? = null,
        var preExistingStorageId: String? = null,
        // Live state — the freshly picked file in this session.
        var state: SlotUiState = SlotUiState.IDLE,
        var fileName: String? = null,
        var storageId: String? = null,
        var localFilePath: String? = null,
        var uploadRequestId: String? = null,
        var lastError: String? = null,
        var uploadJob: Job? = null,
    )

    private val slots = mutableListOf<DocSlotState>()
    private var activeSlotIndex = -1
    private var cameraFile: File? = null
    private var cameraUri: Uri? = null
    private var isViewMode = false
    private var caseId: String? = null
    /** postSaleLoanCases id — when present, each successful slot upload is
     *  also persisted server-side via /api/postsales/loans/upload-document. */
    private var loanCaseId: String? = null
    /** Camera-scanned pages accumulated for the active slot; assembled into a
     *  single PDF on "Finish scan". */
    private val scanPages = mutableListOf<File>()

    private lateinit var btnSubmit: TextView
    private val gson = Gson()

    private val documentScanner by lazy {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(MAX_SCAN_PAGES)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options)
    }

    private var onSubmitted: ((uploads: List<SubmittedDoc>) -> Unit)? = null
    private var onDocumentChanged: ((label: String, document: LoanCaseDocument?, loanCase: com.manjugroups.m_connect.network.LoanCaseRow?) -> Unit)? = null

    // ── File picker / camera launchers (shared across all slots) ──

    private val documentScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { activityResult ->
        if (activityResult.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val result = GmsDocumentScanningResult.fromActivityResultIntent(activityResult.data)
        val pdf = result?.pdf
        if (pdf == null || pdf.pageCount <= 0) {
            Toast.makeText(requireContext(), "No scanned pages were returned", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        startUploadFromUri(
            slotIndex = activeSlotIndex,
            uri = pdf.uri,
            preferredFileName = scanPdfName(slots.getOrNull(activeSlotIndex)?.label.orEmpty()),
        )
    }

    private val filePickerLauncher = registerForActivityResult(
        object : ActivityResultContracts.GetContent() {
            override fun createIntent(context: Context, input: String): Intent {
                val intent = super.createIntent(context, input)
                intent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf"))
                return intent
            }
        },
    ) { uri ->
        if (uri != null) {
            startUploadFromUri(activeSlotIndex, uri)
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
                // Scanned page: accumulate, then ask add-another vs finish —
                // all pages of one scan become a SINGLE PDF for the slot.
                scanPages += f
                promptScanNextPage()
            }
        } else if (scanPages.isNotEmpty()) {
            // Camera cancelled mid-scan: finish with the pages we have.
            promptScanNextPage()
        }
    }

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchCamera()
        else Toast.makeText(requireContext(), "Camera permission is required", Toast.LENGTH_SHORT).show()
    }

    fun setOnSubmittedListener(listener: (uploads: List<SubmittedDoc>) -> Unit) {
        this.onSubmitted = listener
    }

    fun setOnDocumentChangedListener(
        listener: (label: String, document: LoanCaseDocument?, loanCase: com.manjugroups.m_connect.network.LoanCaseRow?) -> Unit,
    ) {
        onDocumentChanged = listener
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
        caseId = arguments?.getString(ARG_CASE_ID)
        loanCaseId = arguments?.getString(ARG_LOAN_CASE_ID)
        val requiredLabels = arguments?.getStringArrayList(ARG_REQUIRED_LABELS).orEmpty()
        val preExistingNames = arguments?.getStringArrayList(ARG_PRE_NAMES).orEmpty()
        val preExistingStorageIds = arguments?.getStringArrayList(ARG_PRE_STORAGE_IDS).orEmpty()

        btnSubmit = view.findViewById(R.id.btnSubmitUploads)
        val docsContainer = view.findViewById<LinearLayout>(R.id.docsContainer)

        if (requiredLabels.isEmpty()) {
            view.findViewById<TextView>(R.id.tvUploadSheetSubtitle)?.text =
                "No documents are configured for this loan case."
            btnSubmit.isEnabled = false
            btnSubmit.alpha = 0.5f
            return
        }

        val title = view.findViewById<TextView>(R.id.tvUploadSheetTitle)
        val subtitle = view.findViewById<TextView>(R.id.tvUploadSheetSubtitle)
        val allAlreadyUploaded = !isViewMode && requiredLabels.indices.all { i ->
            !preExistingNames.getOrNull(i).isNullOrBlank()
        }
        when {
            isViewMode -> {
                title?.text = "Review Documents"
                subtitle?.text = "Tap a row to view the uploaded file"
                btnSubmit.text = "Close"
                btnSubmit.isEnabled = true
                btnSubmit.alpha = 1.0f
            }
            allAlreadyUploaded -> {
                title?.text = "Review Documents"
                subtitle?.text = "Tap to preview · long-press a row to replace"
            }
            else -> {
                title?.text = "Submit Documents"
                subtitle?.text = "Upload the ${requiredLabels.size} required files below to proceed"
            }
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
            val layoutUploading = row.findViewById<View>(R.id.layoutUploading)
            val layoutFailed = row.findViewById<View>(R.id.layoutFailed)
            val layoutUploaded = row.findViewById<View>(R.id.layoutUploaded)
            val tvUploadedName = row.findViewById<TextView>(R.id.tvUploadedName)
            val tvUploadingLabel = row.findViewById<TextView>(R.id.tvUploadingLabel)
            val tvFailedLabel = row.findViewById<TextView>(R.id.tvFailedLabel)
            val btnRetryUpload = row.findViewById<TextView>(R.id.btnRetryUpload)
            val btnDeleteUploaded = row.findViewById<View>(R.id.btnDeleteUploaded)

            tvDocTitle.text = "$label *"
            tvUnuploadedTitle.text = "Upload $label"
            tvUnuploadedSubtitle.text = "Max 20 MB · Scan, JPG, PNG or PDF"

            val preExisting = preExistingNames.getOrNull(index)?.takeIf { it.isNotBlank() }
            val preExistingStorageId =
                preExistingStorageIds.getOrNull(index)?.takeIf { it.isNotBlank() }

            val slot = DocSlotState(
                index = index,
                label = label,
                row = row,
                btnCamera = btnCamera,
                layoutUnuploaded = layoutUnuploaded,
                layoutUploading = layoutUploading,
                layoutFailed = layoutFailed,
                layoutUploaded = layoutUploaded,
                tvUploadedName = tvUploadedName,
                tvUploadingLabel = tvUploadingLabel,
                tvFailedLabel = tvFailedLabel,
                btnRetryUpload = btnRetryUpload,
                btnDeleteUploaded = btnDeleteUploaded,
                preExistingFileName = preExisting,
                preExistingStorageId = preExistingStorageId,
            )
            slots += slot

            if (preExisting != null) {
                tvUploadedName.text = preExisting
                renderSlot(slot)
            }

            layoutUploaded.setOnClickListener {
                previewSlot(slot)
            }
            btnDeleteUploaded.visibility = if (isViewMode) View.GONE else View.VISIBLE
            if (isViewMode) {
                btnCamera.visibility = View.GONE
            } else {
                layoutUnuploaded.setOnClickListener {
                    activeSlotIndex = index
                    filePickerLauncher.launch("*/*")
                }
                btnCamera.setOnClickListener {
                    activeSlotIndex = index
                    launchDocumentScanner()
                }
                btnDeleteUploaded.setOnClickListener { confirmDeleteSlot(slot) }
                btnRetryUpload.setOnClickListener {
                    retrySlotUpload(slot)
                }
            }

            docsContainer.addView(row)
        }

        // Restore any draft uploads persisted from a prior session of
        // this same case. Skip in view-mode and skip for slots that
        // already have a pre-existing server-side upload (the live
        // server state always wins over a stale draft).
        if (!isViewMode) restoreDraft()

        if (!isViewMode) {
            updateSubmitButtonState()
            btnSubmit.setOnClickListener {
                val ready = readyUploadsForSubmit()
                if (ready.isEmpty()) {
                    // Sales reopened an already-complete case and
                    // didn't long-press anything; the button is in
                    // its "Close" form, just dismiss.
                    dismiss()
                    return@setOnClickListener
                }
                onSubmitted?.invoke(ready)
                dismiss()
            }
        } else {
            btnSubmit.setOnClickListener { dismiss() }
        }
    }

    // ── Per-slot render ──

    private fun renderSlot(slot: DocSlotState) {
        // Pick what to show based on the current effective state.
        // Order of preference for "uploaded" rendering:
        //   1) fresh upload completed in this session → fileName/storageId
        //   2) pre-existing server-side upload → preExistingFileName
        val effectiveFileName = slot.fileName ?: slot.preExistingFileName
        when (slot.state) {
            SlotUiState.UPLOADING -> {
                slot.layoutUnuploaded.visibility = View.GONE
                slot.layoutUploading.visibility = View.VISIBLE
                slot.layoutFailed.visibility = View.GONE
                slot.layoutUploaded.visibility = View.GONE
                slot.btnCamera.isEnabled = false
                slot.btnCamera.alpha = 0.4f
            }
            SlotUiState.FAILED -> {
                slot.layoutUnuploaded.visibility = View.GONE
                slot.layoutUploading.visibility = View.GONE
                slot.layoutFailed.visibility = View.VISIBLE
                slot.layoutUploaded.visibility = View.GONE
                slot.tvFailedLabel.text = slot.lastError ?: "Upload failed"
                slot.btnCamera.isEnabled = true
                slot.btnCamera.alpha = 1.0f
            }
            SlotUiState.READY -> {
                slot.tvUploadedName.text = effectiveFileName ?: slot.label
                slot.layoutUnuploaded.visibility = View.GONE
                slot.layoutUploading.visibility = View.GONE
                slot.layoutFailed.visibility = View.GONE
                slot.layoutUploaded.visibility = View.VISIBLE
                slot.btnCamera.isEnabled = true
                slot.btnCamera.alpha = 1.0f
            }
            SlotUiState.IDLE -> {
                if (effectiveFileName != null) {
                    // Pre-existing server upload but no fresh pick yet.
                    slot.tvUploadedName.text = effectiveFileName
                    slot.layoutUnuploaded.visibility = View.GONE
                    slot.layoutUploading.visibility = View.GONE
                    slot.layoutFailed.visibility = View.GONE
                    slot.layoutUploaded.visibility = View.VISIBLE
                } else {
                    slot.layoutUnuploaded.visibility = View.VISIBLE
                    slot.layoutUploading.visibility = View.GONE
                    slot.layoutFailed.visibility = View.GONE
                    slot.layoutUploaded.visibility = View.GONE
                }
                slot.btnCamera.isEnabled = true
                slot.btnCamera.alpha = 1.0f
            }
        }
    }

    private fun clearSlot(slot: DocSlotState) {
        slot.uploadJob?.cancel()
        slot.uploadJob = null
        slot.state = SlotUiState.IDLE
        slot.fileName = null
        slot.storageId = null
        slot.localFilePath?.let { runCatching { File(it).delete() } }
        slot.localFilePath = null
        slot.uploadRequestId = null
        slot.lastError = null
        renderSlot(slot)
        persistDraft()
        updateSubmitButtonState()
    }

    private fun retrySlotUpload(slot: DocSlotState) {
        val file = slot.localFilePath?.let(::File)
        if (file == null || !file.exists() || file.length() <= 0L) {
            clearSlot(slot)
            Toast.makeText(
                requireContext(),
                "The local copy is unavailable. Please scan the document again.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        slot.tvUploadingLabel.text = "Uploading..."
        slot.state = SlotUiState.UPLOADING
        slot.lastError = null
        renderSlot(slot)
        updateSubmitButtonState()
        slot.uploadJob = viewLifecycleOwner.lifecycleScope.launch {
            performUpload(slot, file)
        }
    }

    private fun confirmDeleteSlot(slot: DocSlotState) {
        val storageId = slot.storageId ?: slot.preExistingStorageId
        if (storageId.isNullOrBlank()) {
            clearSlot(slot)
            return
        }
        val currentLoanCaseId = loanCaseId
        if (currentLoanCaseId.isNullOrBlank()) {
            Toast.makeText(
                requireContext(),
                "This document is not attached to a loan case yet.",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete document?")
            .setMessage("${slot.label} will be removed from this loan case.")
            .setNegativeButton("Keep", null)
            .setPositiveButton("Delete") { _, _ ->
                deleteSlot(slot, currentLoanCaseId, storageId)
            }
            .show()
    }

    private fun deleteSlot(slot: DocSlotState, currentLoanCaseId: String, storageId: String) {
        val previousState = slot.state
        slot.tvUploadingLabel.text = "Deleting..."
        slot.state = SlotUiState.UPLOADING
        renderSlot(slot)
        updateSubmitButtonState()
        slot.uploadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    GeoTrackApi.create().deleteLoanDocument(
                        SessionManager(requireContext()).bearerToken,
                        DeleteLoanDocumentRequest(
                            loanCaseId = currentLoanCaseId,
                            label = slot.label,
                            storageId = storageId,
                        ),
                    )
                }
                if (!response.success) {
                    throw IllegalStateException(response.error ?: "Document could not be deleted")
                }
                slot.localFilePath?.let { runCatching { File(it).delete() } }
                slot.preExistingFileName = null
                slot.preExistingStorageId = null
                slot.fileName = null
                slot.storageId = null
                slot.localFilePath = null
                slot.uploadRequestId = null
                slot.lastError = null
                slot.uploadJob = null
                slot.state = SlotUiState.IDLE
                renderSlot(slot)
                persistDraft()
                updateSubmitButtonState()
                onDocumentChanged?.invoke(slot.label, null, response.loanCase)
                Toast.makeText(requireContext(), "Document deleted", Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                slot.uploadJob = null
                slot.state = previousState
                slot.tvUploadingLabel.text = "Uploading..."
                renderSlot(slot)
                updateSubmitButtonState()
                val message = if (error is retrofit2.HttpException) {
                    LoanErrorParser.friendlyMessage(error)
                } else {
                    error.message ?: "Delete failed. Check your network and retry."
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Upload pipeline ──

    private fun startUploadFromUri(
        slotIndex: Int,
        uri: Uri,
        preferredFileName: String? = null,
    ) {
        val slot = slots.getOrNull(slotIndex) ?: return
        val displayName = safeFileName(
            preferredFileName ?: getFileName(uri) ?: "document_${slotIndex + 1}.pdf",
        )
        slot.fileName = displayName
        slot.uploadRequestId = UUID.randomUUID().toString()
        slot.tvUploadingLabel.text = "Uploading..."
        slot.state = SlotUiState.UPLOADING
        slot.lastError = null
        renderSlot(slot)
        updateSubmitButtonState()
        slot.uploadJob = viewLifecycleOwner.lifecycleScope.launch {
            val cached = withContext(Dispatchers.IO) { copyUriToCache(uri, displayName) }
            if (cached == null) {
                onSlotUploadFailed(slot, "Couldn't read the picked file")
                return@launch
            }
            performUpload(slot, cached)
        }
    }

    /** After each captured page: keep scanning, or finish into one PDF. */
    private fun promptScanNextPage() {
        if (!isAdded) return
        val slot = slots.getOrNull(activeSlotIndex)
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Page ${scanPages.size} captured")
            .setMessage(
                "Scan another page of ${slot?.label ?: "this document"}, " +
                    "or finish and upload everything as a single PDF.",
            )
            .setPositiveButton("Add page") { _, _ -> launchCamera() }
            .setNegativeButton("Finish scan") { _, _ -> finishScanAndUpload() }
            .setCancelable(false)
            .show()
    }

    /** Assemble the accumulated pages into one PDF and run the normal
     *  slot-upload pipeline with it. */
    private fun finishScanAndUpload() {
        val slotIndex = activeSlotIndex
        val slot = slots.getOrNull(slotIndex) ?: return
        val pages = scanPages.toList()
        scanPages.clear()
        if (pages.isEmpty()) return
        slot.state = SlotUiState.UPLOADING
        slot.tvUploadingLabel.text = "Uploading..."
        slot.fileName = scanPdfName(slot.label)
        slot.uploadRequestId = UUID.randomUUID().toString()
        renderSlot(slot)
        updateSubmitButtonState()
        viewLifecycleOwner.lifecycleScope.launch {
            val pdf = withContext(Dispatchers.IO) {
                val out = File(requireContext().cacheDir, scanPdfName(slot.label))
                ScanPdfBuilder.build(pages, out)
            }
            pages.forEach { runCatching { it.delete() } }
            if (pdf == null) {
                onSlotUploadFailed(slot, "Couldn't build the scan PDF")
                return@launch
            }
            startUploadFromCameraFile(slotIndex, pdf)
        }
    }

    private fun scanPdfName(label: String): String {
        val safeLabel = label.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "document" }
        val caseRef = (loanCaseId ?: caseId).orEmpty()
            .replace(Regex("[^A-Za-z0-9]+"), "")
            .takeLast(10)
            .lowercase(Locale.US)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return listOf("loan", safeLabel, caseRef, timestamp)
            .filter { it.isNotBlank() }
            .joinToString("_") + ".pdf"
    }

    private fun startUploadFromCameraFile(slotIndex: Int, file: File) {
        val slot = slots.getOrNull(slotIndex) ?: return
        slot.fileName = file.name
        slot.uploadRequestId = UUID.randomUUID().toString()
        slot.state = SlotUiState.UPLOADING
        slot.tvUploadingLabel.text = "Uploading..."
        slot.lastError = null
        renderSlot(slot)
        updateSubmitButtonState()
        slot.uploadJob = viewLifecycleOwner.lifecycleScope.launch {
            performUpload(slot, file)
        }
    }

    private suspend fun performUpload(slot: DocSlotState, file: File) {
        slot.localFilePath = file.absolutePath
        if (!file.exists() || file.length() <= 0L) {
            onSlotUploadFailed(slot, "The selected document is empty")
            return
        }
        if (file.length() > MAX_DOCUMENT_BYTES) {
            onSlotUploadFailed(slot, "Document must be 20 MB or smaller")
            return
        }
        val mime = when (file.extension.lowercase(Locale.US)) {
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "jpg", "jpeg" -> "image/jpeg"
            else -> {
                onSlotUploadFailed(slot, "Only PDF and image documents are supported")
                return
            }
        }
        val token = SessionManager(requireContext()).bearerToken
        val requestId = slot.uploadRequestId
            ?: UUID.randomUUID().toString().also { slot.uploadRequestId = it }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val textType = "text/plain".toMediaType()
                val filePart = MultipartBody.Part.createFormData(
                    "file",
                    file.name,
                    file.asRequestBody(mime.toMediaType()),
                )
                GeoTrackApi.create().uploadLoanDocumentFile(
                    token = token,
                    loanCaseId = loanCaseId
                        ?.takeIf { it.isNotBlank() }
                        ?.toRequestBody(textType),
                    label = slot.label.toRequestBody(textType),
                    file = filePart,
                    fileName = file.name.toRequestBody(textType),
                    requestId = requestId.toRequestBody(textType),
                )
            }
        }
        result.onSuccess { resp ->
            val sid = resp.document?.storageId
            if (resp.success && !sid.isNullOrBlank()) {
                slot.uploadJob = null
                slot.storageId = sid
                slot.fileName = resp.document.fileName.ifBlank { file.name }
                slot.state = SlotUiState.READY
                slot.lastError = null
                renderSlot(slot)
                persistDraft()
                updateSubmitButtonState()
                onDocumentChanged?.invoke(
                    slot.label,
                    LoanCaseDocument(
                        label = slot.label,
                        storageId = sid,
                        fileName = slot.fileName,
                    ),
                    resp.loanCase,
                )
            } else {
                onSlotUploadFailed(slot, resp.error ?: "Upload rejected by server")
            }
        }
        result.onFailure { err ->
            val message = if (err is retrofit2.HttpException) {
                LoanErrorParser.friendlyMessage(err)
            } else {
                err.message ?: "Network error"
            }
            onSlotUploadFailed(slot, message)
        }
    }

    private fun onSlotUploadFailed(slot: DocSlotState, message: String) {
        slot.uploadJob = null
        slot.state = SlotUiState.FAILED
        slot.lastError = message
        slot.storageId = null
        renderSlot(slot)
        persistDraft()
        updateSubmitButtonState()
        if (isAdded) {
            Toast.makeText(requireContext(), "${slot.label}: $message", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateSubmitButtonState() {
        if (isViewMode) return
        val anyUploading = slots.any { it.state == SlotUiState.UPLOADING }
        val allCovered = slots.all {
            it.state == SlotUiState.READY || it.preExistingStorageId != null
        }
        val anyFreshReady = slots.any { it.state == SlotUiState.READY }

        btnSubmit.text = when {
            anyUploading -> "Uploading…"
            anyFreshReady -> "Submit"
            else -> "Close"
        }
        val enabled = !anyUploading && allCovered
        btnSubmit.isEnabled = enabled
        btnSubmit.alpha = if (enabled) 1.0f else 0.5f
    }

    /**
     * Final payload for /loans/submit: fresh uploads always win;
     * pre-existing ones aren't included since the server-side state
     * is already authoritative for those.
     */
    private fun readyUploadsForSubmit(): List<SubmittedDoc> {
        return slots.mapNotNull { s ->
            val sid = s.storageId ?: return@mapNotNull null
            val name = s.fileName ?: return@mapNotNull null
            if (s.state != SlotUiState.READY) return@mapNotNull null
            SubmittedDoc(label = s.label, storageId = sid, fileName = name)
        }
    }

    // ── Draft persistence ──

    private fun draftPrefs(): android.content.SharedPreferences? {
        val ctx = context ?: return null
        return ctx.getSharedPreferences(DRAFT_PREFS, Context.MODE_PRIVATE)
    }

    private fun draftKey(): String? = caseId?.takeIf { it.isNotBlank() }?.let { "case_$it" }

    private fun persistDraft() {
        val prefs = draftPrefs() ?: return
        val key = draftKey() ?: return
        val payload = slots
            .filter { it.state == SlotUiState.READY || it.state == SlotUiState.FAILED }
            .mapNotNull { s ->
                val name = s.fileName ?: return@mapNotNull null
                DraftEntry(
                    label = s.label,
                    fileName = name,
                    storageId = s.storageId,
                    localFilePath = s.localFilePath,
                    uploadRequestId = s.uploadRequestId,
                    state = s.state.name,
                )
            }
        prefs.edit().putString(key, gson.toJson(payload)).apply()
    }

    private fun restoreDraft() {
        val prefs = draftPrefs() ?: return
        val key = draftKey() ?: return
        val json = prefs.getString(key, null) ?: return
        val type = object : TypeToken<List<DraftEntry>>() {}.type
        val entries: List<DraftEntry> = runCatching { gson.fromJson<List<DraftEntry>>(json, type) }
            .getOrNull()
            ?: emptyList()
        val byLabel = entries.associateBy { it.label.lowercase(Locale.US) }
        slots.forEach { slot ->
            // Don't trample a pre-existing server upload with a stale
            // draft — the server is always more authoritative.
            if (slot.preExistingStorageId != null) return@forEach
            val match = byLabel[slot.label.lowercase(Locale.US)] ?: return@forEach
            slot.fileName = match.fileName
            slot.storageId = match.storageId
            slot.localFilePath = match.localFilePath
            slot.uploadRequestId = match.uploadRequestId
            slot.state = if (!match.storageId.isNullOrBlank()) {
                SlotUiState.READY
            } else if (match.localFilePath?.let(::File)?.exists() == true) {
                slot.lastError = "Upload interrupted. Tap Retry."
                SlotUiState.FAILED
            } else {
                return@forEach
            }
            slot.tvUploadedName.text = match.fileName
            renderSlot(slot)
        }
        updateSubmitButtonState()
    }

    private data class DraftEntry(
        val label: String,
        val fileName: String? = null,
        val storageId: String? = null,
        val localFilePath: String? = null,
        val uploadRequestId: String? = null,
        val state: String? = null,
    )

    // ── Camera ──

    private fun launchDocumentScanner() {
        val host = activity ?: return
        documentScanner.getStartScanIntent(host)
            .addOnSuccessListener { intentSender ->
                if (!isAdded) return@addOnSuccessListener
                documentScannerLauncher.launch(
                    IntentSenderRequest.Builder(intentSender).build(),
                )
            }
            .addOnFailureListener {
                if (!isAdded) return@addOnFailureListener
                Toast.makeText(
                    requireContext(),
                    "Document scanner unavailable. Opening the camera instead.",
                    Toast.LENGTH_LONG,
                ).show()
                scanPages.clear()
                checkCameraPermissionAndLaunch()
            }
    }

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

    private fun copyUriToCache(uri: Uri, displayName: String): File? = try {
        val cr = requireContext().contentResolver
        val input = cr.openInputStream(uri) ?: return null
        val folder = File(requireContext().cacheDir, "loandesk").apply { if (!exists()) mkdirs() }
        val cacheFile = File(folder, safeFileName(displayName))
        input.use { source ->
            cacheFile.outputStream().use { out -> source.copyTo(out) }
        }
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

    private fun safeFileName(value: String): String {
        val leaf = value.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = leaf.replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_', '.')
            .take(180)
        return cleaned.ifBlank {
            "loan_document_${System.currentTimeMillis()}.pdf"
        }
    }

    /**
     * Three-way preview source:
     *   1. Freshly picked file from this session → cacheDir hit, Coil
     *      loads it from disk.
     *   2. Pre-existing upload on the server with `storageId` →
     *      resolve to a signed URL via /api/storage/get-url. Same path
     *      is used for fresh uploads whose storageId came back from
     *      the live upload pipeline.
     *   3. Neither → "Preview unavailable" toast (defensive).
     */
    private fun previewSlot(slot: DocSlotState) {
        val freshName = slot.fileName
        if (freshName != null) {
            val folder = File(requireContext().cacheDir, "loandesk")
            val file = slot.localFilePath?.let(::File) ?: File(folder, freshName)
            if (file.exists() && file.length() > 0) {
                if (file.extension.equals("pdf", ignoreCase = true)) {
                    val uri = FileProvider.getUriForFile(
                        requireContext(),
                        "${requireContext().packageName}.fileprovider",
                        file,
                    )
                    openDocument(uri, "application/pdf", grantRead = true)
                    return
                }
                showPreviewDialog { imageView, progressBar ->
                    progressBar.visibility = View.VISIBLE
                    imageView.load(file) {
                        crossfade(true)
                        listener(
                            onSuccess = { _, _ ->
                                progressBar.visibility = View.GONE
                                (imageView as? com.manjugroups.m_connect.ui.chat.ZoomableImageView)
                                    ?.requestRecenter()
                            },
                            onError = { _, _ -> progressBar.visibility = View.GONE },
                        )
                    }
                }
                return
            }
        }
        val storageId = slot.storageId ?: slot.preExistingStorageId
        if (storageId.isNullOrBlank()) {
            Toast.makeText(
                requireContext(),
                "Preview unavailable",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val effectiveName = slot.fileName ?: slot.preExistingFileName
        if (effectiveName?.endsWith(".pdf", ignoreCase = true) == true) {
            openRemotePdf(storageId)
            return
        }
        showPreviewDialog { imageView, progressBar ->
            // Two async waits between tap → image-on-screen: the
            // signed-URL fetch and the bitmap decode. Showing the
            // spinner across BOTH (instead of just the load step) is
            // what removes the "did I tap wrong?" beat where the
            // dialog opened over a black void.
            imageView.setImageDrawable(null)
            progressBar.visibility = View.VISIBLE
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val token = SessionManager(requireContext()).bearerToken
                    val resp = withContext(Dispatchers.IO) {
                        ApiService.create().getStorageUrl(token, storageId)
                    }
                    val url = resp.url
                    if (resp.success && !url.isNullOrBlank()) {
                        imageView.load(url) {
                            crossfade(true)
                            listener(
                                onSuccess = { _, _ ->
                                    progressBar.visibility = View.GONE
                                    (imageView as? com.manjugroups.m_connect.ui.chat.ZoomableImageView)
                                        ?.requestRecenter()
                                },
                                onError = { _, _ ->
                                    progressBar.visibility = View.GONE
                                    Toast.makeText(
                                        requireContext(),
                                        "Couldn't load preview",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                        }
                    } else {
                        progressBar.visibility = View.GONE
                        Toast.makeText(
                            requireContext(),
                            "Couldn't load preview",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                } catch (_: Exception) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(
                        requireContext(),
                        "Couldn't load preview",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    private fun openRemotePdf(storageId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    ApiService.create().getStorageUrl(
                        SessionManager(requireContext()).bearerToken,
                        storageId,
                    )
                }
                val url = response.url
                if (!response.success || url.isNullOrBlank()) {
                    Toast.makeText(requireContext(), "Couldn't open PDF", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                openDocument(Uri.parse(url), "application/pdf", grantRead = false)
            } catch (_: Exception) {
                Toast.makeText(requireContext(), "Couldn't open PDF", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openDocument(uri: Uri, mime: String, grantRead: Boolean) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            if (grantRead) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }.onFailure {
                Toast.makeText(requireContext(), "No PDF viewer is installed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showPreviewDialog(bind: (imageView: ImageView, progressBar: View) -> Unit) {
        // Use PopupWindow rather than AlertDialog — the chat preview
        // does the same and it's the only thing that reliably fills
        // the screen. AlertDialog (even with the Fullscreen theme +
        // explicit window.setLayout MATCH_PARENT) keeps collapsing
        // when the ZoomableImageView's intrinsic size starts at 0×0,
        // bunching the spinner + chrome controls at the top.
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.popup_image_preview, null)
        val imageView = view.findViewById<ImageView>(R.id.ivPreview)
        val progressBar = view.findViewById<View>(R.id.pbPreviewLoading)
        val closeBtn = view.findViewById<View>(R.id.btnPreviewClose)
        val btnBack = view.findViewById<View>(R.id.btnBack)
        bind(imageView, progressBar)
        val popup = android.widget.PopupWindow(
            view,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            true,
        )
        closeBtn?.setOnClickListener { popup.dismiss() }
        btnBack?.setOnClickListener { popup.dismiss() }
        // Anchor on THIS sheet's own dialog decor view, not the
        // activity's — the BottomSheetDialogFragment runs in its own
        // window that sits above the activity, so anchoring on the
        // activity decor would leave the popup z-stacked below the
        // sheet (image renders BEHIND the form). Sharing a window
        // with the sheet keeps the popup on top.
        val anchor = dialog?.window?.decorView
            ?: activity?.window?.decorView
            ?: view
        popup.showAtLocation(anchor, android.view.Gravity.CENTER, 0, 0)
    }

    companion object {
        private const val ARG_REQUIRED_LABELS = "requiredLabels"
        private const val ARG_PRE_NAMES = "preExistingNames"
        private const val ARG_PRE_STORAGE_IDS = "preExistingStorageIds"
        private const val ARG_CASE_ID = "caseId"
        private const val ARG_LOAN_CASE_ID = "loanCaseId"
        private const val DRAFT_PREFS = "loan_desk_drafts"
        private const val MAX_SCAN_PAGES = 20
        private const val MAX_DOCUMENT_BYTES = 20L * 1024L * 1024L

        fun clearRecoveryDraft(context: Context, caseId: String?) {
            val key = caseId?.takeIf { it.isNotBlank() }?.let { "case_$it" } ?: return
            context.getSharedPreferences(DRAFT_PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(key)
                .apply()
        }

        /**
         * @param caseId The postSaleCase id this sheet is editing —
         *   used as the persistence key so an accidental dismiss
         *   doesn't lose in-progress uploads. Required when not in
         *   view-mode; pass empty for view-only opens.
         * @param requiredLabels The doc labels to render (one row per
         *   entry). Drive this from the case's documentsChecklist so
         *   the slot count matches Post-sales Settings.
         * @param preExistingFileNames Aligned with `requiredLabels`.
         *   When non-null, the slot starts in the uploaded state so
         *   the operator sees what the server already has.
         * @param preExistingStorageIds Aligned with `requiredLabels`,
         *   same convention as `preExistingFileNames`. Carries the
         *   Convex `_storage` id so the preview can resolve it to a
         *   signed URL via /api/storage/get-url + Coil.
         */
        fun newInstance(
            caseId: String,
            requiredLabels: List<String>,
            preExistingFileNames: List<String?> = emptyList(),
            preExistingStorageIds: List<String?> = emptyList(),
            isViewMode: Boolean,
            /** postSaleLoanCases id — pass it so each slot upload also persists
             *  server-side immediately via /loans/upload-document. */
            loanCaseId: String? = null,
            onSubmitted: (uploads: List<SubmittedDoc>) -> Unit,
        ): LoanDeskUploadBottomSheet = LoanDeskUploadBottomSheet().apply {
            arguments = Bundle().apply {
                putBoolean("isViewMode", isViewMode)
                putString(ARG_CASE_ID, caseId)
                putString(ARG_LOAN_CASE_ID, loanCaseId)
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
