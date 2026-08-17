package com.manjugroups.m_connect.auth

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings

/**
 * Device identity + telemetry attached to a mobile OTP login so the backend can
 * bind this staff account to a single device (see the web Security tab's "Bound
 * Mobile Device" card). `deviceId` is Settings.Secure.ANDROID_ID — the stable
 * per-device id an app can actually read (a real hardware MAC is not obtainable
 * on modern Android). Only sent from the app; web logins never carry it.
 */
data class LoginDeviceInfo(
    val deviceId: String,
    val platform: String,
    val model: String,
    val batteryPct: Double?,
) {
    companion object {
        fun capture(context: Context): LoginDeviceInfo? {
            val deviceId = runCatching {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ANDROID_ID,
                )
            }.getOrNull()?.trim().orEmpty()
            // No usable id → return null so the request omits binding fields and
            // the backend applies the grace path rather than binding "unknown".
            if (deviceId.isEmpty() || deviceId == "9774d56d682e549c") return null
            val battery = runCatching {
                (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
                    ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    ?.takeIf { it in 0..100 }
                    ?.toDouble()
            }.getOrNull()
            val model = listOf(Build.MANUFACTURER, Build.MODEL)
                .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
                .joinToString(" ")
                .ifEmpty { Build.MODEL ?: "Android device" }
            return LoginDeviceInfo(
                deviceId = deviceId,
                platform = "android",
                model = model,
                batteryPct = battery,
            )
        }
    }
}
