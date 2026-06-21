package com.manjugroups.m_connect.ui.library.loans

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.setFragmentResult
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.manjugroups.m_connect.R

class LoanDeskRejectBottomSheet : BottomSheetDialogFragment() {

    private var itemId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        itemId = arguments?.getString("extra_item_id").orEmpty()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.setBackgroundResource(R.drawable.bg_bottom_sheet)
                androidx.core.view.ViewCompat.setElevation(it, 0f)
                val behavior = BottomSheetBehavior.from(it)
                val metrics = resources.displayMetrics
                val peekH = (metrics.heightPixels * 0.45f).toInt()
                behavior.peekHeight = peekH
                behavior.state = BottomSheetBehavior.STATE_COLLAPSED
                behavior.skipCollapsed = false
            }
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_loan_desk_reject, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val etRemarks = view.findViewById<TextInputEditText>(R.id.etRemarks)
        val btnSubmitReject = view.findViewById<MaterialButton>(R.id.btnSubmitReject)

        btnSubmitReject.setOnClickListener {
            val remarks = etRemarks.text?.toString()?.trim().orEmpty()
            if (remarks.isEmpty()) {
                etRemarks.error = "Remarks are required to reject"
                return@setOnClickListener
            }

            val bundle = Bundle().apply {
                putString("itemId", itemId)
                putString("remarks", remarks)
            }
            setFragmentResult(RESULT_KEY, bundle)
            dismissAllowingStateLoss()
        }
    }

    companion object {
        const val RESULT_KEY = "LoanDeskRejected"
        fun newInstance(itemId: String) = LoanDeskRejectBottomSheet().apply {
            arguments = Bundle().apply {
                putString("extra_item_id", itemId)
            }
        }
    }
}
