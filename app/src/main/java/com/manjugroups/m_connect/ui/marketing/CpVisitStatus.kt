package com.manjugroups.m_connect.ui.marketing

import com.manjugroups.m_connect.network.JointCpParticipant
import com.manjugroups.m_connect.network.JointCpSummary
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * CP statuses that mean "this visit is finished with". Once the CP row says one
 * of these, no trip row can contradict it.
 */
private val TERMINAL_CP_STATUSES = setOf(
    "completed",
    "complete",
    "done",
    "closed",
    "cancelled",
    "canceled",
    "postponed",
    // Not finished, but the CP is deliberately held and must not read as a
    // live trip — the app closes the field visit before the GM decides, so the
    // field visit says "completed" while the CP is actually waiting.
    "pending_gm_approval",
)

/**
 * The status a CP card should display, from the CP row's own status and its
 * spawned field visit's.
 *
 * A CP visit and its field visit are separate rows with separate lifecycles.
 * While the trip is live the FIELD VISIT is authoritative, because only it
 * tracks "arrived" — the CP row has no such state, and preferring the CP row
 * would drop the user back on "Start Trip" after they had already verified
 * arrival.
 *
 * But a TERMINAL CP status wins. The app closes the field visit in a second
 * call after the outcome is recorded; when that call fails — a flaky network, a
 * backend blip — the CP is completed server-side while its field visit is left
 * at "in-progress" forever. Preferring the field visit there made a finished CP
 * render as Enroute with a "Start Trip" action, permanently, on every reload:
 * the CP would never close and tapping it just reopened a trip that was already
 * done.
 *
 * Falls back to "scheduled" when neither row says anything.
 */
fun resolveCpEffectiveStatus(
    cpStatus: String?,
    fieldVisitStatus: String?,
): String {
    val cp = cpStatus?.trim().orEmpty()
    if (cp.isNotEmpty() && cp.lowercase(Locale.US) in TERMINAL_CP_STATUSES) return cp

    val trip = fieldVisitStatus?.trim().orEmpty()
    if (trip.isNotEmpty()) return trip

    return cp.ifEmpty { "scheduled" }
}

/** Prefer the backend's normalized status while remaining compatible with older responses. */
fun resolveServerCpEffectiveStatus(
    serverEffectiveStatus: String?,
    cpStatus: String?,
    fieldVisitStatus: String?,
): String = serverEffectiveStatus?.trim()?.takeIf { it.isNotEmpty() }
    ?: resolveCpEffectiveStatus(cpStatus, fieldVisitStatus)

fun JointCpSummary?.participantFor(staffId: String?): JointCpParticipant? {
    val actorId = staffId?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return this?.participants.orEmpty().firstOrNull {
        it.staffId?.trim()?.equals(actorId, ignoreCase = true) == true
    }
}

/** Joint participants mutate and track their own field-visit leg. */
fun resolveCpFieldVisitId(
    cpVisitId: String,
    parentFieldVisitId: String?,
    joint: JointCpSummary?,
    currentStaffId: String?,
): String = joint.participantFor(currentStaffId)?.fieldVisitId
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?: parentFieldVisitId?.trim()?.takeIf(String::isNotEmpty)
    ?: cpVisitId

/**
 * The field-visit id to send as a Joint CP cross-check, or null.
 *
 * [resolveCpFieldVisitId] falls back to the CP id when no leg id is known yet,
 * which is right for the tolerant visit/OTP routes. The Joint CP mutations
 * validate `fieldVisitId` as a strict fieldVisits id, so a CP id there fails
 * the whole request with "ArgumentValidationError: Found ID ... from table
 * clientPlaceVisits". The field is optional, so omit it instead.
 */
fun jointCpFieldVisitIdOrNull(fieldVisitId: String?, cpVisitId: String?): String? =
    fieldVisitId?.trim()
        ?.takeIf { it.isNotEmpty() && it != cpVisitId?.trim() }

/** Terminal parent state wins; otherwise a Joint CP actor sees their own leg state. */
fun resolveParticipantCpEffectiveStatus(
    serverEffectiveStatus: String?,
    cpStatus: String?,
    parentFieldVisitStatus: String?,
    joint: JointCpSummary?,
    currentStaffId: String?,
    cpCompletedAt: Long? = null,
    fieldVisitCompletedAt: Long? = null,
    arrivalOtpVerifiedAt: Long? = null,
): String {
    val cp = cpStatus?.trim().orEmpty()
    if (cp.lowercase(Locale.US) in TERMINAL_CP_STATUSES) return cp
    val server = serverEffectiveStatus?.trim().orEmpty()
    if (server.lowercase(Locale.US) in TERMINAL_CP_STATUSES) return server
    // A Joint CP is NOT finished when one participant's own leg finishes. The
    // visit closes only when the higher-level reviewer adds their remark and
    // completes it. Submitting for review completes the owner's field visit
    // (so fieldVisitCompletedAt is set), which is why this must be checked
    // BEFORE the completed-timestamp shortcut below.
    if (joint != null && jointReviewStillOpen(joint)) return JOINT_PENDING_REVIEW

    if ((cpCompletedAt ?: 0L) > 0L || (fieldVisitCompletedAt ?: 0L) > 0L) return "completed"

    val participant = joint.participantFor(currentStaffId)?.status?.trim().orEmpty()
    val candidates = if (participant.isNotEmpty()) {
        listOf(participant, server, cp)
    } else {
        listOf(parentFieldVisitStatus?.trim().orEmpty(), server, cp)
    }.filter(String::isNotEmpty)

    val mostAdvanced = candidates.maxByOrNull(::cpStatusProgressRank)
        ?: "scheduled"
    return if ((arrivalOtpVerifiedAt ?: 0L) > 0L && cpStatusProgressRank(mostAdvanced) < 3) {
        "arrived"
    } else {
        mostAdvanced
    }
}

/**
 * The card status for a Joint CP whose outcome is submitted but not yet
 * reviewed. Deliberately its own value rather than reusing "arrived" or
 * "completed": both of those already drive actions that are wrong here.
 */
const val JOINT_PENDING_REVIEW = "pending_joint_review"

/**
 * True while the Joint CP workflow is still running.
 *
 * Only answers from the workflow the SERVER resolved. When a payload carries no
 * workflow — an older deployment, or a list shape that omits it — this returns
 * false and the previous behaviour stands, so a missing field can never strand
 * a genuinely finished visit in a pending state.
 */
private fun jointReviewStillOpen(joint: JointCpSummary): Boolean {
    val state = joint.workflow?.state?.trim()?.lowercase(Locale.US)?.replace('-', '_').orEmpty()
    return state in JOINT_REVIEW_OPEN_STATES
}

/**
 * Only the states AFTER the owner has sent the outcome. The server's earlier
 * states (awaiting_both_trips, awaiting_owner_arrival, awaiting_owner_outcome)
 * are live trips: treating them as pending review hid the swipe/outcome
 * actions and dropped the trip screen back to "Start Trip".
 */
private val JOINT_REVIEW_OPEN_STATES = setOf(
    "pending_review",
    "reviewing",
)

private fun cpStatusProgressRank(status: String): Int = when (
    status.trim().lowercase(Locale.US).replace('-', '_')
) {
    "completed", "complete", "done", "closed" -> 4
    "arrived", "arrival_verified", "on_site" -> 3
    "in_progress", "ongoing", "started", "active", "enroute", "en_route" -> 2
    "scheduled", "assigned", "pending", "in_progress_cp" -> 1
    else -> 0
}

/** Missing legacy outcome text does not reopen an authoritative closed CP. */
fun isCpOutcomePending(cpStatus: String?, fieldVisitStatus: String?, outcome: String?): Boolean {
    val cp = cpStatus?.trim()?.lowercase(Locale.US).orEmpty()
    if (cp in TERMINAL_CP_STATUSES || !outcome.isNullOrBlank()) return false
    return fieldVisitStatus?.trim()?.lowercase(Locale.US) in setOf("completed", "complete", "done", "closed")
}

/** Completed CP activity belongs to the signed-in staff member's actual trip-start day. */
fun resolveCpActivityDate(
    scheduledDate: String,
    serverActivityDate: String?,
    cpCompletedAt: Long?,
    fieldVisitCompletedAt: Long?,
    participantStartedAt: Long? = null,
    fieldVisitStartedAt: Long? = null,
): String {
    val startedAt = participantStartedAt.validEpochMillis()
        ?: fieldVisitStartedAt.validEpochMillis()
    if (startedAt != null) {
        return Instant.ofEpochMilli(startedAt)
            .atZone(ZoneId.of("Asia/Kolkata"))
            .toLocalDate()
            .toString()
    }
    // Legacy rows may not carry any start timestamp. Keep their assigned day
    // rather than incorrectly grouping them by completion time.
    return scheduledDate
}

private fun Long?.validEpochMillis(): Long? = this?.takeIf { it > 0L }
