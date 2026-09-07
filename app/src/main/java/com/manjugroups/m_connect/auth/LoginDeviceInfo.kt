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
            val appContext = context.applicationContext
            val platformDeviceId = runCatching {
                Settings.Secure.getString(
                    appContext.contentResolver,
                    Settings.Secure.ANDROID_ID,
                )
            }.getOrNull()?.trim().orEmpty()
            // No usable id → return null so the request omits binding fields and
            // the backend applies the grace path rather than binding "unknown".
            if (platformDeviceId.isEmpty() || platformDeviceId == "9774d56d682e549c") return null

            // Keep the identity in a preference file that is deliberately
            // separate from SessionManager. Logging out clears the session
            // file, but never this device record. Seed with ANDROID_ID so
            // already-bound production users keep the exact same backend id.
            // If Android reports a different value (factory reset, signing-key
            // change, or a backup restored to another phone), trust the current
            // OS-scoped identity and replace the stale local copy.
            val identityPrefs = appContext.getSharedPreferences(
                DEVICE_IDENTITY_PREFS,
                Context.MODE_PRIVATE,
            )
            val storedDeviceId = identityPrefs.getString(KEY_DEVICE_ID, null)?.trim()
            val deviceId = if (storedDeviceId == platformDeviceId) {
                storedDeviceId
            } else {
                identityPrefs.edit().putString(KEY_DEVICE_ID, platformDeviceId).apply()
                platformDeviceId
            }
            val battery = runCatching {
                (appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
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

        private const val DEVICE_IDENTITY_PREFS = "mconnect_device_identity"
        private const val KEY_DEVICE_ID = "platform_device_id"
    }
}
