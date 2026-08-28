package com.manjugroups.m_connect.ui.marketing

import com.manjugroups.m_connect.network.CpVisitDetail
/**
 * "Don't create more than one CP visit for a client on the same day."
 *
 * The matching rules live here rather than inside the create sheet so they can
 * be tested directly. The shared backend repeats this check authoritatively so
 * another staff member or a simultaneous request cannot bypass it.
 */
object OpenCpVisitGuard {

    /** Last 10 digits, so +91-prefixed and plain numbers compare equal. */
    fun normalizePhone(raw: String?): String =
        raw.orEmpty().filter(Char::isDigit).takeLast(10)

    /**
     * The first non-cancelled visit belonging to [phone] on [scheduledDate].
     * Completed visits still consume that day's single visit allowance.
     */
    fun findSameDayVisit(
        visits: List<CpVisitDetail>,
        phone: String,
        scheduledDate: String,
    ): CpVisitDetail? {
        val target = normalizePhone(phone)
        if (target.length < 10) return null
        return visits.firstOrNull { visit ->
            val visitPhone = normalizePhone(
                visit.lead?.mobileNumber ?: visit.client?.mobileNumber,
            )
            visitPhone == target &&
                visit.scheduledDate == scheduledDate &&
                !visit.status.equals("cancelled", ignoreCase = true)
        }
    }

    /** User-facing reason, or null when creation may proceed. */
    fun blockReason(
        visits: List<CpVisitDetail>,
        phone: String,
        scheduledDate: String,
    ): String? {
        findSameDayVisit(visits, phone, scheduledDate) ?: return null
        return "This client already has a CP visit on $scheduledDate. " +
            "Only one CP visit per client is allowed per day. Open the existing visit or choose another date."
    }
}
