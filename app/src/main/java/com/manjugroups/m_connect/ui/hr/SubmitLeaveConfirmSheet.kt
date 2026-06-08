package com.manjugroups.m_connect.ui.hr

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.setFragmentResult
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R

/**
 * "Submit Leave" double-check confirmation modal.
 *
 * Pops up after the user taps Submit Now on [ApplyLeaveFragment] so
 * they can review their details one last time before firing the
 * mutation. Mirrors the design's third frame in the apply flow —
 * blue layer chip, title, subtitle, and two stacked CTAs.
 *
 * Communicates via [setFragmentResult] on [RESULT_KEY] with a single
 * boolean [KEY_CONFIRMED]:
 *   - true  → Yes, Submit  (caller fires applyLeave)
 *   - false → No, Let me check  (caller just dismisses the sheet)
 *
 * Owns no submit logic of its own — keeps the responsibility on the
 * caller so the existing ViewModel-based applyLeave() path is reused
 * unchanged.
 */
class SubmitLeaveConfirmSheet : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_submit_leave_confirm, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.btnSubmitLeaveConfirm).setOnClickListener {
            setFragmentResult(RESULT_KEY, Bundle().apply { putBoolean(KEY_CONFIRMED, true) })
            dismissAllowingStateLoss()
        }
        view.findViewById<TextView>(R.id.btnSubmitLeaveCancel).setOnClickListener {
            setFragmentResult(RESULT_KEY, Bundle().apply { putBoolean(KEY_CONFIRMED, false) })
            dismissAllowingStateLoss()
        }
    }

    companion object {
        const val RESULT_KEY = "submit_leave_confirm_result"
        const val KEY_CONFIRMED = "confirmed"

        fun newInstance(): SubmitLeaveConfirmSheet = SubmitLeaveConfirmSheet()
    }
}
