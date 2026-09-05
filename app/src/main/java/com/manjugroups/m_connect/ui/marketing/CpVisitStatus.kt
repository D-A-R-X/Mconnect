package com.manjugroups.m_connect.ui.marketing

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

/** Missing legacy outcome text does not reopen an authoritative closed CP. */
fun isCpOutcomePending(cpStatus: String?, fieldVisitStatus: String?, outcome: String?): Boolean {
    val cp = cpStatus?.trim()?.lowercase(Locale.US).orEmpty()
    if (cp in TERMINAL_CP_STATUSES || !outcome.isNullOrBlank()) return false
    return fieldVisitStatus?.trim()?.lowercase(Locale.US) in setOf("completed", "complete", "done", "closed")
}
