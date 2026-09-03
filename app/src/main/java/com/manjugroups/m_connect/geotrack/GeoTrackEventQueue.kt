package com.manjugroups.m_connect.geotrack

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.geotrack.data.GeoTrackDatabase
import com.manjugroups.m_connect.geotrack.data.PendingGeoTrackEventEntity
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.DirectTrackingStartRequest
import com.manjugroups.m_connect.network.DirectTrackingStopRequest
import com.manjugroups.m_connect.network.HeartbeatRequest
import com.manjugroups.m_connect.network.TamperReportRequest

object GeoTrackEventQueue {
    private val gson = Gson()
    private val metadataType = object : TypeToken<Map<String, Any?>>() {}.type
    private const val PREFS = "geotrack_event_queue"
    private const val DUPLICATE_WINDOW_MS = 6 * 60 * 60 * 1000L

    /** Reserved event type for queued heartbeat replays. */
    const val HEARTBEAT_EVENT_TYPE = "HEARTBEAT"
    const val TRACKING_START_EVENT_TYPE = "TRACKING_START"
    const val TRACKING_STOP_EVENT_TYPE = "TRACKING_STOP"
    private val directTamperTypes = setOf(
        "GPS_DISABLED",
        "GPS_ENABLED",
        "LOCATION_DISABLED",
        "LOCATION_ENABLED",
        "NETWORK_OFFLINE",
        "NETWORK_ONLINE",
        "AIRPLANE_MODE_ON",
        "AIRPLANE_MODE_OFF",
        "MOCK_LOCATION",
        "PERMISSION_MISSING",
        "DEVICE_REBOOT",
        "DEVICE_SHUTDOWN",
        "HEARTBEAT_MISSED",
    )

    suspend fun enqueue(
        context: Context,
        eventType: String,
        metadata: Map<String, Any?> = emptyMap(),
        occurredAt: Long = System.currentTimeMillis(),
    ) {
        val appContext = context.applicationContext
        val db = GeoTrackDatabase.getInstance(appContext)
        db.pendingGeoTrackEventDao().insert(
            PendingGeoTrackEventEntity(
                eventType = eventType,
                metadataJson = gson.toJson(metadata + ("ts" to occurredAt)),
                occurredAt = occurredAt,
            )
        )
    }

    suspend fun enqueueDistinct(
        context: Context,
        eventType: String,
        metadata: Map<String, Any?> = emptyMap(),
        signature: String = eventType,
        minIntervalMs: Long = DUPLICATE_WINDOW_MS,
        occurredAt: Long = System.currentTimeMillis(),
    ): Boolean {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "last_$signature"
        val last = prefs.getLong(key, 0L)
        if (occurredAt - last < minIntervalMs) return false

        enqueue(appContext, eventType, metadata, occurredAt)
        prefs.edit().putLong(key, occurredAt).apply()
        return true
    }

    suspend fun flush(
        context: Context,
        api: GeoTrackApi = GeoTrackApi.create(),
        session: SessionManager = SessionManager(context.applicationContext),
        limit: Int = 100,
    ): Int {
        if (!session.isLoggedIn) return 0
        val dao = GeoTrackDatabase.getInstance(context.applicationContext).pendingGeoTrackEventDao()
        val pending = dao.getPending(limit)
        if (pending.isEmpty()) return 0

        val sentIds = mutableListOf<Long>()
        // A single permanently-rejected event must not block everything
        // queued behind it (head-of-line). Per-event rejections are skipped
        // and retried next flush; only a run of consecutive failures — which
        // means the network itself is down — aborts the pass.
        var consecutiveFailures = 0
        for (event in pending) {
            val metadata = runCatching {
                gson.fromJson<Map<String, Any?>>(event.metadataJson, metadataType)
            }.getOrDefault(mapOf("ts" to event.occurredAt))
            // Original occurrence time: prefer the metadata "ts" stamp,
            // fall back to the row's occurredAt. Sent to the server so a
            // replayed heartbeat/tamper event backfills the timeline at the
            // moment it HAPPENED, not the moment connectivity returned.
            val occurredAt = (metadata["ts"] as? Number)?.toLong() ?: event.occurredAt
            val deviceId = (metadata["deviceId"] as? String) ?: session.trackingDeviceId ?: "android"
            val requestId = (metadata["requestId"] as? String)
                ?: "geotrack-event-$deviceId-${event.id}-$occurredAt"

            // Heartbeats reuse this queue (no separate Room table) but
            // need to hit the heartbeat endpoint, not the tamper one.
            // The heartbeat row's metadata carries sessionId/deviceId/
            // batteryPct/appVersion stamped at the time the original
            // send failed — replaying the same values preserves the
            // snapshot the device was in during the offline window.
            val ok = if (event.eventType == HEARTBEAT_EVENT_TYPE) {
                runCatching {
                    val resp = api.heartbeat(
                        token = session.bearerToken,
                        idempotencyKey = requestId,
                        body = HeartbeatRequest(
                            sessionId = (metadata["sessionId"] as? String)
                                ?: session.activeTrackingSessionId,
                            deviceId = deviceId,
                            requestId = requestId,
                            deviceSequence = occurredAt,
                            // -1 = "unknown battery at replay time"; the
                            // server treats negative as missing and just
                            // records the heartbeat tick. Same idea for
                            // unknown app version.
                            batteryPct = (metadata["batteryPct"] as? Number)?.toInt() ?: -1,
                            appVersion = (metadata["appVersion"] as? String) ?: "unknown",
                            recordedAt = occurredAt,
                            airplaneMode = metadata["airplaneMode"] as? Boolean,
                            locationEnabled = metadata["locationEnabled"] as? Boolean,
                        ),
                    )
                    resp.success
                }.getOrDefault(false)
            } else if (event.eventType == TRACKING_START_EVENT_TYPE) {
                runCatching {
                    api.startDirectTracking(
                        token = session.bearerToken,
                        idempotencyKey = requestId,
                        body = DirectTrackingStartRequest(
                            lat = (metadata["lat"] as? Number)?.toDouble(),
                            lng = (metadata["lng"] as? Number)?.toDouble(),
                        ),
                    ).success
                }.getOrDefault(false)
            } else if (event.eventType == TRACKING_STOP_EVENT_TYPE) {
                runCatching {
                    api.stopDirectTracking(
                        token = session.bearerToken,
                        idempotencyKey = requestId,
                        body = DirectTrackingStopRequest(),
                    ).success
                }.getOrDefault(false)
            } else {
                runCatching {
                    val body = TamperReportRequest(
                        sessionId = (metadata["sessionId"] as? String)
                            ?: session.activeTrackingSessionId,
                        eventType = event.eventType,
                        metadata = metadata,
                        detectedAt = occurredAt,
                        requestId = requestId,
                    )
                    if (event.eventType in directTamperTypes) {
                        api.reportTamper(
                            token = session.bearerToken,
                            idempotencyKey = requestId,
                            body = body,
                        ).success
                    } else {
                        // Historical MMS-only lifecycle events such as
                        // USER_LOGIN and APP_UPDATED are not accepted by the
                        // direct GeoTrack tamper contract.
                        api.reportLegacyTamper(session.bearerToken, body).success
                    }
                }.getOrDefault(false)
            }
            if (ok) {
                sentIds.add(event.id)
                consecutiveFailures = 0
            } else {
                consecutiveFailures++
                if (consecutiveFailures >= 3) break
            }
        }
        if (sentIds.isNotEmpty()) dao.deleteByIds(sentIds)
        return sentIds.size
    }
}
