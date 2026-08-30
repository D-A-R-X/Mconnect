package com.manjugroups.m_connect.ui.common

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
 * Captures an optional referral after a New Client CP outcome is filled.
 * The caller sends these fields to the referral endpoint, which creates the
 * Clients-tab record and derives the referring client from the CP visit.
 */
class ReferralCaptureBottomSheet : BottomSheetDialogFragment() {

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
    ): View = inflater.inflate(R.layout.sheet_referral_capture, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val nameInput = view.findViewById<EditText>(R.id.etReferralName)
        val phoneInput = view.findViewById<EditText>(R.id.etReferralPhone)
        val addressInput = view.findViewById<EditText>(R.id.etReferralAddress)

        view.findViewById<Button>(R.id.btnCancelReferral).setOnClickListener {
            sendResult(submitted = false)
            dismissAllowingStateLoss()
        }
        view.findViewById<Button>(R.id.btnSubmitReferral).setOnClickListener {
            val name = nameInput.text?.toString()?.trim().orEmpty()
            val phone = phoneInput.text?.toString()?.trim().orEmpty()
            val address = addressInput.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) {
                Toast.makeText(
                    requireContext(),
                    "Referral name is required.",
                    Toast.LENGTH_SHORT,
                ).show()
                return@setOnClickListener
            }
            val digits = phone.filter(Char::isDigit)
            if (digits.length < 10) {
                Toast.makeText(
                    requireContext(),
                    "Enter a valid referral phone number.",
                    Toast.LENGTH_SHORT,
                ).show()
                return@setOnClickListener
            }
            if (address.isBlank()) {
                Toast.makeText(
                    requireContext(),
                    "Referral address is required.",
                    Toast.LENGTH_SHORT,
                ).show()
                return@setOnClickListener
            }
            sendResult(submitted = true, name = name, phone = digits.takeLast(10), address = address)
            dismissAllowingStateLoss()
        }
    }

    override fun onCancel(dialog: android.content.DialogInterface) {
        sendResult(submitted = false)
        super.onCancel(dialog)
    }

    private fun sendResult(
        submitted: Boolean,
        name: String? = null,
        phone: String? = null,
        address: String? = null,
    ) {
        setFragmentResult(
            RESULT_KEY,
            bundleOf(
                KEY_SUBMITTED to submitted,
                KEY_NAME to name,
                KEY_PHONE to phone,
                KEY_ADDRESS to address,
            ),
        )
    }

    companion object {
        const val RESULT_KEY = "referral_capture_result"
        const val KEY_SUBMITTED = "submitted"
        const val KEY_NAME = "referral_name"
        const val KEY_PHONE = "referral_phone"
        const val KEY_ADDRESS = "referral_address"

        fun newInstance() = ReferralCaptureBottomSheet()
    }
}
