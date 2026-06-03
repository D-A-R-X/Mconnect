package com.manjugroups.m_connect.auth

import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.IamPermissionsResponse
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide bus for IAM permission refreshes.
 *
 * The mobile previously fetched permissions ONLY at app startup
 * (MainActivity.onCreate → refreshSessionContext). Any change made on
 * the web admin UI mid-session was invisible to the running app until
 * the user killed and reopened it. That made every IAM edit feel like
 * "I have to rebuild the APK to test."
 *
 * This bus drives two things at once:
 *  1. A throttled `refresh()` that hits /api/iam/my-permissions and
 *     writes the result back into SessionManager. Throttling avoids
 *     hammering the endpoint when the user bounces between
 *     foreground/background quickly; refreshes that come in within
 *     [REFRESH_THROTTLE_MS] of the previous one short-circuit. Set
 *     `force = true` to override (used by manual "pull to refresh"
 *     style entry points).
 *  2. A SharedFlow that subscribers (e.g. AppLibraryFragment) listen
 *     to so they can re-render IAM-gated UI immediately when fresh
 *     permissions land — no need for screens to poll the session or
 *     rely on their own onResume timing.
 */
object IamUpdateBus {
    private const val REFRESH_THROTTLE_MS = 5_000L

    private val _updates = MutableSharedFlow<Set<String>>(
        replay = 0, extraBufferCapacity = 1,
    )
    val updates: SharedFlow<Set<String>> = _updates.asSharedFlow()

    private val mutex = Mutex()
    private var lastRefreshMs: Long = 0L

    /**
     * Mark the bus as having JUST refreshed — call this when something
     * else fetched IAM directly (e.g. MainActivity.refreshSessionContext)
     * and wrote into SessionManager without going through the bus. It
     * keeps the throttle accurate AND emits to subscribers so any
     * gated UI mounted after that initial fetch can re-render with the
     * new permissions immediately.
     */
    fun notifyFetched(fresh: Set<String>) {
        lastRefreshMs = System.currentTimeMillis()
        _updates.tryEmit(fresh)
    }

    /**
     * Pull the freshest permissions from the server, update the
     * session, and emit on [updates] so listeners can re-render. No-op
     * if the last successful refresh was within [REFRESH_THROTTLE_MS]
     * (unless `force` is true).
     */
    suspend fun refresh(
        session: SessionManager,
        api: ApiService = ApiService.create(),
        force: Boolean = false,
    ) {
        if (!session.isLoggedIn) return
        mutex.withLock {
            val now = System.currentTimeMillis()
            if (!force && now - lastRefreshMs < REFRESH_THROTTLE_MS) return
            val resp: IamPermissionsResponse = try {
                api.getMyIamPermissions(session.bearerToken)
            } catch (_: Throwable) {
                return
            }
            val fresh = resp.permissions.toSet()
            val before = session.iamPermissions
            val adminBefore = session.isAdmin
            session.iamPermissions = fresh
            session.isAdmin = resp.isAdmin
            lastRefreshMs = now
            // Only fan out when something actually changed — avoids a
            // pointless re-render storm in subscribers when the user
            // bounces the app every few seconds.
            if (fresh != before || resp.isAdmin != adminBefore) {
                _updates.tryEmit(fresh)
            }
        }
    }
}
