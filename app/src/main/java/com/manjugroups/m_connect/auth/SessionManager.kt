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

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var userName: String?
        get() = prefs.getString(KEY_USER_NAME, null)
        set(value) = prefs.edit().putString(KEY_USER_NAME, value).apply()

    var userPhone: String?
        get() = prefs.getString(KEY_USER_PHONE, null)
        set(value) = prefs.edit().putString(KEY_USER_PHONE, value).apply()

    var employeeId: String?
        get() = prefs.getString(KEY_EMPLOYEE_ID, null)
        set(value) = prefs.edit().putString(KEY_EMPLOYEE_ID, value).apply()

    var mustChangePassword: Boolean
        get() = prefs.getBoolean(KEY_MUST_CHANGE_PASSWORD, false)
        set(value) = prefs.edit().putBoolean(KEY_MUST_CHANGE_PASSWORD, value).apply()

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

    /**
     * Staff designation cached on login bootstrap (from `getStaffDetail`).
     * Source of truth for role-aware UI gates like the driver/executive
     * branch on the Home screen — mirrors the web's `hasDriverDesignation`
     * check in `convex/lib/mmsFleetDriverSessionLib.ts`, which does the
     * same case-insensitive "Driver" match against `staff.designation`.
     */
    var designation: String?
        get() = prefs.getString(KEY_DESIGNATION, null)
        set(value) = prefs.edit().putString(KEY_DESIGNATION, value).apply()

    /**
     * Set at login bootstrap to mirror the backend's authoritative
     * answer to "is this account a fleet driver?". The web's
     * `requireMmsFleetDriverStaff` (convex/lib/mmsFleetDriverSessionLib.ts)
     * accepts TWO paths: designation === "Driver" OR a fleetDrivers
     * row whose phone matches the staff's phone. The designation
     * string alone misses the second path — a staff IAM-provisioned
     * as a driver by adding a fleetDrivers row never flipped into
     * driver mode on the app even though the backend would happily
     * return their trips.
     *
     * Bootstrap pokes /api/mms-fleet/driver/trips once after OTP /
     * password login and writes true here when it returns success,
     * regardless of HTTP body. False / unset means either not a
     * driver or the probe failed (we fall back to designation in
     * that case).
     */
    var fleetDriverByBackend: Boolean
        get() = prefs.getBoolean(KEY_FLEET_DRIVER_BY_BACKEND, false)
        set(value) = prefs.edit().putBoolean(KEY_FLEET_DRIVER_BY_BACKEND, value).apply()

    /**
     * True when the logged-in staff is a Driver — derived from
     * designation OR the backend-probe flag above. The old Executive /
     * Driver dropdown on the Home tab let any operator flip this
     * flag, which broke the audit story (a Site Supervisor could
     * impersonate the driver view) and didn't match the web, where
     * the driver UI shows only when the staff record's designation
     * is "Driver". Read-only by design.
     */
    val isDriverMode: Boolean
        get() = fleetDriverByBackend ||
            (designation ?: "").trim().equals("Driver", ignoreCase = true)

    var isNotificationEnabled: Boolean
        get() = prefs.getBoolean(KEY_IS_NOTIFICATION_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_IS_NOTIFICATION_ENABLED, value).apply()

    var isOnDuty: Boolean
        get() = prefs.getBoolean(KEY_IS_ON_DUTY, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_ON_DUTY, value).apply()

    var onDutyType: String?
        get() = prefs.getString(KEY_ON_DUTY_TYPE, null)
        set(value) = prefs.edit().putString(KEY_ON_DUTY_TYPE, value).apply()

    var onDutyTargetName: String?
        get() = prefs.getString(KEY_ON_DUTY_TARGET_NAME, null)
        set(value) = prefs.edit().putString(KEY_ON_DUTY_TARGET_NAME, value).apply()

    var onDutyTargetId: String?
        get() = prefs.getString(KEY_ON_DUTY_TARGET_ID, null)
        set(value) = prefs.edit().putString(KEY_ON_DUTY_TARGET_ID, value).apply()

    var onDutyVehicleOwnership: String?
        get() = prefs.getString(KEY_ON_DUTY_VEHICLE_OWNERSHIP, null)
        set(value) = prefs.edit().putString(KEY_ON_DUTY_VEHICLE_OWNERSHIP, value).apply()

    var onDutyVehicleType: String?
        get() = prefs.getString(KEY_ON_DUTY_VEHICLE_TYPE, null)
        set(value) = prefs.edit().putString(KEY_ON_DUTY_VEHICLE_TYPE, value).apply()

    // geoTrips _id returned by /api/geotrack/on-duty/start. Cached so
    // Complete On Duty can pass it back to the server's complete route.
    // The server tolerates a missing id and falls back to the staff's
    // most recent active no-fieldVisit trip — this just makes the call
    // unambiguous.
    var onDutyTripId: String?
        get() = prefs.getString(KEY_ON_DUTY_TRIP_ID, null)
        set(value) = prefs.edit().putString(KEY_ON_DUTY_TRIP_ID, value).apply()

    fun clearOnDutyDetails() {
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
        val tripsJson = prefs.getString(KEY_DRIVER_TRIPS, "{}") ?: "{}"
        try {
            val obj = org.json.JSONObject(tripsJson)
            val trip = obj.optJSONObject(visitId) ?: org.json.JSONObject()
            trip.put("startKm", startKm)
            trip.put("startImage", startImagePath)
            trip.put("startTime", startTime)
            trip.put("status", "started")
            obj.put(visitId, trip)
            prefs.edit().putString(KEY_DRIVER_TRIPS, obj.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveDriverTripArrival(visitId: String) {
        val tripsJson = prefs.getString(KEY_DRIVER_TRIPS, "{}") ?: "{}"
        try {
            val obj = org.json.JSONObject(tripsJson)
            val trip = obj.optJSONObject(visitId) ?: org.json.JSONObject()
            trip.put("status", "reached")
            obj.put(visitId, trip)
            prefs.edit().putString(KEY_DRIVER_TRIPS, obj.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveDriverTripEnd(visitId: String, endKm: String, endImagePath: String, endTime: String) {
        val tripsJson = prefs.getString(KEY_DRIVER_TRIPS, "{}") ?: "{}"
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
            prefs.edit().putString(KEY_DRIVER_TRIPS, obj.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getDriverTrip(visitId: String): DriverTrip? {
        val tripsJson = prefs.getString(KEY_DRIVER_TRIPS, "{}") ?: "{}"
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
        get() = prefs.getString(KEY_BOUND_BASE_URL, null)
        set(value) = prefs.edit().putString(KEY_BOUND_BASE_URL, value).apply()

    fun hasPermission(perm: String): Boolean = isAdmin || iamPermissions.contains(perm)

    fun saveSession(token: String, name: String?, phone: String?) {
        this.token = token
        this.userName = name
        this.userPhone = phone
        this.mustChangePassword = false
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
    }
}
