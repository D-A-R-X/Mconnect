package com.manjugroups.m_connect.ui.hr

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Filter sheet for the My Attendance screen. Matches the design's "Filter" toast:
 *   - close icon (left) + centered title + subtitle
 *   - three preset chips: This month / Last month / Last 7 days
 *   - date range field that opens the Material range picker (brand-blue themed)
 *   - Cancel (outline green) + Select (filled green) actions
 *
 * On Select, emits a fragment result with fromDate / toDate (ISO yyyy-MM-dd).
 */
class AttendanceFilterSheet : BottomSheetDialogFragment() {

    private val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val display = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

    private var fromDate: String = ""
    private var toDate: String = ""

    private var tvDateRangeValue: TextView? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                it.elevation = 0f
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_attendance_filter, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        fromDate = arguments?.getString(ARG_FROM).orEmpty()
        toDate = arguments?.getString(ARG_TO).orEmpty()

        tvDateRangeValue = view.findViewById(R.id.tvDateRangeValue)
        renderDateRange()

        // Capture the range chosen by the calendar sheet.
        childFragmentManager.setFragmentResultListener(CAL_RESULT_KEY, this) { _, bundle ->
            fromDate = bundle.getString(CalendarRangePickerSheet.KEY_FROM).orEmpty()
            toDate = bundle.getString(CalendarRangePickerSheet.KEY_TO).orEmpty()
            renderDateRange()
        }

        view.findViewById<View>(R.id.dateRangeField).setOnClickListener { openCalendarPicker() }

        view.findViewById<View>(R.id.presetThisMonth).setOnClickListener { applyThisMonth() }
        view.findViewById<View>(R.id.presetLastMonth).setOnClickListener { applyLastMonth() }
        view.findViewById<View>(R.id.presetLast7).setOnClickListener { applyLast7Days() }

        view.findViewById<View>(R.id.btnFilterClose).setOnClickListener {
            dismissAllowingStateLoss()
        }
        view.findViewById<View>(R.id.btnFilterCancel).setOnClickListener {
            dismissAllowingStateLoss()
        }
        view.findViewById<View>(R.id.btnFilterApply).setOnClickListener {
            if (fromDate.isBlank() || toDate.isBlank()) {
                // Nothing picked yet — close silently.
                dismissAllowingStateLoss()
                return@setOnClickListener
            }
            // Swap if user picked them reversed.
            val (f, t) = if (fromDate <= toDate) fromDate to toDate else toDate to fromDate
            setFragmentResult(
                RESULT_KEY,
                bundleOf(KEY_FROM to f, KEY_TO to t)
            )
            dismissAllowingStateLoss()
        }
    }

    // ---------- Calendar picker ----------

    private fun openCalendarPicker() {
        val today = ymd.format(Date())
        CalendarRangePickerSheet.newInstance(
            // Custom titles for the attendance filter context — design uses
            // "Leave Duration" / "Select Leave Duration" elsewhere; here we say
            // we're picking the attendance date range.
            title = "Date Range",
            subtitle = "Pick a date range",
            initialFrom = fromDate.ifBlank { null },
            initialTo = toDate.ifBlank { null },
            maxDate = today,
            resultKey = CAL_RESULT_KEY,
        ).show(childFragmentManager, "cal_range")
    }

    // ---------- Presets ----------

    private fun applyThisMonth() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        fromDate = ymd.format(cal.time)
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        toDate = ymd.format(cal.time)
        renderDateRange()
    }

    private fun applyLastMonth() {
        val cal = Calendar.getInstance()
        cal.add(Calendar.MONTH, -1)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        fromDate = ymd.format(cal.time)
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        toDate = ymd.format(cal.time)
        renderDateRange()
    }

    private fun applyLast7Days() {
        val cal = Calendar.getInstance()
        toDate = ymd.format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, -6)
        fromDate = ymd.format(cal.time)
        renderDateRange()
    }

    private fun renderDateRange() {
        val text = when {
            fromDate.isBlank() || toDate.isBlank() -> "Select date range"
            fromDate == toDate -> displayDate(fromDate)
            else -> "${displayDate(fromDate)} - ${displayDate(toDate)}"
        }
        tvDateRangeValue?.text = text
    }

    private fun displayDate(iso: String): String =
        runCatching { display.format(ymd.parse(iso) ?: Date()) }.getOrDefault(iso)

    companion object {
        const val RESULT_KEY = "attendance_filter_result"
        const val KEY_FROM = "fromDate"
        const val KEY_TO = "toDate"
        private const val ARG_FROM = "arg_from"
        private const val ARG_TO = "arg_to"
        private const val CAL_RESULT_KEY = "attendance_filter_cal_range"

        fun newInstance(currentFrom: String?, currentTo: String?): AttendanceFilterSheet =
            AttendanceFilterSheet().apply {
                arguments = Bundle().apply {
                    if (currentFrom != null) putString(ARG_FROM, currentFrom)
                    if (currentTo != null) putString(ARG_TO, currentTo)
                }
            }
    }
}
