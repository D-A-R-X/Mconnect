package com.manjugroups.m_connect.ui.hr

/**
 * Whether "Apply Leave" / "Apply Permission" can be tapped (MMS-290).
 *
 * When nothing is left the button turns grey, reads "Unavailable" and shows a
 * one-line reason under it. The server still enforces every limit; this only
 * stops the user filling a form that is bound to be refused.
 *
 * Never blocks while the numbers are loading, failed or unknown — an unknown
 * balance is treated as available, so a slow or old backend can't lock staff
 * out of applying.
 */
object HrApplyAvailability {

    data class State(val available: Boolean, val reason: String? = null)

    val AVAILABLE = State(available = true)

    const val REASON_PERMISSION_HOURS = "No permission hours left this month"
    const val REASON_PERMISSION_REQUESTS = "Monthly permission requests used up"
    const val REASON_LEAVE = "No leave balance left"

    /**
     * Permission: grey when this month's hours OR request count is used up.
     *
     * @param loaded the monthly-usage call came back successfully.
     * @param remainingHours hours left this month; null = unknown (available).
     * @param remainingRequests requests left this month; null = unknown.
     */
    fun permission(loaded: Boolean, remainingHours: Double?, remainingRequests: Int?): State {
        if (!loaded) return AVAILABLE
        if (remainingRequests != null && remainingRequests <= 0) {
            return State(false, REASON_PERMISSION_REQUESTS)
        }
        if (remainingHours != null && remainingHours <= 0.0) {
            return State(false, REASON_PERMISSION_HOURS)
        }
        return AVAILABLE
    }

    /**
     * Leave: grey only when the unpaid-leave limit configured on web is set and
     * nothing is left of it. A missing/null limit (unlimited, or a server that
     * doesn't send it yet) or an unknown remainder never blocks.
     */
    fun leave(loaded: Boolean, unpaidLimit: Double?, unpaidRemaining: Double?): State {
        if (!loaded || unpaidLimit == null || unpaidRemaining == null) return AVAILABLE
        return if (unpaidRemaining <= 0.0) State(false, REASON_LEAVE) else AVAILABLE
    }

    /** Compensatory Off can be picked only when at least one credit exists. */
    fun compOffAvailable(creditsLoaded: Boolean, creditCount: Int): Boolean =
        !creditsLoaded || creditCount > 0
}
