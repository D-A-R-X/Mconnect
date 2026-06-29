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
        val activeSessionId = bootstrap?.activeSession?.id
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
