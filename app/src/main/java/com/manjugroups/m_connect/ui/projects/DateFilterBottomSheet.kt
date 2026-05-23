package com.manjugroups.m_connect.ui.projects

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.setFragmentResult
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import java.text.SimpleDateFormat
import java.util.*

/**
 * Custom Date Filter Bottom Sheet — pixel-perfect calendar range picker.
 * Allows picking a from/to date range.
 */
class DateFilterBottomSheet : BottomSheetDialogFragment() {

    private var currentMonth = Calendar.getInstance()
    private var startDate: Calendar? = null
    private var endDate: Calendar? = null

    private lateinit var tvMonthYear: TextView
    private lateinit var rvCalendar: RecyclerView
    private val adapter = CalendarAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_date_filter, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvMonthYear = view.findViewById(R.id.tvMonthYear)
        rvCalendar = view.findViewById(R.id.rvCalendar)

        rvCalendar.layoutManager = GridLayoutManager(requireContext(), 7)
        rvCalendar.adapter = adapter

        view.findViewById<View>(R.id.btnPrevMonth).setOnClickListener {
            currentMonth.add(Calendar.MONTH, -1)
            render()
        }
        view.findViewById<View>(R.id.btnNextMonth).setOnClickListener {
            currentMonth.add(Calendar.MONTH, 1)
            render()
        }

        view.findViewById<View>(R.id.btnSubmitDate).setOnClickListener {
            val start = startDate ?: return@setOnClickListener
            val end = endDate ?: start
            
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val result = Bundle().apply {
                putString(RESULT_FROM, fmt.format(start.time))
                putString(RESULT_TO, fmt.format(end.time))
            }
            setFragmentResult(REQUEST_KEY, result)
            dismissAllowingStateLoss()
        }

        render()
    }

    private fun render() {
        val fmt = SimpleDateFormat("MMMM yyyy", Locale.US)
        tvMonthYear.text = fmt.format(currentMonth.time)

        val days = mutableListOf<Calendar?>()
        val cal = currentMonth.clone() as Calendar
        cal.set(Calendar.DAY_OF_MONTH, 1)
        
        val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1
        repeat(firstDayOfWeek) { days.add(null) }

        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        repeat(daysInMonth) {
            days.add(cal.clone() as Calendar)
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }

        adapter.submit(days)
    }

    private inner class CalendarAdapter : RecyclerView.Adapter<CalendarAdapter.DayViewHolder>() {
        private val items = mutableListOf<Calendar?>()

        fun submit(list: List<Calendar?>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_calendar_day, parent, false)
            return DayViewHolder(v)
        }

        override fun onBindViewHolder(holder: DayViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class DayViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvDay: TextView = itemView.findViewById(R.id.tvDay)
            private val rangeBg: View = itemView.findViewById(R.id.rangeBg)
            private val selectedBg: View = itemView.findViewById(R.id.selectedBg)

            fun bind(cal: Calendar?) {
                if (cal == null) {
                    tvDay.text = ""
                    rangeBg.visibility = View.GONE
                    selectedBg.visibility = View.GONE
                    itemView.setOnClickListener(null)
                    return
                }

                val day = cal.get(Calendar.DAY_OF_MONTH)
                tvDay.text = day.toString()

                val isStart = isSameDay(cal, startDate)
                val isEnd = isSameDay(cal, endDate)
                val isInRange = isInRange(cal)

                selectedBg.visibility = if (isStart || isEnd) View.VISIBLE else View.GONE
                rangeBg.visibility = if (isInRange) View.VISIBLE else View.GONE
                
                if (isStart || isEnd) {
                    tvDay.setTextColor(Color.WHITE)
                } else {
                    tvDay.setTextColor(Color.parseColor("#101828"))
                }

                itemView.setOnClickListener {
                    handleDateClick(cal)
                }
            }

            private fun handleDateClick(cal: Calendar) {
                if (startDate == null || (startDate != null && endDate != null)) {
                    startDate = cal
                    endDate = null
                } else if (startDate != null && cal.before(startDate)) {
                    startDate = cal
                } else {
                    endDate = cal
                }
                notifyDataSetChanged()
            }

            private fun isSameDay(c1: Calendar, c2: Calendar?): Boolean {
                if (c2 == null) return false
                return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
                        c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
            }

            private fun isInRange(cal: Calendar): Boolean {
                val start = startDate ?: return false
                val end = endDate ?: return false
                return cal.after(start) && cal.before(end)
            }
        }
    }

    companion object {
        const val REQUEST_KEY = "DateFilterRequest"
        const val RESULT_FROM = "fromDate"
        const val RESULT_TO = "toDate"

        fun newInstance() = DateFilterBottomSheet()
    }
}
