package com.manjugroups.m_connect.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * Today's attendance and day sessions are read by Home, the HR attendance
 * flow and the GeoTrack gate, often at the same moment (app open, resume,
 * after a punch). Identical reads that overlap share ONE request; nothing is
 * kept once it answers, so a read after a clock-in/out is always fresh.
 * (19 Nov 2026: these two endpoints were the busiest on the server.)
 *
 * The first caller makes the request in its own coroutine; overlapping
 * callers wait for its answer. If that first caller is cancelled, the ones
 * waiting make their own request instead of failing.
 */
object AttendanceReads {
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Any>>()

    private class LeaderCancelled : Exception()

    suspend fun today(api: ApiService, token: String, date: String? = null): AttendanceTodayResponse =
        shared("today|$token|${date.orEmpty()}") { api.getMyAttendanceToday(token, date) }

    suspend fun daySessions(api: ApiService, token: String, date: String? = null): DaySessionsResponse =
        shared("day|$token|${date.orEmpty()}") { api.getDaySessions(token, date) }

    @Suppress("UNCHECKED_CAST")
    internal suspend fun <T : Any> shared(key: String, fetch: suspend () -> T): T {
        val mine = CompletableDeferred<Any>()
        val existing = inFlight.putIfAbsent(key, mine)
        if (existing != null) {
            return try {
                existing.await() as T
            } catch (_: LeaderCancelled) {
                fetch()
            }
        }
        try {
            val value = fetch()
            mine.complete(value)
            return value
        } catch (cancelled: CancellationException) {
            mine.completeExceptionally(LeaderCancelled())
            throw cancelled
        } catch (error: Throwable) {
            mine.completeExceptionally(error)
            throw error
        } finally {
            inFlight.remove(key, mine)
        }
    }

    /** Requests currently shared (tests). */
    internal fun inFlightCount(): Int = inFlight.size
}
