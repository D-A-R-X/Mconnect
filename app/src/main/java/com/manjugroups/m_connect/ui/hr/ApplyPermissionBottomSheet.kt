package com.manjugroups.m_connect.ui.hr

import android.app.Dialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.gson.Gson
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.ApplyPermissionRequest
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import com.manjugroups.m_connect.ui.common.showOnce

class ApplyPermissionBottomSheet : BottomSheetDialogFragment() {

    private data class ApiErrorResponse(
        val success: Boolean? = null,
        val error: String? = null,
        val message: String? = null
    )

    private lateinit var session: SessionManager
    private val api = ApiService.create()
    private val gson = Gson()

    private var selectedDate: String? = null
    private var selectedFromTime: String? = null
    private var selectedToTime: String? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        // ADJUST_RESIZE keeps the focused input above the soft keyboard;
        // without it the keyboard covers the lower fields and the submit
        // button with no way to scroll them back into view.
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
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
    ): View = inflater.inflate(R.layout.bottom_sheet_apply_permission, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        val fieldPermissionDate = view.findViewById<View>(R.id.fieldPermissionDate)
        val fieldPermissionDuration = view.findViewById<View>(R.id.fieldPermissionDuration)
        val btnSubmit = view.findViewById<View>(R.id.btnSubmit)

        fieldPermissionDate.setOnClickListener { showDatePicker() }
        fieldPermissionDuration.setOnClickListener { showDurationPicker() }
        btnSubmit.setOnClickListener { submitPermission() }

        setFragmentResultListener(PermDurationPickerBottomSheet.RESULT_KEY_DURATION) { _, bundle ->
            selectedFromTime = bundle.getString(PermDurationPickerBottomSheet.KEY_FROM_TIME)
            selectedToTime = bundle.getString(PermDurationPickerBottomSheet.KEY_TO_TIME)
            updateDurationLabel()
            updateSubmitButtonState()
        }

        updateDateLabel()
        updateDurationLabel()
        updateSubmitButtonState()
    }

    private fun updateSubmitButtonState() {
        val view = view ?: return
        val btnSubmit = view.findViewById<View>(R.id.btnSubmit)
        val isEnabled = selectedDate != null && selectedFromTime != null && selectedToTime != null
        if (isEnabled) {
            btnSubmit.setBackgroundResource(R.drawable.bg_apply_leave_btn_enabled)
            btnSubmit.isEnabled = true
        } else {
            btnSubmit.setBackgroundResource(R.drawable.bg_apply_leave_btn_disabled)
            btnSubmit.isEnabled = false
        }
    }

    private fun showDatePicker() {
        val current = selectedDate ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)
        setFragmentResultListener(RESULT_KEY_DATE) { _, bundle ->
            val date = bundle.getString(CalendarRangePickerSheet.KEY_FROM) ?: return@setFragmentResultListener
            selectedDate = date
            updateDateLabel()
            updateSubmitButtonState()
        }
        CalendarRangePickerSheet.newInstance(
            title = "Permission Date",
            subtitle = "Select Date",
            initialFrom = current,
            initialTo = current,
            resultKey = RESULT_KEY_DATE,
        ).showOnce(parentFragmentManager, "apply_permission_calendar")
    }

    private fun showDurationPicker() {
        PermDurationPickerBottomSheet
            .newInstance(selectedFromTime, selectedToTime)
            .showOnce(parentFragmentManager, "perm_duration_picker")
    }

    private fun updateDateLabel() {
        val tvDateValue = view?.findViewById<TextView>(R.id.tvPermissionDateValue) ?: return
        val raw = selectedDate
        if (raw == null) {
            tvDateValue.text = "Select Date"
            tvDateValue.setTextColor(Color.parseColor("#9CA3AF"))
        } else {
            tvDateValue.text = formatDateLabel(raw)
            tvDateValue.setTextColor(Color.parseColor("#101828"))
        }
    }

    private fun updateDurationLabel() {
        val tvDurationValue = view?.findViewById<TextView>(R.id.tvPermissionDurationValue) ?: return
        val from = selectedFromTime
        val to = selectedToTime
        if (from == null || to == null) {
            tvDurationValue.text = "Select Duration"
            tvDurationValue.setTextColor(Color.parseColor("#9CA3AF"))
        } else {
            tvDurationValue.text = "$from - $to"
            tvDurationValue.setTextColor(Color.parseColor("#101828"))
        }
    }

    private fun formatDateLabel(raw: String): String {
        val inputFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val outputFmt = SimpleDateFormat("dd MMM yyyy", Locale.US)
        val date = runCatching { inputFmt.parse(raw) }.getOrNull()
        return date?.let { outputFmt.format(it) } ?: raw
    }

    private fun isValidTimeRange(fromTime: String, toTime: String): Boolean {
        val fromParts = fromTime.split(":").mapNotNull { it.toIntOrNull() }
        val toParts = toTime.split(":").mapNotNull { it.toIntOrNull() }
        if (fromParts.size != 2 || toParts.size != 2) return false

        val fromMinutes = fromParts[0] * 60 + fromParts[1]
        val toMinutes = toParts[0] * 60 + toParts[1]
        return toMinutes > fromMinutes
    }

    private fun submitPermission() {
        val date = selectedDate
        val from = selectedFromTime
        val to = selectedToTime
        val etReason = view?.findViewById<EditText>(R.id.etReason)
        val reason = etReason?.text?.toString()?.trim().orEmpty() // Optional

        if (date.isNullOrBlank() || from.isNullOrBlank() || to.isNullOrBlank()) {
            Toast.makeText(requireContext(), "Fill all required fields", Toast.LENGTH_SHORT).show()
            return
        }

        val tvSubmit = view?.findViewById<TextView>(R.id.tvSubmit)
        val skeletonSubmit = view?.findViewById<View>(R.id.skeletonSubmit)
        val btnSubmit = view?.findViewById<View>(R.id.btnSubmit)

        tvSubmit?.visibility = View.INVISIBLE
        skeletonSubmit?.visibility = View.VISIBLE
        skeletonSubmit?.let { SkeletonUtils.startSkeletonPulse(it) }
        btnSubmit?.isClickable = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.applyPermission(
                    session.bearerToken,
                    ApplyPermissionRequest(
                        date = date,
                        fromTime = from,
                        toTime = to,
                        reason = reason,
                        reportingToId = session.reportingToId,
                        reportingToName = session.reportingToName,
                    )
                )
                if (resp.success) {
                    Toast.makeText(requireContext(), "Permission applied!", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.setFragmentResult(RESULT_KEY_APPLIED, Bundle().apply { putBoolean("success", true) })
                    dismissAllowingStateLoss()
                } else {
                    Toast.makeText(requireContext(), resp.error ?: "Failed", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), parseErrorMessage(e), Toast.LENGTH_SHORT).show()
            }
            tvSubmit?.visibility = View.VISIBLE
            skeletonSubmit?.let { SkeletonUtils.stopSkeletonPulse(it) }
            skeletonSubmit?.visibility = View.GONE
            btnSubmit?.isClickable = true
        }
    }

    private fun parseErrorMessage(error: Throwable): String {
        if (error is HttpException) {
            val body = error.response()?.errorBody()?.string()
            if (!body.isNullOrBlank()) {
                runCatching {
                    gson.fromJson(body, ApiErrorResponse::class.java)
                }.getOrNull()?.let { parsed ->
                    parsed.error?.takeIf { it.isNotBlank() }?.let { return it }
                    parsed.message?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return error.message ?: "Network error"
    }

    companion object {
        const val RESULT_KEY_APPLIED = "apply_permission_applied_result"
        private const val RESULT_KEY_DATE = "apply_permission_date_calendar"

        fun newInstance(): ApplyPermissionBottomSheet = ApplyPermissionBottomSheet()
    }
}
