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
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.network.PostSaleCaseSummary
import java.io.File
import java.io.Serializable

/**
 * "Add Collection" sheet. The parent passes in a list of bookings
 * already resolved via /api/postsales/cases/byMobile, so this sheet is
 * UI-only — it never hits the network itself. Submit emits a
 * FragmentResult bundle carrying caseId + the form fields + the proof
 * file's local path; the parent uploads the proof to Convex storage
 * and posts /api/postsales/collections/submit on success.
 *
 * Rectify mode reuses the same form pre-populated from the rejected
 * row's previous values. The server records each submission as a new
 * customerCollections row, so the audit trail (rejected → re-submitted
 * → approved) stays intact.
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
    private data class BookingDisplay(val case: PostSaleCaseSummary, val label: String)

    private var cases: List<PostSaleCaseSummary> = emptyList()
    private var bookingDisplays: List<BookingDisplay> = emptyList()
    private var selectedCase: PostSaleCaseSummary? = null
    private var selectedMode: ModeOption = paymentModes.first()

    private var cameraFile: File? = null
    private var cameraUri: Uri? = null
    private var selectedPhotoFile: File? = null
    private var selectedPhotoMime: String? = null

    private lateinit var tvUploadTitle: TextView
    private lateinit var tvUploadSubtitle: TextView
    private lateinit var ivUploadIcon: android.widget.ImageView

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

        val etBooking = view.findViewById<AutoCompleteTextView>(R.id.etBooking)
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

        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        val argCases = arguments?.getSerializable(ARG_CASES) as? ArrayList<PostSaleCaseSummary>
        @Suppress("DEPRECATION")
        val rectifyItem = arguments?.getSerializable(ARG_RECTIFY_ITEM) as? CollectionItem

        cases = argCases.orEmpty()
        bookingDisplays = cases.map { c ->
            BookingDisplay(
                case = c,
                label = formatBookingLabel(c),
            )
        }

        if (cases.size == 1) {
            selectedCase = cases.first()
            etBooking.setText(bookingDisplays.first().label)
        }

        // Prefill from the rejected row if we're in Rectify mode. The
        // amount / mode / reference / notes carry over directly; the
        // proof has to be re-attached because we don't redownload the
        // previous storage object onto the device.
        if (rectifyItem != null) {
            etAmount.setText(rectifyItem.amount.toString())
            etPaymentMode.setText(rectifyItem.paymentMode)
            paymentModes.firstOrNull { it.label.equals(rectifyItem.paymentMode, ignoreCase = true) }?.let {
                selectedMode = it
            }
            etRefId.setText(rectifyItem.refId)
            etNotes.setText(rectifyItem.notes)
            // Best-effort booking pre-select: match on the booking ref
            // surface in the rectifyItem.bookingName if any of the
            // freshly-loaded cases line up.
            val match = bookingDisplays.firstOrNull { display ->
                rectifyItem.bookingName.contains(display.case.bookingRefNo, ignoreCase = true) ||
                    display.case.clientName.contains(rectifyItem.bookingName, ignoreCase = true)
            }
            if (match != null) {
                selectedCase = match.case
                etBooking.setText(match.label)
            }
        }

        etBooking.setAdapter(
            ArrayAdapter(
                requireContext(),
                android.R.layout.simple_list_item_1,
                bookingDisplays.map { it.label },
            ),
        )
        etBooking.setOnClickListener { etBooking.showDropDown() }
        etBooking.setOnItemClickListener { _, _, position, _ ->
            bookingDisplays.getOrNull(position)?.let { picked ->
                selectedCase = picked.case
                etBooking.setText(picked.label)
            }
        }

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

        btnUploadImage.setOnClickListener {
            galleryLauncher.launch("image/*")
        }

        btnSubmit.setOnClickListener {
            val picked = selectedCase
            if (picked == null) {
                toast("Select a booking")
                return@setOnClickListener
            }
            val amount = etAmount.text?.toString()?.trim()?.toDoubleOrNull()
            if (amount == null || amount <= 0) {
                toast("Enter a valid amount (greater than zero)")
                return@setOnClickListener
            }
            val typedMode = etPaymentMode.text?.toString()?.trim().orEmpty()
            // The dropdown click handler keeps `selectedMode` in sync,
            // but if the user types instead of picking we resolve here.
            val resolvedMode = paymentModes.firstOrNull { it.label.equals(typedMode, ignoreCase = true) }
                ?: paymentModes.firstOrNull { it.id.equals(typedMode, ignoreCase = true) }
            if (resolvedMode == null) {
                toast("Select a payment mode")
                return@setOnClickListener
            }
            selectedMode = resolvedMode
            val refId = etRefId.text?.toString()?.trim().orEmpty()
            if (refId.isBlank()) {
                toast("Transaction ID is required (UTR / cheque / ref no)")
                return@setOnClickListener
            }
            val notes = etNotes.text?.toString()?.trim().orEmpty()

            setFragmentResult(
                RESULT_KEY,
                bundleOf(
                    KEY_SUBMITTED to true,
                    KEY_CASE_ID to picked.id,
                    KEY_BOOKING_REF to picked.bookingRefNo,
                    KEY_CLIENT_NAME to picked.clientName,
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

    private fun formatBookingLabel(c: PostSaleCaseSummary): String {
        val location = listOfNotNull(c.projectName?.takeIf { it.isNotBlank() }, c.plotNo?.takeIf { it.isNotBlank() })
            .joinToString(" · ")
        val balance = "Balance ₹${"%,.0f".format(c.balanceAmount)}"
        val base = "${c.clientName} · ${c.bookingRefNo}"
        return if (location.isNotBlank()) "$base · $location · $balance" else "$base · $balance"
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

        private const val ARG_CASES = "arg_cases"
        private const val ARG_RECTIFY_ITEM = "arg_rectify_item"

        fun newInstance(
            cases: List<PostSaleCaseSummary>,
            rectifyItem: CollectionItem? = null,
        ): CollectionCreateBottomSheet = CollectionCreateBottomSheet().apply {
            arguments = Bundle().apply {
                putSerializable(ARG_CASES, ArrayList(cases))
                if (rectifyItem != null) putSerializable(ARG_RECTIFY_ITEM, rectifyItem)
            }
        }
    }
}
