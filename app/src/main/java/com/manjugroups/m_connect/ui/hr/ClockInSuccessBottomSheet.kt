package com.manjugroups.m_connect.ui.hr

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.databinding.DialogClockInSuccessBinding

class ClockInSuccessBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val RESULT_KEY = "clock_in_success_result"
        const val KEY_DONE = "done"
    }

    private var _binding: DialogClockInSuccessBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                // Float the success badge cleanly above the white card —
                // transparent sheet bg + no clipping (same fix as
                // ClockOutSuccessBottomSheet) so there's no grey band
                // behind the green check.
                it.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
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
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogClockInSuccessBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnGoToClockInPage.setOnClickListener {
            parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf(KEY_DONE to true))
            dismissAllowingStateLoss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
