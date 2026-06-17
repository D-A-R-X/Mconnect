package com.manjugroups.m_connect.ui.home

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R

/**
 * Remarks-entry popup for the Old Client CP flow. Surfaces AFTER the
 * staff has captured the arrival photo + verified the OTP on the Yes
 * path. On submit, the typed remarks are returned via FragmentResult
 * for the host to persist as the CP visit's `notes` field alongside
 * `outcome="old_client_visited"`.
 */
class OldClientRemarksBottomSheet : BottomSheetDialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        // ADJUST_RESIZE keeps the focused EditText visible above the
        // soft keyboard. Without it the keyboard overlays the lower
        // half of the sheet and the Submit button ends up hidden.
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
        // Capture-not-skippable: dismissing without a Submit reads as
        // "abandoned" and the parent flow surfaces a retry message.
        // isCancelable stays true so the user can back out and retry.
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_old_client_remarks, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val etRemarks = view.findViewById<EditText>(R.id.etRemarks)
        val btnCancel = view.findViewById<Button>(R.id.btnCancelRemarks)
        val btnSubmit = view.findViewById<Button>(R.id.btnSubmitRemarks)

        btnCancel.setOnClickListener {
            setFragmentResult(RESULT_KEY, bundleOf(KEY_SUBMITTED to false))
            dismissAllowingStateLoss()
        }

        btnSubmit.setOnClickListener {
            val remarks = etRemarks.text?.toString()?.trim().orEmpty()
            if (remarks.isBlank()) {
                Toast.makeText(
                    requireContext(),
                    "Please add at least a short remark before submitting.",
                    Toast.LENGTH_SHORT,
                ).show()
                return@setOnClickListener
            }
            setFragmentResult(
                RESULT_KEY,
                bundleOf(
                    KEY_SUBMITTED to true,
                    KEY_REMARKS to remarks,
                ),
            )
            dismissAllowingStateLoss()
        }
    }

    override fun onCancel(dialog: android.content.DialogInterface) {
        super.onCancel(dialog)
        setFragmentResult(RESULT_KEY, bundleOf(KEY_SUBMITTED to false))
    }

    companion object {
        const val RESULT_KEY = "old_client_remarks_result"
        const val KEY_SUBMITTED = "submitted"
        const val KEY_REMARKS = "remarks"

        fun newInstance(): OldClientRemarksBottomSheet = OldClientRemarksBottomSheet()
    }
}
