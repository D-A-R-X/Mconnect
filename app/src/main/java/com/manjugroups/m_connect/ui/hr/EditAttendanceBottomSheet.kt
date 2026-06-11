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
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.BottomSheetEditAttendanceBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.AttendanceRecord
import com.manjugroups.m_connect.network.AttendanceRequestBody
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class EditAttendanceBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetEditAttendanceBinding? = null
    private val binding get() = _binding!!
    private val api = ApiService.create()
    private lateinit var session: SessionManager

    private var record: AttendanceRecord? = null

    private var isInTimeSelected = false
    private var isOutTimeSelected = false
    private var submitting = false
    // "remark" | "correction" — mirrors the web Request Type dropdown.
    private var requestType = "remark"

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
        session = SessionManager(requireContext())

        // Pre-fill the current punch times so the user adjusts from the
        // existing values rather than re-entering them. Each pre-filled
        // field counts as "selected" so the only thing left to make the
        // form valid is the mandatory reason/remark.
        val rec = record
        rec?.punchInTime?.let {
            binding.etInTime.setText(formatIsoTime(it))
            isInTimeSelected = true
        }
        val resolvedOut = rec?.punchOutTime ?: rec?.sessions?.lastOrNull()?.punchOutTime
        resolvedOut?.let {
            binding.etOutTime.setText(formatIsoTime(it))
            isOutTimeSelected = true
        }

        // Request Type dropdown — Remark (default) | Time Correction,
        // matching the web attendance dialog. The time fields only show
        // for Time Correction.
        setRequestType("remark")
        binding.etRequestType.setOnClickListener { showRequestTypePicker() }

        binding.etInTime.setOnClickListener {
            showTimePicker(true)
        }
        binding.etOutTime.setOnClickListener {
            showTimePicker(false)
        }

        // Notes are optional — re-check validity as they're typed (only
        // affects the button's disabled styling, never blocks submit).
        binding.etRemarks.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { validateForm() }
        })

        binding.btnSubmitEdit.setOnClickListener {
            submitRequest()
        }

        validateForm()
    }

    private fun showRequestTypePicker() {
        val popup = android.widget.PopupMenu(requireContext(), binding.etRequestType)
        popup.menu.add(0, 0, 0, "Remark")
        popup.menu.add(0, 1, 1, "Time Correction")
        popup.setOnMenuItemClickListener { item ->
            setRequestType(if (item.itemId == 1) "correction" else "remark")
            true
        }
        popup.show()
    }

    private fun setRequestType(type: String) {
        requestType = type
        binding.etRequestType.setText(if (type == "correction") "Time Correction" else "Remark")
        binding.timeCorrectionFields.visibility =
            if (type == "correction") View.VISIBLE else View.GONE
        validateForm()
    }

    /**
     * Submits a Remark or Time Correction request for HR approval via
     * /api/hr/attendance/request. Notes (the Remarks field) are optional;
     * when blank we send a default string because the backend mutation
     * stores a reason/remark on approval. On success we notify the history
     * screen to reload and dismiss.
     */
    private fun submitRequest() {
        if (submitting) return
        val rec = record
        val attendanceId = rec?.id
        val date = rec?.date
        if (attendanceId.isNullOrBlank() || date.isNullOrBlank()) {
            Toast.makeText(context, "This day can't be edited", Toast.LENGTH_SHORT).show()
            return
        }
        val notes = binding.etRemarks.text.toString().trim()

        val body: AttendanceRequestBody = if (requestType == "correction") {
            val correctedIn = if (isInTimeSelected)
                toIsoForDate(date, binding.etInTime.text.toString().trim()) else null
            val correctedOut = if (isOutTimeSelected)
                toIsoForDate(date, binding.etOutTime.text.toString().trim()) else null
            if (correctedIn == null && correctedOut == null) {
                Toast.makeText(context, "Select a corrected in or out time", Toast.LENGTH_SHORT).show()
                return
            }
            AttendanceRequestBody(
                attendanceId = attendanceId,
                date = date,
                type = "correction",
                correctedPunchIn = correctedIn,
                correctedPunchOut = correctedOut,
                // Optional in the UI; default so the backend always has a reason.
                correctionReason = notes.ifBlank { "Time correction requested from mobile app" },
            )
        } else {
            AttendanceRequestBody(
                attendanceId = attendanceId,
                date = date,
                type = "remark",
                remark = notes.ifBlank { "Remark requested from mobile app" },
            )
        }

        submitting = true
        binding.btnSubmitEdit.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.submitAttendanceRequest(session.bearerToken, body)
                if (resp.success) {
                    Toast.makeText(
                        context,
                        "Request sent for approval",
                        Toast.LENGTH_SHORT,
                    ).show()
                    setFragmentResult(RESULT_KEY, Bundle().apply { putBoolean(KEY_SUBMITTED, true) })
                    dismiss()
                } else {
                    Toast.makeText(
                        context,
                        resp.error ?: "Failed to submit request",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    extractHttpErrorMessage(e) ?: e.message ?: "Network error",
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                submitting = false
                _binding?.let { validateForm() }
            }
        }
    }

    /** Combine a yyyy-MM-dd date with an "hh:mm a" time into a UTC ISO string. */
    private fun toIsoForDate(date: String, time: String): String? {
        if (time.isEmpty()) return null
        return runCatching {
            val local = SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.US).parse("$date $time")
            val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            local?.let { iso.format(it) }
        }.getOrNull()
    }

    /**
     * Pull {error: "..."} from a Retrofit HttpException body so the toast
     * shows the real server message instead of "HTTP 500".
     */
    private fun extractHttpErrorMessage(e: Throwable): String? {
        val httpEx = e as? retrofit2.HttpException ?: return null
        val raw = runCatching { httpEx.response()?.errorBody()?.string() }.getOrNull() ?: return null
        return runCatching {
            val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject
            (obj.get("error")?.asString ?: obj.get("message")?.asString)?.takeIf { it.isNotBlank() }
        }.getOrNull()
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
        // Notes are always optional. A Time Correction still needs at
        // least one corrected time; a Remark has no mandatory field.
        val isValid = when (requestType) {
            "correction" ->
                (isInTimeSelected && binding.etInTime.text.toString().isNotBlank()) ||
                    (isOutTimeSelected && binding.etOutTime.text.toString().isNotBlank())
            else -> true
        } && !submitting
        binding.btnSubmitEdit.isEnabled = isValid
        binding.btnSubmitEdit.alpha = if (isValid) 1f else 0.5f
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
        const val RESULT_KEY = "edit_attendance_result"
        const val KEY_SUBMITTED = "submitted"

        fun newInstance(record: AttendanceRecord): EditAttendanceBottomSheet {
            val sheet = EditAttendanceBottomSheet()
            sheet.record = record
            return sheet
        }
    }
}
