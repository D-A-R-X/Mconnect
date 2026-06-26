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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (view.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)

        updateTrackerState(loan.approvalStatus)
        // For workflow-routed loans/advances, swap the fixed nominee/GM/AVP/HR
        // tracker for the configured workflow chain once the steps load.
        loadWorkflowTracker()
        loadNomineeSignatures()

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
                Toast.makeText(requireContext(), "Loan approved successfully", Toast.LENGTH_SHORT).show()
                onAccepted()
                dismiss()
            } catch (e: Exception) {
                binding.btnAccept.isEnabled = true
                binding.btnAccept.text = "Accept"
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(requireContext(), "Loan rejected", Toast.LENGTH_SHORT).show()
                onRejected()
                dismiss()
            } catch (e: Exception) {
                binding.btnReject.isEnabled = true
                binding.btnReject.text = "Reject"
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Fetch the configured Approval Workflow for this loan/advance and, when
     * the row is workflow-routed, replace the fixed nominee/GM/AVP/HR/Accounts
     * tracker with the real configured chain. Legacy code-stage rows return no
     * workflow and keep the fixed tracker.
     */
    private fun loadWorkflowTracker() {
        val loanId = loan.id?.takeIf { it.isNotBlank() } ?: return
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
            if (_binding == null || steps.isEmpty()) return@launch
            renderWorkflowSteps(steps)
        }
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
