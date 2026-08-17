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
 * Referral capture for the New-Client CP "Client Referral" outcome.
 * Collects the referred person's name + phone; the caller packs them into
 * the free-text visit notes ("Referral: <name> · <phone>") and closes the
 * visit with outcome="referral". Modelled on [OutcomeRemarksBottomSheet]
 * so it matches the rest of the CP trip flow.
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

        view.findViewById<Button>(R.id.btnCancelReferral).setOnClickListener {
            sendResult(submitted = false)
            dismissAllowingStateLoss()
        }
        view.findViewById<Button>(R.id.btnSubmitReferral).setOnClickListener {
            val name = nameInput.text?.toString()?.trim().orEmpty()
            val phone = phoneInput.text?.toString()?.trim().orEmpty()
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
            sendResult(submitted = true, name = name, phone = phone)
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
    ) {
        setFragmentResult(
            RESULT_KEY,
            bundleOf(KEY_SUBMITTED to submitted, KEY_NAME to name, KEY_PHONE to phone),
        )
    }

    companion object {
        const val RESULT_KEY = "referral_capture_result"
        const val KEY_SUBMITTED = "submitted"
        const val KEY_NAME = "referral_name"
        const val KEY_PHONE = "referral_phone"

        fun newInstance() = ReferralCaptureBottomSheet()
    }
}
