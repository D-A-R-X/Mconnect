package com.manjugroups.m_connect.ui.hr

import android.app.Dialog
import android.app.TimePickerDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.databinding.BottomSheetEditAttendanceBinding
import com.manjugroups.m_connect.network.AttendanceRecord
import java.text.SimpleDateFormat
import java.util.*

class EditAttendanceBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetEditAttendanceBinding? = null
    private val binding get() = _binding!!

    private var record: AttendanceRecord? = null

    private var isInTimeSelected = false
    private var isOutTimeSelected = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.setOnShowListener { di ->
            val sheet = (di as? BottomSheetDialog)
                ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.setBackgroundColor(Color.TRANSPARENT)
                it.elevation = 0f
                it.outlineProvider = null
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetEditAttendanceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (view.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)

        val inTimeStr = record?.punchInTime ?: record?.sessions?.firstOrNull()?.punchInTime
        val outTimeStr = record?.punchOutTime ?: record?.sessions?.lastOrNull()?.punchOutTime

        binding.etInTime.setText(inTimeStr?.let(::formatIsoTime) ?: "")
        binding.etOutTime.setText(outTimeStr?.let(::formatIsoTime) ?: "")

        binding.etInTime.setOnClickListener {
            showTimePicker(true)
        }
        binding.etOutTime.setOnClickListener {
            showTimePicker(false)
        }

        binding.btnSubmitEdit.setOnClickListener {
            val inTime = binding.etInTime.text.toString().trim()
            val outTime = binding.etOutTime.text.toString().trim()
            val remarks = binding.etRemarks.text.toString().trim()

            Toast.makeText(context, "Edit submitted", Toast.LENGTH_SHORT).show()
            dismiss()
        }

        validateForm()
    }

    private fun showTimePicker(isInTime: Boolean) {
        val calendar = Calendar.getInstance()
        val currentText = if (isInTime) binding.etInTime.text.toString() else binding.etOutTime.text.toString()
        if (currentText.isNotEmpty()) {
            try {
                val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
                sdf.parse(currentText)?.let { date ->
                    calendar.time = date
                }
            } catch (_: Exception) {}
        }

        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)

        val timePickerDialog = TimePickerDialog(
            requireContext(),
            { _, selectedHour, selectedMinute ->
                val selectedCal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, selectedHour)
                    set(Calendar.MINUTE, selectedMinute)
                }
                val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
                val formattedTime = sdf.format(selectedCal.time)

                if (isInTime) {
                    binding.etInTime.setText(formattedTime)
                    isInTimeSelected = true
                } else {
                    binding.etOutTime.setText(formattedTime)
                    isOutTimeSelected = true
                }
                validateForm()
            },
            hour,
            minute,
            false // 12-hour format
        )
        timePickerDialog.show()
    }

    private fun validateForm() {
        val inTimeText = binding.etInTime.text.toString().trim()
        val outTimeText = binding.etOutTime.text.toString().trim()
        val isValid = isInTimeSelected && isOutTimeSelected && inTimeText.isNotEmpty() && outTimeText.isNotEmpty()
        binding.btnSubmitEdit.isEnabled = isValid
        if (isValid) {
            binding.btnSubmitEdit.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#15B058"))
        } else {
            binding.btnSubmitEdit.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#A6E69C"))
        }
    }

    private fun formatIsoTime(iso: String): String {
        val millis = parseIsoMillis(iso) ?: return iso
        return SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(millis))
    }

    private fun parseIsoMillis(iso: String): Long? {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
        )
        for (pattern in patterns) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                if (pattern.endsWith("'Z'")) {
                    fmt.timeZone = TimeZone.getTimeZone("UTC")
                }
                return fmt.parse(iso)?.time
            } catch (_: Exception) {
            }
        }
        return null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(record: AttendanceRecord): EditAttendanceBottomSheet {
            val sheet = EditAttendanceBottomSheet()
            sheet.record = record
            return sheet
        }
    }
}
