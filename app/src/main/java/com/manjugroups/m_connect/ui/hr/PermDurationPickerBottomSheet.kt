package com.manjugroups.m_connect.ui.hr

import android.app.Dialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.util.EditableTimeFormat
import java.util.Calendar
import java.util.Locale

class PermDurationPickerBottomSheet : BottomSheetDialogFragment() {

    private var selectedFromTime: String? = null
    private var selectedToTime: String? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.setBackgroundColor(Color.TRANSPARENT)
                it.elevation = 0f
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.isDraggable = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_perm_duration_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        arguments?.let {
            selectedFromTime = it.getString(ARG_FROM_TIME)
            selectedToTime = it.getString(ARG_TO_TIME)
        }

        val fieldFromTime = view.findViewById<View>(R.id.fieldFromTime)
        val fieldToTime = view.findViewById<View>(R.id.fieldToTime)
        val btnSubmit = view.findViewById<View>(R.id.btnSubmit)

        fieldFromTime.setOnClickListener { showFromTimePicker() }
        fieldToTime.setOnClickListener { showToTimePicker() }
        btnSubmit.setOnClickListener { confirmDuration() }

        updateLabels()
        updateSubmitButtonState()
    }

    private fun updateSubmitButtonState() {
        val view = view ?: return
        val btnSubmit = view.findViewById<View>(R.id.btnSubmit)
        val isEnabled = selectedFromTime != null && selectedToTime != null
        if (isEnabled) {
            btnSubmit.setBackgroundResource(R.drawable.bg_apply_leave_btn_enabled)
            btnSubmit.isEnabled = true
        } else {
            btnSubmit.setBackgroundResource(R.drawable.bg_apply_leave_btn_disabled)
            btnSubmit.isEnabled = false
        }
    }

    private fun showFromTimePicker() {
        val cal = Calendar.getInstance()
        val currentParts = selectedFromTime?.split(":")?.mapNotNull { it.toIntOrNull() }
        val hour = currentParts?.getOrNull(0) ?: cal.get(Calendar.HOUR_OF_DAY)
        val minute = currentParts?.getOrNull(1) ?: cal.get(Calendar.MINUTE)

        TimePickerDialog(requireContext(), { _, h, m ->
            selectedFromTime = String.format(Locale.US, "%02d:%02d", h, m)
            updateLabels()
            updateSubmitButtonState()
        }, hour, minute, false).show()
    }

    private fun showToTimePicker() {
        val cal = Calendar.getInstance()
        val currentParts = selectedToTime?.split(":")?.mapNotNull { it.toIntOrNull() }
        val hour = currentParts?.getOrNull(0) ?: (cal.get(Calendar.HOUR_OF_DAY) + 1)
        val minute = currentParts?.getOrNull(1) ?: cal.get(Calendar.MINUTE)

        TimePickerDialog(requireContext(), { _, h, m ->
            selectedToTime = String.format(Locale.US, "%02d:%02d", h, m)
            updateLabels()
            updateSubmitButtonState()
        }, hour, minute, false).show()
    }

    private fun updateLabels() {
        val view = view ?: return
        val tvFrom = view.findViewById<TextView>(R.id.tvFromTimeValue)
        val tvTo = view.findViewById<TextView>(R.id.tvToTimeValue)

        val from = selectedFromTime
        if (from == null) {
            tvFrom.text = "Select From Time"
            tvFrom.setTextColor(Color.parseColor("#9CA3AF"))
        } else {
            tvFrom.text = EditableTimeFormat.toDisplay(from)
            tvFrom.setTextColor(Color.parseColor("#101828"))
        }

        val to = selectedToTime
        if (to == null) {
            tvTo.text = "Select To Time"
            tvTo.setTextColor(Color.parseColor("#9CA3AF"))
        } else {
            tvTo.text = EditableTimeFormat.toDisplay(to)
            tvTo.setTextColor(Color.parseColor("#101828"))
        }
    }

    private fun confirmDuration() {
        val from = selectedFromTime
        val to = selectedToTime
        if (from == null || to == null) return

        if (!isValidTimeRange(from, to)) {
            Toast.makeText(requireContext(), "To time must be after from time", Toast.LENGTH_SHORT).show()
            return
        }

        parentFragmentManager.setFragmentResult(
            RESULT_KEY_DURATION,
            Bundle().apply {
                putString(KEY_FROM_TIME, from)
                putString(KEY_TO_TIME, to)
            }
        )
        dismissAllowingStateLoss()
    }

    private fun isValidTimeRange(fromTime: String, toTime: String): Boolean {
        val fromParts = fromTime.split(":").mapNotNull { it.toIntOrNull() }
        val toParts = toTime.split(":").mapNotNull { it.toIntOrNull() }
        if (fromParts.size != 2 || toParts.size != 2) return false

        val fromMinutes = fromParts[0] * 60 + fromParts[1]
        val toMinutes = toParts[0] * 60 + toParts[1]
        return toMinutes > fromMinutes
    }

    companion object {
        const val RESULT_KEY_DURATION = "perm_duration_picker_result"
        const val KEY_FROM_TIME = "from_time"
        const val KEY_TO_TIME = "to_time"

        private const val ARG_FROM_TIME = "arg_from_time"
        private const val ARG_TO_TIME = "arg_to_time"

        fun newInstance(initialFrom: String? = null, initialTo: String? = null): PermDurationPickerBottomSheet {
            return PermDurationPickerBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_FROM_TIME, initialFrom)
                    putString(ARG_TO_TIME, initialTo)
                }
            }
        }
    }
}
