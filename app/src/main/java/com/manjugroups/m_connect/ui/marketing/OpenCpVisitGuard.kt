package com.manjugroups.m_connect.ui.marketing

import com.manjugroups.m_connect.network.CpVisitDetail
import java.util.Locale

/**
 * "Don't create a second CP visit for a client who already has one open."
 *
 * The matching rules live here rather than inside the create sheet so they can
 * be tested directly — phone normalisation and the set of statuses that count
 * as "still open" are exactly the parts that go quietly wrong.
 */
object OpenCpVisitGuard {

    /**
     * Statuses that mean the visit has NOT been dealt with yet. Completed and
     * cancelled visits must not block a new one — the client can legitimately
     * be visited again.
     *
     * The backend has spelled in-progress several ways over time, so all the
     * shapes the CP list already handles elsewhere are accepted here too.
     */
    private val OPEN_STATUSES = setOf(
        "scheduled",
        "in-progress",
        "in_progress",
        "ongoing",
        "started",
        "active",
        "arrived",
    )

    /** Last 10 digits, so +91-prefixed and plain numbers compare equal. */
    fun normalizePhone(raw: String?): String =
        raw.orEmpty().filter(Char::isDigit).takeLast(10)

    fun isOpen(status: String?): Boolean =
        status?.trim()?.lowercase(Locale.US) in OPEN_STATUSES

    /**
     * The first still-open visit belonging to [phone], or null when the client
     * has none and a new CP may be created.
     */
    fun findOpenVisit(visits: List<CpVisitDetail>, phone: String): CpVisitDetail? {
        val target = normalizePhone(phone)
        if (target.length < 10) return null
        return visits.firstOrNull { visit ->
            val visitPhone = normalizePhone(
                visit.lead?.mobileNumber ?: visit.client?.mobileNumber,
            )
            visitPhone == target && isOpen(visit.status)
        }
    }

    /** User-facing reason, or null when creation may proceed. */
    fun blockReason(visits: List<CpVisitDetail>, phone: String): String? {
        val open = findOpenVisit(visits, phone) ?: return null
        val whenPart = open.scheduledDate?.takeIf { it.isNotBlank() }?.let { " on $it" }.orEmpty()
        return "This client already has a CP visit$whenPart. " +
            "Complete or cancel it before creating another."
    }
}
