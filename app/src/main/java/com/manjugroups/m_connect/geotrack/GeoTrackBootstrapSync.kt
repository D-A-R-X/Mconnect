package com.manjugroups.m_connect.geotrack

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.manjugroups.m_connect.BuildConfig
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.geotrack.service.GeoTrackService
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.TrackingBootstrapData
import com.manjugroups.m_connect.network.TrackingDeviceSyncRequest
import com.manjugroups.m_connect.notifications.PushTokenManager

object GeoTrackBootstrapSync {
    suspend fun sync(
        context: Context,
        allowPromptConsent: Boolean = false,
        api: GeoTrackApi = GeoTrackApi.create(),
    ): Boolean {
        val appContext = context.applicationContext
        val session = SessionManager(appContext)
        if (!session.isLoggedIn) return false

        // Privacy boundary: bootstrap/device state can outlive yesterday's
        // attendance session. Never sync tracking state, queue permission
        // events, or start the service until today's attendance APIs confirm
        // that a punch-in session is open. Unknown is fail-closed for a fresh
        // start; an already-running service handles transient outages itself.
        val attendanceOpen = AttendanceTrackingGate.hasOpenSessionNow(session.bearerToken)
        if (!AttendanceTrackingGate.mayStartTracking(attendanceOpen)) {
            if (attendanceOpen == false) {
                session.shouldTrackNow = false
                session.activeTrackingSessionId = null
                GeoTrackService.stop(appContext)
            }
            return false
        }

        val deviceSync = runCatching {
            val notificationPermission = PushTokenManager.hasNotificationPermission(appContext)
            val fineLocationPermission = hasPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
            val backgroundLocationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                hasPermission(appContext, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                true
            }
            val activityRecognitionPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                hasPermission(appContext, Manifest.permission.ACTIVITY_RECOGNITION)
            } else {
                true
            }
            val batteryOptimizationIgnored = (appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.isIgnoringBatteryOptimizations(appContext.packageName)

            queueMissingPermissionEvent(
                appContext,
                notificationPermission = notificationPermission,
                fineLocationPermission = fineLocationPermission,
                backgroundLocationPermission = backgroundLocationPermission,
                activityRecognitionPermission = activityRecognitionPermission,
                batteryOptimizationIgnored = batteryOptimizationIgnored,
            )

            api.syncTrackingDevice(
                session.bearerToken,
                TrackingDeviceSyncRequest(
                    deviceId = session.trackingDeviceId,
                    appVersion = BuildConfig.VERSION_NAME,
                    pushToken = session.pushToken,
                    notificationPermission = notificationPermission,
                    fineLocationPermission = fineLocationPermission,
                    backgroundLocationPermission = backgroundLocationPermission,
                    activityRecognitionPermission = activityRecognitionPermission,
                    batteryOptimizationIgnored = batteryOptimizationIgnored,
                    manufacturer = Build.MANUFACTURER,
                    model = Build.MODEL,
                )
            )
        }.getOrNull()

        val bootstrap = deviceSync?.bootstrap ?: runCatching {
            api.getTrackingBootstrap(session.bearerToken, session.trackingDeviceId).data
        }.getOrNull()

        apply(context, bootstrap, allowPromptConsent)

        // Fresh sign-in that landed inside a clock-in window → USER_LOGIN.
        // Only consume the flag once we actually have a bootstrap result, so a
        // failed fetch retries on the next sync instead of dropping the event.
        // If the login was outside a tracking window (shouldTrackNow == false)
        // we still consume it — a normal off-shift sign-in is not an event.
        if (bootstrap != null && session.pendingLoginEvent) {
            session.pendingLoginEvent = false
            if (session.shouldTrackNow) {
                runCatching {
                    GeoTrackEventQueue.enqueue(
                        appContext,
                        "USER_LOGIN",
                        GeoTrackDeviceMeta.capture(appContext),
                    )
                }
            }
        }

        runCatching { GeoTrackEventQueue.flush(appContext, api, session) }
        return session.shouldTrackNow
    }

    fun apply(
        context: Context,
        bootstrap: TrackingBootstrapData?,
        allowPromptConsent: Boolean = false,
    ) {
        val appContext = context.applicationContext
        val session = SessionManager(appContext)
        // A null bootstrap means the fetch FAILED (or was never attempted) — NOT a
        // definitive "off shift". The old code fell through and, because every
        // `bootstrap?.…` read is null, set shouldTrack=false, cleared
        // activeTrackingSessionId, and STOPPED the service. On a flaky field
        // network (the norm) a single failed sync on resume/punch therefore killed
        // a live tracking service: heartbeats and point capture both died while the
        // phone was active, so the staffer showed OFFLINE with zero travelled km
        // until the next successful sync. Offline-safe fix: leave the current
        // tracking state untouched and wait for a real bootstrap. A genuine
        // clock-out still stops tracking — it arrives as a non-null bootstrap with
        // shouldTrack=false, and the running service also self-stops via its own
        // authoritative clock-in gate (enforceClockInGate).
        if (bootstrap == null) {
            return
        }
        val activeSessionId = bootstrap.activeSession?.id
        val shouldTrack = bootstrap?.shouldTrack == true && !activeSessionId.isNullOrBlank()

        session.geoTrackingEnabled =
            bootstrap?.assignment?.attendance != null || bootstrap?.assignment?.siteVisit != null
        session.geoConsentGiven = bootstrap?.consent?.status == "granted"
        session.geoConsentDeclined =
            bootstrap?.consent?.status == "declined" || bootstrap?.consent?.status == "revoked"
        session.activeTrackingSessionId = activeSessionId
        session.shouldTrackNow = shouldTrack

        if (shouldTrack && bootstrap?.shouldPromptConsent == true) {
            // Only launch the consent screen if one isn't already open — sync runs
            // on every resume/punch, which would otherwise stack duplicate screens.
            if (allowPromptConsent && !GeoTrackConsentActivity.isActive) {
                context.startActivity(Intent(context, GeoTrackConsentActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
            }
            GeoTrackService.stop(appContext)
            return
        }

        if (shouldTrack && GeoTrackService.hasRequiredLocationPermissions(appContext)) {
            GeoTrackService.start(appContext)
        } else {
            GeoTrackService.stop(appContext)
        }
    }

    private fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private suspend fun queueMissingPermissionEvent(
        context: Context,
        notificationPermission: Boolean,
        fineLocationPermission: Boolean,
        backgroundLocationPermission: Boolean,
        activityRecognitionPermission: Boolean,
        batteryOptimizationIgnored: Boolean?,
    ) {
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
}
