package com.manjugroups.m_connect.ui.home

import android.app.Dialog
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.MarkClientMetRequest
import com.manjugroups.m_connect.network.SetOutcomeRequest
import kotlinx.coroutines.launch

/**
 * KOS-37: collects clientMet + outcome (+ postpone reasons) for a CP visit at
 * arrival time. Caller (TripNavigationFragment) shows it after OTP verify on a
 * tripType=client_place trip; on success the sheet POSTs markClientMet and
 * setOutcome, then signals the host to call completeVisit.
 */
class CompleteCpVisitBottomSheet : BottomSheetDialogFragment() {

    private val geoApi = GeoTrackApi.create()
    private lateinit var session: SessionManager

    private var clientMet: Boolean? = null
    private var selectedOutcome: String? = null
    private val selectedPostponeReasons = linkedSetOf<String>()

    private var btnYes: TextView? = null
    private var btnNo: TextView? = null
    private var etReason: EditText? = null
    private var outcomeRow: LinearLayout? = null
    private var postponeLabel: TextView? = null
    private var postponeScroll: View? = null
    private var postponeRow: LinearLayout? = null
    private var errorText: TextView? = null
    private var submitBtn: TextView? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                isCancelable = false
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_cp_visit_complete, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        btnYes = view.findViewById(R.id.btnCpClientMetYes)
        btnNo = view.findViewById(R.id.btnCpClientMetNo)
        etReason = view.findViewById(R.id.etCpClientNoShowReason)
        outcomeRow = view.findViewById(R.id.outcomeChipRow)
        postponeLabel = view.findViewById(R.id.tvPostponeReasonsLabel)
        postponeScroll = view.findViewById(R.id.postponeReasonScroll)
        postponeRow = view.findViewById(R.id.postponeReasonRow)
        errorText = view.findViewById(R.id.tvCpError)
        submitBtn = view.findViewById(R.id.btnCpSubmit)

        btnYes?.setOnClickListener { setClientMet(true) }
        btnNo?.setOnClickListener { setClientMet(false) }

        renderOutcomeChips()
        renderPostponeReasonChips()

        submitBtn?.setOnClickListener { onSubmit() }
    }

    private fun setClientMet(met: Boolean) {
        clientMet = met
        applyChipState(btnYes, met)
        applyChipState(btnNo, !met)
        etReason?.visibility = if (!met) View.VISIBLE else View.GONE
    }

    private fun renderOutcomeChips() {
        val row = outcomeRow ?: return
        row.removeAllViews()
        OUTCOME_OPTIONS.forEach { (label, value) ->
            val chip = makeChip(label) { setOutcome(value) }
            applyChipState(chip, selectedOutcome == value)
            chip.tag = value
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.marginEnd = dp(10)
            row.addView(chip, params)
        }
    }

    private fun renderPostponeReasonChips() {
        val row = postponeRow ?: return
        row.removeAllViews()
        POSTPONE_REASON_OPTIONS.forEach { reason ->
            val chip = makeChip(reason) { togglePostponeReason(reason) }
            applyChipState(chip, selectedPostponeReasons.contains(reason))
            chip.tag = reason
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.marginEnd = dp(10)
            row.addView(chip, params)
        }
    }

    private fun setOutcome(outcome: String) {
        selectedOutcome = outcome
        outcomeRow?.let { row ->
            for (i in 0 until row.childCount) {
                val v = row.getChildAt(i) as? TextView ?: continue
                applyChipState(v, v.tag == outcome)
            }
        }
        val isPostpone = outcome == OUTCOME_POSTPONED
        postponeLabel?.visibility = if (isPostpone) View.VISIBLE else View.GONE
        postponeScroll?.visibility = if (isPostpone) View.VISIBLE else View.GONE
        if (!isPostpone) selectedPostponeReasons.clear()
    }

    private fun togglePostponeReason(reason: String) {
        if (!selectedPostponeReasons.add(reason)) selectedPostponeReasons.remove(reason)
        postponeRow?.let { row ->
            for (i in 0 until row.childCount) {
                val v = row.getChildAt(i) as? TextView ?: continue
                applyChipState(v, selectedPostponeReasons.contains(v.tag as? String ?: ""))
            }
        }
    }

    private fun makeChip(label: String, onClick: () -> Unit): TextView {
        val tv = TextView(requireContext())
        tv.text = label
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        tv.setPadding(dp(16), dp(10), dp(16), dp(10))
        tv.isClickable = true
        tv.isFocusable = true
        tv.setOnClickListener { onClick() }
        return tv
    }

    private fun applyChipState(view: TextView?, active: Boolean) {
        val v = view ?: return
        v.setBackgroundResource(if (active) R.drawable.bg_chip_active else R.drawable.bg_chip_inactive)
        v.setTextColor(
            if (active) android.graphics.Color.WHITE
            else android.graphics.Color.parseColor("#1D2939")
        )
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun showError(msg: String) {
        errorText?.text = msg
        errorText?.visibility = View.VISIBLE
    }

    private fun clearError() {
        errorText?.visibility = View.GONE
    }

    private fun onSubmit() {
        clearError()
        val cpVisitId = arguments?.getString(ARG_CP_VISIT_ID)
            ?: return showError("Missing CP visit id")
        val met = clientMet ?: return showError("Please record whether you met the client")
        val reason = etReason?.text?.toString()?.trim().orEmpty()
        if (!met && reason.isEmpty()) return showError("Please add a reason for not meeting the client")
        val outcome = selectedOutcome ?: return showError("Please pick an outcome")
        if (outcome == OUTCOME_POSTPONED && selectedPostponeReasons.isEmpty()) {
            return showError("Pick at least one postpone reason")
        }

        submitBtn?.isClickable = false
        submitBtn?.text = "Saving…"

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val metResp = geoApi.markClientMet(
                    session.bearerToken,
                    MarkClientMetRequest(
                        id = cpVisitId,
                        clientMet = met,
                        clientNoShowReason = reason.takeIf { !met }
                    )
                )
                if (!metResp.success) {
                    submitBtn?.isClickable = true
                    submitBtn?.text = "Save and complete"
                    showError(metResp.error ?: "Failed to record client met")
                    return@launch
                }

                val outcomeResp = geoApi.setCpVisitOutcome(
                    session.bearerToken,
                    SetOutcomeRequest(
                        id = cpVisitId,
                        outcome = outcome,
                        postponeReasons = if (outcome == OUTCOME_POSTPONED) selectedPostponeReasons.toList() else null
                    )
                )
                if (!outcomeResp.success) {
                    submitBtn?.isClickable = true
                    submitBtn?.text = "Save and complete"
                    showError(outcomeResp.error ?: "Failed to set outcome")
                    return@launch
                }

                setFragmentResult(
                    RESULT_KEY,
                    bundleOf(
                        KEY_CLIENT_MET to met,
                        KEY_OUTCOME to outcome
                    )
                )
                dismissAllowingStateLoss()
            } catch (e: Exception) {
                submitBtn?.isClickable = true
                submitBtn?.text = "Save and complete"
                showError(e.message ?: "Network error")
                Toast.makeText(requireContext(), e.message ?: "Network error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val RESULT_KEY = "cp_visit_complete_result"
        const val KEY_CLIENT_MET = "clientMet"
        const val KEY_OUTCOME = "outcome"
        private const val ARG_CP_VISIT_ID = "arg_cp_visit_id"

        // Mapping from PRD §10 Phase B labels to backend outcomeValidator literals.
        private const val OUTCOME_BOOKING = "converted_to_booking"
        private const val OUTCOME_POSTPONED = "postponed"
        private const val OUTCOME_NOT_INTERESTED = "not_interested"
        private const val OUTCOME_WAIT = "interested"

        private val OUTCOME_OPTIONS = listOf(
            "Booking" to OUTCOME_BOOKING,
            "Postpone" to OUTCOME_POSTPONED,
            "Not Interested" to OUTCOME_NOT_INTERESTED,
            "Wait" to OUTCOME_WAIT,
        )

        private val POSTPONE_REASON_OPTIONS = listOf("Budget", "Timing", "Project", "Other")

        fun newInstance(cpVisitId: String): CompleteCpVisitBottomSheet =
            CompleteCpVisitBottomSheet().apply {
                arguments = Bundle().apply { putString(ARG_CP_VISIT_ID, cpVisitId) }
            }
    }
}
