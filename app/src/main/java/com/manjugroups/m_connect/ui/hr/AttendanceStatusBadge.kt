package com.manjugroups.m_connect.ui.hr

import android.graphics.Color
import android.view.View
import android.widget.TextView
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.network.AttendanceRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Binds the small status pill in the corner of every attendance day
 * card (`item_attendance_history_card.xml`). Visibility rules — kept
 * consistent across HR Dashboard's recent attendance strip and the
 * dedicated My Attendance log:
 *
 *  - Today (or any future date) → no pill. The day is still running;
 *    a verdict would lie.
 *
 *  - HR verdict on `approvedAttendance` ALWAYS wins, regardless of
 *    the row's workflow `status`. An HR-marked Present row was
 *    rendering as Absent before because we required
 *    status="approved" too; some staff records approve via
 *    different paths (auto, biometric, manager-confirm) where the
 *    workflow status doesn't track.
 *      "present"   → green Present
 *      "half-day"  → amber Half-day
 *      "absent"    → red Absent
 *      "weekoff"   → gray Week Off
 *      "holiday"   → gray Holiday
 *
 *  - No HR verdict yet — look at the punch state:
 *      a. No clock-in AND no clock-out         → red Absent
 *      b. Clock-in but no real clock-out
 *         (totalMinutes == 0, auto-closed)     → yellow Pending
 *      c. Real clock-in + clock-out, awaiting HR review → no pill
 */
object AttendanceStatusBadge {

    private val ymdFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun bind(pill: TextView, record: AttendanceRecord) {
        val date = record.date.orEmpty()
        if (date.isBlank()) {
            pill.visibility = View.GONE
            return
        }
        val today = ymdFmt.format(Date())
        if (date >= today) {
            // Today / future — verdict is provisional, never badge.
            pill.visibility = View.GONE
            return
        }

        // ── HR verdict wins regardless of workflow status ──
        // approvedAttendance is the HR-finalized field; trusting it
        // alone fixes the case where biometric/auto-approved rows
        // were stuck reading as Absent.
        when (record.approvedAttendance?.lowercase(Locale.US).orEmpty()) {
            "present" ->
                return set(pill, "Present", FG_PRESENT, R.drawable.bg_pill_green_light)
            "half-day", "halfday" ->
                return set(pill, "Half-day", FG_HALFDAY, R.drawable.bg_pill_green_light)
            "absent" ->
                return set(pill, "Absent", FG_ABSENT, R.drawable.bg_pill_red_light)
            "weekoff", "week-off" ->
                return set(pill, "Week Off", FG_NEUTRAL, R.drawable.bg_pill_white)
            "holiday" ->
                return set(pill, "Holiday", FG_NEUTRAL, R.drawable.bg_pill_white)
        }

        // ── No HR verdict — derive from punch activity ──
        // hasClockIn covers both shapes the backend uses: legacy
        // top-level punchInTime AND the canonical sessions[].
        val hasClockIn =
            !record.punchInTime.isNullOrBlank()
                || record.sessions.orEmpty().any { !it.punchInTime.isNullOrBlank() }

        if (!hasClockIn) {
            // No clock activity at all → Absent.
            return set(pill, "Absent", FG_ABSENT, R.drawable.bg_pill_red_light)
        }

        val mins = record.totalMinutes ?: 0
        if (mins <= 0) {
            // Clock-in but no real clock-out (auto-closed at day end
            // OR same-instant punch-in/out). HR hasn't reviewed yet,
            // so we surface Pending — distinct from Absent so the
            // staff knows the day needs HR action, not a fresh punch.
            return set(pill, "Pending", FG_PENDING, R.drawable.bg_pill_yellow_light)
        }

        // Real clock-in + clock-out + no HR verdict → no pill (the
        // row will pick one up once HR finalises).
        pill.visibility = View.GONE
    }

    private fun set(view: TextView, text: String, fg: Int, bgRes: Int) {
        view.text = text
        view.setTextColor(fg)
        view.setBackgroundResource(bgRes)
        view.visibility = View.VISIBLE
    }

    private val FG_PRESENT = Color.parseColor("#067647")
    private val FG_HALFDAY = Color.parseColor("#B54708")
    private val FG_ABSENT = Color.parseColor("#B42318")
    private val FG_PENDING = Color.parseColor("#B54708")
    private val FG_NEUTRAL = Color.parseColor("#475467")
}
