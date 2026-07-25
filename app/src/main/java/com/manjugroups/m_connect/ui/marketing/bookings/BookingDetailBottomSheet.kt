package com.manjugroups.m_connect.ui.marketing.bookings

import android.app.Dialog
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.core.os.bundleOf
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.Booking
import com.manjugroups.m_connect.network.BookingApproveRequest
import com.manjugroups.m_connect.network.BookingRejectRequest
import com.manjugroups.m_connect.network.UpdateBookingRequest
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import com.manjugroups.m_connect.network.StorageUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

class BookingDetailBottomSheet : BottomSheetDialogFragment() {

    private enum class Tab(val label: String) {
        APPROVAL("Approval"),
        CLIENT("Client Details"),
        BOOKING("Booking & Finance"),
        PAYMENT("Payment & Staff"),
    }

    private data class FieldSpec(
        val key: String,
        val label: String,
        val value: String?,
        val numeric: Boolean = false,
        val editable: Boolean = true,
    )

    private val api = ApiService.create()
    private lateinit var session: SessionManager
    private var bookingId: String = ""
    private var booking: Booking? = null
    private var activeTab: Tab = Tab.APPROVAL
    private var editMode: Boolean = false
    private val inputs = linkedMapOf<String, EditText>()
    private val fieldContainers = linkedMapOf<String, LinearLayout>()
    private val editableInputs = mutableSetOf<String>()
    private var approvalTransactionInput: EditText? = null

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var editButton: TextView
    private lateinit var saveButton: TextView
    private lateinit var tabRow: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var progress: TextView
    private lateinit var footer: LinearLayout

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        // Resize the sheet when the keyboard opens so edited fields (Client /
        // Booking / Payment tabs in edit mode) stay above the IME instead of
        // being covered — the sheet was previously unresponsive to typing.
        dialog.window?.let { window ->
            window.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            )
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
        }
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                // Rounded top corners + full-height like the app's other sheets;
                // without the rounded background the sheet showed square corners.
                it.setBackgroundResource(R.drawable.bg_bottom_sheet)
                val behavior = BottomSheetBehavior.from(it)
                behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.92f).toInt()
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                // Pullable — the content is a NestedScrollView, so pulling the
                // header/handle drags the sheet while scrolling the body scrolls
                // the content (handed off correctly, no accidental dismiss).
                behavior.isDraggable = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        session = SessionManager(requireContext())
        bookingId = arguments?.getString(ARG_BOOKING_ID).orEmpty()
        return buildView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadBooking()
    }

    private fun buildView(): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            // Transparent so the sheet's rounded bg_bottom_sheet shows (rounded
            // top corners); a solid white root painted square corners over it.
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        root.addView(View(requireContext()).apply {
            setBackgroundColor(Color.parseColor("#D0D5DD"))
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(10)
                bottomMargin = dp(10)
            }
        })

        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(12))
        }
        val titleBox = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        title = TextView(requireContext()).apply {
            text = "Booking"
            textSize = 18f
            includeFontPadding = false
            setTextColor(Color.parseColor("#101828"))
            typeface = interFont(R.font.inter_bold)
        }
        subtitle = TextView(requireContext()).apply {
            text = "Loading details..."
            textSize = 12f
            includeFontPadding = false
            typeface = interFont(R.font.inter_regular)
            setTextColor(Color.parseColor("#667085"))
        }
        titleBox.addView(title)
        titleBox.addView(subtitle)
        editButton = actionButton("Edit", "#FFFFFF", "#175CD3").apply {
            setOnClickListener { setEditMode(!editMode) }
        }
        val closeButton = actionButton("Close", "#F2F4F7", "#344054").apply {
            setOnClickListener { dismissAllowingStateLoss() }
        }
        header.addView(titleBox)
        header.addView(editButton)
        header.addView(closeButton)
        root.addView(header)

        val scroller = HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
        }
        tabRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        scroller.addView(tabRow)
        root.addView(scroller)
        root.addView(View(requireContext()).apply {
            setBackgroundColor(Color.parseColor("#EDEFF3"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1),
            ).apply { topMargin = dp(10) }
        })

        progress = TextView(requireContext()).apply {
            text = "Loading booking..."
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#667085"))
            setPadding(dp(16), dp(32), dp(16), dp(32))
        }
        root.addView(progress)

        val nested = NestedScrollView(requireContext()).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            // Light-grey page so the white summary / timeline / field cards pop.
            setBackgroundColor(Color.parseColor("#F7F8FA"))
            setPadding(dp(16), dp(16), dp(16), dp(20))
        }
        nested.addView(content)
        root.addView(nested)

        footer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(16), dp(10), dp(16), dp(14))
            setBackgroundColor(Color.parseColor("#FFFFFF"))
        }
        saveButton = actionButton("Save Changes", "#0B61CA", "#FFFFFF").apply {
            visibility = View.GONE
            setOnClickListener { saveChanges() }
        }
        footer.addView(saveButton)
        root.addView(footer)

        renderTabs()
        content.visibility = View.GONE
        footer.visibility = View.GONE
        return root
    }

    private fun loadBooking() {
        if (bookingId.isBlank()) {
            showError("Booking id missing")
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getBooking(session.bearerToken, bookingId)
                if (!resp.success || resp.booking == null) {
                    showError(resp.error ?: "Booking not found")
                    return@launch
                }
                booking = resp.booking
                title.text = resp.booking.bookingRefNo ?: "Booking"
                subtitle.text = listOfNotNull(
                    resp.booking.clientName,
                    resp.booking.projectName,
                    resp.booking.status?.replace("_", " "),
                ).joinToString(" - ").ifBlank { "Booking details" }
                progress.visibility = View.GONE
                content.visibility = View.VISIBLE
                footer.visibility = View.VISIBLE
                renderContent()
            } catch (e: Exception) {
                showError(e.message ?: "Failed to load booking")
            }
        }
    }

    private fun renderTabs() {
        tabRow.removeAllViews()
        Tab.entries.forEach { tab ->
            val active = tab == activeTab
            tabRow.addView(TextView(requireContext()).apply {
                text = tab.label
                textSize = 12f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(dp(14), 0, dp(14), 0)
                setTextColor(Color.parseColor(if (active) "#FFFFFF" else "#475467"))
                setBackgroundResource(
                    if (active) R.drawable.bg_outcome_subtab_active
                    else R.drawable.bg_outcome_subtab_inactive,
                )
                typeface = interFont(if (active) R.font.inter_semibold else R.font.inter_medium)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(32),
                ).apply { marginEnd = dp(6) }
                setOnClickListener {
                    activeTab = tab
                    renderTabs()
                    renderContent()
                }
            })
        }
    }

    private fun renderContent() {
        inputs.clear()
        fieldContainers.clear()
        editableInputs.clear()
        content.removeAllViews()
        val b = booking ?: return
        when (activeTab) {
            Tab.APPROVAL -> renderApproval(b)
            Tab.CLIENT -> renderFields(clientFields(b))
            Tab.BOOKING -> renderFields(bookingFields(b))
            Tab.PAYMENT -> renderFields(paymentFields(b))
        }
        refreshEditableState()
    }

    private fun renderApproval(b: Booking) {
        addSummaryCard(
            listOf(
                "Client" to b.clientName,
                "Mobile" to b.mobileNumber,
                "Project" to b.projectName,
                "Plot" to (b.plot?.unitNumber ?: b.plotNumber ?: b.plotNo),
                "Status" to labelStatus(b.status),
                "Approval Stage" to labelStage(b.approvalStage),
                "Agreed Amount" to money(b.agreedAmount ?: b.bookingCost),
                "Advance" to money(b.advanceAmount),
                "Telecaller" to b.sourceTelecallerStaff?.name,
                "Source AVP" to b.sourceAvpStaff?.name,
            )
        )

        section("Approval Timeline")
        val request = b.approvalRequest
        if (request == null && b.approvalStage.isNullOrBlank()) {
            note("Approval starts when the draft is submitted for confirmation.")
        } else {
            note("Requested by: ${request?.requestedBy ?: "system"}")
            note("Current: ${request?.currentApproverName ?: request?.currentApproverRole ?: labelStage(b.approvalStage)}")
            val workflowSteps = b.approvalWorkflow?.steps.orEmpty().sortedBy { it.stepOrder ?: 0 }
            if (workflowSteps.isNotEmpty()) {
                workflowSteps.forEach { step ->
                    timelineLine(
                        "Step ${step.stepOrder ?: "-"}",
                        step.approverRole ?: "Approver",
                    )
                }
            }
            request?.approvalHistory.orEmpty().forEach { item ->
                timelineLine(
                    "${item.action?.replaceFirstChar { it.uppercase() } ?: "Action"} by ${item.approverName ?: "-"}",
                    listOfNotNull(item.timestamp, item.comment).joinToString(" - "),
                )
            }
        }

        if (!b.cancellationApprovalStage.isNullOrBlank()) {
            section("Cancellation")
            note("Stage: ${labelStage(b.cancellationApprovalStage)}")
            val cancel = b.cancellationRequest
            note("Current: ${cancel?.currentApproverName ?: cancel?.currentApproverRole ?: "-"}")
            cancel?.approvalHistory.orEmpty().forEach { item ->
                timelineLine(
                    "${item.action?.replaceFirstChar { it.uppercase() } ?: "Action"} by ${item.approverName ?: "-"}",
                    listOfNotNull(item.timestamp, item.comment).joinToString(" - "),
                )
            }
        }

        val canAct = request?.status == "pending" &&
            request.currentApproverId == session.staffId &&
            session.hasPermissionForAnyApproval()
        if (canAct) {
            section("Approval Action")
            val currentStep = b.approvalWorkflow?.steps.orEmpty()
                .firstOrNull { it.stepOrder == (request?.currentStep ?: 1) }
            if (currentStep?.requiresTransactionId == true) {
                note("Transaction ID is required for this approval step.")
                approvalTransactionInput = EditText(requireContext()).apply {
                    setText(b.accountsTransactionId.orEmpty())
                    hint = "Payment transaction ID"
                    textSize = 14f
                    setSingleLine(true)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    setBackgroundColor(Color.WHITE)
                }
                content.addView(approvalTransactionInput, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) })
            } else {
                approvalTransactionInput = null
            }
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(8), 0, dp(8))
            }
            row.addView(actionButton("Reject", "#FEF3F2", "#B42318").apply {
                setOnClickListener { promptReject() }
            })
            row.addView(actionButton("Approve", "#ECFDF3", "#067647").apply {
                setOnClickListener { promptApprove() }
            })
            content.addView(row)
        } else if (request?.status == "pending") {
            note("Awaiting ${request.currentApproverName ?: request.currentApproverRole ?: "next approver"}.")
        }
    }

    private fun renderFields(fields: List<FieldSpec>) {
        fields.forEachIndexed { index, field ->
            // Label — matches the New Booking form: inter_medium 12sp #475467.
            content.addView(TextView(requireContext()).apply {
                text = field.label
                textSize = 12f
                includeFontPadding = false
                typeface = interFont(R.font.inter_medium)
                setTextColor(Color.parseColor("#475467"))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = if (index == 0) 0 else dp(14) }
            })

            // Rounded white pill (bg_outcome_field_pill) with a leading
            // icon and a transparent EditText — identical chrome to the
            // New Booking client form.
            val pill = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_outcome_field_pill)
                setPadding(dp(14), 0, dp(14), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(48),
                ).apply { topMargin = dp(6) }
            }
            pill.addView(ImageView(requireContext()).apply {
                setImageResource(iconFor(field.key, field.numeric))
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
            })
            val input = EditText(requireContext()).apply {
                setText(field.value.orEmpty())
                textSize = 13f
                setSingleLine(true)
                includeFontPadding = false
                typeface = interFont(R.font.inter_medium)
                background = null
                setTextColor(Color.parseColor("#101828"))
                setHintTextColor(Color.parseColor("#94A3B8"))
                hint = field.label
                setPadding(0, 0, 0, 0)
                inputType = if (field.numeric) {
                    android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                } else {
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                }
            }
            pill.addView(input, LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f,
            ).apply { marginStart = dp(10) })

            inputs[field.key] = input
            fieldContainers[field.key] = pill
            if (field.editable) editableInputs.add(field.key)
            content.addView(pill)
        }
    }

    private fun addSummaryCard(items: List<Pair<String, String?>>) {
        val box = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#EDEFF3"))
            }
        }
        items.chunked(2).forEach { rowItems ->
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            rowItems.forEach { item ->
                val cell = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(5), dp(10), dp(5))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                cell.addView(TextView(requireContext()).apply {
                    text = item.first
                    textSize = 11f
                    includeFontPadding = false
                    typeface = interFont(R.font.inter_medium)
                    setTextColor(Color.parseColor("#94A3B8"))
                })
                cell.addView(TextView(requireContext()).apply {
                    text = item.second?.takeIf { it.isNotBlank() } ?: "-"
                    textSize = 13f
                    includeFontPadding = false
                    typeface = interFont(R.font.inter_semibold)
                    setTextColor(Color.parseColor("#101828"))
                    setPadding(0, dp(2), 0, 0)
                })
                row.addView(cell)
            }
            box.addView(row)
        }
        content.addView(box)
    }

    private fun section(text: String) {
        content.addView(TextView(requireContext()).apply {
            this.text = text
            textSize = 13f
            includeFontPadding = false
            typeface = interFont(R.font.inter_semibold)
            setTextColor(Color.parseColor("#101828"))
            setPadding(0, dp(18), 0, dp(8))
        })
    }

    private fun note(text: String) {
        content.addView(TextView(requireContext()).apply {
            this.text = text
            textSize = 13f
            includeFontPadding = false
            typeface = interFont(R.font.inter_regular)
            setTextColor(Color.parseColor("#475467"))
            setPadding(0, dp(4), 0, dp(4))
        })
    }

    private fun timelineLine(title: String, sub: String) {
        content.addView(TextView(requireContext()).apply {
            text = if (sub.isBlank()) title else "$title\n$sub"
            textSize = 13f
            includeFontPadding = false
            typeface = interFont(R.font.inter_medium)
            setTextColor(Color.parseColor("#344054"))
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#EDEFF3"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        })
    }

    private fun setEditMode(enabled: Boolean) {
        editMode = enabled
        editButton.text = if (enabled) "View" else "Edit"
        saveButton.visibility = if (enabled) View.VISIBLE else View.GONE
        refreshEditableState()
    }

    private fun refreshEditableState() {
        val canEdit = editMode && activeTab != Tab.APPROVAL
        inputs.forEach { (key, input) ->
            val editable = canEdit && editableInputs.contains(key)
            input.isEnabled = editable
            input.isFocusable = editable
            input.isFocusableInTouchMode = editable
            // Keep the pill chrome from bg_outcome_field_pill; just dim
            // read-only pills slightly so editable fields stand out in
            // edit mode. The EditText itself stays transparent.
            fieldContainers[key]?.alpha = if (!canEdit || editable) 1f else 0.6f
        }
    }

    private var pendingProofStorageId: String? = null
    private var pendingProofFileName: String? = null
    private var tvApprovalAttachmentName: TextView? = null
    private var btnUploadAttachment: TextView? = null

    private val pickApprovalAttachment = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        uploadApprovalAttachment(uri)
    }

    private fun uploadApprovalAttachment(uri: Uri) {
        val ctx = context ?: return
        btnUploadAttachment?.text = "Uploading…"
        btnUploadAttachment?.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val displayName = resolveDocName(uri)
            val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
            val uploaded = withContext(Dispatchers.IO) {
                runCatching {
                    val suffix = displayName.substringAfterLast('.', "bin").take(8)
                    val temp = java.io.File.createTempFile("approve_proof_", ".$suffix", ctx.cacheDir)
                    try {
                        ctx.contentResolver.openInputStream(uri).use { input ->
                            requireNotNull(input) { "Unable to read selected file" }
                            temp.outputStream().use { output -> input.copyTo(output) }
                        }
                        StorageUploader.upload(api, session.bearerToken, temp, contentType = mime)
                    } finally {
                        temp.delete()
                    }
                }.getOrNull()
            }
            val storageId = uploaded?.storageId
            btnUploadAttachment?.isEnabled = true
            if (!storageId.isNullOrBlank()) {
                pendingProofStorageId = storageId
                pendingProofFileName = displayName
                tvApprovalAttachmentName?.text = displayName
                tvApprovalAttachmentName?.setTextColor(Color.parseColor("#187A2F"))
                btnUploadAttachment?.text = "Change File"
                Toast.makeText(ctx, "Attachment uploaded", Toast.LENGTH_SHORT).show()
            } else {
                btnUploadAttachment?.text = "Attach File (Optional)"
                Toast.makeText(ctx, "Attachment upload failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun resolveDocName(uri: Uri): String {
        val ctx = context ?: return "payment-proof"
        ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment ?: "payment-proof"
    }

    private fun promptApprove() {
        val input = EditText(requireContext()).apply {
            hint = "Enter Transaction ID / Ref No *"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val attachRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        attachRow.addView(TextView(requireContext()).apply {
            text = "Payment Proof / Receipt (Optional)"
            textSize = 12f
            typeface = interFont(R.font.inter_medium)
            setTextColor(Color.parseColor("#475467"))
        })

        btnUploadAttachment = TextView(requireContext()).apply {
            text = if (pendingProofStorageId != null) "Change File" else "Attach File (Optional)"
            textSize = 13f
            typeface = interFont(R.font.inter_medium)
            setTextColor(Color.parseColor("#1570EF"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = ResourcesCompat.getDrawable(resources, R.drawable.bg_outcome_edit_chip_inactive, null)
            setOnClickListener {
                pickApprovalAttachment.launch("*/*")
            }
        }
        tvApprovalAttachmentName = TextView(requireContext()).apply {
            text = pendingProofFileName ?: "No file attached"
            textSize = 12f
            setTextColor(if (pendingProofStorageId != null) Color.parseColor("#187A2F") else Color.parseColor("#98A2B3"))
            setPadding(0, dp(4), 0, 0)
        }
        attachRow.addView(btnUploadAttachment)
        attachRow.addView(tvApprovalAttachmentName)

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
            addView(TextView(requireContext()).apply {
                text = "Attach Accounts Transaction ID / Ref No to approve this booking."
                textSize = 13f
                setTextColor(Color.parseColor("#475467"))
                setPadding(0, 0, 0, dp(10))
            })
            addView(input)
            addView(attachRow)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Approve Booking")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Approve", null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val txId = input.text?.toString()?.trim().orEmpty()
                        if (txId.isBlank()) {
                            Toast.makeText(requireContext(), "Transaction ID / Ref No is required to approve", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        dismiss()
                        approveBooking(txId, pendingProofStorageId, pendingProofFileName)
                    }
                }
            }
            .show()
    }

    private fun approveBooking(transactionId: String, proofStorageId: String? = null, proofFileName: String? = null) {
        val b = booking ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.approveBooking(
                    session.bearerToken,
                    b.id,
                    BookingApproveRequest(
                        accountsTransactionId = transactionId,
                        accountsPaymentProofStorageId = proofStorageId,
                        accountsPaymentProofFileName = proofFileName,
                    ),
                )
                if (!resp.success) {
                    Toast.makeText(requireContext(), resp.error ?: "Approve failed", Toast.LENGTH_LONG).show()
                    return@launch
                }
                pendingProofStorageId = null
                pendingProofFileName = null
                Toast.makeText(requireContext(), "Approved Successfully", Toast.LENGTH_SHORT).show()
                setFragmentResult(RESULT_KEY, bundleOf("bookingId" to b.id))
                loadBooking()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message ?: "Approve failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun promptReject() {
        val input = EditText(requireContext()).apply {
            hint = "Rejection reason"
            minLines = 3
            setSingleLine(false)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Reject booking")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Reject") { _, _ ->
                val reason = input.text?.toString()?.trim().orEmpty()
                if (reason.isBlank()) {
                    Toast.makeText(requireContext(), "Reason is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                rejectBooking(reason)
            }
            .show()
    }

    private fun rejectBooking(reason: String) {
        val b = booking ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.rejectBooking(session.bearerToken, b.id, BookingRejectRequest(reason))
                if (!resp.success) {
                    Toast.makeText(requireContext(), resp.error ?: "Reject failed", Toast.LENGTH_LONG).show()
                    return@launch
                }
                Toast.makeText(requireContext(), "Rejected", Toast.LENGTH_SHORT).show()
                setFragmentResult(RESULT_KEY, bundleOf("bookingId" to b.id))
                loadBooking()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message ?: "Reject failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveChanges() {
        val b = booking ?: return
        val clientName = value("clientName")
        val mobileNumber = value("mobileNumber")
        val bookingDate = value("bookingDate")
        if (clientName.isBlank() || mobileNumber.isBlank() || bookingDate.isBlank()) {
            Toast.makeText(requireContext(), "Client name, mobile and booking date are required", Toast.LENGTH_SHORT).show()
            return
        }
        val bookingCost = number("bookingCost")
        val specialConsideration = number("specialConsideration")
        val agreedAmount = bookingCost?.minus(specialConsideration ?: 0.0)
        val advance = number("advanceAmount")
        val balance = if (agreedAmount != null && advance != null) agreedAmount - advance else number("balanceAmount")
        val req = UpdateBookingRequest(
            title = valueOrNull("title"),
            clientName = clientName,
            fatherSpouseName = valueOrNull("fatherSpouseName"),
            dateOfBirth = valueOrNull("dateOfBirth"),
            anniversaryDate = valueOrNull("anniversaryDate"),
            mobileNumber = mobileNumber,
            alternateNumbers = valueOrNull("alternateNumbers"),
            whatsappNumber = valueOrNull("whatsappNumber"),
            email = valueOrNull("email"),
            pincode = valueOrNull("pincode"),
            homeAddress = valueOrNull("homeAddress"),
            profession = valueOrNull("profession"),
            designation = valueOrNull("designation"),
            incomePerAnnum = valueOrNull("incomePerAnnum"),
            officeName = valueOrNull("officeName"),
            officeAddress = valueOrNull("officeAddress"),
            state = valueOrNull("state"),
            district = valueOrNull("district"),
            location = valueOrNull("location"),
            officeMobile = valueOrNull("officeMobile"),
            officePhone = valueOrNull("officePhone"),
            officeEmail = valueOrNull("officeEmail"),
            nationality = valueOrNull("nationality"),
            bookingType = valueOrNull("bookingType"),
            cefNo = valueOrNull("cefNo"),
            bookingDate = bookingDate,
            propertyType = valueOrNull("propertyType"),
            bookingMode = valueOrNull("bookingMode"),
            plotNo = valueOrNull("plotNo"),
            bookingCost = bookingCost,
            guidelineValue = number("guidelineValue"),
            specialConsideration = specialConsideration,
            specialConsiderationReason = valueOrNull("specialConsiderationReason"),
            discountApprovedBy = valueOrNull("discountApprovedBy"),
            specialConsiderationValidity = number("specialConsiderationValidity"),
            promotionalOffers = valueOrNull("promotionalOffers"),
            promotionalOffersTnC = valueOrNull("promotionalOffersTnC"),
            promotionalOfferValue = number("promotionalOfferValue"),
            offerValidityPeriod = number("offerValidityPeriod"),
            agreedAmount = agreedAmount,
            registrationCharges = number("registrationCharges"),
            gstAmount = number("gstAmount"),
            documentCharges = number("documentCharges"),
            pattaCharges = number("pattaCharges"),
            otherCharges = number("otherCharges"),
            advanceAmount = advance,
            balanceAmount = balance,
            paymentMode = valueOrNull("paymentMode"),
            allotmentDueAmount = number("allotmentDueAmount"),
            allotmentDueDate = valueOrNull("allotmentDueDate"),
            secondPaymentAmount = number("secondPaymentAmount"),
            secondPaymentDate = valueOrNull("secondPaymentDate"),
            thirdPaymentAmount = number("thirdPaymentAmount"),
            thirdPaymentDate = valueOrNull("thirdPaymentDate"),
            fourthPaymentAmount = number("fourthPaymentAmount"),
            fourthPaymentDate = valueOrNull("fourthPaymentDate"),
            preferredRegistrationDate = valueOrNull("preferredRegistrationDate"),
            aadhaar = valueOrNull("aadhaar"),
            pan = valueOrNull("pan"),
            referenceName1 = valueOrNull("referenceName1"),
            referenceMobile1 = valueOrNull("referenceMobile1"),
            referenceProfession1 = valueOrNull("referenceProfession1"),
            referenceName2 = valueOrNull("referenceName2"),
            referenceMobile2 = valueOrNull("referenceMobile2"),
            referenceProfession2 = valueOrNull("referenceProfession2"),
            docPreparedIn = valueOrNull("docPreparedIn"),
            status = b.status,
        )
        saveButton.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.updateBooking(session.bearerToken, b.id, req)
                if (!resp.success) {
                    Toast.makeText(requireContext(), resp.error ?: "Update failed", Toast.LENGTH_LONG).show()
                    return@launch
                }
                Toast.makeText(requireContext(), "Booking updated", Toast.LENGTH_SHORT).show()
                setFragmentResult(RESULT_KEY, bundleOf("bookingId" to b.id))
                setEditMode(false)
                loadBooking()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message ?: "Update failed", Toast.LENGTH_LONG).show()
            } finally {
                saveButton.isEnabled = true
            }
        }
    }

    private fun clientFields(b: Booking) = listOf(
        FieldSpec("mobileNumber", "Mobile Number", b.mobileNumber),
        FieldSpec("title", "Title", b.title),
        FieldSpec("clientName", "Client Name", b.clientName),
        FieldSpec("fatherSpouseName", "Father / Spouse Name", b.fatherSpouseName),
        FieldSpec("dateOfBirth", "Date of Birth", b.dateOfBirth),
        FieldSpec("anniversaryDate", "Anniversary Date", b.anniversaryDate),
        FieldSpec("alternateNumbers", "Alternate Numbers", b.alternateNumbers),
        FieldSpec("whatsappNumber", "WhatsApp Number", b.whatsappNumber),
        FieldSpec("email", "Email", b.email),
        FieldSpec("nationality", "Nationality", b.nationality),
        FieldSpec("homeAddress", "Home Address", b.homeAddress),
        FieldSpec("pincode", "Pincode", b.pincode),
        FieldSpec("state", "State", b.state),
        FieldSpec("district", "District", b.district),
        FieldSpec("location", "Location", b.location),
        FieldSpec("profession", "Profession", b.profession),
        FieldSpec("designation", "Designation", b.designation),
        FieldSpec("department", "Department", b.department, editable = false),
        FieldSpec("incomePerAnnum", "Income Per Annum", b.incomePerAnnum),
        FieldSpec("officeName", "Office Name", b.officeName),
        FieldSpec("officeMobile", "Office Mobile", b.officeMobile),
        FieldSpec("officePhone", "Office Phone", b.officePhone),
        FieldSpec("officeEmail", "Office Email", b.officeEmail),
        FieldSpec("officeAddress", "Office Address", b.officeAddress),
        FieldSpec("officeArea", "Office Area", b.officeArea, editable = false),
        FieldSpec("officePincode", "Office Pincode", b.officePincode, editable = false),
    )

    private fun bookingFields(b: Booking): List<FieldSpec> = buildList {
        add(FieldSpec("bookingRefNo", "Booking Ref No", b.bookingRefNo, editable = false))
        add(FieldSpec("bookingType", "Booking Type", b.bookingType))
        if (b.bookingType == "CONVERSION") {
            add(FieldSpec(
                "conversionEntryType",
                "Previous Booking Source",
                if (b.conversionManualEntry == true) "Manual entry" else "Linked booking",
                editable = false,
            ))
            add(FieldSpec("manualConversionProjectName", "Previous Project", b.manualConversionProjectName, editable = false))
            add(FieldSpec("manualConversionPlotNo", "Previous Plot", b.manualConversionPlotNo, editable = false))
            add(FieldSpec("manualConversionCredit", "Conversion Credit", b.manualConversionCredit?.toString(), numeric = true, editable = false))
            add(FieldSpec("conversionNotes", "Conversion Notes", b.conversionNotes, editable = false))
            add(FieldSpec("sourceConversionBookingId", "Previous Booking ID", b.sourceExchangeBookingId, editable = false))
        }
        if (b.bookingType == "EXCHANGE" || b.bookingType == "INTERNAL EXCHANGE") {
            add(FieldSpec(
                "exchangeEntryType",
                "Old Property Source",
                if (b.exchangeManualEntry == true) "Manual entry" else "Linked booking",
                editable = false,
            ))
            add(FieldSpec("manualExchangeProjectName", "Old Project", b.manualExchangeProjectName, editable = false))
            add(FieldSpec("manualExchangePlotNo", "Old Plot", b.manualExchangePlotNo, editable = false))
            add(FieldSpec("manualExchangeExtentSqft", "Old Extent (sq ft)", b.manualExchangeExtentSqft?.toString(), numeric = true, editable = false))
            add(FieldSpec("exchangeLookupProjectId", "Old Project ID", b.exchangeLookupProjectId, editable = false))
            add(FieldSpec("exchangeLookupPlotNo", "Old Plot Number", b.exchangeLookupPlotNo, editable = false))
            add(FieldSpec("exchangeConnectedMobileNumber", "Connected Mobile", b.exchangeConnectedMobileNumber, editable = false))
            add(FieldSpec("sourceExchangeBookingId", "Source Booking ID", b.sourceExchangeBookingId, editable = false))
            add(FieldSpec("exchangeOldRegisteredValue", "Exchange Value", b.exchangeOldRegisteredValue?.toString(), numeric = true, editable = false))
            add(FieldSpec("exchangeNewValue", "New Registered Value", b.exchangeNewValue?.toString(), numeric = true, editable = false))
            add(FieldSpec("exchangeBalancePayable", "Exchange Balance Payable", b.exchangeBalancePayable?.toString(), numeric = true, editable = false))
            add(FieldSpec("exchangeNotes", "Exchange Notes", b.exchangeNotes, editable = false))
        }
        add(FieldSpec("cefNo", "CEF No", b.cefNo))
        add(FieldSpec("bookingDate", "Booking Date", b.bookingDate))
        add(FieldSpec("projectName", "Project", b.projectName, editable = false))
        add(FieldSpec("plotNo", "Plot No", b.plot?.unitNumber ?: b.plotNumber ?: b.plotNo))
        add(FieldSpec("propertyType", "Property Type", b.propertyType))
        add(FieldSpec("clientSource", "Client Source", b.clientSource, editable = false))
        add(FieldSpec("clientSourceName", "Source / Reference Name", b.clientSourceName, editable = false))
        add(FieldSpec("clientSourceMobile", "Source / Reference Mobile", b.clientSourceMobile, editable = false))
        add(FieldSpec("referralBenefit", "Referral Benefit", b.referralBenefit, editable = false))
        add(FieldSpec("isAgainstSV", "Is Against Site Visit?", yesNo(b.isAgainstSV), editable = false))
        if (b.isAgainstSV == true) {
            add(FieldSpec("svName", "SV Name", b.svName, editable = false))
            add(FieldSpec("svMobileNo", "SV Mobile No.", b.svMobileNo, editable = false))
        }
        add(FieldSpec("bookingMode", "Advance Booking Payment", b.bookingMode))
        add(FieldSpec("bookingCost", "Booking Cost", b.bookingCost?.toString(), numeric = true))
        add(FieldSpec("guidelineValue", "Guideline Value", b.guidelineValue?.toString(), numeric = true))
        add(FieldSpec("specialConsideration", "Special Consideration", b.specialConsideration?.toString(), numeric = true))
        if ((b.specialConsideration ?: 0.0) > 0.0) {
            add(FieldSpec("discountApprovedBy", "Discount Approved By", b.discountApprovedBy))
            add(FieldSpec("specialConsiderationReason", "SC Reason", b.specialConsiderationReason))
            add(FieldSpec("specialConsiderationValidity", "SC Validity Days", b.specialConsiderationValidity?.toString(), numeric = true))
        }
        add(FieldSpec("promotionalOffers", "Promotional Offers", b.promotionalOffers))
        add(FieldSpec("promotionalOffersTnC", "Promotional Offers T&C", b.promotionalOffersTnC))
        add(FieldSpec("promotionalOfferValue", "Promotional Offer Value", b.promotionalOfferValue?.toString(), numeric = true))
        add(FieldSpec("offerValidityPeriod", "Offer Validity Days", b.offerValidityPeriod?.toString(), numeric = true))
        add(FieldSpec("registrationCharges", "Registration Charges", b.registrationCharges?.toString(), numeric = true))
        add(FieldSpec("gstAmount", "GST Amount", b.gstAmount?.toString(), numeric = true))
        add(FieldSpec("documentCharges", "Document Charges", b.documentCharges?.toString(), numeric = true))
        add(FieldSpec("pattaCharges", "Patta Charges", b.pattaCharges?.toString(), numeric = true))
        add(FieldSpec("otherCharges", "Other Charges", b.otherCharges?.toString(), numeric = true))
        add(FieldSpec("customerPaymentCategory", "Customer Payment Category", b.customerPaymentCategory, editable = false))
        if (b.customerPaymentCategory == "B") {
            add(FieldSpec("loanAmountRequested", "Bank Loan Amount", b.loanAmountRequested?.toString(), numeric = true, editable = false))
        }
        add(FieldSpec("advanceAmount", "Advance Amount", b.advanceAmount?.toString(), numeric = true))
        add(FieldSpec("paymentMode", "Payment Mode", b.paymentMode))
        add(FieldSpec("advanceTransactionId", "Transaction ID", b.advanceTransactionId, editable = false))
        add(FieldSpec("advancePaymentProofFileName", "Payment Proof", b.advancePaymentProofFileName, editable = false))
        add(FieldSpec("advanceInstrumentNo", "Cheque / DD No.", b.advanceInstrumentNo, editable = false))
        add(FieldSpec("advanceBankName", "Bank", b.advanceBankName, editable = false))
        add(FieldSpec("advanceBankBranch", "Branch", b.advanceBankBranch, editable = false))
        add(FieldSpec("advanceInstrumentDate", "Instrument Date", b.advanceInstrumentDate, editable = false))
    }

    private fun paymentFields(b: Booking) = listOf(
        FieldSpec("paymentPlan", "Payment Plan", b.paymentPlan, editable = false),
        FieldSpec("allotmentDueAmount", "Allotment Due Amount", b.allotmentDueAmount?.toString(), numeric = true),
        FieldSpec("allotmentDueDate", "Allotment Due Date", b.allotmentDueDate),
        FieldSpec("secondPaymentAmount", "2nd Payment Amount", b.secondPaymentAmount?.toString(), numeric = true),
        FieldSpec("secondPaymentDate", "2nd Payment Date", b.secondPaymentDate),
        FieldSpec("thirdPaymentAmount", "3rd Payment Amount", b.thirdPaymentAmount?.toString(), numeric = true),
        FieldSpec("thirdPaymentDate", "3rd Payment Date", b.thirdPaymentDate),
        FieldSpec("fourthPaymentAmount", "4th Payment Amount", b.fourthPaymentAmount?.toString(), numeric = true),
        FieldSpec("fourthPaymentDate", "4th Payment Date", b.fourthPaymentDate),
        FieldSpec("preferredRegistrationDate", "Preferred Registration Date", b.preferredRegistrationDate),
        FieldSpec("aadhaar", "Aadhaar", b.aadhaar),
        FieldSpec("aadhaarDocumentFileName", "Aadhaar Upload", b.aadhaarDocumentFileName, editable = false),
        FieldSpec("pan", "PAN", b.pan),
        FieldSpec("panDocumentFileName", "PAN Upload", b.panDocumentFileName, editable = false),
        FieldSpec("referenceName1", "Reference Name 1", b.referenceName1),
        FieldSpec("referenceMobile1", "Reference Mobile 1", b.referenceMobile1),
        FieldSpec("referenceProfession1", "Reference Profession 1", b.referenceProfession1),
        FieldSpec("referenceName2", "Reference Name 2", b.referenceName2),
        FieldSpec("referenceMobile2", "Reference Mobile 2", b.referenceMobile2),
        FieldSpec("referenceProfession2", "Reference Profession 2", b.referenceProfession2),
        FieldSpec("docPreparedIn", "Document Prepared In", b.docPreparedIn),
    )

    private fun value(key: String): String = inputs[key]?.text?.toString()?.trim().orEmpty()
    private fun valueOrNull(key: String): String? = value(key).takeIf { it.isNotBlank() }
    private fun number(key: String): Double? = value(key).takeIf { it.isNotBlank() }?.toDoubleOrNull()

    private fun yesNo(value: Boolean?): String? = when (value) {
        true -> "Yes"
        false -> "No"
        null -> null
    }

    private fun labelStatus(status: String?): String =
        status?.replace("_", " ")?.replaceFirstChar { it.uppercase() } ?: "-"

    private fun labelStage(stage: String?): String =
        stage?.replace("_", " ")?.replaceFirstChar { it.uppercase() } ?: "-"

    private fun money(value: Double?): String {
        if (value == null || value <= 0.0) return "-"
        return try {
            NumberFormat.getCurrencyInstance(Locale.Builder().setLanguage("en").setRegion("IN").build())
                .apply { maximumFractionDigits = 0 }
                .format(value)
        } catch (_: Exception) {
            "Rs ${value.toLong()}"
        }
    }

    private fun showError(message: String) {
        progress.text = message
        progress.visibility = View.VISIBLE
        content.visibility = View.GONE
        footer.visibility = View.GONE
    }

    private fun actionButton(text: String, bg: String, fg: String): TextView =
        TextView(requireContext()).apply {
            this.text = text
            textSize = 13f
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = interFont(R.font.inter_semibold)
            setTextColor(Color.parseColor(fg))
            // Rounded pill background to match the New Booking form's
            // button language instead of the old flat rectangles.
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor(bg))
            }
            setPadding(dp(16), dp(8), dp(16), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(38),
            ).apply { marginStart = dp(8) }
        }

    /** Load a bundled Inter typeface; null-safe fallback to default. */
    private fun interFont(resId: Int): Typeface? =
        runCatching { ResourcesCompat.getFont(requireContext(), resId) }.getOrNull()

    /**
     * Leading icon for a field pill, mirroring the New Booking client
     * form (phone / person / calendar / mail / whatsapp / rupee). Keyed
     * off the field name so dates, money, and contact fields each get
     * the matching glyph; everything else falls back to the person icon.
     */
    private fun iconFor(key: String, numeric: Boolean): Int {
        val k = key.lowercase()
        return when {
            k == "whatsappnumber" -> R.drawable.ic_outcome_whatsapp
            k.contains("email") -> R.drawable.ic_outcome_mail
            k.contains("mobile") || k.contains("phone") || k.contains("number") ->
                R.drawable.ic_outcome_phone
            k.contains("date") || k.contains("dob") || k.contains("birth") ||
                k.contains("anniversary") || k.contains("validity") ->
                R.drawable.ic_outcome_calendar
            numeric -> R.drawable.ic_outcome_rupee
            else -> R.drawable.ic_outcome_person
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun SessionManager.hasPermissionForAnyApproval(): Boolean =
        hasPermission("marketing.bookings.approve.gm") ||
            hasPermission("marketing.bookings.approve.crm") ||
            hasPermission("marketing.bookings.approve.vp")

    companion object {
        const val RESULT_KEY = "booking_detail_result"
        private const val ARG_BOOKING_ID = "booking_id"

        fun newInstance(bookingId: String): BookingDetailBottomSheet =
            BookingDetailBottomSheet().apply {
                arguments = bundleOf(ARG_BOOKING_ID to bookingId)
            }
    }
}
