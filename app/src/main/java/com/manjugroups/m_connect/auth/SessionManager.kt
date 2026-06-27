package com.manjugroups.m_connect.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

data class DriverTrip(
    val startKm: String,
    val startImage: String,
    val startTime: String,
    val endKm: String,
    val endImage: String,
    val endTime: String,
    val status: String, // "started", "reached", "completed"
    val totalDistance: String
)

class SessionManager(context: Context) {

    private val prefs: SharedPreferences = openEncryptedPrefs(context.applicationContext)

    private fun getCachedString(key: String, defValue: String? = null): String? {
        val cached = memoryCache[key]
        if (cached != null) {
            return if (cached === NULL_PLACEHOLDER) null else cached as String
        }
        val value = prefs.getString(key, defValue)
        memoryCache[key] = value ?: NULL_PLACEHOLDER
        return value
    }

    private fun setCachedString(key: String, value: String?) {
        if (value == null) {
            memoryCache[key] = NULL_PLACEHOLDER
            prefs.edit().remove(key).apply()
        } else {
            memoryCache[key] = value
            prefs.edit().putString(key, value).apply()
        }
    }

    private fun getCachedBoolean(key: String, defValue: Boolean): Boolean {
        val cached = memoryCache[key]
        if (cached != null) {
            return cached as Boolean
        }
        val value = prefs.getBoolean(key, defValue)
        memoryCache[key] = value
        return value
    }

    private fun setCachedBoolean(key: String, value: Boolean) {
        memoryCache[key] = value
        prefs.edit().putBoolean(key, value).apply()
    }

    @Suppress("UNCHECKED_CAST")
    private fun getCachedStringSet(key: String, defValue: Set<String>): Set<String> {
        val cached = memoryCache[key]
        if (cached != null) {
            return cached as Set<String>
        }
        val value = prefs.getStringSet(key, defValue) ?: defValue
        memoryCache[key] = value
        return value
    }

    private fun setCachedStringSet(key: String, value: Set<String>) {
        memoryCache[key] = value
        prefs.edit().putStringSet(key, value).apply()
    }

    var token: String?
        get() = getCachedString(KEY_TOKEN, null)
        set(value) = setCachedString(KEY_TOKEN, value)

    var ratePerKm: String?
        get() = getCachedString(KEY_RATE_PER_KM, "")
        set(value) = setCachedString(KEY_RATE_PER_KM, value)

    var ratePackage: String?
        get() = getCachedString(KEY_RATE_PACKAGE, "")
        set(value) = setCachedString(KEY_RATE_PACKAGE, value)

    var userName: String?
        get() = getCachedString(KEY_USER_NAME, null)
        set(value) = setCachedString(KEY_USER_NAME, value)

    var userPhone: String?
        get() = getCachedString(KEY_USER_PHONE, null)
        set(value) = setCachedString(KEY_USER_PHONE, value)

    var employeeId: String?
        get() = getCachedString(KEY_EMPLOYEE_ID, null)
        set(value) = setCachedString(KEY_EMPLOYEE_ID, value)

    var mustChangePassword: Boolean
        get() = getCachedBoolean(KEY_MUST_CHANGE_PASSWORD, false)
        set(value) = setCachedBoolean(KEY_MUST_CHANGE_PASSWORD, value)

    var userPhotoUrl: String?
        get() = getCachedString(KEY_USER_PHOTO_URL, null)
        set(value) = setCachedString(KEY_USER_PHOTO_URL, value)

    var iamPermissions: Set<String>
        get() = getCachedStringSet(KEY_IAM_PERMISSIONS, emptySet())
        set(value) = setCachedStringSet(KEY_IAM_PERMISSIONS, value)

    var isAdmin: Boolean
        get() = getCachedBoolean(KEY_IS_ADMIN, false)
        set(value) = setCachedBoolean(KEY_IS_ADMIN, value)

    var reportingToId: String?
        get() = getCachedString(KEY_REPORTING_TO_ID, null)
        set(value) = setCachedString(KEY_REPORTING_TO_ID, value)

    var reportingToName: String?
        get() = getCachedString(KEY_REPORTING_TO_NAME, null)
        set(value) = setCachedString(KEY_REPORTING_TO_NAME, value)

    var pushToken: String?
        get() = getCachedString(KEY_PUSH_TOKEN, null)
        set(value) = setCachedString(KEY_PUSH_TOKEN, value)

    var notificationPermissionPrompted: Boolean
        get() = getCachedBoolean(KEY_NOTIFICATION_PERMISSION_PROMPTED, false)
        set(value) = setCachedBoolean(KEY_NOTIFICATION_PERMISSION_PROMPTED, value)

    var staffId: String?
        get() = getCachedString(KEY_STAFF_ID, null)
        set(value) = setCachedString(KEY_STAFF_ID, value)

    var geoTrackingEnabled: Boolean
        get() = getCachedBoolean(KEY_GEO_TRACKING_ENABLED, false)
        set(value) = setCachedBoolean(KEY_GEO_TRACKING_ENABLED, value)

    var geoConsentGiven: Boolean
        get() = getCachedBoolean(KEY_GEO_CONSENT_GIVEN, false)
        set(value) = setCachedBoolean(KEY_GEO_CONSENT_GIVEN, value)

    var geoConsentDeclined: Boolean
        get() = getCachedBoolean(KEY_GEO_CONSENT_DECLINED, false)
        set(value) = setCachedBoolean(KEY_GEO_CONSENT_DECLINED, value)

    var trackingDeviceId: String
        get() {
            val existing = getCachedString(KEY_TRACKING_DEVICE_ID, null)
            if (!existing.isNullOrBlank()) return existing
            val created = UUID.randomUUID().toString()
            setCachedString(KEY_TRACKING_DEVICE_ID, created)
            return created
        }
        set(value) = setCachedString(KEY_TRACKING_DEVICE_ID, value)

    var activeTrackingSessionId: String?
        get() = getCachedString(KEY_ACTIVE_TRACKING_SESSION_ID, null)
        set(value) = setCachedString(KEY_ACTIVE_TRACKING_SESSION_ID, value)

    var shouldTrackNow: Boolean
        get() = getCachedBoolean(KEY_SHOULD_TRACK_NOW, false)
        set(value) = setCachedBoolean(KEY_SHOULD_TRACK_NOW, value)

    var designation: String?
        get() = getCachedString(KEY_DESIGNATION, null)
        set(value) = setCachedString(KEY_DESIGNATION, value)

    var fleetDriverByBackend: Boolean
        get() = getCachedBoolean(KEY_FLEET_DRIVER_BY_BACKEND, false)
        set(value) = setCachedBoolean(KEY_FLEET_DRIVER_BY_BACKEND, value)

    val isDriverMode: Boolean
        get() = fleetDriverByBackend ||
            (designation ?: "").trim().equals("Driver", ignoreCase = true)

    var isNotificationEnabled: Boolean
        get() = getCachedBoolean(KEY_IS_NOTIFICATION_ENABLED, true)
        set(value) = setCachedBoolean(KEY_IS_NOTIFICATION_ENABLED, value)

    var isOnDuty: Boolean
        get() = getCachedBoolean(KEY_IS_ON_DUTY, false)
        set(value) = setCachedBoolean(KEY_IS_ON_DUTY, value)

    var onDutyType: String?
        get() = getCachedString(KEY_ON_DUTY_TYPE, null)
        set(value) = setCachedString(KEY_ON_DUTY_TYPE, value)

    var onDutyTargetName: String?
        get() = getCachedString(KEY_ON_DUTY_TARGET_NAME, null)
        set(value) = setCachedString(KEY_ON_DUTY_TARGET_NAME, value)

    var onDutyTargetId: String?
        get() = getCachedString(KEY_ON_DUTY_TARGET_ID, null)
        set(value) = setCachedString(KEY_ON_DUTY_TARGET_ID, value)

    var onDutyVehicleOwnership: String?
        get() = getCachedString(KEY_ON_DUTY_VEHICLE_OWNERSHIP, null)
        set(value) = setCachedString(KEY_ON_DUTY_VEHICLE_OWNERSHIP, value)

    var onDutyVehicleType: String?
        get() = getCachedString(KEY_ON_DUTY_VEHICLE_TYPE, null)
        set(value) = setCachedString(KEY_ON_DUTY_VEHICLE_TYPE, value)

    // geoTrips _id returned by /api/geotrack/on-duty/start. Cached so
    // Complete On Duty can pass it back to the server's complete route.
    // The server tolerates a missing id and falls back to the staff's
    // most recent active no-fieldVisit trip — this just makes the call
    // unambiguous.
    var onDutyTripId: String?
        get() = prefs.getString(KEY_ON_DUTY_TRIP_ID, null)
        set(value) = prefs.edit().putString(KEY_ON_DUTY_TRIP_ID, value).apply()

    fun clearOnDutyDetails() {
        memoryCache.remove(KEY_IS_ON_DUTY)
        memoryCache.remove(KEY_ON_DUTY_TYPE)
        memoryCache.remove(KEY_ON_DUTY_TARGET_NAME)
        memoryCache.remove(KEY_ON_DUTY_TARGET_ID)
        memoryCache.remove(KEY_ON_DUTY_VEHICLE_OWNERSHIP)
        memoryCache.remove(KEY_ON_DUTY_VEHICLE_TYPE)
        prefs.edit()
            .remove(KEY_IS_ON_DUTY)
            .remove(KEY_ON_DUTY_TYPE)
            .remove(KEY_ON_DUTY_TARGET_NAME)
            .remove(KEY_ON_DUTY_TARGET_ID)
            .remove(KEY_ON_DUTY_VEHICLE_OWNERSHIP)
            .remove(KEY_ON_DUTY_VEHICLE_TYPE)
            .remove(KEY_ON_DUTY_TRIP_ID)
            .apply()
    }

    fun saveDriverTripStart(visitId: String, startKm: String, startImagePath: String, startTime: String) {
        val tripsJson = getCachedString(KEY_DRIVER_TRIPS, "{}") ?: "{}"
        try {
            val obj = org.json.JSONObject(tripsJson)
            val trip = obj.optJSONObject(visitId) ?: org.json.JSONObject()
            trip.put("startKm", startKm)
            trip.put("startImage", startImagePath)
            trip.put("startTime", startTime)
            trip.put("status", "started")
            obj.put(visitId, trip)
            setCachedString(KEY_DRIVER_TRIPS, obj.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveDriverTripArrival(visitId: String) {
        val tripsJson = getCachedString(KEY_DRIVER_TRIPS, "{}") ?: "{}"
        try {
            val obj = org.json.JSONObject(tripsJson)
            val trip = obj.optJSONObject(visitId) ?: org.json.JSONObject()
            trip.put("status", "reached")
            obj.put(visitId, trip)
            setCachedString(KEY_DRIVER_TRIPS, obj.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveDriverTripEnd(visitId: String, endKm: String, endImagePath: String, endTime: String) {
        val tripsJson = getCachedString(KEY_DRIVER_TRIPS, "{}") ?: "{}"
        try {
            val obj = org.json.JSONObject(tripsJson)
            val trip = obj.optJSONObject(visitId) ?: org.json.JSONObject()
            trip.put("endKm", endKm)
            trip.put("endImage", endImagePath)
            trip.put("endTime", endTime)
            trip.put("status", "completed")
            
            // Calculate total distance if both readings are numeric
            val startKmStr = trip.optString("startKm", "")
            val startVal = startKmStr.toDoubleOrNull()
            val endVal = endKm.toDoubleOrNull()
            if (startVal != null && endVal != null) {
                val diff = endVal - startVal
                trip.put("totalDistance", String.format(java.util.Locale.getDefault(), "%.1f", diff))
            } else {
                trip.put("totalDistance", "0.0")
            }
            
            obj.put(visitId, trip)
            setCachedString(KEY_DRIVER_TRIPS, obj.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getDriverTrip(visitId: String): DriverTrip? {
        val tripsJson = getCachedString(KEY_DRIVER_TRIPS, "{}") ?: "{}"
        try {
            val obj = org.json.JSONObject(tripsJson)
            if (!obj.has(visitId)) return null
            val trip = obj.getJSONObject(visitId)
            return DriverTrip(
                startKm = trip.optString("startKm", ""),
                startImage = trip.optString("startImage", ""),
                startTime = trip.optString("startTime", ""),
                endKm = trip.optString("endKm", ""),
                endImage = trip.optString("endImage", ""),
                endTime = trip.optString("endTime", ""),
                status = trip.optString("status", ""),
                totalDistance = trip.optString("totalDistance", "")
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    val isLoggedIn: Boolean
        get() = token != null

    val bearerToken: String
        get() = "Bearer $token"

    var boundBaseUrl: String?
        get() = getCachedString(KEY_BOUND_BASE_URL, null)
        set(value) = setCachedString(KEY_BOUND_BASE_URL, value)

    fun hasPermission(perm: String): Boolean = isAdmin || iamPermissions.contains(perm)

    fun saveSession(token: String, name: String?, phone: String?) {
        this.token = token
        this.userName = name
        this.userPhone = phone
        this.mustChangePassword = false
        this.boundBaseUrl = com.manjugroups.m_connect.BuildConfig.BASE_URL
    }

    fun clearSession() {
        memoryCache.clear()
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
        if (token != null) {
            if (bound == null) {
                // First time running with this guard or legacy session.
                // Bind the current URL so we can detect future changes,
                // but do NOT clear the session.
                boundBaseUrl = current
                return false
            }
            if (bound != current) {
                clearSession()
                return true
            }
        }
        return false
    }

    var hasSeenEdgeQrTooltip: Boolean
        get() = getCachedBoolean("has_seen_edge_qr_tooltip", false)
        set(value) = setCachedBoolean("has_seen_edge_qr_tooltip", value)

    companion object {
        private const val TAG = "SessionManager"
        private const val PREFS_NAME = "mconnect_session"

        @Volatile
        private var cachedPrefs: SharedPreferences? = null

        private val memoryCache = java.util.concurrent.ConcurrentHashMap<String, Any>()
        private val NULL_PLACEHOLDER = Any()

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
            cachedPrefs?.let { return it }
            synchronized(this) {
                cachedPrefs?.let { return it }
                val instance = try {
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
                cachedPrefs = instance
                return instance
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
        private const val KEY_EMPLOYEE_ID = "employee_id"
        private const val KEY_MUST_CHANGE_PASSWORD = "must_change_password"
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
        // KEY_IS_DRIVER_MODE retained on purpose — older installs may
        // have a stale "true" cached from the dropdown era. The current
        // getter no longer reads it; the next bootstrap re-derives
        // driver mode from `designation`. Leave the constant here so
        // the migration path stays grep-able.
        private const val KEY_IS_DRIVER_MODE = "is_driver_mode"
        private const val KEY_DESIGNATION = "designation"
        private const val KEY_FLEET_DRIVER_BY_BACKEND = "fleet_driver_by_backend"
        private const val KEY_DRIVER_TRIPS = "driver_trips"
        private const val KEY_IS_NOTIFICATION_ENABLED = "is_notification_enabled"
        private const val KEY_IS_ON_DUTY = "is_on_duty"
        private const val KEY_ON_DUTY_TYPE = "on_duty_type"
        private const val KEY_ON_DUTY_TARGET_NAME = "on_duty_target_name"
        private const val KEY_ON_DUTY_TARGET_ID = "on_duty_target_id"
        private const val KEY_ON_DUTY_VEHICLE_OWNERSHIP = "on_duty_vehicle_ownership"
        private const val KEY_ON_DUTY_VEHICLE_TYPE = "on_duty_vehicle_type"
        private const val KEY_ON_DUTY_TRIP_ID = "on_duty_trip_id"
        private const val KEY_RATE_PER_KM = "rate_per_km"
        private const val KEY_RATE_PACKAGE = "rate_package"
    }
}
