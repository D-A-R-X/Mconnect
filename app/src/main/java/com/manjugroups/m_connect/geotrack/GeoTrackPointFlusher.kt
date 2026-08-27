package com.manjugroups.m_connect.geotrack

import android.content.Context
import android.util.Log
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.geotrack.data.GeoTrackDatabase
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.LocationPoint
import com.manjugroups.m_connect.network.PushBatchRequest

/**
 * Store-and-forward uploader for buffered location points.
 *
 * Points are stamped with their tracking session at CAPTURE time and this
 * flusher groups uploads by that stamp, so a backlog flushes under the RIGHT
 * session even after clock-out cleared the live id, after a reboot, or the
 * next day. Rows are deleted ONLY after a confirmed server ack; a failing
 * stale-session group is kept buffered and never blocks other groups.
 *
 * Shared by GeoTrackService (periodic + reconnect + teardown syncs),
 * GeoTrackFlushWorker (post-teardown leftovers) and TrackingCheckWorker.
 */
object GeoTrackPointFlusher {

    private const val TAG = "GeoTrackFlusher"
    private const val BATCH_SIZE = 200

    data class Result(
        val flushedPoints: Int,
        val batches: Int,
        val hadFailure: Boolean,
        /** True when the server rejected the CURRENT active session id. */
        val activeSessionInvalid: Boolean,
        val lastError: String? = null,
        /** True when nothing is left unsent (attributable) in the buffer. */
        val drained: Boolean,
    )

    suspend fun flush(
        context: Context,
        api: GeoTrackApi = GeoTrackApi.create(),
        session: SessionManager = SessionManager(context.applicationContext),
        maxBatches: Int = 10,
    ): Result {
        if (!session.isLoggedIn) {
            return Result(0, 0, hadFailure = false, activeSessionInvalid = false, drained = true)
        }
        val dao = GeoTrackDatabase.getInstance(context.applicationContext).locationPointDao()
        val activeSessionId = session.activeTrackingSessionId

        var flushed = 0
        var batches = 0
        var hadFailure = false
        var activeInvalid = false
        var lastError: String? = null
        // Session ids that already failed THIS pass — skip their rows so a
        // rejecting group can't spin the loop or get re-sent this cycle.
        val failedSessions = mutableSetOf<String>()

        // Drain the whole backlog in bounded batches, oldest first.
        while (batches < maxBatches) {
            val unsent = dao.getUnsent(BATCH_SIZE)
            if (unsent.isEmpty()) break

            // Attribute every row to its capture-time session; legacy rows
            // (pre-migration) fall back to the live id. Unattributable rows
            // stay buffered — never guess a session for someone's journey.
            val sendable = unsent
                .filter { (it.sessionId ?: activeSessionId) != null }
                .groupBy { (it.sessionId ?: activeSessionId)!! }
                .filterKeys { it !in failedSessions }
            if (sendable.isEmpty()) break

            var progressed = false
            for ((sid, rows) in sendable) {
                val points = rows.map { e ->
                    LocationPoint(
                        lat = e.lat, lng = e.lng, accuracy = e.accuracy,
                        speed = e.speed, bearing = e.bearing, altitude = e.altitude,
                        activity = e.activity, activityConfidence = e.activityConfidence,
                        isMock = e.isMock, batteryPct = e.batteryPct,
                        networkType = e.networkType, gpsEnabled = e.gpsEnabled,
                        airplaneMode = e.airplaneMode, recordedAt = e.recordedAt,
                    )
                }
                val resp = try {
                    api.pushBatch(
                        session.bearerToken,
                        PushBatchRequest(
                            sessionId = sid,
                            deviceId = session.trackingDeviceId,
                            points = points,
                        ),
                    )
                } catch (e: Exception) {
                    hadFailure = true
                    lastError = "${e.javaClass.simpleName}: ${e.message}"
                    failedSessions.add(sid)
                    // Network-level failure — every other group will fail
                    // the same way; stop the pass and keep everything.
                    return Result(flushed, batches, hadFailure, activeInvalid, lastError, drained = false)
                }

                if (resp.success) {
                    // Ack'd — safe to delete. Out-of-window points the server
                    // intentionally skipped are gone by policy (tracking is
                    // strictly clock-in → clock-out); in-window ones are stored.
                    dao.deleteByIds(rows.map { it.id })
                    flushed += resp.inserted ?: rows.size
                    progressed = true
                    // A fully-rejected batch is the "Live but zero GPS" trap —
                    // make the reason visible in logcat (accuracy gate vs
                    // window clamp) instead of silently acking.
                    if ((resp.inserted ?: 0) == 0 && rows.isNotEmpty()) {
                        Log.w(
                            TAG,
                            "Batch ack'd but stored 0/${rows.size} points " +
                                "(rejectedAccuracy=${resp.rejectedAccuracy ?: -1}, " +
                                "rejectedWindow=${resp.rejectedWindow ?: -1}, session=$sid)",
                        )
                    }
                } else {
                    hadFailure = true
                    lastError = resp.error
                    failedSessions.add(sid)
                    Log.w(TAG, "Batch rejected for session $sid: ${resp.error}")
                    if (
                        sid == activeSessionId &&
                        resp.error?.contains("Tracking session not active", ignoreCase = true) == true
                    ) {
                        activeInvalid = true
                    }
                    // Keep rows buffered; move on to the other groups.
                }
            }
            if (!progressed) break
            batches++
        }

        val remaining = dao.getUnsentCount()
        return Result(flushed, batches, hadFailure, activeInvalid, lastError, drained = remaining == 0)
    }
}
