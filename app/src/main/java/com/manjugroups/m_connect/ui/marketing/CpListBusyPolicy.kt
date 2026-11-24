package com.manjugroups.m_connect.ui.marketing

/**
 * What the CP Visits list does when the MMS server is too busy to answer.
 *
 * The production backend runs at most 16 requests at once for every user
 * together. Under load it refuses with 429 ("TooManyConcurrentRequests") or a
 * 502/503/504. That is not a broken network or a bad request: the same call
 * works a little later. So the list keeps what it has (or the copy saved from
 * the last good load), tells the user the server is busy, and asks again on
 * its own after a growing pause instead of showing a dead-end error.
 */
object CpListBusyPolicy {

    /** Quiet automatic retries per busy spell; after that, pull to refresh. */
    const val MAX_AUTO_RETRIES = 5

    private const val FIRST_RETRY_MS = 10_000L
    private const val MAX_RETRY_MS = 60_000L
    private const val MIN_RETRY_AFTER_MS = 5_000L
    private const val MAX_RETRY_AFTER_MS = 120_000L

    /** HTTP codes that mean "server busy, try again shortly". */
    fun isServerBusy(httpCode: Int?): Boolean =
        httpCode == 429 || httpCode == 502 || httpCode == 503 || httpCode == 504

    /**
     * Wait before automatic retry number [attempt] (0-based), or null when no
     * more automatic retries should run. A server Retry-After (seconds) wins,
     * clamped so a bad header can neither hammer the server nor stall forever.
     */
    fun retryDelayMs(attempt: Int, retryAfterSeconds: Long?): Long? {
        if (attempt < 0 || attempt >= MAX_AUTO_RETRIES) return null
        retryAfterSeconds?.takeIf { it > 0 }?.let {
            return (it * 1000L).coerceIn(MIN_RETRY_AFTER_MS, MAX_RETRY_AFTER_MS)
        }
        var delay = FIRST_RETRY_MS
        repeat(attempt) { delay = (delay * 2).coerceAtMost(MAX_RETRY_MS) }
        return delay
    }

    /**
     * Only the plain browse list (no search, no filters, default dates) is
     * saved and shown as a fallback, so a saved copy is never mistaken for
     * the result of a search or filter the user just applied.
     */
    fun isPlainQuery(
        serverSearch: String?,
        status: String?,
        outcome: String?,
        cpType: String?,
        fromDate: String?,
        toDate: String?,
        assignedStaffId: String?,
        telecallerStaffId: String?,
    ): Boolean = listOf(
        serverSearch, status, outcome, cpType, fromDate, toDate, assignedStaffId, telecallerStaffId,
    ).all { it.isNullOrBlank() }

    /** Saved-list key: per signed-in staff member and per My/Team/All scope. */
    fun cacheKey(staffId: String?, scopeApiValue: String): String? =
        staffId?.takeIf { it.isNotBlank() }?.let { "cp_visits_list_${it}_$scopeApiValue" }

    /** Filter options rarely change; re-read them at most this often per scope/dates. */
    const val FILTER_OPTIONS_TTL_MS = 10 * 60_000L

    fun filterOptionsKey(scopeApiValue: String, fromDate: String?, toDate: String?): String =
        "$scopeApiValue|${fromDate.orEmpty()}|${toDate.orEmpty()}"

    fun filterOptionsStale(
        loadedKey: String?,
        loadedAtMs: Long,
        wantedKey: String,
        nowMs: Long,
    ): Boolean = loadedKey != wantedKey || nowMs - loadedAtMs >= FILTER_OPTIONS_TTL_MS

    fun busyMessage(retryInMs: Long?): String =
        if (retryInMs == null) {
            "The server is busy right now. Pull down to try again."
        } else {
            "The server is busy right now. Retrying in ${(retryInMs + 999) / 1000}s…"
        }
}
