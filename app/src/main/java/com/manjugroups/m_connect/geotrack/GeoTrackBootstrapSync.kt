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
            api.syncTrackingDevice(
                session.bearerToken,
                TrackingDeviceSyncRequest(
                    deviceId = session.trackingDeviceId,
                    appVersion = BuildConfig.VERSION_NAME,
                    pushToken = session.pushToken,
                    notificationPermission = PushTokenManager.hasNotificationPermission(appContext),
                    fineLocationPermission = hasPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION),
                    backgroundLocationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        hasPermission(appContext, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    } else {
                        true
                    },
                    activityRecognitionPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        hasPermission(appContext, Manifest.permission.ACTIVITY_RECOGNITION)
                    } else {
                        true
                    },
                    batteryOptimizationIgnored = (appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                        ?.isIgnoringBatteryOptimizations(appContext.packageName),
                    manufacturer = Build.MANUFACTURER,
                    model = Build.MODEL,
                )
            )
        }.getOrNull()

        val bootstrap = deviceSync?.bootstrap ?: runCatching {
            api.getTrackingBootstrap(session.bearerToken, session.trackingDeviceId).data
        }.getOrNull()

        apply(context, bootstrap, allowPromptConsent)
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
            if (allowPromptConsent) {
                context.startActivity(Intent(context, GeoTrackConsentActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
}
