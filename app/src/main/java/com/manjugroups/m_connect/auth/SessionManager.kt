package com.manjugroups.m_connect.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

class SessionManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "mconnect_session",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var userName: String?
        get() = prefs.getString(KEY_USER_NAME, null)
        set(value) = prefs.edit().putString(KEY_USER_NAME, value).apply()

    var userPhone: String?
        get() = prefs.getString(KEY_USER_PHONE, null)
        set(value) = prefs.edit().putString(KEY_USER_PHONE, value).apply()

    var iamPermissions: Set<String>
        get() = prefs.getStringSet(KEY_IAM_PERMISSIONS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_IAM_PERMISSIONS, value).apply()

    var isAdmin: Boolean
        get() = prefs.getBoolean(KEY_IS_ADMIN, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_ADMIN, value).apply()

    var pushToken: String?
        get() = prefs.getString(KEY_PUSH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_PUSH_TOKEN, value).apply()

    var notificationPermissionPrompted: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_PROMPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_PROMPTED, value).apply()

    // GeoTrack fields
    var staffId: String?
        get() = prefs.getString(KEY_STAFF_ID, null)
        set(value) = prefs.edit().putString(KEY_STAFF_ID, value).apply()

    var geoTrackingEnabled: Boolean
        get() = prefs.getBoolean(KEY_GEO_TRACKING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_GEO_TRACKING_ENABLED, value).apply()

    var geoConsentGiven: Boolean
        get() = prefs.getBoolean(KEY_GEO_CONSENT_GIVEN, false)
        set(value) = prefs.edit().putBoolean(KEY_GEO_CONSENT_GIVEN, value).apply()

    var geoConsentDeclined: Boolean
        get() = prefs.getBoolean(KEY_GEO_CONSENT_DECLINED, false)
        set(value) = prefs.edit().putBoolean(KEY_GEO_CONSENT_DECLINED, value).apply()

    var trackingDeviceId: String
        get() {
            val existing = prefs.getString(KEY_TRACKING_DEVICE_ID, null)
            if (!existing.isNullOrBlank()) return existing
            val created = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_TRACKING_DEVICE_ID, created).apply()
            return created
        }
        set(value) = prefs.edit().putString(KEY_TRACKING_DEVICE_ID, value).apply()

    var activeTrackingSessionId: String?
        get() = prefs.getString(KEY_ACTIVE_TRACKING_SESSION_ID, null)
        set(value) = prefs.edit().putString(KEY_ACTIVE_TRACKING_SESSION_ID, value).apply()

    var shouldTrackNow: Boolean
        get() = prefs.getBoolean(KEY_SHOULD_TRACK_NOW, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOULD_TRACK_NOW, value).apply()

    val isLoggedIn: Boolean
        get() = token != null

    val bearerToken: String
        get() = "Bearer $token"

    fun hasPermission(perm: String): Boolean = isAdmin || iamPermissions.contains(perm)

    fun saveSession(token: String, name: String?, phone: String?) {
        this.token = token
        this.userName = name
        this.userPhone = phone
    }

    fun clearSession() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_TOKEN = "session_token"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_PHONE = "user_phone"
        private const val KEY_IAM_PERMISSIONS = "iam_permissions"
        private const val KEY_IS_ADMIN = "is_admin"
        private const val KEY_PUSH_TOKEN = "push_token"
        private const val KEY_NOTIFICATION_PERMISSION_PROMPTED = "notification_permission_prompted"
        private const val KEY_STAFF_ID = "staff_id"
        private const val KEY_GEO_TRACKING_ENABLED = "geo_tracking_enabled"
        private const val KEY_GEO_CONSENT_GIVEN = "geo_consent_given"
        private const val KEY_GEO_CONSENT_DECLINED = "geo_consent_declined"
        private const val KEY_TRACKING_DEVICE_ID = "tracking_device_id"
        private const val KEY_ACTIVE_TRACKING_SESSION_ID = "active_tracking_session_id"
        private const val KEY_SHOULD_TRACK_NOW = "should_track_now"
    }
}
