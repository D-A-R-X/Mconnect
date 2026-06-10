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
import com.manjugroups.m_connect.databinding.DialogClockOutConfirmBinding
import java.util.Locale

class ClockOutConfirmBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val RESULT_KEY = "clock_out_confirm_result"
        const val KEY_CONFIRMED = "confirmed"
        private const val ARG_TODAY_MINUTES = "today_minutes"
        private const val ARG_OVERTIME_MINUTES = "overtime_minutes"

        /**
         * Today minutes is the staff's worked minutes for the calendar day,
         * already in the parent AttendanceFlowState. Overtime is derived
         * (worked - 8h, floored at zero) so the sheet doesn't need to know
         * the standard workday rule itself. Both fall back to 0 when the
         * args are absent so a default newInstance() still renders a
         * harmless "00:00:00 Hrs" pair instead of throwing.
         */
        fun newInstance(
            todayMinutes: Int = 0,
            overtimeMinutes: Int = 0,
        ): ClockOutConfirmBottomSheet = ClockOutConfirmBottomSheet().apply {
            arguments = bundleOf(
                ARG_TODAY_MINUTES to todayMinutes,
                ARG_OVERTIME_MINUTES to overtimeMinutes,
            )
        }
    }

    private var _binding: DialogClockOutConfirmBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                // Mirror CpClientSeenBottomSheet ("Have you seen the
                // client?") so the floating clock badge renders the same
                // way: transparent sheet background + clip-free sheet AND
                // parent lets the badge's drop-shadow paint outside the
                // card edge cleanly instead of stamping a gray band.
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
        _binding = DialogClockOutConfirmBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val todayMinutes = arguments?.getInt(ARG_TODAY_MINUTES, 0) ?: 0
        val overtimeMinutes = arguments?.getInt(ARG_OVERTIME_MINUTES, 0) ?: 0
        binding.tvTodayHoursValue.text = formatMinutesAsHHMMSS(todayMinutes)
        binding.tvOvertimeHoursValue.text = formatMinutesAsHHMMSS(overtimeMinutes)

        binding.btnConfirmClockOut.setOnClickListener {
            parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf(KEY_CONFIRMED to true))
            dismissAllowingStateLoss()
        }

        binding.btnCancelClockOut.setOnClickListener {
            dismissAllowingStateLoss()
        }
    }

    /**
     * The Figma uses `HH:MM:SS Hrs`. We don't have second-precision on
     * the punch totals (attendance is summed at minute granularity), so
     * seconds always render as `00`. Matches the design pixel-for-pixel
     * while staying honest about what the backend gives us.
     */
    private fun formatMinutesAsHHMMSS(totalMinutes: Int): String {
        val safe = totalMinutes.coerceAtLeast(0)
        val hours = safe / 60
        val minutes = safe % 60
        return String.format(Locale.US, "%02d:%02d:00 Hrs", hours, minutes)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
