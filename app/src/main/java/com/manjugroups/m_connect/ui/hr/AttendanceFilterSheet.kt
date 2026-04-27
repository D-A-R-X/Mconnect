package com.manjugroups.m_connect.ui.hr

import android.app.DatePickerDialog
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Date-range filter sheet for the My Attendance screen. Same visual language
 * as the leave-category sheet (corner-rounded, full-width primary button).
 *
 * On Apply, emits a fragment result containing fromDate / toDate (ISO YYYY-MM-DD).
 */
class AttendanceFilterSheet : BottomSheetDialogFragment() {

    private val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val display = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

    private var fromDate: String = ""
    private var toDate: String = ""

    private var btnFrom: TextView? = null
    private var btnTo: TextView? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
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

        btnFrom = view.findViewById(R.id.btnFromDate)
        btnTo = view.findViewById(R.id.btnToDate)
        renderDates()

        btnFrom?.setOnClickListener { pickDate(initial = fromDate) { picked -> setFrom(picked) } }
        btnTo?.setOnClickListener { pickDate(initial = toDate) { picked -> setTo(picked) } }

        view.findViewById<View>(R.id.presetThisMonth).setOnClickListener { applyThisMonth() }
        view.findViewById<View>(R.id.presetLastMonth).setOnClickListener { applyLastMonth() }
        view.findViewById<View>(R.id.presetLast7).setOnClickListener { applyLast7Days() }

        view.findViewById<View>(R.id.btnFilterClose).setOnClickListener {
            dismissAllowingStateLoss()
        }
        view.findViewById<View>(R.id.btnFilterApply).setOnClickListener {
            if (fromDate.isBlank() || toDate.isBlank()) {
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

    private fun pickDate(initial: String, onPick: (String) -> Unit) {
        val cal = Calendar.getInstance()
        runCatching { ymd.parse(initial) }.getOrNull()?.let { cal.time = it }
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                val c = Calendar.getInstance().apply { set(year, month, day) }
                onPick(ymd.format(c.time))
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun setFrom(value: String) {
        fromDate = value
        renderDates()
    }

    private fun setTo(value: String) {
        toDate = value
        renderDates()
    }

    private fun renderDates() {
        btnFrom?.text = if (fromDate.isBlank()) "Select" else displayDate(fromDate)
        btnTo?.text = if (toDate.isBlank()) "Select" else displayDate(toDate)
    }

    private fun displayDate(iso: String): String =
        runCatching { display.format(ymd.parse(iso) ?: Date()) }.getOrDefault(iso)

    private fun applyThisMonth() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        fromDate = ymd.format(cal.time)
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        toDate = ymd.format(cal.time)
        renderDates()
    }

    private fun applyLastMonth() {
        val cal = Calendar.getInstance()
        cal.add(Calendar.MONTH, -1)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        fromDate = ymd.format(cal.time)
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        toDate = ymd.format(cal.time)
        renderDates()
    }

    private fun applyLast7Days() {
        val cal = Calendar.getInstance()
        toDate = ymd.format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, -6)
        fromDate = ymd.format(cal.time)
        renderDates()
    }

    companion object {
        const val RESULT_KEY = "attendance_filter_result"
        const val KEY_FROM = "fromDate"
        const val KEY_TO = "toDate"
        private const val ARG_FROM = "arg_from"
        private const val ARG_TO = "arg_to"

        fun newInstance(currentFrom: String?, currentTo: String?): AttendanceFilterSheet =
            AttendanceFilterSheet().apply {
                arguments = Bundle().apply {
                    if (currentFrom != null) putString(ARG_FROM, currentFrom)
                    if (currentTo != null) putString(ARG_TO, currentTo)
                }
            }
    }
}
