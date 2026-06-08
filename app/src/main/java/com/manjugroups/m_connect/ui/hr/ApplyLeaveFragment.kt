package com.manjugroups.m_connect.ui.hr

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.gson.Gson
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentApplyLeaveBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.ApplyLeaveRequest
import com.manjugroups.m_connect.ui.common.BottomActionInsets
import com.manjugroups.m_connect.ui.common.MonthYearPicker
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import com.manjugroups.m_connect.ui.common.navigateUp
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ApplyLeaveFragment : Fragment() {

    private data class ApiErrorResponse(
        val success: Boolean? = null,
        val error: String? = null,
        val message: String? = null
    )

    private var _binding: FragmentApplyLeaveBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager
    private val api = ApiService.create()
    private val gson = Gson()

    private var leaveTypes = listOf("casual", "sick", "earned")
    private var selectedLeaveType: String = "casual"
    private var selectedFromMillis: Long? = null
    private var selectedToMillis: Long? = null

    private val apiDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val labelDateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentApplyLeaveBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        binding.btnBack.setOnClickListener { navigateUp() }
        BottomActionInsets.applyAboveSystemNavAndTabs(binding.btnSubmit)
        binding.fieldLeaveCategory.setOnClickListener { showCategorySheet() }
        binding.fieldLeaveDuration.setOnClickListener { showDurationSheet() }
        // Submit Now now opens the confirmation modal; the real
        // applyLeave call only fires after the user picks "Yes, Submit"
        // in the sheet. Mirrors the design's third frame in the apply
        // flow ("Double-check your leave details…").
        binding.btnSubmit.setOnClickListener { promptSubmitConfirmation() }

        // Listen once at view-create so multiple sheet opens reuse the
        // same handler. The sheet emits a single boolean signalling
        // whether the user confirmed; we drop the result on the floor
        // when false and dismiss naturally.
        setFragmentResultListener(SubmitLeaveConfirmSheet.RESULT_KEY) { _, bundle ->
            val confirmed = bundle.getBoolean(SubmitLeaveConfirmSheet.KEY_CONFIRMED, false)
            if (confirmed) submitLeave()
        }

        updateDurationLabel()
        loadLeaveTypes()
    }

    /**
     * Validate the form locally before opening the confirmation sheet
     * so the user doesn't see the "Submit Leave" double-check modal
     * just to be told their description is blank. Same checks
     * submitLeave() runs — keeping them in sync.
     */
    private fun promptSubmitConfirmation() {
        val fromMillis = selectedFromMillis
        val toMillis = selectedToMillis
        val reason = binding.etReason.text.toString().trim()
        if (fromMillis == null || toMillis == null) {
            Toast.makeText(requireContext(), "Select leave duration", Toast.LENGTH_SHORT).show()
            return
        }
        if (toMillis < fromMillis) {
            Toast.makeText(requireContext(), "To date must be on or after from date", Toast.LENGTH_SHORT).show()
            return
        }
        if (reason.isBlank()) {
            Toast.makeText(requireContext(), "Enter leave description", Toast.LENGTH_SHORT).show()
            return
        }
        SubmitLeaveConfirmSheet
            .newInstance()
            .show(parentFragmentManager, "submit_leave_confirm")
    }

    private fun loadLeaveTypes() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val policy = api.getPolicy(session.bearerToken)
                val types = mutableListOf<String>()
                val lp = policy.policy?.leave
                if ((lp?.casualPerYear ?: 1) > 0) types.add("casual")
                if ((lp?.sickPerYear ?: 1) > 0) types.add("sick")
                if ((lp?.earnedPerYear ?: 1) > 0) types.add("earned")
                lp?.types?.forEach { t ->
                    if (t !in listOf("casual", "sick", "earned") && t !in types) types.add(t)
                }
                if (types.isNotEmpty()) {
                    leaveTypes = types
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // Coroutine cancelled by view-lifecycle teardown. Rethrow
                // so structured concurrency unwinds correctly — swallowing
                // it here previously made the coroutine resume past the
                // catch and touch `binding` after _binding was already
                // null on onDestroyView.
                throw ce
            } catch (_: Exception) {
                // Keep defaults when policy isn't available.
            }

            selectedLeaveType = leaveTypes.firstOrNull() ?: "casual"
            // Belt-and-suspenders: if for any reason the view was torn
            // down between the await and here (race, slow device), use
            // _binding? so the assignment no-ops instead of NPE-ing.
            _binding?.tvLeaveCategoryValue?.text = prettyType(selectedLeaveType)
        }
    }

    private fun submitLeave() {
        val fromMillis = selectedFromMillis
        val toMillis = selectedToMillis
        val reason = binding.etReason.text.toString().trim()

        if (fromMillis == null || toMillis == null) {
            Toast.makeText(requireContext(), "Select leave duration", Toast.LENGTH_SHORT).show()
            return
        }
        if (toMillis < fromMillis) {
            Toast.makeText(requireContext(), "To date must be on or after from date", Toast.LENGTH_SHORT).show()
            return
        }
        if (reason.isBlank()) {
            Toast.makeText(requireContext(), "Enter leave description", Toast.LENGTH_SHORT).show()
            return
        }

        val from = apiDateFormat.format(fromMillis)
        val to = apiDateFormat.format(toMillis)

        binding.tvSubmit.visibility = View.INVISIBLE
        binding.skeletonSubmit.visibility = View.VISIBLE
        SkeletonUtils.startSkeletonPulse(binding.skeletonSubmit)
        binding.btnSubmit.isClickable = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.applyLeave(
                    session.bearerToken,
                    ApplyLeaveRequest(
                        leaveType = selectedLeaveType,
                        fromDate = from,
                        toDate = to,
                        reason = reason,
                        reportingToId = session.reportingToId,
                        reportingToName = session.reportingToName,
                    )
                )
                if (resp.success) {
                    Toast.makeText(requireContext(), "Leave applied!", Toast.LENGTH_SHORT).show()
                    navigateUp()
                } else {
                    Toast.makeText(requireContext(), resp.error ?: "Failed", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), parseErrorMessage(e), Toast.LENGTH_SHORT).show()
            }
            _binding?.tvSubmit?.visibility = View.VISIBLE
            _binding?.skeletonSubmit?.let { SkeletonUtils.stopSkeletonPulse(it) }
            _binding?.skeletonSubmit?.visibility = View.GONE
            _binding?.btnSubmit?.isClickable = true
        }
    }

    private fun showCategorySheet() {
        val dialog = BottomSheetDialog(requireContext())
        val content = layoutInflater.inflate(R.layout.bottom_sheet_leave_category, null)
        dialog.setContentView(content)

        val container = content.findViewById<LinearLayout>(R.id.leaveCategoryContainer)
        val closeButton = content.findViewById<TextView>(R.id.btnCloseSheet)
        val submitButton = content.findViewById<TextView>(R.id.btnSubmitSheet)

        var selectedIndex = leaveTypes.indexOf(selectedLeaveType).coerceAtLeast(0)

        val rows = mutableListOf<View>()
        leaveTypes.forEachIndexed { index, type ->
            val row = layoutInflater.inflate(R.layout.item_leave_sheet_option, container, false)
            row.findViewById<TextView>(R.id.tvOption).text = prettyType(type)
            row.setOnClickListener {
                selectedIndex = index
                rows.forEachIndexed { rowIndex, view ->
                    val icon = view.findViewById<ImageView>(R.id.ivOptionCheck)
                    icon.setImageResource(
                        if (rowIndex == selectedIndex) R.drawable.ic_leave_radio_selected
                        else R.drawable.ic_leave_radio_unselected
                    )
                }
            }
            row.findViewById<ImageView>(R.id.ivOptionCheck).setImageResource(
                if (index == selectedIndex) R.drawable.ic_leave_radio_selected
                else R.drawable.ic_leave_radio_unselected
            )
            rows.add(row)
            container.addView(row)
        }

        closeButton.setOnClickListener { dialog.dismiss() }
        submitButton.setOnClickListener {
            val selected = leaveTypes.getOrElse(selectedIndex) { leaveTypes.firstOrNull() ?: "casual" }
            selectedLeaveType = selected
            binding.tvLeaveCategoryValue.text = prettyType(selected)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showDurationSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val content = layoutInflater.inflate(R.layout.bottom_sheet_leave_duration, null)
        dialog.setContentView(content)

        val monthLabel = content.findViewById<TextView>(R.id.tvMonthLabel)
        val prevMonth = content.findViewById<ImageView>(R.id.btnPrevMonth)
        val nextMonth = content.findViewById<ImageView>(R.id.btnNextMonth)
        val grid = content.findViewById<android.widget.GridLayout>(R.id.calendarGrid)
        val submitButton = content.findViewById<TextView>(R.id.btnDurationSubmit)
        val closeButton = content.findViewById<TextView>(R.id.btnDurationClose)

        val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        val dayFormat = SimpleDateFormat("d", Locale.getDefault())

        val displayMonth = Calendar.getInstance().apply {
            timeInMillis = selectedFromMillis ?: System.currentTimeMillis()
            set(Calendar.DAY_OF_MONTH, 1)
            clearTime()
        }

        var tempFrom = selectedFromMillis
        var tempTo = selectedToMillis

        lateinit var renderCalendar: () -> Unit

        fun buildDayCell(dayMillis: Long?, inCurrentMonth: Boolean): TextView {
            val cell = TextView(requireContext())
            val col = grid.childCount % 7
            val params = android.widget.GridLayout.LayoutParams(
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f),
                android.widget.GridLayout.spec(col, 1f)
            )
            params.width = 0
            params.height = dp(30)
            params.setMargins(dp(1), dp(1), dp(1), dp(1))
            cell.layoutParams = params
            cell.gravity = android.view.Gravity.CENTER
            cell.textSize = 12f

            if (dayMillis == null) {
                cell.text = ""
                return cell
            }

            val dayCalendar = Calendar.getInstance().apply { timeInMillis = dayMillis }
            cell.text = dayFormat.format(dayCalendar.time)

            val from = tempFrom
            val to = tempTo
            val isStart = from != null && sameDay(dayMillis, from)
            val isEnd = to != null && sameDay(dayMillis, to)
            val inRange = from != null && to != null && dayMillis in minOf(from, to)..maxOf(from, to)

            when {
                !inCurrentMonth -> {
                    cell.setTextColor(ContextCompat.getColor(requireContext(), R.color.lt_foreground_muted))
                }
                isStart || isEnd -> {
                    cell.setBackgroundResource(R.drawable.bg_leave_day_selected)
                    cell.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
                }
                inRange -> {
                    cell.setBackgroundResource(R.drawable.bg_leave_day_range)
                    cell.setTextColor(ContextCompat.getColor(requireContext(), R.color.lt_foreground_primary))
                }
                else -> {
                    cell.background = null
                    cell.setTextColor(ContextCompat.getColor(requireContext(), R.color.lt_foreground_primary))
                }
            }

            if (inCurrentMonth) {
                cell.setOnClickListener {
                    if (tempFrom == null || tempTo != null) {
                        tempFrom = dayMillis
                        tempTo = null
                    } else if (dayMillis < tempFrom!!) {
                        tempTo = tempFrom
                        tempFrom = dayMillis
                    } else {
                        tempTo = dayMillis
                    }
                    renderCalendar()
                }
            }
            return cell
        }

        renderCalendar = {
            monthLabel.text = monthFormat.format(displayMonth.time)
            grid.removeAllViews()

            val firstVisible = displayMonth.clone() as Calendar
            val monthStartWeekday = firstVisible.get(Calendar.DAY_OF_WEEK) - 1
            firstVisible.add(Calendar.DAY_OF_MONTH, -monthStartWeekday)

            repeat(42) { offset ->
                val day = firstVisible.clone() as Calendar
                day.add(Calendar.DAY_OF_MONTH, offset)
                day.clearTime()
                val inCurrent =
                    day.get(Calendar.MONTH) == displayMonth.get(Calendar.MONTH) &&
                        day.get(Calendar.YEAR) == displayMonth.get(Calendar.YEAR)
                grid.addView(buildDayCell(day.timeInMillis, inCurrent))
            }
        }

        prevMonth.setOnClickListener {
            displayMonth.add(Calendar.MONTH, -1)
            displayMonth.set(Calendar.DAY_OF_MONTH, 1)
            displayMonth.clearTime()
            renderCalendar()
        }

        nextMonth.setOnClickListener {
            displayMonth.add(Calendar.MONTH, 1)
            displayMonth.set(Calendar.DAY_OF_MONTH, 1)
            displayMonth.clearTime()
            renderCalendar()
        }

        monthLabel.setOnClickListener {
            MonthYearPicker.show(requireContext(), displayMonth) { year, month ->
                displayMonth.set(Calendar.YEAR, year)
                displayMonth.set(Calendar.MONTH, month)
                displayMonth.set(Calendar.DAY_OF_MONTH, 1)
                displayMonth.clearTime()
                renderCalendar()
            }
        }

        submitButton.setOnClickListener {
            val pickedFrom = tempFrom
            if (pickedFrom == null) {
                Toast.makeText(requireContext(), "Select leave start date", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val pickedTo = tempTo ?: pickedFrom
            selectedFromMillis = minOf(pickedFrom, pickedTo)
            selectedToMillis = maxOf(pickedFrom, pickedTo)
            updateDurationLabel()
            dialog.dismiss()
        }

        closeButton.setOnClickListener { dialog.dismiss() }

        renderCalendar()
        dialog.show()
    }

    private fun updateDurationLabel() {
        val fromMillis = selectedFromMillis
        val toMillis = selectedToMillis
        binding.tvLeaveDurationValue.text = when {
            fromMillis == null || toMillis == null -> "Select Duration"
            sameDay(fromMillis, toMillis) -> labelDateFormat.format(fromMillis)
            else -> "${labelDateFormat.format(fromMillis)} - ${labelDateFormat.format(toMillis)}"
        }
    }

    private fun prettyType(value: String): String {
        return value
            .replace('_', ' ')
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
                }
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

    private fun sameDay(firstMillis: Long, secondMillis: Long): Boolean {
        val first = Calendar.getInstance().apply { timeInMillis = firstMillis }
        val second = Calendar.getInstance().apply { timeInMillis = secondMillis }
        return first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
            first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
    }

    private fun Calendar.clearTime() {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onResume() {
        super.onResume()
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
