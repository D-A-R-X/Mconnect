package com.manjugroups.m_connect.ui.library.collections

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
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
import com.manjugroups.m_connect.ui.common.SearchableOption
import com.manjugroups.m_connect.ui.common.SearchableSelectionDialog
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
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.setBackgroundResource(R.drawable.bg_bottom_sheet)
                androidx.core.view.ViewCompat.setElevation(it, 0f)
                val behavior = BottomSheetBehavior.from(it)
                val metrics = resources.displayMetrics
                val peekH = (metrics.heightPixels * 0.55f).toInt()
                behavior.peekHeight = peekH
                behavior.state = BottomSheetBehavior.STATE_COLLAPSED
                behavior.skipCollapsed = false
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
        val etAmount = view.findViewById<TextInputEditText>(R.id.etAmount)
        val etPaymentMode = view.findViewById<AutoCompleteTextView>(R.id.etPaymentMode)
        val etRefId = view.findViewById<TextInputEditText>(R.id.etRefId)
        val etNotes = view.findViewById<TextInputEditText>(R.id.etNotes)
        val btnSubmit = view.findViewById<MaterialButton>(R.id.btnSubmit)
        val btnCamera = view.findViewById<View>(R.id.btnCamera)
        val btnUploadImage = view.findViewById<View>(R.id.btnUploadImage)

        tvUploadTitle = view.findViewById(R.id.tvUploadTitle)
        tvUploadSubtitle = view.findViewById(R.id.tvUploadSubtitle)
        ivUploadIcon = view.findViewById(R.id.ivUploadIcon)

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
            // Wire the Select Booking field to the in-form lookup.
            // Both click and focus trigger it so the keyboard never
            // takes over (the field doesn't accept free text).
            etBooking.isFocusable = false
            etBooking.isCursorVisible = false
            etBooking.keyListener = null
            etBooking.setOnClickListener { promptBookingLookup() }
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
            dismissAllowingStateLoss()
        }
    }

    override fun onCancel(dialog: android.content.DialogInterface) {
        super.onCancel(dialog)
        setFragmentResult(RESULT_KEY, bundleOf(KEY_SUBMITTED to false))
    }

    // ── Booking lookup (inside the Select Booking field) ──────────────
    //
    // Field staff taps the Select Booking row → we prompt for the
    // customer's 10-digit mobile (the only routine identifier on the
    // exec's side) → /api/postsales/cases/byMobile returns the rows →
    // we render them in a SearchableSelectionDialog so the staff can
    // pick the right booking among the customer's multiple plots.

    private fun promptBookingLookup() {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            hint = "Customer mobile (10 digits)"
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(ctx)
            .setTitle("Find booking")
            .setMessage("Enter the customer's mobile number to load their bookings.")
            .setView(input)
            .setPositiveButton("Search") { dialog, _ ->
                val mobile = input.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                if (mobile == null) {
                    toast("Mobile number is required")
                } else {
                    fetchAndPickBooking(mobile)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun fetchAndPickBooking(mobile: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    api.getPostSaleCasesByMobile(session.bearerToken, mobile)
                }
                if (!resp.success || resp.cases.isEmpty()) {
                    toast(resp.error ?: "No bookings found for $mobile")
                    return@launch
                }
                showBookingPicker(resp.cases)
            } catch (e: Exception) {
                toast(e.message ?: "Lookup failed")
            }
        }
    }

    private fun showBookingPicker(cases: List<PostSaleCaseSummary>) {
        SearchableSelectionDialog.show(
            context = requireContext(),
            title = "Select Booking",
            options = cases.map { c ->
                SearchableOption(
                    item = c,
                    title = "${c.clientName} · ${c.bookingRefNo}",
                    subtitle = buildBookingSubtitle(c),
                    keywords = "${c.clientName} ${c.bookingRefNo} ${c.projectName.orEmpty()} ${c.plotNo.orEmpty()}",
                )
            },
            emptyMessage = "No bookings",
        ) { picked ->
            selectedCase = picked
            selectedCaseId = picked.id
            selectedCaseLabel = "${picked.clientName} · ${picked.bookingRefNo}"
            etBooking.setText(selectedCaseLabel)
        }
    }

    private fun buildBookingSubtitle(c: PostSaleCaseSummary): String {
        val location = listOfNotNull(
            c.projectName?.takeIf { it.isNotBlank() },
            c.plotNo?.takeIf { it.isNotBlank() }?.let { "Plot $it" },
        ).joinToString(" · ")
        val balance = "Balance ₹${"%,.0f".format(c.balanceAmount)}"
        return if (location.isNotBlank()) "$location · $balance" else balance
    }

    private fun showImageAttached(fileName: String) {
        tvUploadTitle.text = "Image Attached"
        tvUploadSubtitle.text = fileName
        ivUploadIcon.setImageResource(R.drawable.ic_check_circle)
        ivUploadIcon.imageTintList = android.content.res.ColorStateList.valueOf(
            Color.parseColor("#12B76A"),
        )
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
