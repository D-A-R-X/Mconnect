package com.manjugroups.m_connect.ui.hr

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAttendanceHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }

        val cal = Calendar.getInstance()
        val monthFmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        binding.tvMonth.text = monthFmt.format(cal.time)

        loadData()
    }

    private fun loadData() {
        val cal = Calendar.getInstance()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
        val monthStart = String.format("%04d-%02d-01", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getMyAttendance(session.bearerToken, fromDate = monthStart, toDate = today)
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

        val dateFmt = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
        val parseFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        records.forEach { record ->
            val card = LayoutInflater.from(requireContext()).inflate(R.layout.item_attendance, binding.attendanceList, false)

            val dateStr = record.date?.let {
                try { dateFmt.format(parseFmt.parse(it)!!) } catch (_: Exception) { it }
            } ?: ""
            card.findViewById<TextView>(R.id.tvAttDate).text = dateStr

            val mins = record.totalMinutes ?: 0
            card.findViewById<TextView>(R.id.tvAttHours).text = "${mins / 60}h ${mins % 60}m"

            val badge = card.findViewById<TextView>(R.id.tvAttStatus)
            val status = record.approvedAttendance ?: record.status ?: "pending"
            when {
                status == "present" || status == "auto-approved" || status == "approved" -> {
                    badge.text = "Present"
                    badge.setBackgroundResource(R.drawable.bg_badge_success)
                    badge.setTextColor(resolveColor(R.attr.colorSuccess))
                }
                status == "half-day" -> {
                    badge.text = "Half Day"
                    badge.setBackgroundResource(R.drawable.bg_badge_warning)
                    badge.setTextColor(resolveColor(R.attr.colorWarning))
                }
                else -> {
                    badge.text = status.replaceFirstChar { it.uppercase() }
                    badge.setBackgroundResource(R.drawable.bg_badge_error)
                    badge.setTextColor(resolveColor(R.attr.colorError))
                }
            }

            binding.attendanceList.addView(card)
        }
    }

    private fun resolveColor(attr: Int): Int {
        val tv = TypedValue()
        requireContext().theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
