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
    val status: String, // "started", "reached", "picked_from_site", "completed"
    val totalDistance: String
)

/**
 * The staff's current field-work context, surfaced in the GeoTrack
 * foreground notification so it adapts to what they're actually doing
 * (idle shift vs. On Duty vs. a CP / Site / Fleet visit) instead of a
 * single static line. `startMs` drives the notification's live elapsed
 * chronometer. Written by the flow that begins the activity and cleared
 * when it ends; read by TrackingNotification.
 */
data class FieldActivity(
    val kind: String,   // "onduty" | "cp" | "sv" | "fleet"
    val title: String,
    val sub: String?,
    val startMs: Long,
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

    // Normalized system role from IAM (super-admin / office-staff / field-staff).
    var role: String?
        get() = getCachedString(KEY_ROLE, null)
        set(value) = setCachedString(KEY_ROLE, value)

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

    /**
     * Set once at login (saveSession); consumed by the next GeoTrack bootstrap
     * sync to raise a USER_LOGIN tamper event IF the user is inside their
     * clock-in/tracking window. Lets us flag a mid-shift re-login without
     * emitting an event on every ordinary morning sign-in.
     */
    var pendingLoginEvent: Boolean
        get() = getCachedBoolean(KEY_PENDING_LOGIN_EVENT, false)
        set(value) = setCachedBoolean(KEY_PENDING_LOGIN_EVENT, value)

    var designation: String?
        get() = getCachedString(KEY_DESIGNATION, null)
        set(value) = setCachedString(KEY_DESIGNATION, value)

    /**
     * Staff department. Needed alongside designation because the two driver
     * populations are told apart by it: "Driver • Transport" drives, while
     * "Driver • Administration" runs the fleet desk.
     */
    var department: String?
        get() = getCachedString(KEY_DEPARTMENT, null)
        set(value) = setCachedString(KEY_DEPARTMENT, value)

    var fleetDriverByBackend: Boolean
        get() = getCachedBoolean(KEY_FLEET_DRIVER_BY_BACKEND, false)
        set(value) = setCachedBoolean(KEY_FLEET_DRIVER_BY_BACKEND, value)

    var externalFleetCanBill: Boolean
        get() = getCachedBoolean(KEY_EXTERNAL_FLEET_CAN_BILL, false)
        set(value) = setCachedBoolean(KEY_EXTERNAL_FLEET_CAN_BILL, value)

    /**
     * Fleet-desk staff: designation "Driver" but department "Administration".
     *
     * There are two driver populations. "Driver • Transport" actually drives and
     * gets the trip UI; "Driver • Administration" runs the fleet desk and gets
     * the Admin Fleet portal instead. Designation alone can't tell them apart,
     * which is why department is now persisted on the session.
     */
    /**
     * The external travel agency itself. The backend has no `staff` row for
     * an agency, so it synthesises this designation on the auth payload —
     * it is the only signal the client gets.
     */
    val isExternalFleetAgency: Boolean
        get() = (designation ?: "").trim()
            .equals("External Fleet", ignoreCase = true)

    val isExternalFleetStaff: Boolean
        get() = (role ?: "").trim().equals("agency_staff", ignoreCase = true) ||
            (designation ?: "").trim()
                .equals("External Fleet Staff", ignoreCase = true)

    val isExternalFleetAgencyOperator: Boolean
        get() = isExternalFleetAgency || isExternalFleetStaff

    /** Matches Travel Desk web: agency admin always bills; staff need a grant. */
    val canBillExternalFleet: Boolean
        get() = isExternalFleetAgency || (isExternalFleetStaff && externalFleetCanBill)

    /**
     * A driver created BY an external agency. Same synthesised-designation
     * trick as [isExternalFleetAgency]. Deliberately distinct from
     * [isDriverMode]: an agency driver has no staff record, no attendance and
     * no fleet permissions, so they get a stripped shell (assigned trips
     * only, no bottom nav / bell / clock-in).
     */
    val isExternalFleetDriver: Boolean
        get() = (designation ?: "").trim()
            .equals("External Fleet Driver", ignoreCase = true)

    /** Either external principal — neither has a staff record to look up. */
    val isExternalFleetPrincipal: Boolean
        get() = isExternalFleetAgencyOperator || isExternalFleetDriver

    val isFleetAdminDriver: Boolean
        get() = isDriverDesignation(designation) &&
            (department ?: "").trim().equals("Administration", ignoreCase = true)

    /**
     * A non-driver MANAGER in the fleet-owning departments (Transport /
     * Administration) — e.g. "Assistant Manager • Transport". They run the
     * fleet dispatch, sitting above Driver • Administration. Drivers are
     * excluded (they drive, not dispatch).
     */
    private fun isFleetManagerDesignation(): Boolean {
        val dept = (department ?: "").trim().lowercase(java.util.Locale.US)
        val desig = (designation ?: "").trim().lowercase(java.util.Locale.US)
        val fleetDept = dept == "transport" || dept == "administration"
        return fleetDept && !isDriverDesignation(designation) && desig.contains("manager")
    }

    /**
     * Extra capability for a fleet MANAGER (not a plain Driver • Administration
     * dispatcher): allot to external agencies AND assign the agency's own
     * drivers + vehicles — the travel-desk dispatcher powers, from inside MMS.
     * Gated on the manager designation or an explicit fleet permission.
     */
    val canAssignAgencyResources: Boolean
        get() = !isExternalFleetPrincipal &&
            (isFleetManagerDesignation() ||
                iamPermissions.contains("marketing.fleet.assign") ||
                iamPermissions.contains("marketing.fleet.manageVehicles"))

    /**
     * Who gets the internal fleet-dispatch Home (the Pending / Assigned /
     * Completed allocation queue). Two populations:
     *   1. Driver • Administration by designation ([isFleetAdminDriver]).
     *   2. Any internal staff (e.g. a Fleet Assistant Manager) who explicitly
     *      holds the fleet permission in IAM — mirroring the web, where the
     *      fleet page is gated on marketing.fleet.view / .assign.
     *
     * Permission is read from the EXPLICIT [iamPermissions] set (not
     * hasPermission), so the blanket `isAdmin` flag doesn't pull every admin
     * into the dispatcher Home — the same phantom-key caution as
     * [canViewVpDashboard]. VP / GM / super-admin users keep their company
     * dashboard even if they also hold a fleet key.
     */
    val isInternalFleetDispatcher: Boolean
        get() {
            if (isExternalFleetPrincipal) return false
            // Driver • Administration IS the designation-based dispatcher.
            if (isFleetAdminDriver) return true
            // Any OTHER driver (e.g. Driver • Transport) only drives the trips
            // assigned to them — they never get the dispatch queue / Allocate,
            // even if a fleet permission is present. They fall through to the
            // driver's own-trips Home instead.
            if (isDriverMode) return false
            if (canViewVpDashboard()) return false
            // A non-driver MANAGER in the Transport / Administration department
            // runs the fleet — they sit above Driver • Administration, so they
            // get the dispatch by designation even before an IAM key is granted.
            if (isFleetManagerDesignation()) return true
            return iamPermissions.contains("marketing.fleet.assign") ||
                iamPermissions.contains("marketing.fleet.view")
        }

    /**
     * An internal staff driver (e.g. Driver • Transport) who DRIVES fleet trips
     * assigned to them — they get the same granular Start → Reached → Picked →
     * End flow the external agency driver has, backed by the mms-fleet driver
     * endpoints. Excludes the dispatcher (Driver • Administration) and the
     * external agency principals.
     */
    val isInternalFleetDriver: Boolean
        get() = !isExternalFleetPrincipal && isDriverMode && !isFleetAdminDriver

    val isDriverMode: Boolean
        // Both driver populations get the driver screen. "Driver • Transport"
        // sees only their own assigned trips; "Driver • Administration" sees
        // the same UI plus the dispatch list and the Allocate action
        // (isFleetAdminDriver). The Admin Fleet portal is NOT for either of
        // them — it belongs to external agencies.
        get() = fleetDriverByBackend || isDriverDesignation(designation)

    /**
     * True for any driver designation, including qualified forms like
     * "Driver (Transport)" / "Driver - Transport" — not just the bare
     * "Driver". Matching only the exact word left internal transport drivers
     * out of the driver shell and the fleet-trip routes.
     */
    private fun isDriverDesignation(value: String?): Boolean {
        val d = (value ?: "").trim().lowercase(java.util.Locale.US)
        return d == "driver" ||
            d.startsWith("driver ") ||
            d.startsWith("driver(") ||
            d.startsWith("driver-")
    }

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
        // On-duty is one kind of field activity; drop the notification
        // context with it so a stale "On Duty" line can't linger.
        if (getCachedString(KEY_FIELD_ACT_KIND, null) == "onduty") clearFieldActivity()
    }

    // ── Field-activity context for the tracking notification ──
    //
    // A single source of truth the GeoTrack notification reads. Each flow
    // (On Duty / CP / Site / Fleet) sets it when the activity begins and
    // clears it when the activity ends. Kept deliberately small (4 keys) so
    // reads are cheap enough to happen on notification refresh.

    fun setFieldActivity(kind: String, title: String, sub: String?, startMs: Long) {
        setCachedString(KEY_FIELD_ACT_KIND, kind)
        setCachedString(KEY_FIELD_ACT_TITLE, title)
        setCachedString(KEY_FIELD_ACT_SUB, sub)
        memoryCache[KEY_FIELD_ACT_START_MS] = startMs
        prefs.edit().putLong(KEY_FIELD_ACT_START_MS, startMs).apply()
    }

    fun clearFieldActivity() {
        memoryCache.remove(KEY_FIELD_ACT_KIND)
        memoryCache.remove(KEY_FIELD_ACT_TITLE)
        memoryCache.remove(KEY_FIELD_ACT_SUB)
        memoryCache.remove(KEY_FIELD_ACT_START_MS)
        prefs.edit()
            .remove(KEY_FIELD_ACT_KIND)
            .remove(KEY_FIELD_ACT_TITLE)
            .remove(KEY_FIELD_ACT_SUB)
            .remove(KEY_FIELD_ACT_START_MS)
            .apply()
    }

    /** Current field activity, or null when the staff is on a plain shift. */
    fun fieldActivity(): FieldActivity? {
        val kind = getCachedString(KEY_FIELD_ACT_KIND, null) ?: return null
        val title = getCachedString(KEY_FIELD_ACT_TITLE, null) ?: return null
        val start = prefs.getLong(KEY_FIELD_ACT_START_MS, 0L)
        return FieldActivity(kind, title, getCachedString(KEY_FIELD_ACT_SUB, null), start)
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

    fun saveDriverTripPickedFromSite(visitId: String) {
        val tripsJson = getCachedString(KEY_DRIVER_TRIPS, "{}") ?: "{}"
        try {
            val obj = org.json.JSONObject(tripsJson)
            val trip = obj.optJSONObject(visitId) ?: org.json.JSONObject()
            trip.put("status", "picked_from_site")
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

    /**
     * Who sees Fleet Management → My Trips in the App Library.
     *
     * Drivers get it from their designation (the web's roster check), plus
     * super-admins, plus anyone explicitly granted the IAM key.
     *
     * Deliberately does NOT use hasPermission(): until the key is actually
     * granted to someone, that collapses to the blanket `isAdmin` flag, which
     * is set for many non-admin roles — the same phantom-key leak documented
     * on canViewVpDashboard.
     */
    fun canViewFleetMyTrips(): Boolean {
        if (isDriverMode) return true
        if ((role ?: "").trim().equals("super-admin", ignoreCase = true)) return true
        return iamPermissions.contains("marketing.fleet.myTrips.view")
    }

    /**
     * Whether the current user can mark an expired fleet trip as completed offline.
     *
     * Mirrors the server-side gating on markExpiredTripOutcomePending which
     * requires `marketing.fleet.completeOffline`. Fleet dispatchers get this
     * by designation grant; super-admins get it automatically.
     */
    fun canCompleteOfflineFleet(): Boolean {
        if (isExternalFleetAgencyOperator) return true
        if ((role ?: "").trim().equals("super-admin", ignoreCase = true)) return true
        return iamPermissions.contains("marketing.fleet.completeOffline")
    }

    /**
     * Who sees the company-wide Home dashboard (everyone else gets Today's Trip).
     *
     * The dashboard was wrongly showing for ALL users: it was gated on
     * `hasPermission("vpDashboard.view")`, but `vpDashboard.view` is a phantom
     * key (never granted to anyone), so that expression collapsed to the broad
     * `isAdmin` flag — which is set for many roles. Gate strictly instead:
     *   • super-admins (by normalized role),
     *   • VP / GM management designations,
     *   • anyone explicitly granted the dashboard IAM key (if/when it becomes
     *     a real, grantable permission).
     * Notably this NO LONGER keys off the blanket `isAdmin` flag.
     */
    fun canViewVpDashboard(): Boolean {
        if ((role ?: "").trim().equals("super-admin", ignoreCase = true)) return true
        if (iamPermissions.contains("vpDashboard.view")) return true
        return isVpOrGmDesignation(designation)
    }

    /** True for the Vice-President / General-Manager designation families
     *  (incl. Assistant/Deputy variants and Managing Director) that manage the
     *  company-wide dashboard. Matches full titles and the common abbreviations. */
    private fun isVpOrGmDesignation(designation: String?): Boolean {
        val d = (designation ?: "").lowercase(java.util.Locale.US).trim()
        if (d.isEmpty()) return false
        if (d.contains("vice president") || d.contains("vice-president") ||
            d.contains("general manager") || d.contains("managing director")
        ) return true
        // Standalone abbreviations only (so "vp"/"gm" don't match inside another
        // word): VP, AVP, GM, AGM, DGM.
        return Regex("(^|[^a-z])(vp|avp|gm|agm|dgm)([^a-z]|\$)").containsMatchIn(d)
    }

    fun saveSession(token: String, name: String?, phone: String?) {
        this.token = token
        this.userName = name
        this.userPhone = phone
        this.mustChangePassword = false
        this.boundBaseUrl = com.manjugroups.m_connect.BuildConfig.BASE_URL
        // Flag a fresh sign-in so the next tracking bootstrap can emit a
        // USER_LOGIN event when it lands inside an active clock-in window.
        this.pendingLoginEvent = true
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
        private const val KEY_ROLE = "iam_role"
        private const val KEY_PUSH_TOKEN = "push_token"
        private const val KEY_NOTIFICATION_PERMISSION_PROMPTED = "notification_permission_prompted"
        private const val KEY_STAFF_ID = "staff_id"
        private const val KEY_GEO_TRACKING_ENABLED = "geo_tracking_enabled"
        private const val KEY_GEO_CONSENT_GIVEN = "geo_consent_given"
        private const val KEY_GEO_CONSENT_DECLINED = "geo_consent_declined"
        private const val KEY_TRACKING_DEVICE_ID = "tracking_device_id"
        private const val KEY_ACTIVE_TRACKING_SESSION_ID = "active_tracking_session_id"
        private const val KEY_SHOULD_TRACK_NOW = "should_track_now"
        private const val KEY_PENDING_LOGIN_EVENT = "pending_login_event"
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
        private const val KEY_DEPARTMENT = "department"
        private const val KEY_FLEET_DRIVER_BY_BACKEND = "fleet_driver_by_backend"
        private const val KEY_EXTERNAL_FLEET_CAN_BILL = "external_fleet_can_bill"
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
        private const val KEY_FIELD_ACT_KIND = "field_act_kind"
        private const val KEY_FIELD_ACT_TITLE = "field_act_title"
        private const val KEY_FIELD_ACT_SUB = "field_act_sub"
        private const val KEY_FIELD_ACT_START_MS = "field_act_start_ms"
    }
}
