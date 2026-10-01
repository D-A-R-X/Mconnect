package com.manjugroups.m_connect.ui.hr

import com.manjugroups.m_connect.network.AttendanceApprovalRecord
import com.manjugroups.m_connect.network.AttendanceRecord
import java.util.Locale

/**
 * Web parity: while a day is still with an approver its verdict is only
 * proposed, so the web shows "Awaiting for HR/RO" rather than Present or
 * Absent. The apps showed the proposed verdict as final - a day HR had not
 * decided yet read "Absent".
 */
internal fun awaitingApprovalLabel(approvalStage: String?): String? =
    when (approvalStage?.trim()?.lowercase(Locale.US)) {
        "pending-hr", "hr_review" -> "Awaiting HR"
        "pending-manager", "manager_review" -> "Awaiting RO"
        else -> null
    }

internal fun isOnHold(holdStatus: String?): Boolean =
    holdStatus?.trim()?.equals("on-hold", ignoreCase = true) == true

/** Shared web-parity rules for system-enforced absent attendance rows. */
internal fun AttendanceRecord.hasAbsentPenalty(): Boolean =
    approvedAttendance.isAbsentValue() &&
        (approvedByName.isPenaltyApprover() || !penaltyKind.isNullOrBlank())

internal fun AttendanceApprovalRecord.hasAbsentPenalty(): Boolean =
    approvedAttendance.isAbsentValue() &&
        (approvedByName.isPenaltyApprover() || !penaltyKind.isNullOrBlank())

internal fun AttendanceRecord.resolvedPenaltyReason(): String? =
    if (hasAbsentPenalty()) resolvePenaltyReason(penaltyKind, penaltyReason, approvedByName) else null

internal fun AttendanceApprovalRecord.resolvedPenaltyReason(): String? =
    if (hasAbsentPenalty()) resolvePenaltyReason(penaltyKind, penaltyReason, approvedByName) else null

private fun String?.isAbsentValue(): Boolean =
    this?.trim()?.lowercase(Locale.US) == "absent"

private fun String?.isPenaltyApprover(): Boolean = when (this?.trim()) {
    "System (RO deadline)", "System (HR deadline)" -> true
    else -> false
}

private fun resolvePenaltyReason(kind: String?, reason: String?, approver: String?): String {
    reason?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return when (kind?.trim()?.lowercase(Locale.US)) {
        "task_block" -> "Overdue task blocked attendance."
        "hr_deadline" -> "HR Attendance Review deadline missed."
        "ro_deadline" -> "Team approval deadline missed."
        else -> when (approver?.trim()) {
            "System (HR deadline)" -> "HR Attendance Review deadline missed."
            "System (RO deadline)" -> "Team approval deadline missed."
            else -> "Marked Absent because of an attendance penalty."
        }
    }
}
