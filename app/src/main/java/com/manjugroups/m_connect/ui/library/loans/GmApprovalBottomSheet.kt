package com.manjugroups.m_connect.ui.library.loans

import android.app.Dialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.manjugroups.m_connect.network.WorkflowStepData
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.BottomSheetGmApprovalBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.ApproveLoanRequest
import com.manjugroups.m_connect.network.LoanData
import com.manjugroups.m_connect.network.RejectRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class GmApprovalBottomSheet(
    private val loan: LoanData,
    private val onAccepted: () -> Unit,
    private val onRejected: () -> Unit
) : BottomSheetDialogFragment() {

    // The currently-rendered workflow/advance steps (empty when the fixed
    // legacy tracker is shown). Used by the "tap the progress bar" detail popup.
    private var workflowSteps: List<WorkflowStepData> = emptyList()

    private var _binding: BottomSheetGmApprovalBinding? = null
    private val binding get() = _binding!!
    private val api = ApiService.create()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            bottomSheet?.setBackgroundColor(Color.TRANSPARENT)
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetGmApprovalBinding.inflate(inflater, container, false)
        return binding.root
    }

    /** True when this row is a salary advance, false for a regular loan.
     *  Drives the title/subtitle/toast copy so a single sheet works for both
     *  flows without splitting into two classes. */
    private val isAdvance: Boolean
        get() = loan.requestType?.trim()?.equals("salary_advance", ignoreCase = true) == true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (view.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)

        // Swap the header copy based on loan vs advance — same approval
        // mechanics, but the screen reads correctly in both contexts.
        binding.tvSheetTitle.text =
            if (isAdvance) "Requested Advance" else "Requested Loan"
        binding.tvSheetSubtitle.text =
            if (isAdvance) "Information about this salary advance"
            else "Information about this loan request"

        if (isAdvance) {
            // Salary advances are HR -> Accounts only. The 6-step
            // nominee/GM/AVP/HR/Accounts chain and the nominee-signature panel
            // both belong to the loan flow, so hide them and render the
            // 2-step chain via the same renderer the workflow path uses.
            binding.sheetNomineePanel.visibility = View.GONE
            renderWorkflowSteps(buildAdvanceSteps())
        } else {
            // For loans: the *configured Approval Workflow* (web admin) is the
            // source of truth. Hide the hardcoded 6-step tracker upfront so it
            // never flashes; load the workflow and render it. Only if the
            // backend confirms this loan has no workflow do we fall back to
            // the legacy fixed chain (for rows created before workflows existed).
            binding.sheetFixedTracker.visibility = View.GONE
            loadWorkflowTrackerOrFallback()
            loadNomineeSignatures()
        }

        // Set nominee labels with names if available
        binding.tvNominee1Label.text = loan.nominee1Name?.uppercase() ?: "NOMINEE 1"
        binding.tvNominee2Label.text = loan.nominee2Name?.uppercase() ?: "NOMINEE 2"

        binding.btnAccept.setOnClickListener {
            approveLoan()
        }

        binding.btnReject.setOnClickListener {
            rejectLoan()
        }

        binding.btnExpandNominee1Signature.setOnClickListener {
            showSignatureFullscreen(loan.nominee1ESignature, loan.nominee1Name ?: "Nominee 1")
        }
        binding.btnExpandNominee2Signature.setOnClickListener {
            showSignatureFullscreen(loan.nominee2ESignature, loan.nominee2Name ?: "Nominee 2")
        }
    }

    /** Open a nominee's e-signature full screen (the expand button on each box). */
    private fun showSignatureFullscreen(storageId: String?, who: String) {
        if (storageId.isNullOrBlank()) {
            Toast.makeText(requireContext(), "No signature available yet", Toast.LENGTH_SHORT).show()
            return
        }
        val density = resources.displayMetrics.density
        val image = ImageView(requireContext()).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            minimumHeight = (260 * density).toInt()
            setBackgroundColor(Color.WHITE)
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("$who — Signature")
            .setView(image)
            .setPositiveButton("Close", null)
            .create()
        dialog.show()
        val url = "${com.manjugroups.m_connect.BuildConfig.BASE_URL}api/storage/serve?storageId=$storageId"
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val bmp = withContext(Dispatchers.IO) {
                    val client = OkHttpClient()
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().body?.byteStream()?.let {
                        BitmapFactory.decodeStream(it)
                    }
                }
                if (bmp != null) image.setImageBitmap(bmp)
            }
        }
    }

    /** Pulls the `{error: "..."}` JSON the Convex HTTP routes return so the
     *  user sees the real reason ("You are not nominated as a guarantor on
     *  this loan", etc.) instead of an opaque "HTTP 500". */
    private fun extractHttpErrorMessage(e: Throwable): String? {
        val httpEx = e as? retrofit2.HttpException ?: return null
        val raw = runCatching { httpEx.response()?.errorBody()?.string() }.getOrNull()
            ?: return null
        val msg = runCatching {
            val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject
            obj.get("error")?.asString ?: obj.get("message")?.asString
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return msg.substringBefore('\n')
            .replace(Regex("^Uncaught \\w*Error:\\s*"), "")
            .trim()
            .ifBlank { null }
    }

    /** Build the 2-step (HR -> Accounts) tracker for salary advances. The
     *  backend's approvalStatus encodes how far the row has moved through the
     *  chain ("hr_pending", "account_pending", "approved", ...), so we derive
     *  each step's status from it without an extra fetch. */
    private fun buildAdvanceSteps(): List<WorkflowStepData> {
        val status = (loan.approvalStatus ?: loan.currentStage ?: "").lowercase().trim()
        val hrDone = listOf("account", "finance", "approved").any { status.contains(it) }
        val accountsDone = listOf("approved").any { status.contains(it) }
        val rejected = status.contains("rejected")

        fun stepStatus(done: Boolean): String = when {
            rejected -> "rejected"
            done -> "approved"
            else -> "pending"
        }

        return listOf(
            WorkflowStepData(
                stepOrder = 1,
                name = "HR",
                status = stepStatus(hrDone),
            ),
            WorkflowStepData(
                stepOrder = 2,
                name = "Accounts",
                status = stepStatus(accountsDone),
            ),
        )
    }

    private fun updateTrackerState(approvalStatus: String?) {
        val status = approvalStatus?.lowercase() ?: ""

        val n1Done = listOf("nominee_2", "gm", "avp", "vp", "hr", "account", "finance", "approved").any { status.contains(it) }
        val n2Done = listOf("gm", "avp", "vp", "hr", "account", "finance", "approved").any { status.contains(it) }
        val gmDone = listOf("avp", "vp", "hr", "account", "finance", "approved").any { status.contains(it) }
        val avpDone = listOf("hr", "account", "finance", "approved").any { status.contains(it) }
        val hrDone = listOf("account", "finance", "approved").any { status.contains(it) }

        fun setDone(frame: FrameLayout, icon: ImageView, text: TextView) {
            frame.setBackgroundResource(R.drawable.bg_loan_track_active)
            icon.setImageResource(R.drawable.ic_loan_track_check)
            text.setTextColor(Color.parseColor("#0B61CA"))
            text.setTypeface(null, Typeface.BOLD)
        }

        fun setPending(frame: FrameLayout, icon: ImageView, text: TextView, defaultIcon: Int) {
            frame.setBackgroundResource(R.drawable.bg_loan_track_pending)
            icon.setImageResource(defaultIcon)
            text.setTextColor(Color.parseColor("#98A2B3"))
            text.setTypeface(null, Typeface.NORMAL)
        }

        if (n1Done) setDone(binding.sheetTrackFrameN1, binding.sheetTrackIconN1, binding.sheetTrackTextN1)
        else setPending(binding.sheetTrackFrameN1, binding.sheetTrackIconN1, binding.sheetTrackTextN1, R.drawable.ic_track_shield)

        if (n2Done) setDone(binding.sheetTrackFrameN2, binding.sheetTrackIconN2, binding.sheetTrackTextN2)
        else setPending(binding.sheetTrackFrameN2, binding.sheetTrackIconN2, binding.sheetTrackTextN2, R.drawable.ic_track_shield)

        if (gmDone) setDone(binding.sheetTrackFrameGm, binding.sheetTrackIconGm, binding.sheetTrackTextGm)
        else setPending(binding.sheetTrackFrameGm, binding.sheetTrackIconGm, binding.sheetTrackTextGm, R.drawable.ic_track_gm)

        if (avpDone) setDone(binding.sheetTrackFrameAvp, binding.sheetTrackIconAvp, binding.sheetTrackTextAvp)
        else setPending(binding.sheetTrackFrameAvp, binding.sheetTrackIconAvp, binding.sheetTrackTextAvp, R.drawable.ic_track_avp)

        if (hrDone) setDone(binding.sheetTrackFrameHr, binding.sheetTrackIconHr, binding.sheetTrackTextHr)
        else setPending(binding.sheetTrackFrameHr, binding.sheetTrackIconHr, binding.sheetTrackTextHr, R.drawable.ic_track_hr)

        setPending(binding.sheetTrackFrameAccs, binding.sheetTrackIconAccs, binding.sheetTrackTextAccs, R.drawable.ic_track_accs)
    }

    private fun loadNomineeSignatures() {
        val token = SessionManager(requireContext()).bearerToken

        // Load Nominee 1 e-signature
        val sig1 = loan.nominee1ESignature
        if (!sig1.isNullOrBlank()) {
            loadSignatureImage(token, sig1, binding.ivNominee1Signature)
        }

        // Load Nominee 2 e-signature
        val sig2 = loan.nominee2ESignature
        if (!sig2.isNullOrBlank()) {
            loadSignatureImage(token, sig2, binding.ivNominee2Signature)
        }
    }

    private fun loadSignatureImage(token: String, storageId: String, target: ImageView) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                // Hit the public serve endpoint directly. /api/storage/get-url can
                // return an internal URL the device can't reach (the same issue the
                // web had), which left the signature preview blank.
                val url = "${com.manjugroups.m_connect.BuildConfig.BASE_URL}api/storage/serve?storageId=$storageId"
                val bitmap = withContext(Dispatchers.IO) {
                    val client = OkHttpClient()
                    val request = Request.Builder().url(url).build()
                    val response = client.newCall(request).execute()
                    val stream = response.body?.byteStream()
                    BitmapFactory.decodeStream(stream)
                }
                if (_binding != null && bitmap != null) {
                    target.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun approveLoan() {
        binding.btnAccept.isEnabled = false
        binding.btnAccept.text = "Approving..."
        val token = SessionManager(requireContext()).bearerToken

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val req = ApproveLoanRequest(id = loan.id!!)
                withContext(Dispatchers.IO) { api.approveLoan(token, req) }
                Toast.makeText(
                    requireContext(),
                    if (isAdvance) "Advance approved successfully" else "Loan approved successfully",
                    Toast.LENGTH_SHORT,
                ).show()
                onAccepted()
                dismiss()
            } catch (e: Exception) {
                binding.btnAccept.isEnabled = true
                binding.btnAccept.text = "Accept"
                val serverMsg = extractHttpErrorMessage(e)
                Toast.makeText(
                    requireContext(),
                    serverMsg ?: "Error: ${e.message ?: "network error"}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun rejectLoan() {
        binding.btnReject.isEnabled = false
        binding.btnReject.text = "Rejecting..."
        val token = SessionManager(requireContext()).bearerToken

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val req = RejectRequest(id = loan.id!!, reason = "Rejected by approver")
                withContext(Dispatchers.IO) { api.rejectLoan(token, req) }
                Toast.makeText(
                    requireContext(),
                    if (isAdvance) "Advance rejected" else "Loan rejected",
                    Toast.LENGTH_SHORT,
                ).show()
                onRejected()
                dismiss()
            } catch (e: Exception) {
                binding.btnReject.isEnabled = true
                binding.btnReject.text = "Reject"
                val serverMsg = extractHttpErrorMessage(e)
                Toast.makeText(
                    requireContext(),
                    serverMsg ?: "Error: ${e.message ?: "network error"}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /**
     * Fetch the *configured Approval Workflow* (web admin) for this loan and
     * render it as the authoritative chain. Only when the backend confirms
     * the loan has no workflow attached (legacy rows from before the feature)
     * do we fall back to the hardcoded nominee/GM/AVP/HR/Accounts chain — and
     * in that case the previously-hidden fixed tracker is brought back so the
     * user can still see and act on the request.
     */
    private fun loadWorkflowTrackerOrFallback() {
        val loanId = loan.id?.takeIf { it.isNotBlank() } ?: run {
            // No loan id — can't fetch; restore the legacy chain so the sheet
            // isn't blank.
            restoreLegacyFixedTracker()
            return
        }
        val token = SessionManager(requireContext()).bearerToken
        viewLifecycleOwner.lifecycleScope.launch {
            val steps = runCatching {
                withContext(Dispatchers.IO) { api.getLoanWorkflow(token, loanId) }
            }.getOrNull()
                ?.workflow
                ?.steps
                ?.filter { (it.stepOrder ?: 0) > 0 }
                ?.sortedBy { it.stepOrder ?: 0 }
                .orEmpty()
            if (_binding == null) return@launch
            if (steps.isNotEmpty()) {
                // The configured loan workflow only covers GM/AVP/HR/Accounts,
                // so prepend the nominee guarantor steps (otherwise the progress
                // bar omits them) and renumber the whole chain sequentially.
                val combined = (nomineeSteps() + steps)
                    .mapIndexed { i, s -> s.copy(stepOrder = i + 1) }
                renderWorkflowSteps(combined)
            } else {
                // Legacy row (no workflow configured). Show the hardcoded
                // chain so the approval can still proceed — backend's
                // dispatchLoanAction routes through the legacy stage code
                // for these rows.
                restoreLegacyFixedTracker()
            }
        }
    }

    /**
     * Nominee guarantor steps shown at the front of the workflow tracker. The
     * configured loan workflow only models GM/AVP/HR/Accounts, so without these
     * the progress bar wouldn't show the nominees' approval status at all.
     */
    private fun nomineeSteps(): List<WorkflowStepData> {
        fun st(s: String?): String {
            val v = s?.lowercase()?.trim().orEmpty()
            return when {
                v.contains("reject") || v.contains("declin") -> "rejected"
                v.contains("approv") || v.contains("sign") || v.contains("accept") || v.contains("done") -> "approved"
                else -> "pending"
            }
        }
        val out = mutableListOf<WorkflowStepData>()
        if (!loan.nominee1Name.isNullOrBlank() || !loan.nominee1Id.isNullOrBlank()) {
            out.add(
                WorkflowStepData(
                    name = "Nominee 1",
                    resolvedStaffName = loan.nominee1Name,
                    status = st(loan.nominee1Status),
                ),
            )
        }
        if (!loan.nominee2Name.isNullOrBlank() || !loan.nominee2Id.isNullOrBlank()) {
            out.add(
                WorkflowStepData(
                    name = "Nominee 2",
                    resolvedStaffName = loan.nominee2Name,
                    status = st(loan.nominee2Status),
                ),
            )
        }
        return out
    }

    /** Role-appropriate icon for a workflow step dot (shown instead of a number). */
    private fun iconForStep(step: WorkflowStepData): Int {
        val r = listOfNotNull(step.name, step.approverRole, step.approverDesignation)
            .joinToString(" ").lowercase()
        return when {
            r.contains("nominee") -> R.drawable.ic_track_shield
            r.contains("avp") || r.contains("vice president") -> R.drawable.ic_track_avp
            r.contains("gm") || r.contains("general manager") -> R.drawable.ic_track_gm
            r.contains("hr") || r.contains("human resource") -> R.drawable.ic_track_hr
            r.contains("account") -> R.drawable.ic_track_accs
            else -> R.drawable.ic_track_shield
        }
    }

    private fun restoreLegacyFixedTracker() {
        val b = _binding ?: return
        workflowSteps = emptyList()
        b.sheetFixedTracker.visibility = View.VISIBLE
        b.sheetWorkflowScroll.visibility = View.GONE
        updateTrackerState(loan.approvalStatus)
        // Tap the progress bar to see who approves next.
        b.sheetFixedTracker.isClickable = true
        b.sheetFixedTracker.setOnClickListener { showApprovalChainDialog() }
    }

    private fun renderWorkflowSteps(steps: List<WorkflowStepData>) {
        val b = _binding ?: return
        workflowSteps = steps
        b.sheetFixedTracker.visibility = View.GONE
        b.sheetWorkflowScroll.visibility = View.VISIBLE

        val container = b.sheetWorkflowContainer
        container.removeAllViews()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        steps.forEachIndexed { index, step ->
            val status = step.status?.lowercase()?.trim().orEmpty()
            val done = status == "approved" || status == "skipped"
            val rejected = status == "rejected"

            val item = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    dp(78), LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }

            val dotSize = dp(40)
            val dot = FrameLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize)
                setBackgroundResource(
                    if (done) R.drawable.bg_loan_track_active
                    else R.drawable.bg_loan_track_pending,
                )
            }
            if (done) {
                dot.addView(ImageView(requireContext()).apply {
                    setImageResource(R.drawable.ic_loan_track_check)
                    layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
                })
            } else {
                // Role icon (not a step number) for pending/rejected stages.
                dot.addView(ImageView(requireContext()).apply {
                    setImageResource(iconForStep(step))
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        if (rejected) Color.parseColor("#D92D20")
                        else Color.parseColor("#98A2B3"),
                    )
                    layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
                })
            }
            item.addView(dot)

            item.addView(TextView(requireContext()).apply {
                text = step.name?.takeIf { it.isNotBlank() }
                    ?: step.approverRole?.takeIf { it.isNotBlank() }
                    ?: step.approverDesignation?.takeIf { it.isNotBlank() }
                    ?: "Step ${step.stepOrder ?: (index + 1)}"
                setTextColor(
                    when {
                        done -> Color.parseColor("#0B61CA")
                        rejected -> Color.parseColor("#D92D20")
                        else -> Color.parseColor("#98A2B3")
                    },
                )
                textSize = 10f
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTypeface(null, if (done) Typeface.BOLD else Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) }
            })

            step.resolvedStaffName?.takeIf { it.isNotBlank() }?.let { who ->
                item.addView(TextView(requireContext()).apply {
                    text = who
                    setTextColor(Color.parseColor("#98A2B3"))
                    textSize = 9f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(2) }
                })
            }

            item.isClickable = true
            item.setOnClickListener { showApprovalChainDialog() }
            container.addView(item)
        }
        // Tapping anywhere on the tracker also opens the chain detail.
        container.isClickable = true
        container.setOnClickListener { showApprovalChainDialog() }
    }

    /**
     * "Who approves next" popup, opened by tapping the approval progress bar.
     * Lists every stage with its role, the assigned approver's name, and its
     * status, and calls out the next pending approver at the top. Works for the
     * dynamic workflow/advance tracker (uses each step's resolved approver) and
     * the legacy fixed tracker (derives the chain from approvalStatus + the
     * approver names stamped on the loan).
     */
    private fun showApprovalChainDialog() {
        if (_binding == null) return

        // role, person (nullable), state: "done" | "next" | "pending" | "rejected"
        val entries = mutableListOf<Triple<String, String?, String>>()

        if (workflowSteps.isNotEmpty()) {
            var nextTaken = false
            workflowSteps.forEachIndexed { i, step ->
                val s = step.status?.lowercase()?.trim().orEmpty()
                val role = step.name?.takeIf { it.isNotBlank() }
                    ?: step.approverRole?.takeIf { it.isNotBlank() }
                    ?: step.approverDesignation?.takeIf { it.isNotBlank() }
                    ?: "Step ${step.stepOrder ?: (i + 1)}"
                val state = when {
                    s == "rejected" -> "rejected"
                    s == "approved" || s == "skipped" -> "done"
                    !nextTaken -> { nextTaken = true; "next" }
                    else -> "pending"
                }
                entries.add(Triple(role, step.resolvedStaffName?.takeIf { it.isNotBlank() }, state))
            }
        } else {
            val status = (loan.approvalStatus ?: loan.currentStage ?: "").lowercase()
            fun has(vararg keys: String) = keys.any { status.contains(it) }
            val rejected = status.contains("rejected")
            val raw = listOf(
                Triple("Nominee 1", loan.nominee1Name, has("nominee_2", "gm", "avp", "vp", "hr", "account", "finance", "approved")),
                Triple("Nominee 2", loan.nominee2Name, has("gm", "avp", "vp", "hr", "account", "finance", "approved")),
                Triple("GM", loan.assignedGmName ?: loan.gmName, has("avp", "vp", "hr", "account", "finance", "approved")),
                Triple("AVP", loan.assignedAvpName ?: loan.avpName, has("hr", "account", "finance", "approved")),
                Triple("HR", "HR Team", has("account", "finance", "approved")),
                Triple("Accountant", loan.accountantName?.takeIf { it.isNotBlank() } ?: "Accounts Team", has("approved")),
            )
            var nextTaken = false
            raw.forEach { (role, person, done) ->
                val state = when {
                    done -> "done"
                    rejected -> "rejected"
                    !nextTaken -> { nextTaken = true; "next" }
                    else -> "pending"
                }
                entries.add(Triple(role, person?.takeIf { it.isNotBlank() }, state))
            }
        }

        val next = entries.firstOrNull { it.third == "next" }
        val sb = StringBuilder()
        if (next != null) {
            sb.append("Next to approve:  ")
                .append(next.second ?: "Unassigned")
                .append("  (").append(next.first).append(")\n\n")
        }
        entries.forEach { (role, person, state) ->
            val mark = when (state) {
                "done" -> "✓"      // check
                "next" -> "➜"      // arrow
                "rejected" -> "✕"  // cross
                else -> "•"        // bullet
            }
            val label = when (state) {
                "done" -> "Approved"
                "next" -> "Pending — next"
                "rejected" -> "Rejected"
                else -> "Pending"
            }
            sb.append(mark).append("  ").append(role)
            if (!person.isNullOrBlank()) sb.append("  —  ").append(person)
            sb.append("   ·  ").append(label).append('\n')
        }

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Approval progress")
            .setMessage(sb.toString().trim())
            .setPositiveButton("Close", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
