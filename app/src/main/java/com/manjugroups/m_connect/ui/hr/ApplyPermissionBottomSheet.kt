package com.manjugroups.m_connect.ui.hr

import com.manjugroups.m_connect.ui.common.toastSafe

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
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.ApplyPermissionRequest
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import com.manjugroups.m_connect.util.EditableTimeFormat
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import com.manjugroups.m_connect.ui.common.showOnce

class ApplyPermissionBottomSheet : BottomSheetDialogFragment() {

    private lateinit var session: SessionManager
    private val api = ApiService.create()
    private var isSubmitting = false
    private var applicationSubmitted = false
    private var shiftTiming: PermissionShiftTiming? = null
    private var shiftLoading = false
    private var shiftError: String? = null
    private var shiftJob: Job? = null
    private var shiftRequestId = 0

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
                // Full-height so the Submit footer pins to the bottom.
                it.layoutParams = it.layoutParams.apply {
                    height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                }
                val behavior = BottomSheetBehavior.from(it)
                behavior.peekHeight = resources.displayMetrics.heightPixels
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
        view.findViewById<View>(R.id.btnRetryPermissionShift).setOnClickListener { loadShift() }
        view.findViewById<EditText>(R.id.etReason).doAfterTextChanged {
            updateSubmitButtonState()
        }

        setFragmentResultListener(PermDurationPickerBottomSheet.RESULT_KEY_DURATION) { _, bundle ->
            selectedFromTime = bundle.getString(PermDurationPickerBottomSheet.KEY_FROM_TIME)
            selectedToTime = bundle.getString(PermDurationPickerBottomSheet.KEY_TO_TIME)
            updateDurationLabel()
            updateSubmitButtonState()
        }

        updateDateLabel()
        updateDurationLabel()
        updateSubmitButtonState()
        if (selectedDate != null) loadShift()
    }

    private fun updateSubmitButtonState() {
        val view = view ?: return
        val btnSubmit = view.findViewById<View>(R.id.btnSubmit)
        val hasReason = view.findViewById<EditText>(R.id.etReason)
            .text
            ?.toString()
            ?.trim()
            ?.isNotEmpty() == true
        val isEnabled = !isSubmitting && !applicationSubmitted && !shiftLoading && shiftTiming != null &&
            selectedDate != null && selectedFromTime != null && selectedToTime != null && hasReason &&
            shiftTiming?.error(selectedFromTime, selectedToTime) == null
        if (isEnabled) {
            btnSubmit.setBackgroundResource(R.drawable.bg_apply_leave_btn_enabled)
            btnSubmit.isEnabled = true
        } else {
            btnSubmit.setBackgroundResource(R.drawable.bg_apply_leave_btn_disabled)
            btnSubmit.isEnabled = false
        }
        updateShiftLabel()
    }

    private fun showDatePicker() {
        val today = HrApplicationDates.todayIso()
        val current = selectedDate?.takeUnless { HrApplicationDates.isPastIsoDate(it) } ?: today
        setFragmentResultListener(RESULT_KEY_DATE) { _, bundle ->
            val date = bundle.getString(CalendarRangePickerSheet.KEY_FROM) ?: return@setFragmentResultListener
            selectedDate = date
            selectedFromTime = null
            selectedToTime = null
            updateDateLabel()
            updateDurationLabel()
            loadShift()
            updateSubmitButtonState()
        }
        CalendarRangePickerSheet.newInstance(
            title = "Permission Date",
            subtitle = "Select Date",
            initialFrom = current,
            initialTo = current,
            resultKey = RESULT_KEY_DATE,
            // Permission can't be applied for a past day.
            minDate = today,
        ).showOnce(parentFragmentManager, "apply_permission_calendar")
    }

    private fun showDurationPicker() {
        val timing = shiftTiming
        if (timing == null || shiftLoading) {
            if (!shiftLoading && selectedDate != null) loadShift()
            else toastSafe(if (selectedDate == null) "Select a permission date first" else "Loading your shift")
            return
        }
        timing.error(null, null)?.let { toastSafe(it); return }
        PermDurationPickerBottomSheet
            .newInstance(selectedFromTime ?: timing.startTime, selectedToTime ?: timing.defaultEndTime(), timing.startTime, timing.endTime)
            .showOnce(parentFragmentManager, "perm_duration_picker")
    }

    private fun loadShift() {
        val date = selectedDate ?: return
        val requestId = ++shiftRequestId
        shiftJob?.cancel()
        shiftTiming = null
        shiftError = null
        shiftLoading = true
        updateSubmitButtonState()
        shiftJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val staffId = session.staffId?.takeIf { it.isNotBlank() }
                    ?: error("Sign in again to load your staff shift.")
                val response = withTimeoutOrNull(10_000) { api.getTodayShift(session.bearerToken, staffId, date) }
                    ?: error("Shift lookup timed out. Please retry.")
                if (shiftRequestId == requestId) shiftTiming = PermissionShiftTiming.resolve(response, date)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (shiftRequestId == requestId) shiftError = HrApplicationError.message(e, "Shift")
            } finally {
                if (shiftRequestId == requestId) {
                    shiftLoading = false
                    updateSubmitButtonState()
                }
            }
        }
    }

    private fun updateShiftLabel() {
        val view = view ?: return
        val timing = shiftTiming
        val message = when {
            selectedDate == null -> null
            shiftLoading -> "Loading your shift..."
            shiftError != null -> shiftError
            timing != null -> timing.error(selectedFromTime, selectedToTime)
                ?: "Shift: ${EditableTimeFormat.toDisplay(timing.startTime)} - ${EditableTimeFormat.toDisplay(timing.endTime)}. Permission must start or end with your shift."
            else -> null
        }
        view.findViewById<TextView>(R.id.tvPermissionShift).apply {
            text = message
            visibility = if (message == null) View.GONE else View.VISIBLE
        }
        view.findViewById<View>(R.id.btnRetryPermissionShift).visibility =
            if (shiftError != null && !shiftLoading) View.VISIBLE else View.GONE
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
            tvDurationValue.text =
                "${EditableTimeFormat.toDisplay(from)} - ${EditableTimeFormat.toDisplay(to)}"
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
        if (isSubmitting || applicationSubmitted) return
        val date = selectedDate
        val from = selectedFromTime
        val to = selectedToTime
        val etReason = view?.findViewById<EditText>(R.id.etReason)
        val reason = etReason?.text?.toString()?.trim().orEmpty()

        if (date.isNullOrBlank() || from.isNullOrBlank() || to.isNullOrBlank() || reason.isBlank()) {
            toastSafe("Fill all required fields")
            return
        }
        if (!isValidTimeRange(from, to)) {
            toastSafe("To time must be after from time")
            return
        }
        if (HrApplicationDates.isPastIsoDate(date)) {
            toastSafe(HrApplicationDates.PERMISSION_PAST_MESSAGE)
            return
        }
        if (shiftLoading || shiftTiming == null) {
            toastSafe("Load your shift before submitting")
            return
        }
        shiftTiming?.error(from, to)?.let { toastSafe(it); return }

        val tvSubmit = view?.findViewById<TextView>(R.id.tvSubmit)
        val skeletonSubmit = view?.findViewById<View>(R.id.skeletonSubmit)
        val btnSubmit = view?.findViewById<View>(R.id.btnSubmit)

        isSubmitting = true
        updateSubmitButtonState()
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
                    )
                )
                if (resp.success) {
                    onPermissionApplied()
                } else {
                    toastSafe(HrApplicationError.clean(resp.error) ?: "Could not submit the permission request. Please try again.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A timeout or a reasonless 5xx may still have saved the
                // request: check the history before calling it a failure.
                if (HrApplicationError.isAmbiguous(e) && wasPermissionSaved(date, from, to)) {
                    onPermissionApplied()
                } else {
                    toastSafe(HrApplicationError.message(e, "Permission"))
                }
            } finally {
                isSubmitting = false
                tvSubmit?.visibility = View.VISIBLE
                skeletonSubmit?.let { SkeletonUtils.stopSkeletonPulse(it) }
                skeletonSubmit?.visibility = View.GONE
                btnSubmit?.isClickable = true
                updateSubmitButtonState()
            }
        }
    }

    private fun onPermissionApplied() {
        applicationSubmitted = true
        toastSafe("Permission applied!")
        parentFragmentManager.setFragmentResult(RESULT_KEY_APPLIED, Bundle().apply { putBoolean("success", true) })
        dismissAllowingStateLoss()
    }

    /** True when the server already holds this exact request (same date and times, still live). */
    private suspend fun wasPermissionSaved(date: String, from: String, to: String): Boolean = try {
        api.getMyPermissions(session.bearerToken, fromDate = date, toDate = date).permissions.orEmpty().any {
            it.date == date && it.fromTime == from && it.toTime == to &&
                it.status?.lowercase() !in setOf("rejected", "cancelled", "canceled")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    companion object {
        const val RESULT_KEY_APPLIED = "apply_permission_applied_result"
        private const val RESULT_KEY_DATE = "apply_permission_date_calendar"

        fun newInstance(): ApplyPermissionBottomSheet = ApplyPermissionBottomSheet()
    }
}
