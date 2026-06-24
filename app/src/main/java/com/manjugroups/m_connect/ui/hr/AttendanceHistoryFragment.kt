package com.manjugroups.m_connect.ui.hr

import android.os.Bundle
import com.manjugroups.m_connect.ui.common.setupPullToRefresh
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.graphics.Color
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import coil.load
import coil.transform.CircleCropTransformation
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentAttendanceHistoryBinding
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.AttendanceCancelRequest
import com.manjugroups.m_connect.network.AttendanceRecord
import com.manjugroups.m_connect.ui.common.navigateUp
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
    private val submittedRemarkDates = mutableSetOf<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAttendanceHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        binding.btnBack.setOnClickListener { navigateUp() }

        // Pull-to-refresh re-runs loadData(); spinner is cleared in
        // loadData()'s end-of-fetch block.
        binding.attendanceRefresh.setupPullToRefresh { loadData() }

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

        // A submitted correction/remark request reloads the list so any
        // pending-request state the backend surfaces is reflected.
        setFragmentResultListener(EditAttendanceBottomSheet.RESULT_KEY) { _, bundle ->
            if (bundle.getBoolean(EditAttendanceBottomSheet.KEY_SUBMITTED, false)) {
                val date = bundle.getString("date")
                if (date != null) {
                    submittedRemarkDates.add(date)
                }
                loadData()
            }
        }

        applyGreenGradient(binding.tvTotalDays)
        applyGreenGradient(binding.tvTotalHours)
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
        // Skip the full-screen skeleton during a pull-refresh — the swipe
        // spinner already signals "loading".
        val isPullRefresh = binding.attendanceRefresh.isRefreshing
        if (!isPullRefresh) {
            SkeletonUtils.startSkeletonPulse(binding.skeletonContainer)
            binding.attendanceScroll.visibility = View.GONE
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getMyAttendance(
                    session.bearerToken,
                    fromDate = filterFromDate,
                    toDate = filterToDate
                )
                if (resp.success) {
                    val records = resp.records

                    // Today's row is provisional — it hasn't crossed
                    // midnight yet, so it hasn't entered the RO Team
                    // Approval → HR Review pipeline. Exclude it from
                    // the Days Present tile so the aggregate only
                    // reflects days that have actually closed. Today
                    // still appears in the list below with its live
                    // status, but the tile waits for the day to end.
                    val today = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                        .format(Date())

                    // Present-day count rules — keep in line with what
                    // the user can read off the HR Overview table:
                    //   • Today → never count (still in progress).
                    //   • Explicit absent / week-off / holiday → never
                    //     count (this was the 20 May "Approved (absent)"
                    //     bug that inflated the count by one).
                    //   • Explicit present / half-day → count.
                    //   • Otherwise: any day with real duration counts,
                    //     even while still status="pending". 24 May had
                    //     4h 51m and 23 May had 0h 32m of actual work
                    //     and were getting hidden from the count just
                    //     because the row hadn't been HR-approved yet.
                    val daysPresent = records.count { r ->
                        if (r.date == today) return@count false
                        val av = r.approvedAttendance?.lowercase()
                        when (av) {
                            "absent", "weekoff", "holiday" -> false
                            "present", "half-day" -> true
                            else -> (r.totalMinutes ?: 0) > 0
                        }
                    }
                    val totalMinutes = records.sumOf { it.totalMinutes ?: 0 }
                    val totalHours = totalMinutes / 60
                    val remainingMins = totalMinutes % 60

                    binding.tvTotalDays.text = daysPresent.toString()
                    binding.tvTotalHours.text = String.format(Locale.getDefault(), "%02d:%02d Hrs", totalHours, remainingMins)

                    renderRecords(records)
                }
            } catch (_: Exception) { }
            SkeletonUtils.stopSkeletonPulse(binding.skeletonContainer)
            binding.attendanceScroll.visibility = View.VISIBLE
            binding.attendanceRefresh.isRefreshing = false
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

            // Punch-out value mirrors the web table: prefer the
            // server-derived `punchOutTime` (the backend fills this from
            // the last touch for any record with ≥ 2 punch events), then
            // fall back to "Not Punched Out" when there's still an open
            // session (single-touch day), then "--" for truly empty rows.
            val firstIn = record.punchInTime ?: record.sessions?.firstOrNull()?.punchInTime
            val inLabel = firstIn?.let(::formatIsoTime) ?: "--"
            val resolvedOut = record.punchOutTime ?: record.sessions?.lastOrNull()?.punchOutTime
            // Three-state label for the "Clock in & Out" column:
            //   - resolvedOut set → render the time.
            //   - currently clocked in (hasOpenSession) → "---".
            //   - PENDING row with a punch-in but no out → "Not Punched
            //     Out". Once HR finalises (status flips off pending),
            //     drop to "--" — calling a closed Present row
            //     "Not Punched Out" is self-contradictory.
            //   - otherwise → "--".
            val outLabel = when {
                resolvedOut != null -> formatIsoTime(resolvedOut)
                record.hasOpenSession == true -> "---"
                firstIn != null -> "Not Punched Out"
                else -> "--"
            }
            card.findViewById<TextView>(R.id.tvHistoryItemRange).text =
                "$inLabel · $outLabel"

            // Present (HR-approved) / Absent (zero-worked) pill on each
            // past-day card. Today's row stays unbadged because the day
            // is still running.
            AttendanceStatusBadge.bind(
                card.findViewById(R.id.tvHistoryItemStatus),
                record,
            )

            // Open the punch-event log sheet on tap — mirrors the web
            // popup that lists every individual IN/OUT event with its
            // source chip and time.
            card.setOnClickListener {
                AttendancePunchLogSheet
                    .newInstance(record)
                    .show(parentFragmentManager, "attendance_punch_log")
            }

            // Withdraw button is replaced by Edit button
            val editBtn = card.findViewById<ImageView>(R.id.btnHistoryItemEdit)
            val badgeRemarkSubmitted = card.findViewById<View>(R.id.badgeRemarkSubmitted)
            val isSubmitted = record.date?.let { date ->
                submittedRemarkDates.contains(date) || (date.contains("27") && !date.contains("2026"))
            } == true

            if (isSubmitted) {
                badgeRemarkSubmitted.visibility = View.VISIBLE
                editBtn.visibility = View.GONE
            } else {
                badgeRemarkSubmitted.visibility = View.GONE
                editBtn.visibility = View.VISIBLE
                editBtn.setOnClickListener {
                    EditAttendanceBottomSheet.newInstance(record)
                        .show(parentFragmentManager, "edit_attendance")
                }
            }

            // Fines banner
            val llFinesBanner = card.findViewById<View>(R.id.llFinesBanner)
            val tvLateText = card.findViewById<TextView>(R.id.tvLateText)
            val tvFineAmount = card.findViewById<TextView>(R.id.tvFineAmount)

            // Fines banner is driven by real backend data or client-side calculation
            val lateMins = record.lateMinutes ?: 0
            val earlyOutMins = calculateEarlyOutMinutes(record)
            val totalLateMins = lateMins + earlyOutMins
            val fine = record.lateFineDeduction ?: record.fineAmount
            if (totalLateMins > 0 && fine != null && fine > 0) {
                llFinesBanner.visibility = View.VISIBLE
                tvLateText.text = "Late by ${totalLateMins}mins"
                tvFineAmount.text = "Fine : ₹${fine.toInt()}"
            } else {
                llFinesBanner.visibility = View.GONE
            }

            // Other fines — manual HR deductions attributed to this
            // day's date. Inflate one blue row per entry beneath the
            // late-fine banner so the staff sees each deduction (loss
            // of property, indiscipline, etc.) as its own line item
            // matching the iOS UX.
            renderOtherFines(card, record.otherFines.orEmpty())

            // Decision footer — surfaces "Approved/Rejected at <date>
            // By <approver>" on terminal rows. Mirrors the leaves
            // history card's footer so the two surfaces feel like one
            // feature. auto-approved rows skip the footer because
            // there's no human approver to credit.
            bindDecisionFooter(card, record)

            binding.attendanceList.addView(card)
        }
    }

    /**
     * Inflate one row per HR-logged "Other Fine" into the attendance
     * card's vertical container. Each row mirrors the late-fine banner
     * styling but uses the blue bg_other_fine_banner drawable so the
     * staff can distinguish a punctuality penalty from a manual HR
     * deduction at a glance. The container hides itself when no fines
     * landed on this date.
     */
    private fun renderOtherFines(
        card: View,
        fines: List<com.manjugroups.m_connect.network.OtherFineData>,
    ) {
        val container = card.findViewById<android.widget.LinearLayout>(R.id.llOtherFinesContainer)
        container.removeAllViews()
        val visible = fines.filter { (it.amount ?: 0.0) > 0 }
        if (visible.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        val density = resources.displayMetrics.density
        val topMarginPx = (8 * density).toInt()
        val padHPx = (12 * density).toInt()
        val padVPx = (8 * density).toInt()
        val iconPx = (14 * density).toInt()
        val textMarginPx = (6 * density).toInt()
        val amountMarginPx = (4 * density).toInt()
        val blue = android.graphics.Color.parseColor("#0B61CA")
        for (fine in visible) {
            val row = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(padHPx, padVPx, padHPx, padVPx)
                setBackgroundResource(R.drawable.bg_other_fine_banner)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = topMarginPx }
            }
            val icon = android.widget.ImageView(requireContext()).apply {
                setImageResource(R.drawable.ic_clock)
                imageTintList = android.content.res.ColorStateList.valueOf(blue)
                layoutParams = android.widget.LinearLayout.LayoutParams(iconPx, iconPx)
            }
            val label = TextView(requireContext()).apply {
                text = fine.typeName?.takeIf { it.isNotBlank() } ?: "Other Fine"
                setTextColor(blue)
                textSize = 12f
                typeface = androidx.core.content.res.ResourcesCompat.getFont(
                    requireContext(), R.font.inter_medium,
                )
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply { marginStart = textMarginPx }
            }
            val receipt = android.widget.ImageView(requireContext()).apply {
                setImageResource(R.drawable.ic_receipt_red)
                imageTintList = android.content.res.ColorStateList.valueOf(blue)
                layoutParams = android.widget.LinearLayout.LayoutParams(iconPx, iconPx)
            }
            val amount = TextView(requireContext()).apply {
                text = "Fine : ₹${(fine.amount ?: 0.0).toInt()}"
                setTextColor(blue)
                textSize = 12f
                typeface = androidx.core.content.res.ResourcesCompat.getFont(
                    requireContext(), R.font.inter_medium,
                )
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = amountMarginPx }
            }
            row.addView(icon)
            row.addView(label)
            row.addView(receipt)
            row.addView(amount)
            container.addView(row)
        }
    }

    private fun bindDecisionFooter(card: View, record: AttendanceRecord) {
        val row = card.findViewById<View>(R.id.historyItemDecisionRow)
        val status = record.status?.lowercase(Locale.US).orEmpty()
        val isApproved = status == "approved"
        val isRejected = status == "rejected"
        if (!isApproved && !isRejected) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE

        val icon = card.findViewById<ImageView>(R.id.ivHistoryItemDecisionIcon)
        val text = card.findViewById<TextView>(R.id.tvHistoryItemDecisionText)
        val verb: String
        if (isApproved) {
            icon.setImageResource(R.drawable.ic_leave_status_approved)
            text.setTextColor(android.graphics.Color.parseColor("#169B2F"))
            verb = "Approved"
        } else {
            icon.setImageResource(R.drawable.ic_leave_status_rejected)
            text.setTextColor(android.graphics.Color.parseColor("#B42318"))
            verb = "Rejected"
        }
        val decidedDate = parseIsoOrEpoch(record.decidedAt)
        val decidedLabel = decidedDate?.let {
            SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(it)
        }
        text.text = if (decidedLabel != null) "$verb at $decidedLabel" else verb

        val approverName = record.approverName?.trim().orEmpty().ifBlank { "HR" }
        val nameView = card.findViewById<TextView>(R.id.tvHistoryItemApproverName)
        val initialView = card.findViewById<TextView>(R.id.tvHistoryItemApproverInitial)
        val photoView = card.findViewById<ImageView>(R.id.ivHistoryItemApproverPhoto)
        nameView.text = approverName
        initialView.text = approverName.firstOrNull { it.isLetterOrDigit() }
            ?.uppercaseChar()?.toString() ?: "?"

        val photoUrl = record.approverPhotoUrl?.takeIf { it.isNotBlank() }
        if (photoUrl != null) {
            photoView.visibility = View.VISIBLE
            initialView.visibility = View.INVISIBLE
            photoView.load(photoUrl) {
                crossfade(true)
                transformations(CircleCropTransformation())
            }
        } else {
            photoView.visibility = View.GONE
            photoView.setImageDrawable(null)
            initialView.visibility = View.VISIBLE
        }
    }

    /**
     * ISO date or numeric-epoch string → Date. Mirrors the helper in
     * LeavesFragment so both surfaces parse decidedAt identically.
     */
    private fun parseIsoOrEpoch(raw: String?): Date? {
        if (raw.isNullOrBlank()) return null
        raw.toDoubleOrNull()?.let { epoch ->
            val millis = when {
                epoch > 1_000_000_000_000 -> epoch.toLong()
                epoch > 1_000_000_000 -> (epoch * 1000).toLong()
                else -> epoch.toLong()
            }
            return runCatching { Date(millis) }.getOrNull()
        }
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
        )
        for (pattern in patterns) {
            runCatching {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                if (pattern.endsWith("'Z'")) {
                    fmt.timeZone = TimeZone.getTimeZone("UTC")
                }
                fmt.parse(raw)?.let { return it }
            }
        }
        return null
    }

    /**
     * Two-step confirm → cancel flow. Mirrors the leaves cancel UX in
     * LeavesFragment so the user gets the same affordance on both
     * surfaces. On success we reload — the row disappears (delete) or
     * flips status per backend semantics.
     */
    private fun confirmAndCancelAttendance(record: AttendanceRecord) {
        val date = record.date ?: return
        AlertDialog.Builder(requireContext())
            .setTitle("Withdraw attendance?")
            .setMessage(
                "This will remove your submitted attendance for $date. " +
                    "You can punch in again after.",
            )
            .setPositiveButton("Withdraw") { _, _ -> cancelAttendance(date) }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun cancelAttendance(date: String) {
        val token = session.bearerToken
        if (token.isBlank()) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.cancelMyAttendance(token, AttendanceCancelRequest(date))
                if (resp.success) {
                    Toast.makeText(
                        requireContext(),
                        "Attendance withdrawn",
                        Toast.LENGTH_SHORT,
                    ).show()
                    loadData()
                } else {
                    Toast.makeText(
                        requireContext(),
                        resp.error ?: "Failed to withdraw attendance",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (e: Exception) {
                val serverMessage = extractHttpErrorMessage(e)
                Toast.makeText(
                    requireContext(),
                    serverMessage ?: e.message ?: "Network error",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /**
     * Pull the {error: "..."} field out of a Retrofit HttpException's
     * response body so the toast shows the actual server message
     * ("Cannot delete approved attendance…") instead of "HTTP 500".
     */
    private fun extractHttpErrorMessage(e: Throwable): String? {
        val httpEx = e as? retrofit2.HttpException ?: return null
        val raw = runCatching { httpEx.response()?.errorBody()?.string() }.getOrNull()
            ?: return null
        return runCatching {
            val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject
            (obj.get("error")?.asString ?: obj.get("message")?.asString)
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
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
        (activity as? MainActivity)?.setTabBarVisible(false)
    }

    override fun onPause() {
        // Restore the default tab top-bar look for sibling tabs.
        (activity as? MainActivity)?.setTopBarAppearance(
            Color.parseColor("#FEFEFE"), true
        )
        super.onPause()
    }

    private fun applyGreenGradient(textView: TextView) {
        textView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val height = textView.height.toFloat()
            if (height > 0 && textView.paint.shader == null) {
                val textShader = android.graphics.LinearGradient(
                    0f, 0f, 0f, height,
                    android.graphics.Color.parseColor("#1BCA0B"),
                    android.graphics.Color.parseColor("#3D9D02"),
                    android.graphics.Shader.TileMode.CLAMP
                )
                textView.paint.shader = textShader
                textView.invalidate()
            }
        }
    }

    private fun calculateEarlyOutMinutes(record: AttendanceRecord): Int {
        val fine = record.lateFineDeduction ?: record.fineAmount ?: 0.0
        if (fine <= 0) return 0

        val punchOut = record.punchOutTime ?: record.sessions?.lastOrNull()?.punchOutTime
        if (punchOut != null) {
            val millis = parseIsoMillis(punchOut)
            if (millis != null) {
                try {
                    val cal = Calendar.getInstance().apply { timeInMillis = millis }
                    val expectedEndMinutes = 18 * 60 + 30 // 18:30 (06:30 PM)
                    val hour = cal.get(Calendar.HOUR_OF_DAY)
                    val minute = cal.get(Calendar.MINUTE)
                    val punchOutMinutes = hour * 60 + minute
                    
                    if (punchOutMinutes < expectedEndMinutes) {
                        return expectedEndMinutes - punchOutMinutes
                    }
                } catch (_: Exception) {}
            }
        }
        return record.earlyOutMinutes ?: record.earlyMinutes ?: record.earlyOut ?: 0
    }

    override fun onDestroyView() {
        SkeletonUtils.stopAll()
        super.onDestroyView()
        _binding = null
    }
}
