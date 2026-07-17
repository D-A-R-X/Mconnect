package com.manjugroups.m_connect.geotrack

import android.content.Context
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import com.manjugroups.m_connect.BuildConfig

/**
 * Lightweight, synchronous device snapshot for GeoTrack events raised OUTSIDE
 * the tracking service (login / logout, and the app-lifecycle side of things).
 * Mirrors the battery/gps/airplane fields the service's own buildEventMetadata
 * captures, minus the fused-location await — so it is safe to call on the main
 * thread or in a dying process. Battery is -1 when it can't be read.
 */
object GeoTrackDeviceMeta {
    fun capture(context: Context): Map<String, Any?> {
        val ctx = context.applicationContext
        val battery = runCatching {
            (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrDefault(-1)
        val gpsEnabled = runCatching {
            (ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
                .isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
        val airplane = runCatching {
            Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        }.getOrDefault(false)
        return mapOf(
            "batteryPct" to battery,
            "gpsEnabled" to gpsEnabled,
            "airplaneMode" to airplane,
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "appVersion" to BuildConfig.VERSION_NAME,
        )
    }
}
