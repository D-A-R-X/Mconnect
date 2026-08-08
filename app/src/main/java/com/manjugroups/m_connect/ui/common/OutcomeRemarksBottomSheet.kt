package com.manjugroups.m_connect.ui.common

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R

/** Required remarks input shared by terminal visit outcomes such as Others. */
class OutcomeRemarksBottomSheet : BottomSheetDialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return BottomSheetDialog(requireContext(), theme).apply {
            window?.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            )
            setOnShowListener { shown ->
                (shown as BottomSheetDialog)
                    .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                    ?.let { sheet ->
                        sheet.setBackgroundColor(Color.TRANSPARENT)
                        BottomSheetBehavior.from(sheet).apply {
                            state = BottomSheetBehavior.STATE_EXPANDED
                            skipCollapsed = true
                        }
                    }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_old_client_remarks, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.tvRemarksTitle).text =
            arguments?.getString(ARG_TITLE) ?: "Other outcome"
        view.findViewById<TextView>(R.id.tvRemarksSubtitle).text =
            arguments?.getString(ARG_SUBTITLE)
                ?: "Add remarks before closing this visit."

        val remarks = view.findViewById<EditText>(R.id.etRemarks).apply {
            hint = arguments?.getString(ARG_HINT) ?: "Enter what happened with the client"
        }
        view.findViewById<Button>(R.id.btnCancelRemarks).setOnClickListener {
            sendResult(submitted = false)
            dismissAllowingStateLoss()
        }
        view.findViewById<Button>(R.id.btnSubmitRemarks).setOnClickListener {
            val value = remarks.text?.toString()?.trim().orEmpty()
            if (value.isBlank()) {
                Toast.makeText(
                    requireContext(),
                    "Remarks are required to close the visit.",
                    Toast.LENGTH_SHORT,
                ).show()
                return@setOnClickListener
            }
            sendResult(submitted = true, remarks = value)
            dismissAllowingStateLoss()
        }
    }

    override fun onCancel(dialog: android.content.DialogInterface) {
        sendResult(submitted = false)
        super.onCancel(dialog)
    }

    private fun sendResult(submitted: Boolean, remarks: String? = null) {
        setFragmentResult(
            RESULT_KEY,
            bundleOf(KEY_SUBMITTED to submitted, KEY_REMARKS to remarks),
        )
    }

    companion object {
        const val RESULT_KEY = "outcome_remarks_result"
        const val KEY_SUBMITTED = "submitted"
        const val KEY_REMARKS = "remarks"
        private const val ARG_TITLE = "title"
        private const val ARG_SUBTITLE = "subtitle"
        private const val ARG_HINT = "hint"

        fun newInstance(
            title: String,
            subtitle: String,
            hint: String,
        ) = OutcomeRemarksBottomSheet().apply {
            arguments = bundleOf(
                ARG_TITLE to title,
                ARG_SUBTITLE to subtitle,
                ARG_HINT to hint,
            )
        }
    }
}
