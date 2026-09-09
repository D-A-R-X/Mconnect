package com.manjugroups.m_connect.geotrack

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.geotrack.service.GeoTrackService
import com.manjugroups.m_connect.network.DirectTrackingStartRequest
import com.manjugroups.m_connect.network.DirectTrackingStopRequest
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.notifications.PushTokenManager

object GeoTrackBootstrapSync {
    suspend fun sync(
        context: Context,
        allowPromptConsent: Boolean = false,
        api: GeoTrackApi = GeoTrackApi.create(),
        attendanceOpenOverride: Boolean? = null,
        contextId: String? = null,
        startedAt: Long = System.currentTimeMillis(),
        lat: Double? = null,
        lng: Double? = null,
    ): Boolean {
        val appContext = context.applicationContext
        val session = SessionManager(appContext)
        if (!session.isLoggedIn) return false
        if (!session.geoTrackingEnabled) {
            endDirectSession(appContext, session, api, lat, lng, "tracking_not_enabled")
            return false
        }

        val attendanceOpen = attendanceOpenOverride
            ?: AttendanceTrackingGate.hasOpenSessionNow(session.bearerToken)
        if (attendanceOpen == false) {
            endDirectSession(appContext, session, api, lat, lng, "attendance_session_closed")
            return false
        }
        if (attendanceOpen == null) {
            // Preserve a locally known active session during a temporary
            // attendance outage, but never create a new one without proof of
            // punch-in.
            applyDirectSession(appContext, session.activeTrackingSessionId)
            return session.shouldTrackNow
        }

        if (!session.geoConsentGiven) {
            session.shouldTrackNow = false
            GeoTrackService.stop(appContext)
            if (!session.geoConsentDeclined && allowPromptConsent && !GeoTrackConsentActivity.isActive) {
                context.startActivity(Intent(context, GeoTrackConsentActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
            }
            return false
        }

        queuePermissionHealth(appContext)
        runCatching { GeoTrackEventQueue.flush(appContext, api, session) }

        val currentResponse = runCatching {
            api.getCurrentTrackingSession(session.bearerToken)
        }.getOrElse {
            // A timeout is not proof that no session exists. Keep the last
            // acknowledged local session and retry recovery later instead of
            // creating a duplicate tracking session.
            applyDirectSession(appContext, session.activeTrackingSessionId)
            return session.shouldTrackNow
        }
        if (!currentResponse.success) {
            applyDirectSession(appContext, session.activeTrackingSessionId)
            return session.shouldTrackNow
        }
        val current = currentResponse.data
        val active = current?.takeIf { it.state.equals("active", ignoreCase = true) }
        val resolved = active ?: runCatching {
            val requestId = startRequestId(session.trackingDeviceId, contextId, startedAt)
            api.startDirectTracking(
                token = session.bearerToken,
                idempotencyKey = requestId,
                body = DirectTrackingStartRequest(
                    deviceId = session.trackingDeviceId,
                    contextId = contextId,
                    startedAt = startedAt,
                    lat = lat,
                    lng = lng,
                    batteryPct = batteryPct(appContext),
                ),
            ).takeIf { it.success }?.data
        }.getOrNull()

        if (resolved == null) {
            if (session.activeTrackingSessionId.isNullOrBlank()) {
                queueStart(appContext, session, contextId, startedAt, lat, lng)
            }
            applyDirectSession(appContext, session.activeTrackingSessionId)
            return session.shouldTrackNow
        }

        applyDirectSession(appContext, resolved.sessionId)

        // Fresh sign-in that landed inside a clock-in window → USER_LOGIN.
        if (session.pendingLoginEvent) {
            session.pendingLoginEvent = false
            runCatching {
                GeoTrackEventQueue.enqueue(
                    appContext,
                    "USER_LOGIN",
                    GeoTrackDeviceMeta.capture(appContext),
                )
            }
        }

        runCatching { GeoTrackEventQueue.flush(appContext, api, session) }
        return session.shouldTrackNow
    }

    suspend fun onPunchRecorded(
        context: Context,
        punchedIn: Boolean,
        contextId: String? = null,
        occurredAt: Long = System.currentTimeMillis(),
        lat: Double? = null,
        lng: Double? = null,
        api: GeoTrackApi = GeoTrackApi.create(),
    ): Boolean {
        val appContext = context.applicationContext
        val session = SessionManager(appContext)
        return if (punchedIn) {
            sync(
                context = appContext,
                allowPromptConsent = true,
                api = api,
                attendanceOpenOverride = true,
                contextId = contextId,
                startedAt = occurredAt,
                lat = lat,
                lng = lng,
            )
        } else {
            endDirectSession(appContext, session, api, lat, lng, "attendance_punch_out", occurredAt)
            false
        }
    }

    suspend fun endForLogout(
        context: Context,
        api: GeoTrackApi = GeoTrackApi.create(),
    ) {
        val appContext = context.applicationContext
        endDirectSession(
            context = appContext,
            session = SessionManager(appContext),
            api = api,
            lat = null,
            lng = null,
            reason = "user_logout",
        )
    }

    private fun applyDirectSession(context: Context, sessionId: String?) {
        val session = SessionManager(context.applicationContext)
        val shouldTrack = !sessionId.isNullOrBlank()
        session.activeTrackingSessionId = sessionId
        session.shouldTrackNow = shouldTrack

        if (shouldTrack && GeoTrackService.hasRequiredLocationPermissions(context)) {
            GeoTrackService.start(context)
        } else {
            GeoTrackService.stop(context)
        }
    }

    private suspend fun endDirectSession(
        context: Context,
        session: SessionManager,
        api: GeoTrackApi,
        lat: Double?,
        lng: Double?,
        reason: String,
        endedAt: Long = System.currentTimeMillis(),
    ) {
        val activeId = session.activeTrackingSessionId ?: runCatching {
            api.getCurrentTrackingSession(session.bearerToken).data?.sessionId
        }.getOrNull()
        if (!activeId.isNullOrBlank()) {
            val requestId = "tracking-end-$activeId"
            val stopped = runCatching {
                api.stopDirectTracking(
                    token = session.bearerToken,
                    idempotencyKey = requestId,
                    body = DirectTrackingStopRequest(
                        sessionId = activeId,
                        endedAt = endedAt,
                        lat = lat,
                        lng = lng,
                        reason = reason,
                    ),
                ).success
            }.getOrDefault(false)
            if (stopped) {
                session.activeTrackingSessionId = null
            } else {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    queueEnd(context, session, activeId, endedAt, lat, lng, reason)
                }
            }
        }
        stopLocally(context, session, clearSession = false)
    }

    private suspend fun queuePermissionHealth(context: Context) {
        val notificationPermission = PushTokenManager.hasNotificationPermission(context)
        val fineLocationPermission = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val backgroundLocationPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val activityRecognitionPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            hasPermission(context, Manifest.permission.ACTIVITY_RECOGNITION)
        val batteryOptimizationIgnored = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isIgnoringBatteryOptimizations(context.packageName)
        val missing = mutableListOf<String>()
        if (!notificationPermission) missing.add("notification")
        if (!fineLocationPermission) missing.add("fine_location")
        if (!backgroundLocationPermission) missing.add("background_location")
        if (!activityRecognitionPermission) missing.add("activity_recognition")
        if (batteryOptimizationIgnored == false) missing.add("battery_optimization")
        if (missing.isEmpty()) return

        GeoTrackEventQueue.enqueueDistinct(
            context,
            "PERMISSION_MISSING",
            mapOf(
                "missingPermissions" to missing.joinToString(","),
                "notificationPermission" to notificationPermission,
                "fineLocationPermission" to fineLocationPermission,
                "backgroundLocationPermission" to backgroundLocationPermission,
                "activityRecognitionPermission" to activityRecognitionPermission,
                "batteryOptimizationIgnored" to batteryOptimizationIgnored,
                "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL,
            ),
            signature = "permission_missing_${missing.sorted().joinToString("_")}",
        )
    }

    private suspend fun queueStart(
        context: Context,
        session: SessionManager,
        contextId: String?,
        startedAt: Long,
        lat: Double?,
        lng: Double?,
    ) {
        val requestId = startRequestId(session.trackingDeviceId, contextId, startedAt)
        GeoTrackEventQueue.enqueueDistinct(
            context,
            GeoTrackEventQueue.TRACKING_START_EVENT_TYPE,
            buildMap {
                put("deviceId", session.trackingDeviceId)
                put("requestId", requestId)
                put("startedAt", startedAt)
                contextId?.let { put("contextId", it) }
                lat?.let { put("lat", it) }
                lng?.let { put("lng", it) }
                batteryPct(context)?.let { put("batteryPct", it) }
            },
            signature = requestId,
            minIntervalMs = 24 * 60 * 60 * 1000L,
            occurredAt = startedAt,
        )
        GeoTrackFlushWorker.enqueue(context)
    }

    private suspend fun queueEnd(
        context: Context,
        session: SessionManager,
        sessionId: String,
        endedAt: Long,
        lat: Double?,
        lng: Double?,
        reason: String,
    ) {
        val requestId = "tracking-end-$sessionId"
        GeoTrackEventQueue.enqueueDistinct(
            context,
            GeoTrackEventQueue.TRACKING_STOP_EVENT_TYPE,
            buildMap {
                put("sessionId", sessionId)
                put("deviceId", session.trackingDeviceId)
                put("requestId", requestId)
                put("endedAt", endedAt)
                put("reason", reason)
                lat?.let { put("lat", it) }
                lng?.let { put("lng", it) }
            },
            signature = requestId,
            minIntervalMs = 24 * 60 * 60 * 1000L,
            occurredAt = endedAt,
        )
        GeoTrackFlushWorker.enqueue(context)
    }

    private fun stopLocally(context: Context, session: SessionManager, clearSession: Boolean) {
        session.shouldTrackNow = false
        if (clearSession) session.activeTrackingSessionId = null
        GeoTrackService.stop(context)
    }

    private fun startRequestId(deviceId: String, contextId: String?, startedAt: Long): String =
        "tracking-start-$deviceId-${contextId ?: AttendanceDayBoundary.dateKey(startedAt)}"

    private fun batteryPct(context: Context): Int? =
        (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
}
