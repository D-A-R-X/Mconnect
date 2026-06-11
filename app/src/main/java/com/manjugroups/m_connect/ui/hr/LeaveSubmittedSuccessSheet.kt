package com.manjugroups.m_connect.ui.hr

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R

/**
 * Success modal shown after a leave is created. Matches Screen 7.
 */
class LeaveSubmittedSuccessSheet : BottomSheetDialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
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
    ): View = inflater.inflate(R.layout.dialog_leave_submitted_success, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<FrameLayout>(R.id.btnViewLeaveApproved).setOnClickListener {
            parentFragmentManager.setFragmentResult(RESULT_KEY, Bundle().apply { putBoolean(KEY_OK, true) })
            dismissAllowingStateLoss()
        }
    }

    companion object {
        const val RESULT_KEY = "leave_submitted_success_result"
        const val KEY_OK = "ok"

        fun newInstance(): LeaveSubmittedSuccessSheet = LeaveSubmittedSuccessSheet()
    }
}
