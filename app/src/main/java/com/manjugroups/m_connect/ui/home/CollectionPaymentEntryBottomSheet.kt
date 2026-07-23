package com.manjugroups.m_connect.ui.home

import android.app.Dialog
import android.content.ContentResolver
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.ui.common.SearchableOption
import com.manjugroups.m_connect.ui.common.SearchableSelectionDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Payment Entry sheet — Collection CP Yes path.
 *
 * Lands after the staff has captured the arrival photo + verified the
 * arrival OTP. The sheet mirrors the web `Payment Entry` dialog from
 * the post-sales workbench: pick the booking, enter amount + mode,
 * (optional) reference + proof attachment + notes, submit. On submit
 * the proof is uploaded to Convex storage, then the collection row is
 * written via /api/postsales/collections/submit and the {caseId,
 * collectionRefNo, summary} payload is returned via FragmentResult so
 * the host can close the CP visit with outcome="collection_done".
 *
 * Cancel / outside-tap returns submitted=false so the host can reset
 * the swipe button and let the field staff retry without losing the
 * arrived state.
 */
class CollectionPaymentEntryBottomSheet : BottomSheetDialogFragment() {

    private val api = ApiService.create()
    private lateinit var session: SessionManager

    private data class BookingOption(
        val caseId: String,
        val bookingRefNo: String,
        val clientName: String,
        val projectName: String?,
        val plotNo: String?,
        val balanceAmount: Double,
    )

    private data class ModeOption(val id: String, val label: String)

    private val modeOptions = listOf(
        ModeOption("upi", "UPI"),
        ModeOption("cash", "Cash"),
        ModeOption("neft", "NEFT"),
        ModeOption("rtgs", "RTGS"),
        ModeOption("cheque", "Cheque"),
        ModeOption("dd", "DD"),
        ModeOption("bank", "Bank"),
    )

    private var bookings: List<BookingOption> = emptyList()
    private var selectedBooking: BookingOption? = null
    private var selectedMode: ModeOption = modeOptions.first()

    private var proofUri: Uri? = null
    private var proofFileName: String? = null
    private var proofFileSize: Long = 0
    private var proofMimeType: String? = null

    private val pickProofLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        handleProofPicked(uri)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        // ADJUST_RESIZE keeps the focused input visible above the soft
        // keyboard — otherwise the keyboard overlays the lower half of
        // the sheet (the Amount / Reference / Notes fields and the
        // Submit button all land underneath it).
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.setBackgroundColor(Color.TRANSPARENT)
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.isDraggable = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_collection_payment_entry, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        val args = requireArguments()
        val caseIds = args.getStringArrayList(ARG_CASE_IDS).orEmpty()
        val refs = args.getStringArrayList(ARG_BOOKING_REFS).orEmpty()
        val names = args.getStringArrayList(ARG_CLIENT_NAMES).orEmpty()
        val projects = args.getStringArrayList(ARG_PROJECTS).orEmpty()
        val plots = args.getStringArrayList(ARG_PLOTS).orEmpty()
        val balances = args.getDoubleArray(ARG_BALANCES)?.toList().orEmpty()
        bookings = caseIds.indices.map { i ->
            BookingOption(
                caseId = caseIds.getOrNull(i).orEmpty(),
                bookingRefNo = refs.getOrNull(i).orEmpty(),
                clientName = names.getOrNull(i).orEmpty(),
                projectName = projects.getOrNull(i)?.takeIf { it.isNotBlank() },
                plotNo = plots.getOrNull(i)?.takeIf { it.isNotBlank() },
                balanceAmount = balances.getOrNull(i) ?: 0.0,
            )
        }

        val etBooking = view.findViewById<EditText>(R.id.etCustomerBooking)
        val etAmount = view.findViewById<EditText>(R.id.etCollectionAmount)
        val etMode = view.findViewById<EditText>(R.id.etCollectionMode)
        val etReference = view.findViewById<EditText>(R.id.etCollectionReference)
        val etNotes = view.findViewById<EditText>(R.id.etCollectionNotes)
        val btnCancel = view.findViewById<Button>(R.id.btnCancelCollection)
        val btnSubmit = view.findViewById<Button>(R.id.btnSubmitCollection)
        val dropZone = view.findViewById<View>(R.id.proofDropZone)
        val proofEmpty = view.findViewById<LinearLayout>(R.id.proofEmptyState)
        val proofFilled = view.findViewById<LinearLayout>(R.id.proofFilledState)
        val tvProofName = view.findViewById<TextView>(R.id.tvProofFileName)
        val tvProofSize = view.findViewById<TextView>(R.id.tvProofFileSize)
        val btnRemoveProof = view.findViewById<Button>(R.id.btnRemoveProof)

        // Auto-pick the first booking when there's only one — mirrors
        // the web flow where the Customer dropdown still shows the row
        // but the user doesn't have to click anything for the common
        // case of a single confirmed booking per mobile.
        if (bookings.size == 1) {
            selectedBooking = bookings.first()
            etBooking.setText(formatBookingLabel(bookings.first()))
        }

        etBooking.setOnClickListener {
            SearchableSelectionDialog.show(
                context = requireContext(),
                title = "Select booking",
                options = bookings.map { b ->
                    SearchableOption(
                        item = b,
                        title = formatBookingLabel(b),
                        subtitle = formatBookingSubtitle(b),
                        keywords = b.bookingRefNo + " " + b.clientName,
                    )
                },
                emptyMessage = "No bookings",
            ) { picked ->
                selectedBooking = picked
                etBooking.setText(formatBookingLabel(picked))
            }
        }

        etMode.setOnClickListener {
            SearchableSelectionDialog.show(
                context = requireContext(),
                title = "Select mode",
                options = modeOptions.map { m ->
                    SearchableOption(
                        item = m,
                        title = m.label,
                        subtitle = "",
                        keywords = m.id + " " + m.label,
                    )
                },
                emptyMessage = "No modes",
            ) { picked ->
                selectedMode = picked
                etMode.setText(picked.label)
            }
        }

        dropZone.setOnClickListener { pickProofLauncher.launch("*/*") }

        btnRemoveProof.setOnClickListener {
            proofUri = null
            proofFileName = null
            proofFileSize = 0
            proofMimeType = null
            proofEmpty.visibility = View.VISIBLE
            proofFilled.visibility = View.GONE
        }

        btnCancel.setOnClickListener {
            setFragmentResult(RESULT_KEY, bundleOf(KEY_SUBMITTED to false))
            dismissAllowingStateLoss()
        }

        btnSubmit.setOnClickListener {
            val booking = selectedBooking
            if (booking == null) {
                toast("Select the booking you collected against")
                return@setOnClickListener
            }
            val amount = etAmount.text?.toString()?.trim()?.toDoubleOrNull()
            if (amount == null || amount <= 0) {
                toast("Enter the amount received (greater than zero)")
                return@setOnClickListener
            }
            val reference = etReference.text?.toString()?.trim().orEmpty()
            if (reference.isBlank()) {
                toast("Transaction ID is required (UTR / Cheque / Ref no)")
                return@setOnClickListener
            }
            val notes = etNotes.text?.toString()?.trim().orEmpty()

            btnSubmit.isEnabled = false
            btnCancel.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val storageId = if (proofUri != null) uploadProofIfAny() else null
                    if (proofUri != null && storageId == null) {
                        btnSubmit.isEnabled = true
                        btnCancel.isEnabled = true
                        return@launch
                    }
                    setFragmentResult(
                        RESULT_KEY,
                        bundleOf(
                            KEY_SUBMITTED to true,
                            KEY_CASE_ID to booking.caseId,
                            KEY_BOOKING_REF to booking.bookingRefNo,
                            KEY_CLIENT_NAME to booking.clientName,
                            KEY_AMOUNT to amount,
                            KEY_PAYMENT_MODE to selectedMode.id,
                            KEY_PAYMENT_MODE_LABEL to selectedMode.label,
                            KEY_TRANSACTION_REF to reference,
                            KEY_NOTES to notes,
                            KEY_PROOF_STORAGE_ID to (storageId.orEmpty()),
                            KEY_PROOF_FILE_NAME to (proofFileName.orEmpty()),
                        ),
                    )
                    dismissAllowingStateLoss()
                } catch (e: Exception) {
                    btnSubmit.isEnabled = true
                    btnCancel.isEnabled = true
                    toast(e.message ?: "Failed to submit collection")
                }
            }
        }
    }

    private fun handleProofPicked(uri: Uri) {
        val cr = requireContext().contentResolver
        val mime = cr.getType(uri)
        if (mime !in ALLOWED_MIME_TYPES) {
            toast("Proof must be PDF, JPG, PNG or WebP")
            return
        }
        var name: String? = null
        var size: Long = 0
        cr.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) name = c.getString(nameIdx)
                if (sizeIdx >= 0) size = c.getLong(sizeIdx)
            }
        }
        if (size > MAX_PROOF_BYTES) {
            toast("Proof must be 10 MB or smaller")
            return
        }
        proofUri = uri
        proofFileName = name ?: "proof"
        proofFileSize = size
        proofMimeType = mime
        view?.findViewById<LinearLayout>(R.id.proofEmptyState)?.visibility = View.GONE
        view?.findViewById<LinearLayout>(R.id.proofFilledState)?.visibility = View.VISIBLE
        view?.findViewById<TextView>(R.id.tvProofFileName)?.text = proofFileName
        view?.findViewById<TextView>(R.id.tvProofFileSize)?.text =
            "${(size / 1024).coerceAtLeast(1)} KB · ${mime.orEmpty()}"
    }

    private suspend fun uploadProofIfAny(): String? {
        val uri = proofUri ?: return null
        val mime = proofMimeType
        return try {
            val ctx = requireContext()
            val resp = withContext(Dispatchers.IO) {
                val cr: ContentResolver = ctx.contentResolver
                var bytes = cr.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Unable to read proof file")
                // Downscale photo proofs (field networks); non-images pass through.
                if ((mime ?: "").startsWith("image/", ignoreCase = true)) {
                    val tmp = java.io.File.createTempFile("coll_proof_", ".jpg", ctx.cacheDir)
                    try {
                        tmp.writeBytes(bytes)
                        val cmp = com.manjugroups.m_connect.util.ImageCompressor.compress(tmp)
                        bytes = cmp.readBytes()
                        if (cmp !== tmp) runCatching { cmp.delete() }
                    } finally {
                        runCatching { tmp.delete() }
                    }
                }
                api.uploadStorageFile(
                    token = session.bearerToken,
                    body = bytes.toRequestBody(mime?.toMediaTypeOrNull()),
                )
            }
            if (!resp.success || resp.storageId.isNullOrBlank()) {
                toast(resp.error ?: "Proof upload failed")
                return null
            }
            resp.storageId
        } catch (e: Exception) {
            toast(e.message ?: "Proof upload failed")
            null
        }
    }

    private fun formatBookingLabel(b: BookingOption): String =
        "${b.clientName} · ${b.bookingRefNo}"

    private fun formatBookingSubtitle(b: BookingOption): String {
        val location = listOfNotNull(b.projectName, b.plotNo).joinToString(" · ")
        val balance = "Balance ₹${"%,.0f".format(b.balanceAmount)}"
        return if (location.isNotBlank()) "$location · $balance" else balance
    }

    override fun onCancel(dialog: android.content.DialogInterface) {
        super.onCancel(dialog)
        setFragmentResult(RESULT_KEY, bundleOf(KEY_SUBMITTED to false))
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val RESULT_KEY = "collection_payment_entry_result"
        const val KEY_SUBMITTED = "submitted"
        const val KEY_CASE_ID = "case_id"
        const val KEY_BOOKING_REF = "booking_ref"
        const val KEY_CLIENT_NAME = "client_name"
        const val KEY_AMOUNT = "amount"
        const val KEY_PAYMENT_MODE = "payment_mode"
        const val KEY_PAYMENT_MODE_LABEL = "payment_mode_label"
        const val KEY_TRANSACTION_REF = "transaction_ref"
        const val KEY_NOTES = "notes"
        const val KEY_PROOF_STORAGE_ID = "proof_storage_id"
        const val KEY_PROOF_FILE_NAME = "proof_file_name"

        private const val ARG_CASE_IDS = "arg_case_ids"
        private const val ARG_BOOKING_REFS = "arg_booking_refs"
        private const val ARG_CLIENT_NAMES = "arg_client_names"
        private const val ARG_PROJECTS = "arg_projects"
        private const val ARG_PLOTS = "arg_plots"
        private const val ARG_BALANCES = "arg_balances"

        private val ALLOWED_MIME_TYPES = setOf(
            "application/pdf",
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/webp",
        )
        private const val MAX_PROOF_BYTES = 10L * 1024L * 1024L

        fun newInstance(
            cases: List<com.manjugroups.m_connect.network.PostSaleCaseSummary>,
        ): CollectionPaymentEntryBottomSheet {
            return CollectionPaymentEntryBottomSheet().apply {
                arguments = bundleOf(
                    ARG_CASE_IDS to ArrayList(cases.map { it.id }),
                    ARG_BOOKING_REFS to ArrayList(cases.map { it.bookingRefNo }),
                    ARG_CLIENT_NAMES to ArrayList(cases.map { it.clientName }),
                    ARG_PROJECTS to ArrayList(cases.map { it.projectName.orEmpty() }),
                    ARG_PLOTS to ArrayList(cases.map { it.plotNo.orEmpty() }),
                    ARG_BALANCES to cases.map { it.balanceAmount }.toDoubleArray(),
                )
            }
        }
    }
}
