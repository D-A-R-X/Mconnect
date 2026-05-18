package com.manjugroups.m_connect.ui.home

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R

class CpClientSeenBottomSheet : BottomSheetDialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                // Allow the floating verification chip (-40dp top margin)
                // to render outside the sheet's content area. By default
                // the sheet + its CoordinatorLayout parent clip children.
                if (it is ViewGroup) {
                    it.clipChildren = false
                    it.clipToPadding = false
                }
                (it.parent as? ViewGroup)?.let { parent ->
                    parent.clipChildren = false
                    parent.clipToPadding = false
                }
            }
        }
        // Tapping the dim area above the sheet should dismiss it like a
        // normal toast — without this the user is stuck unable to back
        // out of the prompt by tapping the map / trip progress behind.
        isCancelable = true
        dialog.setCanceledOnTouchOutside(true)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_cp_client_seen, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.btnCpSeenYes).setOnClickListener {
            setFragmentResult(RESULT_KEY, bundleOf(KEY_CLIENT_SEEN to true))
            dismissAllowingStateLoss()
        }
        view.findViewById<TextView>(R.id.btnCpSeenNo).setOnClickListener {
            setFragmentResult(RESULT_KEY, bundleOf(KEY_CLIENT_SEEN to false))
            dismissAllowingStateLoss()
        }
    }

    companion object {
        const val RESULT_KEY = "cp_client_seen_result"
        const val KEY_CLIENT_SEEN = "clientSeen"
    }
}
