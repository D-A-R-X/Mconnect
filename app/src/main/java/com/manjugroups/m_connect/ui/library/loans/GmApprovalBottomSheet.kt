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
                val resp = withContext(Dispatchers.IO) { api.getStorageUrl(token, storageId) }
                val url = resp.url ?: return@runCatching
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
                renderWorkflowSteps(steps)
            } else {
                // Legacy row (no workflow configured). Show the hardcoded
                // chain so the approval can still proceed — backend's
                // dispatchLoanAction routes through the legacy stage code
                // for these rows.
                restoreLegacyFixedTracker()
            }
        }
    }

    private fun restoreLegacyFixedTracker() {
        val b = _binding ?: return
        b.sheetFixedTracker.visibility = View.VISIBLE
        b.sheetWorkflowScroll.visibility = View.GONE
        updateTrackerState(loan.approvalStatus)
    }

    private fun renderWorkflowSteps(steps: List<WorkflowStepData>) {
        val b = _binding ?: return
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
                dot.addView(TextView(requireContext()).apply {
                    text = (step.stepOrder ?: (index + 1)).toString()
                    setTextColor(
                        if (rejected) Color.parseColor("#D92D20")
                        else Color.parseColor("#98A2B3"),
                    )
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER,
                    )
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

            container.addView(item)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
