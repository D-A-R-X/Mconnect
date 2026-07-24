package com.manjugroups.m_connect.ui.library.collections

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.PostSaleCaseSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Serializable

/**
 * "Collection Creations" sheet — the designer's form.
 *
 * Opens directly when the "+" is tapped on the Collections screen, no
 * intermediary prompt. The booking lookup is built into the Select
 * Booking field: tapping it asks the field staff for the customer's
 * mobile, hits /api/postsales/cases/byMobile, and renders the result
 * in a picker. Selection populates the field.
 *
 * In Rectify mode the rejected row already carries the case (caseId,
 * bookingRefNo, clientName, project + plot), so the Select Booking
 * field is pre-filled and locked — the field staff only edits what
 * Accounts flagged. Submit returns to the parent which handles the
 * proof upload and POST to /api/postsales/collections/submit.
 */
class CollectionCreateBottomSheet : BottomSheetDialogFragment() {

    private val paymentModes = listOf(
        ModeOption("upi", "UPI"),
        ModeOption("cash", "Cash"),
        ModeOption("neft", "NEFT"),
        ModeOption("rtgs", "RTGS"),
        ModeOption("cheque", "Cheque"),
        ModeOption("dd", "DD"),
        ModeOption("bank", "Bank Transfer"),
    )

    private data class ModeOption(val id: String, val label: String) : Serializable

    private val api = GeoTrackApi.create()
    private lateinit var session: SessionManager

    private var rectifyItem: CollectionItem? = null
    private var rectifyLocked: Boolean = false

    private var allBookings: List<PostSaleCaseSummary> = emptyList()
    private var selectedCase: PostSaleCaseSummary? = null
    private var selectedCaseId: String? = null
    private var selectedCaseLabel: String? = null
    private var selectedMode: ModeOption = paymentModes.first()

    private var cameraFile: File? = null
    private var cameraUri: Uri? = null
    private var selectedPhotoFile: File? = null
    private var selectedPhotoMime: String? = null

    private lateinit var tvUploadTitle: TextView
    private lateinit var tvUploadSubtitle: TextView
    private lateinit var ivUploadIcon: android.widget.ImageView
    private lateinit var etBooking: AutoCompleteTextView
    private lateinit var etAmount: TextInputEditText
    private lateinit var etPaymentMode: AutoCompleteTextView
    private lateinit var etRefId: TextInputEditText
    private lateinit var etNotes: TextInputEditText
    private lateinit var btnRemoveProof: TextView

    // Device-level draft (NOT keyed to the logged-in account) so a form
    // interrupted by a crash resumes on this device regardless of who is
    // signed in. Cleared on a successful submit.
    private val draftPrefs by lazy {
        requireContext().getSharedPreferences("collection_form_draft", android.content.Context.MODE_PRIVATE)
    }
    private val defaultUploadSubtitle =
        "To avoid reject, ensure Aadhaar photo size is max 2MB\n(Supports: JPG,PNG,PDF)"

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            val mime = requireContext().contentResolver.getType(uri) ?: "image/jpeg"
            val file = copyUriToTempFile(uri)
            if (file != null) {
                selectedPhotoFile = file
                selectedPhotoMime = mime
                showImageAttached(file.name)
            }
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
                selectedPhotoFile = f
                selectedPhotoMime = "image/jpeg"
                showImageAttached(f.name)
            }
        }
    }

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchCamera()
        else Toast.makeText(requireContext(), "Camera permission is required", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        // Same setup as CollectionPaymentEntryBottomSheet: expand fully
        // on show + skip the half-state. With a half/collapsed state the
        // outer drag and the inner NestedScrollView fight for the same
        // gesture and the form jitters; skipCollapsed=true makes the
        // sheet behave like a regular scrollable surface.
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.setBackgroundColor(Color.TRANSPARENT)
                androidx.core.view.ViewCompat.setElevation(it, 0f)
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.isDraggable = true
            }
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_collection_create, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        etBooking = view.findViewById(R.id.etBooking)
        etAmount = view.findViewById(R.id.etAmount)
        etPaymentMode = view.findViewById(R.id.etPaymentMode)
        etRefId = view.findViewById(R.id.etRefId)
        etNotes = view.findViewById(R.id.etNotes)
        val btnSubmit = view.findViewById<MaterialButton>(R.id.btnSubmit)
        val btnCamera = view.findViewById<View>(R.id.btnCamera)
        val btnUploadImage = view.findViewById<View>(R.id.btnUploadImage)

        tvUploadTitle = view.findViewById(R.id.tvUploadTitle)
        tvUploadSubtitle = view.findViewById(R.id.tvUploadSubtitle)
        ivUploadIcon = view.findViewById(R.id.ivUploadIcon)
        btnRemoveProof = view.findViewById(R.id.btnRemoveProof)
        btnRemoveProof.setOnClickListener { clearAttachedImage() }

        @Suppress("DEPRECATION")
        rectifyItem = arguments?.getSerializable(ARG_RECTIFY_ITEM) as? CollectionItem
        val rectifyCaseId = arguments?.getString(ARG_RECTIFY_CASE_ID)
        val rectifyLabel = arguments?.getString(ARG_RECTIFY_BOOKING_LABEL)

        // Rectify mode: the rejected row already carries its caseId +
        // booking caption, so we lock the booking field and only let
        // the user fix what Accounts flagged (amount / mode / ref /
        // notes / proof).
        val r = rectifyItem
        if (r != null && !rectifyCaseId.isNullOrBlank()) {
            rectifyLocked = true
            selectedCaseId = rectifyCaseId
            selectedCaseLabel = rectifyLabel ?: r.bookingName
            etBooking.setText(selectedCaseLabel)
            etBooking.isEnabled = false
            etBooking.isFocusable = false

            etAmount.setText(r.amount.toString())
            paymentModes.firstOrNull { it.label.equals(r.paymentMode, ignoreCase = true) }?.let {
                selectedMode = it
                etPaymentMode.setText(it.label)
            }
            etRefId.setText(r.refId)
            etNotes.setText(r.notes)
        }

        if (!rectifyLocked) {
            // Non-editable Select Booking field — drops down inline
            // (AutoCompleteTextView's native dropdown) with the list
            // of open bookings loaded from /api/postsales/cases/list.
            // Items map back to PostSaleCaseSummary by index so the
            // submit still uses the picked caseId.
            etBooking.isFocusable = false
            etBooking.isCursorVisible = false
            etBooking.keyListener = null
            // Our searchable picker (with a search bar) instead of the native
            // AutoCompleteTextView dropdown — each row shows the client and the
            // project, and the search matches name / number / project / plot.
            etBooking.setOnClickListener { openBookingPicker() }
            loadOpenBookings()
        }

        // Payment mode dropdown — keep on a static list of the seven
        // server-recognised modes.
        etPaymentMode.setAdapter(
            ArrayAdapter(
                requireContext(),
                android.R.layout.simple_list_item_1,
                paymentModes.map { it.label },
            ),
        )
        etPaymentMode.setOnClickListener { etPaymentMode.showDropDown() }
        etPaymentMode.setOnItemClickListener { _, _, position, _ ->
            paymentModes.getOrNull(position)?.let {
                selectedMode = it
                etPaymentMode.setText(it.label)
                if (!rectifyLocked) saveDraft()
            }
        }

        btnCamera.setOnClickListener {
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
        btnUploadImage.setOnClickListener { galleryLauncher.launch("image/*") }

        // Restore an interrupted draft (device-level), then save on every edit
        // so a crash mid-fill can be resumed. Skipped in rectify mode, which
        // pre-fills from the rejected row instead.
        if (!rectifyLocked) {
            restoreDraft()
            val watcher = object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) = saveDraft()
            }
            etAmount.addTextChangedListener(watcher)
            etRefId.addTextChangedListener(watcher)
            etNotes.addTextChangedListener(watcher)
            etPaymentMode.addTextChangedListener(watcher)
        }

        btnSubmit.setOnClickListener {
            val caseId = selectedCaseId
            if (caseId.isNullOrBlank()) {
                toast("Select a booking")
                return@setOnClickListener
            }
            val amount = etAmount.text?.toString()?.trim()?.toDoubleOrNull()
            if (amount == null || amount <= 0) {
                toast("Enter a valid amount (greater than zero)")
                return@setOnClickListener
            }
            val typedMode = etPaymentMode.text?.toString()?.trim().orEmpty()
            val resolvedMode = paymentModes.firstOrNull { it.label.equals(typedMode, ignoreCase = true) }
                ?: paymentModes.firstOrNull { it.id.equals(typedMode, ignoreCase = true) }
            if (resolvedMode == null) {
                toast("Select a payment mode")
                return@setOnClickListener
            }
            selectedMode = resolvedMode
            val refId = etRefId.text?.toString()?.trim().orEmpty()
            if (refId.isBlank()) {
                toast("Transaction reference is required")
                return@setOnClickListener
            }
            val notes = etNotes.text?.toString()?.trim().orEmpty()

            setFragmentResult(
                RESULT_KEY,
                bundleOf(
                    KEY_SUBMITTED to true,
                    KEY_CASE_ID to caseId,
                    KEY_BOOKING_REF to (selectedCase?.bookingRefNo ?: ""),
                    KEY_CLIENT_NAME to (selectedCase?.clientName ?: ""),
                    KEY_AMOUNT to amount,
                    KEY_PAYMENT_MODE to selectedMode.id,
                    KEY_PAYMENT_MODE_LABEL to selectedMode.label,
                    KEY_TRANSACTION_REF to refId,
                    KEY_NOTES to notes,
                    KEY_PROOF_LOCAL_PATH to (selectedPhotoFile?.absolutePath ?: ""),
                    KEY_PROOF_FILE_NAME to (selectedPhotoFile?.name ?: ""),
                    KEY_PROOF_MIME to (selectedPhotoMime ?: ""),
                ),
            )
            if (!rectifyLocked) clearDraft()
            dismissAllowingStateLoss()
        }
    }

    override fun onCancel(dialog: android.content.DialogInterface) {
        super.onCancel(dialog)
        setFragmentResult(RESULT_KEY, bundleOf(KEY_SUBMITTED to false))
    }

    // ── Booking dropdown ──────────────────────────────────────────────
    //
    // The full list of open bookings is loaded once when the sheet
    // opens (/api/postsales/cases/list, capped at 200 newest first)
    // and fed into the Select Booking AutoCompleteTextView so it
    // drops down inline under the field — matches the designer's
    // intent. Tap → list shows under the field; pick → field text
    // updates and selectedCaseId locks in the right caseId.

    private fun loadOpenBookings(openAfter: Boolean = false) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    api.listOpenBookings(session.bearerToken)
                }
                if (!isAdded) return@launch
                if (!resp.success) {
                    toast(resp.error ?: "Could not load bookings")
                    return@launch
                }
                allBookings = resp.cases
                // Reconnect a restored draft's booking to its full record so the
                // submitted result carries the ref/client name, not just the id.
                if (selectedCase == null && !selectedCaseId.isNullOrBlank()) {
                    allBookings.firstOrNull { it.id == selectedCaseId }?.let { selectedCase = it }
                }
                if (openAfter) openBookingPicker()
            } catch (e: Exception) {
                toast(e.message ?: "Could not load bookings")
            }
        }
    }

    /**
     * Searchable booking picker (our SearchableSelectionDialog). Each row leads
     * with the client — name, or their number when the name is blank — and shows
     * the project (and plot) beneath, so field staff can find a booking by
     * whichever they remember. Search matches name / number / project / plot / ref.
     */
    private fun openBookingPicker() {
        if (allBookings.isEmpty()) {
            toast("Loading bookings…")
            loadOpenBookings(openAfter = true)
            return
        }
        val options = allBookings.map { c ->
            com.manjugroups.m_connect.ui.common.SearchableOption(
                item = c,
                title = bookingClientLabel(c),
                subtitle = bookingProjectLabel(c).takeIf { it.isNotBlank() },
                keywords = listOf(
                    c.clientName, c.mobileNumber,
                    c.projectName.orEmpty(), c.plotNo.orEmpty(), c.bookingRefNo,
                ).joinToString(" "),
            )
        }
        com.manjugroups.m_connect.ui.common.SearchableSelectionDialog.show(
            context = requireContext(),
            title = "Select Booking",
            options = options,
            emptyMessage = "No open bookings",
        ) { picked ->
            selectedCase = picked
            selectedCaseId = picked.id
            selectedCaseLabel = bookingSelectedLabel(picked)
            etBooking.setText(selectedCaseLabel)
            if (!rectifyLocked) saveDraft()
        }
    }

    /** Client identity for a booking row — name, else number, else ref. */
    private fun bookingClientLabel(c: PostSaleCaseSummary): String =
        c.clientName.trim().ifBlank { c.mobileNumber.trim() }.ifBlank { c.bookingRefNo }

    /** Project (+ plot) for a booking row; blank when neither is set. */
    private fun bookingProjectLabel(c: PostSaleCaseSummary): String {
        val project = c.projectName.orEmpty().trim()
        val plot = c.plotNo.orEmpty().trim()
        return when {
            project.isNotBlank() && plot.isNotBlank() -> "$project - Plot $plot"
            project.isNotBlank() -> project
            plot.isNotBlank() -> "Plot $plot"
            else -> ""
        }
    }

    /** What fills the field once a booking is picked: client + project. */
    private fun bookingSelectedLabel(c: PostSaleCaseSummary): String {
        val client = bookingClientLabel(c)
        val project = bookingProjectLabel(c)
        return if (project.isNotBlank()) "$client · $project" else client
    }

    private fun showImageAttached(fileName: String) {
        tvUploadTitle.text = "Image Attached"
        tvUploadSubtitle.text = fileName
        ivUploadIcon.setImageResource(R.drawable.ic_check_circle)
        ivUploadIcon.imageTintList = android.content.res.ColorStateList.valueOf(
            Color.parseColor("#12B76A"),
        )
        btnRemoveProof.visibility = View.VISIBLE
        if (!rectifyLocked) saveDraft()
    }

    /** Clear the attached proof and reset the upload card to its empty state. */
    private fun clearAttachedImage() {
        // Drop the temp we copied in; the caller's original (gallery uri) is
        // untouched, and the camera temp lives in our own cache dir.
        selectedPhotoFile?.let { f -> runCatching { if (f.exists()) f.delete() } }
        selectedPhotoFile = null
        selectedPhotoMime = null
        cameraFile = null
        tvUploadTitle.text = "Upload Image"
        tvUploadSubtitle.text = defaultUploadSubtitle
        ivUploadIcon.setImageResource(R.drawable.ic_upload_cloud)
        ivUploadIcon.imageTintList = null
        btnRemoveProof.visibility = View.GONE
        if (!rectifyLocked) saveDraft()
    }

    // ── Device-level form draft (crash resume) ────────────────────────
    private fun saveDraft() {
        draftPrefs.edit()
            .putString("caseId", selectedCaseId)
            .putString("caseLabel", selectedCaseLabel)
            .putString("amount", etAmount.text?.toString())
            .putString("modeId", selectedMode.id)
            .putString("refId", etRefId.text?.toString())
            .putString("notes", etNotes.text?.toString())
            .putString("proofPath", selectedPhotoFile?.absolutePath)
            .putString("proofName", selectedPhotoFile?.name)
            .putString("proofMime", selectedPhotoMime)
            .apply()
    }

    private fun clearDraft() {
        draftPrefs.edit().clear().apply()
    }

    private fun restoreDraft() {
        // Nothing meaningful saved yet → leave the fresh form alone.
        val caseId = draftPrefs.getString("caseId", null)
        val amount = draftPrefs.getString("amount", null)
        val refId = draftPrefs.getString("refId", null)
        val notes = draftPrefs.getString("notes", null)
        val hasData = !caseId.isNullOrBlank() || !amount.isNullOrBlank() ||
            !refId.isNullOrBlank() || !notes.isNullOrBlank()
        if (!hasData) return

        if (!caseId.isNullOrBlank()) {
            selectedCaseId = caseId
            selectedCaseLabel = draftPrefs.getString("caseLabel", null)
            etBooking.setText(selectedCaseLabel.orEmpty())
        }
        if (!amount.isNullOrBlank()) etAmount.setText(amount)
        draftPrefs.getString("modeId", null)?.let { id ->
            paymentModes.firstOrNull { it.id == id }?.let {
                selectedMode = it
                etPaymentMode.setText(it.label)
            }
        }
        if (!refId.isNullOrBlank()) etRefId.setText(refId)
        if (!notes.isNullOrBlank()) etNotes.setText(notes)

        // Restore the proof only if the temp file is still on disk.
        val proofPath = draftPrefs.getString("proofPath", null)
        if (!proofPath.isNullOrBlank()) {
            val f = File(proofPath)
            if (f.exists() && f.length() > 0) {
                selectedPhotoFile = f
                selectedPhotoMime = draftPrefs.getString("proofMime", null) ?: "image/jpeg"
                showImageAttached(draftPrefs.getString("proofName", null) ?: f.name)
            }
        }
    }

    private fun launchCamera() {
        val f = createTempPhotoFile("collection_cam_") ?: run {
            toast("Unable to create photo file")
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
            toast("Unable to open camera")
            return
        }
        cameraUri = uri
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            clipData = ClipData.newUri(requireContext().contentResolver, "Collection", uri)
        }
        try {
            cameraLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            toast("No camera app available")
        }
    }

    private fun createTempPhotoFile(prefix: String): File? = try {
        val dir = File(requireContext().cacheDir, "collections").apply { if (!exists()) mkdirs() }
        File.createTempFile(prefix, ".jpg", dir)
    } catch (_: Exception) {
        null
    }

    private fun copyUriToTempFile(uri: Uri): File? = try {
        val f = createTempPhotoFile("collection_pick_")
        if (f != null) {
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                f.outputStream().use { output -> input.copyTo(output) }
            }
        }
        f?.takeIf { it.length() > 0 }
    } catch (_: Exception) {
        null
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val RESULT_KEY = "CollectionCreated"

        const val KEY_SUBMITTED = "submitted"
        const val KEY_CASE_ID = "caseId"
        const val KEY_BOOKING_REF = "bookingRef"
        const val KEY_CLIENT_NAME = "clientName"
        const val KEY_AMOUNT = "amount"
        const val KEY_PAYMENT_MODE = "paymentMode"
        const val KEY_PAYMENT_MODE_LABEL = "paymentModeLabel"
        const val KEY_TRANSACTION_REF = "transactionRef"
        const val KEY_NOTES = "notes"
        const val KEY_PROOF_LOCAL_PATH = "proofLocalPath"
        const val KEY_PROOF_FILE_NAME = "proofFileName"
        const val KEY_PROOF_MIME = "proofMime"

        private const val ARG_RECTIFY_ITEM = "arg_rectify_item"
        private const val ARG_RECTIFY_CASE_ID = "arg_rectify_case_id"
        private const val ARG_RECTIFY_BOOKING_LABEL = "arg_rectify_booking_label"

        /** Fresh collection — the booking is picked inside the sheet. */
        fun newInstance(): CollectionCreateBottomSheet = CollectionCreateBottomSheet()

        /**
         * Rectify a previously-rejected collection. The original row
         * carries the caseId so the booking field is locked; the
         * label is the same booking caption the list row displays.
         */
        fun forRectify(
            item: CollectionItem,
            caseId: String,
            bookingLabel: String,
        ): CollectionCreateBottomSheet = CollectionCreateBottomSheet().apply {
            arguments = Bundle().apply {
                putSerializable(ARG_RECTIFY_ITEM, item)
                putString(ARG_RECTIFY_CASE_ID, caseId)
                putString(ARG_RECTIFY_BOOKING_LABEL, bookingLabel)
            }
        }
    }
}
