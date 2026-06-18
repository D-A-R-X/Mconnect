package com.manjugroups.m_connect.ui.library.loans

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R

class LoanDeskUploadBottomSheet : BottomSheetDialogFragment() {

    private var onSubmitted: (() -> Unit)? = null

    // Track upload states for the 4 documents
    private var isDoc1Uploaded = false
    private var isDoc2Uploaded = false
    private var isDoc3Uploaded = false
    private var isDoc4Uploaded = false

    fun setOnSubmittedListener(listener: () -> Unit) {
        this.onSubmitted = listener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.setOnShowListener { dialogInterface ->
            val bottomSheetDialog = dialogInterface as BottomSheetDialog
            val bottomSheet = bottomSheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                it.setBackgroundResource(R.drawable.bg_auth_sheet)
                androidx.core.view.ViewCompat.setElevation(it, 0f)
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_loan_desk_upload, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnDoc1 = view.findViewById<View>(R.id.btnUploadDoc1)
        val tvStatus1 = view.findViewById<TextView>(R.id.tvDocStatus1)
        val ivCheck1 = view.findViewById<ImageView>(R.id.ivDocCheck1)

        val btnDoc2 = view.findViewById<View>(R.id.btnUploadDoc2)
        val tvStatus2 = view.findViewById<TextView>(R.id.tvDocStatus2)
        val ivCheck2 = view.findViewById<ImageView>(R.id.ivDocCheck2)

        val btnDoc3 = view.findViewById<View>(R.id.btnUploadDoc3)
        val tvStatus3 = view.findViewById<TextView>(R.id.tvDocStatus3)
        val ivCheck3 = view.findViewById<ImageView>(R.id.ivDocCheck3)

        val btnDoc4 = view.findViewById<View>(R.id.btnUploadDoc4)
        val tvStatus4 = view.findViewById<TextView>(R.id.tvDocStatus4)
        val ivCheck4 = view.findViewById<ImageView>(R.id.ivDocCheck4)

        val btnSubmit = view.findViewById<TextView>(R.id.btnSubmitUploads)

        fun updateSubmitButtonState() {
            val allUploaded = isDoc1Uploaded && isDoc2Uploaded && isDoc3Uploaded && isDoc4Uploaded
            btnSubmit.isEnabled = allUploaded
            btnSubmit.alpha = if (allUploaded) 1.0f else 0.5f
        }

        btnDoc1.setOnClickListener {
            isDoc1Uploaded = true
            tvStatus1.text = "pan_card_doc.pdf"
            tvStatus1.setTextColor(android.graphics.Color.parseColor("#101828"))
            ivCheck1.visibility = View.VISIBLE
            updateSubmitButtonState()
        }

        btnDoc2.setOnClickListener {
            isDoc2Uploaded = true
            tvStatus2.text = "aadhaar_card_doc.pdf"
            tvStatus2.setTextColor(android.graphics.Color.parseColor("#101828"))
            ivCheck2.visibility = View.VISIBLE
            updateSubmitButtonState()
        }

        btnDoc3.setOnClickListener {
            isDoc3Uploaded = true
            tvStatus3.text = "bank_statement.pdf"
            tvStatus3.setTextColor(android.graphics.Color.parseColor("#101828"))
            ivCheck3.visibility = View.VISIBLE
            updateSubmitButtonState()
        }

        btnDoc4.setOnClickListener {
            isDoc4Uploaded = true
            tvStatus4.text = "pay_slip.pdf"
            tvStatus4.setTextColor(android.graphics.Color.parseColor("#101828"))
            ivCheck4.visibility = View.VISIBLE
            updateSubmitButtonState()
        }

        btnSubmit.setOnClickListener {
            Toast.makeText(requireContext(), "Documents submitted successfully", Toast.LENGTH_SHORT).show()
            onSubmitted?.invoke()
            dismiss()
        }
    }

    companion object {
        fun newInstance(onSubmitted: () -> Unit): LoanDeskUploadBottomSheet {
            return LoanDeskUploadBottomSheet().apply {
                setOnSubmittedListener(onSubmitted)
            }
        }
    }
}
