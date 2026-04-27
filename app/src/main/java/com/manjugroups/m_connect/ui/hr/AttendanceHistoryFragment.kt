package com.manjugroups.m_connect.ui.hr

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.graphics.Color
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentAttendanceHistoryBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.AttendanceRecord
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class AttendanceHistoryFragment : Fragment() {

    private var _binding: FragmentAttendanceHistoryBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager
    private val api = ApiService.create()

    private var filterFromDate: String = ""
    private var filterToDate: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAttendanceHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }

        // Default range = current calendar month
        val cal = Calendar.getInstance()
        val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        filterToDate = ymd.format(cal.time)
        filterFromDate = String.format(
            Locale.US,
            "%04d-%02d-01",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1
        )
        updateRangeLabel()

        binding.btnAttendanceFilter.setOnClickListener {
            AttendanceFilterSheet
                .newInstance(filterFromDate, filterToDate)
                .show(parentFragmentManager, "attendance_filter")
        }

        setFragmentResultListener(AttendanceFilterSheet.RESULT_KEY) { _, bundle ->
            filterFromDate = bundle.getString(AttendanceFilterSheet.KEY_FROM).orEmpty()
            filterToDate = bundle.getString(AttendanceFilterSheet.KEY_TO).orEmpty()
            updateRangeLabel()
            loadData()
        }

        loadData()
    }

    private fun updateRangeLabel() {
        val parseFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val display = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        val from = runCatching { parseFmt.parse(filterFromDate) }.getOrNull()
        val to = runCatching { parseFmt.parse(filterToDate) }.getOrNull()
        binding.tvMonth.text = when {
            from != null && to != null && sameMonthYear(from, to) ->
                SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(from)
            from != null && to != null ->
                "${display.format(from)} – ${display.format(to)}"
            else -> ""
        }
    }

    private fun sameMonthYear(a: Date, b: Date): Boolean {
        val ca = Calendar.getInstance().apply { time = a }
        val cb = Calendar.getInstance().apply { time = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.MONTH) == cb.get(Calendar.MONTH) &&
            ca.get(Calendar.DAY_OF_MONTH) == 1 &&
            cb.get(Calendar.DAY_OF_MONTH) == cb.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    private fun loadData() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getMyAttendance(
                    session.bearerToken,
                    fromDate = filterFromDate,
                    toDate = filterToDate
                )
                if (resp.success) {
                    val records = resp.records

                    val daysPresent = records.count { r ->
                        r.approvedAttendance == "present" || r.status == "auto-approved" || r.status == "approved"
                    }
                    val totalMinutes = records.sumOf { it.totalMinutes ?: 0 }
                    val totalHours = totalMinutes / 60

                    binding.tvTotalDays.text = daysPresent.toString()
                    binding.tvTotalHours.text = "${totalHours}h"

                    renderRecords(records)
                }
            } catch (_: Exception) { }
        }
    }

    private fun renderRecords(records: List<AttendanceRecord>) {
        binding.attendanceList.removeAllViews()

        val parseFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dateFmt = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())

        records.forEach { record ->
            val card = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_attendance_history_card, binding.attendanceList, false)

            val parsed = record.date?.let { runCatching { parseFmt.parse(it) }.getOrNull() }
            card.findViewById<TextView>(R.id.tvHistoryItemDate).text =
                parsed?.let { dateFmt.format(it) } ?: (record.date ?: "")

            val mins = record.totalMinutes ?: 0
            val hours = mins / 60
            val minutes = mins % 60
            card.findViewById<TextView>(R.id.tvHistoryItemHours).text =
                String.format(Locale.getDefault(), "%02d:%02d:00 hrs", hours, minutes)

            val firstIn = record.punchInTime ?: record.sessions?.firstOrNull()?.punchInTime
            val lastOut = record.punchOutTime ?: record.sessions?.lastOrNull()?.punchOutTime
            val inLabel = firstIn?.let(::formatIsoTime) ?: "--"
            val outLabel = if (record.hasOpenSession == true) "--"
                else (lastOut?.let(::formatIsoTime) ?: "--")
            card.findViewById<TextView>(R.id.tvHistoryItemRange).text =
                "$inLabel - $outLabel"

            binding.attendanceList.addView(card)
        }
    }

    private fun formatIsoTime(iso: String): String {
        val millis = parseIsoMillis(iso) ?: return "--"
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
                // try next format
            }
        }
        return null
    }

    private fun resolveColor(attr: Int): Int {
        val tv = TypedValue()
        requireContext().theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    override fun onResume() {
        super.onResume()
        // White system status bar with dark icons to match the white in-app header.
        (activity as? MainActivity)?.setTopBarAppearance(Color.WHITE, true)
    }

    override fun onPause() {
        // Restore the default tab top-bar look for sibling tabs.
        (activity as? MainActivity)?.setTopBarAppearance(
            Color.parseColor("#FEFEFE"), true
        )
        super.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
