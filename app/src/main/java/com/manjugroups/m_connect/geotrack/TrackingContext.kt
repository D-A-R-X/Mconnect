package com.manjugroups.m_connect.geotrack

import com.manjugroups.m_connect.auth.FieldActivity
import com.manjugroups.m_connect.auth.SessionManager

/**
 * What a tracking point or heartbeat belongs to.
 *
 * The app runs ONE tracking session per attendance day — deliberately, because
 * tracking is bounded to clock-in → clock-out and a second session would tear
 * the point stream in half. So a CP trip or an on-duty trip is not a session of
 * its own; it is a stretch of the day's session, and the only way the GeoTrack
 * service can attribute distance to it is if each point says so.
 *
 * Without this, every point arrives tagged "attendance" and a CP trip's real
 * distance can only be guessed from a time window. That is what forced the
 * backend into straight-line fallbacks for trip distance and travel allowance.
 *
 * [contextType] values match the backend segment contract
 * (see `reports/GEOTRACK_CONVEX_CUTOVER.md` §5.2).
 */
data class TrackingContext(
    val contextType: String,
    val contextId: String?,
) {
    companion object {
        const val ATTENDANCE = "attendance"
        const val CP_TRIP = "cp_trip"
        const val SITE_VISIT = "site_visit"
        const val ON_DUTY = "on_duty"
        const val FLEET_TRIP = "fleet_trip"

        /**
         * The plain shift: no trip is running, so the point belongs to the
         * attendance day. `contextId` stays null on purpose — the session was
         * opened with the attendance row id as its own contextId, so repeating
         * it on every point would be redundant, and guessing it here would risk
         * disagreeing with the session after a day rollover.
         */
        val SHIFT = TrackingContext(ATTENDANCE, null)

        /**
         * Maps the field-activity kind the tracking notification already uses
         * onto the backend's segment types. Keeping ONE source of truth for
         * "what is this staff doing right now" is the point — a second,
         * parallel notion of the active trip is exactly how the notification
         * and the trip row drifted apart before.
         */
        fun fromFieldActivity(activity: FieldActivity?): TrackingContext {
            if (activity == null) return SHIFT
            val type = when (activity.kind.trim().lowercase()) {
                "onduty" -> ON_DUTY
                "cp" -> CP_TRIP
                "sv" -> SITE_VISIT
                "fleet" -> FLEET_TRIP
                // An unknown kind means a newer flow set an activity this build
                // does not know. Fall back to the shift rather than inventing a
                // segment type the backend would reject.
                else -> return SHIFT
            }
            return TrackingContext(type, activity.refId?.trim()?.takeIf { it.isNotEmpty() })
        }
    }
}

/**
 * The context to stamp on telemetry captured right now.
 *
 * Read at CAPTURE time, never at flush time: a buffered backlog uploaded hours
 * later must stay attributed to the trip it was recorded during, the same
 * reason `LocationPointEntity.sessionId` is stamped on capture.
 */
fun SessionManager.trackingContext(): TrackingContext =
    TrackingContext.fromFieldActivity(fieldActivity())
