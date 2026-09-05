package com.manjugroups.m_connect.ui.home

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.SetOutcomeRequest
import kotlinx.coroutines.launch

/**
 * RejectReasonBottomSheet — sub-flow opened from
 * [CompleteCpVisitBottomSheet] when the field staff taps "Reject It"
 * on a locked SV-cum-CP. Captures a free-text rejection reason and
 * submits the atomic `setCpVisitOutcome(rejected)` mutation with the
 * reason in outcome notes. The server closes all linked visit/task records
 * and creates the follow-up call before returning success.
 *
 * Result contract (mirrors CompleteCpVisitBottomSheet so the trip nav
 * doesn't need to know which surface produced the rejection):
 *   RESULT_KEY → bundle {
 *     KEY_CLIENT_MET: Boolean = true,
 *     KEY_OUTCOME:    String  = "rejected",
 *     KEY_REASON:     String  = the captured text,
 *   }
 *
 * On success the parent sheet ([CompleteCpVisitBottomSheet]) listens
 * on its own RESULT_KEY too, so we forward both — first to ourselves
 * via this fragment's key (for any future caller that wants the
 * reason text), then to the outer sheet's key so the upstream
 * TripNavigationFragment flow continues unchanged.
 */
class RejectReasonBottomSheet : BottomSheetDialogFragment() {

    private val geoApi = GeoTrackApi.create()
    private lateinit var session: SessionManager

    private var etReason: EditText? = null
    private var btnSubmit: LinearLayout? = null
    private var tvSubmitLabel: TextView? = null
    private var tvError: TextView? = null
    private var resultSent = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        // Same IME / drag handling we use on the main CP outcome sheet —
        // resize on keyboard, disable drag so a swipe inside the
        // textarea doesn't dismiss accidentally.
        dialog.window?.let { window ->
            window.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
        }
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.isDraggable = false
                isCancelable = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.dialog_cp_reject_reason, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        etReason = view.findViewById(R.id.etRejectReason)
        btnSubmit = view.findViewById(R.id.btnRejectSubmit)
        tvSubmitLabel = view.findViewById(R.id.tvRejectSubmitLabel)
        tvError = view.findViewById(R.id.tvRejectError)

        btnSubmit?.setOnClickListener { onSubmitTap() }
    }

    private fun onSubmitTap() {
        clearError()
        val reason = etReason?.text?.toString()?.trim().orEmpty()
        if (reason.isEmpty()) {
            showError("Please share a reason for the rejection")
            return
        }
        val cpVisitId = arguments?.getString(ARG_CP_VISIT_ID).orEmpty()
        if (cpVisitId.isBlank()) {
            showError("Missing CP visit id")
            return
        }

        // Lock the submit button while the network calls run so a
        // double-tap doesn't fire two rejections back to back.
        btnSubmit?.isClickable = false
        tvSubmitLabel?.text = "Submitting…"

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val outcomeResp = geoApi.setCpVisitOutcome(
                    session.bearerToken,
                    SetOutcomeRequest(
                        id = cpVisitId,
                        outcome = OUTCOME_REJECTED,
                        notes = reason,
                    ),
                )
                if (!outcomeResp.success) {
                    finishWithError(outcomeResp.error ?: "Failed to record rejection")
                    return@launch
                }
                if (outcomeResp.status != OUTCOME_REJECTED || outcomeResp.followUpTaskId.isNullOrBlank()) {
                    finishWithError("Rejection was not confirmed with a follow-up task. Please retry.")
                    return@launch
                }
                // Single result keyed by THIS sheet — the parent sheet
                // (CompleteCpVisitBottomSheet) listens here, then itself
                // re-broadcasts on its own RESULT_KEY for the trip nav.
                // Keeping the keys distinct avoids any re-broadcast
                // loop on a shared key.
                val payload = bundleOf(
                    KEY_SUBMITTED to true,
                    KEY_CLIENT_MET to true,
                    KEY_OUTCOME to OUTCOME_REJECTED,
                    KEY_REASON to reason,
                )
                resultSent = true
                setFragmentResult(RESULT_KEY, payload)
                dismissAllowingStateLoss()
            } catch (e: Exception) {
                finishWithError(e.message ?: "Network error")
            }
        }
    }

    private fun finishWithError(message: String) {
        btnSubmit?.isClickable = true
        tvSubmitLabel?.text = "Submit Now"
        showError(message)
    }

    private fun showError(message: String) {
        tvError?.text = message
        tvError?.visibility = View.VISIBLE
    }

    private fun clearError() {
        tvError?.text = ""
        tvError?.visibility = View.GONE
    }

    override fun onCancel(dialog: android.content.DialogInterface) {
        if (!resultSent) {
            resultSent = true
            setFragmentResult(RESULT_KEY, bundleOf(KEY_SUBMITTED to false))
        }
        super.onCancel(dialog)
    }

    companion object {
        const val RESULT_KEY = "cp_visit_reject_reason_result"
        const val KEY_SUBMITTED = "submitted"
        const val KEY_CLIENT_MET = "clientMet"
        const val KEY_OUTCOME = "outcome"
        const val KEY_REASON = "reason"
        private const val ARG_CP_VISIT_ID = "arg_cp_visit_id"
        // SV-cum-CP rejection lands as outcome=rejected (a dedicated
        // value distinct from not_interested) so back-office surfaces
        // can show a "Rejected" pill alongside the captured reason in
        // notes. Convex outcomeValidator was extended in lockstep.
        private const val OUTCOME_REJECTED = "rejected"

        fun newInstance(cpVisitId: String): RejectReasonBottomSheet =
            RejectReasonBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_CP_VISIT_ID, cpVisitId)
                }
            }
    }
}
