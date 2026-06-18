package com.manjugroups.m_connect.ui.library.loans

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

        // Bind Doc 1 Views (PAN Card)
        val btnCamera1 = view.findViewById<View>(R.id.btnCamera1)
        val layoutUnuploaded1 = view.findViewById<View>(R.id.layoutUnuploaded1)
        val layoutUploaded1 = view.findViewById<View>(R.id.layoutUploaded1)
        val tvUploadedName1 = view.findViewById<TextView>(R.id.tvUploadedName1)

        // Bind Doc 2 Views (Aadhaar Card)
        val btnCamera2 = view.findViewById<View>(R.id.btnCamera2)
        val layoutUnuploaded2 = view.findViewById<View>(R.id.layoutUnuploaded2)
        val layoutUploaded2 = view.findViewById<View>(R.id.layoutUploaded2)
        val tvUploadedName2 = view.findViewById<TextView>(R.id.tvUploadedName2)

        // Bind Doc 3 Views (Bank Statement)
        val btnCamera3 = view.findViewById<View>(R.id.btnCamera3)
        val layoutUnuploaded3 = view.findViewById<View>(R.id.layoutUnuploaded3)
        val layoutUploaded3 = view.findViewById<View>(R.id.layoutUploaded3)
        val tvUploadedName3 = view.findViewById<TextView>(R.id.tvUploadedName3)

        // Bind Doc 4 Views (IT / Pay Slip)
        val btnCamera4 = view.findViewById<View>(R.id.btnCamera4)
        val layoutUnuploaded4 = view.findViewById<View>(R.id.layoutUnuploaded4)
        val layoutUploaded4 = view.findViewById<View>(R.id.layoutUploaded4)
        val tvUploadedName4 = view.findViewById<TextView>(R.id.tvUploadedName4)

        val btnSubmit = view.findViewById<TextView>(R.id.btnSubmitUploads)

        fun updateSubmitButtonState() {
            val allUploaded = isDoc1Uploaded && isDoc2Uploaded && isDoc3Uploaded && isDoc4Uploaded
            btnSubmit.isEnabled = allUploaded
            btnSubmit.alpha = if (allUploaded) 1.0f else 0.5f
        }

        // --- Doc 1 Action Listeners ---
        val triggerDoc1Upload = View.OnClickListener {
            isDoc1Uploaded = true
            tvUploadedName1.text = "pan_card_doc.pdf"
            layoutUnuploaded1.visibility = View.GONE
            layoutUploaded1.visibility = View.VISIBLE
            updateSubmitButtonState()
        }
        btnCamera1.setOnClickListener(triggerDoc1Upload)
        layoutUnuploaded1.setOnClickListener(triggerDoc1Upload)
        layoutUploaded1.setOnClickListener {
            isDoc1Uploaded = false
            layoutUploaded1.visibility = View.GONE
            layoutUnuploaded1.visibility = View.VISIBLE
            updateSubmitButtonState()
        }

        // --- Doc 2 Action Listeners ---
        val triggerDoc2Upload = View.OnClickListener {
            isDoc2Uploaded = true
            tvUploadedName2.text = "aadhaar_card_doc.pdf"
            layoutUnuploaded2.visibility = View.GONE
            layoutUploaded2.visibility = View.VISIBLE
            updateSubmitButtonState()
        }
        btnCamera2.setOnClickListener(triggerDoc2Upload)
        layoutUnuploaded2.setOnClickListener(triggerDoc2Upload)
        layoutUploaded2.setOnClickListener {
            isDoc2Uploaded = false
            layoutUploaded2.visibility = View.GONE
            layoutUnuploaded2.visibility = View.VISIBLE
            updateSubmitButtonState()
        }

        // --- Doc 3 Action Listeners ---
        val triggerDoc3Upload = View.OnClickListener {
            isDoc3Uploaded = true
            tvUploadedName3.text = "bank_statement.pdf"
            layoutUnuploaded3.visibility = View.GONE
            layoutUploaded3.visibility = View.VISIBLE
            updateSubmitButtonState()
        }
        btnCamera3.setOnClickListener(triggerDoc3Upload)
        layoutUnuploaded3.setOnClickListener(triggerDoc3Upload)
        layoutUploaded3.setOnClickListener {
            isDoc3Uploaded = false
            layoutUploaded3.visibility = View.GONE
            layoutUnuploaded3.visibility = View.VISIBLE
            updateSubmitButtonState()
        }

        // --- Doc 4 Action Listeners ---
        val triggerDoc4Upload = View.OnClickListener {
            isDoc4Uploaded = true
            tvUploadedName4.text = "pay_slip.pdf"
            layoutUnuploaded4.visibility = View.GONE
            layoutUploaded4.visibility = View.VISIBLE
            updateSubmitButtonState()
        }
        btnCamera4.setOnClickListener(triggerDoc4Upload)
        layoutUnuploaded4.setOnClickListener(triggerDoc4Upload)
        layoutUploaded4.setOnClickListener {
            isDoc4Uploaded = false
            layoutUploaded4.visibility = View.GONE
            layoutUnuploaded4.visibility = View.VISIBLE
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
