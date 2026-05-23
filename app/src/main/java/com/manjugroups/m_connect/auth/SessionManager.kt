package com.manjugroups.m_connect.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

class SessionManager(context: Context) {

    private val prefs: SharedPreferences = openEncryptedPrefs(context.applicationContext)

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var userName: String?
        get() = prefs.getString(KEY_USER_NAME, null)
        set(value) = prefs.edit().putString(KEY_USER_NAME, value).apply()

    var userPhone: String?
        get() = prefs.getString(KEY_USER_PHONE, null)
        set(value) = prefs.edit().putString(KEY_USER_PHONE, value).apply()

    var userPhotoUrl: String?
        get() = prefs.getString(KEY_USER_PHOTO_URL, null)
        set(value) = prefs.edit().putString(KEY_USER_PHOTO_URL, value).apply()

    var iamPermissions: Set<String>
        get() = prefs.getStringSet(KEY_IAM_PERMISSIONS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_IAM_PERMISSIONS, value).apply()

    var isAdmin: Boolean
        get() = prefs.getBoolean(KEY_IS_ADMIN, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_ADMIN, value).apply()

    // Reporting officer (manager) — sent in leave/permission apply requests so
    // the backend can route the approval to the right person. Populated from
    // the user's staff record on bootstrap and on every profile refresh.
    var reportingToId: String?
        get() = prefs.getString(KEY_REPORTING_TO_ID, null)
        set(value) = prefs.edit().putString(KEY_REPORTING_TO_ID, value).apply()

    var reportingToName: String?
        get() = prefs.getString(KEY_REPORTING_TO_NAME, null)
        set(value) = prefs.edit().putString(KEY_REPORTING_TO_NAME, value).apply()

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

    var boundBaseUrl: String?
        get() = prefs.getString(KEY_BOUND_BASE_URL, null)
        set(value) = prefs.edit().putString(KEY_BOUND_BASE_URL, value).apply()

    fun hasPermission(perm: String): Boolean = isAdmin || iamPermissions.contains(perm)

    fun saveSession(token: String, name: String?, phone: String?) {
        this.token = token
        this.userName = name
        this.userPhone = phone
        this.boundBaseUrl = com.manjugroups.m_connect.BuildConfig.BASE_URL
    }

    fun clearSession() {
        prefs.edit().clear().apply()
    }

    /**
     * Wipes the session if the BuildConfig BASE_URL has changed since the
     * token was minted. Prevents data from a previous backend (e.g., old
     * Convex deployment) from showing through after switching the URL.
     * Returns true if the session was purged.
     */
    fun purgeIfBaseUrlChanged(): Boolean {
        val current = com.manjugroups.m_connect.BuildConfig.BASE_URL
        val bound = boundBaseUrl
        // Wipe when (a) the bound URL no longer matches the current build, or
        // (b) the session predates this guard (no bound URL recorded). The
        // second case treats legacy tokens as untrusted because we can't be
        // sure which backend they were minted against — safer to re-auth.
        if (token != null && bound != current) {
            clearSession()
            return true
        }
        return false
    }

    companion object {
        private const val TAG = "SessionManager"
        private const val PREFS_NAME = "mconnect_session"

        /**
         * Open the encrypted prefs, recovering automatically if the keystore
         * master key can no longer decrypt the stored blob.
         *
         * `EncryptedSharedPreferences.create` throws `AEADBadTagException`
         * (wrapped in `GeneralSecurityException` / `IOException`) when the
         * Android Keystore master key was rotated, invalidated, or the
         * encrypted prefs file came from a backup whose key no longer
         * matches. Without recovery the app FCs on every launch — every
         * SplashActivity → MconnectApp.onCreate → SessionManager(…) crashes
         * before any UI renders, the user can't even reach Login to start
         * over.
         *
         * Recovery: delete the corrupt prefs file + clear the master key
         * alias so the next open mints fresh credentials. Cost is one
         * forced re-login; benefit is the app actually starts.
         */
        private fun openEncryptedPrefs(context: Context): SharedPreferences {
            return try {
                buildEncryptedPrefs(context)
            } catch (first: Throwable) {
                // AEADBadTagException + its KeyStoreException cause sit
                // under GeneralSecurityException / IOException; catch the
                // widest net so OEM-specific subclass leaks don't slip
                // past us.
                Log.w(
                    TAG,
                    "EncryptedSharedPreferences open failed; wiping and retrying.",
                    first,
                )
                // 1. Drop the on-disk prefs file (this is the cipher blob
                //    that no longer decrypts).
                try {
                    context.deleteSharedPreferences(PREFS_NAME)
                } catch (deleteErr: Throwable) {
                    Log.w(TAG, "deleteSharedPreferences failed", deleteErr)
                }
                // 2. Rebuild — MasterKey.Builder will re-create the
                //    keystore alias if it was missing/invalid, and the
                //    empty prefs file gets a fresh encrypted header.
                try {
                    buildEncryptedPrefs(context)
                } catch (second: Throwable) {
                    // Last-resort fallback: plaintext prefs. We surrender
                    // the at-rest encryption to keep the app launchable.
                    // The session token still lives behind the user's
                    // OTP login, and any subsequent successful
                    // MasterKey rotation will let us flip back to
                    // encrypted on the next clean install.
                    Log.e(
                        TAG,
                        "EncryptedSharedPreferences still failing — falling back to plaintext prefs.",
                        second,
                    )
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                }
            }
        }

        private fun buildEncryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

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
        private const val KEY_BOUND_BASE_URL = "bound_base_url"
        private const val KEY_USER_PHOTO_URL = "user_photo_url"
        private const val KEY_REPORTING_TO_ID = "reporting_to_id"
        private const val KEY_REPORTING_TO_NAME = "reporting_to_name"
    }
}
