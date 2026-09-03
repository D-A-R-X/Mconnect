package com.manjugroups.m_connect.ui.home

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import coil.load
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import java.io.File

/** Reviews client-not-met photo proof before upload and collects optional notes. */
class CpClientNotMetProofBottomSheet : BottomSheetDialogFragment() {

    private var resultSent = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        BottomSheetDialog(requireContext(), theme).apply {
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_cp_client_not_met_proof, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val photoPath = requireArguments().getString(ARG_PHOTO_PATH).orEmpty()
        val photo = File(photoPath)
        view.findViewById<ImageView>(R.id.ivClientNotMetProof).load(photo) {
            crossfade(true)
        }
        val remarks = view.findViewById<EditText>(R.id.etClientNotMetRemarks)

        view.findViewById<View>(R.id.btnRetakeClientNotMetPhoto).setOnClickListener {
            sendResult(ACTION_RETAKE, photoPath, null)
            dismissAllowingStateLoss()
        }
        view.findViewById<View>(R.id.btnCompleteClientNotMet).setOnClickListener {
            sendResult(
                ACTION_SUBMIT,
                photoPath,
                remarks.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
            )
            dismissAllowingStateLoss()
        }
    }

    override fun onCancel(dialog: android.content.DialogInterface) {
        sendResult(ACTION_CANCEL, requireArguments().getString(ARG_PHOTO_PATH).orEmpty(), null)
        super.onCancel(dialog)
    }

    private fun sendResult(action: String, photoPath: String, remarks: String?) {
        if (resultSent) return
        resultSent = true
        setFragmentResult(
            RESULT_KEY,
            bundleOf(
                KEY_ACTION to action,
                KEY_PHOTO_PATH to photoPath,
                KEY_REMARKS to remarks,
            ),
        )
    }

    companion object {
        const val RESULT_KEY = "cp_client_not_met_proof_result"
        const val KEY_ACTION = "action"
        const val KEY_PHOTO_PATH = "photoPath"
        const val KEY_REMARKS = "remarks"
        const val ACTION_SUBMIT = "submit"
        const val ACTION_RETAKE = "retake"
        const val ACTION_CANCEL = "cancel"
        private const val ARG_PHOTO_PATH = "arg_photo_path"

        fun newInstance(photoPath: String) = CpClientNotMetProofBottomSheet().apply {
            arguments = bundleOf(ARG_PHOTO_PATH to photoPath)
        }
    }
}
