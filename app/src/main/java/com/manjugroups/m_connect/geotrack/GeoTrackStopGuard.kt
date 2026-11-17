package com.manjugroups.m_connect.geotrack

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One tracking-session end at a time, and never the same session twice.
 *
 * Several paths end a session: the service's queued stop (flushed right away),
 * [GeoTrackBootstrapSync]'s direct end from any of its many callers (app open,
 * Home, workers, push, boot), and the queue replay. When attendance closes,
 * e.g. at the day boundary or on clock-out, two of them fired together for the
 * same session. The first got 200, the second got 404 "tracking record not
 * found" (the service refuses to end an ended session): 101 issue reports from
 * 50 staff on build 104, clustered at 12:00 am.
 *
 * Holding the lock across "resolve the session, POST end" means the second
 * caller re-resolves after the first finished and finds nothing to end. The
 * remembered ids cover the queue replay, which carries its session id with it.
 */
internal object GeoTrackStopGuard {
    private const val REMEMBER_MS = 6 * 60 * 60 * 1000L
    private const val MAX_REMEMBERED = 32

    private val mutex = Mutex()
    private val ended = LinkedHashMap<String, Long>()

    suspend fun <T> serialized(block: suspend () -> T): T = mutex.withLock { block() }

    @Synchronized
    fun wasEnded(sessionId: String, now: Long = System.currentTimeMillis()): Boolean {
        prune(now)
        return ended.containsKey(sessionId)
    }

    @Synchronized
    fun markEnded(sessionId: String, now: Long = System.currentTimeMillis()) {
        if (sessionId.isBlank()) return
        ended.remove(sessionId)
        ended[sessionId] = now
        prune(now)
    }

    private fun prune(now: Long) {
        val iterator = ended.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > REMEMBER_MS || ended.size > MAX_REMEMBERED) iterator.remove()
        }
    }
}
