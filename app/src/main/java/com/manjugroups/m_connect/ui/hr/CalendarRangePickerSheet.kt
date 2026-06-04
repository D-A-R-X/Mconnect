package com.manjugroups.m_connect.ui.hr

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.ui.common.MonthYearPicker
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Custom date-range calendar matching the "Leave Duration" design — month nav at the
 * top, weekday header, 6×7 day grid, "Submit Date" (filled green) + "Close Message"
 * (outline green) actions at the bottom.
 *
 * Selection model: user taps a start day, then an end day. Tapping again resets and
 * starts over. Start and end render as solid blue circles; days strictly between them
 * render with a connecting bar so the range reads as one continuous selection.
 *
 * On Submit Date, emits a fragment result with fromDate / toDate (ISO yyyy-MM-dd).
 * Allows callers to customize the title / subtitle (re-used for filter date range,
 * leave duration, permission duration, etc.).
 */
class CalendarRangePickerSheet : BottomSheetDialogFragment() {

    private val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val monthLabel = SimpleDateFormat("MMMM yyyy", Locale.getDefault())

    private val displayedMonth: Calendar = Calendar.getInstance()
    private var rangeStart: Calendar? = null
    private var rangeEnd: Calendar? = null

    private var resultKey: String = DEFAULT_RESULT_KEY
    private var maxDate: Calendar? = null  // null = no upper bound
    private var minDate: Calendar? = null  // null = no lower bound

    private var tvMonth: TextView? = null
    private var grid: LinearLayout? = null

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
    ): View = inflater.inflate(R.layout.bottom_sheet_calendar_range_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Pull arguments
        val args = arguments
        resultKey = args?.getString(ARG_RESULT_KEY) ?: DEFAULT_RESULT_KEY
        val title = args?.getString(ARG_TITLE) ?: "Leave Duration"
        val subtitle = args?.getString(ARG_SUBTITLE) ?: "Select Leave Duration"
        view.findViewById<TextView>(R.id.tvCalendarTitle).text = title
        view.findViewById<TextView>(R.id.tvCalendarSubtitle).text = subtitle

        val initialFrom = args?.getString(ARG_FROM)
        val initialTo = args?.getString(ARG_TO)
        rangeStart = parseIso(initialFrom)
        rangeEnd = parseIso(initialTo)
        // Show the month that contains the start (or today).
        (rangeStart ?: Calendar.getInstance()).let {
            displayedMonth.time = it.time
            displayedMonth.set(Calendar.DAY_OF_MONTH, 1)
        }

        // Optional cap (e.g. "no future days" for the attendance filter).
        args?.getString(ARG_MAX_DATE)?.let { maxIso ->
            maxDate = parseIso(maxIso)
        }
        // Optional floor (e.g. "no past days" for inspection reschedule —
        // dates before the floor render greyed and refuse taps).
        args?.getString(ARG_MIN_DATE)?.let { minIso ->
            minDate = parseIso(minIso)
        }

        tvMonth = view.findViewById(R.id.tvCalendarMonth)
        grid = view.findViewById(R.id.calendarGrid)

        view.findViewById<View>(R.id.btnCalendarPrev).setOnClickListener {
            displayedMonth.add(Calendar.MONTH, -1)
            rebuild()
        }
        view.findViewById<View>(R.id.btnCalendarNext).setOnClickListener {
            displayedMonth.add(Calendar.MONTH, 1)
            rebuild()
        }
        tvMonth?.setOnClickListener {
            MonthYearPicker.show(requireContext(), displayedMonth, minDate, maxDate) { year, month ->
                displayedMonth.set(Calendar.YEAR, year)
                displayedMonth.set(Calendar.MONTH, month)
                displayedMonth.set(Calendar.DAY_OF_MONTH, 1)
                rebuild()
            }
        }
        view.findViewById<View>(R.id.btnCalendarClose).setOnClickListener {
            dismissAllowingStateLoss()
        }
        view.findViewById<View>(R.id.btnCalendarSubmit).setOnClickListener {
            val start = rangeStart
            val end = rangeEnd ?: rangeStart  // single-day selection counts as start==end
            if (start == null) {
                dismissAllowingStateLoss()
                return@setOnClickListener
            }
            val finalEnd = end ?: start
            val (a, b) = if (start.timeInMillis <= finalEnd.timeInMillis) {
                start to finalEnd
            } else {
                finalEnd to start
            }
            setFragmentResult(
                resultKey,
                bundleOf(KEY_FROM to ymd.format(a.time), KEY_TO to ymd.format(b.time))
            )
            dismissAllowingStateLoss()
        }

        rebuild()
    }

    private fun rebuild() {
        tvMonth?.text = monthLabel.format(displayedMonth.time)
        grid?.let { buildGrid(it) }
    }

    private fun buildGrid(container: LinearLayout) {
        container.removeAllViews()
        val ctx = container.context

        // First day of displayed month
        val first = (displayedMonth.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH)
        // Sunday = 1 ... Saturday = 7. We render columns Sun → Sat so leading blanks = (firstDayOfWeek - 1).
        val leadingBlanks = first.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
        val totalCells = leadingBlanks + daysInMonth
        val rows = (totalCells + 6) / 7  // ceil

        // Previous month for trailing-end-of-prev-month numbers (we render those greyed)
        val prevMonth = (displayedMonth.clone() as Calendar).apply {
            add(Calendar.MONTH, -1)
        }
        val prevMonthDays = prevMonth.getActualMaximum(Calendar.DAY_OF_MONTH)

        var dayCounter = 1
        var nextMonthCounter = 1
        for (r in 0 until rows) {
            val row = LinearLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                orientation = LinearLayout.HORIZONTAL
                weightSum = 7f
            }
            for (col in 0 until 7) {
                val cellIndex = r * 7 + col
                val cell = makeDayCell(ctx)
                when {
                    cellIndex < leadingBlanks -> {
                        // Leading day from previous month
                        val day = prevMonthDays - (leadingBlanks - cellIndex - 1)
                        styleDayCell(cell, day, inCurrentMonth = false, dayInThisMonth = -1)
                    }
                    dayCounter <= daysInMonth -> {
                        val day = dayCounter
                        styleDayCell(cell, day, inCurrentMonth = true, dayInThisMonth = day)
                        cell.setOnClickListener { onDayClick(day) }
                        dayCounter++
                    }
                    else -> {
                        // Trailing day from next month
                        val day = nextMonthCounter
                        styleDayCell(cell, day, inCurrentMonth = false, dayInThisMonth = -1)
                        nextMonthCounter++
                    }
                }
                row.addView(cell)
            }
            container.addView(row)
        }
    }

    private fun makeDayCell(ctx: android.content.Context): TextView {
        val tv = TextView(ctx)
        val lp = LinearLayout.LayoutParams(0, dp(40), 1f)
        tv.layoutParams = lp
        tv.gravity = Gravity.CENTER
        tv.textSize = 13f
        tv.includeFontPadding = false
        tv.typeface = ResourcesCompat.getFont(ctx, R.font.inter_medium)
        return tv
    }

    private fun styleDayCell(cell: TextView, dayNumber: Int, inCurrentMonth: Boolean, dayInThisMonth: Int) {
        cell.text = dayNumber.toString()
        cell.background = null
        cell.isClickable = false

        if (!inCurrentMonth) {
            cell.setTextColor(Color.parseColor("#D0D5DD"))
            return
        }

        // Out-of-bounds (after maxDate) — render greyed-out, non-clickable.
        val cellCal = (displayedMonth.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, dayInThisMonth)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val capped = maxDate?.let { cellCal.timeInMillis > stripTime(it).timeInMillis } ?: false
        val floored = minDate?.let { cellCal.timeInMillis < stripTime(it).timeInMillis } ?: false
        if (capped || floored) {
            cell.setTextColor(Color.parseColor("#D0D5DD"))
            return
        }

        val start = rangeStart?.let { stripTime(it) }
        val end = rangeEnd?.let { stripTime(it) }

        val isStart = start != null && cellCal.timeInMillis == start.timeInMillis
        val isEnd = end != null && cellCal.timeInMillis == end.timeInMillis
        val isInBetween = start != null && end != null &&
            cellCal.timeInMillis > start.timeInMillis &&
            cellCal.timeInMillis < end.timeInMillis

        when {
            isStart && isEnd -> {
                cell.background = ContextCompat.getDrawable(cell.context, R.drawable.bg_calendar_day_selected)
                cell.setTextColor(Color.WHITE)
            }
            isStart -> {
                cell.background = if (end != null && end.timeInMillis > start!!.timeInMillis) {
                    ContextCompat.getDrawable(cell.context, R.drawable.bg_calendar_day_range_start)
                } else {
                    ContextCompat.getDrawable(cell.context, R.drawable.bg_calendar_day_selected)
                }
                cell.setTextColor(Color.WHITE)
            }
            isEnd -> {
                cell.background = ContextCompat.getDrawable(cell.context, R.drawable.bg_calendar_day_range_end)
                cell.setTextColor(Color.WHITE)
            }
            isInBetween -> {
                cell.background = ContextCompat.getDrawable(cell.context, R.drawable.bg_calendar_day_in_range)
                cell.setTextColor(Color.WHITE)
            }
            else -> {
                cell.setTextColor(Color.parseColor("#101828"))
            }
        }
    }

    private fun onDayClick(day: Int) {
        val tapped = (displayedMonth.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, day)
        }
        val tappedStripped = stripTime(tapped)
        // Out-of-bounds taps are no-ops — matches the greyed-out
        // styling so a (cap|floor) day looks AND behaves disabled.
        maxDate?.let { if (tappedStripped.timeInMillis > stripTime(it).timeInMillis) return }
        minDate?.let { if (tappedStripped.timeInMillis < stripTime(it).timeInMillis) return }
        val start = rangeStart
        val end = rangeEnd

        when {
            start == null -> {
                rangeStart = tappedStripped
                rangeEnd = null
            }
            end == null -> {
                // Second tap → set end. If before start, swap.
                if (tappedStripped.timeInMillis < start.timeInMillis) {
                    rangeEnd = start
                    rangeStart = tappedStripped
                } else {
                    rangeEnd = tappedStripped
                }
            }
            else -> {
                // Range already complete → reset and start fresh.
                rangeStart = tappedStripped
                rangeEnd = null
            }
        }
        rebuild()
    }

    private fun parseIso(iso: String?): Calendar? {
        if (iso.isNullOrBlank()) return null
        return runCatching {
            val date = ymd.parse(iso) ?: return null
            Calendar.getInstance().apply {
                time = date
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        }.getOrNull()
    }

    private fun stripTime(c: Calendar): Calendar =
        (c.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val DEFAULT_RESULT_KEY = "calendar_range_result"
        const val KEY_FROM = "fromDate"
        const val KEY_TO = "toDate"

        private const val ARG_TITLE = "arg_title"
        private const val ARG_SUBTITLE = "arg_subtitle"
        private const val ARG_FROM = "arg_from"
        private const val ARG_TO = "arg_to"
        private const val ARG_MAX_DATE = "arg_max_date"
        private const val ARG_MIN_DATE = "arg_min_date"
        private const val ARG_RESULT_KEY = "arg_result_key"

        fun newInstance(
            title: String = "Leave Duration",
            subtitle: String = "Select Leave Duration",
            initialFrom: String? = null,
            initialTo: String? = null,
            maxDate: String? = null,
            minDate: String? = null,
            resultKey: String = DEFAULT_RESULT_KEY,
        ): CalendarRangePickerSheet = CalendarRangePickerSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_TITLE, title)
                putString(ARG_SUBTITLE, subtitle)
                if (initialFrom != null) putString(ARG_FROM, initialFrom)
                if (initialTo != null) putString(ARG_TO, initialTo)
                if (maxDate != null) putString(ARG_MAX_DATE, maxDate)
                if (minDate != null) putString(ARG_MIN_DATE, minDate)
                putString(ARG_RESULT_KEY, resultKey)
            }
        }
    }
}
