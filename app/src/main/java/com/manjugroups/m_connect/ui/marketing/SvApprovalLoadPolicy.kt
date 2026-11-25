package com.manjugroups.m_connect.ui.marketing

/**
 * How the SV Approvals screen reads its task list without tripping the
 * server's busy limit.
 *
 * The queue is filtered on the phone out of the 500-row task-manager list,
 * a heavy read. Opening Apps already reads that list for the SV Approvals
 * badge, and tapping SV Approvals straight after used to fire a second,
 * identical read while the first was still running. Under load the server
 * refused it with 429 ("Server busy"), and only a manual Retry, made once the
 * first read had finished, showed the data.
 *
 * Now the screen shares the badge's read (in flight, or finished moments
 * ago), and a busy answer is retried quietly with a short backoff while the
 * skeleton stays up.
 */
internal object SvApprovalLoadPolicy {

    /** A task list read this recently is reused instead of read again. */
    const val SHARE_MS = 30_000L

    /** Quiet automatic retries on a busy server before the Retry card shows. */
    const val MAX_AUTO_RETRIES = 4

    private val RETRY_DELAYS_MS = longArrayOf(1_500L, 4_000L, 10_000L, 20_000L)
    private const val MIN_RETRY_AFTER_MS = 1_000L
    private const val MAX_RETRY_AFTER_MS = 60_000L

    /** Whether a read made at [fetchedAtMs] can be reused at [nowMs]. */
    fun isFresh(fetchedAtMs: Long, nowMs: Long): Boolean =
        fetchedAtMs > 0L && nowMs - fetchedAtMs in 0..SHARE_MS

    /**
     * Wait before automatic retry [attempt] (0-based), or null when the
     * automatic retries are used up. A server Retry-After (seconds) wins,
     * clamped so a bad header can neither hammer the server nor stall.
     */
    fun retryDelayMs(attempt: Int, retryAfterSeconds: Long?): Long? {
        if (attempt < 0 || attempt >= MAX_AUTO_RETRIES) return null
        retryAfterSeconds?.takeIf { it > 0 }?.let {
            return (it * 1000L).coerceIn(MIN_RETRY_AFTER_MS, MAX_RETRY_AFTER_MS)
        }
        return RETRY_DELAYS_MS[attempt]
    }
}
