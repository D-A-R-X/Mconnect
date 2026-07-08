package com.manjugroups.m_connect.ui.common

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.fragment.app.setFragmentResult
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.manjugroups.m_connect.R

/**
 * Shared "reject with reason" bottom sheet — a reason input + red Reject
 * button, reused across every rejection flow (leaves, permissions, …) so the
 * experience is consistent. Reject only; not for absents/cancels.
 *
 * Delivers its result via a caller-chosen [FragmentResult] key so each screen
 * routes the reason to its own reject API:
 *   result bundle → { KEY_ITEM_ID: String, KEY_REASON: String }
 *
 * Usage:
 *   childFragmentManager.setFragmentResultListener(REQUEST_KEY, owner) { _, b ->
 *       val id = b.getString(KEY_ITEM_ID); val reason = b.getString(KEY_REASON)
 *   }
 *   RejectWithReasonBottomSheet.newInstance(id, REQUEST_KEY, title = "Reject leave request")
 *       .show(childFragmentManager, "reject")
 */
class RejectWithReasonBottomSheet : BottomSheetDialogFragment() {

    private var itemId: String = ""
    private var resultKey: String = DEFAULT_RESULT_KEY
    private var titleText: String = "Rejection Reason"
    private var subtitleText: String = "Specify details about why this is rejected"
    private var buttonText: String = "Reject"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let { a ->
            itemId = a.getString(ARG_ITEM_ID).orEmpty()
            resultKey = a.getString(ARG_RESULT_KEY) ?: DEFAULT_RESULT_KEY
            a.getString(ARG_TITLE)?.let { titleText = it }
            a.getString(ARG_SUBTITLE)?.let { subtitleText = it }
            a.getString(ARG_BUTTON)?.let { buttonText = it }
        }
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
                behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.5f).toInt()
                behavior.state = BottomSheetBehavior.STATE_COLLAPSED
                behavior.skipCollapsed = false
            }
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.bottom_sheet_reject_reason, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.tvRejectTitle).text = titleText
        view.findViewById<TextView>(R.id.tvRejectSubtitle).text = subtitleText

        val etReason = view.findViewById<TextInputEditText>(R.id.etRejectReason)
        val btnSubmit = view.findViewById<MaterialButton>(R.id.btnRejectSubmit)
        btnSubmit.text = buttonText

        btnSubmit.setOnClickListener {
            val reason = etReason.text?.toString()?.trim().orEmpty()
            if (reason.isEmpty()) {
                etReason.error = "A reason is required to reject"
                return@setOnClickListener
            }
            setFragmentResult(
                resultKey,
                Bundle().apply {
                    putString(KEY_ITEM_ID, itemId)
                    putString(KEY_REASON, reason)
                },
            )
            dismissAllowingStateLoss()
        }
    }

    companion object {
        const val DEFAULT_RESULT_KEY = "RejectWithReason"
        const val KEY_ITEM_ID = "itemId"
        const val KEY_REASON = "reason"

        private const val ARG_ITEM_ID = "arg_item_id"
        private const val ARG_RESULT_KEY = "arg_result_key"
        private const val ARG_TITLE = "arg_title"
        private const val ARG_SUBTITLE = "arg_subtitle"
        private const val ARG_BUTTON = "arg_button"

        fun newInstance(
            itemId: String,
            resultKey: String = DEFAULT_RESULT_KEY,
            title: String? = null,
            subtitle: String? = null,
            buttonText: String? = null,
        ) = RejectWithReasonBottomSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_ITEM_ID, itemId)
                putString(ARG_RESULT_KEY, resultKey)
                title?.let { putString(ARG_TITLE, it) }
                subtitle?.let { putString(ARG_SUBTITLE, it) }
                buttonText?.let { putString(ARG_BUTTON, it) }
            }
        }
    }
}
