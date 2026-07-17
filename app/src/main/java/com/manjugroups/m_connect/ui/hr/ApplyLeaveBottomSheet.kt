package com.manjugroups.m_connect.ui.hr

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.gson.Gson
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.ApplyLeaveRequest
import com.manjugroups.m_connect.ui.common.MonthYearPicker
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ApplyLeaveBottomSheet : BottomSheetDialogFragment() {

    private data class ApiErrorResponse(
        val success: Boolean? = null,
        val error: String? = null,
        val message: String? = null
    )

    private lateinit var session: SessionManager
    private val api = ApiService.create()
    private val gson = Gson()

    // Fallback used only when the HR policy can't be fetched; the live list
    // comes from the policy allocations (see loadLeaveTypes) and mirrors the
    // web defaults (casual 12 / sick 12 / earned 15 — all enabled).
    private var leaveTypes = listOf("casual", "sick", "earned", "unpaid", "half_day", "compensatory")
    private var selectedLeaveType: String = "casual"
    private var selectedFromMillis: Long? = null
    private var selectedToMillis: Long? = null
    private var selectedSession: String = "Morning"

    // Comp Off: the credit pool the "Compensatory Off" category spends from.
    // A credit is valid only within its earned calendar month, so picking a
    // credit constrains which day can be taken (see showDurationSheet).
    private var compCredits: List<com.manjugroups.m_connect.network.CompOffCredit> = emptyList()
    private var selectedCredit: com.manjugroups.m_connect.network.CompOffCredit? = null
    private var compCreditsLoaded = false

    private val apiDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val labelDateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
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
    ): View = inflater.inflate(R.layout.bottom_sheet_apply_leave, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        val fieldLeaveCategory = view.findViewById<View>(R.id.fieldLeaveCategory)
        val fieldLeaveDuration = view.findViewById<View>(R.id.fieldLeaveDuration)
        val etReason = view.findViewById<EditText>(R.id.etReason)
        val btnSubmit = view.findViewById<View>(R.id.btnSubmit)

        val fieldCompCredit = view.findViewById<View>(R.id.fieldCompCredit)

        fieldLeaveCategory.setOnClickListener { showCategorySheet() }
        fieldLeaveDuration.setOnClickListener { showDurationSheet() }
        fieldCompCredit.setOnClickListener { showCompCreditSheet() }
        btnSubmit.setOnClickListener { promptSubmitConfirmation() }

        // Setup session buttons for Half Day
        val btnSessionMorning = view.findViewById<View>(R.id.btnSessionMorning)
        val btnSessionAfternoon = view.findViewById<View>(R.id.btnSessionAfternoon)
        val tvSessionMorning = view.findViewById<TextView>(R.id.tvSessionMorning)
        val tvSessionAfternoon = view.findViewById<TextView>(R.id.tvSessionAfternoon)

        fun updateSessionUi() {
            if (selectedSession == "Morning") {
                btnSessionMorning?.setBackgroundResource(R.drawable.bg_leave_sheet_option_selected)
                tvSessionMorning?.setTextColor(Color.parseColor("#1D4ED8"))
                btnSessionAfternoon?.setBackgroundResource(R.drawable.bg_leave_sheet_option)
                tvSessionAfternoon?.setTextColor(Color.parseColor("#344054"))
            } else {
                btnSessionMorning?.setBackgroundResource(R.drawable.bg_leave_sheet_option)
                tvSessionMorning?.setTextColor(Color.parseColor("#344054"))
                btnSessionAfternoon?.setBackgroundResource(R.drawable.bg_leave_sheet_option_selected)
                tvSessionAfternoon?.setTextColor(Color.parseColor("#1D4ED8"))
            }
        }

        btnSessionMorning?.setOnClickListener {
            selectedSession = "Morning"
            updateSessionUi()
        }
        btnSessionAfternoon?.setOnClickListener {
            selectedSession = "Afternoon"
            updateSessionUi()
        }

        // Setup real-time validation text watcher on reason input
        etReason.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateSubmitButtonState()
            }
        })

        // Listen once at view-create so multiple sheet opens reuse the
        // same handler. The sheet emits a single boolean signalling
        // whether the user confirmed; we drop the result on the floor
        // when false and dismiss naturally.
        setFragmentResultListener(SubmitLeaveConfirmSheet.RESULT_KEY) { _, bundle ->
            val confirmed = bundle.getBoolean(SubmitLeaveConfirmSheet.KEY_CONFIRMED, false)
            if (confirmed) submitLeave()
        }

        setFragmentResultListener(LeaveSubmittedSuccessSheet.RESULT_KEY) { _, bundle ->
            val ok = bundle.getBoolean(LeaveSubmittedSuccessSheet.KEY_OK, false)
            if (ok) {
                parentFragmentManager.setFragmentResult(RESULT_KEY_APPLIED, Bundle().apply { putBoolean("success", true) })
                dismissAllowingStateLoss()
            }
        }

        updateDurationLabel()
        loadLeaveTypes()
        applyTypeUi()
        updateSessionUi()
        updateSubmitButtonState() // Initial disable
    }

    private fun updateSubmitButtonState() {
        val view = view ?: return
        val btnSubmit = view.findViewById<View>(R.id.btnSubmit)

        val isEnabled = if (selectedLeaveType == "compensatory") {
            selectedCredit != null && selectedFromMillis != null
        } else {
            selectedFromMillis != null && selectedToMillis != null
        }

        if (isEnabled) {
            btnSubmit.setBackgroundResource(R.drawable.bg_apply_leave_btn_enabled)
            btnSubmit.isEnabled = true
        } else {
            btnSubmit.setBackgroundResource(R.drawable.bg_apply_leave_btn_disabled)
            btnSubmit.isEnabled = false
        }
    }

    /**
     * Swap the form between the three modes driven by the selected category:
     * normal leave (duration range + description), half-day (adds the
     * morning/afternoon session picker) and Compensatory Off (credit picker +
     * a single in-month date, no free-text reason — matching the web flow).
     */
    private fun applyTypeUi() {
        val view = view ?: return
        val layoutHalfDaySession = view.findViewById<View>(R.id.layoutHalfDaySession)
        val layoutCompCredit = view.findViewById<View>(R.id.layoutCompCredit)
        val layoutDescription = view.findViewById<View>(R.id.layoutDescription)
        val lblLeaveDuration = view.findViewById<TextView>(R.id.lblLeaveDuration)

        val isComp = selectedLeaveType == "compensatory"
        val isHalf = selectedLeaveType == "half_day"

        layoutHalfDaySession?.visibility = if (isHalf) View.VISIBLE else View.GONE
        layoutCompCredit?.visibility = if (isComp) View.VISIBLE else View.GONE
        layoutDescription?.visibility = if (isComp) View.GONE else View.VISIBLE
        lblLeaveDuration?.text = if (isComp) "Comp Off Date" else "Leave Duration"

        if (isComp) loadCompCredits()
    }

    /**
     * Validate the form locally before opening the confirmation sheet
     * so the user doesn't see the "Submit Leave" double-check modal
     * just to be told their description is blank. Same checks
     * submitLeave() runs — keeping them in sync.
     */
    private fun promptSubmitConfirmation() {
        if (selectedLeaveType == "compensatory") {
            if (selectedCredit == null) {
                Toast.makeText(requireContext(), "Select a comp-off credit", Toast.LENGTH_SHORT).show()
                return
            }
            if (selectedFromMillis == null) {
                Toast.makeText(requireContext(), "Select the day to take", Toast.LENGTH_SHORT).show()
                return
            }
            SubmitLeaveConfirmSheet
                .newInstance()
                .show(parentFragmentManager, "submit_leave_confirm")
            return
        }

        val fromMillis = selectedFromMillis
        val toMillis = selectedToMillis

        if (fromMillis == null || toMillis == null) {
            Toast.makeText(requireContext(), "Select leave duration", Toast.LENGTH_SHORT).show()
            return
        }
        if (toMillis < fromMillis) {
            Toast.makeText(requireContext(), "To date must be on or after from date", Toast.LENGTH_SHORT).show()
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
                val lp = policy.policy?.leave
                if (lp != null) {
                    // Same rule as the web Apply Leave dropdown: a tracked type
                    // is offered only while its HR-policy allocation is > 0
                    // (an explicit 0 disables the category); a missing value
                    // falls back to the web defaults (12/12/15). Unpaid has no
                    // allocation and is always available.
                    val types = mutableListOf<String>()
                    if ((lp.casualPerYear ?: 12) > 0) types.add("casual")
                    if ((lp.sickPerYear ?: 12) > 0) types.add("sick")
                    if ((lp.earnedPerYear ?: 15) > 0) types.add("earned")
                    types.add("unpaid")
                    // half_day is submitted as a casual half-day, so it is only
                    // meaningful while casual leave is enabled.
                    if (types.contains("casual")) types.add("half_day")
                    // Compensatory Off spends earned comp-off credits through a
                    // separate flow (compoff/apply). Always offered — like web —
                    // and gated at submit-time by whether the user has credits.
                    types.add("compensatory")
                    leaveTypes = types
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (_: Exception) {
                // Keep defaults when policy isn't available.
                leaveTypes = listOf("casual", "sick", "earned", "unpaid", "half_day", "compensatory")
            }

            selectedLeaveType = leaveTypes.firstOrNull() ?: "casual"
            view?.findViewById<TextView>(R.id.tvLeaveCategoryValue)?.text = prettyType(selectedLeaveType)
            applyTypeUi()
            updateSubmitButtonState()
        }
    }

    private fun loadCompCredits() {
        if (compCreditsLoaded) {
            renderCompCreditState()
            return
        }
        compCreditsLoaded = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getCompOffCredits(session.bearerToken)
                compCredits = resp.credits
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (_: Exception) {
                compCredits = emptyList()
            }
            renderCompCreditState()
        }
    }

    /** Reflect the loaded credit pool: show the empty hint or leave the picker
     *  ready, and keep the submit button in sync. */
    private fun renderCompCreditState() {
        val view = view ?: return
        if (selectedLeaveType != "compensatory") return
        val tvCompEmpty = view.findViewById<View>(R.id.tvCompEmpty)
        val fieldCompCredit = view.findViewById<View>(R.id.fieldCompCredit)
        val hasCredits = compCredits.isNotEmpty()
        tvCompEmpty?.visibility = if (hasCredits) View.GONE else View.VISIBLE
        fieldCompCredit?.isEnabled = hasCredits
        fieldCompCredit?.alpha = if (hasCredits) 1f else 0.5f
        updateSubmitButtonState()
    }

    private fun showCompCreditSheet() {
        if (compCredits.isEmpty()) {
            Toast.makeText(requireContext(), "No comp-off credits available", Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = BottomSheetDialog(requireContext())
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog).findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.setBackgroundColor(Color.TRANSPARENT)
                it.elevation = 0f
            }
        }
        val content = layoutInflater.inflate(R.layout.bottom_sheet_leave_category, null)
        dialog.setContentView(content)

        content.findViewById<TextView>(R.id.tvSheetTitle)?.text = "Comp Off Credit"
        content.findViewById<TextView>(R.id.tvSheetSubtitle)?.text = "Select a credit to spend"

        val container = content.findViewById<LinearLayout>(R.id.leaveCategoryContainer)
        val closeButton = content.findViewById<TextView>(R.id.btnCloseSheet)
        val submitButton = content.findViewById<TextView>(R.id.btnSubmitSheet)

        var selectedIndex = compCredits.indexOfFirst { it.id == selectedCredit?.id }

        val rows = mutableListOf<View>()
        compCredits.forEachIndexed { index, credit ->
            val row = layoutInflater.inflate(R.layout.item_leave_sheet_option, container, false)
            row.findViewById<TextView>(R.id.tvOption).text = compCreditLabel(credit)

            val isSelected = index == selectedIndex
            row.setBackgroundResource(
                if (isSelected) R.drawable.bg_leave_sheet_option_selected
                else R.drawable.bg_leave_sheet_option
            )
            row.findViewById<ImageView>(R.id.ivOptionCheck).setImageResource(
                if (isSelected) R.drawable.ic_leave_radio_selected
                else R.drawable.ic_leave_radio_unselected
            )
            row.setOnClickListener {
                selectedIndex = index
                rows.forEachIndexed { rowIndex, v ->
                    val sel = rowIndex == selectedIndex
                    v.setBackgroundResource(
                        if (sel) R.drawable.bg_leave_sheet_option_selected
                        else R.drawable.bg_leave_sheet_option
                    )
                    v.findViewById<ImageView>(R.id.ivOptionCheck).setImageResource(
                        if (sel) R.drawable.ic_leave_radio_selected
                        else R.drawable.ic_leave_radio_unselected
                    )
                }
                submitButton.setBackgroundResource(R.drawable.bg_apply_leave_btn_enabled)
                submitButton.isEnabled = true
            }
            rows.add(row)
            container.addView(row)
        }

        val hasSelection = selectedIndex >= 0
        submitButton.setBackgroundResource(
            if (hasSelection) R.drawable.bg_apply_leave_btn_enabled else R.drawable.bg_apply_leave_btn_disabled
        )
        submitButton.isEnabled = hasSelection

        closeButton.setOnClickListener { dialog.dismiss() }
        submitButton.setOnClickListener {
            if (selectedIndex >= 0) {
                val credit = compCredits[selectedIndex]
                val changed = credit.id != selectedCredit?.id
                selectedCredit = credit
                view?.findViewById<TextView>(R.id.tvCompCreditValue)?.text = compCreditLabel(credit)
                // A new credit can invalidate a previously-picked date (must be
                // in the credit's earned month) — clear it so the user re-picks.
                if (changed) {
                    selectedFromMillis = null
                    selectedToMillis = null
                    updateDurationLabel()
                }
                dialog.dismiss()
                updateSubmitButtonState()
            }
        }
        dialog.show()
    }

    private fun compCreditLabel(credit: com.manjugroups.m_connect.network.CompOffCredit): String {
        val earned = credit.earnedDate?.let { formatIsoLabel(it) } ?: "Credit"
        val expires = credit.expiresAt?.let { formatIsoLabel(it) }
        return if (expires != null) "Earned $earned · valid till $expires" else "Earned $earned"
    }

    private fun formatIsoLabel(iso: String): String {
        return try {
            val d = apiDateFormat.parse(iso)
            if (d != null) labelDateFormat.format(d) else iso
        } catch (_: Exception) {
            iso
        }
    }

    private fun submitLeave() {
        if (selectedLeaveType == "compensatory") {
            submitCompOff()
            return
        }

        val fromMillis = selectedFromMillis
        val toMillis = selectedToMillis
        val etReason = view?.findViewById<EditText>(R.id.etReason)
        val reason = etReason?.text?.toString()?.trim().orEmpty()

        if (fromMillis == null || toMillis == null) {
            Toast.makeText(requireContext(), "Select leave duration", Toast.LENGTH_SHORT).show()
            return
        }
        if (toMillis < fromMillis) {
            Toast.makeText(requireContext(), "To date must be on or after from date", Toast.LENGTH_SHORT).show()
            return
        }

        val from = apiDateFormat.format(fromMillis)
        val to = apiDateFormat.format(toMillis)

        val tvSubmit = view?.findViewById<TextView>(R.id.tvSubmit)
        val skeletonSubmit = view?.findViewById<View>(R.id.skeletonSubmit)
        val btnSubmit = view?.findViewById<View>(R.id.btnSubmit)

        tvSubmit?.visibility = View.INVISIBLE
        skeletonSubmit?.visibility = View.VISIBLE
        skeletonSubmit?.let { SkeletonUtils.startSkeletonPulse(it) }
        btnSubmit?.isClickable = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.applyLeave(
                    session.bearerToken,
                    ApplyLeaveRequest(
                        leaveType = if (selectedLeaveType == "half_day") "casual" else selectedLeaveType,
                        fromDate = from,
                        toDate = to,
                        reason = if (selectedLeaveType == "half_day") "[$selectedSession] $reason".trim() else reason,
                        reportingToId = session.reportingToId,
                        reportingToName = session.reportingToName,
                        isHalfDay = if (selectedLeaveType == "half_day") true else null,
                        halfDaySession = if (selectedLeaveType == "half_day") selectedSession.lowercase() else null,
                        halfDayType = if (selectedLeaveType == "half_day") selectedSession.lowercase() else null
                    )
                )
                if (resp.success) {
                    LeaveSubmittedSuccessSheet.newInstance().show(parentFragmentManager, "leave_submitted_success")
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

    /** Spend the selected comp-off credit on the chosen day. */
    private fun submitCompOff() {
        val credit = selectedCredit
        val dayMillis = selectedFromMillis
        if (credit == null) {
            Toast.makeText(requireContext(), "Select a comp-off credit", Toast.LENGTH_SHORT).show()
            return
        }
        if (dayMillis == null) {
            Toast.makeText(requireContext(), "Select the day to take", Toast.LENGTH_SHORT).show()
            return
        }
        val date = apiDateFormat.format(dayMillis)

        val tvSubmit = view?.findViewById<TextView>(R.id.tvSubmit)
        val skeletonSubmit = view?.findViewById<View>(R.id.skeletonSubmit)
        val btnSubmit = view?.findViewById<View>(R.id.btnSubmit)

        tvSubmit?.visibility = View.INVISIBLE
        skeletonSubmit?.visibility = View.VISIBLE
        skeletonSubmit?.let { SkeletonUtils.startSkeletonPulse(it) }
        btnSubmit?.isClickable = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.applyCompOff(
                    session.bearerToken,
                    com.manjugroups.m_connect.network.ApplyCompOffRequest(creditId = credit.id, date = date)
                )
                if (resp.success) {
                    LeaveSubmittedSuccessSheet.newInstance().show(parentFragmentManager, "leave_submitted_success")
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

    private fun showCategorySheet() {
        val dialog = BottomSheetDialog(requireContext())
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog).findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.setBackgroundColor(Color.TRANSPARENT)
                it.elevation = 0f
            }
        }
        val content = layoutInflater.inflate(R.layout.bottom_sheet_leave_category, null)
        dialog.setContentView(content)

        val container = content.findViewById<LinearLayout>(R.id.leaveCategoryContainer)
        val closeButton = content.findViewById<TextView>(R.id.btnCloseSheet)
        val submitButton = content.findViewById<TextView>(R.id.btnSubmitSheet)

        var selectedIndex = leaveTypes.indexOf(selectedLeaveType)

        val rows = mutableListOf<View>()
        leaveTypes.forEachIndexed { index, type ->
            val row = layoutInflater.inflate(R.layout.item_leave_sheet_option, container, false)
            row.findViewById<TextView>(R.id.tvOption).text = prettyType(type)
            
            val isSelected = index == selectedIndex
            row.setBackgroundResource(
                if (isSelected) R.drawable.bg_leave_sheet_option_selected
                else R.drawable.bg_leave_sheet_option
            )
            row.findViewById<ImageView>(R.id.ivOptionCheck).setImageResource(
                if (isSelected) R.drawable.ic_leave_radio_selected
                else R.drawable.ic_leave_radio_unselected
            )

            row.setOnClickListener {
                selectedIndex = index
                rows.forEachIndexed { rowIndex, view ->
                    val isRowSelected = rowIndex == selectedIndex
                    view.setBackgroundResource(
                        if (isRowSelected) R.drawable.bg_leave_sheet_option_selected
                        else R.drawable.bg_leave_sheet_option
                    )
                    view.findViewById<ImageView>(R.id.ivOptionCheck).setImageResource(
                        if (isRowSelected) R.drawable.ic_leave_radio_selected
                        else R.drawable.ic_leave_radio_unselected
                    )
                }
                submitButton.setBackgroundResource(R.drawable.bg_apply_leave_btn_enabled)
                submitButton.isEnabled = true
            }
            rows.add(row)
            container.addView(row)
        }

        // Set initial state for submit button
        if (selectedIndex >= 0) {
            submitButton.setBackgroundResource(R.drawable.bg_apply_leave_btn_enabled)
            submitButton.isEnabled = true
        } else {
            submitButton.setBackgroundResource(R.drawable.bg_apply_leave_btn_disabled)
            submitButton.isEnabled = false
        }

        closeButton.setOnClickListener { dialog.dismiss() }
        submitButton.setOnClickListener {
            if (selectedIndex >= 0) {
                val selected = leaveTypes[selectedIndex]
                val previous = selectedLeaveType
                selectedLeaveType = selected
                view?.findViewById<TextView>(R.id.tvLeaveCategoryValue)?.text = prettyType(selected)

                // If switching to half_day and we had a multi-day range, collapse it to a single day
                if (selected == "half_day" && selectedFromMillis != null && selectedToMillis != null && selectedFromMillis != selectedToMillis) {
                    selectedToMillis = selectedFromMillis
                    updateDurationLabel()
                }

                // Comp Off dates are constrained to the chosen credit's month, so
                // any date picked under another category is no longer valid.
                if (selected == "compensatory" && previous != "compensatory") {
                    selectedFromMillis = null
                    selectedToMillis = null
                    updateDurationLabel()
                }

                dialog.dismiss()
                applyTypeUi()
                updateSubmitButtonState()
            }
        }

        dialog.show()
    }

    private fun showDurationSheet() {
        val isComp = selectedLeaveType == "compensatory"
        // Comp Off must be spent within the credit's earned month; the day it
        // was earned (the weekly-off/holiday worked) can't be taken back.
        val compEarnedMillis: Long? = if (isComp) {
            val credit = selectedCredit
            if (credit == null) {
                Toast.makeText(requireContext(), "Select a comp-off credit first", Toast.LENGTH_SHORT).show()
                return
            }
            credit.earnedDate?.let { runCatching { apiDateFormat.parse(it)?.time }.getOrNull() }
        } else null

        val dialog = BottomSheetDialog(requireContext())
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog).findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.setBackgroundColor(Color.TRANSPARENT)
                it.elevation = 0f
            }
        }
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

        // Comp Off locks the calendar to the credit's earned month.
        if (isComp) {
            prevMonth?.visibility = View.INVISIBLE
            nextMonth?.visibility = View.INVISIBLE
        }

        val displayMonth = Calendar.getInstance().apply {
            timeInMillis = when {
                isComp && compEarnedMillis != null -> compEarnedMillis
                else -> selectedFromMillis ?: System.currentTimeMillis()
            }
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
            // For comp-off, the earned day itself is not selectable.
            val isEarnedDay = isComp && compEarnedMillis != null && sameDay(dayMillis, compEarnedMillis)
            val selectable = inCurrentMonth && !isEarnedDay

            when {
                !inCurrentMonth || isEarnedDay -> {
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
                sameDay(dayMillis, System.currentTimeMillis()) -> {
                    // Today — accent ring so the user can orient without it
                    // reading as a selection.
                    cell.setBackgroundResource(R.drawable.bg_leave_day_today)
                    cell.setTextColor(android.graphics.Color.parseColor("#0B61CA"))
                }
                else -> {
                    cell.background = null
                    cell.setTextColor(ContextCompat.getColor(requireContext(), R.color.lt_foreground_primary))
                }
            }

            if (selectable) {
                cell.setOnClickListener {
                    if (selectedLeaveType == "half_day" || isComp) {
                        tempFrom = dayMillis
                        tempTo = dayMillis
                    } else {
                        if (tempFrom == null || tempTo != null) {
                            tempFrom = dayMillis
                            tempTo = null
                        } else if (dayMillis < tempFrom!!) {
                            tempTo = tempFrom
                            tempFrom = dayMillis
                        } else {
                            tempTo = dayMillis
                        }
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

        if (!isComp) {
            monthLabel.setOnClickListener {
                MonthYearPicker.show(requireContext(), displayMonth) { year, month ->
                    displayMonth.set(Calendar.YEAR, year)
                    displayMonth.set(Calendar.MONTH, month)
                    displayMonth.set(Calendar.DAY_OF_MONTH, 1)
                    displayMonth.clearTime()
                    renderCalendar()
                }
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
            updateSubmitButtonState()
        }

        closeButton.setOnClickListener { dialog.dismiss() }

        renderCalendar()
        dialog.show()
    }

    private fun updateDurationLabel() {
        val fromMillis = selectedFromMillis
        val toMillis = selectedToMillis
        val tvLeaveDurationValue = view?.findViewById<TextView>(R.id.tvLeaveDurationValue) ?: return
        tvLeaveDurationValue.text = when {
            fromMillis == null || toMillis == null -> "Select Duration"
            sameDay(fromMillis, toMillis) -> labelDateFormat.format(fromMillis)
            else -> "${labelDateFormat.format(fromMillis)} - ${labelDateFormat.format(toMillis)}"
        }
    }

    private fun prettyType(value: String): String {
        // Explicit labels mirroring the web leave-type dropdown so the two
        // stay in lockstep. Unknown/custom policy types fall through to the
        // generic title-case formatter below.
        when (value.lowercase(Locale.getDefault())) {
            "casual" -> return "Casual Leave"
            "sick" -> return "Sick Leave"
            "earned" -> return "Earned Leave"
            "unpaid" -> return "Unpaid Leave"
            "compensatory" -> return "Compensatory Off"
        }
        val base = value
            .replace('_', ' ')
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
                }
            }
        return if (base.lowercase().contains("leave")) base else "$base Leave"
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

    companion object {
        const val RESULT_KEY_APPLIED = "apply_leave_applied_result"
        
        fun newInstance(): ApplyLeaveBottomSheet = ApplyLeaveBottomSheet()
    }
}
